#!/usr/bin/env bash
# .claude/hooks/pre-commit-coverage.sh
# R123 常态化:R119 病根 #1 测试覆盖率按域检查 → 接 pre-commit
#
# 这是 hook 源码(.claude/hooks/ 在 OPS-09 白名单,可主仓直写)
# 真正的 install 走 scripts/install-coverage-pre-commit.sh
#
# 行为:
#   - 阻断型 pre-commit(覆盖率不达标 → exit 1)
#   - 检查所有 staged 的 .java 文件,跑 scripts/check-test-coverage-by-domain.sh
#   - 如果 staged 里没有 Java 文件 → exit 0(不阻 governance commit)
#
# OPS-09 撞车 0:
#   - 不覆盖 R30+ 治理 hook(避免撞车)
#   - 安装方式:pre-commit.d/ 子目录方式,不直接覆盖 .git/hooks/pre-commit
#   - 链路:.githooks/pre-commit → .git/hooks/pre-commit → 调 pre-commit.d/*.sh → 调本 hook

set -e

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
SCRIPT="$ROOT/scripts/check-test-coverage-by-domain.sh"

# 0. 没 staged 文件 → 不阻
STAGED=$(git diff --cached --name-only 2>/dev/null || true)
if [ -z "$STAGED" ]; then
  exit 0
fi

# 1. 没 Java 文件 → 不阻(纯 governance commit 不该被覆盖率门禁干扰)
HAS_JAVA=0
while IFS= read -r f; do
  case "$f" in
    *.java) HAS_JAVA=1 ;;
  esac
done <<< "$STAGED"

if [ "$HAS_JAVA" = "0" ]; then
  echo "[pre-commit-coverage] no Java files staged → skip"
  exit 0
fi

# 2. 跑覆盖率门禁
if [ ! -f "$SCRIPT" ]; then
  echo "❌ [pre-commit-coverage] 门禁脚本不存在: $SCRIPT"
  echo "  请确认 R119 5 脚本已 commit (commit a34a0002)"
  exit 1
fi

echo "[pre-commit-coverage] running $SCRIPT"
if ! bash "$SCRIPT"; then
  echo
  echo "════════════════════════════════════════════════════════════════════"
  echo "  ❌ 测试覆盖率不达标,提交被阻断 (R119 病根 #1 + R123 常态化)"
  echo "════════════════════════════════════════════════════════════════════"
  echo "  解法:"
  echo "    1. 给缺失覆盖率的 Java 类补单元测试"
  echo "    2. owner 紧急绕过:SKIP_COVERAGE_GATE=1 git commit ..."
  echo "    3. 仅 governance commit 不该撞门禁(本 hook 已加 HAS_JAVA 短路)"
  echo "════════════════════════════════════════════════════════════════════"
  exit 1
fi

echo "[pre-commit-coverage] ✓ 覆盖率达标"
exit 0
