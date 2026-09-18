#!/usr/bin/env bash
# verify.sh - gen-test skill 自检主入口
# 调用顺序：env-probe → red-scan → mock-drift-check
# 失败归因三类（DisCo 同款）：
#   1. 知识自身错：修复 SKILL.md 或 references/，定点重跑
#   2. 环境跑不通：检查 JDK/Maven/Profile，在 SKILL.md 顶部加 WARNING
#   3. 检查不安全：跳过或换其他检查方式

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(dirname "$SCRIPT_DIR")"

EXIT_CODE=0
FAILED_STEPS=()

echo "=========================================="
echo "  gen-test skill self-verify"
echo "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "  SKILL_DIR: $SKILL_DIR"
echo "=========================================="

# Step 1: env-probe
echo ""
echo "[1/3] env-probe.sh ..."
if bash "$SCRIPT_DIR/env-probe.sh"; then
  echo "[1/3] env-probe OK"
else
  FAILED_STEPS+=("env-probe")
  EXIT_CODE=1
fi

# Step 2: red-scan
echo ""
echo "[2/3] red-scan.sh ..."
if bash "$SCRIPT_DIR/red-scan.sh"; then
  echo "[2/3] red-scan OK"
else
  FAILED_STEPS+=("red-scan")
  EXIT_CODE=1
fi

# Step 3: mock-drift-check
echo ""
echo "[3/3] mock-drift-check.sh ..."
if bash "$SCRIPT_DIR/mock-drift-check.sh"; then
  echo "[3/3] mock-drift-check OK"
else
  FAILED_STEPS+=("mock-drift-check")
  EXIT_CODE=1
fi

echo ""
echo "=========================================="
if [ $EXIT_CODE -eq 0 ]; then
  echo "  [VERIFIED] 全部通过，skill 可用"
  echo "=========================================="
else
  echo "  [FAIL] 失败步骤: ${FAILED_STEPS[*]}"
  echo ""
  echo "  失败归因（按 DisCo 三类）:"
  echo "    1. 知识自身错：修复 SKILL.md 或 references/，定点重跑"
  echo "    2. 环境跑不通：检查 JDK/Maven/Profile，在 SKILL.md 顶部加 WARNING"
  echo "    3. 检查不安全：跳过或换其他检查方式"
  echo "=========================================="
fi

exit $EXIT_CODE