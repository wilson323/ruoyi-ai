#!/usr/bin/env bash
# red-scan.sh - 红名单基线扫描（简化版演示）
# 按"空格"分割而非 "#"，避免丢失 method（项目记忆"红名单基线解析"）
# 演示用：扫 *.test.java 中 mock/builder 写死 ID 模式

set -euo pipefail

# 切换到仓库根（脚本可能在任意 cwd 下被调用）
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo ".")
cd "$REPO_ROOT"

EXIT_CODE=0
SPATIAL_HITS=0

# 扫描 *.test.java 中的 builder().id(NN) 等写死 ID 模式
SPATIAL_OUTPUT=$(find ruoyi-modules -name "*.test.java" -print0 2>/dev/null | xargs -0 grep -hnE "builder\\(\\)\\.id\\([0-9]+\\)" 2>/dev/null | head -50 || true)
if [ -n "$SPATIAL_OUTPUT" ]; then
  echo "$SPATIAL_OUTPUT"
  SPATIAL_HITS=$(echo "$SPATIAL_OUTPUT" | wc -l | tr -d ' ')
  echo "[INFO] 按空格分割模式扫到 $SPATIAL_HITS 处 mock/builder 写死 ID"
fi

if [ "$SPATIAL_HITS" -le 0 ]; then
  echo "[WARN] 未扫到任何 mock/builder 写死 ID；可能项目结构变了，需更新脚本"
  # 不强制失败（演示脚本）
fi

echo "[OK] red-scan 完成"
exit $EXIT_CODE