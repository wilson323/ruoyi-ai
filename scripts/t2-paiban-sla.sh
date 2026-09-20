#!/usr/bin/env bash
# scripts/t2-paiban-sla.sh — T2 拍板 SLA 监测脚本（cron 每日 02:00）
# 来源：R131 §二.2.4 T2 + R132 派单（agency-harness）
# 撞车 0 让路：✅ scripts/ 白名单；飞书 webhook 仅写 log.md，不实跑
# 自证能红：B 类决策包创建时间改 8 天前 → exit 1 + 「自动通过」日志

set -uo pipefail

DECISION_DIR="docs/ipd-系统说明/拍板决策包"
LOG_FILE=".harness/t2-paiban-sla.log"
B_AUTO_LIST="07 08 09 10 12 14"   # B 类：7d 未决自动 sign-off
C_REAUDIT_LIST="04 06"           # C 类最大破坏：14d 未决重新评审

mkdir -p "$(dirname "$LOG_FILE")"
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"; }
log "=== T2 拍板 SLA 扫描启动 ==="

today_epoch=$(date +%s)
auto_pass_count=0
red_count=0
reaudit_count=0
exit_code=0

shopt -s nullglob
for f in "$DECISION_DIR"/paiban-*.md; do
  [ "$(basename "$f")" = "README.md" ] && continue
  num=$(basename "$f" | grep -oE 'paiban-[0-9]+' | grep -oE '[0-9]+')
  [ -z "$num" ] && continue

  # 拍板位判定：以"拍板日期：____"是否填写为准（仍是 ____ = 未决）
  if grep -qE '拍板日期：[^_]' "$f" 2>/dev/null; then
    status="已拍"; continue
  else
    status="未决"
  fi

  # 创建时间提取
  ctime=$(grep -oE '创建时间：[0-9]{4}-[0-9]{2}-[0-9]{2}' "$f" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}')
  [ -z "$ctime" ] && continue
  ctime_epoch=$(date -j -f "%Y-%m-%d" "$ctime" +%s 2>/dev/null || date -d "$ctime" +%s 2>/dev/null) || continue
  pending_days=$(( (today_epoch - ctime_epoch) / 86400 ))
  log "paiban-$num 状态=$status 未决=${pending_days}d"

  # B 类 > 7d 自动 sign-off
  if [ "$status" = "未决" ] && echo " $B_AUTO_LIST " | grep -q " $num " && [ "$pending_days" -gt 7 ]; then
    log "🔴 B类自动通过 paiban-$num (${pending_days}d > 7d) → PM-OWNED 接管"
    auto_pass_count=$((auto_pass_count + 1))
    exit_code=1
    continue
  fi

  # C 类 > 7d 标红（仅 log.md，不实跑 webhook）
  if [ "$status" = "未决" ] && [ "$pending_days" -gt 7 ] && ! echo " $B_AUTO_LIST " | grep -q " $num "; then
    log "🔴 C类超时 paiban-$num (${pending_days}d) → webhook 仅 log.md（撞车 0 让路）"
    red_count=$((red_count + 1))
  fi

  # C 类 #4/#6 > 14d 重新评审
  if [ "$status" = "未决" ] && echo " $C_REAUDIT_LIST " | grep -q " $num " && [ "$pending_days" -gt 14 ]; then
    log "🔴 最大破坏重审 paiban-$num (${pending_days}d > 14d)"
    reaudit_count=$((reaudit_count + 1))
    exit_code=1
  fi
done

log "=== 扫描完成：自动通过=$auto_pass_count 标红=$red_count 重审=$reaudit_count exit=$exit_code ==="
exit $exit_code
