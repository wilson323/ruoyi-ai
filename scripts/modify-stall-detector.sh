#!/usr/bin/env bash
# scripts/modify-stall-detector.sh — H-14 GEP Modify-stall 检测脚本（真实逻辑）
# 来源：R131 §二.2.3 GEP 8 阶段修复路径 + R132 §一.2
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：MODIFY_FAIL_SEED=1 → 故意注入 mtime 7 天前卡 → exit 1

set -euo pipefail

# === 配置区 ===
EVOLVER_DIR="${EVOLVER_DIR:-.evolver}"
STALL_THRESHOLD_HOURS="${STALL_THRESHOLD_HOURS:-48}"
FAILURES_JSONL="${FAILURES_JSONL:-.evolver/failures.jsonl}"
MODIFY_FAIL_SEED="${MODIFY_FAIL_SEED:-0}"

# === 自证能红：FAIL_SEED=1 注入 mtime 7 天前卡 ===
if [ "$MODIFY_FAIL_SEED" = "1" ]; then
  echo "[H-14] FAIL_SEED=1 → 注入 7 天前 modify-stall 假卡（基线 R132）"
  TS=$(date +%s)
  mkdir -p .evolver
  SEED_FILE=".evolver/.fail-seed-card.md"
  touch -t "$(date -v-7d +%Y%m%d%H%M 2>/dev/null || date -d "7 days ago" +%Y%m%d%H%M)" "$SEED_FILE" 2>/dev/null || true
  echo "STALL|FAIL-SEED-CARD|168h|seed_injected|$TS"
  exit 1
fi

# === 主逻辑 ===
main() {
  echo "[H-14] modify-stall-detector.sh 启动 (基线: R131)"

  # 1. 扫 .evolver/ 下 in-progress 卡
  if [ ! -d "$EVOLVER_DIR" ]; then
    echo "⚠️  .evolver/ 目录不存在（GEP 文档形态非运行态）"
    echo "   行为：目录缺失 = 不阻断（exit 0）"
    exit 0
  fi

  # 2. 创建 failures.jsonl（如缺）
  mkdir -p "$(dirname "$FAILURES_JSONL")"
  [ -f "$FAILURES_JSONL" ] || touch "$FAILURES_JSONL"

  # 3. find in-progress 卡 mtime > 2 天（= 48h）
  local stall_count=0
  local stall_report=""
  while IFS= read -r card; do
    [ -z "$card" ] && continue
    local card_mtime card_age_hr card_id
    card_mtime=$(stat -f %m "$card" 2>/dev/null || stat -c %Y "$card" 2>/dev/null || echo 0)
    [ "$card_mtime" -eq 0 ] && continue
    local now=$(date +%s)
    local age_sec=$(( now - card_mtime ))
    card_age_hr=$(( age_sec / 3600 ))
    [ "$card_age_hr" -gt "$STALL_THRESHOLD_HOURS" ] || continue

    # 提取 card_id（首行 # 标题 或 文件名）
    card_id=$(head -1 "$card" 2>/dev/null | sed -nE 's/^#\s+(card-[0-9]+|CARD-[0-9]+)/\1/p')
    [ -z "$card_id" ] && card_id=$(basename "$card" .md)

    stall_count=$((stall_count + 1))
    local line="{\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"type\":\"stall\",\"card_id\":\"$card_id\",\"age_hours\":$card_age_hr,\"file\":\"$card\"}"
    echo "$line" >> "$FAILURES_JSONL"
    stall_report="${stall_report}STALL|${card_id}|${card_age_hr}h|${card}"$'\n'
  done < <(find "$EVOLVER_DIR" -name "*.md" -type f 2>/dev/null)

  # 4. 报告
  if [ "$stall_count" -gt 0 ]; then
    echo "🔴 GEP Modify-stall: $stall_count 卡超 $STALL_THRESHOLD_HOURS h 阈值"
    echo "$stall_report"
    echo "   写入: $FAILURES_JSONL (GEP 进入运行态第 1 步)"
    echo "   触发：反脆弱指针 #130 反思链元根因（修待 R133 派单）"
    exit 1
  fi

  echo "✅ GEP in-progress 卡全部 ≤ $STALL_THRESHOLD_HOURS h"
  echo "   failures.jsonl 现状: $(wc -l < "$FAILURES_JSONL" 2>/dev/null || echo 0) 行"
  exit 0
}

main "$@"
