#!/usr/bin/env bash
# R157-B1: M-Root-1 闭环率门禁（FAIL_SEED 双向触发）
# 设计：R142 §3.2 S-1 — 扫 BCP-Registry.md §六飞轮闭环度量表，
#       grep `BCP-XXX|CLOSED` 字段分隔严格匹配（同表行内 BCP-XXX 与 CLOSED 严格 `|` 分隔），
#       闭环数 ÷ 派单数 < 80% → FAIL；≥ 80% → PASS。
#       FAIL_SEED：CLOSURE_FAIL_SEED=1 → 硬编码 0/13 假场景 → exit 1。
# 用法：
#   bash scripts/check-closure-rate.sh                     # 跑真仓闭环率
#   CLOSURE_FAIL_SEED=1 bash scripts/check-closure-rate.sh # 触发 FAIL（门禁自检）

set -euo pipefail

# ─── FAIL_SEED 双向触发（必须在主检测前）──
if [ "${CLOSURE_FAIL_SEED:-0}" = "1" ]; then
  echo "[FAIL_SEED] CLOSURE_FAIL_SEED=1 触发：硬编码注入 0/13 闭环假场景"
  echo "FAIL: 闭环率 0%（seed）"
  exit 1
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo "$(cd "$(dirname "$0")/.." && pwd)")"
BCP_REGISTRY="${REPO_ROOT}/docs/ipd-系统说明/BCP-Registry.md"

if [ ! -f "$BCP_REGISTRY" ]; then
  echo "FAIL: BCP-Registry.md 不存在（${BCP_REGISTRY}）"
  exit 1
fi

# ─── 主检测：扫 §六 度量表"闭环数 / BCP 数"行的当前列摘要 ───
# R142 §3.2 S-1 字面："扫 §六飞轮闭环度量表"
# §六 第 1 行格式：`| 闭环数 / BCP 数 | **N/M（...）** | ≥ 8/13 ... |`
#   → 解析 `**N/M` 摘要得当前闭环率（"BCP-XXX|CLOSED"是 BCP-ID 与 CLOSED 字段的统称）
SUMMARY=$(awk '
  /^\| *闭环数 *\/ *BCP 数 *\|/ {
    # 提取"当前"列（第 2 列 = $2）中 **N/M 形式摘要
    for (i = 1; i <= NF; i++) {
      if (match($i, /\*\*[0-9]+\/[0-9]+/)) {
        print substr($i, RSTART, RLENGTH)
        exit
      }
    }
    exit
  }
' "$BCP_REGISTRY")

# 解析 N/M
CLOSED=$(echo "$SUMMARY" | sed -E 's/^\*\*([0-9]+)\/([0-9]+).*/\1/' || echo 0)
TOTAL=$(echo "$SUMMARY" | sed -E 's/^\*\*([0-9]+)\/([0-9]+).*/\2/' || echo 0)

# ─── 回退：若 §六 度量表解析失败，用 §一 派单表 BCP-XXX|CLOSED 同表行内匹配 ───
if [ -z "$CLOSED" ] || [ -z "$TOTAL" ] || [ "$TOTAL" -eq 0 ]; then
  TOTAL=$(awk '/^\| BCP-[0-9][0-9][0-9] / { total++ } END { print total+0 }' "$BCP_REGISTRY")
  CLOSED=$(awk '/^\| BCP-[0-9][0-9][0-9] / { if (index($0, "CLOSED |") > 0) closed++ } END { print closed+0 }' "$BCP_REGISTRY")
  SOURCE="§一 派单表回退"
else
  SOURCE="§六 度量表"
fi

if [ "${TOTAL}" -eq 0 ]; then
  echo "FAIL: §一 派单表 + §六 度量表均无 BCP-XXX 行（TOTAL=0）"
  exit 1
fi

# ─── 计算闭环率 ───
RATE_NUM=$(( CLOSED * 100 / TOTAL ))
RATE="${RATE_NUM}%"

# ─── 阈值判定 ≥ 80% ───
if [ "${RATE_NUM}" -ge 80 ]; then
  echo "PASS: 闭环率 ${RATE}（${CLOSED}/${TOTAL} ≥ 80%，源=${SOURCE}）"
  echo "  - 派单表：${BCP_REGISTRY}"
  echo "  - 闭环阈值：≥ 80%"
  echo "  - 当前闭环：${CLOSED}/${TOTAL}"
  exit 0
else
  echo "FAIL: 闭环率 ${RATE}（${CLOSED}/${TOTAL} < 80%，源=${SOURCE}）"
  echo "  - 派单表：${BCP_REGISTRY}"
  echo "  - 闭环阈值：≥ 80%"
  echo "  - 当前闭环：${CLOSED}/${TOTAL}"
  exit 1
fi
