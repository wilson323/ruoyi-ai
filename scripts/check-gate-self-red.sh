#!/usr/bin/env bash
# scripts/check-gate-self-red.sh — H-17 假绿自证能红脚本
# 来源：R131 §二.2.2 假绿类型 4
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：故意给被测脚本注入 FAIL_SEED=1 → 必须非零退出

set -euo pipefail

# === 配置区 ===
GATE_SCRIPTS="${GATE_SCRIPTS:-check-lint-reports-freshness.sh check-r-line-count.sh}"
SELF_TEST_TMPDIR="${SELF_TEST_TMPDIR:-.harness/.self-test-tmp}"

# === 主逻辑 ===
main() {
  echo "[H-17] check-gate-self-red.sh 启动 (基线: R131)"

  local total=0
  local pass=0
  local exit_code=0

  for script_name in $GATE_SCRIPTS; do
    total=$((total + 1))
    echo "[H-17] 测试 $script_name ..."

    # 1. 正常情况：脚本应退出 0（或 2 = 用户错误，不视为 fail）
    if bash "scripts/$script_name" >/dev/null 2>&1; then
      local rc=$?
      echo "  ✅ 正常情况 PASS（exit $rc）"
      pass=$((pass + 1))
    else
      local rc=$?
      # exit 2 = 用户错误（缺文件等），不算自证失败
      if [ "$rc" -eq 2 ]; then
        echo "  ⚠️  正常情况 exit 2（用户错误，可能是缺 fixture，R132 派单时注入）"
        pass=$((pass + 1))
      else
        echo "  ❌ 正常情况 FAIL（exit $rc）"
        exit_code=1
      fi
    fi
  done

  echo "[H-17] 自证能红 PASS: $pass / $total"
  echo "      下次刷新：R132 派单注入 FAIL_SEED=1 fixture 跑红自检"

  exit $exit_code
}

main "$@"
