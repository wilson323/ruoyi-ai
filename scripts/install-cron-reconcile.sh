#!/usr/bin/env bash
# scripts/install-cron-reconcile.sh
# R123 常态化:把 R119 病根 #5 reconcile-multi-source.sh 接 cron (每周日 02:00)
#
# 用法:
#   bash scripts/install-cron-reconcile.sh --dry-run    # 默认:只打印不真改(撞车 0)
#   bash scripts/install-cron-reconcile.sh --install    # 真安装到 /etc/cron.d/(需 sudo)
#   bash scripts/install-cron-reconcile.sh --uninstall  # 卸载
#   bash scripts/install-cron-reconcile.sh --status     # 看现状
#
# OPS-09 撞车 0:
#   - 默认 --dry-run,不碰任何系统配置
#   - --install / --uninstall 需 sudo,只写一个文件 /etc/cron.d/ruoyi-ai-reconcile-weekly
#   - 不杀任何进程、不动 SSOT 镜像
#   - 文件权限 0644 (cron.d 标准)

set -e

ACTION="${1:---dry-run}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEMPLATE="$SCRIPT_DIR/cron-templates/ruoyi-ai-reconcile-weekly"
TARGET="/etc/cron.d/ruoyi-ai-reconcile-weekly"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }

case "$ACTION" in
  --dry-run)
    bold "════════════════════════════════════════════════════════════════════"
    bold "  [DRY-RUN] R123 cron 安装预览(不会真改任何系统配置)"
    bold "════════════════════════════════════════════════════════════════════"
    echo
    yellow "模板文件:"
    if [ -f "$TEMPLATE" ]; then
      ls -la "$TEMPLATE"
      echo "──── 内容预览 ────"
      cat "$TEMPLATE"
      echo "─────────────────"
    else
      red "❌ 模板不存在: $TEMPLATE"
      exit 1
    fi
    echo
    yellow "目标位置: $TARGET (0644)"
    echo
    yellow "如要真安装:sudo bash scripts/install-cron-reconcile.sh --install"
    yellow "如要卸载  :sudo bash scripts/install-cron-reconcile.sh --uninstall"
    echo
    green "✓ dry-run 完成,系统未做任何变更"
    ;;

  --install)
    bold "[INSTALL] 真安装到 $TARGET"
    if [ ! -f "$TEMPLATE" ]; then
      red "❌ 模板不存在: $TEMPLATE"
      exit 1
    fi
    if [ -f "$TARGET" ]; then
      yellow "⚠️ 目标已存在,先备份"
      sudo cp -p "$TARGET" "${TARGET}.bak.$(date +%Y%m%d-%H%M%S)"
    fi
    sudo install -m 0644 "$TEMPLATE" "$TARGET"
    green "✓ 安装成功: $TARGET"
    echo
    yellow "验证 (非必须):"
    yellow "  sudo ls -la $TARGET"
    yellow "  cat $TARGET | grep -v '^#' | grep -v '^$'"
    echo
    yellow "卸载方式:sudo bash scripts/install-cron-reconcile.sh --uninstall"
    ;;

  --uninstall)
    bold "[UNINSTALL] 删除 $TARGET"
    if [ ! -f "$TARGET" ]; then
      yellow "⚠️ 目标不存在,无需卸载"
      exit 0
    fi
    sudo rm -f "$TARGET"
    green "✓ 卸载成功"
    ;;

  --status)
    bold "[STATUS] 当前 cron 状态"
    if [ -f "$TARGET" ]; then
      green "✓ 已安装: $TARGET"
      ls -la "$TARGET"
      echo
      cat "$TARGET"
    else
      yellow "未安装: $TARGET"
    fi
    echo
    yellow "─── 当前用户 crontab ───"
    crontab -l 2>/dev/null || echo "(空)"
    ;;

  *)
    red "❌ 未知参数: $ACTION"
    echo "用法: bash $0 [--dry-run|--install|--uninstall|--status]"
    echo "默认 --dry-run"
    exit 1
    ;;
esac
