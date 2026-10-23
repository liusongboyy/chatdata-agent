#!/usr/bin/env python3
"""
把评测夹具数据源（ask_datasource.id = 100）的密码加密写入。

单独一个脚本而不是塞进 prepare.sh 的内联 heredoc：
  03-eval-domains.sql 是「先删后插」，每次重跑都会把 password_enc 清成 NULL。
  重跑那个脚本之后**必须**再跑本脚本，否则连接池建不起来，
  评测会以 "Connection is not available, total=0" 失败——那个报错完全指不到病根。

口令与密钥都从环境变量取：
  DB_PASSWORD                被测库的口令（默认 root）
  CHATDATA_ENGINE_DS_SECRET  与 ask 启动时一致
"""
from __future__ import annotations

import base64
import hashlib
import os
import sys

import pymysql
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

DATASOURCE_ID = 100
CONFIG_DB = os.environ.get("CONFIG_DB", "jimureport")
DB_USER = os.environ.get("DB_USER", "root")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "root")


def encrypt(secret: str, plaintext: str) -> str:
    """与 PasswordCipher 完全一致：sha256(secret) 作密钥，base64(IV || 密文 || tag)。"""
    key = hashlib.sha256(secret.encode("utf-8")).digest()
    iv = os.urandom(12)
    return base64.b64encode(iv + AESGCM(key).encrypt(iv, plaintext.encode("utf-8"), None)).decode("ascii")


def main() -> int:
    secret = os.environ.get("CHATDATA_ENGINE_DS_SECRET", "")
    if not secret:
        print("缺少 CHATDATA_ENGINE_DS_SECRET", file=sys.stderr)
        return 2

    conn = pymysql.connect(host="127.0.0.1", user=DB_USER, password=DB_PASSWORD, database=CONFIG_DB)
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM ask_datasource WHERE id = %s", (DATASOURCE_ID,))
        if cur.fetchone()[0] == 0:
            print(f"数据源 {DATASOURCE_ID} 不存在，先跑 03-eval-domains.sql", file=sys.stderr)
            return 2
        cur.execute("UPDATE ask_datasource SET password_enc = %s WHERE id = %s",
                    (encrypt(secret, DB_PASSWORD), DATASOURCE_ID))
    conn.commit()
    conn.close()
    print(f"数据源 {DATASOURCE_ID} 的密码已加密写入")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
