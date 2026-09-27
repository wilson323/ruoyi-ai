#!/usr/bin/env bash
# =============================================================================
# check-permission-mirror-fe-be.sh
# 权限码前后端镜像对账门禁（R234，2026-09-27 owner 拍板「权限码全套开工」）
#
# 用途: 后端 org.ruoyi.ipd.security.IpdPermissionCode 与前端
#       ipd-permission-codes.ts 的 ipd:* 权限码字面值集合必须完全一致。
#       任一侧独有 → 按钮闸/注解闸永不匹配（前端独有=v-access 永不满足疑功能不可达；
#       后端独有=码册无消费端疑死码）。双向 diff 非空即红。
#
# 提取口径: 只取【代码声明行】的字面值，不做全文 grep——
#   后端: String NAME = "ipd:..."   （排除 javadoc/注释里的示例字面值与 2 段式如 ipd:admin）
#   前端: NAME: 'ipd:...',          （排除注释里重复提及的字面值，如 stage-action:add 注释提及）
#   两侧均取 distinct 集合比对（前端 STAGE_ACTION_DELIVERABLE/INSTANTIATE 共享
#   'ipd:stage-action:add' 为既知共享，集合语义天然去重）。
#
# 用法:
#   bash scripts/check-permission-mirror-fe-be.sh                          # 默认模式（跨仓）
#   PERM_FAIL_SEED=1 bash scripts/check-permission-mirror-fe-be.sh         # FAIL_SEED 故意红（验脚本能拦）
#   PERM_FRONTEND_DIR=/path/to/web-antd bash scripts/check-permission-mirror-fe-be.sh  # 覆盖前端仓路径
#
# 退出码:
#   0 = PASS（两侧字面值集合完全一致）
#   1 = FAIL（双向 diff 非空，打印两侧差集）
#   2 = FAIL_SEED 注入（故意红，验脚本自证能红）
#   3 = USAGE（输入文件缺失）
#
# 备注: 仅登记，不接入 CI（owner 2026-09-27 拍板：.sh 落地即可，CI 接入另行安排）。
# =============================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------- 1. 环境变量（可被环境覆盖）----------
PERM_FRONTEND_DIR="${PERM_FRONTEND_DIR_OVERRIDE:-${PERM_FRONTEND_DIR:-/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd}}"
PERM_FAIL_SEED="${PERM_FAIL_SEED:-0}"

BACKEND_FILE="$REPO_ROOT/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/security/IpdPermissionCode.java"
FRONTEND_FILE="$PERM_FRONTEND_DIR/src/views/ipd/_shared/ipd-permission-codes.ts"

# ---------- 2. 输入文件检查（exit 3）----------
MISSING=0
for f in "$BACKEND_FILE" "$FRONTEND_FILE"; do
  if [ ! -f "$f" ]; then
    echo "❌ 输入文件缺失: $f"
    MISSING=1
  fi
done
if [ "$MISSING" -eq 1 ]; then
  echo "结果: ❌ USAGE（输入文件缺失，exit=3）"
  exit 3
fi

# ---------- 3. FAIL_SEED（exit 2，故意红）----------
if [ "$PERM_FAIL_SEED" = "1" ]; then
  echo "❌ FAIL_SEED 注入（验脚本能拦）"
  echo "  [MIRROR-FAIL_SEED] 故意制造差集: ipd:__fail_seed__:injected（前端独有）/ ipd:__fail_seed__:missing（后端独有）"
  exit 2
fi

# ---------- 4. 字面值提取（仅声明行，distinct）----------
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

# 后端：String NAME = "ipd:...";  （跳过注释行，只认声明）
grep -E '^[[:space:]]*String [A-Za-z_0-9]+ = "ipd:[^"]+";' "$BACKEND_FILE" \
  | grep -oE 'ipd:[^"]+' | sort -u > "$TMP_DIR/be.txt"

# 前端：NAME: 'ipd:...',  （键名为大写常量风格，跳过注释里的提及）
grep -E "^[[:space:]]*[A-Z_0-9]+:[[:space:]]*'ipd:[^']+'" "$FRONTEND_FILE" \
  | grep -oE "ipd:[^']+" | sort -u > "$TMP_DIR/fe.txt"

BE_COUNT=$(wc -l < "$TMP_DIR/be.txt" | tr -d ' ')
FE_COUNT=$(wc -l < "$TMP_DIR/fe.txt" | tr -d ' ')

# ---------- 5. 双向 diff ----------
comm -23 "$TMP_DIR/fe.txt" "$TMP_DIR/be.txt" > "$TMP_DIR/fe_only.txt"   # 前端独有
comm -13 "$TMP_DIR/fe.txt" "$TMP_DIR/be.txt" > "$TMP_DIR/be_only.txt"   # 后端独有
FE_ONLY=$(wc -l < "$TMP_DIR/fe_only.txt" | tr -d ' ')
BE_ONLY=$(wc -l < "$TMP_DIR/be_only.txt" | tr -d ' ')

# ---------- 6. 报告----------
echo "═══════════════════════════════════════════════════════════════"
echo " 权限码前后端镜像对账报告（R234）"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "后端码册: $BACKEND_FILE"
echo "前端码册: $FRONTEND_FILE"
echo "后端 distinct 字面值: $BE_COUNT"
echo "前端 distinct 字面值: $FE_COUNT"
echo ""

if [ "$FE_ONLY" -gt 0 ]; then
  echo "  ❌ 前端独有（后端不下发 → v-access 按钮闸永不满足）: $FE_ONLY 个"
  sed 's/^/     - /' "$TMP_DIR/fe_only.txt"
  echo ""
fi

if [ "$BE_ONLY" -gt 0 ]; then
  echo "  ❌ 后端独有（前端无消费端 → 疑死码/前端漏镜像）: $BE_ONLY 个"
  sed 's/^/     - /' "$TMP_DIR/be_only.txt"
  echo ""
fi

if [ "$FE_ONLY" -eq 0 ] && [ "$BE_ONLY" -eq 0 ]; then
  echo "  ✅ 双向差集为空（${BE_COUNT} == ${FE_COUNT}）"
fi

echo ""
echo "═══════════════════════════════════════════════════════════════"
if [ "$FE_ONLY" -eq 0 ] && [ "$BE_ONLY" -eq 0 ]; then
  echo " 结果: ✅ PASS"
  echo "═══════════════════════════════════════════════════════════════"
  exit 0
else
  echo " 结果: ❌ FAIL（双向 diff 非空，exit=1）"
  echo "═══════════════════════════════════════════════════════════════"
  exit 1
fi
