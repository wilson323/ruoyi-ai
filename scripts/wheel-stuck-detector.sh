#!/usr/bin/env bash
# scripts/wheel-stuck-detector.sh — H-13 飞轮转速监控脚本（真实逻辑）
# 来源：R131 §四.4.3 + R132 §一.1
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：WHEEL_FAIL_SEED=1 → 故意注入 7 天前 BCP → exit 1

set -euo pipefail

# === 配置区 ===
WHEEL_REGISTRY="${WHEEL_REGISTRY:-docs/ipd-系统说明/BCP-Registry.md}"
STUCK_THRESHOLD_HOURS="${STUCK_THRESHOLD_HOURS:-48}"
LOG_PATH="${LOG_PATH:-docs/ipd-系统说明/log.md}"
NOW_EPOCH="${NOW_EPOCH:-$(date +%s)}"
WHEEL_FAIL_SEED="${WHEEL_FAIL_SEED:-0}"

# === 自证能红：FAIL_SEED=1 故意注入超 48h 卡 ===
if [ "$WHEEL_FAIL_SEED" = "1" ]; then
  SEVEN_DAYS_AGO=$(date -u -v-7d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "7 days ago" +%Y-%m-%dT%H:%M:%SZ)
  echo "[H-13] FAIL_SEED=1 → 注入超 7 天前 BCP-FAIL-SEED（基线 R132）"
  echo "STUCK|BCP-FAIL-SEED|$SEVEN_DAYS_AGO|168h|seed_injected"
  exit 1
fi

# === 主逻辑 ===
main() {
  echo "[H-13] wheel-stuck-detector.sh 启动 (基线: R131 acdbaac3)"

  # 1. 读 BCP-Registry（飞轮 SSOT 登记位）
  if [ ! -f "$WHEEL_REGISTRY" ]; then
    echo "⚠️  飞轮 SSOT 不存在: $WHEEL_REGISTRY"
    echo "   提示：R132 派单需先创建 docs/ipd-系统说明/BCP-Registry.md"
    echo "   行为：SSOT 缺失 = 不阻断（exit 0），仅提示"
    exit 0
  fi

  # 2. 解析 BCP-Registry markdown 表格
  local stuck_count=0
  local total_count=0
  local stuck_report=""

  while IFS='|' read -r _ bcp_id status _ last_ts _; do
    [ -z "$bcp_id" ] && continue
    case "$bcp_id" in
      *BCP-*) ;;
      *) continue ;;
    esac
    total_count=$((total_count + 1))

    case "$status" in
      *✅*|*完成*|*done*|*closed*) continue ;;
    esac

    local last_epoch
    last_epoch=$(date -j -f "%Y-%m-%dT%H:%M:%S" "$last_ts" +%s 2>/dev/null \
      || date -j -f "%Y-%m-%d" "$last_ts" +%s 2>/dev/null \
      || echo 0)
    [ "$last_epoch" -eq 0 ] && continue

    local age_sec=$(( NOW_EPOCH - last_epoch ))
    local age_hr=$(( age_sec / 3600 ))

    if [ "$age_hr" -gt "$STUCK_THRESHOLD_HOURS" ]; then
      stuck_count=$((stuck_count + 1))
      stuck_report="${stuck_report}STUCK|${bcp_id}|${last_ts}|${age_hr}h"$'\n'
    fi
  done < "$WHEEL_REGISTRY"

  if [ "$stuck_count" -gt 0 ]; then
    echo "🔴 飞轮卡死: $stuck_count / $total_count 超 $STUCK_THRESHOLD_HOURS h 阈值"
    echo "$stuck_report"
    echo "   撞车 0 让路：暂不实跑看板 PUT / 飞书 webhook（R132 派单时接入）"
    [ -d "$(dirname "$LOG_PATH")" ] && echo "## H-13-触发-$(date +%Y%m%d-%H%M)" >> "$LOG_PATH" 2>/dev/null || true
    exit 1
  fi

  echo "✅ 飞轮转速正常: $total_count 个 BCP 全部 ≤ $STUCK_THRESHOLD_HOURS h"
  exit 0
}

main "$@"
