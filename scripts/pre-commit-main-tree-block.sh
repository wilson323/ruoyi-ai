#!/usr/bin/env bash
# scripts/pre-commit-main-tree-block.sh
# R30+ 治理门禁：默认阻断主工作树 commit，强制走 worktree 隔离。
# AGENTS.md §并发写单一写入者（OPS-09）："Java 源码、SSOT 看板镜像 与本地看板默认由
# 主协调会话串行写，其他会话只做只读探针 + 证据交付。"
# AGENTS.md §构建/测试："未经用户明确要求不提交、推送、创建业务分支或发布。
#                       保留同目录未提交工作；跨Java/SQL修改须回原卡确认范围。"
#
# 该 hook 通过 .git/hooks/pre-commit 触发，检测当前 commit 是否发生在主工作树。
# 若发生在主工作树 → exit 1 阻断 + 提示用 worktree 隔离。
# 例外（白名单）：
#   1. R25 owner 已授权「完整接手兄弟会话在途」——通过环境变量 SKIP_MAIN_TREE_BLOCK=1 跳过
#   2. 文件列表仅含 .claude/skills/, scripts/, docs/ipd-系统说明/治理/ —— 治理类非业务
#   3. 用户显式允许（commit message 含 "main-tree-allowed: <reason>" 标记）
#
# 安装方式：
#   bash scripts/install-pre-commit-hook.sh
# 卸载方式：
#   git config --unset core.hooksPath
#   或 rm .git/hooks/pre-commit

set -o pipefail

# Determine if we're in a worktree.
# git-dir == common-dir → main worktree (block)
# git-dir != common-dir → linked worktree (allow)
WORKTREE_DIR="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
GIT_DIR_REL="$(git rev-parse --git-dir 2>/dev/null)" || exit 0
GIT_COMMON_REL="$(git rev-parse --git-common-dir 2>/dev/null)" || exit 0

# Resolve to absolute paths for comparison (--git-common-dir can return relative)
GIT_DIR_ABS="$(cd "$GIT_DIR_REL" 2>/dev/null && pwd -P)" || GIT_DIR_ABS=""
GIT_COMMON_ABS="$(cd "$GIT_COMMON_REL" 2>/dev/null && pwd -P)" || GIT_COMMON_ABS=""

# If we can't resolve, bail safely (don't block)
[ -z "$GIT_DIR_ABS" ] || [ -z "$GIT_COMMON_ABS" ] && exit 0

if [ "$GIT_DIR_ABS" = "$GIT_COMMON_ABS" ]; then
  # Check for skip env
  if [ "${SKIP_MAIN_TREE_BLOCK:-0}" = "1" ]; then
    echo "[pre-commit] SKIP_MAIN_TREE_BLOCK=1 → allowing main-tree commit"
    exit 0
  fi

  # Check commit msg marker (only available in commit-msg, NOT pre-commit)
  # So skip that here — pre-commit doesn't have access to commit message reliably

  # Check staged files: if ONLY governance files are staged → allow
  STAGED_FILES=$(git diff --cached --name-only 2>/dev/null)
  if [ -z "$STAGED_FILES" ]; then
    # Nothing staged → not a real commit attempt
    exit 0
  fi

  GOVERNANCE_ONLY=1
  while IFS= read -r f; do
    case "$f" in
      .claude/skills/*|.claude/agents/*|.claude/commands/*|.claude/helpers/*|.claude/hooks/*|.agents/*)
        # governance config — allowed
        ;;
      scripts/*)
        # scripts/ (governance scripts) — allowed
        ;;
      docs/ipd-系统说明/治理/*)
        # 治理类文档 — allowed
        ;;
      AGENTS.md|CLAUDE.md|README*.md|.gitmessage|.gitignore|.editorconfig|.gitattributes)
        # governance docs — allowed
        ;;
      docs/ipd-系统说明/log.md|docs/ipd-系统说明/开发计划-看板镜像.md)
        # SSOT board mirror — only main coordinator allowed (manual SKIP env)
        GOVERNANCE_ONLY=0
        ;;
      *)
        # Java/SQL/业务代码/其他文档 → BLOCK
        GOVERNANCE_ONLY=0
        echo "  ⛔ main-tree commit blocked: staged '$f' is not governance-only"
        ;;
    esac
  done <<< "$STAGED_FILES"

  if [ "$GOVERNANCE_ONLY" = "1" ]; then
    echo "[pre-commit] governance-only files staged → allowing main-tree commit"
    exit 0
  fi

  echo
  echo "===================================================================="
  echo "  ⛔ main-tree commit BLOCKED"
  echo "===================================================================="
  echo "  AGENTS.md §OPS-09：Java 源码/SSOT 看板镜像 默认由主协调会话串行写。"
  echo "  错误：你在主工作树尝试 commit 业务文件。"
  echo
  echo "  解法（按需选用）："
  echo "    1. 推荐：用 worktree 隔离 → .claude/worktrees/<branch>-<ts>"
  echo "       git worktree add .claude/worktrees/fix-xxx -b fix/xxx"
  echo "       cd .claude/worktrees/fix-xxx && <edit> && git commit"
  echo "    2. owner 授权绕过：SKIP_MAIN_TREE_BLOCK=1 git commit ..."
  echo "    3. 仅治理文件可走主工作树（白名单见脚本注释）"
  echo "===================================================================="
  exit 1
fi

# Worktree commit → allow
exit 0