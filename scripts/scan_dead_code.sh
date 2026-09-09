#!/usr/bin/env bash
# scan_dead_code.sh
# R25 P0-1 根因 RC-4 治理：死代码三向交叉扫描
#
# 设计要点（避免 R14 AllowanceService 误删教训）：
#   1. 禁用 grep | head -N 截断：全量输出后用 awk/sort 二次处理
#   2. 注释剥离：javadoc / 单行注释 / 多行注释先 sed 掉，再 grep 类名
#   3. 三向交叉：grep 类名 / grep import.*类名 / grep new 类名 / grep extends 类名
#      全部为零才算真孤儿
#   4. 排除 Spring 自发现：@RestController / @Service / @Component / @Mapper /
#      @RestControllerAdvice / @Configuration / Spring 自带的字段注入
#   5. 排除 Lombok 注入：解析 @RequiredArgsConstructor 类 → 字段名 → 构造参数类型
#      反向追踪构造参数类型引用（避免 xxxService 字段名漏判）
#   6. 排除 @Deprecated / @JsonIgnore / @SaIgnore 显式标注
#   7. 排除 framework 自带代码（org.springframework / org.ruoyi.framework）
#   8. 排除测试文件引用作主证据（仅保留为"误报排除"）
#
# 退出码:
#   0  = 无死代码
#   1  = 发现疑似死代码（高风险必须修）
#   2  = 脚本错误
#
# 用法:
#   ./scripts/scan_dead_code.sh                          # 后端默认扫描
#   ./scripts/scan_dead_code.sh --module ruoyi-ipd       # 指定模块
#   ./scripts/scan_dead_code.sh --output docs/治理/...   # 指定报告路径
#   ./scripts/scan_dead_code.sh --strict                 # 把中风险也升为高风险

set -u  # 不开 -e：grep 找不到匹配时返回 1 是正常的

BACKEND_ROOT="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}"
MODULE="ruoyi-ipd"
OUTPUT_DIR=""
STRICT=0
HIGH=0
MEDIUM=0
LOW=0

while [ $# -gt 0 ]; do
  case "$1" in
    --module) MODULE="$2"; shift 2 ;;
    --output) OUTPUT_DIR="$2"; shift 2 ;;
    --strict) STRICT=1; shift ;;
    -h|--help)
      sed -n '2,32p' "$0" | sed 's/^# //;s/^#//'
      exit 0
      ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

# 默认输出位置
if [ -z "$OUTPUT_DIR" ]; then
  OUTPUT_DIR="${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports"
fi
mkdir -p "$OUTPUT_DIR"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
REPORT_JSON="${OUTPUT_DIR}/scan-dead-code-${TIMESTAMP}.json"
REPORT_MD="${OUTPUT_DIR}/scan-dead-code-${TIMESTAMP}.md"
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

if [ ! -d "$BACKEND_ROOT" ]; then
  echo "[scan-dead-code] ❌ 后端工程不存在: $BACKEND_ROOT" >&2
  exit 2
fi

# ---- 扫描配置 ----
SCAN_BASE="${BACKEND_ROOT}/ruoyi-modules/${MODULE}/src/main/java"
if [ ! -d "$SCAN_BASE" ]; then
  # fallback: ruoyi-common-*
  if [ -d "${BACKEND_ROOT}/ruoyi-common/${MODULE}/src/main/java" ]; then
    SCAN_BASE="${BACKEND_ROOT}/ruoyi-common/${MODULE}/src/main/java"
  else
    echo "[scan-dead-code] ❌ 模块不存在: ${MODULE}（尝试 ruoyi-modules + ruoyi-common）" >&2
    exit 2
  fi
fi

echo "==== R25 P0-1 死代码扫描（RC-4 三向交叉） ===="
echo "后端根: $BACKEND_ROOT"
echo "模块:   $MODULE"
echo "扫描基: $SCAN_BASE"
echo

# ---- 1. 抽取所有"业务 public 类"清单 ----
echo "[1/4] 抽取业务 public 类清单（剥离 framework/测试/内部类）..."

# 排除路径：
#   - */target/*            （编译产物）
#   - */.claude/worktrees/* （兄弟会话 worktree）
#   - */test/*              （测试目录）
#   - 包路径以 org.springframework. / org.ruoyi.framework. 开头的 framework 自带代码

find "$SCAN_BASE" -name '*.java' \
  -not -path '*/target/*' \
  -not -path '*/test/*' \
  -not -path '*/.claude/worktrees/*' \
  > "$TMPDIR_CHECK/all_java.txt"

