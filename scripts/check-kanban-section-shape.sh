#!/usr/bin/env bash
# scripts/check-kanban-section-shape.sh — M1 看板镜像三大 U 段齐全性检查
# 来源：R131 §二.2.2 M1 最小验证
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：R231 改造 — KSS_FAIL_SEED=1 → 复制真实镜像删 "## U2" 段 → 走真判据 → exit 2
#
# R231 病灶修复（看板 8500d227 待拍2，owner 拍板「按建议执行」）：
#   病灶1 默认路径写成 docs/ipd-系统说明/看镜像.md（从未存在），真实 SSOT 是
#          docs/ipd-系统说明/开发计划-看板镜像.md → 永远命中缺失分支
#   病灶2 缺失分支 exit 0「不阻断」→ 整个门禁死件（永远绿）
#   病灶3 判据找 §一/§二/§三，但镜像实际小节结构是 "## U0/## U1/## U2"（2026-09 实查）
#   修复：路径对齐真实 SSOT + 判据对齐真实结构 + 镜像缺失 = exit 2（缺 SSOT 是要红的事）
# 退出码词表：0=三大 U 段齐全 / 2=缺小节或缺 SSOT

set -eo pipefail

KANBAN_FILE="${KANBAN_FILE:-docs/ipd-系统说明/开发计划-看板镜像.md}"
KSS_FAIL_SEED="${KSS_FAIL_SEED:-0}"
TMP_SEED_DIR=""

cleanup() {
  if [ -n "$TMP_SEED_DIR" ]; then
    rm -rf "$TMP_SEED_DIR"
  fi
}
trap cleanup EXIT

main() {
  echo "[M1] check-kanban-section-shape.sh 启动 (基线: R131, 判据: R231 对齐镜像真实 U0/U1/U2 结构)"

  local target="$KANBAN_FILE"
  if [ "$KSS_FAIL_SEED" = "1" ]; then
    # 负向自证：以真实镜像为底，删掉 "## U2" 段（含标题行），让真判据去抓
    if [ ! -f "$KANBAN_FILE" ]; then
      echo "❌ KSS_FAIL_SEED 需要真实镜像作底（${KANBAN_FILE} 不存在），无法注入"
      exit 2
    fi
    TMP_SEED_DIR=$(mktemp -d)
    awk '/^## U2/{skip=1; next} /^## /{skip=0} !skip' "$KANBAN_FILE" > "$TMP_SEED_DIR/镜像-缺U2段.md"
    target="$TMP_SEED_DIR/镜像-缺U2段.md"
    echo "[M1] KSS_FAIL_SEED=1 → 注入缺「## U2」段的临时副本，走真判据"
  fi

  if [ ! -f "$target" ]; then
    echo "❌ ${target} 不存在：看板镜像 SSOT 缺失（路径配错或被误删）→ M1 红"
    exit 2
  fi

  # 期望三大优先级段（以真实镜像 H2 标题为单一真相源）
  local missing=0
  local section
  for section in "## U0" "## U1" "## U2"; do
    if ! grep -q "^${section}" "$target" 2>/dev/null; then
      echo "❌ 缺失小节: ${section}*"
      missing=$((missing + 1))
    fi
  done

  if [ "$missing" -gt 0 ]; then
    echo "🔴 M1 看板镜像缺 ${missing} 个小节，exit 2"
    exit 2
  fi

  echo "✅ M1 看板镜像 U0/U1/U2 三大段齐全"
  exit 0
}

main "$@"
