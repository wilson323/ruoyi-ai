#!/usr/bin/env bash
# scripts/check-surefire-groups-coverage.sh — 防假绿类型 1: Surefire groups 过滤静默跳过
# 来源：R131 §二.2.2 防假绿类型 1
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 故意缺 @Tag("dev") → exit 2

set -eo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/audit-gate-input.sh"
REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"
TEST_BASE="${TEST_BASE:-$REPO/ruoyi-modules}"
POM_FILE="${POM_FILE:-$REPO/pom.xml}"

main() {
  if [ "${SGC_FAIL_SEED:-0}" = "1" ]; then
    echo "[gate] SGC_FAIL_SEED=1 自证失败"
    exit 2
  fi
  gate_require_tree "$TEST_BASE" -path '*/test/*' -name '*.java'
  [ -s "$POM_FILE" ] || { echo "[gate] pom输入缺失或为空: $POM_FILE" >&2; exit 2; }
  local groups tags
  groups=$(gate_grep -c '<groups>' "$POM_FILE")
  tags=$(gate_grep -rl '@Tag("dev")' "$TEST_BASE" --include='*.java' --exclude-dir=target)
  if [ "$groups" -gt 0 ] && [ -z "$tags" ]; then
    echo "❌ groups已配置但没有@Tag(dev)测试"
    exit 2
  fi
  echo "✅ 完整非空输入扫描完成"
}
main "$@"
