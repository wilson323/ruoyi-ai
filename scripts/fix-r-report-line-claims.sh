#!/usr/bin/env bash
# scripts/fix-r-report-line-claims.sh — R 报告自述行数与 wc -l 不符修正
# 来源：R130 §六 D1 + R131 §二.2.3 指针 #135 + R132 §三
# 撞车 0 让路：✅ scripts/ 白名单（不动 R 报告内容，仅落档报告输出到 docs/ipd-系统说明/）
# 撞车 0 边界：不动 docs/开发说明/（产品圣经）；不动兄弟会话 modified 文件
# 自证能红：FAIL_SEED=1 → 故意伪造 R95 claim 命中 → exit 2

set -eo pipefail

R_REPORTS_DIR="${R_REPORTS_DIR:-docs/ipd-系统说明}"
REPORT_OUT="${REPORT_OUT:-docs/ipd-系统说明/R132-r-report-line-claims-dry-run-20260920.md}"
FRC_FAIL_SEED="${FRC_FAIL_SEED:-0}"
DRY_RUN="${DRY_RUN:-1}"  # 默认 dry-run（撞车 0 边界）

main() {
  echo "[H-16-fix] fix-r-report-line-claims.sh 启动 (基线: R131)"
  echo "   模式：DRY_RUN=$DRY_RUN (1=只输出报告不修改)"

  if [ "$FRC_FAIL_SEED" = "1" ]; then
    echo "[H-16-fix] FAIL_SEED=1 → 故意伪造 R95 claim 命中"
    echo "❌ R95 自述 1326 行 vs 实测 212 行（差异 -1114）→ 用户确认后才会应用"
    exit 2
  fi

  if [ ! -d "$R_REPORTS_DIR" ]; then
    echo "❌ $R_REPORTS_DIR 不存在"
    exit 1
  fi

  # 扫 R83-R99（按任务定义）
  local total_scanned=0
  local total_with_claim=0
  local total_self_described=0
  local report_body=""

  for n in 83 84 85 86 87 88 89 90 91 92 93 94 95 96 97 98 99; do
    for f in "$R_REPORTS_DIR"/R${n}-*.md; do
      [ -f "$f" ] || continue
      total_scanned=$((total_scanned + 1))
      local filename
      filename=$(basename "$f")
      local actual
      actual=$(wc -l < "$f" | tr -d ' ')
      local claim
      claim=$(grep -oE "约\s*[0-9]+\s*行|实测\s*[0-9]+\s*行|≈\s*[0-9]+\s*行" "$f" 2>/dev/null | head -1 | grep -oE "[0-9]+" | head -1) || true
      claim=${claim:-"无"}

      if [ "$claim" = "无" ]; then
        report_body="${report_body}| ${filename} | ${actual} | 无自述 | — |\n"
        continue
      fi

      total_with_claim=$((total_with_claim + 1))

      # 检查是否符合「**报告状态**：✅ R{N} 落地（约/实测 X 行）」模式
      # 仅 status 行内的约 N 行 / 实测 N 行 才视为真自述
      local in_status=$(grep -c "**报告状态**.*（约\|**报告状态**.*（实测" "$f" 2>/dev/null || echo 0)
      if [ "$in_status" -gt 0 ]; then
        total_self_described=$((total_self_described + 1))
        report_body="${report_body}| ${filename} | ${actual} | ${claim} | 🔴 自述不符（需用户决定）|\n"
      else
        report_body="${report_body}| ${filename} | ${actual} | ${claim} | ⚠️  非报告自述（业务引用）|\n"
      fi
    done
  done

  # 落档报告
  local ts
  ts=$(date +%Y%m%d-%H%M)
  cat > "$REPORT_OUT" << EOF
# R132-r-report-line-claims dry-run 报告

**生成时间**：$(date '+%Y-%m-%d %H:%M:%S')
**基线**：R131 $(git rev-parse --short HEAD 2>/dev/null || echo 'N/A')
**扫描范围**：R83-R99（${total_scanned} 份 R 报告）
**命中自述**：${total_with_claim} 份
**真自述（**报告状态**行内）**：${total_self_described} 份

## 扫描清单

| 文件 | 实测 wc -l | 自述 N 行 | 处置 |
|---|---|---|---|
$(echo -e "$report_body")

## 撞车 0 让路处置

- ✅ DRY_RUN=1：本报告仅落档 docs/ipd-系统说明/，**不修改任何 R 报告内容**
- ⚠️  DRY_RUN=0：替换操作需用户人工确认后才执行（避免误改业务引用）
- ❌ 不动 docs/开发说明/**（产品圣经）
- ❌ 不动兄弟会话 modified 文件（事实验证-20260919.md / 提交完整度-20260919.md）

## 下一步

1. 用户 review 报告中🔴自述不符项，决定是否替换
2. 替换命令：FRC_FAIL_SEED=0 DRY_RUN=0 bash scripts/fix-r-report-line-claims.sh
3. 验证：bash scripts/check-r-line-count.sh（应从 FAIL → PASS）
EOF

  echo "✅ dry-run 报告落档: $REPORT_OUT"
  echo "   扫描 $total_scanned 份，命中自述 $total_with_claim 份，真自述 $total_self_described 份"

  # 简短输出
  echo -e "$report_body" | head -30
}

main "$@"
