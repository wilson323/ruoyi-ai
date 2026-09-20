#!/usr/bin/env bash
# scripts/check-lint-reports-freshness.sh — H-15 lint-reports 防漂移脚本
# 来源：R130 §六 D6 + R131 §二.2.2 假绿类型 6
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：TODO (R132 故意在 lint-reports/ 放 11 份新文件 → exit 1)

set -euo pipefail

# === 配置区 ===
LINT_REPORTS_DIR="${LINT_REPORTS_DIR:-docs/ipd-系统说明/lint-reports}"
FRESHNESS_THRESHOLD="${FRESHNESS_THRESHOLD:-10}"  # 1h 增量阈值
SNAPSHOT_FILE="${SNAPSHOT_FILE:-.harness/lint-reports-snapshot.txt}"

# === 主逻辑 ===
main() {
  echo "[H-15] check-lint-reports-freshness.sh 启动 (基线: R131)"

  # 1. 检查 lint-reports 目录
  if [ ! -d "$LINT_REPORTS_DIR" ]; then
    echo "❌ $LINT_REPORTS_DIR 不存在"
    exit 0  # 目录缺失 = 不阻断
  fi

  # 2. 计算当前 lint-reports 总数
  current_count=$(find "$LINT_REPORTS_DIR" -type f \( -name "*.md" -o -name "*.json" \) | wc -l | tr -d ' ')

  # 3. 与上次快照对比
  if [ -f "$SNAPSHOT_FILE" ]; then
    last_count=$(cat "$SNAPSHOT_FILE" | tr -d ' ')
    delta=$((current_count - last_count))
    echo "[H-15] 当前: $current_count  上次: $last_count  增量: $delta"

    if [ "$delta" -gt "$FRESHNESS_THRESHOLD" ]; then
      echo "🔴 增量超阈值 ($delta > $FRESHNESS_THRESHOLD)，触发 T4 升级"
      echo "   建议：人工 review 新增 lint-reports 是否需要处理"
      # TODO: 触发 T4 t4-lint-burst.sh
      exit 1
    fi
  else
    echo "[H-15] 无快照，创建基线快照: $current_count"
    mkdir -p "$(dirname "$SNAPSHOT_FILE")"
    echo "$current_count" > "$SNAPSHOT_FILE"
  fi

  # 4. 更新快照
  echo "$current_count" > "$SNAPSHOT_FILE"

  echo "[H-15] lint-reports freshness 检查 PASS"
  exit 0
}

main "$@"
