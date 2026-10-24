#!/usr/bin/env bash
#
# 构建评测夹具库 chatdata_eval，并写入三个评测域的配置。
#
#   ./eval/fixture/prepare.sh
#
# 可重复执行：每次都会重建 chatdata_eval（业务库不受影响），
# 评测域配置按固定 id 先删后插。
#
# 需要：
#   · 本机 MySQL（默认 root/root，可用 DB_USER / DB_PASSWORD 覆盖）
#   · 环境变量 CHATDATA_ENGINE_DS_SECRET（与 ask 启动时一致，用于加密数据源密码）
#   · python3（生成工单数据；只用标准库）
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$ROOT/eval/fixture"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"
CONFIG_DB="${CONFIG_DB:-chatdata_ask}"

export MYSQL_PWD="$DB_PASSWORD"
MYSQL=(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER")

if [ -z "${CHATDATA_ENGINE_DS_SECRET:-}" ] && [ -f "$HOME/.chatdata-agent.env" ]; then
  # shellcheck disable=SC1090
  . "$HOME/.chatdata-agent.env"
fi
if [ -z "${CHATDATA_ENGINE_DS_SECRET:-}" ]; then
  echo "缺少 CHATDATA_ENGINE_DS_SECRET：数据源密码要用它加密，评测应用也要用它解密" >&2
  exit 2
fi

echo "==> 重建 chatdata_eval"
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`chatdata_eval\`"
"${MYSQL[@]}" < "$FIXTURE/01-schema.sql"

echo "==> 装载保险数据快照"
"${MYSQL[@]}" chatdata_eval < "$FIXTURE/02-insurance-data.sql"

echo "==> 生成并装载工单数据（确定性种子）"
python3 "$FIXTURE/generate-ticket-data.py" | "${MYSQL[@]}" chatdata_eval

echo "==> 写入评测域配置（数据源 100 / 域 2001-2003）"
"${MYSQL[@]}" < "$FIXTURE/03-eval-domains.sql"

echo "==> 加密数据源密码"
"$FIXTURE/set-datasource-password.py"

echo "==> 复核"
"${MYSQL[@]}" -N -e "
SELECT 'ticket', COUNT(*) FROM chatdata_eval.ticket
UNION ALL SELECT 'ticket_comment', COUNT(*) FROM chatdata_eval.ticket_comment
UNION ALL SELECT 'ticket_flow', COUNT(*) FROM chatdata_eval.ticket_flow
UNION ALL SELECT 'sla_violation', COUNT(*) FROM chatdata_eval.sla_violation
UNION ALL SELECT 'knowledge_article', COUNT(*) FROM chatdata_eval.knowledge_article
UNION ALL SELECT 'ins_policy', COUNT(*) FROM chatdata_eval.ins_policy
UNION ALL SELECT 'ins_claim', COUNT(*) FROM chatdata_eval.ins_claim
UNION ALL SELECT 'eval_domains', COUNT(*) FROM $CONFIG_DB.ask_domain WHERE id IN (2001,2002,2003);
"

echo "完成。跑评测：./eval/run.sh"
