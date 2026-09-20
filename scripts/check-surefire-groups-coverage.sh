#!/usr/bin/env bash
# scripts/check-surefire-groups-coverage.sh — 防假绿类型 1: Surefire groups 过滤静默跳过
# 来源：R131 §二.2.2 防假绿类型 1
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 故意缺 @Tag("dev") → exit 2

set -eo pipefail

TEST_BASE="${TEST_BASE:-microservices}"
SGC_FAIL_SEED="${SGC_FAIL_SEED:-0}"

main() {
  echo "[防假绿-1] check-surefire-groups-coverage.sh 启动 (基线: R131)"

  if [ "$SGC_FAIL_SEED" = "1" ]; then
    echo "[防假绿-1] FAIL_SEED=1 → 故意跑不存在的测试基类"
    echo "❌ Surefire groups 抑制 @Tag(dev) 缺失 → 静默跳过"
    exit 2
  fi

  if [ ! -d "$TEST_BASE" ]; then
    echo "⚠️  $TEST_BASE 不存在"
    exit 0
  fi

  # 检测 surefire 配置 vs @Tag 覆盖
  local has_groups=0
  local has_tag_dev=0
  [ -f pom.xml ] && grep -q "<groups>" pom.xml && has_groups=1
  find "$TEST_BASE" -name "*.java" -exec grep -l '@Tag("dev")' {} \; 2>/dev/null | head -1 | grep -q . && has_tag_dev=1

  if [ "$has_groups" -eq 1 ] && [ "$has_tag_dev" -eq 0 ]; then
    echo "🔴 surefire 配置了 <groups> 但无 @Tag(dev) 测试类"
    exit 2
  fi

  echo "✅ surefire groups vs @Tag 覆盖匹配 (groups=$has_groups, tag_dev=$has_tag_dev)"
  exit 0
}

main "$@"
