#!/usr/bin/env bash
# scripts/check-mock-legality.sh
# ----------------------------------------------------------------------
# R30+ 治理门禁：单测合法性与 Mockito 替业务假绿防御。
# 背景：5 类假红/假绿根因（实测 1851 用例），Surefire @Tag 静默跳过陷阱。
#
# 检查项（按严重度排序，阻断在前）：
#   A. @ExtendWith(MockitoExtension.class) 测试类无 @Tag("dev") → dev profile 静默跳过
#   B. 业务 Service 方法被 stub 替身（when().thenReturn(固定值)）
#   C. helper 默认值反转（getOrDefault(code, "PASS") 类陷阱）
#   D. Calendar.SEPTEMBER 等 0-based 常量传入 helper.date(y,m,d)
#   E. Caffeine cache 测试未强制同步维护（estimatedSize 断言）
#   F. 静态 doesNotContain 断言未剔注释
#
# 用法（在 ruoyi-ai 仓根目录）：
#   ./scripts/check-mock-legality.sh                          # 全量扫描 ruoyi-ipd
#   ./scripts/check-mock-legality.sh --module ruoyi-system    # 指定模块
#   ./scripts/check-mock-legality.sh --strict                 # 警告也升级为错误
#   ./scripts/check-mock-legality.sh --check <A|B|C|D|E|F>    # 只跑指定检查
#
# 退出码：
#   0 = 无阻断问题
#   1 = 检测到阻断
#   2 = 脚本/参数错误
#
# 背景与根因：见 .claude/skills/ipd-guard-mock-validity/SKILL.md
# ----------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MODULE="ruoyi-ipd"
STRICT=0
ONLY_CHECK=""

while [ $# -gt 0 ]; do
  case "$1" in
    --module)   MODULE="$2"; shift 2 ;;
    --strict)   STRICT=1; shift ;;
    --check)    ONLY_CHECK="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,21p' "$0"
      exit 0
      ;;
    *)
      echo "ERROR: 未知参数 $1" >&2
      exit 2
      ;;
  esac
done

cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

TEST_DIR="ruoyi-modules/$MODULE/src/test/java"
if [ ! -d "$TEST_DIR" ]; then
  echo "ERROR: 测试目录不存在: $TEST_DIR" >&2
  exit 2
fi

echo "▶ check-mock-legality: module=$MODULE strict=$STRICT"
echo "▶ 测试根: $TEST_DIR"
echo

problems=0
warnings=0
should_run() { [ -z "$ONLY_CHECK" ] || [ "$ONLY_CHECK" = "$1" ]; }

# ---- A. Mockito 测试无 @Tag ----
if should_run "A"; then
  echo "[A] 扫描 @ExtendWith(MockitoExtension.class) 缺 @Tag 测试..."
  MISSING_TAG=$(find "$TEST_DIR" -name "*Test.java" -type f 2>/dev/null \
    | xargs grep -l "@ExtendWith(MockitoExtension.class)" 2>/dev/null \
    | xargs grep -L "@Tag" 2>/dev/null || true)
  if [ -n "$MISSING_TAG" ]; then
    COUNT=$(echo "$MISSING_TAG" | wc -l | tr -d ' ')
    echo "  ⛔ 发现 $COUNT 个 Mockito 测试类缺 @Tag（dev profile 会被静默跳过 = 假绿）:"
    echo "$MISSING_TAG" | sed 's/^/    - /'
    problems=$((problems + COUNT))
  else
    echo "  ✅ 所有 Mockito 测试类都有 @Tag"
  fi
  echo
fi

# ---- B. 业务 Service 方法被 stub 替身 ----
if should_run "B"; then
  echo "[B] 扫描业务 Service 方法被 when().thenReturn() 替身..."
  # 模式：when(service.method(...)).thenReturn(<固定值>) —— 此类替身验不出 NOT NULL 等业务约束
  SERVICE_STUB=$(find "$TEST_DIR" -name "*Test.java" -type f 2>/dev/null \
    | xargs grep -nE "when\s*\(\s*[a-zA-Z]+Service\.[a-zA-Z]+\([^)]*\)\s*\)\.thenReturn" 2>/dev/null || true)
  if [ -n "$SERVICE_STUB" ]; then
    COUNT=$(echo "$SERVICE_STUB" | wc -l | tr -d ' ')
    echo "  ⛔ 发现 $COUNT 处 Service 方法被 stub 替身（验不出真实业务约束）:"
    echo "$SERVICE_STUB" | head -5 | sed 's/^/    /'
    [ "$COUNT" -gt 5 ] && echo "    ...（省略其余 $((COUNT - 5)) 处）"
    echo "  修法: 取消 Service stub，改用 @SpringBootTest 真活集成测试连 ipd_dev"
    problems=$((problems + COUNT))
  else
    echo "  ✅ 无 Service 方法被 stub 替身"
  fi
  echo
fi

