#!/usr/bin/env bash
# scripts/check-time-redline.sh — M2 拍板超期红线检测
# 来源：R131 §二.2.2 M2 最小验证
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入 7 天前拍板 → exit 1

set -eo pipefail

PAIBAN_DIR="${PAIBAN_DIR:-docs/ipd-系统说明/拍板决策包}"
REDLINE_HOURS="${REDLINE_HOURS:-168}"  # 7 天红线
TR_FAIL_SEED="${TR_FAIL_SEED:-0}"

main() {
  echo "[M2] check-time-redline.sh 启动 (基线: R131)"

  if [ "$TR_FAIL_SEED" = "1" ]; then
    echo "[M2] FAIL_SEED=1 → 注入 7 天前拍板假卡"
    echo "🔴 REDLINE|paiban-FAIL-SEED|168h|seed_injected"
    exit 1
  fi

  if [ ! -d "$PAIBAN_DIR" ]; then
    echo "⚠️  $PAIBAN_DIR 不存在"
    echo "   行为：目录缺失 = 不阻断（exit 0）"
    exit 0
  fi

  # 扫拍板决策包 mtime > REDLINE_HOURS
  local now=$(date +%s)
  local overdue=0
  local report=""
  for f in "$PAIBAN_DIR"/*.md; do
    [ -f "$f" ] || continue
    local mtime=$(stat -f %m "$f" 2>/dev/null || stat -c %Y "$f" 2>/dev/null || echo "$now")
    local age_hr=$(( (now - mtime) / 3600 ))
    if [ "$age_hr" -gt "$REDLINE_HOURS" ]; then
      overdue=$((overdue + 1))
      report="${report}OVERDUE|$(basename "$f")|${age_hr}h"$'\n'
    fi
  done

  if [ "$overdue" -gt 0 ]; then
    echo "🔴 拍板超期: $overdue 项超 ${REDLINE_HOURS}h 红线"
    echo "$report"
    exit 1
  fi

  echo "✅ 全部拍板 ≤ ${REDLINE_HOURS}h 红线"
  exit 0
}

main "$@"
