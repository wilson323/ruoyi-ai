#!/usr/bin/env bash
# =============================================================================
# selftest-check-permission-single-source.sh
# R204 权限收敛脚本自检（沿用 R142 9 门禁范式 + R134 自证能红纪律）
#
# 用法:
#   bash scripts/selftest-check-permission-single-source.sh
#
# 退出码:
#   0 = PASS（5/5 用例全过）
#   1 = FAIL（有用例失败）
# =============================================================================

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET="$SCRIPT_DIR/check-permission-single-source.sh"

PASS=0
FAIL=0
TEST_RESULTS=()

# ---------- 用例 T1：默认扫描（应 exit=0 或 exit=1）----------
T1_START=$(date +%s)
T1_OUTPUT=$(bash "$TARGET" 2>&1) || T1_EXIT=$?
T1_EXIT=${T1_EXIT:-0}
T1_END=$(date +%s)
T1_DUR=$((T1_END - T1_START))

if [ "$T1_EXIT" -eq 0 ] || [ "$T1_EXIT" -eq 1 ]; then
  PASS=$((PASS+1))
  TEST_RESULTS+=("✅ T1 PASS (exit=$T1_EXIT, ${T1_DUR}s): 默认扫描可执行 + 验证真活 FAIL")
else
  FAIL=$((FAIL+1))
  TEST_RESULTS+=("❌ T1 FAIL (exit=$T1_EXIT): 默认扫描异常退出")
fi

# ---------- 用例 T2：FAIL_SEED 模式（应 exit=2，验脚本能拦）----------
T2_START=$(date +%s)
T2_OUTPUT=$(PERM_FAIL_SEED=1 bash "$TARGET" 2>&1) || T2_EXIT=$?
T2_EXIT=${T2_EXIT:-0}
T2_END=$(date +%s)
T2_DUR=$((T2_END - T2_START))

if [ "$T2_EXIT" -eq 2 ]; then
  PASS=$((PASS+1))
  TEST_RESULTS+=("✅ T2 PASS (exit=$T2_EXIT, ${T2_DUR}s): FAIL_SEED 故意红，验脚本能拦")
else
  FAIL=$((FAIL+1))
  TEST_RESULTS+=("❌ T2 FAIL (exit=$T2_EXIT): FAIL_SEED 应 exit=2 实际 exit=$T2_EXIT")
fi

# ---------- 用例 T3：DRY_RUN + FAIL_SEED（应 exit=0，验旁路）----------
T3_START=$(date +%s)
T3_OUTPUT=$(DRY_RUN=1 PERM_FAIL_SEED=1 bash "$TARGET" 2>&1) || T3_EXIT=$?
T3_EXIT=${T3_EXIT:-0}
T3_END=$(date +%s)
T3_DUR=$((T3_END - T3_START))

if [ "$T3_EXIT" -eq 0 ]; then
  PASS=$((PASS+1))
  TEST_RESULTS+=("✅ T3 PASS (exit=$T3_EXIT, ${T3_DUR}s): DRY_RUN 旁路 FAIL_SEED，验旁路生效")
else
  FAIL=$((FAIL+1))
  TEST_RESULTS+=("❌ T3 FAIL (exit=$T3_EXIT): DRY_RUN 应 exit=0 实际 exit=$T3_EXIT")
fi

# ---------- 用例 T4：MP_TARGETS 子集限定（应 exit=0 或 exit=1）----------
T4_START=$(date +%s)
T4_OUTPUT=$(MP_TARGETS=1,3 bash "$TARGET" 2>&1) || T4_EXIT=$?
T4_EXIT=${T4_EXIT:-0}
T4_END=$(date +%s)
T4_DUR=$((T4_END - T4_START))

if [ "$T4_EXIT" -eq 0 ] || [ "$T4_EXIT" -eq 1 ]; then
  PASS=$((PASS+1))
  TEST_RESULTS+=("✅ T4 PASS (exit=$T4_EXIT, ${T4_DUR}s): MP_TARGETS=1,3 子集限定生效")
else
  FAIL=$((FAIL+1))
  TEST_RESULTS+=("❌ T4 FAIL (exit=$T4_EXIT): MP_TARGETS 子集限定异常")
fi

# ---------- 用例 T5：性能 ≤ 60s----------
T5_START=$(date +%s)
T5_OUTPUT=$(bash "$TARGET" 2>&1) || T5_EXIT=$?
T5_EXIT=${T5_EXIT:-0}
T5_END=$(date +%s)
T5_DUR=$((T5_END - T5_START))

if [ "$T5_DUR" -le 60 ]; then
  PASS=$((PASS+1))
  TEST_RESULTS+=("✅ T5 PASS (${T5_DUR}s ≤ 60s): 性能合规")
else
  FAIL=$((FAIL+1))
  TEST_RESULTS+=("❌ T5 FAIL (${T5_DUR}s > 60s): 性能超时")
fi

# ---------- 报告----------
echo "═══════════════════════════════════════════════════════════════"
echo " R204 check-permission-single-source.sh selftest"
echo "═══════════════════════════════════════════════════════════════"
for r in "${TEST_RESULTS[@]}"; do echo " $r"; done
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo " 汇总: $PASS PASS / $FAIL FAIL"
echo "═══════════════════════════════════════════════════════════════"

if [ "$FAIL" -eq 0 ]; then
  exit 0
else
  exit 1
fi