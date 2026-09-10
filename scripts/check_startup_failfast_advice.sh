#!/usr/bin/env bash
# scripts/check_startup_failfast_advice.sh
# ----------------------------------------------------------------------
# R28.5 治理门禁：扫描全局 @ExceptionHandler(Exception.class) 兜底 advice
# 是否对"配置冲突类异常"做了专门处理或豁免。
#
# 规约见 docs/ipd-系统说明/架构规约-禁止implements-AsyncConfigurer-20260909.md §六
#
# 命中即 exit 1——若 advice 把 IllegalStateException 等配置冲突异常
# 静默兜底成 code:90001，会让"启动期该炸的 bug"延迟到运行期首次调用才暴露。
# 期望行为：advice 对 IllegalStateException 等返回 503 + 特殊错误码（如 90002 配置异常），
# 让运维从日志 traceId 立即定位。
# ----------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

echo "▶ R28.5 治理门禁：扫描全局 Exception 兜底 advice 对配置异常的处理"

# 配置冲突类异常——这些该 fail-fast，不该被静默兜底
CONFIG_EXCEPTIONS=(
  "IllegalStateException"
  "BeanDefinitionOverrideException"
  "NoUniqueBeanDefinitionException"
  "BeanCreationException"
  "BeanInstantiationException"
  "FatalBeanException"
  "ApplicationContextException"
)

# 配置文件作用域（默认 ipd；CI 全仓用 ADVICE_SCOPE=all）
# 本会话设计：只覆盖 IPD controller 包 advice；基线 ruoyi-common-web 的 GlobalExceptionHandler 改动范围太大，留待单独议题。
ADVICE_SCOPE="${ADVICE_SCOPE:-ipd}"

if [ "$ADVICE_SCOPE" = "all" ]; then
  ADVICE_ROOTS="."
else
  ADVICE_ROOTS="./ruoyi-modules/ruoyi-ipd"
fi

# 找所有带 @ExceptionHandler(Exception.class) 的类（即全局兜底 advice）
ADVICE_FILES=$(find $ADVICE_ROOTS -type f -name "*.java" \
  -not -path "*/.codex/*" \
  -not -path "*/.claude/worktrees/*" \
  -not -path "*/target/*" \
  -not -path "*/.git/*" \
  -print0 2>/dev/null | xargs -0 grep -l "@ExceptionHandler(Exception\.class)" 2>/dev/null)

if [ -z "$ADVICE_FILES" ]; then
  echo "✅ scope=$ADVICE_SCOPE 未发现 @ExceptionHandler(Exception.class) 兜底 advice。门禁通过。"
  exit 0
fi

EXIT_CODE=0
for advice in $ADVICE_FILES; do
  # 检查这个 advice 里类名是否被引用（支持 @ExceptionHandler(X.class) 与 @ExceptionHandler({...X.class...}) 两种语法）
  missing_exceptions=""
  for exc in "${CONFIG_EXCEPTIONS[@]}"; do
    # 在 advice 文件里查 X.class（允许 .class 前后有空白/换行/逗号/花括号）
    if ! grep -E "(@ExceptionHandler\b|\b)${exc}\.class" "$advice" 2>/dev/null | grep -v '^\s*//' | grep -q .; then
      missing_exceptions="$missing_exceptions $exc"
    fi
  done
  if [ -n "$missing_exceptions" ]; then
    echo "❌ $advice —— 缺少对以下配置异常类的专门 handler（建议加白名单返回 503/90002）："
    echo "$missing_exceptions"
    echo ""
    EXIT_CODE=1
  fi
done

if [ $EXIT_CODE -eq 0 ]; then
  echo "✅ 所有兜底 advice 都对配置冲突异常有专门处理。门禁通过。"
fi
exit $EXIT_CODE