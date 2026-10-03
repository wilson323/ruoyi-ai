#!/usr/bin/env bash
# scripts/check-doc-code-sync.sh
# ----------------------------------------------------------------------
# 注释与代码一致性检测门禁（BP-002 — frontend-code-review 代码质量维度）
#
# 用途：检测 Java 文件的注释是否与代码一致。
#
# === 2026-10-03 判据重构（owner 决策）===
# 背景：原实现的退出码**只由「有没有写 javadoc」驱动**（`VIOLATIONS=$JAVADOC_MISS`），
# 而脚本头部第 4 条规则自称查的是「签名变更后 JavaDoc 参数列表未同步」——
# **名实不符**：那条规则实现了却永远只打 INFO，从不影响退出码。
# owner 决策：本项目**不强制**「每个公开方法都写注释」，因此：
#   · 规则 3（javadoc 缺失）→ 降为 INFO，不再计入违规
#   · 规则 4（@param 与签名对不上）→ 升为**唯一判据**
#
# 规则 4 为何改用 Python 字符级扫描（scripts/lib/java-param-doc-check.py）：
#   用 grep/正则逐方法比对连续失败 4 次，伪阳性抽查 3/3 全是假的——
#   多行签名被当成零参数、参数内注解（`@Pattern(regexp="a|b,c")`）被当成参数名。
#   正则做不了这件事，故改为字符级扫描（跟踪字符串字面量 + 括号/泛型深度）。
#   该判定器自带 `--self-test`：就地注入一个改错名的 @param，验证它确实会报违规。
#
# 判定口径（刻意收窄，避免变成变相的「强制写注释」）：
#   只检查**已经写了 @param** 的方法。javadoc 里一个 @param 都没有 → 不计违规
#   （那是「没写参数文档」，不是「文档与代码不一致」）。违规只有两种：
#     ① 文档里的 @param 名在签名里不存在（改了参数名没同步文档）
#     ② 签名里的参数在文档里找不到（且该 javadoc 至少写了一个 @param）
#
# 自证能红：DOCSYNC_FAIL_SEED=1 → exit 1
#
# 来源：R141 阶段四 4.2 BP-002（agency-harness）；2026-10-03 判据重构
# 撞车 0 让路：✅ scripts/ 白名单（仅检测注释模式，不动业务源码）
#
# 退出码：
#   0 = pass
#   1 = fail（@param 与签名不一致数超过阈值）
#   2 = 脚本/输入错误（源码树不存在等，绝不降级成 0 条通过）

set -eo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/audit-gate-input.sh"

REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SCAN_ROOT="$REPO/ruoyi-modules/ruoyi-ipd/src/main/java"
CHECKER="$REPO/scripts/lib/java-param-doc-check.py"
# 允许的不一致条数。默认 0——本仓当前实测为 0，任何新增都会被拦下。
MAX_MISMATCH="${DOCSYNC_MAX_MISMATCH:-0}"

# === 自证能红 ===
if [[ "${DOCSYNC_FAIL_SEED:-0}" == "1" ]]; then
  echo "[BP-002] DOCSYNC_FAIL_SEED=1 → 故意注入注释不一致"
  echo "DOCSYNC|VIOLATION|DOCSYNC-FAIL-SEED|@param 与签名不一致|seed_injected"
  exit 1
fi

echo "=== 注释与代码一致性检测 (BP-002) ==="

VIOLATIONS=0

gate_require_tree "$SCAN_ROOT" -name "*.java"

if [[ ! -f "$CHECKER" ]]; then
  # 判定器缺失 = 门禁失效，必须硬失败，不能当成「0 条通过」
  echo "[ERROR] 判定器不存在: $CHECKER" >&2
  exit 2
fi

# === 1. TODO/FIXME 注释扫描（INFO）===
echo "[STEP 1] TODO/FIXME 注释扫描..."

TODO_FILES=$(gate_grep --exclude-dir=target --include="*.java" -rlE "TODO|FIXME|XXX|HACK" "$SCAN_ROOT" || true)
TODO_COUNT=0
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  CNT=$(gate_grep -cE "TODO|FIXME|XXX|HACK" "$f" || true)
  TODO_COUNT=$((TODO_COUNT + CNT))
