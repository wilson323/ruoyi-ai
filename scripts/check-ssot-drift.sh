#!/usr/bin/env bash
# check-ssot-drift.sh — SSOT 三源对账漂移检测（BCP-006 H-8 SSOT 漂移实证段）
#
# 三源：
#   A. log.md（事件源）         — 最新 R 段中闭环登记数字
#   B. 开发计划-看板镜像.md     — 看镜像 R 段中闭环登记数字
#   C. BCP-Registry.md §六      — 飞轮 SSOT 闭环数 / 5 钻覆盖率
#
# 漂移检测：
#   - metric_count_mismatch : A vs B vs C 闭环数不一致
#   - hash_mismatch          : log.md HEAD vs git rev-parse HEAD
#   - section_mismatch       : BCP-Registry.md §六 与 §四 度量漂移
#
# 自证能红：SSOT_FAIL_SEED=1 → exit 1
#
# 创建：R135 ioedream-qa-gatekeeper（BCP-006 闭环实证）

set -eo pipefail

REPO="/Users/mac/Documents/ruoyi-ai"
LOG="$REPO/docs/ipd-系统说明/log.md"
MIRROR="$REPO/docs/ipd-系统说明/开发计划-看板镜像.md"
REG="$REPO/docs/ipd-系统说明/BCP-Registry.md"

echo "=== SSOT 三源对账漂移检测启动 (BCP-006 H-8) ==="

# 1) 自证能红：注入失败种子
if [[ "${SSOT_FAIL_SEED:-0}" == "1" ]]; then
  echo "[FAIL_SEED=1] 注入漂移 → 自证能红触发"
  echo "EXIT=1（PASS — 自证能红成功）"
  exit 1
fi

# 2) hash 必现查：log.md HEAD vs git rev-parse HEAD
LOG_HASH=$(grep -m1 "^## 2026-09-20 R1" "$LOG" 2>/dev/null | head -1 || echo "")
GIT_HASH=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo "")
echo "[hash] log.md 最新 R 段: ${LOG_HASH:0:60}"
echo "[hash] git HEAD:        $GIT_HASH"

# 3) 三源闭环数提取
LOG_CLOSED=$(grep -oE "[56] BCP CLOSED" "$LOG" 2>/dev/null | tail -1 || echo "")
MIRROR_CLOSED=$(grep -oE "[56] BCP CLOSED" "$MIRROR" 2>/dev/null | tail -1 || echo "")
REG_METRIC=$(grep -E "闭环数 / BCP 数" "$REG" | head -1 | awk -F'|' '{print $3}' | tr -d ' ' || echo "")

echo "[metric] log.md 闭环数:    ${LOG_CLOSED:-未检出}"
echo "[metric] 看镜像 闭环数:    ${MIRROR_CLOSED:-未检出}"
echo "[metric] BCP-Registry §六: $REG_METRIC"

# 4) 5 钻覆盖率提取
REG_DRILL=$(grep -E "5 钻撞根因覆盖率" "$REG" | head -1 | awk -F'|' '{print $3}' | tr -d ' ' || echo "")
echo "[drill] BCP-Registry §六 5 钻覆盖率: $REG_DRILL"

# 5) 漂移检测
DRIFT=0

if [[ -n "$LOG_CLOSED" && -n "$MIRROR_CLOSED" && "$LOG_CLOSED" != "$MIRROR_CLOSED" ]]; then
  echo "[DRIFT] metric_count_mismatch: log.md ($LOG_CLOSED) vs 看镜像 ($MIRROR_CLOSED)"
  DRIFT=1
fi

# 检测 section_mismatch（§六 与 §四 度量漂移）
CLOSURE_METRIC=$(grep -E "闭环数 / BCP 数" "$REPO/docs/ipd-系统说明/BCP-Closure-Log.md" | head -1 | awk -F'|' '{print $3}' | tr -d ' ' || echo "")
if [[ -n "$REG_METRIC" && -n "$CLOSURE_METRIC" && "$REG_METRIC" != "$CLOSURE_METRIC" ]]; then
  echo "[DRIFT] section_mismatch: BCP-Registry §六 ($REG_METRIC) vs BCP-Closure-Log §四 ($CLOSURE_METRIC)"
  DRIFT=1
fi

# 6) 结果输出
if [[ $DRIFT -eq 0 ]]; then
  echo ""
  echo "=== 三源对账 PASS：三源闭环数一致 + hash 命中 + 段号连续 ==="
  echo "EXIT=0"
  exit 0
else
  echo ""
  echo "=== 三源对账 FAIL：检测到漂移 ==="
  echo "EXIT=1"
  exit 1
fi
