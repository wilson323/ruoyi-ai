#!/usr/bin/env bash
# check_duplicate_ssot.sh
# R25 P1-1 根因 RC-6 治理：重复 SSOT 检测
#
# 设计要点：
#   "Single Source of Truth" 是 G-04 第一原则，但实践中同一业务概念经常在多处重复定义：
#     - 后端 BusinessConstants / IpdConstants
#     - 前端 constants/ipd/*.ts
#     - 文档（开发说明书 + 主 Prompt v3）
#   任何一处更新，其他几处都不同步 → 必然导致 R25 死代码 + 字段漂移
#
# 检测方式：
#   1. 抽取后端常量定义（public static final String X = "value"）
#   2. 抽取前端常量定义（export const X = 'value'）
#   3. 抽取文档常量定义（"X": "value" 形式 + 表格列名）
#   4. 按值（value）分组，找出现 ≥ 2 次的 → 重复 SSOT
#
# 退出码:
#   0  = 无重复 SSOT（或值已对齐）
#   1  = 发现值不一致的重复定义
#   2  = 脚本错误

set -u

BACKEND_ROOT="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}"
FRONTEND_ROOT="${FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd}"
DOCS_ROOT="${DOCS_ROOT:-/Users/mac/Documents/ZK-IPD}"

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,30p' "$0" | sed 's/^# //;s/^#//'; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports"
mkdir -p "$OUTPUT_DIR"
REPORT_MD="${OUTPUT_DIR}/duplicate-ssot-${TIMESTAMP}.md"
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

echo "==== R25 P1-1 重复 SSOT 检测（RC-6） ===="
echo "后端: $BACKEND_ROOT"
echo "前端: $FRONTEND_ROOT"
echo "文档: $DOCS_ROOT"
echo

# ---- 1. 后端常量 ----
echo "[1/4] 抽取后端常量（public static final String X = \"value\"）..."

> "$TMPDIR_CHECK/be_constants.txt"
find "$BACKEND_ROOT/ruoyi-modules/ruoyi-ipd/src/main/java" \
     "$BACKEND_ROOT/ruoyi-common/ruoyi-common-ipd/src/main/java" \
     -name '*.java' -not -path '*/target/*' -not -path '*/.claude/worktrees/*' \
  2>/dev/null \
  | while IFS= read -r jf; do
      [ -f "$jf" ] || continue
      # 匹配 public static final String X = "value";
      grep -oE 'public[[:space:]]+static[[:space:]]+final[[:space:]]+String[[:space:]]+[A-Z][A-Za-z0-9_]+[[:space:]]*=[[:space:]]*"[^"]*"' "$jf" 2>/dev/null \
        | sed -E 's,.*[[:space:]]+([A-Z][A-Za-z0-9_]+)[[:space:]]*=[[:space:]]*"([^"]*)".*,BE|\1|\2|'"${jf#${BACKEND_ROOT}/}"',' \
        >> "$TMPDIR_CHECK/be_constants.txt"
    done

be_n=$(wc -l < "$TMPDIR_CHECK/be_constants.txt" | tr -d ' ')
echo "  → 后端常量数: $be_n"

# ---- 2. 前端常量 ----
echo
echo "[2/4] 抽取前端常量（export const X = 'value'）..."

> "$TMPDIR_CHECK/fe_constants.txt"
find "$FRONTEND_ROOT/src" \
     -name '*.ts' -not -path '*/node_modules/*' -not -path '*/.pnpm-store/*' \
  2>/dev/null \
  | while IFS= read -r ts; do
      [ -f "$ts" ] || continue
      # 匹配 export const X = 'value' 或 export const X: string = 'value'
      grep -oE "export[[:space:]]+const[[:space:]]+[A-Z][A-Za-z0-9_]+[[:space:]]*[:=]" "$ts" 2>/dev/null \
        | sed -E 's,export[[:space:]]+const[[:space:]]+([A-Z][A-Za-z0-9_]+).*,FE|\1|TODO|'"${ts#${FRONTEND_ROOT}/}"',' \
        >> "$TMPDIR_CHECK/fe_constants.txt"
    done

fe_n=$(wc -l < "$TMPDIR_CHECK/fe_constants.txt" | tr -d ' ')
echo "  → 前端常量数: $fe_n"

# ---- 3. 文档常量 ----
echo
echo "[3/4] 抽取文档常量（开发说明书表格 + 主Prompt）..."

