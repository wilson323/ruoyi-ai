#!/usr/bin/env bash
# scripts/check-doc-code-sync.sh
# ----------------------------------------------------------------------
# 注释与代码一致性检测门禁（BP-002 — frontend-code-review 代码质量维度）
#
# 用途：
#   检测 Java/TypeScript 文件的注释是否与代码一致
#   启发式规则（基于 frontend-code-review 7 维度代码质量）：
#     1. TODO/FIXME 注释扫描：标记代码中未完成的注释
#     2. @deprecated 注释：标记已弃用但仍在使用的 API
#     3. JavaDoc 缺失：public 方法应有 JavaDoc
#     4. 注释与签名不一致：方法签名变更后 JavaDoc 参数列表未同步
#
# 自证能红：DOCSYNC_FAIL_SEED=1 → exit 1
#
# 来源：R141 阶段四 4.2 BP-002（agency-harness）
# 撞车 0 让路：✅ scripts/ 白名单（仅检测注释模式，不动源码）
#
# 退出码：
#   0 = pass
#   1 = fail（注释严重不一致）
#   2 = 脚本/参数错误

set -eo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/audit-gate-input.sh"

REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# === 自证能红 ===
if [[ "${DOCSYNC_FAIL_SEED:-0}" == "1" ]]; then
  echo "[BP-002] DOCSYNC_FAIL_SEED=1 → 故意注入注释不一致"
  echo "DOCSYNC|VIOLATION|DOCSYNC-FAIL-SEED|public method missing @param doc|seed_injected"
  exit 1
fi

echo "=== 注释与代码一致性检测 (BP-002) ==="

VIOLATIONS=0
WARNINGS=0

gate_require_tree "$REPO/ruoyi-modules/ruoyi-ipd/src" -name "*.java"

# === 1. TODO/FIXME 注释扫描 ===
echo "[STEP 1] TODO/FIXME 注释扫描..."

TODO_FILES=$(gate_grep --exclude-dir=target --include="*.java" --include="*.vue" --include="*.ts" --include="*.tsx" --include="*.js" --include="*.jsx" -rlE "TODO|FIXME|XXX|HACK" "$REPO/ruoyi-modules/ruoyi-ipd/src")
TODO_COUNT=0

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  CNT=$(gate_grep -cE "TODO|FIXME|XXX|HACK" "$f")
  TODO_COUNT=$((TODO_COUNT + CNT))
  if [[ $CNT -gt 0 ]]; then
    [[ $CNT -le 2 ]] && echo "  [INFO] $f 含 $CNT 个 TODO/FIXME 标记"
  fi
done <<< "$TODO_FILES"

echo "  TODO/FIXME 标记总数: ${TODO_COUNT}（INFO 级，仅记录）"

# === 2. @deprecated 扫描 ===
echo "[STEP 2] @deprecated 注释扫描..."

DEP_FILES=$(gate_grep --exclude-dir=target --include="*.java" --include="*.vue" --include="*.ts" --include="*.tsx" --include="*.js" --include="*.jsx" -rlE "@deprecated|@Deprecated" "$REPO/ruoyi-modules/ruoyi-ipd/src")
DEP_COUNT=0

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  CNT=$(gate_grep -cE "@deprecated|@Deprecated" "$f")
  DEP_COUNT=$((DEP_COUNT + CNT))
done <<< "$DEP_FILES"

echo "  @deprecated 标记数: ${DEP_COUNT}（INFO 级）"

# === 3. public 方法 JavaDoc 覆盖率启发式 ===
echo "[STEP 3] public 方法 JavaDoc 启发式扫描..."

JAVA_FILES=$(find "$REPO/ruoyi-modules/ruoyi-ipd/src" -name "*Service.java" -type f)
JAVADOC_MISS=0

while IFS= read -r f; do
  [[ -z "$f" ]] && continue

  # 提取 public 方法签名
  PUBLIC_METHODS=$(gate_grep -nE "^\s*public\s+[a-zA-Z<>?, ]+\s+[a-zA-Z]+\s*\(" "$f")
  TOTAL_METHODS=$(echo "$PUBLIC_METHODS" | gate_grep -c ".")

  # 检查方法上方 5 行内是否有 /** ... */
  # 注意：禁止用 bash 保留变量 LINENO 存方法行号——赋值后下一条命令会把它重置为
  # 「脚本自身行号」，导致 sed 窗口永远扫错文件位置，产生大面积假红（2026-09-24 实证）。
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    METHOD_LINE=$(echo "$line" | cut -d: -f1)
    [[ -z "$METHOD_LINE" ]] && continue
    PREV_START=$((METHOD_LINE - 5))
    [[ $PREV_START -lt 1 ]] && PREV_START=1

    PREVIOUS_LINES=$(sed -n "${PREV_START},$((METHOD_LINE-1))p" "$f")
    if printf '%s\n' "$PREVIOUS_LINES" | grep -qE "/\*\*|\*/"; then
      :
    else
      JAVADOC_MISS=$((JAVADOC_MISS + 1))
    fi
  done <<< "$PUBLIC_METHODS"

done <<< "$JAVA_FILES"

echo "  Service 类 public 方法 JavaDoc 疑似缺失: ${JAVADOC_MISS}（WARN 级）"

# === 4. 注释与签名不一致（参数列表）启发式 ===
echo "[STEP 4] 注释与签名参数列表不一致检测..."

PARAM_MISMATCH=0
SAMPLE_FILES=$(find "$REPO/ruoyi-modules/ruoyi-ipd/src" -name "*Controller.java" -type f)

while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  # 检查 @param 数量 vs 方法参数数量
  PARAM_DOCS=$(gate_grep -cE "@param [a-zA-Z]" "$f" | awk '{print $1}')
  PARAM_DOCS=${PARAM_DOCS:-0}
  PARAM_DECLS=$(gate_grep -cE "^\s*public\s+[a-zA-Z<>?, ]+\s+[a-zA-Z]+\s*\([^)]*," "$f" | awk '{print $1}')
  PARAM_DECLS=${PARAM_DECLS:-0}
  if [[ $PARAM_DECLS -gt 0 ]] && [[ $PARAM_DOCS -lt $PARAM_DECLS ]]; then
    PARAM_MISMATCH=$((PARAM_MISMATCH + 1))
  fi
done <<< "$SAMPLE_FILES"

echo "  参数注释疑似不完整 Controller: ${PARAM_MISMATCH}（INFO 级）"

# === 总结 ===
VIOLATIONS=$JAVADOC_MISS

echo ""
echo "=== 注释与代码一致性检测总结 (BP-002) ==="
echo "  TODO/FIXME: ${TODO_COUNT}（INFO）"
echo "  @deprecated: ${DEP_COUNT}（INFO）"
echo "  JavaDoc 疑似缺失: ${JAVADOC_MISS}（WARN→VIOLATION）"
echo "  参数注释疑似不完整: ${PARAM_MISMATCH}（INFO）"
echo ""

if [[ $VIOLATIONS -eq 0 ]]; then
  echo "[PASS] 注释与代码一致性检测 PASS"
  exit 0
elif [[ $VIOLATIONS -le 5 ]]; then
  echo "[WARN] 注释与代码一致性 WARN（$VIOLATIONS 个疑似缺失，建议修复）"
  exit 0
else
  echo "[FAIL] 注释与代码一致性 FAIL（$VIOLATIONS 个疑似缺失，超过阈值 5）"
  exit 1
fi
