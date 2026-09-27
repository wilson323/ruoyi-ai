#!/usr/bin/env bash
# scripts/check-dispatch-sequence.sh — M3 wt 派单拓扑序检测
# 来源：R131 §二.2.2 M3 最小验证 + R131 §三.5 D3
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：R231 改造 — DS_FAIL_SEED=1 → 复制真实登记表注入乱序行 → 真判据 → exit 2
#
# R231 处置结论：修，不废弃（看板 8500d227 待拍2）。理由：
#   与 M4（检查对象根本不可观测）不同，本门禁的检查对象——R131 §六.3 派单登记行的
#   [N] wt- 序号单调性——是文件内可机械验证的真命题。登记表固化为历史 SSOT 后，
#   其价值转为「防篡改/防重排哨兵」：任何人重写或回改派单序列段即红；若未来轮次在
#   该文件续登 [N] wt- 行，检查恢复原始意图继续生效。意图结构上成立 → 按二选一规则修。
# R231 病灶修复：
#   病灶1 WT_REGISTRY 缺失 exit 0 = 假绿 → 改 exit 2（期望 SSOT 丢失是配置错，要红）
#   病灶2 输出乱码（历史版本 last_wt=$last_wt）紧跟全角括号触发 R224 吞字节）→ 已 ${last_wt} 化
# 退出码词表：0=序列单调 / 2=乱序或数据源缺失
set -eo pipefail

WT_REGISTRY="${WT_REGISTRY:-docs/ipd-系统说明/R131-系统性反思+拍板机制+自主执行-20260920.md}"
DS_FAIL_SEED="${DS_FAIL_SEED:-0}"
TMP_SEED_DIR=""

cleanup() {
  if [ -n "$TMP_SEED_DIR" ]; then
    rm -rf "$TMP_SEED_DIR"
  fi
}
trap cleanup EXIT

main() {
  echo "[M3] check-dispatch-sequence.sh 启动 (基线: R131, 判据: R231 缺失红+防篡改哨兵)"

  local target="$WT_REGISTRY"
  if [ "$DS_FAIL_SEED" = "1" ]; then
    # 负向自证：真实登记表为底，在末尾追加一条回退序号行 [1]，让真判据去抓
    if [ ! -f "$WT_REGISTRY" ]; then
      echo "❌ DS_FAIL_SEED 需要真实登记表作底（${WT_REGISTRY} 不存在），无法注入"
      exit 2
    fi
    TMP_SEED_DIR=$(mktemp -d)
    cp "$WT_REGISTRY" "$TMP_SEED_DIR/registry.md"
    echo "  [1] wt-99 FAIL_SEED 注入乱序行（R231 自证能红专用）" >> "$TMP_SEED_DIR/registry.md"
    target="$TMP_SEED_DIR/registry.md"
    echo "[M3] DS_FAIL_SEED=1 → 注入「[1] 排在 [11] 之后」乱序行，走真判据"
  fi

  if [ ! -f "$target" ]; then
    echo "❌ ${target} 不存在：派单登记 SSOT 丢失或 WT_REGISTRY 配置错误 → 红"
    exit 2
  fi

  # 扫描 [N] wt-M 行：N（登记顺位）必须单调不减
  local last_pos=0
  local out_of_order=0
  local line pos
  while read -r line; do
    if [[ "$line" =~ \[([0-9]+)\][[:space:]]*wt-([0-9]+) ]]; then
      pos=${BASH_REMATCH[1]}
      if [ "$pos" -lt "$last_pos" ] 2>/dev/null; then
        echo "❌ 派单拓扑违例：[ ${pos} ] 出现在 [ ${last_pos} ] 之后（行: ${line%%（*}）"
        out_of_order=$((out_of_order + 1))
      fi
      last_pos=$pos
    fi
  done < "$target"

  if [ "$out_of_order" -gt 0 ]; then
    echo "🔴 M3 派单序列乱序 ${out_of_order} 处，exit 2"
    exit 2
  fi

  if [ "$last_pos" -eq 0 ]; then
    echo "⚠️ 登记表中未扫到任何 [N] wt- 行（0 项可验，视为窗口内通过但请核实 SSOT 是否被清空）"
  fi

  echo "✅ 派单序列单调（last_pos=${last_pos}）"
  exit 0
}

main "$@"
