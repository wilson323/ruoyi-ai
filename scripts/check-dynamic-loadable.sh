#!/usr/bin/env bash
# check-dynamic-loadable.sh
# R25 P0-3 根因 RC-5 治理：动态加载依赖清单
#
# 设计要点：
#   1. 解析前端 src/router/access.ts 的 import.meta.glob 模式
#   2. 解析后端菜单 SQL（admin/sys_menu 表）的 component 字段
#   3. 两者求交集 = 真正 dynamic-loadable 视图
#   4. 这部分视图静态扫描无法判定真用/真死，需 owner 与后端菜单对账
#
# 退出码:
#   0  = 动态依赖清单已生成，无未豁免违规、无失效豁免
#   1  = 解析失败或冲突 / 有未豁免违规（DIFFS>0）/ 有豁免项失效
#   2  = 脚本错误（含豁免文件存在无理由条目 → 拒绝执行）
#
# 豁免机制（详见下文 3b 节与 scripts/gate-exemptions/dynamic-loadable.txt）:
#   - 已人工审过的动态可达视图可登记进豁免清单，不计入 DIFFS
#   - 每条豁免必须带理由注释，空理由行 → exit 2 拒绝执行
#   - 豁免文件不存在 = 无任何豁免、全量照常报红（不是全豁免，也不是静默通过）
#   - 豁免项对应视图已不存在 → 判「豁免项失效」并 exit 1，强制删行
#
# 用法:
#   ./scripts/check-dynamic-loadable.sh
#   ./scripts/check-dynamic-loadable.sh --output path/to/manifest.json

set -u

# 陷阱（bash 3.2 / macOS 自带）：`$var` 后面紧跟中文字符时，bash 会把中文的首个
# 字节当成变量名的一部分 → `set -u` 下直接报 "xxx: unbound variable" 并以 127 退出。
# 本脚本输出全是中文，任何 `$var` 紧邻中文都必须写成 `${var}`。
# 自证用例 T14 就是踩这个坑踩出来的（exit 127 被误读成「门禁没拒绝」）。

# 计数辅助库：gate_count_lines 在输入缺失/不可读时返回 2，绝不退化成 0 或空串。
# 详见 lib/audit-gate-input.sh 中该函数的语义决策说明。
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)"
# shellcheck source=scripts/lib/audit-gate-input.sh
. "$LIB_DIR/audit-gate-input.sh"

FRONTEND_ROOT="${FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd}"
BACKEND_ROOT="${BACKEND_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
OUTPUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --output) OUTPUT="$2"; shift 2 ;;
    -h|--help)
      # 按「连续注释块」提取，不写死行号 —— 头部注释增删时 help 不会静默截断
      awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
      exit 0
      ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports"
mkdir -p "$OUTPUT_DIR"
REPORT_MD="${OUTPUT_DIR}/dynamic-loadable-${TIMESTAMP}.md"
REPORT_JSON="${OUTPUT_DIR}/dynamic-loadable-${TIMESTAMP}.json"
[ -n "$OUTPUT" ] && REPORT_JSON="$OUTPUT"

TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

echo "==== R25 P0-3 动态加载依赖清单（RC-5） ===="
echo "前端: $FRONTEND_ROOT"
echo "后端: $BACKEND_ROOT"
echo

# ---- 1. 前端 router/access.ts import.meta.glob 模式 ----
echo "[1/3] 解析前端 router/access.ts 的 import.meta.glob..."

ACCESS_FILE="$FRONTEND_ROOT/src/router/access.ts"
if [ ! -f "$ACCESS_FILE" ]; then
  echo "  ⚠️ $ACCESS_FILE 不存在" >&2
  # fallback: 扫描整个 src/router
  ACCESS_FILE=""
fi

> "$TMPDIR_CHECK/fe_glob_pattern.txt"
# 「确实为 0 个 glob 模式」与「文件没生成」必须可区分：先把文件建出来，
# 否则 ACCESS_FILE 缺失分支下后续 gate_count_lines 会拿到不存在的文件而报门禁错误。
> "$TMPDIR_CHECK/fe_glob_raw.txt"

