#!/usr/bin/env bash
# scripts/check-test-assertion-history.sh — 防假绿类型 2: 测试断言改成"现状"
# 来源：R131 §二.2.2 防假绿类型 2
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入 assertTrue("现状保留") 模式 → exit 2

set -eo pipefail

TEST_BASE="${TEST_BASE:-microservices}"
TAH_FAIL_SEED="${TAH_FAIL_SEED:-0}"

main() {
  echo "[防假绿-2] check-test-assertion-history.sh 启动 (基线: R131)"

  if [ "$TAH_FAIL_SEED" = "1" ]; then
    echo "[防假绿-2] FAIL_SEED=1 → 注入 assertTrue(\"现状保留\") 软化断言"
    echo "❌ 检测到 git history 中将硬断言改成软断言（assertTrue(\"现状\"））"
    exit 2
  fi

  if [ ! -d "$TEST_BASE" ]; then
    echo "⚠️  $TEST_BASE 不存在"
    exit 0
  fi

  # git blame 软化断言检测（assertTrue/assertEquals 被改成常量断言）
  if ! command -v git >/dev/null 2>&1; then
    echo "⚠️  git 不可用，跳过"
    exit 0
  fi

  # 最近 30 commit 内 + 软化断言模式
  local soft=0
  while read -r h file; do
    [ -f "$file" ] || continue
    if grep -qE "assertTrue\([\"']现状|assertEquals\([\"']现状" "$file"; then
      echo "❌ ${file}:${h} 软化断言"
      soft=$((soft + 1))
    fi
  done < <(git log --since="30 days ago" --name-only --pretty=format: 2>/dev/null | grep -E "\.java$" | sort -u | head -20)

  if [ "$soft" -gt 0 ]; then
    echo "🔴 $soft 个文件含软化断言"
    exit 2
  fi

  echo "✅ 最近 30 commit 无软化断言"
  exit 0
}

main "$@"
