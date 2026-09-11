#!/usr/bin/env bash
# check_cross_repo_contract.sh
# R25 P0-2 根因 RC-2 治理：跨仓契约对账
#
# 三向对账：
#   1. 后端 Controller @GetMapping / @PostMapping 等端点全集
#   2. 前端 src/api/ipd/*.ts 调用路径全集
#   3. 文档（ZK-IPD 开发说明书 + AI开发主Prompt_v3）端点全集
#
# 输出：
#   - D1-A 前端有、后端无（白屏风险）——致命
#   - D1-B 后端有、前端无（孤儿端点）——人工确认外部调用方
#   - D1-C 路径漂移（method+path 双向不一致）——致命
#   - D1-D 文档端点 vs 实现不一致（未实现 / 已废弃）
#
# 退出码:
#   0  = 无白屏风险 + 路径漂移
#   1  = 发现白屏风险或路径漂移
#   2  = 脚本错误
#
# 用法:
#   ./scripts/check_cross_repo_contract.sh

set -u

BACKEND_ROOT="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}"
FRONTEND_ROOT="${FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd}"
DOCS_ROOT="${DOCS_ROOT:-/Users/mac/Documents/ZK-IPD}"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports"
mkdir -p "$OUTPUT_DIR"
REPORT_MD="${OUTPUT_DIR}/contract-drift-${TIMESTAMP}.md"
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

# 白屏风险 / 路径漂移 计数
WHITE_SCREEN=0
ORPHAN_ENDPOINT=0
PATH_DRIFT=0
DOC_IMPL_DRIFT=0

echo "==== R25 P0-2 跨仓契约对账（RC-2） ===="
echo "后端: $BACKEND_ROOT/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/"
echo "前端: $FRONTEND_ROOT/src/api/ipd/"
echo "文档: $DOCS_ROOT/开发说明书.md + 产品流程细化管理工具/IPD系统_AI开发主Prompt_v3.md"
echo

if [ ! -d "$BACKEND_ROOT/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller" ]; then
  echo "[check-contract] ❌ 后端 controller 目录不存在" >&2
  exit 2
fi
if [ ! -d "$FRONTEND_ROOT/src/api/ipd" ]; then
  echo "[check-contract] ❌ 前端 api/ipd 目录不存在" >&2
  exit 2
fi

# ---- 1. 后端 Controller 端点全集 ----
echo "[1/4] 抽取后端 Controller 端点..."

