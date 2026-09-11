#!/usr/bin/env bash
# scripts/install-pre-commit-hook.sh
# 安装 pre-commit-main-tree-block 到 .git/hooks/pre-commit。
# 不会覆盖现有 hook；如已存在 → 备份到 .git/hooks/pre-commit.bak-<ts>。

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT" || exit 2

HOOK_SRC="$SCRIPT_DIR/pre-commit-main-tree-block.sh"
HOOK_DST="$REPO_ROOT/.git/hooks/pre-commit"

if [ ! -f "$HOOK_SRC" ]; then
  echo "ERROR: hook source not found: $HOOK_SRC"
  exit 2
fi

if [ -e "$HOOK_DST" ]; then
  TS=$(date +%Y%m%d%H%M%S)
  BACKUP="$HOOK_DST.bak-$TS"
  echo "  Backup existing hook → $BACKUP"
  mv "$HOOK_DST" "$BACKUP"
fi

cp "$HOOK_SRC" "$HOOK_DST"
chmod +x "$HOOK_DST"

echo
echo "OK: pre-commit hook installed"
echo "  source: $HOOK_SRC"
echo "  dest:   $HOOK_DST"
echo
echo "绕过方式（仅 owner 授权时使用）："
echo "  SKIP_MAIN_TREE_BLOCK=1 git commit ..."
echo
echo "卸载："
echo "  rm $HOOK_DST"
echo "  或：git config --unset core.hooksPath"