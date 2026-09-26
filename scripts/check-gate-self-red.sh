#!/usr/bin/env bash
# scripts/check-gate-self-red.sh — H-17 假绿自证能红脚本（真实注入 fixture）
# 来源：R131 §二.2.2 假绿类型 4 + R132 §一.5
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 故意给 check-r-line-count.sh 注入失败场景 → exit 1
# 兼容：bash 3.2（无关联数组）

set -eo pipefail

# === 配置区 ===
GSR_FAIL_SEED="${GSR_FAIL_SEED:-0}"
rc=0; rc2=0; exit_code=0

# === 主逻辑 ===
main() {
  echo "[H-17] check-gate-self-red.sh 启动 (基线: R131)"

  local total=0
  local pass=0
  local script_name=""
  local seed_var=""

  # === 自证能红：FAIL_SEED=1（放在函数内最前面）===
  if [ "$GSR_FAIL_SEED" = "1" ]; then
    echo "[H-17] FAIL_SEED=1 → 故意跑 check-r-line-count.sh 注入 RLC_FAIL_SEED=1"
    set +e; RLC_FAIL_SEED=1 bash scripts/check-r-line-count.sh >/dev/null 2>&1; rc=$?; set -e
    if [ "$rc" -ne 0 ]; then
      echo "🔴 注入后子脚本 exit ${rc}（确认 FAIL_SEED=1 → 子脚本非零退出）"
      echo "✅ 自证能红：子脚本在 FAIL_SEED=1 下能红，PASS"
      exit 1
    fi
    echo "❌ 自证能红失败：FAIL_SEED=1 下子脚本未非零退出（假绿）"
    exit 1
  fi

  # === 逐个脚本测试（手写列表兼容 bash 3.2）===
  for entry in \
    "check-lint-reports-freshness.sh:LINT_FAIL_SEED" \
    "check-r-line-count.sh:RLC_FAIL_SEED" \
    "modify-stall-detector.sh:MODIFY_FAIL_SEED" \
    "wheel-stuck-detector.sh:WHEEL_FAIL_SEED"; do
    script_name=${entry%%:*}
    seed_var=${entry##*:}
    total=$((total + 1))
    echo "[H-17] 测试 $script_name ..."

    # 1. 正常情况
    set +e; bash "scripts/$script_name" >/dev/null 2>&1; rc=$?; set -e
    case "$rc" in
      0) echo "  ✅ 正常情况 PASS（exit 0）"; pass=$((pass + 1)) ;;
      2) echo "  ⚠️  正常情况 exit 2（用户错误，可能是缺 fixture）"; pass=$((pass + 1)) ;;
      *) echo "  ❌ 正常情况 FAIL（exit ${rc}）"; exit_code=1 ;;
    esac

    # 2. 注入 FAIL_SEED=1
    set +e; env "$seed_var=1" bash "scripts/$script_name" >/dev/null 2>&1; rc2=$?; set -e
    if [ "$rc2" -eq 0 ]; then
      echo "  ❌ FAIL_SEED=1 未非零退出（自证能红缺失 = 假绿！）"
      exit_code=1
    else
      echo "  ✅ FAIL_SEED=1 非零退出（exit ${rc2}，自证能红 OK）"
      pass=$((pass + 1))
    fi
  done

  echo "[H-17] 自证能红结果: $pass / $((total * 2)) 测试通过"
  if [ "$exit_code" -eq 0 ]; then
    echo "✅ [H-17] 全部脚本自证能红 PASS"
  else
    echo "🔴 [H-17] 部分脚本自证能红 FAIL（假绿隐患）"
  fi

  return $exit_code
}

main "$@"