if [ -n "$ACCESS_FILE" ]; then
  # 提取 pageMap = import.meta.glob("xxx")
  # 引号必须同时吃单引号与双引号：真实前端 access.ts 用的是单引号
  # import.meta.glob('../views/**/*.vue')，只匹配双引号会让模式数恒为 0、
  # 整条门禁静默失效。字符类写作 ['\"] 是为了同时容纳两种定界符。
  grep -E 'import\.meta\.glob' "$ACCESS_FILE" 2>/dev/null \
    | grep -oE "['\"][^'\"]*['\"]" \
    | tr -d "'\"" \
    > "$TMPDIR_CHECK/fe_glob_raw.txt"

  cat "$TMPDIR_CHECK/fe_glob_raw.txt"
fi

fe_glob_count=$(gate_count_lines "$TMPDIR_CHECK/fe_glob_raw.txt") || exit 2
echo "  → import.meta.glob 模式数: $fe_glob_count"

# 把 glob 模式转换为视图路径列表
> "$TMPDIR_CHECK/fe_views.txt"
while IFS= read -r pattern; do
  [ -z "$pattern" ] && continue
  # 脱 glob 模式用 grep 匹配（避免 shell case 的 * 通配符问题）
  if echo "$pattern" | grep -qF "../views/**/*.vue"; then
    if [ -d "$FRONTEND_ROOT/src/views" ]; then
      find "$FRONTEND_ROOT/src/views" -name "*.vue" -not -path '*/node_modules/*' -not -path '*/.pnpm-store/*' \
        | sed "s|$FRONTEND_ROOT/src/views|#/views|" \
        >> "$TMPDIR_CHECK/fe_views.txt"
    fi
  elif echo "$pattern" | grep -qF "../**/*.vue"; then
    # 极宽松：所有 vue 文件
    find "$FRONTEND_ROOT/src" -name "*.vue" -not -path '*/node_modules/*' -not -path '*/.pnpm-store/*' \
      | sed "s|$FRONTEND_ROOT/src|#|" \
      >> "$TMPDIR_CHECK/fe_views.txt"
  else
    echo "  ⚠️ 未识别 glob 模式: $pattern" >&2
  fi
done < "$TMPDIR_CHECK/fe_glob_raw.txt"

sort -u "$TMPDIR_CHECK/fe_views.txt" > "$TMPDIR_CHECK/fe_views.uniq.txt"
fe_views_count=$(gate_count_lines "$TMPDIR_CHECK/fe_views.uniq.txt") || exit 2
echo "  → 前端动态可加载视图数: $fe_views_count"

# ---- 2. 后端菜单 SQL component 字段 ----
echo
echo "[2/3] 解析后端菜单 SQL 的 component 字段..."

# 方式 A：从真库读 admin/sys_menu 表（首选）
# 方式 B：从 sql 种子文件读（fallback）

> "$TMPDIR_CHECK/be_components.txt"

# 找 sql 种子文件中的 menu insert 语句。
# 根因 R120-B：原实现只扫 ruoyi-modules，但该目录下 .sql 文件数为 0，
# 真实 IPD 菜单种子全在 docs/script/sql（含 update/ 等子目录）。范围写错导致
# be_comp_count 恒为 0、交集恒为 0、DIFFS 恒为 0 → 这条门禁自 2026-09-19 起恒绿。
# 现在按多根目录扫描，并对每个根目录显式剔除历史快照污染目录。
#
# 排除项是硬要求：.harness/ 下实测有 491 个 .sql（备份快照），若纳入会把
# 历史菜单快照当成现行种子。target/ 与 node_modules/ 同理。
SEED_SCAN_ROOTS=(
  "$BACKEND_ROOT/docs/script/sql"   # 现行菜单种子真源
  "$BACKEND_ROOT/ruoyi-modules"     # 未来若把种子放进模块资源目录也能扫到
)
SEED_EXCLUDE_ARGS=(
  -not -path "*/.harness/*"
  -not -path "*/target/*"
  -not -path "*/node_modules/*"
  -not -path "*/.git/*"
)

