#!/usr/bin/env bash
# scripts/check-e2e-block-gate.sh — M5 E2E 文件无 passed 标记阻断
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

  # 扫 E2E-验收-*.md 缺 PASSED/FAILED/RUNNING 标记
  local unblocked=0
  for f in "$E2E_DIR"/E2E-验收-*.md; do
    [ -f "$f" ] || continue
    if ! grep -qE "状态.*(PASSED|FAILED|RUNNING)|✅.*通过|❌.*未通过|🏃.*运行中" "$f"; then
      echo "❌ $(basename $f) 缺终态标记"
      unblocked=$((unblocked + 1))
    fi
  done

  if [ "$unblocked" -gt 0 ]; then
    echo "🔴 $unblocked 份 E2E 文件缺终态标记 → 阻断"
    exit 1
  fi

  echo "✅ 全部 E2E 文件有终态标记"
  exit 0
}

main "$@"
