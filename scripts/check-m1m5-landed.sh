#!/usr/bin/env bash
# scripts/check-m1m5-landed.sh — M-Root-4 M1-M5 落地门禁
# 来源：R142 §3.2 S-4
# 撞车 0 让路：✅ scripts/ 白名单
# 关系：让 R131 §2.2 写的 5 个验证脚本从"文档名"升级为"可跑门禁" — 任何 1 个未实装 = FAIL
# 自证能红：FAIL_SEED=1 → 模拟 5 脚本全 0 hit → exit 1

set -euo pipefail

# 5 个 R131 §2.2 M1-M5 验证脚本清单（R142 §3.2 S-4 锁定）
M1M5_SCRIPTS=(
  "check-kanban-section-shape.sh"
  "check-time-redline.sh"
  "check-dispatch-sequence.sh"
  "check-cross-repo-cd-guard.sh"
  "check-e2e-block-gate.sh"
)

main() {
  echo "[M-Root-4] check-m1m5-landed.sh 启动 (基线: R142 §3.2 S-4)"

  # FAIL_SEED：模拟 5 脚本全 0 hit
  if [ "${M1M5_FAIL_SEED:-0}" = "1" ]; then
    echo "[FAIL_SEED] 模拟 5 脚本全 0 hit"
    echo "❌ M1M5_FAIL_SEED 注入 → M1-M5 验证脚本视为未实装"
    exit 1
  fi

  local missing=0
  local hit_list=()
  for name in "${M1M5_SCRIPTS[@]}"; do
    local count
    count=$(find scripts/ -name "${name}" -type f | wc -l | tr -d ' ')
    if [ "$count" -lt 1 ]; then
      echo "❌ 缺失脚本: ${name} (find scripts/ -name ${name} → ${count} hit)"
      missing=$((missing + 1))
    else
      hit_list+=("${name}")
    fi
  done

  echo "[M-Root-4] 5 脚本扫描结果：${#hit_list[@]}/5 命中"
  for h in "${hit_list[@]}"; do
    echo "  ✅ ${h}"
  done

  if [ "$missing" -gt 0 ]; then
    echo "🔴 M-Root-4 M1-M5 落地缺 ${missing} 个脚本，exit 1"
    exit 1
  fi

  echo "✅ M-Root-4 M1-M5 验证脚本全部实装"
  exit 0
}

main "$@"
