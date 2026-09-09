#!/usr/bin/env bash
# check_i18n_unused_keys.sh
# R25 P1-3 根因 RC-8 治理：i18n 死键扫描
#
# 设计要点：
#   蜂群 B R25 共发现 89 个 i18n 死键（zh-CN.json 声明但代码未引用）
#   死键危害：翻译成本浪费 + 增加 PR diff 噪音 + 影响代码搜索准确度
#   检测方式：
#     1. 抽取所有 locales/*.json 文件
#     2. 收集全部 key
#     3. 在 src/**/*.{vue,ts,tsx} 中三向 grep（t('key') / $t('key') / i18nKey="key"）
#     4. 三向为零的 key 判定为死键
#
# 退出码:
#   0  = 无死键
#   1  = 发现死键
#   2  = 脚本错误

set -u

FRONTEND_ROOT="${FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd}"
MODE="scan"           # scan | delete
NAMESPACE_PREFIX=""   # 只删指定 namespace，如 component.upload
DRY_RUN=1             # 1=只打印，0=真删

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,30p' "$0" | sed 's/^# //;s/^#//'; exit 0 ;;
    --delete) MODE="delete"; DRY_RUN=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --namespace=*) NAMESPACE_PREFIX="${1#*=}"; shift ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}/docs/ipd-系统说明/lint-reports"
mkdir -p "$OUTPUT_DIR"
REPORT_MD="${OUTPUT_DIR}/i18n-unused-keys-${TIMESTAMP}.md"
REPORT_JSON="${OUTPUT_DIR}/i18n-unused-keys-${TIMESTAMP}.json"
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

echo "==== R25 P1-3 i18n 死键扫描（RC-8） ===="
echo "前端: $FRONTEND_ROOT"
echo

# ---- 1. 抽取所有 locales/*.json ----
echo "[1/4] 抽取 locales/*.json..."

> "$TMPDIR_CHECK/locales.txt"
find "$FRONTEND_ROOT/src/locales" -name '*.json' -not -path '*/node_modules/*' 2>/dev/null \
  | while IFS= read -r f; do
      base=$(basename "$f")
      echo "${f}|${base}" >> "$TMPDIR_CHECK/locales.txt"
    done

loc_n=$(wc -l < "$TMPDIR_CHECK/locales.txt" | tr -d ' ')
echo "  → 语言文件数: $loc_n"

# ---- 2. 收集全部 key（zh-CN 所有 json 文件为基准） ----
echo "[2/4] 收集 key（zh-CN 所有 json 文件为基准）..."

> "$TMPDIR_CHECK/all_keys.txt"
ZH_NS_COUNT=$(find "$FRONTEND_ROOT/src/locales" -path '*zh-CN*.json' 2>/dev/null | wc -l | tr -d ' \n')
ZH_NS_COUNT=${ZH_NS_COUNT:-0}
if [ "$ZH_NS_COUNT" -eq 0 ]; then
  echo "[i18n] ❌ 找不到 zh-CN json 文件" >&2
  exit 2
fi
echo "  → zh-CN namespace 文件数: $ZH_NS_COUNT"

while IFS= read -r ZH_FILE; do
  [ -f "$ZH_FILE" ] || continue
  ns=$(echo "$ZH_FILE" | sed -E 's|.*/zh-CN/||; s|\.json$||')
  if [ "$ns" = "http" ]; then
    jq -r 'paths(scalars) | join(".")' "$ZH_FILE" 2>/dev/null
  else
    jq -r --arg ns "$ns" 'paths(scalars) | [$ns] + . | join(".")' "$ZH_FILE" 2>/dev/null
  fi
done < <(find "$FRONTEND_ROOT/src/locales" -path '*zh-CN*.json' 2>/dev/null) \
  | sort -u > "$TMPDIR_CHECK/all_keys.txt"

key_n=$(wc -l < "$TMPDIR_CHECK/all_keys.txt" | tr -d ' \n')
key_n=${key_n:-0}
echo "  → key 总数（去重后）: $key_n"

# ---- 3. 三向 grep 引用 ----
echo
echo "[3/4] 三向 grep 引用（t('key') / \$t('key') / i18nKey=\"key\"）..."

