#!/usr/bin/env bash
# verify.sh - db-migration skill 自检（模板套用示范）
# 跑 check-entity-db-drift.py 验证 entity ↔ 真库字段已对齐

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo ".")
cd "$REPO_ROOT"

EXIT_CODE=0
FAILED_STEPS=()

echo "=========================================="
echo "  db-migration skill self-verify (template)"
echo "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="

# Step 1: env-probe：检查 Python 与 check-entity-db-drift.py 是否存在
echo ""
echo "[1/2] env-probe..."
if command -v python3 >/dev/null 2>&1; then
  echo "[OK] python3 found: $(command -v python3)"
else
  echo "[FAIL] python3 not in PATH"
  EXIT_CODE=1
  FAILED_STEPS+=("env-probe")
fi

PY_CHECK="$REPO_ROOT/docs/script/sql/check-entity-db-drift.py"
if [ -f "$PY_CHECK" ]; then
  echo "[OK] $PY_CHECK exists"
else
  echo "[FAIL] $PY_CHECK not found"
  EXIT_CODE=1
  FAILED_STEPS+=("env-probe")
fi

# Step 2: 跑 entity ↔ 真库 drift check（演示模式：仅 --help，不实际连库）
echo ""
echo "[2/2] drift-check (--help dry-run)..."
if python3 "$PY_CHECK" --help 2>&1 | head -5; then
  echo "[OK] drift-check --help succeeded"
else
  echo "[FAIL] drift-check --help failed"
  EXIT_CODE=1
  FAILED_STEPS+=("drift-check")
fi

echo ""
echo "=========================================="
if [ $EXIT_CODE -eq 0 ]; then
  echo "  [VERIFIED] 全部通过，db-migration skill 模板可用"
  echo "=========================================="
else
  echo "  [FAIL] 失败步骤: ${FAILED_STEPS[*]}"
  echo ""
  echo "  失败归因（按 DisCo 三类）:"
  echo "    1. 知识自身错：修复 SKILL.md 或 references/，定点重跑"
  echo "    2. 环境跑不通：检查 Python/check-entity-db-drift.py"
  echo "    3. 检查不安全：跳过或换其他检查方式"
  echo "=========================================="
fi

exit $EXIT_CODE