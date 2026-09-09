#!/usr/bin/env bash
# check_doc_link.sh
# R25 P1-2 根因 RC-7 治理：文档死链检测
#
# 设计要点：
#   蜂群 C R25 共发现 13 处文档死链（含 v1 文件名残留 + 跨仓相对路径 broken + 文档间互引）
#   检测方式：
#     1. Markdown 内 `[text](path)` 形式抽取
#     2. 绝对路径 / 相对路径 / 跨仓路径 分别验证文件存在
#     3. 文档间相对引用（旧版本文档名 → 新版本文档名）
#
# 退出码:
#   0  = 无死链
#   1  = 发现死链
#   2  = 脚本错误

set -u

DOCS_ROOT="${DOCS_ROOT:-/Users/mac/Documents/ZK-IPD}"
BACKEND_ROOT="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}"
FRONTEND_ROOT="${FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web}"

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,28p' "$0" | sed 's/^# //;s/^#//'; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports"
mkdir -p "$OUTPUT_DIR"
REPORT_MD="${OUTPUT_DIR}/doc-link-${TIMESTAMP}.md"
REPORT_JSON="${OUTPUT_DIR}/doc-link-${TIMESTAMP}.json"
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

echo "==== R25 P1-2 文档死链检测（RC-7） ===="
echo "文档: $DOCS_ROOT"
echo "后端: $BACKEND_ROOT"
echo "前端: $FRONTEND_ROOT"
echo

# ---- 1. 抽取所有 markdown 链接 ----
echo "[1/4] 抽取 markdown 链接..."

> "$TMPDIR_CHECK/all_links.txt"
find "$DOCS_ROOT" "$BACKEND_ROOT/docs" -name '*.md' \
  -not -path '*/.claude/*' -not -path '*/node_modules/*' \
  2>/dev/null \
  | while IFS= read -r md; do
      [ -f "$md" ] || continue
      base=$(dirname "$md")
      # 形式 [text](path)
      grep -oE '\[[^]]*\]\([^)]+\)' "$md" 2>/dev/null \
        | grep -oE '\([^)]+\)' \
        | sed 's/^(//;s/)$//' \
        | grep -vE '^(https?|mailto:|ftp://|#)' \
        | while IFS= read -r link; do
            echo "${md}|${base}|${link}" >> "$TMPDIR_CHECK/all_links.txt"
          done
    done

link_n=$(wc -l < "$TMPDIR_CHECK/all_links.txt" | tr -d ' ')
echo "  → 链接数: $link_n"

# ---- 2. 验证本地路径 ----
echo
echo "[2/4] 验证本地路径..."