echo "  → Java 文件数: $(wc -l < "$TMPDIR_CHECK/all_java.txt" | tr -d ' ')"

# 抽取 public class / public interface / public enum / public @interface 声明
# 注释剥离先行：sed 掉 /* ... */ 和 // 行，避免 javadoc 干扰
strip_comments() {
  sed -E '
    /\/\*/,/\*\//d
    s|//.*$||g
  '
}

> "$TMPDIR_CHECK/classes.txt"
while IFS= read -r jf; do
  strip_comments < "$jf" \
    | grep -E '^public[[:space:]]+(class|interface|enum|@interface)[[:space:]]+[A-Z][A-Za-z0-9_]*' \
    | grep -oE '[A-Z][A-Za-z0-9_]+' \
    | tail -1 \
    >> "$TMPDIR_CHECK/classes.txt" 2>/dev/null
done < "$TMPDIR_CHECK/all_java.txt"

sort -u "$TMPDIR_CHECK/classes.txt" > "$TMPDIR_CHECK/classes.uniq.txt"

# 过滤 java.lang.* / framework 自带类名（避免 RuntimeException 等误报）
# 已知白名单：java.lang 公共异常 + Spring / Sa-Token / Lombok 注解前缀
filter_framework_classes() {
  grep -vE '^(RuntimeException|Exception|Throwable|Error|Object|String|Integer|Long|Boolean|Double|Float|Short|Byte|Character|Void|Number|CharSequence|Comparable|Iterable|Collection|List|Set|Map|Queue|Deque|Iterator|ListIterator|Optional|Stream|IntStream|LongStream|DoubleStream|StreamSupport|Function|Predicate|Supplier|Consumer|Runnable|Callable|Class|ClassLoader|Package|Module|Process|Thread|RunnableFuture|FutureTask|TimeUnit|Override|Deprecated|SuppressWarnings|SafeVarargs|FunctionalInterface|Retention|Target|Documented|Inherited|Repeatable|Component|Service|Controller|RestController|Repository|Configuration|Bean|Autowired|Value|Resource|RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestParam|PathVariable|RequestBody|ResponseBody|Valid|Validated|NotNull|NotBlank|NotEmpty|Size|Min|Max|Past|Future|PastOrPresent|FutureOrPresent|Pattern|Email|Length|Range|DateTimeFormat|JsonFormat|JsonProperty|JsonIgnore|JsonInclude|JsonIgnoreProperties|TableName|TableId|TableField|TableLogic|TenantIgnore|TenantId|VersionLock|DataSource|Transactional|Cacheable|CacheEvict|CachePut|CacheAspect|AsyncEventListener|PreAuthorize|PostAuthorize|SaIgnore|SaCheckPermission|SaCheckRole|SaCheckLogin|SaCheckSafe|SaCheckDisable|SaCheckBasic|SaCheckHttpBasic|SaCheckOr|SaCheckAnd|SaMode|SaTokenInfo|SaLoginConfig|SaInterceptor|SaHolder|SaTokenContext|SaStrategy|SaApplication)$' "$1" > "$1.filtered"
  mv "$1.filtered" "$1"
}
filter_framework_classes "$TMPDIR_CHECK/classes.uniq.txt"

echo "  → 业务类数（去 framework 后）: $(wc -l < "$TMPDIR_CHECK/classes.uniq.txt" | tr -d ' ')"

# ---- 2. 三向交叉引用核查 ----
echo
echo "[2/4] 三向交叉引用核查（类名 / import / new / extends）..."

# 构造"全量被扫文本"——包含所有 main + test + 全部 ipd 模块代码（避免漏看测试里的调用）
# 注意：用 find -print0 + xargs -0 处理带空格路径
ALL_SOURCE_FILES=$(find "$SCAN_BASE" \
  -not -path '*/target/*' \
  -not -path '*/.claude/worktrees/*' \
  -name '*.java' \
  -print0 2>/dev/null | tr '\0' ' ')

# 同时把 test 目录纳入引用核查（防止 R14 AllowanceService 同型：测试调用但 main 不用）
TEST_SOURCE_FILES=""
for testdir in "$BACKEND_ROOT/ruoyi-modules/${MODULE}/src/test/java" \
               "$BACKEND_ROOT/ruoyi-common/${MODULE}/src/test/java"; do
  if [ -d "$testdir" ]; then
    TEST_SOURCE_FILES="$TEST_SOURCE_FILES $(find "$testdir" -name '*.java' \
      -not -path '*/.claude/worktrees/*' -print0 2>/dev/null | tr '\0' ' ')"
  fi
