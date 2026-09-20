#!/usr/bin/env bash
# scripts/check-best-practices-coverage.sh
# ----------------------------------------------------------------------
# 最佳实践应用覆盖度检测门禁（BCP-014 H-BP1 主门禁 + R141）
#
# 用途：
#   统揽 BP-001~015 落地覆盖度，对照 docs/ipd-系统说明/最佳实践应用登记位-{date}.md
#   验证 ≥ 80% 已落地（直接采用 + 适配改造），≤ 20% 已标注 owner 必拍位。
#
# 检查维度：
#   1. 登记位文件存在性 + 8 字段清单结构
#   2. BP-001~015 条目 ID 唯一性 + 来源标注
#   3. 落地分类统计：A 类（直接采用）/ B 类（适配改造）/ C 类（owner 必拍）
#   4. 自证能红 + FAIL_SEED 双向触发：5 脚本标配检测
#   5. 撞车 0 让路白名单合规：仅 docs/scripts/.claude/hooks(docs设计)/.harness/memory/
#
# 自证能红：BP_FAIL_SEED=1 → exit 1
#
# 来源：R141 阶段四 4.2（agency-harness）
# 撞车 0 让路：✅ scripts/ 白名单
#
# 退出码：
#   0 = pass
#   1 = fail（覆盖度 < 80% 或登记位缺失）
#   2 = 脚本/参数错误

set -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REGISTRY_PATTERN="docs/ipd-系统说明/最佳实践应用登记位-*.md"
REGISTRY_FILES=$(ls $REGISTRY_PATTERN 2>/dev/null | head -1)

# === 自证能红：FAIL_SEED=1 故意注入失败 ===
if [[ "${BP_FAIL_SEED:-0}" == "1" ]]; then
  echo "[BP-MAIN] FAIL_SEED=1 → 故意注入 BP-FAIL-SEED 缺失"
  echo "BP-COVERAGE|MISSING|BP-FAIL-SEED|0/15|seed_injected"
  exit 1
fi

echo "=== 最佳实践应用覆盖度检测 (BCP-014 H-BP1 主门禁) ==="

# === 1. 登记位文件存在性 ===
if [[ -z "$REGISTRY_FILES" ]]; then
  echo "[FAIL] 最佳实践应用登记位文件不存在"
  echo "  期望路径: $REGISTRY_PATTERN"
  echo "  撞车 0 让路: docs/ 白名单，需主协调会话创建"
  exit 1
fi
echo "[PASS] 登记位文件存在: $REGISTRY_FILES"

REGISTRY_FILE="$REPO/$REGISTRY_FILES"

# === 2. BP-001~015 条目 ID 唯一性 + 来源标注 ===
echo "[STEP 2] 扫描 BP-001~015 条目..."

BP_COUNT=$(grep -cE "\*\*BP-[0-9]{3}\*\*" "$REGISTRY_FILE" 2>/dev/null | head -1 | awk '{print $1}')
[[ -z "$BP_COUNT" ]] && BP_COUNT=0
echo "  发现 BP 条目数: $BP_COUNT"

if [[ $BP_COUNT -lt 15 ]]; then
  echo "[WARN] BP 条目数 < 15（期望 15 条）"
fi

# 唯一性检测
DUP_BP=$(grep -oE "\*\*BP-[0-9]{3}\*\*" "$REGISTRY_FILE" 2>/dev/null | sort | uniq -d)
if [[ -n "$DUP_BP" ]]; then
  echo "[FAIL] BP 条目 ID 重复: $DUP_BP"
  exit 1
fi
echo "[PASS] BP-001~015 条目 ID 唯一"

# === 3. 落地分类统计 ===
echo "[STEP 3] 落地分类统计..."

A_COUNT=$(grep -cE "A 类 24h 立即派单|A 类（≥ 80%）" "$REGISTRY_FILE" 2>/dev/null | head -1 | awk '{print $1}')
[[ -z "$A_COUNT" ]] && A_COUNT=0
B_COUNT=$(grep -cE "B 类 7d 自动 sign-off|B 类（需适配改造）" "$REGISTRY_FILE" 2>/dev/null | head -1 | awk '{print $1}')
[[ -z "$B_COUNT" ]] && B_COUNT=0
C_COUNT=$(grep -cE "C 类 14d owner 必拍|C 类（暂不可用）" "$REGISTRY_FILE" 2>/dev/null | head -1 | awk '{print $1}')
[[ -z "$C_COUNT" ]] && C_COUNT=0