done <<< "$TODO_FILES"
echo "  TODO/FIXME 标记总数: ${TODO_COUNT}（INFO 级，仅记录）"

# === 2. @deprecated 扫描（INFO）===
echo "[STEP 2] @deprecated 注释扫描..."
DEP_COUNT=$(gate_grep --exclude-dir=target --include="*.java" -rE "@deprecated|@Deprecated" "$SCAN_ROOT" | wc -l | tr -d ' ')
echo "  @deprecated 标记数: ${DEP_COUNT}（INFO 级）"

# === 3. public 方法 JavaDoc 覆盖率（INFO —— owner 2026-10-03 决策：不强制）===
echo "[STEP 3] public 方法 JavaDoc 覆盖率（INFO，不作为判据）..."
JAVA_FILES=$(find "$SCAN_ROOT" -name "*Service.java" -type f)
JAVADOC_MISS=0
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  PUBLIC_METHODS=$(gate_grep -nE "^\s*public\s+[a-zA-Z<>?, ]+\s+[a-zA-Z]+\s*\(" "$f" || true)
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    METHOD_LINE=$(echo "$line" | cut -d: -f1)
    [[ -z "$METHOD_LINE" ]] && continue
    PREV_START=$((METHOD_LINE - 5))
    [[ $PREV_START -lt 1 ]] && PREV_START=1
    if ! sed -n "${PREV_START},$((METHOD_LINE-1))p" "$f" | grep -qE "/\*\*|\*/"; then
      JAVADOC_MISS=$((JAVADOC_MISS + 1))
    fi
  done <<< "$PUBLIC_METHODS"
done <<< "$JAVA_FILES"
echo "  Service 类 public 方法无 javadoc: ${JAVADOC_MISS}（INFO —— 本项目不强制写注释）"

# === 4. @param 与签名不一致（唯一判据）===
echo "[STEP 4] @param 与签名一致性（判据）..."

set +e
CHECK_OUT=$(python3 "$CHECKER" "$SCAN_ROOT")
CHECK_RC=$?
set -e

if [[ $CHECK_RC -eq 2 ]]; then
  echo "[ERROR] 判定器输入错误（源码树缺失等），拒绝降级为通过：" >&2
  echo "$CHECK_OUT" >&2
  exit 2
fi

if [[ $CHECK_RC -ne 0 ]]; then
  echo "$CHECK_OUT" | while IFS= read -r l; do echo "    $l"; done
  VIOLATIONS=$(echo "$CHECK_OUT" | grep -c . || true)
else
  VIOLATIONS=0
fi

STATS=$(python3 "$CHECKER" "$SCAN_ROOT" --json 2>/dev/null \
        | python3 -c "import json,sys;d=json.load(sys.stdin);s=d['stats'];print(f\"扫描 {s['scanned']} 个公开方法 / 其中 {s['with_param_doc']} 个写了 @param / @Override 豁免 {s['overrides_exempt']} 个\")" 2>/dev/null || echo "（统计不可用）")

echo "  $STATS"

# === 总结 ===
echo ""
echo "=== 注释与代码一致性检测总结 (BP-002) ==="
echo "  TODO/FIXME: ${TODO_COUNT}（INFO）"
echo "  @deprecated: ${DEP_COUNT}（INFO）"
echo "  javadoc 缺失: ${JAVADOC_MISS}（INFO —— 不强制写注释，见头部说明）"
echo "  @param 与签名不一致: ${VIOLATIONS}（判据，阈值 ${MAX_MISMATCH}）"
echo ""

if [[ $VIOLATIONS -le $MAX_MISMATCH ]]; then
  echo "[PASS] 注释与代码一致性检测 PASS"
  exit 0
else
  echo "[FAIL] 注释与代码一致性 FAIL（${VIOLATIONS} 处 @param 与签名对不上，超过阈值 ${MAX_MISMATCH}）"
  echo "       修法：把缺失/多余的 @param 补全或删掉，使 javadoc 参数名与签名逐一对应。"
  exit 1
fi