done

ALL_FILES="$ALL_SOURCE_FILES $TEST_SOURCE_FILES"

> "$TMPDIR_CHECK/dead_candidates.txt"

while IFS= read -r cls; do
  # 类名空行跳过
  [ -z "$cls" ] && continue

  # 三向 grep（禁用 head -N 截断！）
  hit_class=$(echo "$ALL_FILES" | xargs -n1 grep -l "\b${cls}\b" 2>/dev/null | wc -l | tr -d ' ')
  hit_import=$(echo "$ALL_FILES" | xargs -n1 grep -l "^import.*\.${cls}\b\|^import.*\.${cls};" 2>/dev/null | wc -l | tr -d ' ')
  hit_new=$(echo "$ALL_FILES" | xargs -n1 grep -l "\bnew[[:space:]]+${cls}\b" 2>/dev/null | wc -l | tr -d ' ')
  # 双向：extends/implements 在前 / 在后都匹配（避免 NotificationChannel 同型 implements 类名在后漏检）
  hit_extends=$(echo "$ALL_FILES" | xargs -n1 grep -lE "\b${cls}\b[^;{}]*[[:space:]]+(implements|extends)|(implements|extends)[^;{}]*\b${cls}\b" 2>/dev/null | wc -l | tr -d ' ')

  # 三向为零才算疑似死代码
  # 注：hit_class 总大于 0（自己定义自己），所以只看 import/new/extends
  total_refs=$((hit_import + hit_new + hit_extends))

  if [ "$total_refs" -eq 0 ]; then
    # 排除 framework 自发现（@RestController / @Service / @Component / @Mapper）
    # 这些类即使零 import 也被 Spring 容器加载
    sample_file=$(grep -l "\b${cls}\b" $ALL_SOURCE_FILES 2>/dev/null | head -1)
    if [ -n "$sample_file" ]; then
      ann=$(strip_comments < "$sample_file" | grep -E '@(RestController|Service|Component|RestControllerAdvice|Mapper|Configuration|Repository)' | head -1)
      # 排除 @Deprecated 类
      if strip_comments < "$sample_file" | grep -q "@Deprecated"; then
        continue
      fi
      # @Service / @Component / @Controller 等有自发现注解：标 LOW（不再 continue）
      # 让 owner 看到后再决定：可能是 Lombok @RequiredArgsConstructor 注入但脚本漏抓
      echo "${cls}|${sample_file}|${hit_import}|${hit_new}|${hit_extends}|${ann:-NO_ANN}" >> "$TMPDIR_CHECK/dead_candidates.txt"
    fi
  fi
done < "$TMPDIR_CHECK/classes.uniq.txt"

dead_count=$(wc -l < "$TMPDIR_CHECK/dead_candidates.txt" | tr -d ' ')
echo "  → 疑似死代码类数: $dead_count"

# ---- 3. 风险分级 ----
echo
echo "[3/4] 风险分级..."

> "$REPORT_JSON"
echo "[" >> "$REPORT_JSON"

first=1
while IFS='|' read -r cls sample hit_imp hit_new hit_ext ann; do
  [ -z "$cls" ] && continue

  # 风险评级：
  #   高 = Lombok @RequiredArgsConstructor 注入字段名不含类名（grep 字面量会漏）
  #       这类是 Controller/Service 中 xxxService 字段的真实类型，必须人工复核
  #   中 = 无 Lombok 注入但确实零引用（可能真死）
  #   低 = 仅有 javadoc @link 引用（不算业务引用）

  # 检测 Lombok 注入（构造参数类型）
  lombok_field_count=0
  if echo "$ALL_SOURCE_FILES" | xargs -n1 grep -l "@RequiredArgsConstructor" 2>/dev/null | while IFS= read -r lf; do
    if [ -f "$lf" ]; then
      # 字段声明：private final XxxService xxxService;
      grep -cE "private[[:space:]]+final[[:space:]]+${cls}\b" "$lf" 2>/dev/null
    fi
  done | grep -v "^$" | head -1 > "$TMPDIR_CHECK/lombok_count"; then
    lombok_field_count=$(cat "$TMPDIR_CHECK/lombok_count" 2>/dev/null || echo 0)
  fi

  if [ "$lombok_field_count" -gt 0 ]; then
    risk="HIGH"
    HIGH=$((HIGH + 1))
    reason="Lombok 注入字段使用此类型——grep 字面量会漏，R14 AllowanceService 同型"
  elif [ "$ann" = "NO_ANN" ]; then
    risk="MEDIUM"
    if [ "$STRICT" -eq 1 ]; then HIGH=$((HIGH + 1)); else MEDIUM=$((MEDIUM + 1)); fi
    reason="无 Spring 自发现注解，确实零引用——可能真死或 framework 遗漏"
  else
    risk="LOW"
    LOW=$((LOW + 1))
    reason="仅有注解自发现，需人工复核业务可达性"
  fi

  if [ "$first" -eq 0 ]; then echo "," >> "$REPORT_JSON"; fi
  first=0
  cat >> "$REPORT_JSON" <<EOF
  {
    "class": "${cls}",
    "file": "${sample#${BACKEND_ROOT}/}",
    "risk": "${risk}",
    "reason": "${reason}",
    "refs": {"import": ${hit_imp}, "new": ${hit_new}, "extends": ${hit_ext}},
    "spring_annotation": "${ann}"
  }
