#!/usr/bin/env bash
# scripts/install-coverage-pre-commit.sh
# R123 常态化:把 R119 病根 #1 测试覆盖率检查接 pre-commit
#
# 用法:
#   bash scripts/install-coverage-pre-commit.sh --dry-run    # 默认:打印预览(撞车 0)
#   bash scripts/install-coverage-pre-commit.sh --install    # 真安装(创建 .claude/hooks/pre-commit.d/coverage.sh)
#   bash scripts/install-coverage-pre-commit.sh --uninstall  # 卸载
#   bash scripts/install-coverage-pre-commit.sh --status     # 看现状
#
# OPS-09 撞车 0 严守:
#   - 不动 R30+ 治理 hook(scripts/pre-commit-main-tree-block.sh)
#   - 不覆盖 .claude/hooks/pre-commit wrapper(.githooks/pre-commit 也不动)
#   - 安装方式:.claude/hooks/pre-commit.d/coverage.sh(独立文件,不撞 main-tree-block)
#   - 注意:.claude/hooks/pre-commit 当前是 wrapper,本 hook 需手动启用调度
#     或者用 git config core.hooksPath 改指(本次不自动改)

set -e

ACTION="${1:---dry-run}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HOOK_SRC="$SCRIPT_DIR/../.claude/hooks/pre-commit-coverage.sh"
HOOK_DST=".claude/hooks/pre-commit.d/coverage.sh"
HOOK_DST_ABS="$(cd "$SCRIPT_DIR/.." 2>/dev/null && pwd -P)/$HOOK_DST"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }

case "$ACTION" in
  --dry-run)
    bold "════════════════════════════════════════════════════════════════════"
    bold "  [DRY-RUN] R123 coverage pre-commit 安装预览(不真改)"
    bold "════════════════════════════════════════════════════════════════════"
    echo
    yellow "hook 源:"
    if [ -f "$HOOK_SRC" ]; then
      ls -la "$HOOK_SRC"
      echo "──── 内容预览 ────"
      head -40 "$HOOK_SRC"
      echo "─────────────────"
    else
      red "❌ hook 源不存在: $HOOK_SRC"
      exit 1
    fi
    echo
    yellow "目标位置: $HOOK_DST (绝对路径: $HOOK_DST_ABS)"
    yellow "安装方式: install -m 0755 → .claude/hooks/pre-commit.d/coverage.sh"
    echo
    yellow "⚠️ 重要前提:"
    yellow "  当前 .claude/hooks/pre-commit wrapper 不会自动调度 pre-commit.d/*.sh"
    yellow "  如要让本 hook 真触发,需三选一:"
    yellow "    a) 在 .claude/hooks/check-pre-commit.sh 末尾追加调度 .claude/hooks/pre-commit.d/*.sh 逻辑(需 OPS-09 授权改 R43-α hook)"
    yellow "    b) 手动 git config core.hooksPath <其它路径>(本次不自动改)"
    yellow "    c) owner 临时绕过:SKIP_COVERAGE_GATE=1 git commit"
    echo
    yellow "如要真安装:bash scripts/install-coverage-pre-commit.sh --install"
    echo
    green "✓ dry-run 完成,系统未做任何变更"
    ;;

  --install)
    bold "[INSTALL] 安装 coverage pre-commit hook"
    if [ ! -f "$HOOK_SRC" ]; then
      red "❌ hook 源不存在: $HOOK_SRC"
      exit 1
    fi
    mkdir -p ".claude/hooks/pre-commit.d"
    install -m 0755 "$HOOK_SRC" "$HOOK_DST_ABS"
    green "✓ 安装成功: $HOOK_DST"
    echo
    yellow "⚠️ 别忘了:本次不会自动改 .claude/hooks/pre-commit wrapper 来调度 pre-commit.d/"
    yellow "  如要触发:见上面 dry-run 输出的 a) b) c) 三选一"
    echo
    yellow "卸载方式:bash scripts/install-coverage-pre-commit.sh --uninstall"
    ;;

  --uninstall)
    bold "[UNINSTALL] 删除 coverage hook"
    if [ -f "$HOOK_DST_ABS" ]; then
      rm -f "$HOOK_DST_ABS"
      green "✓ 删除: $HOOK_DST"
    else
      yellow "⚠️ 已不存在: $HOOK_DST"
    fi
    # 删空目录(可选)
    rmdir ".claude/hooks/pre-commit.d" 2>/dev/null && yellow "  已清空 .claude/hooks/pre-commit.d/" || true
    ;;

  --status)
    bold "[STATUS] 当前 coverage pre-commit hook 状态"
    echo
    yellow "─── hook 源 (.claude/hooks/pre-commit-coverage.sh) ───"
    if [ -f "$HOOK_SRC" ]; then
      ls -la "$HOOK_SRC"
    else
      red "❌ 不存在"
    fi
    echo
    yellow "─── 目标安装位 ($HOOK_DST) ───"
    if [ -f "$HOOK_DST_ABS" ]; then
      ls -la "$HOOK_DST_ABS"
    else
      yellow "未安装"
    fi
    echo
    yellow "─── core.hooksPath ───"
    git config --get core.hooksPath 2>/dev/null || echo "(未设置,使用默认 .git/hooks/)"  # 注:本仓已显式设 .claude/hooks"
    echo
    yellow "─── .claude/hooks/pre-commit 当前内容 ───"
    head -5 .claude/hooks/pre-commit 2>/dev/null || echo "(不存在)"
    ;;

  *)
    red "❌ 未知参数: $ACTION"
    echo "用法: bash $0 [--dry-run|--install|--uninstall|--status]"
    exit 1
    ;;
esac
