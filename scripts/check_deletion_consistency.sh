#!/usr/bin/env bash
# check_deletion_consistency.sh
# R25 P1-4 删除前对账：影响扩散分析
#
# 设计要点（避免 R14 AllowanceService 误删教训 + IpdPermissionCode 4 孤儿教训）：
#   删除一个业务类前必须验证以下 7 类引用都已处理：
#     1. 后端 Java 引用（import / new / extends / implements / @Autowired / Lombok 注入）
#     2. 前端 TS 引用（import / new / type）
#     3. MyBatis Mapper XML（resultMap / parameterType / sql 片段引用）
#     4. SQL 脚本（CREATE TABLE / INSERT / UPDATE 引用）
#     5. i18n key（如果类名是 i18n key 前缀）
#     6. 看板/SSOT 镜像（开发计划-看板镜像.md 卡片标题或描述）
#     7. 测试（单测 / 集成测试）
#
# 用法:
#   ./scripts/check_deletion_consistency.sh FooService
#   ./scripts/check_deletion_consistency.sh FooService FooController
#   ./scripts/check_deletion_consistency.sh --auto   # 扫所有 scan_dead_code.sh 输出的 HIGH 项

set -u

BACKEND_ROOT="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}"
FRONTEND_ROOT="${FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd}"
DOCS_ROOT="${DOCS_ROOT:-/Users/mac/Documents/ZK-IPD}"

MODE="manual"
TARGETS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --auto) MODE="auto"; shift ;;
    --module) MODULE="$2"; shift 2 ;;
    -h|--help) sed -n '2,32p' "$0" | sed 's/^# //;s/^#//'; exit 0 ;;
    *) TARGETS+=("$1"); shift ;;
  esac
done
MODULE="${MODULE:-ruoyi-ipd}"

if [ "$MODE" = "manual" ] && [ "${#TARGETS[@]}" -eq 0 ]; then
  echo "用法: $0 <ClassName1> [ClassName2 ...]" >&2
  echo "      $0 --auto   # 自动扫 scan_dead_code.sh 输出" >&2
  exit 2
fi

