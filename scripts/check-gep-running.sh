#!/usr/bin/env bash
# scripts/check-gep-running.sh — M-Root-5 GEP 运行态门禁
# 来源：R142 §3.2 S-5（系统性根因反思深化 + 根除机制补齐，2026-09-20）
# 撞车 0 让路：✅ scripts/ + .harness/evolve/failures.jsonl（只读扫描）
# 自证能红：GEP_FAIL_SEED=1 → 故意把数据行清零 → exit 1

set -uo pipefail

FAILURES_FILE=".harness/evolve/failures.jsonl"

echo "[GEP] check-gep-running.sh 启动 (基线: R142 §3.2 S-5)"

# FAIL_SEED：双向触发标配 —— owner 可用此变量自证脚本能红
if [ "${GEP_FAIL_SEED:-0}" = "1" ]; then
  echo "[GEP] FAIL_SEED=1 → 故意把数据行清零（= 文档态）"
  echo "GEP|FAIL|seed_injected|failures-jsonl-data-rows-zero"
  exit 1
fi

# 主逻辑：扫 .harness/evolve/failures.jsonl，注释行（以 # 开头）除外
if [ ! -f "$FAILURES_FILE" ]; then
  echo "FAIL: $FAILURES_FILE 不存在（= 文档态，GEP 未真实运行过）"
  echo "GEP|FAIL|missing|failures-jsonl-not-found"
  exit 1
fi

# 用 < file 直接喂 wc，避免 pipefail 把 grep 无匹配时的退出码 1 放大
data_lines=$(grep -v '^#' "$FAILURES_FILE" 2>/dev/null | wc -l | tr -d ' ')
data_lines="${data_lines:-0}"

if [ "$data_lines" -lt 1 ] 2>/dev/null; then
  echo "FAIL: $FAILURES_FILE 仅有注释行，0 数据行（= 文档态，GEP 未真实运行过）"
  echo "GEP|FAIL|doc_state|failures-jsonl-data-rows-zero"
  exit 1
fi

echo "✅ GEP 运行态：failures.jsonl 数据行 ${data_lines} ≥ 1"
exit 0