> "$TMPDIR_CHECK/doc_constants.txt"
for doc in "$DOCS_ROOT/开发说明/开发说明书.md" \
           "$DOCS_ROOT/产品流程细化管理工具/IPD系统_AI开发主Prompt_v3.md"; do
  if [ -f "$doc" ]; then
    # 表格形式：| KEY | VALUE |
    grep -oE '\|[[:space:]]*[A-Z][A-Z0-9_]+[[:space:]]*\|[[:space:]]*[a-zA-Z0-9_-]+[[:space:]]*\|' "$doc" 2>/dev/null \
      | sed -E 's,\|[[:space:]]*([A-Z][A-Z0-9_]+)[[:space:]]*\|[[:space:]]*([a-zA-Z0-9_-]+).*,DOC|\1|\2|'"${doc#${DOCS_ROOT}/}"',' \
      >> "$TMPDIR_CHECK/doc_constants.txt"
  fi
done

doc_n=$(wc -l < "$TMPDIR_CHECK/doc_constants.txt" | tr -d ' ')
echo "  → 文档常量数: $doc_n"

# ---- 4. 同名跨仓比对 ----
echo
echo "[4/4] 同名跨仓比对..."

> "$TMPDIR_CHECK/dup_constant.txt"
# 抽所有常量名（去掉 |SOURCE|NAME|VALUE|PATH| 格式中的 NAME 字段）
awk -F'|' 'NF>=5 { print $2 }' "$TMPDIR_CHECK/be_constants.txt" "$TMPDIR_CHECK/fe_constants.txt" "$TMPDIR_CHECK/doc_constants.txt" 2>/dev/null \
  | sort | uniq -c | awk '$1 >= 2 { gsub(/^[[:space:]]+[0-9]+[[:space:]]+/, ""); print }' \
  > "$TMPDIR_CHECK/dup_names.txt"

while IFS= read -r name; do
  [ -z "$name" ] && continue
  # 抽取同名常量的所有值
  values=$(awk -F'|' -v n="$name" '$2 == n { print $3 " (" $1 ")" }' "$TMPDIR_CHECK/be_constants.txt" "$TMPDIR_CHECK/fe_constants.txt" "$TMPDIR_CHECK/doc_constants.txt" 2>/dev/null | sort -u)
  vcount=$(echo "$values" | wc -l | tr -d ' ')
  # 唯一值数 == 出现次数 表示一致；>1 表示不一致
  occurrences=$(grep -c "|${name}|" "$TMPDIR_CHECK/be_constants.txt" "$TMPDIR_CHECK/fe_constants.txt" "$TMPDIR_CHECK/doc_constants.txt" 2>/dev/null | awk -F: '{ s += $2 } END { print s }')
  if [ "$vcount" -gt 1 ]; then
    echo "${name}|不一致|${values//$'\n'/; }" >> "$TMPDIR_CHECK/dup_constant.txt"
  elif [ "$occurrences" -ge 2 ]; then
    echo "${name}|一致|${values//$'\n'/; }" >> "$TMPDIR_CHECK/dup_constant.txt"
  fi
done < "$TMPDIR_CHECK/dup_names.txt"

dup_inconsistent=$(grep -c "|不一致|" "$TMPDIR_CHECK/dup_constant.txt" 2>/dev/null | tr -d ' ')
dup_consistent=$(grep -c "|一致|" "$TMPDIR_CHECK/dup_constant.txt" 2>/dev/null | tr -d ' ')

echo "  → 同名常量: 一致 $dup_consistent / 不一致 $dup_inconsistent"

# ---- 5. 文档副本 md5 检测（R25 蜂群 C 发现：开发说明_副本/ 与 开发说明/ 100% 重复） ----
echo
echo "[5/5] 文档副本 md5 检测..."

