#!/usr/bin/env bash
#
# 跑问数评测集。见 eval/README.md。
#
#   ./eval/run.sh
#   ./eval/run.sh --limit 5
#   ./eval/run.sh --min-pass-rate 0.8
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPRING_ARGS=""
LIMIT=""
REPORT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --limit)          LIMIT="$2"; SPRING_ARGS="$SPRING_ARGS --chatdata.eval.limit=$2"; shift 2 ;;
    --path)           SPRING_ARGS="$SPRING_ARGS --chatdata.eval.path=$2"; shift 2 ;;
    --min-pass-rate)  SPRING_ARGS="$SPRING_ARGS --chatdata.eval.min-pass-rate=$2"; shift 2 ;;
    --report)         REPORT="$2"; SPRING_ARGS="$SPRING_ARGS --chatdata.eval.report=$2"; shift 2 ;;
    -h|--help)
      sed -n '3,9p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "未知参数：$1（可用：--limit / --path / --min-pass-rate / --report）" >&2
      exit 2
      ;;
  esac
done

# 只跑前 N 条时默认写到另一个报告文件，避免把全量基线覆盖成一份"冒烟报告"
if [ -n "$LIMIT" ] && [ -z "$REPORT" ]; then
  SPRING_ARGS="$SPRING_ARGS --chatdata.eval.report=target/eval-report-limit.json"
fi

# 评测跑的是引擎的当前源码，先把最新的 ask-engine 装进本地仓库，
# 否则 spring-boot:run 会用到 ~/.m2 里的旧快照
cd "$ROOT"
mvn -q -pl chatdata-agent-ask-engine -am install -DskipTests

cd "$ROOT/chatdata-agent-ask"

# 本地开发从统一的环境文件取密钥；CI 里直接注入环境变量即可
if [ -f "$HOME/.chatdata-agent.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$HOME/.chatdata-agent.env"
  set +a
fi

if [ -n "$SPRING_ARGS" ]; then
  exec mvn spring-boot:run -Dspring-boot.run.profiles=eval \
    -Dspring-boot.run.arguments="$SPRING_ARGS"
else
  exec mvn spring-boot:run -Dspring-boot.run.profiles=eval
fi
