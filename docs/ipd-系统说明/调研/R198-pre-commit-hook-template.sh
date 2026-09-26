#!/usr/bin/env bash
# =============================================================================
# R198 拍板方案甲 pre-commit 软拦 hook 模板（references only — DO NOT INSTALL）
# 仅供 R199 拍板后由 owner 复制到 .claude/hooks/pre-commit（动共享 hook 需 OPS-09）
# 设计依据: docs/ipd-系统说明/调研/R198-R197W3-CI集成拍板材料-20260924.md DP-1 B
#
# 行为: git commit 触发 → 跑 check-multigates-sync.sh → FAIL 仅警告不阻断
# 撞车 0 严守: 本文件存 docs/ipd-系统说明/调研/，不挂入 .claude/hooks/
# =============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
GATE="${REPO_ROOT}/scripts/check-multigates-sync.sh"
MG_TARGETS="${MG_TARGETS:-2,3,4}"  # R198 DP-3 D 推荐：MG-2/3/4 + MG-1 SKIP

if [ ! -x "$GATE" ]; then
  echo "[ipd-multigates-pre-commit] 闸门脚本不可执行: ${GATE}（撞车 0 软化：跳过）"
  exit 0
fi

echo "[ipd-multigates-pre-commit] 跑 check-multigates-sync.sh（DP-1 B 软拦模式）"
if bash "$GATE" 2>&1; then
  echo "[ipd-multigates-pre-commit] ✅ PASS（commit 可继续）"
  exit 0
fi

# 软拦模式（DP-1 B）：仅打印警告不阻断 commit
echo "::warning::[ipd-multigates-pre-commit] ❌ FAIL（多套闸不同步）→ 仅警告不阻断，按 R198 拍板方案甲"
echo "[ipd-multigates-pre-commit] 拍板修复路径: docs/ipd-系统说明/调研/R198-R197W3-CI集成拍板材料-20260924.md"
exit 0  # 软拦：commit 仍继续