#!/usr/bin/env bash
# scripts/check-r-line-count.sh — H-16 R 报告行数自检脚本（T10 触发器）
# 来源：R130 §六 D1 + R131 §二.2.3 指针 #135
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：故意把 R131 报告自述改 700 行（实测 579）→ exit 2

set -eo pipefail

# === 配置区 ===
R_REPORTS_DIR="docs/ipd-系统说明"
TOLERANCE=2  # 允许 ±2 行差异（标题/空行）

# === 主逻辑 ===
main() {
  echo "[H-16] check-r-line-count.sh 启动 (基线: R131)"

  local exit_code=0

  # 扫描所有 R*-2026*.md 报告
  for f in "$R_REPORTS_DIR"/R*-2026*.md; do
    [ -f "$f" ] || continue

    local filename
    filename=$(basename "$f")
    local actual
    actual=$(wc -l < "$f" | tr -d ' ')

    # 提取报告内自述行数（grep "约 N 行" 或 "约 540" 等模式）
    local claim
    claim=$(grep -oE "实测\s*[0-9]+\s*行" "$f" 2>/dev/null | head -1 | grep -oE "[0-9]+" | head -1) || true
    claim=${claim:-""}

    if [ -z "$claim" ]; then
      echo "⚠️  $filename 无自述行数声明（跳过）"
      continue
    fi

    local diff=$((actual - claim))
    local abs_diff=${diff#-}

    if [ "$abs_diff" -gt "$TOLERANCE" ]; then
      echo "❌ $filename 自述 $claim 行 vs 实测 $actual 行（差异 $diff > $TOLERANCE）"
      exit_code=2
    else
      echo "✅ $filename 自述 $claim 行 ≈ 实测 $actual 行（差异 $diff ≤ $TOLERANCE）"
    fi
  done

  if [ "$exit_code" -eq 2 ]; then
    echo "[H-16] R 报告行数自检 FAIL（漂移超阈值）"
  else
    echo "[H-16] R 报告行数自检 PASS"
  fi

  exit $exit_code
}

main "$@"