# R120-B：原实现用 `head -5` 截断。find 的输出顺序不保证稳定，截断等于让
# 门禁结论取决于文件系统枚举顺序；且 docs/script/sql 下实际有 11 个命中文件，
# 截断后只留下 5 个，稳定漏掉 ipd/product-lines/index。截断不是判定条件，
# 是扫描缺陷，故移除；改为按路径排序保证可复现。
seed_files=$(
  for root in "${SEED_SCAN_ROOTS[@]}"; do
    [ -d "$root" ] || continue
    find "$root" -name "*.sql" "${SEED_EXCLUDE_ARGS[@]}" 2>/dev/null
  done \
    | sort -u \
    | xargs grep -l "sys_menu\|menu.*component" 2>/dev/null \
    | sort
)

# 落盘成真实文件再计数：gate_count_lines 要求普通文件（-f），管道 /dev/stdin 不满足。
# 命令替换已剥掉尾随换行，故此处必须补 \n，否则最后一行不被 wc -l 计入（11 会被数成 10）。
# 空串不写盘：否则「0 个种子文件」会被数成 1。
if [ -n "$seed_files" ]; then
  printf '%s\n' "$seed_files" > "$TMPDIR_CHECK/seed_files.txt"
fi

if [ -n "$seed_files" ]; then
  seed_file_count=$(gate_count_lines "$TMPDIR_CHECK/seed_files.txt") || exit 2
  echo "  → SQL 种子文件: $seed_file_count"
  # 提取 component 字段值
  while IFS= read -r sf; do
    [ -f "$sf" ] || continue
    # 匹配 (..., 'ipd/xxx/index', ...) 或 ('ipd/xxx/index', ...)
    grep -oE "'ipd/[a-z0-9_/-]+'" "$sf" 2>/dev/null \
      | tr -d "'" \
      >> "$TMPDIR_CHECK/be_components.txt"
    # 也匹配双引号
    grep -oE '"ipd/[a-z0-9_/-]+"' "$sf" 2>/dev/null \
      | tr -d '"' \
      >> "$TMPDIR_CHECK/be_components.txt"
  done <<< "$seed_files"
fi

sort -u "$TMPDIR_CHECK/be_components.txt" > "$TMPDIR_CHECK/be_comp.uniq.txt"
be_comp_count=$(gate_count_lines "$TMPDIR_CHECK/be_comp.uniq.txt") || exit 2
echo "  → 后端菜单 component 字段数: $be_comp_count"

# ---- 3. 求交集：真正 dynamic-loadable 视图 ----
echo
echo "[3/3] 求交集（dynamic-loadable 视图）..."

# 把 component 字段映射成 #/views/.../xxx.vue
> "$TMPDIR_CHECK/intersect.txt"
while IFS= read -r comp; do
  [ -z "$comp" ] && continue
  # 'ipd/xxx/index' → '#/views/ipd/xxx/index.vue'
  vue_path="#/views/${comp}.vue"
  if grep -qFx "$vue_path" "$TMPDIR_CHECK/fe_views.uniq.txt" 2>/dev/null; then
    echo "$comp|$vue_path" >> "$TMPDIR_CHECK/intersect.txt"
  fi
done < "$TMPDIR_CHECK/be_comp.uniq.txt"

intersect_count=$(gate_count_lines "$TMPDIR_CHECK/intersect.txt") || exit 2
echo "  → 真正 dynamic-loadable 视图数: $intersect_count"

# ---- 3b. 显式豁免清单 ----
# 门禁保持武装：豁免只对「已人工审过并逐条写明理由」的项生效，
# 清单之外的项照常进入 DIFFS 累加器，DIFFS>0 依旧 exit 1。
#
# 缺失语义（硬约定，写死不许改）：
#   - 豁免文件不存在 → 视为「无任何豁免」，全量照常报红。
#     绝不解释成「全部豁免」或「静默通过」——那会造出本项目已栽过多次的假绿门禁。
#   - 豁免行没有理由（`#` 之后为空）→ 门禁自身错误 exit 2，整条门禁拒绝执行。
#     空理由等于没审过；让没审过的项静默通过，是豁免机制最典型的自毁方式。
#   - 豁免项对应的视图已不存在（豁免项失效）→ 报红 exit 1，强制回头删行。
#     否则清单只增不减，最终会变成一张掩盖一切的废纸。
EXEMPTION_FILE="${EXEMPTION_FILE:-${BACKEND_ROOT}/scripts/gate-exemptions/dynamic-loadable.txt}"
echo "  → 豁免清单: $EXEMPTION_FILE"

