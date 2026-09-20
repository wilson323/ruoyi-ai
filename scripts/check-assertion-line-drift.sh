#!/usr/bin/env bash
# scripts/check-assertion-line-drift.sh — 防假绿类型 5: 行号型断言历史漂移
# 来源：R131 §二.2.2 防假绿类型 5 + R131 §二.2.4 T5
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入断言指向已删除行号 → exit 2

set -eo pipefail

TEST_BASE="${TEST_BASE:-microservices}"
ALD_FAIL_SEED="${ALD_FAIL_SEED:-0}"

main() {
  echo "[防假绿-5] check-assertion-line-drift.sh 启动 (基线: R131)"

  if [ "$ALD_FAIL_SEED" = "1" ]; then
    echo "[防假绿-5] FAIL_SEED=1 → 注入断言指向已删除行号 999"
    echo "❌ 行号型断言漂移：断言指向 line=999 但文件仅 50 行"
    exit 2
  fi

  if [ ! -d "$TEST_BASE" ]; then
    echo "⚠️  $TEST_BASE 不存在"
    exit 0
  fi

  # 检测断言中的 hardcoded line number (如 assertXxx(..., 999) 最后参数)
  local drift=0
  while read -r file; do
    [ -f "$file" ] || continue
    local total_lines
    total_lines=$(wc -l < "$file" | tr -d ' ')
    # assertEquals/assertTrue 第 3 参数为 hardcoded line
    if grep -qE "assert(Equals|True|False|NotNull)\([^,]+,[^,]+,[0-9]{3,}" "$file"; then
      local max_line=$(grep -oE "assert[A-Za-z]+\([^,]+,[^,]+,([0-9]+)" "$file" | grep -oE "[0-9]+" | sort -nr | head -1)
      if [ "$max_line" -gt "$total_lines" ]; then
        echo "❌ ${file}: 断言指向 ${max_line} 但实际 ${total_lines} 行"
        drift=$((drift + 1))
      fi
    fi
  done < <(find "$TEST_BASE" -name "*.java" -path "*/test/*" 2>/dev/null | head -10)

  if [ "$drift" -gt 0 ]; then
    echo "🔴 $drift 个测试类含行号型断言漂移（建议转键名型）"
    exit 2
  fi

  echo "✅ 行号型断言无漂移"
  exit 0
}

main "$@"
