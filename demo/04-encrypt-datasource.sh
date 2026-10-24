#!/usr/bin/env bash
#
# 给演示数据源（ask_datasource.id = 120）加密密码。
#
# 03-ask-domain.sql 是"先删后插"，每次重跑都会把 password_enc 清成 NULL。
# 重跑那个脚本之后**必须**再跑本脚本，否则连接池建不起来，
# 表现为 "Connection is not available, total=0"——那个报错完全指不到病根。
#
# 用法：
#   ./demo/04-encrypt-datasource.sh
#
# 依赖环境变量（可从 ~/.chatdata-agent.env 加载）：
#   CHATDATA_ENGINE_DS_SECRET
#   DB_PASSWORD（默认 root，是被加密的库口令）
#
set -euo pipefail

RAW_USER="${DB_USER:-root}"
RAW_PASSWORD="${DB_PASSWORD:-root}"
DATASOURCE_ID=120
CONFIG_DB="${CONFIG_DB:-chatdata_ask}"

if [ -z "${CHATDATA_ENGINE_DS_SECRET:-}" ] && [ -f "$HOME/.chatdata-agent.env" ]; then
  # shellcheck disable=SC1090
  . "$HOME/.chatdata-agent.env"
fi
if [ -z "${CHATDATA_ENGINE_DS_SECRET:-}" ]; then
  echo "缺少 CHATDATA_ENGINE_DS_SECRET" >&2
  exit 2
fi

DB_USER="$RAW_USER" DB_PASSWORD="$RAW_PASSWORD" DATASOURCE_ID="$DATASOURCE_ID" \
CONFIG_DB="$CONFIG_DB" python3 - <<'PY'
import base64, hashlib, os, pymysql
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

secret = os.environ["CHATDATA_ENGINE_DS_SECRET"]
key = hashlib.sha256(secret.encode()).digest()
iv = os.urandom(12)
enc = base64.b64encode(
    iv + AESGCM(key).encrypt(iv, os.environ["DB_PASSWORD"].encode(), None)
).decode()

conn = pymysql.connect(host="127.0.0.1", user=os.environ["DB_USER"],
                       password=os.environ["DB_PASSWORD"],
                       database=os.environ["CONFIG_DB"])
with conn.cursor() as cur:
    n = cur.execute("UPDATE ask_datasource SET password_enc=%s WHERE id=%s",
                    (enc, os.environ["DATASOURCE_ID"]))
conn.commit()
conn.close()
print(f"数据源 {os.environ['DATASOURCE_ID']} 密码已加密写入（影响 {n} 行）")
PY
