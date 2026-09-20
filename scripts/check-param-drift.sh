#!/usr/bin/env bash
# check-param-drift.sh
# R46-A4 治本：参数漂移自动门禁
# 检查 ipd_dev 真库 system_configs 表中 config_value != default_value 的行
# 用于防止「种子重灌脚本覆盖默认值」或「运营手改忘归位」类漂移再现
#
# 用法:
#   ./scripts/check-param-drift.sh
#   ./scripts/check-param-drift.sh --cnf /path/to/mysql.cnf --db ipd_dev
#   ./scripts/check-param-drift.sh --json-only
#
# 退出码:
#   0  = 无漂移(所有 config_value == default_value)
#   1  = 检测到漂移(列出漂移行)
#   2  = 脚本错误(凭证不存在 / mysqld 未起)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CNF="${CNF:-$REPO_ROOT/.codex/ipd-dev/config/mysql-client.cnf}"
DB="${DB:-ipd_dev}"
JSON_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cnf) CNF="$2"; shift 2 ;;
    --db) DB="$2"; shift 2 ;;
    --json-only) JSON_ONLY=1; shift ;;
    *) echo "[check-param-drift] unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$CNF" ]]; then
  echo "[check-param-drift] ❌ MySQL cnf not found: $CNF" >&2
  exit 2
fi

if [[ "$JSON_ONLY" -eq 0 ]]; then
  echo "==== 参数漂移自检（R46-A4 治本门禁）===="
  echo "cnf: $CNF"
  echo "db: $DB"
  echo
fi

# 真活 SELECT: config_value != default_value 即视为漂移
ROWS=$(mysql --defaults-file="$CNF" "$DB" -N -e "
SELECT config_key, config_value, default_value,
       UNIX_TIMESTAMP(update_time) AS update_unix,
       update_by,
       description
FROM system_configs
WHERE config_value != default_value
ORDER BY config_key;
" 2>/dev/null)

if [[ -z "$ROWS" ]]; then
  if [[ "$JSON_ONLY" -eq 1 ]]; then
    echo '{"check":"param-drift","drift_count":0,"rows":[],"pass":true}'
  else
    echo "✅ 无漂移（所有 config_value == default_value）"
  fi
  exit 0
fi

COUNT=$(echo "$ROWS" | wc -l | tr -d ' ')
NOW=$(date '+%Y-%m-%d %H:%M:%S')

if [[ "$JSON_ONLY" -eq 1 ]]; then
  # 输出 JSON（供 CI 消费）
  echo "{"
  echo "  \"check\": \"param-drift\","
  echo "  \"drift_count\": $COUNT,"
  echo "  \"checked_at\": \"$NOW\","
  echo "  \"rows\": ["
  FIRST=1
  while IFS=$'\t' read -r key value default_value update_unix update_by desc; do
    if [[ $FIRST -eq 0 ]]; then echo ","; fi
    FIRST=0
    # JSON escape for description
    desc_escaped=$(echo "$desc" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().rstrip()))' 2>/dev/null || echo "\"$desc\"")
    cat <<EOF
    {"config_key":"$key","config_value":"$value","default_value":"$default_value","update_unix":$update_unix,"update_by":$update_by,"description":$desc_escaped}
EOF
  done <<< "$ROWS"
  echo "  ],"
  echo "  \"pass\": false"
  echo "}"
else
  echo "❌ 检测到 $COUNT 行漂移（config_value != default_value）："
  echo
  printf "%-30s | %-15s | %-15s | %-19s | %-10s | %s\n" "config_key" "current" "default" "update_time" "update_by" "description"
  printf -- "-%.0s" {1..110}; echo
  while IFS=$'\t' read -r key value default_value update_unix update_by desc; do
    # update_unix → human readable
    human_time=$(date -r "$update_unix" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$update_unix")
    printf "%-30s | %-15s | %-15s | %-19s | %-10s | %s\n" "$key" "$value" "$default_value" "$human_time" "$update_by" "$desc"
  done <<< "$ROWS"
  echo
  echo "建议处置："
  echo "  1. 若是运营手动漂移:超管登录 /ipd/admin/config 编辑回 default_value"
  echo "  2. 若是种子脚本漂移:检查 docs/script/sql/update/** 是否硬编码 config_value"
  echo "  3. 若是系统通道漂移(update_by=0/-1):追查 SystemConfigService.update 调用栈"
fi

exit 1
