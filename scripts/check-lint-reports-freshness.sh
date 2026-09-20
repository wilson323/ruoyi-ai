#!/usr/bin/env bash
# scripts/check-lint-reports-freshness.sh — H-15 lint-reports 防漂移脚本（增强）
# 来源：R130 §六 D6 + R131 §二.2.2 假绿类型 6 + R132 §一.3
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：LINT_FAIL_SEED=1 → 故意放 11 份新文件 → exit 1

set -euo pipefail

# === 配置区 ===
LINT_REPORTS_DIR="${LINT_REPORTS_DIR:-docs/ipd-系统说明/lint-reports}"
FRESHNESS_THRESHOLD="${FRESHNESS_THRESHOLD:-10}"  # 1h 增量阈值
SNAPSHOT_FILE="${SNAPSHOT_FILE:-.harness/lint-reports-snapshot.txt}"
CROSS_COMMIT_THRESHOLD="${CROSS_COMMIT_THRESHOLD:-50}"  # 跨 commit 阈值
LINT_FAIL_SEED="${LINT_FAIL_SEED:-0}"

# === 自证能红：FAIL_SEED=1 放 11 份新文件 ===
if [ "$LINT_FAIL_SEED" = "1" ]; then
  echo "[H-15] FAIL_SEED=1 → 故意放 11 份新 lint-reports（基线 R132）"
  mkdir -p "$LINT_REPORTS_DIR"
  for i in $(seq 1 11); do
    echo "seed $i $(date +%s)" > "$LINT_REPORTS_DIR/.fail-seed-$i.md"
  done
  echo "🔴 自证注入完成（exit 1）"
  exit 1
fi

# === 主逻辑 ===
main() {
  echo "[H-15] check-lint-reports-freshness.sh 启动 (基线: R131)"

  # 1. 检查 lint-reports 目录
  if [ ! -d "$LINT_REPORTS_DIR" ]; then
    echo "⚠️  $LINT_REPORTS_DIR 不存在"
    echo "   行为：目录缺失 = 不阻断（exit 0）"
    exit 0
  fi

  # 2. 计算当前 lint-reports 总数
  local current_count
  current_count=$(find "$LINT_REPORTS_DIR" -type f \( -name "*.md" -o -name "*.json" \) 2>/dev/null | wc -l | tr -d ' ')

  # 3. 与上次快照对比（1h 增量）
  local delta=0
  if [ -f "$SNAPSHOT_FILE" ]; then
    local last_count
    last_count=$(cat "$SNAPSHOT_FILE" | tr -d ' ')
    delta=$((current_count - last_count))
    echo "[H-15] 1h 增量: current=$current_count  last=$last_count  delta=$delta"

    if [ "$delta" -gt "$FRESHNESS_THRESHOLD" ]; then
      echo "🔴 1h 增量超阈值 ($delta > $FRESHNESS_THRESHOLD)，触发 T4 升级"
      echo "   建议：人工 review 新增 lint-reports 是否需要处理"
      echo "$current_count" > "$SNAPSHOT_FILE"
      exit 1
    fi
  else
    echo "[H-15] 无快照，创建基线快照: $current_count"
    mkdir -p "$(dirname "$SNAPSHOT_FILE")"
    echo "$current_count" > "$SNAPSHOT_FILE"
  fi

  # 4. 跨 commit 增量检测（与 HEAD 上一个 commit 对比）
  local cross_commit_delta=0
  if command -v git >/dev/null 2>&1; then
    local prev_count
    prev_count=$(git show HEAD~1:"$LINT_REPORTS_DIR" 2>/dev/null | wc -l || echo "$current_count")
    cross_commit_delta=$(( current_count - prev_count ))
    if [ "$cross_commit_delta" -gt "$CROSS_COMMIT_THRESHOLD" ]; then
      echo "🔴 跨 commit 增量超阈值 ($cross_commit_delta > $CROSS_COMMIT_THRESHOLD)"
      echo "   提示：lint-reports 跨 commit 暴增，可能批量生成，需人工 review"
      exit 1
    fi
  fi

  # 5. 更新快照
  echo "$current_count" > "$SNAPSHOT_FILE"

  echo "✅ lint-reports freshness PASS（1h delta=$delta, cross-commit delta=$cross_commit_delta）"
  exit 0
}

main "$@"