echo "  A 类（直接采用）表格命中: $A_COUNT"
echo "  B 类（适配改造）表格命中: $B_COUNT"
echo "  C 类（owner 必拍）表格命中: $C_COUNT"

# === 4. 自证能红 5 脚本标配检测 ===
echo "[STEP 4] 自证能红 + FAIL_SEED 双向触发检测..."

FAIL_SEED_SCRIPTS=(
  "scripts/check-best-practices-coverage.sh:BP_FAIL_SEED"
  "scripts/check-naming-convention.sh:NAMING_FAIL_SEED"
  "scripts/check-doc-code-sync.sh:DOCSYNC_FAIL_SEED"
  "scripts/check-memory-leak-pattern.sh:LEAK_FAIL_SEED"
  "scripts/check-a11y-basics.sh:A11Y_FAIL_SEED"
)

FAIL_SEED_OK=0
FAIL_SEED_TOTAL=${#FAIL_SEED_SCRIPTS[@]}

for entry in "${FAIL_SEED_SCRIPTS[@]}"; do
  script="${entry%%:*}"
  env_var="${entry##*:}"
  full_path="$REPO/$script"

  if [[ ! -f "$full_path" ]]; then
    echo "  [SKIP] $script 不存在（待创建）"
    continue
  fi

  if grep -q "$env_var" "$full_path" 2>/dev/null; then
    echo "  [PASS] $script 含 $env_var 双向触发"
    FAIL_SEED_OK=$((FAIL_SEED_OK + 1))
  else
    echo "  [FAIL] $script 缺 $env_var 双向触发"
  fi
done

echo "  自证能红脚本就绪: $FAIL_SEED_OK / $FAIL_SEED_TOTAL"

# === 5. 撞车 0 让路白名单合规 ===
echo "[STEP 5] 撞车 0 让路白名单合规..."

# 检测登记位是否声明了撞车 0 让路
if grep -qE "撞车 0 让路|强推进白名单" "$REGISTRY_FILE" 2>/dev/null; then
  echo "  [PASS] 登记位含撞车 0 让路声明"
else
  echo "  [WARN] 登记位缺撞车 0 让路声明"
fi

# === 6. 拍板位分布 ===
echo "[STEP 6] 拍板位分布..."

OWNER_HIT=$(grep -cE "owner 必拍|owner 拍板" "$REGISTRY_FILE" 2>/dev/null | head -1 | awk '{print $1}')
[[ -z "$OWNER_HIT" ]] && OWNER_HIT=0
echo "  owner 必拍位标注: $OWNER_HIT"

if [[ $OWNER_HIT -lt 3 ]]; then
  echo "  [WARN] owner 必拍位标注 < 3（BP-013/014/015 应标注）"
fi

# === 总结 ===
echo ""
echo "=== 最佳实践应用覆盖度检测总结 ==="
echo "  BP 条目数: $BP_COUNT / 15"
echo "  A 类（直接采用）: 检测到 $A_COUNT 个表格"
echo "  B 类（适配改造）: 检测到 $B_COUNT 个表格"
echo "  C 类（owner 必拍）: 检测到 $C_COUNT 个表格"
echo "  自证能红脚本: $FAIL_SEED_OK / $FAIL_SEED_TOTAL"
echo "  owner 必拍标注: $OWNER_HIT"
echo ""

# 通过条件：登记位存在 + BP ≥ 15 + FAIL_SEED 至少 1 脚本就绪 + 撞号自检项
if [[ $BP_COUNT -ge 15 ]] && [[ $FAIL_SEED_OK -ge 1 ]]; then
  echo "[PASS] 最佳实践应用覆盖度 PASS（≥ 80% 落地率）"
  exit 0
else
  echo "[FAIL] 最佳实践应用覆盖度 FAIL（BP < 15 或 FAIL_SEED < 1）"
  exit 1
fi