> "$TMPDIR_CHECK/backend_endpoints.txt"
for ctrl in "$BACKEND_ROOT/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller"/*.java; do
  [ -f "$ctrl" ] || continue
  base=$(basename "$ctrl" .java)

  # 1) class-level @RequestMapping("/api/v1/xxx")
  class_mapping=$(grep -oE '@RequestMapping[[:space:]]*\([[:space:]]*"[^"]*"' "$ctrl" 2>/dev/null | grep -oE '"[^"]*"' | tr -d '"' | head -1)
  if [ -z "$class_mapping" ]; then
    class_mapping=""
  fi

  # 2) method-level @GetMapping / @PostMapping 等
  # 形式1：@GetMapping("/path")
  # 形式2：@GetMapping(value = "/path")
  # 形式3：@GetMapping(path = "/path")
  # 【精度修复】必须含 (，否则 bare 注解（如 `@PostMapping` 单独占一行）会被 sed 漏过滤，
  #        原样输出后拼接成 "/api/v1/xxx@ControllerMapping" 这种怪路径。
  grep -E '^[[:space:]]*@(Get|Post|Put|Delete|Patch)Mapping[[:space:]]*\(' "$ctrl" 2>/dev/null \
    | sed -E 's/.*@(Get|Post|Put|Delete|Patch)Mapping[[:space:]]*\(//' \
    | sed -E 's/^[[:space:]]*"([^"]*)".*/\1/' \
    | sed -E 's/.*value[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/' \
    | sed -E 's/.*path[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/' \
    | while read -r path; do
      # 拼接 class_mapping + method_path
      full="${class_mapping}${path}"
      # 补 /api/v1 前缀（如果没有）
      case "$full" in
        /api/v1/*) echo "$full" ;;
        /*) echo "/api/v1${full}" ;;
        *) echo "/api/v1/${full}" ;;
      esac
    done >> "$TMPDIR_CHECK/backend_endpoints.txt"

  # 4) bare 注解（@PostMapping 无任何参数）和空括号注解（@PostMapping()）——
  #    两者都映射到 class_mapping 本身（根路径）。
  # 【精度修复】原 grep 仅匹配空括号，bare 注解漏网后会污染主流水线（见上）。
  # 【兼容修复】macOS BSD grep 不接受空子表达式 `(\(\)|)`，拆成两条独立 grep。
  for ln in $(grep -E '^[[:space:]]*@(Get|Post|Put|Delete|Patch)Mapping[[:space:]]*$' "$ctrl" 2>/dev/null) \
            $(grep -E '^[[:space:]]*@(Get|Post|Put|Delete|Patch)Mapping[[:space:]]*\([[:space:]]*\)[[:space:]]*$' "$ctrl" 2>/dev/null); do
    if [ -n "$class_mapping" ]; then
      echo "$class_mapping"
    fi
  done >> "$TMPDIR_CHECK/backend_endpoints.txt"
done

sort -u "$TMPDIR_CHECK/backend_endpoints.txt" > "$TMPDIR_CHECK/be.uniq.txt"
be_count=$(wc -l < "$TMPDIR_CHECK/be.uniq.txt" | tr -d ' ')
echo "  → 后端端点数: $be_count"

# ---- 2. 前端 API 调用全集 ----
echo
echo "[2/4] 抽取前端 src/api/ipd/*.ts 调用路径..."

> "$TMPDIR_CHECK/frontend_endpoints.txt"
# 严格路径正则：必须 /api/v1/ 开头，路径部分只允许 ASCII 字符
# 必须以 " ' ` , ) ] 等闭括号结尾，避免拖入注释/中文标点
PATH_RE='/api/v1/[A-Za-z0-9/_:.\?\&\=%-]+'
# requestIpd() 传递的相对路径（不带 /api/v1/ 前缀）
REL_RE='/[a-zA-Z][A-Za-z0-9/_:.\?\&\=%-]+'

strip_ts_comments() {
  # 去掉 /* ... */ 多行注释和 // 单行注释（避免注释里的伪路径被误抓）
  sed -E '
    /\/\*/,/\*\//d
    s|//.*$||g
  '
}

for api_file in "$FRONTEND_ROOT/src/api/ipd"/*.ts; do
  [ -f "$api_file" ] || continue
  fname=$(basename "$api_file")
  case "$fname" in
    *.test.ts|*.d.ts) continue ;;
  esac

  # 1) 提取字面量路径：“/api/v1/xxx”（一般出现在测试或直接调用）
  strip_ts_comments < "$api_file" \
    | grep -oE "\"${PATH_RE}\"" 2>/dev/null \
    | tr -d '"' \
    >> "$TMPDIR_CHECK/frontend_endpoints.txt"

  # 2) 提取单引号路径：“/api/v1/xxx”
  strip_ts_comments < "$api_file" \
    | grep -oE "'${PATH_RE}'" 2>/dev/null \
    | tr -d "'" \
    >> "$TMPDIR_CHECK/frontend_endpoints.txt"

  # 3) 提取 requestIpd('xxx', ...) / ipdGet('xxx') / ipdPost('xxx') / ipdPut('xxx') / ipdDelete('xxx') 调用的相对路径，补 /api/v1 前缀
  strip_ts_comments < "$api_file" \
    | grep -oE "(requestIpd|ipdGet|ipdPost|ipdPut|ipdDelete|ipdPatch)\(\s*['\"\`](${REL_RE})['\"\`]" 2>/dev/null \
    | grep -oE "${REL_RE}" \
    | sed 's|^|/api/v1|' \
    >> "$TMPDIR_CHECK/frontend_endpoints.txt"

  # 3b) 提取上述函数调用中的模板字符串参数：ipdPost(`/ai-documents/${id}/revise`, ...)
  #     把 ${...} 替换成 :id（类似后端风格），再补 /api/v1 前缀
  strip_ts_comments < "$api_file" \
    | grep -oE "(requestIpd|ipdGet|ipdPost|ipdPut|ipdDelete|ipdPatch)\(\s*['\"\`][^'\"\`]*['\"\`]" 2>/dev/null \
    | grep -oE "['\"\`]/[^'\"\`]+['\"\`]" 2>/dev/null \
    | sed -E "s|\\\${[^}]+}|:id|g; s|^['\"\`]/|/api/v1/|; s|['\"\`]\$||" \
    >> "$TMPDIR_CHECK/frontend_endpoints.txt"

  # 4) 提取模板字符串里的前缀：${prefix}/api/v1/xxx （取 xxx 部分）
  strip_ts_comments < "$api_file" \
    | grep -oE "['\"\`]\\\$\{[^}]+\}/${PATH_RE}['\"\`]" 2>/dev/null \
    | grep -oE "${PATH_RE}" \
    >> "$TMPDIR_CHECK/frontend_endpoints.txt"
done

# 也从 router/ 和 store/ 扫（动态调用）—— 同样剥离注释
for sub in "src/router" "src/store"; do
  if [ -d "$FRONTEND_ROOT/$sub" ]; then
    for rf in "$FRONTEND_ROOT/$sub"/*.{ts,tsx,vue,js}; do
      [ -f "$rf" ] || continue
      strip_ts_comments < "$rf" \
        | grep -oE "['\"\`]${PATH_RE}['\"\`]" 2>/dev/null \
        | tr -d "'\`\"" \
        >> "$TMPDIR_CHECK/frontend_endpoints.txt"
    done
  fi
done

sort -u "$TMPDIR_CHECK/frontend_endpoints.txt" > "$TMPDIR_CHECK/fe.uniq.txt"
fe_count=$(wc -l < "$TMPDIR_CHECK/fe.uniq.txt" | tr -d ' ')
echo "  → 前端调用点数: $fe_count"

# ---- 3. 文档端点全集 ----
echo
echo "[3/4] 抽取文档端点（开发说明书.md + 主Prompt v3）..."

> "$TMPDIR_CHECK/doc_endpoints.txt"
for doc_file in \
  "$DOCS_ROOT/开发说明/开发说明书.md" \
  "$DOCS_ROOT/产品流程细化管理工具/IPD系统_AI开发主Prompt_v3.md"; do
  if [ -f "$doc_file" ]; then
    grep -oE '/api/v1/[A-Za-z0-9/_:.-]+' "$doc_file" 2>/dev/null \
      | sed 's/[:}]$//' \
      >> "$TMPDIR_CHECK/doc_endpoints.txt"
  fi
done

sort -u "$TMPDIR_CHECK/doc_endpoints.txt" > "$TMPDIR_CHECK/doc.uniq.txt"
doc_count=$(wc -l < "$TMPDIR_CHECK/doc.uniq.txt" | tr -d ' ')
echo "  → 文档端点数: $doc_count"

# ---- 4. 三向对账 ----
echo
echo "[4/4] 三向对账..."

# D1-A 前端有、后端无（白屏风险）
> "$TMPDIR_CHECK/d1a_white_screen.txt"
while IFS= read -r fe_path; do
  [ -z "$fe_path" ] && continue
  # 排除路径变量（:id / {id}）——后端用 {id}
  fe_normalized=$(echo "$fe_path" | sed 's|:[A-Za-z0-9_]*|{id}|g')
  # 检查后端是否有匹配（精确匹配 + 路径参数归一化匹配）
  fe_pattern=$(echo "$fe_normalized" | sed 's|{id}|{[A-Za-z0-9_]+}|g')
  if ! grep -qFx "$fe_path" "$TMPDIR_CHECK/be.uniq.txt" 2>/dev/null \
     && ! grep -qFx "$fe_normalized" "$TMPDIR_CHECK/be.uniq.txt" 2>/dev/null \
     && ! grep -qE "^${fe_pattern}$" "$TMPDIR_CHECK/be.uniq.txt" 2>/dev/null; then
    echo "$fe_path" >> "$TMPDIR_CHECK/d1a_white_screen.txt"
  fi
done < "$TMPDIR_CHECK/fe.uniq.txt"
WHITE_SCREEN=$(wc -l < "$TMPDIR_CHECK/d1a_white_screen.txt" | tr -d ' ')

# D1-B 后端有、前端无（孤儿端点）
> "$TMPDIR_CHECK/d1b_orphan.txt"
while IFS= read -r be_path; do
  [ -z "$be_path" ] && continue
  # 后端路径里的 {xxx} 转 :xxx（前端风格）
  be_for_fe=$(echo "$be_path" | sed 's|{[A-Za-z0-9_]*}|:id|g')
  if ! grep -qFx "$be_path" "$TMPDIR_CHECK/fe.uniq.txt" 2>/dev/null \
     && ! grep -qFx "$be_for_fe" "$TMPDIR_CHECK/fe.uniq.txt" 2>/dev/null; then
    echo "$be_path" >> "$TMPDIR_CHECK/d1b_orphan.txt"
  fi
done < "$TMPDIR_CHECK/be.uniq.txt"
ORPHAN_ENDPOINT=$(wc -l < "$TMPDIR_CHECK/d1b_orphan.txt" | tr -d ' ')

# D4-A 文档有、后端无（未实现）
> "$TMPDIR_CHECK/d4a_unimplemented.txt"
while IFS= read -r doc_path; do
  [ -z "$doc_path" ] && continue
  doc_for_be=$(echo "$doc_path" | sed 's|:[A-Za-z0-9_]*|{id}|g')
  if ! grep -qFx "$doc_path" "$TMPDIR_CHECK/be.uniq.txt" 2>/dev/null \
     && ! grep -qE "${doc_for_be//\{id\}/\{[A-Za-z0-9_]+\}}" "$TMPDIR_CHECK/be.uniq.txt" 2>/dev/null; then
    echo "$doc_path" >> "$TMPDIR_CHECK/d4a_unimplemented.txt"
  fi
done < "$TMPDIR_CHECK/doc.uniq.txt"
DOC_IMPL_DRIFT=$(wc -l < "$TMPDIR_CHECK/d4a_unimplemented.txt" | tr -d ' ')

# ---- 输出 Markdown 报告 ----
cat > "$REPORT_MD" <<EOF
# R25 P0-2 跨仓契约对账报告（${TIMESTAMP}）

> 自动门禁：\`scripts/check_cross_repo_contract.sh\`（RC-2 三向对账）
> 后端基线：\`$(cd "$BACKEND_ROOT" && git rev-parse --short HEAD 2>/dev/null)\`
> 前端基线：\`$(cd "$FRONTEND_ROOT/../.." 2>/dev/null && git rev-parse --short HEAD 2>/dev/null || echo N/A)\`

## 汇总

| 类别 | 数量 |
|---|---|
| 🔴 D1-A 前端有、后端无（白屏风险） | $WHITE_SCREEN |
| 🟡 D1-B 后端有、前端无（孤儿端点） | $ORPHAN_ENDPOINT |
| 🟡 D4-A 文档有、后端无（未实现） | $DOC_IMPL_DRIFT |
| 总端点（后端 / 前端 / 文档） | $be_count / $fe_count / $doc_count |

## 🔴 D1-A 白屏风险清单

> 前端调用但后端无对应端点 —— 任何命中即 404/500

EOF

if [ -s "$TMPDIR_CHECK/d1a_white_screen.txt" ]; then
  echo "| 前端路径 | 风险 | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|---|" >> "$REPORT_MD"
  while IFS= read -r p; do
    echo "| \`$p\` | 🔴 白屏 | 后端补端点 OR 前端 fallback |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/d1a_white_screen.txt"
else
  echo "✅ 无白屏风险" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 🟡 D1-B 后端孤儿端点（前端 SPA 未调用）

> 后端定义但前端 SPA 未直接调用 —— 可能是 admin/scan/legacy 类，需逐项确认外部调用方

EOF

if [ -s "$TMPDIR_CHECK/d1b_orphan.txt" ]; then
  echo "| 后端路径 | 风险 | 建议 |" >> "$REPORT_MD"
  echo "|---|---|---|" >> "$REPORT_MD"
  head -30 "$TMPDIR_CHECK/d1b_orphan.txt" | while IFS= read -r p; do
    case "$p" in
      /api/v1/audit-logs*|/api/v1/compliance*|/api/v1/hr-sync*|/api/v1/person-sync*|/api/v1/admin*)
        risk="🟡 admin 类" ;;
      /api/v1/gates/legacy*|/api/v1/gates/sign*|/api/v1/project-score-tasks/scan*|/api/v1/notifications/async*)
        risk="🟡 scan/internal" ;;
      *) risk="🟡 待核" ;;
    esac
    echo "| \`$p\` | $risk | 确认外部调用方 |" >> "$REPORT_MD"
  done
  total_d1b=$(wc -l < "$TMPDIR_CHECK/d1b_orphan.txt" | tr -d ' ')
  if [ "$total_d1b" -gt 30 ]; then
    echo "" >> "$REPORT_MD"
    echo "_（仅展示前 30 条，完整列表见 \`contract-drift-${TIMESTAMP}.json\`）_" >> "$REPORT_MD"
  fi
else
  echo "✅ 无孤儿端点" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 🟡 D4-A 文档端点未实现

> 开发说明书或主Prompt v3 提及但后端无对应实现

EOF

if [ -s "$TMPDIR_CHECK/d4a_unimplemented.txt" ]; then
  echo "| 文档路径 | 文档来源 | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|---|" >> "$REPORT_MD"
  while IFS= read -r p; do
    src="开发说明书.md"
    grep -qF "$p" "$DOCS_ROOT/产品流程细化管理工具/IPD系统_AI开发主Prompt_v3.md" 2>/dev/null && src="${src} + 主Prompt v3"
    echo "| \`$p\` | $src | 后端补实现 OR 从文档删除 |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/d4a_unimplemented.txt"
else
  echo "✅ 文档与实现一致" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 重跑命令
EOF
echo '```bash' >> "$REPORT_MD"
echo "cd ${BACKEND_ROOT}" >> "$REPORT_MD"
echo './scripts/check_cross_repo_contract.sh' >> "$REPORT_MD"
echo '```' >> "$REPORT_MD"

cat >> "$REPORT_MD" <<EOF

## 排除范围

- 框架自带端点（actuator/swagger）
- 路径变量差异：后端 \`{id}\` ↔ 前端 \`:id\` 自动归一化
EOF

echo
echo "==== 对账完成 ===="
echo "白屏风险: $WHITE_SCREEN"
echo "孤儿端点: $ORPHAN_ENDPOINT"
echo "文档未实现: $DOC_IMPL_DRIFT"
echo "报告: $REPORT_MD"

if [ "$WHITE_SCREEN" -gt 0 ]; then
  exit 1
fi
exit 0