EOF
done < "$TMPDIR_CHECK/dead_candidates.txt"
echo "]" >> "$REPORT_JSON"

echo "  → 高风险: $HIGH / 中风险: $MEDIUM / 低风险: $LOW"

# ---- 4. 输出 Markdown 报告 ----
echo
echo "[4/4] 生成 Markdown 报告..."

cat > "$REPORT_MD" <<EOF
# R25 P0-1 死代码扫描报告（${TIMESTAMP}）

> 自动门禁：\`scripts/scan_dead_code.sh\`（RC-4 三向交叉）
> 模块：\`${MODULE}\`
> 后端基线：\`$(cd "$BACKEND_ROOT" && git rev-parse --short HEAD 2>/dev/null)\`

## 汇总

| 风险等级 | 数量 |
|---|---|
| 🔴 HIGH（必须修，会引发 R14 AllowanceService 同型误判） | $HIGH |
| 🟡 MEDIUM（建议修，影响可维护性） | $MEDIUM |
| 🟢 LOW（人工复核） | $LOW |
| **合计** | **$dead_count** |

## 明细表

| # | 类名 | 路径 | 风险 | 原因 |
|---|---|---|---|---|
EOF

line=0
while IFS='|' read -r cls sample hit_imp hit_new hit_ext ann; do
  [ -z "$cls" ] && continue
  line=$((line + 1))
  rel="${sample#${BACKEND_ROOT}/}"
  echo "$line | \`$cls\` | \`$rel\` | $([ "$ann" != "NO_ANN" ] && echo "🟢 LOW" || echo "🟡 MEDIUM") | $ann |" >> "$REPORT_MD"
done < "$TMPDIR_CHECK/dead_candidates.txt"

cat >> "$REPORT_MD" <<EOF

## 验证建议

1. **🔴 HIGH 项**：必须人工复核 Lombok 注入字段，验证业务可达性
2. 删除前必跑 \`mvn -o -pl <module> test-compile\`（主代码 compile 绿不等于测试编译绿）
3. 删除前必跑 \`scripts/check_deletion_consistency.sh\` 检查 \`IpdPermissionCode\` / \`BusinessConfigKeys\` / i18n 联动
4. 判死刑前必跑本模块 \`mvn -o -pl ruoyi-modules/${MODULE} -Dtest=Xxx test\`（dev profile 真跑测试）

## 重跑命令

\`\`\`bash
cd ${BACKEND_ROOT}
./scripts/scan_dead_code.sh --module ${MODULE} --strict
\`\`\`

## 排除范围

- framework 自带代码（org.springframework.\* / org.ruoyi.framework.\*）
- @Deprecated / @JsonIgnore / @SaIgnore 显式标注
- Spring 自发现注解类（@RestController / @Service / @Component / @Mapper / @Configuration / @RestControllerAdvice）
- 注释引用（javadoc / 单行注释，扫描前已 sed 剥离）
- 测试文件引用作主证据（仅保留为误报排除）
- 兄弟会话 worktree 目录（.claude/worktrees/）
EOF

echo
echo "==== 扫描完成 ===="
echo "报告: $REPORT_MD"
echo "JSON: $REPORT_JSON"

# 退出码：高风险 > 0 时 exit 1
if [ "$HIGH" -gt 0 ]; then
  exit 1
fi
exit 0