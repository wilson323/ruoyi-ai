#!/bin/sh
# E4 (R25) PostToolUse：写入 IPD domain 实体后自动跑 drift 静态扫描，即时提醒缺迁移
# 提示型 hook（永不阻断）；扫描脚本自检失败也只提示，不拦写入
set -u
D="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
INPUT=$(cat 2>/dev/null || true); [ -n "$INPUT" ] || exit 0
FILE=$(printf '%s' "$INPUT" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
[ -n "$FILE" ] || exit 0
case "$FILE" in
  */org/ruoyi/ipd/domain/*.java) ;;
  *) exit 0 ;;
esac
[ -f "$D/.claude/helpers/ipd-drift-scan.py" ] || exit 0
echo "[hook] IPD 实体已写入: $(basename "$FILE") — 自动跑 drift 扫描："
python3 "$D/.claude/helpers/ipd-drift-scan.py" 2>&1 | head -30
exit 0
