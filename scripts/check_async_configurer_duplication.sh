#!/usr/bin/env bash
# scripts/check_async_configurer_duplication.sh
# ----------------------------------------------------------------------
# R28.5 治理门禁：扫描业务代码 implements/extends AsyncConfigurer。
# 规约见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md
#
# 命中即 exit 1，并打印命中行号 + 文件 + 排除例外清单（当前无白名单）。
# 用法：在 ruoyi-ai 仓根目录执行 ./scripts/check_async_configurer_duplication.sh
# ----------------------------------------------------------------------
set -uo pipefail

# 解析仓根（脚本所在目录的父目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

# 排除路径：.codex / .claude/worktrees / 任何 target / node_modules / .git
EXCLUDES=(
  -path "*/.codex/*"
  -path "*/.claude/worktrees/*"
  -path "*/target/*"
  -path "*/.git/*"
)

# 命中模式（注释与字符串里的"implements AsyncConfigurer"也算，避免有人靠注释逃逸）
PATTERN='(implements|extends)\s+AsyncConfigurer\b'

echo "▶ R28.5 治理门禁：扫描业务代码 implements/extends AsyncConfigurer"
echo "▶ 仓根: $REPO_ROOT"
echo "▶ 排除: .codex/ .claude/worktrees/ target/ .git/"
echo

# find + grep
HITS=$(find . -type f -name "*.java" \
  \( "${EXCLUDES[@]}" \) -prune -o \
  -type f -name "*.java" -print 2>/dev/null \
  | xargs grep -nE "$PATTERN" 2>/dev/null)

if [ -z "$HITS" ]; then
  echo "✅ 0 命中。门禁通过。"
  exit 0
fi

echo "❌ 命中 $(( $(echo "$HITS" | wc -l) )) 处，规约违规："
echo "----------------------------------------"
echo "$HITS"
echo "----------------------------------------"
echo
echo "修复指引："
echo "  1. 去掉 implements/extends AsyncConfigurer"
echo "  2. 把 getAsyncExecutor() 改 @Bean(name=\"taskExecutor\")"
echo "  3. 把 getAsyncUncaughtExceptionHandler() 改 @Bean(name=\"asyncUncaughtExceptionHandler\")"
echo "  4. 详细规约见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md"
exit 1