> "$TMPDIR_CHECK/dead_local.txt"
while IFS='|' read -r md base link; do
  [ -z "$link" ] && continue
  # 跳过 URL / 锚点
  case "$link" in
    http*|mailto*|ftp*|\#*) continue ;;
  esac
  # 解析路径：相对路径以 base 为锚
  if [[ "$link" = /* ]]; then
    target="$link"
  else
    target="$base/$link"
  fi
  # 去除查询字符串与锚点
  target="${target%%#*}"
  target="${target%%\?*}"
  # 文件系统是否可达
  if [ ! -e "$target" ] && [ ! -e "${target}.md" ] && [ ! -e "${target%/}.md" ]; then
    echo "${md#${DOCS_ROOT}/}|${link}|${target}" >> "$TMPDIR_CHECK/dead_local.txt"
  fi
done < "$TMPDIR_CHECK/all_links.txt"

dead_n=$(wc -l < "$TMPDIR_CHECK/dead_local.txt" | tr -d ' ')
echo "  → 本地死链: $dead_n"

# ---- 3. 检测 v1 / 旧版本文件名残留 ----
echo
echo "[3/4] 检测 v1/旧版本文件名残留..."

> "$TMPDIR_CHECK/v1_residual.txt"
# 形式：xxx_v1.md / xxx_old.md / xxx_legacy.md / xxx-副本.md
find "$DOCS_ROOT" "$BACKEND_ROOT/docs" -type f \
  \( -name '*_v1.md' -o -name '*_v1.pdf' -o -name '*_old.md' -o -name '*_legacy.md' \
     -o -name '开发说明_副本*' -o -name '*副本*' \) \
  -not -path '*/.claude/*' 2>/dev/null \
  | sed "s|^|${DOCS_ROOT}/|" \
  >> "$TMPDIR_CHECK/v1_residual.txt"

v1_n=$(wc -l < "$TMPDIR_CHECK/v1_residual.txt" | tr -d ' ')
echo "  → v1/旧版本残留: $v1_n"

# ---- 4. 报告 ----
echo
echo "[4/4] 生成报告..."

cat > "$REPORT_JSON" <<EOF
{
  "timestamp": "${TIMESTAMP}",
  "total_links": ${link_n},
  "dead_local_count": ${dead_n},
  "v1_residual_count": ${v1_n},
  "dead_local": $(awk -F'|' '{ printf "{\"from\":\"%s\",\"link\":\"%s\",\"target\":\"%s\"},", $1, $2, $3 }' "$TMPDIR_CHECK/dead_local.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]"),
  "v1_residual": $(awk '{ printf "\"%s\",", $0 }' "$TMPDIR_CHECK/v1_residual.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]")
}
EOF

cat > "$REPORT_MD" <<EOF
# R25 P1-2 文档死链检测报告（${TIMESTAMP}）

> 自动门禁：\`scripts/check_doc_link.sh\`（RC-7 死链检测）
> 文档基线：\`$(cd "$DOCS_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo "N/A(uncommitted)")\`

## 汇总

| 类别 | 数量 |
|---|---|
| 总链接数 | $link_n |
| 🔴 本地死链 | $dead_n |
| 🟡 v1/旧版本残留文件 | $v1_n |

## 🔴 本地死链

> 文档中引用的本地路径解析后文件不存在

EOF

if [ -s "$TMPDIR_CHECK/dead_local.txt" ]; then
  echo "| 来源文档 | 死链 | 解析后路径 |" >> "$REPORT_MD"
  echo "|---|---|---|" >> "$REPORT_MD"
  head -30 "$TMPDIR_CHECK/dead_local.txt" | while IFS='|' read -r from link target; do
    echo "| \`$from\` | \`$link\` | \`$target\` |" >> "$REPORT_MD"
  done
  total=$(wc -l < "$TMPDIR_CHECK/dead_local.txt" | tr -d ' ')
  if [ "$total" -gt 30 ]; then
    echo "" >> "$REPORT_MD"
    echo "_（仅展示前 30 条，完整列表见 \`doc-link-${TIMESTAMP}.json\`）_" >> "$REPORT_MD"
  fi
else
  echo "✅ 无本地死链" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 🟡 v1/旧版本残留文件

> 旧版本文件名未清理（开发说明书\_副本 / xxx\_v1 等），可能造成引用歧义

EOF

if [ -s "$TMPDIR_CHECK/v1_residual.txt" ]; then
  echo "| 文件 | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|" >> "$REPORT_MD"
  while IFS= read -r f; do
    echo "| \`$f\` | 确认是否仍在引用 → 否则归档 \`.archive/\` |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/v1_residual.txt"
else
  echo "✅ 无 v1/旧版本残留" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 重跑命令

\`\`\`bash
cd ${BACKEND_ROOT}
./scripts/check_doc_link.sh
\`\`\`

## 排除范围

- 外链（http/https/mailto）—— 仅本地路径校验
- 锚点引用（#section）—— 当前未解析
- 图片引用（.png/.jpg）—— 如有 broken 也会被检出
EOF

echo
echo "==== 检测完成 ===="
echo "🔴 本地死链: $dead_n"
echo "🟡 v1 残留: $v1_n"
echo "报告: $REPORT_MD"

if [ "$dead_n" -gt 0 ]; then
  exit 1
fi
exit 0