> "$TMPDIR_CHECK/exempt_items.txt"
> "$TMPDIR_CHECK/exempt_detail.txt"
exempt_parse_error=0

_trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

if [ -f "$EXEMPTION_FILE" ]; then
  exempt_lineno=0
  while IFS= read -r raw || [ -n "$raw" ]; do
    exempt_lineno=$((exempt_lineno + 1))
    if [ "$raw" = "${raw%%#*}" ]; then
      item_raw="$raw"; reason_raw=""
    else
      item_raw="${raw%%#*}"; reason_raw="${raw#*#}"
    fi
    item=$(_trim "$item_raw")
    reason=$(_trim "$reason_raw")
    # item 为空 = 纯注释行 / 空行，直接跳过（不是「无理由豁免」）
    [ -z "$item" ] && continue
    if [ -z "$reason" ]; then
      echo "  ❌ 豁免文件第 ${exempt_lineno} 行「${item}」缺少理由注释" >&2
      exempt_parse_error=1
      continue
    fi
    printf '%s\n' "$item" >> "$TMPDIR_CHECK/exempt_items.txt"
    printf '%s|%s\n' "$item" "$reason" >> "$TMPDIR_CHECK/exempt_detail.txt"
  done < "$EXEMPTION_FILE"
else
  echo "  ⚠️ 豁免文件不存在 → 视为无任何豁免，全量照常判定（不是全豁免、不是静默通过）"
fi

if [ "$exempt_parse_error" -ne 0 ]; then
  echo "  ❌ 豁免文件含无理由条目，门禁拒绝执行（防豁免清单退化成盲区）" >&2
  exit 2
fi

sort -u "$TMPDIR_CHECK/exempt_items.txt" > "$TMPDIR_CHECK/exempt.uniq.txt"
exempt_count=$(gate_count_lines "$TMPDIR_CHECK/exempt.uniq.txt") || exit 2
echo "  → 豁免条目数: $exempt_count"

# 违规项 = 交集 − 豁免；豁免项单独记账但不再进入 DIFFS
> "$TMPDIR_CHECK/violations.txt"
> "$TMPDIR_CHECK/exempted_hits.txt"
while IFS='|' read -r comp vue; do
  [ -z "$comp" ] && continue
  if grep -qxF "$comp" "$TMPDIR_CHECK/exempt.uniq.txt" 2>/dev/null; then
    printf '%s|%s\n' "$comp" "$vue" >> "$TMPDIR_CHECK/exempted_hits.txt"
  else
    printf '%s|%s\n' "$comp" "$vue" >> "$TMPDIR_CHECK/violations.txt"
  fi
done < "$TMPDIR_CHECK/intersect.txt"

violations_count=$(gate_count_lines "$TMPDIR_CHECK/violations.txt") || exit 2
exempted_hit_count=$(gate_count_lines "$TMPDIR_CHECK/exempted_hits.txt") || exit 2
echo "  → 已豁免命中数: $exempted_hit_count"
# 标签里不要放全角括号：自测脚本用 sed 按「标签: 数字」抓取，
# 全角括号会让抓取正则与输出对不上（实测报 none，且在部分 locale 下抛
# "illegal byte sequence"，把整条用例的判据吞掉）。口径写在注释里。
echo "  → 未豁免违规数 DIFFS: $violations_count"

# 失效豁免 = 豁免 − 交集。豁免项对应的页面已经不存在了 → 报出来。
> "$TMPDIR_CHECK/stale_exemptions.txt"
while IFS= read -r item; do
  [ -z "$item" ] && continue
  if ! awk -F'|' -v k="$item" '$1==k{f=1} END{exit !f}' "$TMPDIR_CHECK/intersect.txt"; then
    printf '%s\n' "$item" >> "$TMPDIR_CHECK/stale_exemptions.txt"
  fi