# 收集所有可能的代码文件（含 .vue .ts .tsx .js .jsx）
ALL_SRC=$(find "$FRONTEND_ROOT/src" \
  -type f \( -name '*.vue' -o -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.jsx' \) \
  -not -path '*/node_modules/*' -not -path '*/.pnpm-store/*' \
  2>/dev/null)

# 一次性 dump 到一个临时文件，避免 xargs -n1 反复扫
cat $ALL_SRC > "$TMPDIR_CHECK/code.dump" 2>/dev/null
dump_size=$(wc -c < "$TMPDIR_CHECK/code.dump" | tr -d ' ')
echo "  → 源代码 dump 大小: ${dump_size} bytes"

> "$TMPDIR_CHECK/dead_keys.txt"
> "$TMPDIR_CHECK/live_keys.txt"

# 一次性构造 grep -F 友好的全部字面量，导出到 dump 后面（避免逐 key grep 调用）
# 简化策略：用 awk 逐行扫描 dump，为每个 key 检查出现次数
awk -v keys_file="$TMPDIR_CHECK/all_keys.txt" -v live="$TMPDIR_CHECK/live_keys.txt" -v dead="$TMPDIR_CHECK/dead_keys.txt" '
BEGIN {
  # 读入所有 key
  while ((getline k < keys_file) > 0) {
    if (k == "") continue
    n = split(k, parts, ".")
    # 转义点号为字面量
    esc = ""
    for (i = 1; i <= length(k); i++) {
      c = substr(k, i, 1)
      if (c == ".") esc = esc "\\."
      else if (c == "+") esc = esc "\\+"
      else if (c == "*") esc = esc "\\*"
      else if (c == "?") esc = esc "\\?"
      else if (c == "(") esc = esc "\\("
      else if (c == ")") esc = esc "\\)"
      else if (c == "[") esc = esc "\\["
      else if (c == "]") esc = esc "\\]"
      else if (c == "|") esc = esc "\\|"
      else if (c == "$") esc = esc "\\$"
      else if (c == "^") esc = esc "\\^"
      else esc = esc c
    }
    KEYS[++kc] = k
    PATTERNS[kc] = "['"'"'\"`]?" esc "['"'"'\"`]?|\\$\\{[^}]*" esc "[^}]*\\}"
  }
  print "" > "/dev/stderr"  # 静默
}
{
  line = $0
  # 去注释简化（不严格，仅去除行内 //）
  gsub(/\/\/.*$/, "", line)
  for (i = 1; i <= kc; i++) {
    if (match(line, PATTERNS[i])) {
      HIT[i]++
    }
  }
}
END {
  for (i = 1; i <= kc; i++) {
    if (HIT[i] > 0) print KEYS[i] > live
    else print KEYS[i] > dead
  }
}
' "$TMPDIR_CHECK/code.dump" 2>/dev/null

live_n=$(wc -l < "$TMPDIR_CHECK/live_keys.txt" 2>/dev/null | tr -d ' \n' || echo 0)
live_n=${live_n:-0}
dead_n=$(wc -l < "$TMPDIR_CHECK/dead_keys.txt" 2>/dev/null | tr -d ' \n' || echo 0)
dead_n=${dead_n:-0}
echo "  → 活键: $live_n / 死键: $dead_n"

# ---- 4. 报告 ----
echo
echo "[4/4] 生成报告..."

cat > "$REPORT_JSON" <<EOF
{
  "timestamp": "${TIMESTAMP}",
  "baseline_locale": "${ZH_FILE#${FRONTEND_ROOT}/}",
  "total_keys": ${key_n},
  "live_keys": ${live_n},
  "dead_keys": ${dead_n},
  "dead_keys_list": $(awk '{ printf "\"%s\",", $0 }' "$TMPDIR_CHECK/dead_keys.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]")
}
EOF

cat > "$REPORT_MD" <<EOF
# R25 P1-3 i18n 死键扫描报告（${TIMESTAMP}）

> 自动门禁：\`scripts/check_i18n_unused_keys.sh\`（RC-8 死键扫描）
> 基准：\`${ZH_FILE#${FRONTEND_ROOT}/}\`
> 前端基线：\`$(cd "$FRONTEND_ROOT/../.." 2>/dev/null && git rev-parse --short HEAD 2>/dev/null || echo "N/A")\`

## 汇总

| 类别 | 数量 |
|---|---|
| 总 key | $key_n |
| 🟢 活键（有引用） | $live_n |
| 🔴 死键（无引用） | $dead_n |

## 🔴 死键清单

> zh-CN.json 声明但代码未引用 —— 删除前必须二次复核（动态拼接 key 可能漏抓）

EOF

if [ -s "$TMPDIR_CHECK/dead_keys.txt" ]; then
  echo "| # | key |" >> "$REPORT_MD"
  echo "|---|---|" >> "$REPORT_MD"
  line=0
  while IFS= read -r k; do
    line=$((line + 1))
    echo "$line | \`$k\` |" >> "$REPORT_MD"
    [ "$line" -ge 100 ] && break
  done < "$TMPDIR_CHECK/dead_keys.txt"
  total=$(wc -l < "$TMPDIR_CHECK/dead_keys.txt" | tr -d ' ')
  if [ "$total" -gt 100 ]; then
    echo "" >> "$REPORT_MD"
    echo "_（仅展示前 100 条，完整列表见 \`i18n-unused-keys-${TIMESTAMP}.json\`）_" >> "$REPORT_MD"
  fi
else
  echo "✅ 无死键" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 重跑命令

\`\`\`bash
cd ${FRONTEND_ROOT}/../..
./scripts/check_i18n_unused_keys.sh
\`\`\`

## CI 接入

\`\`\`yaml
# .github/workflows/i18n-unused.yml
- name: i18n 死键扫描
  run: ./scripts/check_i18n_unused_keys.sh
\`\`\`

新加 key 但未引用 → PR 自动评论 + 阻断合入（待配置）

## 排除范围

- 动态拼接 key（如 \`t(\\\`menu.\\\${name}\\\`)\`）—— 当前正则按字面量匹配，会误判为死键
- 组件库 keys（ant-design-vue 内置）—— 不在 zh-CN.json 中
- 注释内的 key 引用
EOF

echo
echo "==== 扫描完成 ===="
echo "🔴 死键: $dead_n"
echo "报告: $REPORT_MD"

if [ "$dead_n" -gt 0 ]; then
  if [ "$MODE" = "delete" ]; then
    echo ""
    echo "==== 删除模式（--delete）触发 ===="
    if [ -n "$NAMESPACE_PREFIX" ]; then
      echo "范围限定: namespace 前缀 = '$NAMESPACE_PREFIX'"
    else
      echo "范围: 全部死键（78 项）"
    fi
    if [ "$DRY_RUN" = "1" ]; then
      echo "DRY-RUN：未真删，加 --delete 才真执行"
      exit 0
    fi

    # 真删：按 zh-CN json 文件逐个处理
    delete_count=0
    skip_count=0
    while IFS= read -r ZH_FILE; do
      [ -f "$ZH_FILE" ] || continue
      ns=$(echo "$ZH_FILE" | sed -E 's|.*/zh-CN/||; s|\.json$||')
      while IFS= read -r KEY; do
        [ -z "$KEY" ] && continue
        if [ -n "$NAMESPACE_PREFIX" ] && [[ "$KEY" != "${NAMESPACE_PREFIX}"* ]]; then
          skip_count=$((skip_count + 1))
          continue
        fi
        # ns 不为空且 KEY 不以 ns. 开头则跳过（http 例外）
        if [ "$ns" != "http" ] && [[ "$KEY" != "${ns}."* ]]; then
          skip_count=$((skip_count + 1))
          continue
        fi
        rel_path=${KEY#${ns}.}
        if [ "$ns" = "http" ]; then
          rel_path=$KEY
        fi
        # jq 检查路径是否存在（精确匹配 any(paths; join(".") == rel_path)，-r 输出 raw 字符串避免 JSON 引号）
        if jq -er --arg p "$rel_path" 'any(paths; join(".") == $p)' "$ZH_FILE" >/dev/null 2>&1; then
          # 用 delpaths 删除（按 split(".") 转 path 数组，避免点号转义问题）
          jq --arg p "$rel_path" 'delpaths([$p | split(".")])' "$ZH_FILE" > "${ZH_FILE}.tmp" && mv "${ZH_FILE}.tmp" "$ZH_FILE"
          echo "  🗑  $ns.$rel_path"
          delete_count=$((delete_count + 1))
        else
          echo "  ⚠️ 跳过（路径不存在）: $ns.$rel_path"
          skip_count=$((skip_count + 1))
        fi
      done < "$TMPDIR_CHECK/dead_keys.txt"
    done < <(find "$FRONTEND_ROOT/src/locales" -path '*zh-CN*.json' 2>/dev/null)

    echo ""
    echo "✅ 删除完成: $delete_count 个 / 跳过: $skip_count 个"
    exit 0
  fi
  exit 1
fi
exit 0
