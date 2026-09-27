#!/usr/bin/env bash
# scripts/check-time-redline.sh — M2 拍板超期红线检测
# 来源：R131 §二.2.2 M2 最小验证
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：R231 改造 — TR_FAIL_SEED=1 → 临时目录注入「2020 年截止且未拍板」假卡 → 真判据 → exit 1
#
# R231 病灶修复（看板 8500d227 待拍2，owner 拍板「按建议执行」）：
#   病灶1 PAIBAN_DIR 缺失时 exit 0「目录缺失=不阻断」= 假绿；配置指向不存在必须红
#   病灶2 用文件 mtime 判超期不靠得住（git checkout / touch 即可伪造"未过期"）
#   修复：解析 paiban-*.md 卡面自带的「截止：YYYY-MM-DD」与「拍板日期：____」真实状态：
#     · 已拍板 = 卡面拍板日期已填日期，或该 paiban 序号被同目录 OWNER-拍板登记*.md 收录
#       （登记文件 = owner 一次性拍板凭据，如 2026-09-21 P3 九项全批准）
#     · pending = 两者皆无
#     · 红线 = pending 且 今天已越过截止日（截止日当天仍算窗口内，过完 23:59:59 才红，防误红）
#     · pending 且卡面「截止」缺失/不可解析 = 无法证明未超期 → 同样记红（不许静默失效）
#   注：本门禁未接 pre-commit、不阻断提交；真红海啸（多张 pending 卡过期）是设计预期，
#       不得为规避红而放宽判据。
# 退出码词表：0=无超期 / 1=存在超期或卡格式失据 / 2=数据源配置缺失（目录不存在）

set -eo pipefail

PAIBAN_DIR="${PAIBAN_DIR:-docs/ipd-系统说明/拍板决策包}"
TR_FAIL_SEED="${TR_FAIL_SEED:-0}"
TMP_SEED_DIR=""

cleanup() {
  if [ -n "$TMP_SEED_DIR" ]; then
    rm -rf "$TMP_SEED_DIR"
  fi
}
trap cleanup EXIT

# YYYY-MM-DD → epoch 秒（macOS BSD date 优先，GNU date 兜底；全失败输出空）
date_to_epoch() {
  date -j -f "%Y-%m-%d" "$1" +%s 2>/dev/null \
    || date -d "$1" +%s 2>/dev/null \
    || true
}

main() {
  echo "[M2] check-time-redline.sh 启动 (基线: R131, 判据: R231 解析卡面 pending 真实状态)"

  local scan_dir="$PAIBAN_DIR"
  if [ "$TR_FAIL_SEED" = "1" ]; then
    # 负向自证：真实决策包为底 + 注入一张过期未拍板假卡，让真判据去抓
    if [ ! -d "$PAIBAN_DIR" ]; then
      echo "❌ TR_FAIL_SEED 需要真实决策包作底（${PAIBAN_DIR} 不存在），无法注入"
      exit 2
    fi
    TMP_SEED_DIR=$(mktemp -d)
    cp "$PAIBAN_DIR"/*.md "$TMP_SEED_DIR"/ 2>/dev/null || true
    cat > "$TMP_SEED_DIR/paiban-99-seed-overdue.md" <<'SEED'
# Paiban-99 种子假卡（R231 自证能红专用，勿当真实拍板项）
> 创建时间：2020-01-01                截止：2020-01-02
owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
SEED
    scan_dir="$TMP_SEED_DIR"
    echo "[M2] TR_FAIL_SEED=1 → 注入 paiban-99（截止 2020-01-02 未拍板），走真判据"
  fi

  # 区分「合法无数据」与「配置错丢失」：目录本身不存在 = 配置错 → 红
  if [ ! -d "$scan_dir" ]; then
    echo "❌ ${scan_dir} 不存在：拍板决策包 SSOT 丢失或 PAIBAN_DIR 配置错误 → 红"
    exit 2
  fi

  local now_epoch
  now_epoch=$(date +%s)
  local overdue=0 pending_ok=0 decided=0
  local f base pid due due_end reg decided_by_reg
  for f in "$scan_dir"/paiban-*.md; do
    [ -f "$f" ] || continue
    base=$(basename "$f")

    # 判据1：卡面拍板日期已填真实日期 → 已拍板
    decided=0
    if grep -qE "拍板日期： *2[0-9]{3}-[0-9]{2}-[0-9]{2}" "$f"; then
      decided=1
    fi
    # 判据2：paiban 序号被 OWNER-拍板登记*.md 收录 → 登记凭据即已拍板
    decided_by_reg=""
    if [ "$decided" = "0" ]; then
      pid=$(printf '%s' "$base" | sed -nE 's/^(paiban-[0-9]+).*/\1/p')
      if [ -n "$pid" ]; then
        for reg in "$scan_dir"/OWNER-拍板登记*.md; do
          if [ -f "$reg" ] && grep -q -- "$pid" "$reg"; then
            decided=1
            decided_by_reg=$(basename "$reg")
            break
          fi
        done
      fi
    fi
    if [ "$decided" = "1" ]; then
      echo "  · ${base} 已拍板${decided_by_reg:+（凭据: ${decided_by_reg}）}"
      continue
    fi

    # pending：解析截止日
    due=$(sed -nE 's/.*截止：(2[0-9]{3}-[0-9]{2}-[0-9]{2}).*/\1/p' "$f" | head -1)
    if [ -z "$due" ]; then
      echo "🔴 OVERDUE|${base}|卡面无「截止：YYYY-MM-DD」，无法证明未超期"
      overdue=$((overdue + 1))
      continue
    fi
    due_end=$(date_to_epoch "$due")
    if [ -z "$due_end" ]; then
      echo "🔴 OVERDUE|${base}|截止日「${due}」不可解析，无法证明未超期"
      overdue=$((overdue + 1))
      continue
    fi
    due_end=$((due_end + 86399))  # 截止日当天 23:59:59 前仍算窗口内
    if [ "$now_epoch" -gt "$due_end" ]; then
      echo "🔴 OVERDUE|${base}|截止 ${due} 已过 $(( (now_epoch - due_end) / 3600 ))h 且无拍板记录"
      overdue=$((overdue + 1))
    else
      pending_ok=$((pending_ok + 1))
    fi
  done

  if [ "$overdue" -gt 0 ]; then
    echo "🔴 M2 拍板超期：${overdue} 项红（本门禁未接 pre-commit，红=真话，不阻断提交）"
    exit 1
  fi

  echo "✅ 无超期：已拍板跳过、pending 均在窗口内（pending 未到期 ${pending_ok} 项）"
  exit 0
}

main "$@"