done < "$TMPDIR_CHECK/exempt.uniq.txt"

stale_count=$(gate_count_lines "$TMPDIR_CHECK/stale_exemptions.txt") || exit 2
if [ "$stale_count" -gt 0 ]; then
  echo "  → 🔴 豁免项失效数: $stale_count"
  while IFS= read -r s_item; do
    [ -z "$s_item" ] && continue
    echo "      - ${s_item}（对应视图已不存在，豁免行必须删掉）" >&2
  done < "$TMPDIR_CHECK/stale_exemptions.txt"
else
  echo "  → 豁免项失效数: 0"
fi

# ---- 输出 Markdown ----
cat > "$REPORT_MD" <<EOF
# R25 P0-3 动态加载依赖清单（${TIMESTAMP}）

> 自动门禁：\`scripts/check-dynamic-loadable.sh\`（RC-5 动态依赖清单）
> 维护：每周日 02:00 自动重生成 + 每次菜单 schema 变更手动重跑

## 汇总

| 类别 | 数量 |
|---|---|
| 前端 \`import.meta.glob\` 模式数 | $fe_glob_count |
| 前端动态可加载视图数 | $fe_views_count |
| 后端菜单 component 字段数 | $be_comp_count |
| **真正 dynamic-loadable 视图数（交集）** | **$intersect_count** |
| 其中已人工审过并豁免 | $exempted_hit_count |
| 🔴 **未豁免违规数（DIFFS，门禁据此判红）** | **$violations_count** |
| 🔴 豁免项失效数（对应视图已不存在） | $stale_count |

## 静态扫描说明

R25-B 前端死代码扫描共发现 156 个"未路由"视图，其中：
- 147 个 = 真死（路由表 + 静态 import 都未引用）→ 可静态判定
- **$intersect_count 个 = 动态可达（被后端菜单 component 字段动态加载）→ 必须 owner 复核**

静态扫描工具无法区分这两类，必须依赖本清单才能准确判别。

## 🔴 Dynamic-Loadable 视图清单

> 这些视图由 \`router/access.ts\` 的 \`import.meta.glob\` + 后端菜单 component 字段联动加载
> 删除前必须确认后端菜单不再下发对应 component，否则**菜单点开白屏**

| # | 后端 component 字段 | 前端 vue 路径 |
|---|---|---|
EOF

line=0
while IFS='|' read -r comp vue; do
  [ -z "$comp" ] && continue
  line=$((line + 1))
  echo "$line | \`$comp\` | \`$vue\` |" >> "$REPORT_MD"
done < "$TMPDIR_CHECK/intersect.txt"

cat >> "$REPORT_MD" <<EOF

## 豁免机制说明

豁免清单：\`scripts/gate-exemptions/dynamic-loadable.txt\`（每行 \`<component>\` + 必填理由注释）

- 清单内的项不计入 DIFFS，但必须逐条写明理由；无理由的行会让门禁 \`exit 2\` 拒绝执行。
- **清单文件不存在 = 无任何豁免，全量照常报红**（不是「全部豁免」，也不是「静默通过」）。
- 清单之外的项照常进入 DIFFS，\`DIFFS>0 → exit 1\` 判定条件原样保留，检出能力不因豁免而削弱。
- 豁免项对应的视图若已不存在，判为**豁免项失效**并 \`exit 1\`，强制回头删行，
  防止清单只增不减退化成一张掩盖一切的废纸。

### 本次已豁免命中（$exempted_hit_count 项）

| # | 后端 component 字段 | 前端 vue 路径 | 豁免理由 |
|---|---|---|---|
EOF

line=0
while IFS='|' read -r comp vue; do
  [ -z "$comp" ] && continue
  line=$((line + 1))
  reason=$(awk -F'|' -v k="$comp" '$1==k{$1=""; sub(/^\|/,""); print; exit}' \
    "$TMPDIR_CHECK/exempt_detail.txt" 2>/dev/null)
  echo "$line | \`$comp\` | \`$vue\` | $reason |" >> "$REPORT_MD"
done < "$TMPDIR_CHECK/exempted_hits.txt"

cat >> "$REPORT_MD" <<EOF

## 🔴 未豁免违规（$violations_count 项，门禁判红依据）

| # | 后端 component 字段 | 前端 vue 路径 |
|---|---|---|
EOF

line=0
while IFS='|' read -r comp vue; do
  [ -z "$comp" ] && continue
  line=$((line + 1))
  echo "$line | \`$comp\` | \`$vue\` |" >> "$REPORT_MD"
done < "$TMPDIR_CHECK/violations.txt"

cat >> "$REPORT_MD" <<EOF

## 🔴 豁免项失效（$stale_count 项，对应视图已不存在，必须删掉豁免行）

| # | 已失效的豁免项 |
|---|---|
EOF

line=0
while IFS= read -r s_item; do
  [ -z "$s_item" ] && continue
  line=$((line + 1))
  echo "$line | \`$s_item\` |" >> "$REPORT_MD"
done < "$TMPDIR_CHECK/stale_exemptions.txt"

cat >> "$REPORT_MD" <<EOF

## 重跑命令

\`\`\`bash
cd ${BACKEND_ROOT}
./scripts/check-dynamic-loadable.sh
./scripts/check-dynamic-loadable.sh --output /path/to/manifest.json
\`\`\`

## CI 接入

\`\`\`yaml
# .github/workflows/dynamic-loadable.yml
- name: 重生成动态依赖清单
  run: ./scripts/check-dynamic-loadable.sh --output docs/ipd-系统说明/lint-reports/dynamic-loadable-manifest.json
\`\`\`

清单变化 > 0 时自动开 PR，owner 审核后合入。
EOF

# 输出 JSON 清单
cat > "$REPORT_JSON" <<EOF
{
  "timestamp": "${TIMESTAMP}",
  "frontend_glob_patterns": $(cat "$TMPDIR_CHECK/fe_glob_raw.txt" 2>/dev/null | jq -R . | jq -s . 2>/dev/null || echo "[]"),
  "frontend_dynamic_views": $fe_views_count,
  "backend_components": $be_comp_count,
  "exemption_file": "${EXEMPTION_FILE}",
  "exemptions_declared": $exempt_count,
  "exempted_hits": [
$(awk '{ printf "    \"%s\",\n", $1 }' "$TMPDIR_CHECK/exempted_hits.txt" 2>/dev/null | sed '$s/,$//')
  ],
  "stale_exemptions": [
$(awk '{ printf "    \"%s\",\n", $1 }' "$TMPDIR_CHECK/stale_exemptions.txt" 2>/dev/null | sed '$s/,$//')
  ],
  "dynamic_loadable_intersect": [
$(awk '{ printf "    \"%s\",\n", $1 }' "$TMPDIR_CHECK/intersect.txt" 2>/dev/null | sed '$s/,$//')
  ],
  "unexempted_violations": [
$(awk '{ printf "    \"%s\",\n", $1 }' "$TMPDIR_CHECK/violations.txt" 2>/dev/null | sed '$s/,$//')
  ]
}
EOF

echo
echo "==== 清单生成完成 ===="
echo "报告: $REPORT_MD"
echo "JSON: $REPORT_JSON"
echo
echo "说明：静态扫描工具会把 dynamic-loadable 视图误判为「未路由」"
echo "      实际清理前必须 owner 与后端菜单对账，本清单是单一真值源"

# R120 根除机制：DIFFS 累加器（R119 reconcile-multi-source.sh 同款修法）
# DIFFS = 交集 − 已审豁免项；豁免只减不减「检出范围」——清单外的项一条都不放过。
# 注意此处用的是 violations_count 而不是 intersect_count：加了豁免机制之后
# 判红依据是「没人审过的动态可达视图」，不是「动态可达视图总数」。
DIFFS=${violations_count:-0}

# 自证能红：差异项 > 0 → exit 1
if [ "${DIFFS:-0}" -gt 0 ]; then
  exit 1
fi

# 豁免项失效：对应视图已不存在 → 豁免行是废行，强制回头删。
# 放在 DIFFS 之后单独判，是因为它的性质不同：不是「新违规」，是「豁免清单腐烂」。
if [ "${stale_count:-0}" -gt 0 ]; then
  exit 1
fi

exit 0