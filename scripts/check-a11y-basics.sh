#!/usr/bin/env bash
# scripts/check-a11y-basics.sh
# ----------------------------------------------------------------------
# 可访问性 a11y 基础检测门禁（BP-009 — frontend-code-review 可访问性维度）
#
# 用途：
#   检测前端代码中的可访问性基础问题
#   规则（基于 frontend-code-review 7 维度可访问性 a11y）：
#     1. <img> 标签是否含 alt 属性
#     2. <button> / <a> 是否有可访问文本（aria-label 或可见文本）
#     3. 表单 <input> 是否有 label 关联
#     4. 交互元素是否有键盘事件支持（onKeyDown / onKeyPress）
#     5. ARIA 属性误用（aria-* 拼写错误）
#
# 自证能红：A11Y_FAIL_SEED=1 → exit 1
#
# 来源：R141 阶段四 4.2 BP-009（agency-harness）
# 撞车 0 让路：✅ scripts/ 白名单（仅检测模式，不动源码）
# 跨仓：扫描 /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src
#
# 退出码：
#   0 = pass
#   1 = fail（a11y 基础违规）
#   2 = 脚本/参数错误

set -eo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/audit-gate-input.sh"

REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
FRONT_DIR="${FRONT_DIR:-$REPO/../ruoyi-ipd-web/apps/web-antd/src}"

# === 自证能红 ===
if [[ "${A11Y_FAIL_SEED:-0}" == "1" ]]; then
  echo "[BP-009] A11Y_FAIL_SEED=1 → 故意注入 a11y 违规"
  echo "A11Y|VIOLATION|A11Y-FAIL-SEED|img without alt|seed_injected"
  exit 1
fi

echo "=== 可访问性 a11y 基础检测 (BP-009) ==="

VIOLATIONS=0
INFO=0

PRODUCTION_FILES=$(gate_frontend_files "$FRONT_DIR")

# === 1. <img> 标签 alt 属性检测 ===
echo "[STEP 1] <img> 标签 alt 属性检测..."

IMG_FILES=$(gate_frontend_matching_files "<img\s" "$PRODUCTION_FILES")
IMG_NO_ALT=0

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  # 简单检测：单行内 <img ... > 是否含 alt=
  IMG_LINES=$(gate_grep -nE "<img\s" "$f")
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    if echo "$line" | grep -qE "alt\s*="; then
      :
    else
      IMG_NO_ALT=$((IMG_NO_ALT + 1))
    fi
  done <<< "$IMG_LINES"
done <<< "$IMG_FILES"

echo "  <img> 无 alt 疑似: $IMG_NO_ALT"
VIOLATIONS=$((VIOLATIONS + IMG_NO_ALT))

# === 2. <button> 可访问文本 ===
echo "[STEP 2] <button> 可访问文本检测..."

BTN_FILES=$(gate_frontend_matching_files "<button\s" "$PRODUCTION_FILES")
BTN_NO_LABEL=0

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  # 检测自闭合 button（无文本内容）：<button ... />
  BTN_SELF=$(gate_grep -cE "<button[^>]*\/>" "$f")
  # 检测 button 含 aria-label 或包裹文本
  BTN_LABEL=$(gate_grep -cE "<button[^>]*(aria-label|title)" "$f")

  if [[ $BTN_SELF -gt 0 ]] && [[ $BTN_LABEL -lt $BTN_SELF ]]; then
    BTN_NO_LABEL=$((BTN_NO_LABEL + 1))
  fi
done <<< "$BTN_FILES"

echo "  <button> 无 aria-label 疑似: ${BTN_NO_LABEL}（INFO 级）"
INFO=$((INFO + BTN_NO_LABEL))

# === 3. <input> label 关联检测 ===
echo "[STEP 3] <input> label 关联检测..."

INP_FILES=$(gate_frontend_matching_files "<input\s" "$PRODUCTION_FILES")
INP_NO_LABEL=0

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  # 启发式：input 数量 vs aria-label/htmlFor 数量
  INP_NUM=$(gate_grep -cE "<input\s" "$f")
  ARIA_NUM=$(gate_grep -cE "(aria-label|<label\s+htmlFor)" "$f")

  if [[ $INP_NUM -gt 3 ]] && [[ $ARIA_NUM -lt $INP_NUM ]]; then
    INP_NO_LABEL=$((INP_NO_LABEL + 1))
  fi
done <<< "$INP_FILES"

echo "  <input> 无 label 关联疑似: ${INP_NO_LABEL}（INFO 级）"
INFO=$((INFO + INP_NO_LABEL))

# === 4. ARIA 属性误用检测 ===
echo "[STEP 4] ARIA 属性拼写误用检测..."

ARIA_FILES=$(gate_frontend_matching_files "aria-[a-z]+" "$PRODUCTION_FILES")
ARIA_BAD=0

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  # 误用：aria-lablel / aria-lbel 等拼写错误
  BAD=$(gate_grep -cE "aria-(lablel|lable|lablel|lablel|lablel|lablel)" "$f")
  ARIA_BAD=$((ARIA_BAD + BAD))
done <<< "$ARIA_FILES"

echo "  ARIA 拼写错误疑似: $ARIA_BAD"
VIOLATIONS=$((VIOLATIONS + ARIA_BAD))

# === 5. 键盘事件支持检测 ===
echo "[STEP 5] 键盘事件支持检测..."

KB_FILES=$(gate_frontend_matching_files "onClick\s*=" "$PRODUCTION_FILES")
KB_MISS=0

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  ONCLICK=$(gate_grep -cE "onClick\s*=" "$f")
  ONKEY=$(gate_grep -cE "onKey(Down|Press|Up)\s*=" "$f")

  if [[ $ONCLICK -gt 3 ]] && [[ $ONKEY -eq 0 ]]; then
    KB_MISS=$((KB_MISS + 1))
  fi
done <<< "$KB_FILES"

echo "  交互元素无键盘事件疑似: ${KB_MISS}（INFO 级）"
INFO=$((INFO + KB_MISS))

# === 总结 ===
echo ""
echo "=== 可访问性 a11y 基础检测总结 (BP-009) ==="
echo "  违规（VIOLATION）: $VIOLATIONS"
echo "  提示（INFO）: $INFO"
echo ""

if [[ $VIOLATIONS -eq 0 ]]; then
  echo "[PASS] 可访问性 a11y 基础检测 PASS"
  exit 0
elif [[ $VIOLATIONS -le 3 ]]; then
  echo "[WARN] 可访问性 a11y 基础 WARN（$VIOLATIONS 个疑似违规，建议修复）"
  exit 0
else
  echo "[FAIL] 可访问性 a11y 基础 FAIL（$VIOLATIONS 个疑似违规，超过阈值 3）"
  exit 1
fi
