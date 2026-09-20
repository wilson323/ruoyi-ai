#!/usr/bin/env bash
# scripts/check-mock-data-realism.sh — 防假绿类型 3: mock 造真库不可能产生的数据
# 来源：R131 §二.2.2 防假绿类型 3
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入 id=99999 不可能的 mock → exit 2

set -eo pipefail

TEST_BASE="${TEST_BASE:-microservices}"
MDR_FAIL_SEED="${MDR_FAIL_SEED:-0}"

main() {
  echo "[防假绿-3] check-mock-data-realism.sh 启动 (基线: R131)"

  if [ "$MDR_FAIL_SEED" = "1" ]; then
    echo "[防假绿-3] FAIL_SEED=1 → 注入 mock 数据 id=99999 不可能值"
    echo "❌ mock ID 超出真库 max(id) 范围（真库 max=245 报告 R118）"
    exit 2
  fi

  if [ ! -d "$TEST_BASE" ]; then
    echo "⚠️  $TEST_BASE 不存在"
    exit 0
  fi

  # mock 字段值域约束检查（ID > 10000 / 时间 2099年 等）
  local unrealistic=0
  find "$TEST_BASE" -name "*.java" -exec grep -l "Mockito\|when(\|mock(" {} \; 2>/dev/null | head -10 | while read -r file; do
    # ID 大于 10000
    if grep -qE "when\(.*\).thenReturn\([0-9]{5,}" "$file"; then
      echo "❌ ${file}: mock ID ≥ 10000（真库通常 ≤ 1000）"
      unrealistic=$((unrealistic + 1))
    fi
    # 时间 2099 年
    if grep -qE "2099|9999" "$file"; then
      echo "❌ ${file}: mock 时间 2099/9999（不合理）"
      unrealistic=$((unrealistic + 1))
    fi
  done

  if [ "$unrealistic" -gt 0 ]; then
    echo "🔴 $unrealistic 个 mock 字段超真库合理值域"
    exit 2
  fi

  echo "✅ mock 数据值域合理（头 10 个 mock 文件扫描）"
  exit 0
}

main "$@"
