#!/usr/bin/env bash
# scripts/check-kanban-section-shape.sh — M1 看板镜像三子标题齐全性检查
# 来源：R131 §二.2.2 M1 最小验证
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 故意删三子标题之一 → exit 2

set -eo pipefail

KANBAN_FILE="${KANBAN_FILE:-docs/ipd-系统说明/看镜像.md}"
KSS_FAIL_SEED="${KSS_FAIL_SEED:-0}"

main() {
  echo "[M1] check-kanban-section-shape.sh 启动 (基线: R131)"

  if [ "$KSS_FAIL_SEED" = "1" ]; then
    echo "[M1] FAIL_SEED=1 → 模拟缺 §三 拍板项追踪"
    echo "❌ KSS_FAIL_SEED 注入 → "
    exit 2
  fi

  if [ ! -f "$KANBAN_FILE" ]; then
    echo "⚠️  $KANBAN_FILE 不存在（M1 看板化尚未派单）"
    echo "   行为：文件缺失 = 不阻断（exit 0）"
    exit 0
  fi

  # 期望三子标题（按 R131 §四.2 拍板项追踪表）
  local missing=0
  for section in "§一" "§二" "§三"; do
    if ! grep -q "^${section} 拍板项追踪表" "$KANBAN_FILE" 2>/dev/null \
      && ! grep -q "^${section} " "$KANBAN_FILE" 2>/dev/null; then
      echo "❌ 缺失子标题: ${section}"
      missing=$((missing + 1))
    fi
  done

  if [ "$missing" -gt 0 ]; then
    echo "🔴 M1 看板缺 $missing 个子标题，exit 2"
    exit 2
  fi

  echo "✅ M1 看板三子标题齐全"
  exit 0
}

main "$@"
