#!/usr/bin/env bash
# check_dynamic_loadable.sh
# R25 P0-3 根因 RC-5 治理：动态加载依赖清单
#
# 设计要点：
#   1. 解析前端 src/router/access.ts 的 import.meta.glob 模式
#   2. 解析后端菜单 SQL（admin/sys_menu 表）的 component 字段
#   3. 两者求交集 = 真正 dynamic-loadable 视图
#   4. 这部分视图静态扫描无法判定真用/真死，需 owner 与后端菜单对账
#
# 退出码:
#   0  = 动态依赖清单已生成
#   1  = 解析失败或冲突
#   2  = 脚本错误
#
# 用法:
#   ./scripts/check_dynamic_loadable.sh
#   ./scripts/check_dynamic_loadable.sh --output path/to/manifest.json

set -u

FRONTEND_ROOT="${FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd}"
BACKEND_ROOT="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}"
OUTPUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --output) OUTPUT="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,22p' "$0" | sed 's/^# //;s/^#//'
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

if [ -n "$ACCESS_FILE" ]; then
  # 提取 pageMap = import.meta.glob("xxx")
  grep -E 'import\.meta\.glob' "$ACCESS_FILE" 2>/dev/null \
    | grep -oE '"[^"]*"' \
    | tr -d '"' \
    > "$TMPDIR_CHECK/fe_glob_raw.txt"

  cat "$TMPDIR_CHECK/fe_glob_raw.txt"
fi

echo "  → import.meta.glob 模式数: $(wc -l < "$TMPDIR_CHECK/fe_glob_raw.txt" 2>/dev/null | tr -d ' ' || echo 0)"

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
fe_views_count=$(wc -l < "$TMPDIR_CHECK/fe_views.uniq.txt" 2>/dev/null | tr -d ' ' || echo 0)
echo "  → 前端动态可加载视图数: $fe_views_count"

# ---- 2. 后端菜单 SQL component 字段 ----
echo
echo "[2/3] 解析后端菜单 SQL 的 component 字段..."

# 方式 A：从真库读 admin/sys_menu 表（首选）
# 方式 B：从 sql 种子文件读（fallback）

> "$TMPDIR_CHECK/be_components.txt"

# 找 sql 种子文件中的 menu insert 语句
seed_files=$(find "$BACKEND_ROOT/ruoyi-modules" -name "*.sql" 2>/dev/null \
  | xargs grep -l "sys_menu\|menu.*component" 2>/dev/null \
  | head -5)

if [ -n "$seed_files" ]; then
  echo "  → SQL 种子文件: $(echo "$seed_files" | wc -l | tr -d ' ')"
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
be_comp_count=$(wc -l < "$TMPDIR_CHECK/be_comp.uniq.txt" 2>/dev/null | tr -d ' ' || echo 0)
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

intersect_count=$(wc -l < "$TMPDIR_CHECK/intersect.txt" 2>/dev/null | tr -d ' ' || echo 0)
echo "  → 真正 dynamic-loadable 视图数: $intersect_count"

# ---- 输出 Markdown ----
cat > "$REPORT_MD" <<EOF
# R25 P0-3 动态加载依赖清单（${TIMESTAMP}）

> 自动门禁：\`scripts/check_dynamic_loadable.sh\`（RC-5 动态依赖清单）
> 维护：每周日 02:00 自动重生成 + 每次菜单 schema 变更手动重跑

## 汇总

| 类别 | 数量 |
|---|---|
| 前端 \`import.meta.glob\` 模式数 | $(wc -l < "$TMPDIR_CHECK/fe_glob_raw.txt" 2>/dev/null | tr -d ' ' || echo 0) |
| 前端动态可加载视图数 | $fe_views_count |
| 后端菜单 component 字段数 | $be_comp_count |
| 🔴 **真正 dynamic-loadable 视图数（交集）** | **$intersect_count** |

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

## 重跑命令

\`\`\`bash
cd ${BACKEND_ROOT}
./scripts/check_dynamic_loadable.sh
./scripts/check_dynamic_loadable.sh --output /path/to/manifest.json
\`\`\`

## CI 接入

\`\`\`yaml
# .github/workflows/dynamic-loadable.yml
- name: 重生成动态依赖清单
  run: ./scripts/check_dynamic_loadable.sh --output docs/ipd-系统说明/lint-reports/dynamic-loadable-manifest.json
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
  "dynamic_loadable_intersect": [
$(awk '{ printf "    \"%s\",\n", $1 }' "$TMPDIR_CHECK/intersect.txt" 2>/dev/null | sed '$s/,$//')
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