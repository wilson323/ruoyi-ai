#!/usr/bin/env bash
# scripts/t2-paiban-sla.sh — T2 拍板 SLA 监测脚本（cron 每日 02:00）
# 来源：R131 §二.2.4 T2 + R132 派单（agency-harness）+ R141 阶段五 5.3 监控接入（BP-013/014/015 C 类 14d owner 必拍 docs-only 监控）
# 撞车 0 让路：✅ scripts/ 白名单；飞书 webhook 仅写 log.md，不实跑
# 自证能红：B 类决策包创建时间改 8 天前 → exit 1 + 「自动通过」日志
# R141 新增：BP_DOCS_ONLY_LIST（BP-013/014/015 三件套 C 类 14d owner 必拍 docs-only 设计）+ BP_COVERAGE_MIN（≥ 80% PASS）

set -uo pipefail

DECISION_DIR="docs/ipd-系统说明/拍板决策包"
LOG_FILE=".harness/t2-paiban-sla.log"
B_AUTO_LIST="07 08 09 10 12 14"   # B 类：7d 未决自动 sign-off
C_REAUDIT_LIST="04 06"           # C 类最大破坏：14d 未决重新评审
BP_DOCS_ONLY_LIST="${BP_DOCS_ONLY_LIST:-BP-013 BP-014 BP-015}"  # R141 阶段五：BP-013 hook / BP-014 CI / BP-015 跨仓 三件套 docs-only 设计清单
BP_COVERAGE_MIN="${BP_COVERAGE_MIN:-80}"  # BP 覆盖度阈值（默认 80%）

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

# ===== R141 阶段五 5.3 新增：BP-013/014/015 三件套 C 类 docs-only 设计监控 + BP-COVERAGE 趋势 =====
log "--- R141 BP 监控扫描启动（BP_DOCS_ONLY_LIST=${BP_DOCS_ONLY_LIST}）---"

bp_doc_count=0
bp_doc_missing=0
for bp in ${BP_DOCS_ONLY_LIST}; do
  case "$bp" in
    BP-013) design_doc="docs/ipd-系统说明/BCP-014-pre-commit-best-practices-hook-设计-20260920.md" ;;
    BP-014) design_doc="docs/ipd-系统说明/BCP-014-pre-commit-best-practices-hook-设计-20260920.md" ;;
    BP-015) design_doc="docs/ipd-系统说明/BCP-014-browser-business-testing-适配设计-20260920.md" ;;
    *) design_doc="" ;;
  esac
  if [ -n "$design_doc" ] && [ -f "$design_doc" ]; then
    log "✅ $bp docs-only 设计文档存在：$design_doc"
    bp_doc_count=$((bp_doc_count + 1))
  else
    log "🔴 $bp docs-only 设计文档缺失：$design_doc → owner 拍板前仅 docs-only 准备，不可实装"
    bp_doc_missing=$((bp_doc_missing + 1))
    exit_code=1
  fi
done

# BP-COVERAGE 趋势：检查主门禁是否仍 ≥ 80% PASS（不在 cron 实跑，仅检测脚本可调用）
if [ -x "scripts/check-best-practices-coverage.sh" ]; then
  log "📊 BP-COVERAGE 监控位：scripts/check-best-practices-coverage.sh 可调用（BP_COVERAGE_MIN=${BP_COVERAGE_MIN}%）"
  log "📊 实跑验证：bash scripts/check-best-practices-coverage.sh + BP_FAIL_SEED=1 bash scripts/check-best-practices-coverage.sh （FAIL_SEED 双向触发验证）"
else
  log "🔴 BP-COVERAGE 主门禁脚本缺失：scripts/check-best-practices-coverage.sh"
  exit_code=1
fi

log "BP 监控完成：docs-only 存在=$bp_doc_count/3 缺失=$bp_doc_missing/3"
log "=== 扫描完成：自动通过=$auto_pass_count 标红=$red_count 重审=$reaudit_count exit=$exit_code ==="
exit $exit_code
