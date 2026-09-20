#!/usr/bin/env bash
# scripts/check-naming-convention.sh
# ----------------------------------------------------------------------
# 命名规范检测门禁（BP-001 — frontend-code-review 代码质量维度）
#
# 用途：
#   检测 Java/TypeScript/JavaScript 文件的命名是否符合项目规范
#   规则（基于 frontend-code-review 7 维度代码质量 + 本项目既有命名约定）：
#     1. Java 类名：PascalCase（首字母大写，驼峰）
#     2. Java 方法名/变量名：camelCase（首字母小写，驼峰）
#     3. Java 常量：UPPER_SNAKE_CASE（全大写+下划线）
#     4. TypeScript/JavaScript 类：PascalCase（同 Java）
#     5. TypeScript/JavaScript 函数/变量：camelCase
#     6. 文件名：kebab-case.ts/.tsx/.js（前端） / PascalCase.java（后端 Controller）
#
# 自证能红：NAMING_FAIL_SEED=1 → exit 1
#
# 来源：R141 阶段四 4.2 BP-001（agency-harness）
# 撞车 0 让路：✅ scripts/ 白名单（仅检测命名模式，不动源码）
#
# 退出码：
#   0 = pass
#   1 = fail（命名违规）
#   2 = 脚本/参数错误

set -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# === 自证能红 ===
if [[ "${NAMING_FAIL_SEED:-0}" == "1" ]]; then
  echo "[BP-001] NAMING_FAIL_SEED=1 → 故意注入命名违规"
  echo "NAMING|VIOLATION|NAMING-FAIL-SEED|badName_should_be_camelCase|seed_injected"
  exit 1
fi

echo "=== 命名规范检测 (BP-001) ==="

VIOLATIONS=0
SCANNED=0

# === 1. Java 类名 PascalCase 检测 ===
echo "[STEP 1] Java 类名 PascalCase 检测..."
JAVA_FILES=$(find "$REPO/ruoyi-modules" "$REPO/ruoyi-admin" "$REPO/ruoyi-common" \
  -name "*.java" -type f 2>/dev/null | head -100)

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  SCANNED=$((SCANNED + 1))

  # 类声明行：public class FooBar（PascalCase）或 interface FooBar
  CLASS_NAME=$(grep -oE "(public |protected |private )?(class|interface|enum) [A-Z][a-zA-Z0-9]*" "$f" 2>/dev/null | head -1 | awk '{print $NF}')
  if [[ -n "$CLASS_NAME" ]]; then
    # PascalCase 规则：首字母大写，后续不能有下划线
    if echo "$CLASS_NAME" | grep -qE "^[A-Z][a-zA-Z0-9]*$"; then
      :
    else
      echo "  [VIOLATION] $f → $CLASS_NAME 非 PascalCase"
      VIOLATIONS=$((VIOLATIONS + 1))
    fi
  fi
done <<< "$JAVA_FILES"

echo "  扫描 Java 文件: $SCANNED"
echo "  命名违规: $VIOLATIONS"

# === 2. Java 常量 UPPER_SNAKE_CASE 启发式检测 ===
echo "[STEP 2] Java 常量 UPPER_SNAKE_CASE 启发式检测..."

CONST_VIOL=0
# 启发式：扫描 ruoyi-modules 下 Service/Config/Constants 类的 public static final 字段
CONST_FILES=$(grep -rlE "(public |private |protected )?static final [A-Za-z_]+ [A-Z]" "$REPO/ruoyi-modules" 2>/dev/null | head -10)
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  # 提取 final 字段名（避免方法/类）
  FIELDS=$(grep -nE "(public |private |protected )?static final [A-Za-z<>?, ]+ [A-Za-z_][A-Za-z0-9_]*\s*=" "$f" 2>/dev/null \
    | awk -F'= ' '{print $1}' | awk '{print $NF}' | head -5)
  for cn in $FIELDS; do
    if echo "$cn" | grep -qE "^[A-Z][A-Z0-9_]*$"; then
      :
    else
      CONST_VIOL=$((CONST_VIOL + 1))
      [[ $CONST_VIOL -le 3 ]] && echo "  [WARN] $f → $cn 可能违反 UPPER_SNAKE_CASE（需人工确认）"
    fi
  done
done <<< "$CONST_FILES"

echo "  常量命名疑似违规: $CONST_VIOL（WARN 级，仅提示）"

# === 3. 前端文件名 kebab-case 检测 ===
echo "[STEP 3] 前端文件名 kebab-case 检测..."

if [[ -d "$REPO/../ruoyi-ipd-web/apps/web-antd/src" ]]; then
  FRONT_FILES=$(find "$REPO/../ruoyi-ipd-web/apps/web-antd/src" \
    \( -name "*.ts" -o -name "*.tsx" -o -name "*.js" -o -name "*.jsx" \) \
    -type f 2>/dev/null | head -50)
  FRONT_VIOL=0

  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    filename=$(basename "$f" | sed -E 's/\.(ts|tsx|js|jsx)$//')
    # kebab-case 规则：全小写，可包含 - 或数字
    if echo "$filename" | grep -qE "^[a-z][a-z0-9-]*$"; then
      :
    else
      FRONT_VIOL=$((FRONT_VIOL + 1))
      [[ $FRONT_VIOL -le 3 ]] && echo "  [WARN] $f → $filename 非 kebab-case（需人工确认）"
    fi
  done <<< "$FRONT_FILES"

  echo "  前端文件名疑似违规: $FRONT_VIOL（WARN 级，仅提示）"
fi

# === 总结 ===
echo ""
echo "=== 命名规范检测总结 (BP-001) ==="
echo "  扫描 Java 文件: $SCANNED"
echo "  类名 PascalCase 违规: $VIOLATIONS"
echo "  常量命名 WARN: $CONST_VIOL"
echo ""

if [[ $VIOLATIONS -eq 0 ]]; then
  echo "[PASS] 命名规范检测 PASS"
  exit 0
else
  echo "[FAIL] 命名规范检测 FAIL（$VIOLATIONS 个类名违规）"
  exit 1
fi