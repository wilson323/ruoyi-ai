#!/usr/bin/env bash
# .harness/verify.sh — 一条命令自证（Anthropic: "别听 AI 说完成，让结果作证"）
#
# 用法:
#   bash .harness/verify.sh          # 跑全部三段，任何一段失败则整体 FAIL
#
# 纪律:
#   1. 项目只需改下面的 §VERIFY 区，把三条命令换成真实可跑的。
#   2. AI 说"完成"之前必须实跑本脚本，把输出原样贴进 plan.md 的证据区。
#   3. 本脚本由 sync 同步，但 sync 不会覆盖项目已填过的版本（no-clobber）。
set -uo pipefail
cd "$(dirname "$0")/.."

FAIL=0

run_step() {
  local name="$1" cmd="$2"
  echo ""
  echo "── [$name] \$ $cmd"
  if [[ "$cmd" == *"<填写>"* ]]; then
    echo "   ⚠ 未配置（编辑 .harness/verify.sh 的 §VERIFY 区）—— 跳过，但整体记为 FAIL"
    FAIL=1
    return
  fi
  if OUT=$(eval "$cmd" 2>&1); then
    echo "$OUT" | tail -20
    echo "   ✓ PASS"
  else
    CODE=$?
    echo "$OUT" | tail -40
    echo "   ✗ FAIL (exit $CODE)"
    FAIL=1
  fi
}

# ═══ §VERIFY 项目自填区（改成项目真实的命令）═════════════════
BUILD_CMD="<填写> 例如: pnpm build / mvn -DskipTests package / go build ./..."
CHECK_CMD="<填写> 例如: pnpm type-check && pnpm lint / mvn checkstyle:check"
TEST_CMD="<填写> 例如: pnpm test / mvn test / go test ./..."
# ═════════════════════════════════════════════════════════════

echo "════════ VERIFY START $(date '+%F %T') ════════"
run_step "Build 构建" "$BUILD_CMD"
run_step "Check 静态检查" "$CHECK_CMD"
run_step "Test 测试" "$TEST_CMD"
echo ""
echo "════════ VERIFY: $([ $FAIL -eq 0 ] && echo "✓ PASS" || echo "✗ FAIL") ════════"
echo "（把上面完整输出贴进 plan.md 证据区 —— 不贴视同未验证）"
exit $FAIL