> "$TMPDIR_CHECK/dup_files.txt"
if [ -d "$DOCS_ROOT/开发说明" ] && [ -d "$DOCS_ROOT/开发说明_副本" ]; then
  for f in "$DOCS_ROOT/开发说明"/*; do
    base=$(basename "$f")
    [ -f "$f" ] || continue
    if [ -f "$DOCS_ROOT/开发说明_副本/$base" ]; then
      m1=$(md5 -q "$f")
      m2=$(md5 -q "$DOCS_ROOT/开发说明_副本/$base")
      if [ "$m1" = "$m2" ]; then
        echo "SAME|$base|${f#$DOCS_ROOT/}|开发说明_副本/$base" >> "$TMPDIR_CHECK/dup_files.txt"
      else
        echo "DIFF|$base|${f#$DOCS_ROOT/}|开发说明_副本/$base" >> "$TMPDIR_CHECK/dup_files.txt"
      fi
    fi
  done
fi

dup_same=$(grep -c "^SAME|" "$TMPDIR_CHECK/dup_files.txt" 2>/dev/null | tr -d ' ')
dup_diff=$(grep -c "^DIFF|" "$TMPDIR_CHECK/dup_files.txt" 2>/dev/null | tr -d ' ')
dup_same=${dup_same:-0}
dup_diff=${dup_diff:-0}
echo "  → 副本文件: md5 相同 $dup_same / 内容漂移 $dup_diff"

# ---- 输出报告 ----
cat > "$REPORT_MD" <<EOF
# R25 P1-1 重复 SSOT 检测报告（${TIMESTAMP}）

> 自动门禁：\`scripts/check_duplicate_ssot.sh\`（RC-6 重复定义检测）
> 后端基线：\`$(cd "$BACKEND_ROOT" && git rev-parse --short HEAD 2>/dev/null)\`

## 汇总

| 类别 | 数量 |
|---|---|
| 后端常量 | $be_n |
| 前端常量 | $fe_n |
| 文档常量 | $doc_n |
| 🔴 同名但值不一致（必须修） | $dup_inconsistent |
| 🟡 同名值一致（待清理单点源） | $dup_consistent |
| 📄 文档副本 md5 相同（待清理） | $dup_same |
| 📄 文档副本内容漂移（需人工对齐） | $dup_diff |

## 🔴 同名但值不一致（最严重）

> 同一常量名在三仓中定义但值不同 —— 必然导致运行时行为漂移

EOF

if [ -s "$TMPDIR_CHECK/dup_constant.txt" ] && [ "$dup_inconsistent" -gt 0 ]; then
  echo "| 常量名 | 风险 | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|---|" >> "$REPORT_MD"
  grep "|不一致|" "$TMPDIR_CHECK/dup_constant.txt" | head -30 | while IFS='|' read -r name risk vals; do
    echo "| \`$name\` | 🔴 值不一致 | 选单一来源，删其他 |" >> "$REPORT_MD"
  done
else
  echo "✅ 无值不一致的重复常量" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 🟡 同名值一致（可清理）

> 三仓有同名常量但值一致 —— 安全但冗余，建议清理单点源

EOF

if [ -s "$TMPDIR_CHECK/dup_constant.txt" ] && [ "$dup_consistent" -gt 0 ]; then
  echo "| 常量名 | 现状 |" >> "$REPORT_MD"
  echo "|---|---|" >> "$REPORT_MD"
  grep "|一致|" "$TMPDIR_CHECK/dup_constant.txt" | head -30 | while IFS='|' read -r name risk vals; do
    echo "| \`$name\` | 三仓一致但重复定义 |" >> "$REPORT_MD"
  done
else
  echo "✅ 无冗余常量" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 📄 文档副本检测（开发说明/ vs 开发说明_副本/）

EOF

if [ "$dup_same" -gt 0 ]; then
  echo "| 状态 | 文件 | 主路径 | 副本路径 | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|---|---|---|" >> "$REPORT_MD"
  grep "^SAME|" "$TMPDIR_CHECK/dup_files.txt" | head -20 | while IFS='|' read -r st base main copy; do
    echo "| 🟡 md5 相同 | \`$base\` | $main | $copy | 删副本（保留主路径） |" >> "$REPORT_MD"
  done
fi
if [ "$dup_diff" -gt 0 ]; then
  grep "^DIFF|" "$TMPDIR_CHECK/dup_files.txt" | head -20 | while IFS='|' read -r st base main copy; do
    echo "| 🔴 内容漂移 | \`$base\` | $main | $copy | 人工比对后合入主路径再删副本 |" >> "$REPORT_MD"
  done
fi
if [ "$dup_same" -eq 0 ] && [ "$dup_diff" -eq 0 ]; then
  echo "✅ 无文档副本重复" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 重跑命令

\`\`\`bash
cd ${BACKEND_ROOT}
./scripts/check_duplicate_ssot.sh
\`\`\`

## 排除范围

- framework 自带常量（org.ruoyi.common.core.constants.*）
- 数字常量（int/long/double 等）—— 当前仅匹配 String
- 文档表格中的"列名"误识别为常量 —— 需 owner 复核
EOF

echo
echo "==== 检测完成 ===="
echo "🔴 不一致: $dup_inconsistent"
echo "🟡 一致但冗余: $dup_consistent"
echo "📄 文档副本 md5 相同: $dup_same / 内容漂移: $dup_diff"
echo "报告: $REPORT_MD"

if [ "$dup_inconsistent" -gt 0 ]; then
  exit 1
fi
exit 0
