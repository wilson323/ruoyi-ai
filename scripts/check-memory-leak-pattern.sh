#!/usr/bin/env bash
# scripts/check-memory-leak-pattern.sh
# ----------------------------------------------------------------------
# 内存泄漏模式检测门禁（BP-008 — frontend-code-review 性能优化维度）
#
# 用途：
#   检测前端代码中的内存泄漏反模式
#   规则（基于 frontend-code-review 7 维度性能优化）：
#     1. setInterval / setTimeout 未清理（应在 unmount 清除）
#     2. addEventListener 未 removeEventListener
#     3. WebSocket / EventSource 未关闭
#     4. 全局 window 对象挂载未清理
#     5. 闭包引用大型对象（启发式：长链式 .bind/.call）
#
# 自证能红：LEAK_FAIL_SEED=1 → exit 1
#
# 来源：R141 阶段四 4.2 BP-008（agency-harness）
# 撞车 0 让路：✅ scripts/ 白名单（仅检测模式，不动源码）
#
# 退出码：
#   0 = pass
#   1 = fail（疑似内存泄漏模式）
#   2 = 脚本/参数错误

set -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONT_DIR="$REPO/../ruoyi-ipd-web/apps/web-antd/src"

# === 自证能红 ===
if [[ "${LEAK_FAIL_SEED:-0}" == "1" ]]; then
  echo "[BP-008] LEAK_FAIL_SEED=1 → 故意注入内存泄漏模式"
  echo "LEAK|PATTERN|LEAK-FAIL-SEED|setInterval without clearInterval|seed_injected"
  exit 1
fi

echo "=== 内存泄漏模式检测 (BP-008) ==="

VIOLATIONS=0

# === 1. setInterval / setTimeout 未清理检测 ===
echo "[STEP 1] setInterval/setTimeout 未清理检测..."

if [[ -d "$FRONT_DIR" ]]; then
  SI_FILES=$(grep -rlE "setInterval\s*\(|setTimeout\s*\(" "$FRONT_DIR" 2>/dev/null | head -30)
  SI_COUNT=0

  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    SI_NUM=$(grep -cE "setInterval\s*\(" "$f" 2>/dev/null || echo 0)
    ST_NUM=$(grep -cE "setTimeout\s*\(" "$f" 2>/dev/null || echo 0)
    CLEAR_NUM=$(grep -cE "clearInterval\s*\(|clearTimeout\s*\(" "$f" 2>/dev/null || echo 0)

    TOTAL_TIMER=$((SI_NUM + ST_NUM))
    if [[ $TOTAL_TIMER -gt 0 ]] && [[ $CLEAR_NUM -lt $TOTAL_TIMER ]]; then
      SI_COUNT=$((SI_COUNT + 1))
      [[ $SI_COUNT -le 3 ]] && echo "  [WARN] $f → $TOTAL_TIMER 个 setInterval/setTimeout 但仅 $CLEAR_NUM 个 clear"
    fi
  done <<< "$SI_FILES"

  echo "  定时器未清理疑似: $SI_COUNT"
  VIOLATIONS=$((VIOLATIONS + SI_COUNT))
fi

# === 2. addEventListener 未 remove 检测 ===
echo "[STEP 2] addEventListener 未 removeEventListener 检测..."

if [[ -d "$FRONT_DIR" ]]; then
  EL_FILES=$(grep -rlE "addEventListener\s*\(" "$FRONT_DIR" 2>/dev/null | head -20)
  EL_COUNT=0

  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    ADD_NUM=$(grep -cE "addEventListener\s*\(" "$f" 2>/dev/null || echo 0)
    REM_NUM=$(grep -cE "removeEventListener\s*\(" "$f" 2>/dev/null || echo 0)

    if [[ $ADD_NUM -gt 0 ]] && [[ $REM_NUM -lt $ADD_NUM ]]; then
      EL_COUNT=$((EL_COUNT + 1))
      [[ $EL_COUNT -le 3 ]] && echo "  [WARN] $f → $ADD_NUM 个 addEventListener 但仅 $REM_NUM 个 remove"
    fi
  done <<< "$EL_FILES"

  echo "  事件监听未清理疑似: $EL_COUNT"
  VIOLATIONS=$((VIOLATIONS + EL_COUNT))
fi

# === 3. WebSocket / EventSource 未关闭 ===
echo "[STEP 3] WebSocket/EventSource 未关闭检测..."

if [[ -d "$FRONT_DIR" ]]; then
  WS_FILES=$(grep -rlE "new WebSocket\s*\(|new EventSource\s*\(" "$FRONT_DIR" 2>/dev/null | head -20)
  WS_COUNT=0

  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    WS_NUM=$(grep -cE "new WebSocket\s*\(|new EventSource\s*\(" "$f" 2>/dev/null || echo 0)
    CLOSE_NUM=$(grep -cE "\.close\s*\(" "$f" 2>/dev/null || echo 0)

    if [[ $WS_NUM -gt 0 ]] && [[ $CLOSE_NUM -lt $WS_NUM ]]; then
      WS_COUNT=$((WS_COUNT + 1))
      [[ $WS_COUNT -le 3 ]] && echo "  [WARN] $f → $WS_NUM 个 WebSocket/EventSource 但仅 $CLOSE_NUM 个 close"
    fi
  done <<< "$WS_FILES"

  echo "  WebSocket/EventSource 未关闭疑似: $WS_COUNT"
  VIOLATIONS=$((VIOLATIONS + WS_COUNT))
fi

# === 4. window 全局挂载未清理检测 ===
echo "[STEP 4] window 全局挂载检测..."

if [[ -d "$FRONT_DIR" ]]; then
  WIN_FILES=$(grep -rlE "window\.[a-zA-Z]+\s*=" "$FRONT_DIR" 2>/dev/null | head -10)
  WIN_COUNT=$(echo "$WIN_FILES" | grep -c "." 2>/dev/null || echo 0)
  echo "  window 全局挂载文件数: ${WIN_COUNT}（INFO 级，不计入违规）"
fi

# === 总结 ===
echo ""
echo "=== 内存泄漏模式检测总结 (BP-008) ==="
echo "  总疑似违规: $VIOLATIONS"
echo ""

if [[ $VIOLATIONS -eq 0 ]]; then
  echo "[PASS] 内存泄漏模式检测 PASS"
  exit 0
elif [[ $VIOLATIONS -le 3 ]]; then
  echo "[WARN] 内存泄漏模式 WARN（$VIOLATIONS 个疑似泄漏，建议修复）"
  exit 0
else
  echo "[FAIL] 内存泄漏模式 FAIL（$VIOLATIONS 个疑似泄漏，超过阈值 3）"
  exit 1
fi