# --auto 模式：从最新 scan-dead-code 报告读 HIGH 项
if [ "$MODE" = "auto" ]; then
  latest=$(ls -t "${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports/scan-dead-code-"*.json 2>/dev/null | head -1)
  if [ -z "$latest" ]; then
    echo "[deletion] ❌ --auto 模式需要 scan_dead_code.sh 已跑过" >&2
    exit 2
  fi
  echo "[deletion] --auto 从 $latest 抽取 HIGH 项..."
  TARGETS=($(python3 -c "
import json, sys
with open('$latest') as f:
    data = json.load(f)
for item in data:
    if item.get('risk') == 'HIGH':
        print(item['class'])
" 2>/dev/null))
  if [ "${#TARGETS[@]}" -eq 0 ]; then
    echo "[deletion] 无 HIGH 项，退出" >&2
    exit 0
  fi
  echo "[deletion] 待审 HIGH 类: ${TARGETS[*]}"
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports"
mkdir -p "$OUTPUT_DIR"
REPORT_MD="${OUTPUT_DIR}/deletion-consistency-${TIMESTAMP}.md"
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

echo "==== R25 P1-4 删除前对账 ===="
echo "目标: ${TARGETS[*]}"
echo

> "$TMPDIR_CHECK/all_results.txt"

for TARGET in "${TARGETS[@]}"; do
  echo ">>> 处理: $TARGET"
  echo "" >> "$TMPDIR_CHECK/all_results.txt"
  echo "## $TARGET" >> "$TMPDIR_CHECK/all_results.txt"

  # ---- 1. 后端 Java 引用 ----
  echo "  [1/7] 后端 Java 引用..."
  be_refs=$(grep -rEn "\b${TARGET}\b" \
    "$BACKEND_ROOT/ruoyi-modules/${MODULE}/src" \
    "$BACKEND_ROOT/ruoyi-common/${MODULE}/src" \
    --include='*.java' \
    --exclude-dir=target --exclude-dir=worktrees 2>/dev/null \
    | grep -vE "^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)" \
    | grep -vE "/${TARGET}\.java:" \
    | wc -l | tr -d ' ')
  echo "  - 后端 Java 引用（排除自身 + 注释）: $be_refs 行" >> "$TMPDIR_CHECK/all_results.txt"

  # ---- 2. 前端 TS 引用 ----
  echo "  [2/7] 前端 TS 引用..."
  fe_refs=$(grep -rEn "\b${TARGET}\b" \
    "$FRONTEND_ROOT/src" \
    --include='*.ts' --include='*.tsx' --include='*.vue' \
    --exclude-dir=node_modules --exclude-dir=.pnpm-store 2>/dev/null \
    | wc -l | tr -d ' ')
  echo "  - 前端 TS/Vue 引用: $fe_refs 行" >> "$TMPDIR_CHECK/all_results.txt"

  # ---- 3. Mapper XML 引用 ----
  echo "  [3/7] Mapper XML 引用..."
  xml_refs=$(grep -rEn "\b${TARGET}\b" \
    "$BACKEND_ROOT/ruoyi-modules/${MODULE}/src/main/resources" \
    --include='*.xml' \
    2>/dev/null | wc -l | tr -d ' ')
  echo "  - Mapper XML 引用: $xml_refs 行" >> "$TMPDIR_CHECK/all_results.txt"

  # ---- 4. SQL 脚本引用 ----
  echo "  [4/7] SQL 脚本引用..."
  sql_refs=$(grep -rEn "\b${TARGET}\b|${TARGET,,}" \
    "$BACKEND_ROOT/docs/script/sql" \
    --include='*.sql' --include='*.md' \
    2>/dev/null | wc -l | tr -d ' ')
  echo "  - SQL 脚本引用: $sql_refs 行" >> "$TMPDIR_CHECK/all_results.txt"

  # ---- 5. i18n key 引用 ----
  echo "  [5/7] i18n key 引用..."
  i18n_refs=$(grep -rEn "\"${TARGET}\"|\.${TARGET}\b" \
    "$FRONTEND_ROOT/src/locales" \
    --include='*.json' \
    2>/dev/null | wc -l | tr -d ' ')
  echo "  - i18n key 引用: $i18n_refs 行" >> "$TMPDIR_CHECK/all_results.txt"

  # ---- 6. 看板/SSOT 引用 ----
  echo "  [6/7] 看板/SSOT 引用..."
  ssot_file="$BACKEND_ROOT/docs/ipd-系统说明/开发计划-看板镜像.md"
  if [ -f "$ssot_file" ]; then
    ssot_refs=$(grep -cE "\b${TARGET}\b" "$ssot_file" 2>/dev/null || echo 0)
  else
    ssot_refs=0
  fi
  echo "  - 看板 SSOT 引用: $ssot_refs 行" >> "$TMPDIR_CHECK/all_results.txt"

  # ---- 7. 测试引用 ----
  echo "  [7/7] 测试引用..."
  test_refs=$(grep -rEn "\b${TARGET}\b" \
    "$BACKEND_ROOT/ruoyi-modules/${MODULE}/src/test" \
    --include='*.java' --include='*.xml' \
    --exclude-dir=worktrees 2>/dev/null | wc -l | tr -d ' ')
  echo "  - 测试引用: $test_refs 行" >> "$TMPDIR_CHECK/all_results.txt"

  echo "" >> "$TMPDIR_CHECK/all_results.txt"
done

# 汇总
cat > "$REPORT_MD" <<EOF
# R25 P1-4 删除前对账报告（${TIMESTAMP}）

> 自动门禁：\`scripts/check_deletion_consistency.sh\`（避免 R14 AllowanceService / IpdPermissionCode 误删）
> 待审目标：\`${TARGETS[*]}\`
> 模式：\`$MODE\`

## 汇总表

| 目标类 | 后端Java | 前端TS | Mapper XML | SQL脚本 | i18n | SSOT | 测试 |
|---|---|---|---|---|---|---|---|
EOF

echo "$(cat "$TMPDIR_CHECK/all_results.txt")" >> "$REPORT_MD"

cat >> "$REPORT_MD" <<EOF

## 判定准则

1. **后端Java 引用 = 0 且 Mapper XML = 0 且 SQL = 0**：可删
2. **i18n 引用 > 0**：需同步删除 i18n key（4 语言）
3. **SSOT 引用 > 0**：需先关闭对应看板卡（置"已删除"状态，登记 R25 治理）
4. **测试引用 > 0**：先删测试再删主代码（或同步删除）

## 红线

- 任一维度 > 0 引用时 **禁止删除**
- 必须先完成引用清理，**再** 重跑本脚本确认全 0

## 重跑命令

\`\`\`bash
cd ${BACKEND_ROOT}
./scripts/check_deletion_consistency.sh ${TARGETS[0]:-FooService}
./scripts/check_deletion_consistency.sh --auto   # 自动扫 scan_dead_code.sh HIGH 项
\`\`\`

## 排除范围

- framework 自带类
- 注释 / javadoc / @link 引用（已用 grep 排除）
- 自我引用（已用 grep -vE "/${TARGET}\.java:" 排除）
EOF

echo
echo "==== 对账完成 ===="
echo "报告: $REPORT_MD"
exit 0
