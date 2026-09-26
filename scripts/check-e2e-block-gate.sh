#!/usr/bin/env bash
# scripts/check-e2e-block-gate.sh — M5 E2E 报告缺终态标记阻断
# 注：本门禁只查"有没有结论"，不查"结论是好是坏"；FAILED 终态同样放行，
#     但会在汇总行显式报 PASSED/FAILED 份数，避免 ✅ 被误读成"E2E 已闭环"（R224）
# 来源：R131 §二.2.2 M5 最小验证
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入 E2E 文件无 passed 标记 → exit 1

set -eo pipefail

E2E_DIR="${E2E_DIR:-docs/ipd-系统说明}"
EBG_FAIL_SEED="${EBG_FAIL_SEED:-0}"

main() {
  echo "[M5] check-e2e-block-gate.sh 启动 (基线: R131)"

  if [ "$EBG_FAIL_SEED" = "1" ]; then
    echo "[M5] FAIL_SEED=1 → 注入 E2E-验收-FAIL.md 无 passed 标记"
    echo "🔴 E2E 缺失 PASSED|FAILED|RUNNING 标记 → 阻断发布"
    exit 1
  fi

  if [ ! -d "$E2E_DIR" ]; then
    echo "⚠️  $E2E_DIR 不存在"
    exit 0
  fi

  # 扫 E2E-验收-*.md 缺终态标记
  # R224 判据修正：旧正则只认 "状态…PASSED/FAILED" / "✅…通过" / "❌…未通过"，
  #   而 check-e2e-fe-be.sh 实际写出的终态行是 "## ❌ 业务契约有失败项"（3 份存量报告均如此），
  #   导致 M5 把"有结论但判据不认识"误报成"缺结论"。现按实际产物措辞 + 机器可读行双口径认定。
  local unblocked=0 failed_verdict=0 passed_verdict=0
  for f in "$E2E_DIR"/E2E-验收-*.md; do
    [ -f "$f" ] || continue
    if ! grep -qE "^状态: *(PASSED|FAILED|RUNNING)|^STATUS: *(PASSED|FAILED|RUNNING)|状态.*(PASSED|FAILED|RUNNING)|✅.*通过|❌.*(未通过|有失败项|不通过)|🏃.*运行中" "$f"; then
      echo "❌ $(basename "$f") 缺终态标记"
      unblocked=$((unblocked + 1))
    fi
    if grep -qE "^状态: *FAILED|^STATUS: *FAILED|❌.*(有失败项|不通过|未通过)" "$f"; then
      failed_verdict=$((failed_verdict + 1))
    elif grep -qE "^状态: *PASSED|^STATUS: *PASSED|✅.*通过" "$f"; then
      passed_verdict=$((passed_verdict + 1))
    fi
  done

  if [ "$unblocked" -gt 0 ]; then
    echo "🔴 $unblocked 份 E2E 文件缺终态标记 → 阻断"
    exit 1
  fi

  echo "✅ 全部 E2E 文件有终态标记（其中终态 PASSED=${passed_verdict} / FAILED=${failed_verdict}）"
  if [ "$passed_verdict" -eq 0 ]; then
    echo "⚠️  无一份终态为 PASSED：M5 职责是『有结论即可放行』，真活契约是否闭环请看分类汇总"
  fi
  exit 0
}

main "$@"
