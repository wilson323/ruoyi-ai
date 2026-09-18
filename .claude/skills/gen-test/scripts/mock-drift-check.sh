#!/usr/bin/env bash
# mock-drift-check.sh - Mock 合法性自检
# 检查项目内 *.test.java 的常见违规：
#   1. 未标 @Tag("dev") 的测试类（Surefire 静默跳过假绿）
#   2. 测试里 Thread.sleep（已知假路）
#   3. 空 builder().build() 模式（可疑 NOT NULL 漏赋值）

set -euo pipefail

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo ".")
cd "$REPO_ROOT"

EXIT_CODE=0

# 规则 1：未标 @Tag("dev") 的测试类
echo "[INFO] 扫描未标 @Tag(\"dev\") 的测试类..."
UNANNOTATED=$(find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -L "@Tag(\"dev\")" 2>/dev/null | wc -l | tr -d ' ')
if [ "$UNANNOTATED" -gt 0 ]; then
  echo "[FAIL] $UNANNOTATED 个测试类未标 @Tag(\"dev\")，会被 Surefire 静默跳过"
  EXIT_CODE=1
fi

# 规则 2：测试里 Thread.sleep
echo "[INFO] 扫描 Thread.sleep 用法..."
SLEEP_FILES=$(find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -l "Thread\\.sleep" 2>/dev/null | head -3 || true)
if [ -n "$SLEEP_FILES" ]; then
  echo "[WARN] 发现 Thread.sleep 用法（已知假路 1）："
  echo "$SLEEP_FILES"
fi

# 规则 3：空 builder().build() 模式
echo "[INFO] 扫描空 builder().build() 模式..."
EMPTY_BUILDER=$(find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -lE "builder\\(\\)\\s*\\.build\\(\\)" 2>/dev/null | head -5 || true)
if [ -n "$EMPTY_BUILDER" ]; then
  echo "[WARN] 发现空 builder().build() 模式（可疑 NOT NULL 漏赋值）："
  echo "$EMPTY_BUILDER"
fi

if [ $EXIT_CODE -eq 0 ]; then
  echo "[OK] mock-drift-check 完成（未发现阻断性问题）"
fi

exit $EXIT_CODE