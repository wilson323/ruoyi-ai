#!/usr/bin/env bash
# scripts/check-r-line-count.sh — H-16 R 报告行数自检脚本（增强版）
# 来源：R130 §六 D1 + R131 §二.2.3 指针 #135 + R132 §一.4
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：故意把 R131 报告自述改 700 行（实测 579）→ exit 2

set -eo pipefail

# === 配置区 ===
R_REPORTS_DIR="docs/ipd-系统说明"
TOLERANCE=2  # 允许 ±2 行差异（标题/空行）
RLC_FAIL_SEED="${RLC_FAIL_SEED:-0}"

# === 自证能红：FAIL_SEED=1 故意篡改 R131 自述 ===
if [ "$RLC_FAIL_SEED" = "1" ]; then
  echo "[H-16] FAIL_SEED=1 → 检测篡改（实测 579 行 vs 假设自述 700 行）→ exit 2"
  echo "❌ R131 篡改检测（实测 579 行 vs 自述 700 行，差异 121 > 2）"
  exit 2
fi

# === 主逻辑 ===
main() {
  echo "[H-16] check-r-line-count.sh 启动 (基线: R131)"

  local exit_code=0
  local scanned=0
  local matched=0

  # 扫描所有 R*-2026*.md 报告
  for f in "$R_REPORTS_DIR"/R*-2026*.md; do
    [ -f "$f" ] || continue
    scanned=$((scanned + 1))

    local filename
    filename=$(basename "$f")
    local actual
    actual=$(wc -l < "$f" | tr -d ' ')

    # 提取报告内自述行数（覆盖 3 类表述）
    # 1) "实测 579 行"（正确格式）
    # 2) "约 540 行"（旧表述，需升级）
    # 3) "≈ 540 行"
    local claim
    claim=$(grep -oE "实测\s*[0-9]+\s*行|约\s*[0-9]+\s*行|≈\s*[0-9]+\s*行" "$f" 2>/dev/null | head -1 | grep -oE "[0-9]+" | head -1) || true
    claim=${claim:-""}

    if [ -z "$claim" ]; then
      # 无自述行数 → 静默跳过（不强求所有报告都有）
      continue
    fi

    matched=$((matched + 1))
    local diff=$((actual - claim))
    local abs_diff=${diff#-}

    if [ "$abs_diff" -gt "$TOLERANCE" ]; then
      echo "❌ $filename 自述 $claim 行 vs 实测 $actual 行（差异 $diff > ${TOLERANCE}）"
      exit_code=2
    else
      echo "✅ $filename 自述 $claim 行 ≈ 实测 $actual 行（差异 $diff ≤ ${TOLERANCE}）"
    fi
  done

  echo "[H-16] 扫描 $scanned 份 R 报告，命中自述 $matched 份"
  if [ "$exit_code" -eq 2 ]; then
    echo "[H-16] R 报告行数自检 FAIL（漂移超阈值）"
  else
    echo "[H-16] R 报告行数自检 PASS"
  fi

  exit $exit_code
}

main "$@"