# ---- C. helper 默认值反转 ----
if should_run "C"; then
  echo "[C] 扫描 helper 默认值反转（getOrDefault 静默反转缺判）..."
  # 模式：getOrDefault(<code>, "PASS") 或 getOrDefault(<code>, true) 之类反转语义
  HELPER_INVERT=$(find "$TEST_DIR" -name "*.java" -type f 2>/dev/null \
    | xargs grep -nE 'getOrDefault\([^,]+,\s*"(PASS|OK|SUCCESS|true)"' 2>/dev/null || true)
  if [ -n "$HELPER_INVERT" ]; then
    COUNT=$(echo "$HELPER_INVERT" | wc -l | tr -d ' ')
    echo "  ⛔ 发现 $COUNT 处 helper 默认值反转（缺判静默 = 负例用例全过假绿）:"
    echo "$HELPER_INVERT" | head -5 | sed 's/^/    /'
    [ "$COUNT" -gt 5 ] && echo "    ...（省略其余 $((COUNT - 5)) 处）"
    echo "  修法: 拆双 helper —— 缺省 PASS 版（多数用例）+ containsKey 显式缺判版（负例）"
    problems=$((problems + COUNT))
  else
    echo "  ✅ 无 helper 默认值反转"
  fi
  echo
fi

# ---- D. Calendar 0-based 月份传 helper ----
if should_run "D"; then
  echo "[D] 扫描 Calendar 0-based 月份常量传入 helper.date(y,m,d)..."
  # 模式：date(yyyy, Calendar.JANUARY/DECEMBER/SEPTEMBER, d) 等
  CAL_HELPER=$(find "$TEST_DIR" -name "*.java" -type f 2>/dev/null \
    | xargs grep -nE 'date\(\s*\d{4}\s*,\s*Calendar\.[A-Z]+\s*,' 2>/dev/null \
    | grep -v "//" || true)
  if [ -n "$CAL_HELPER" ]; then
    COUNT=$(echo "$CAL_HELPER" | wc -l | tr -d ' ')
    echo "  ⛔ 发现 $COUNT 处 Calendar 0-based 月份传 helper（双重减一陷阱）:"
    echo "$CAL_HELPER" | head -5 | sed 's/^/    /'
    [ "$COUNT" -gt 5 ] && echo "    ...（省略其余 $((COUNT - 5)) 处）"
    echo "  修法: helper 一律传 1-based 字面量（date(2026, 9, 11)），或统一 java.time.LocalDate"
    problems=$((problems + COUNT))
  else
    echo "  ✅ 无 Calendar 0-based 月份传 helper"
  fi
  echo
fi

# ---- E. Caffeine cache 测试未强制同步维护 ----
if should_run "E"; then
  echo "[E] 扫描 Caffeine cache 测试缺强制同步维护..."
  # 模式：Caffeine.newBuilder() 后立刻断言 estimatedSize / size() / 没有 .executor(
  CAFFEINE_ASYNC=$(find "$TEST_DIR" -name "*.java" -type f 2>/dev/null \
    | xargs grep -lE "Caffeine\.newBuilder|com\.github\.benmanes\.caffeine" 2>/dev/null \
    | xargs grep -L "\.executor\(Runnable::run\)" 2>/dev/null || true)
  if [ -n "$CAFFEINE_ASYNC" ]; then
    COUNT=$(echo "$CAFFEINE_ASYNC" | wc -l | tr -d ' ')
    echo "  ⚠️ 发现 $COUNT 个 Caffeine 测试未强制同步维护（ForkJoinPool 异步维护滞后）:"
    echo "$CAFFEINE_ASYNC" | sed 's/^/    - /'
    echo "  修法: builder.executor(Runnable::run) 强制同步驱逐后再断言"
    warnings=$((warnings + COUNT))
  else
    echo "  ✅ Caffeine 测试都有强制同步维护"
  fi
  echo
fi

# ---- F. 静态 doesNotContain 断言未剔注释 ----
if should_run "F"; then
  echo "[F] 扫描静态 doesNotContain 类断言未剔注释..."
  # 模式：doesNotContain / assertThat(...).doesNotContain("===") 类字符串断言，未先按行剔注释
  STATIC_ASSERT=$(find "$TEST_DIR" -name "*.java" -type f 2>/dev/null \
    | xargs grep -nE 'doesNotContain\(\s*"[^"]{1,5}"\s*\)' 2>/dev/null \
    | grep -vE '"\s*\*|//' || true)
  if [ -n "$STATIC_ASSERT" ]; then
    COUNT=$(echo "$STATIC_ASSERT" | wc -l | tr -d ' ')
    echo "  ⚠️ 发现 $COUNT 处 doesNotContain 短字符串断言（易误伤注释分隔线）:"
    echo "$STATIC_ASSERT" | head -5 | sed 's/^/    /'
    [ "$COUNT" -gt 5 ] && echo "    ...（省略其余 $((COUNT - 5)) 处）"
    echo "  修法: 扫描前按行剔除注释（//、/*、* 开头行 + 行内 // 后缀）"
    warnings=$((warnings + COUNT))
  else
    echo "  ✅ doesNotContain 断言已剔注释"
  fi
  echo
fi

echo "==== 总结 ===="
echo "  阻断性问题（A/B/C/D）: $problems"
echo "  警告（E/F）:            $warnings"

if [ "$problems" -gt 0 ]; then
  echo
  echo "❌ 检测到阻断性问题。处置: 修复后再跑本脚本。"
  echo "详细根因: .claude/skills/ipd-guard-mock-validity/SKILL.md"
  exit 1
fi

if [ "$warnings" -gt 0 ] && [ "$STRICT" = "1" ]; then
  echo
  echo "⚠️ --strict 模式下警告升级为错误"
  exit 1
fi

echo
echo "✅ 全部检查通过"
exit 0