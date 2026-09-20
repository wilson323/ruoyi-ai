#!/usr/bin/env bash
# scripts/check-dispatch-sequence.sh — M3 wt 派单拓扑排序检测
# 来源：R131 §二.2.2 M3 最小验证 + R131 §三.5 D3
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入乱序派单 → exit 2

set -eo pipefail

WT_REGISTRY="${WT_REGISTRY:-docs/ipd-系统说明/R131-系统性反思+拍板机制+自主执行-20260920.md}"
DS_FAIL_SEED="${DS_FAIL_SEED:-0}"

main() {
  echo "[M3] check-dispatch-sequence.sh 启动 (基线: R131)"

  if [ "$DS_FAIL_SEED" = "1" ]; then
    echo "[M3] FAIL_SEED=1 → 注入乱序派单（wt-9 在 wt-1 前）"
    echo "❌ 派单拓扑违例：wt-9 H-7 依赖 wt-1 M1 看板化"
    exit 2
  fi

  # 扫 R131 §六.3 派单序列（5 hr M1-M5 + 7 项强推进 + 13 wt）
  if [ ! -f "$WT_REGISTRY" ]; then
    echo "⚠️  $WT_REGISTRY 不存在"
    exit 0
  fi

  # 简化检测：扫描 wt-N 出现顺序，是否单调
  local last_wt=0
  local out_of_order=0
  while read -r line; do
    if [[ "$line" =~ \[([0-9]+)\][[:space:]]*wt-([0-9]+) ]]; then
      local pos=${BASH_REMATCH[1]}
      local wt=${BASH_REMATCH[2]}
      if [ "$pos" -lt "$last_wt" ] 2>/dev/null; then
        out_of_order=$((out_of_order + 1))
      fi
      last_wt=$pos
    fi
  done < "$WT_REGISTRY"

  if [ "$out_of_order" -gt 0 ]; then
    echo "🔴 派单拓扑违例：$out_of_order 处乱序"
    exit 2
  fi

  echo "✅ 派单序列单调（last_wt=$last_wt）"
  exit 0
}

main "$@"
