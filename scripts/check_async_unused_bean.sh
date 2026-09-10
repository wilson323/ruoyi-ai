#!/usr/bin/env bash
# scripts/check_async_unused_bean.sh
# ----------------------------------------------------------------------
# R28.5 治理门禁：扫描"自定义 @Bean(name="taskExecutor") 与 implements AsyncConfigurer 同一文件"
# 规约见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md §三
#
# 命中即 exit 1（违反规约禁止 implements）。
# 同时统计所有 @Bean(name="taskExecutor") 文件——超过 1 个时报警（同 Bean 名冲突）。
# ----------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

echo "▶ R28.5 治理门禁：扫描 taskExecutor Bean 定义与 AsyncConfigurer 复用"

# 找所有 @Bean(name="taskExecutor") 出现的文件
TASKEX_FILES=$(find . -type f -name "*.java" \
  -not -path "*/.codex/*" \
  -not -path "*/.claude/worktrees/*" \
  -not -path "*/target/*" \
  -not -path "*/.git/*" \
  -print0 2>/dev/null | xargs -0 grep -l '@Bean(name\s*=\s*"taskExecutor"' 2>/dev/null)

TASKEX_COUNT=$(echo "$TASKEX_FILES" | grep -c . 2>/dev/null || true)

# 找所有 implements AsyncConfigurer 出现的文件（排除注释行 // 开头）
ASYNC_CFG_FILES=$(find . -type f -name "*.java" \
  -not -path "*/.codex/*" \
  -not -path "*/.claude/worktrees/*" \
  -not -path "*/target/*" \
  -not -path "*/.git/*" \
  -print0 2>/dev/null | xargs -0 grep -l 'implements\s\+AsyncConfigurer\|extends\s\+AsyncConfigurer' 2>/dev/null | while read f; do
    # 精确匹配：class Foo implements AsyncConfigurer（避免注释里讲其他类的 implements 字面量）
    if grep -v '^\s*//' "$f" | grep -v '^\s*\*' | grep -E 'class\s+[A-Za-z_][A-Za-z0-9_]*\s+(implements|extends)\s+AsyncConfigurer' | grep -q .; then
      echo "$f"
    fi
  done)

ASYNC_CFG_COUNT=$(echo "$ASYNC_CFG_FILES" | grep -c . 2>/dev/null || true)

# 同 Bean 名冲突
if [ "${TASKEX_COUNT:-0}" -gt 1 ]; then
  echo "❌ 命中 — @Bean(name=\"taskExecutor\") 出现 ${TASKEX_COUNT} 次（必须唯一）："
  echo "$TASKEX_FILES"
  echo "----------------------------------------"
  exit 1
fi

# implements AsyncConfigurer 仍存在
if [ "${ASYNC_CFG_COUNT:-0}" -gt 0 ]; then
  echo "❌ 命中 — implements/extends AsyncConfigurer 仍有 ${ASYNC_CFG_COUNT} 个文件（禁止）："
  echo "$ASYNC_CFG_FILES"
  echo "----------------------------------------"
  exit 1
fi

echo "✅ 0 命中。门禁通过（taskExecutor 定义 1 个、无 implements AsyncConfigurer）。"
exit 0