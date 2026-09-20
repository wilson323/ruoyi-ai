#!/usr/bin/env bash
# scripts/install-completeness-post-commit.sh
# R123 常态化:把 R119 病根 #2 提交完整度检查接 post-commit
#
# 用法:
#   bash scripts/install-completeness-post-commit.sh --dry-run    # 默认
#   bash scripts/install-completeness-post-commit.sh --install
#   bash scripts/install-completeness-post-commit.sh --uninstall
#   bash scripts/install-completeness-post-commit.sh --status
#
# OPS-09 撞车 0 严守:
#   - 不覆盖 R30+ 治理 hook
#   - 安装方式:.claude/hooks/post-commit.d/completeness.sh
#   - 当前 .claude/hooks/post-commit 已存在,本次不动它
#   - post-commit 不阻断(失败也不让 commit 看起来失败)

set -e

ACTION="${1:---dry-run}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HOOK_SRC="$SCRIPT_DIR/../.claude/hooks/post-commit-completeness.sh"
HOOK_DST=".claude/hooks/post-commit.d/completeness.sh"
HOOK_DST_ABS="$(cd "$SCRIPT_DIR/.." 2>/dev/null && pwd -P)/$HOOK_DST"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }

case "$ACTION" in
  --dry-run)
    bold "════════════════════════════════════════════════════════════════════"
    bold "  [DRY-RUN] R123 completeness post-commit 安装预览(不真改)"
    bold "════════════════════════════════════════════════════════════════════"
    echo
    yellow "hook 源:"
    if [ -f "$HOOK_SRC" ]; then
      ls -la "$HOOK_SRC"
      echo "──── 内容预览 ────"
      head -30 "$HOOK_SRC"
      echo "─────────────────"
    else
      red "❌ hook 源不存在: $HOOK_SRC"
      exit 1
    fi
    echo
    yellow "目标位置: $HOOK_DST"
    yellow "安装方式: install -m 0755 → .claude/hooks/post-commit.d/completeness.sh"
    echo
    yellow "⚠️ 重要前提:"
    yellow "  当前 .claude/hooks/post-commit 不会自动调度 post-commit.d/*.sh"
    yellow "  本次不自动改 .claude/hooks/post-commit,owner 可手动追加调度逻辑"
    yellow "  或者临时绕过:SKIP_COMPLETENESS=1 git commit"
    echo
    green "✓ dry-run 完成,系统未做任何变更"
    ;;

  --install)
    bold "[INSTALL] 安装 completeness post-commit hook"
    if [ ! -f "$HOOK_SRC" ]; then
      red "❌ hook 源不存在: $HOOK_SRC"
      exit 1
    fi
    mkdir -p ".claude/hooks/post-commit.d"
    install -m 0755 "$HOOK_SRC" "$HOOK_DST_ABS"
    green "✓ 安装成功: $HOOK_DST"
    yellow "⚠️ 别忘了:本次不自动改 .claude/hooks/post-commit 调度"
    ;;

  --uninstall)
    bold "[UNINSTALL] 删除 completeness hook"
    if [ -f "$HOOK_DST_ABS" ]; then
      rm -f "$HOOK_DST_ABS"
      green "✓ 删除: $HOOK_DST"
    else
      yellow "⚠️ 已不存在"
    fi
    rmdir ".claude/hooks/post-commit.d" 2>/dev/null && yellow "  已清空 post-commit.d/" || true
    ;;

  --status)
    bold "[STATUS] completeness post-commit hook 状态"
    echo
    yellow "─── hook 源 ───"
    [ -f "$HOOK_SRC" ] && ls -la "$HOOK_SRC" || red "❌ 不存在"
    echo
    yellow "─── 目标安装位 ($HOOK_DST) ───"
    [ -f "$HOOK_DST_ABS" ] && ls -la "$HOOK_DST_ABS" || yellow "未安装"
    echo
    yellow "─── .claude/hooks/post-commit 当前内容 ───"
    head -5 .claude/hooks/post-commit 2>/dev/null || echo "(不存在)"
    ;;

  *)
    red "❌ 未知参数: $ACTION"
    echo "用法: bash $0 [--dry-run|--install|--uninstall|--status]"
    exit 1
    ;;
esac
