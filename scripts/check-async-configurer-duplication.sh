#!/usr/bin/env bash
# scripts/check-async-configurer-duplication.sh
# ----------------------------------------------------------------------
# R28.5 治理门禁：扫描业务代码 implements/extends AsyncConfigurer。
# 规约见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md
#
# 命中即 exit 1，并打印命中行号 + 文件 + 排除例外清单（当前无白名单）。
# 用法：在 ruoyi-ai 仓根目录执行 ./scripts/check-async-configurer-duplication.sh
#
# 【2026-09-28 O-6-1 修订（审计 §S6 证据①裁决）】
#   - 新增 src/test 豁免：守卫测试自身必须在注释/断言字符串里引用本模式
#     才能断言生产代码不含它（ApplicationConfigSmokeTest 假红 3 处）。
#     运行态权威判定归 check-async-unused-bean.sh（bean 版本为正轨，0 命中 PASS），
#     本脚本仅负责 src/main 生产代码的静态文本扫描。
#   - 新增 .worktrees 豁免：在途 worktree 副本不是本工作区的事实源
#     （曾导致同一守卫测试被重复计入 3 处假红）。
# ----------------------------------------------------------------------
set -uo pipefail

# 解析仓根（脚本所在目录的父目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

# 排除路径：.codex / .claude/worktrees / .worktrees / 任何 target / node_modules / .git / src/test
# src/test 豁免理由见头部 O-6-1 修订说明（守卫测试自引用 ≠ 生产违规）
EXCLUDES=(
  -path "*/.codex/*" -o
  -path "*/.harness/*" -o
  -path "*/.claude/worktrees/*" -o
  -path "*/.worktrees/*" -o
  -path "*/target/*" -o
  -path "*/.git/*" -o
  -path "*/node_modules/*" -o
  -path "*/src/test/*"
)

# 命中模式（注释与字符串里的"implements AsyncConfigurer"也算，避免有人靠注释逃逸；
# 该口径仅作用于 src/main——测试目录已豁免，见 O-6-1）
PATTERN='(implements|extends)\s+AsyncConfigurer\b'

echo "▶ R28.5 治理门禁：扫描业务代码 implements/extends AsyncConfigurer"
echo "▶ 仓根: $REPO_ROOT"
echo "▶ 排除: .codex/ .claude/worktrees/ .worktrees/ target/ .git/ node_modules/ src/test/（O-6-1：守卫测试自引用豁免，运行态权威归 check-async-unused-bean.sh）"
echo

# find + grep
# 注意：排除模式必须用 -o 连接（AND 永不成立——2026-09-28 O-6-1 顺带修复的历史 bug，
# 该 bug 曾导致 .worktrees/src/test 排除全部失效）；-prune 前不能前置 -type f，否则目录不被剪枝
HITS=$(find . \
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
