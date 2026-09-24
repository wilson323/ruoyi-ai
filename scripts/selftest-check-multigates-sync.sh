#!/usr/bin/env bash
# =============================================================================
# selftest-check-multigates-sync.sh
# check-multigates-sync.sh 自证能红 selftest（R194 §A4 §五 5 用例）
#
# 5 用例（设计稿 §五）：
#   T1 绿 默认无 FAIL_SEED → 脚本能跑（exit ∈ {0, 1}，非 2/3 即视为脚本可执行）
#   T2 红 FAIL_SEED=true → exit 2（验脚本能拦）
#   T3 边界 DRY_RUN + FAIL_SEED → exit 0（验 dry-run 旁路）
#   T4 子集 MG_TARGETS=1,2 → exit 0/1（验子集动作）
#   T5 性能 ≤ 60s（5 类闸 × find/grep 合计）
#
# 用法: bash scripts/selftest-check-multigates-sync.sh
# 退出码: 0 = 全 5 用例 PASS / 1 = 任一用例 FAIL
# =============================================================================

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${SCRIPT_DIR}/check-multigates-sync.sh"
PASS_COUNT=0
FAIL_COUNT=0

assert_exit() {
  local name="$1" expect="$2" actual="$3"
  if [ "$expect" = "$actual" ]; then
    echo "  [OK] $name (exit=$actual)"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  [FAIL] $name (expect=$expect actual=$actual)"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

echo "================================================================"
echo "selftest-check-multigates-sync.sh  (R194 §A4 §五 5 用例)"
echo "================================================================"

# === T1 默认无 FAIL_SEED → 脚本能跑（exit ∈ {0, 1}）===
echo "--- T1 默认无 FAIL_SEED（脚本可执行性） ---"
T1_ACTUAL=$(bash "$TARGET" > /tmp/t1.out 2>&1; echo $?)
if [ "$T1_ACTUAL" = "2" ] || [ "$T1_ACTUAL" = "3" ]; then
  assert_exit "T1 默认扫描" "0_or_1" "$T1_ACTUAL"
else
  echo "  [OK] T1 默认扫描（exit=$T1_ACTUAL，脚本可执行）"
  PASS_COUNT=$((PASS_COUNT + 1))
fi

# === T2 FAIL_SEED=true → exit 2 ===
echo "--- T2 FAIL_SEED 自证能红 ---"
T2_ACTUAL=$(MULTIGATES_FAIL_SEED=1 bash "$TARGET" > /tmp/t2.out 2>&1; echo $?)
assert_exit "T2 FAIL_SEED → exit 2" "2" "$T2_ACTUAL"

# === T3 DRY_RUN + FAIL_SEED → exit 0（验 dry-run 旁路）===
echo "--- T3 DRY_RUN + FAIL_SEED ---"
T3_ACTUAL=$(DRY_RUN=1 MULTIGATES_FAIL_SEED=1 bash "$TARGET" > /tmp/t3.out 2>&1; echo $?)
assert_exit "T3 DRY_RUN+FAIL_SEED → exit 0" "0" "$T3_ACTUAL"

# === T4 MG_TARGETS=1,2 子集 → exit 0/1（验子集限定动作）===
echo "--- T4 子集 MG_TARGETS=1,2 ---"
T4_ACTUAL=$(MG_TARGETS=1,2 bash "$TARGET" > /tmp/t4.out 2>&1; echo $?)
if [ "$T4_ACTUAL" = "2" ] || [ "$T4_ACTUAL" = "3" ]; then
  assert_exit "T4 子集限定" "0_or_1" "$T4_ACTUAL"
else
  echo "  [OK] T4 子集 MG=1,2 (exit=$T4_ACTUAL，子集限定工作)"
  PASS_COUNT=$((PASS_COUNT + 1))
fi

# === T5 性能 ≤ 60s ===
echo "--- T5 性能（≤ 60s） ---"
START=$(date +%s)
bash "$TARGET" > /tmp/t5.out 2>&1
END=$(date +%s)
ELAPSED=$((END - START))
if [ "$ELAPSED" -le 60 ]; then
  echo "  [OK] T5 性能: ${ELAPSED}s ≤ 60s"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  [FAIL] T5 性能: ${ELAPSED}s > 60s"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo "================================================================"
echo "selftest 汇总: PASS=$PASS_COUNT / FAIL=$FAIL_COUNT / TOTAL=5"
echo "================================================================"

if [ "$FAIL_COUNT" -gt 0 ]; then
  echo "❌ selftest FAIL: $FAIL_COUNT 用例未过"
  exit 1
fi
echo "✅ selftest PASS: 5/5 用例全过（M-Root-12 自证能红）"
exit 0