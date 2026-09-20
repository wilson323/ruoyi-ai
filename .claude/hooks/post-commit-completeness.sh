#!/usr/bin/env bash
# .claude/hooks/post-commit-completeness.sh
# R123 常态化:R119 病根 #2 提交完整度检查 → 接 post-commit
#
# 这是 hook 源码(.claude/hooks/ 在 OPS-09 白名单,可主仓直写)
# 真正的 install 走 scripts/install-completeness-post-commit.sh
#
# 行为:
#   - 通知型 post-commit(完整性问题不阻断 commit 已发生)
#   - 每次 commit 后跑 scripts/check-commit-completeness.sh 收集健康快照
#   - exit code 不传递(失败也不让 commit 看起来失败)
#   - 完整度报告落到 docs/ipd-系统说明/提交完整度-YYYYMMDD-HHMMSS.md
#
# OPS-09 撞车 0:
#   - post-commit 不阻断(commit 已成功才走)
#   - 不覆盖 R30+ 治理 hook
#   - 安装:.git/hooks/post-commit.d/completeness.sh(独立文件)

set -e

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
SCRIPT="$ROOT/scripts/check-commit-completeness.sh"

if [ ! -f "$SCRIPT" ]; then
  echo "❌ [post-commit-completeness] 门禁脚本不存在: $SCRIPT"
  echo "  请确认 R119 5 脚本已 commit (commit a34a0002)"
  exit 0  # post-commit 不阻
fi

echo "[post-commit-completeness] running $SCRIPT"
bash "$SCRIPT" || {
  echo
  echo "════════════════════════════════════════════════════════════════════"
  echo "  ⚠️ 提交完整度不达标(已 commit 完成,信息快照)"
  echo "════════════════════════════════════════════════════════════════════"
  echo "  R119 病根 #2 提示:"
  echo "    - 检查 commit message 是否含 R### 编号、链路号"
  echo "    - 检查 diff 是否混入了无关改动"
  echo "    - 检查是否漏掉对应 docs/R*.md 文档落档"
  echo "════════════════════════════════════════════════════════════════════"
  # post-commit 永远 exit 0(commit 已发生,不二次阻断)
  exit 0
}

echo "[post-commit-completeness] ✓ 提交完整度 OK"
exit 0
