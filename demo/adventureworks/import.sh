#!/usr/bin/env bash
#
# 导入 AdventureWorks（MySQL 版）。
#
#   ./demo/adventureworks/import.sh /path/to/AdventureWorks-MySQL-with-FKs.zip
#
# 原始 dump 的外键定义有缺陷，直接导入会在 specialofferproduct 上中断。
# 问数不需要外键，这里删掉 FK 子句再导。
set -euo pipefail

ZIP="${1:-$HOME/Downloads/AdventureWorks-MySQL-with-FKs.zip}"
DB="${DB:-adventureworks}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

[ -f "$ZIP" ] || { echo "找不到 $ZIP" >&2; exit 2; }

echo "==> 解压"
unzip -o -q "$ZIP" -d "$WORK"
SQL="$(find "$WORK" -name '*.sql' | head -1)"

echo "==> 剔除外键子句"
# 1) CREATE TABLE 里的 FOREIGN KEY / my_fk_* 行
# 2) ALTER TABLE ... ADD CONSTRAINT my_fk_* 整条
# 3) 删行后留下的尾部多余逗号 ", )"
python3 - "$SQL" "$WORK/clean.sql" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="latin-1").read()
out = [l for l in s.split("\n")
       if not re.match(r'\s*(CONSTRAINT\s+`?\w+`?\s+)?FOREIGN KEY', l)
       and not re.match(r'\s*KEY\s+`my_fk_\d+`', l)]
s = "\n".join(out)
s = re.sub(r'ALTER TABLE `\w+`\s+ADD CONSTRAINT `my_fk_\d+`[^;]*;', '', s)
s = re.sub(r',(\s*)\)(\s*ENGINE=)', r'\1)\2', s)
open(dst, "w", encoding="latin-1").write(s)
PY

echo "==> 建库并导入（约 15 秒）"
mysql -uroot -p -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` DEFAULT CHARSET utf8mb4;"
mysql -uroot -p --default-character-set=latin1 "$DB" < "$WORK/clean.sql"

echo "==> 完成"
mysql -uroot -p -N -e "
SELECT CONCAT('表数: ', COUNT(*)) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB';
SELECT CONCAT('订单: ', COUNT(*)) FROM \`$DB\`.salesorderheader;
SELECT CONCAT('明细: ', COUNT(*)) FROM \`$DB\`.salesorderdetail;"
