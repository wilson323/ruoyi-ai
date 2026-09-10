#!/usr/bin/env bash
# scripts/check_async_eventlistener.sh
# ----------------------------------------------------------------------
# R28.5 治理门禁：扫描 @Async + @EventListener 同方法组合。
# 规约见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md §四
#
# 命中即 exit 1。
# 排除：注释里的 @Async/@EventListener 提及（无法 100% 区分，靠人工复核）
# ----------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

echo "▶ R28.5 治理门禁：扫描 @Async + @EventListener 同方法组合"

# 用 awk：在同一文件内，@Async 后 5 行内有 @EventListener 视为同方法
HITS=$(find . -type f -name "*.java" \
  -not -path "*/.codex/*" \
  -not -path "*/.claude/worktrees/*" \
  -not -path "*/target/*" \
  -not -path "*/.git/*" \
  -print0 2>/dev/null | xargs -0 awk '
    /@Async\b/ { async_line = NR }
    /@EventListener\b/ && NR <= async_line + 5 && async_line > 0 {
      print FILENAME ":" NR ": @Async(行" async_line ") + @EventListener 同方法组合"
      async_line = -1   # 防止同一方法内多次命中
    }
  ' 2>/dev/null)

if [ -z "$HITS" ]; then
  echo "✅ 0 命中。门禁通过。"
  exit 0
fi

echo "❌ 命中 — @Async + @EventListener 同方法组合（禁止）"
echo "原因：异步派发异常链路断裂；事件链应同步发布 + 同步消费"
echo "修复指引：去掉方法上的 @Async，保留 @EventListener"
echo "规约见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md §四"
echo "----------------------------------------"
echo "$HITS"
echo "----------------------------------------"
exit 1