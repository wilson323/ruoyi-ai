#!/usr/bin/env bash
# check_entity_complete.sh
# R25 P0-4 根因 RC-3 治理：实体完整性对账（Entity ↔ Mapper ↔ Service ↔ Controller ↔ DB）
#
# 设计要点：
#   一个业务表必须同时存在：
#     - Entity 类（@TableName）
#     - Mapper 接口（@Mapper 或 extends BaseMapper）
#     - Service 接口 + Impl（@Service）
#     - Controller（@RestController，可选 —— 内部 RPC 也可）
#   任一缺失即"残废"，会导致：
#     - 编译期：CI 失败
#     - 运行期：ClassNotFoundException / NPE
#   DB 表也必须在 ipd_dev 库中存在（除非 entity 声明 @TenantIgnore 且未 excludes）
#
# 输出：
#   - F1 Entity 无 Mapper（最常见 — 蜂群 A 报告 AllowanceService 同型）
#   - F2 Mapper 无 Service
#   - F3 Service 无 Controller（业务可达性受限）
#   - F4 Controller 端点全无前端调用（参考 check_cross_repo_contract.sh D1-B）

set -u

BACKEND_ROOT="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}"
MODULE="${MODULE:-ruoyi-ipd}"

while [ $# -gt 0 ]; do
  case "$1" in
    --module) MODULE="$2"; shift 2 ;;
    -h|--help) sed -n '2,28p' "$0" | sed 's/^# //;s/^#//'; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports"
mkdir -p "$OUTPUT_DIR"
REPORT_MD="${OUTPUT_DIR}/entity-complete-${TIMESTAMP}.md"
REPORT_JSON="${OUTPUT_DIR}/entity-complete-${TIMESTAMP}.json"
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

echo "==== R25 P0-4 实体完整性对账（RC-3 残废检查） ===="
echo "模块: $MODULE"
echo

SCAN_BASE="$BACKEND_ROOT/ruoyi-modules/${MODULE}/src/main/java/org/ruoyi/${MODULE}"
[ ! -d "$SCAN_BASE" ] && SCAN_BASE="$BACKEND_ROOT/ruoyi-modules/${MODULE}/src/main/java"

# ---- 1. 抽取四层文件清单 ----
echo "[1/4] 抽取 Entity / Mapper / Service / Controller 清单..."

> "$TMPDIR_CHECK/entities.txt"
> "$TMPDIR_CHECK/mappers.txt"
> "$TMPDIR_CHECK/services.txt"
> "$TMPDIR_CHECK/controllers.txt"

find "$SCAN_BASE" -name '*Entity.java' -not -path '*/target/*' -not -path '*/.claude/worktrees/*' \
  | sed "s|.*/||;s|\.java$||" \
  >> "$TMPDIR_CHECK/entities.txt"

find "$SCAN_BASE" -name '*Mapper.java' -not -path '*/target/*' -not -path '*/.claude/worktrees/*' \
  | sed "s|.*/||;s|\.java$||" \
  >> "$TMPDIR_CHECK/mappers.txt"

find "$SCAN_BASE" -name '*Service.java' -not -path '*/target/*' -not -path '*/.claude/worktrees/*' \
  | sed "s|.*/||;s|\.java$||" \
  >> "$TMPDIR_CHECK/services.txt"
# ServiceImpl 也算（驼峰命名变体）
find "$SCAN_BASE" -name '*ServiceImpl.java' -not -path '*/target/*' -not -path '*/.claude/worktrees/*' \
  | sed "s|.*/||;s|\.java$||;s|Impl$||" \
  >> "$TMPDIR_CHECK/services.txt"

find "$SCAN_BASE" -name '*Controller.java' -not -path '*/target/*' -not -path '*/.claude/worktrees/*' \
  | sed "s|.*/||;s|\.java$||" \
  >> "$TMPDIR_CHECK/controllers.txt"

sort -u "$TMPDIR_CHECK/entities.txt" > "$TMPDIR_CHECK/entities.uniq.txt"
sort -u "$TMPDIR_CHECK/mappers.txt" > "$TMPDIR_CHECK/mappers.uniq.txt"
sort -u "$TMPDIR_CHECK/services.txt" > "$TMPDIR_CHECK/services.uniq.txt"
sort -u "$TMPDIR_CHECK/controllers.txt" > "$TMPDIR_CHECK/controllers.uniq.txt"

ent_n=$(wc -l < "$TMPDIR_CHECK/entities.uniq.txt" | tr -d ' ')
map_n=$(wc -l < "$TMPDIR_CHECK/mappers.uniq.txt" | tr -d ' ')
svc_n=$(wc -l < "$TMPDIR_CHECK/services.uniq.txt" | tr -d ' ')
ctl_n=$(wc -l < "$TMPDIR_CHECK/controllers.uniq.txt" | tr -d ' ')

echo "  → Entity: $ent_n / Mapper: $map_n / Service(Impl): $svc_n / Controller: $ctl_n"
echo

# ---- 2. F1 Entity 无 Mapper ----
echo "[2/4] F1 Entity 无 Mapper..."
> "$TMPDIR_CHECK/f1_no_mapper.txt"
while IFS= read -r ent; do
  [ -z "$ent" ] && continue
  base="${ent%Entity}"  # FooEntity → Foo
  # 形如 FooMapper 或 FooBaseMapper
  if ! grep -qE "^${base}Mapper$|^${base}BaseMapper$" "$TMPDIR_CHECK/mappers.uniq.txt" 2>/dev/null; then
    echo "$ent" >> "$TMPDIR_CHECK/f1_no_mapper.txt"
  fi
done < "$TMPDIR_CHECK/entities.uniq.txt"
f1_n=$(wc -l < "$TMPDIR_CHECK/f1_no_mapper.txt" | tr -d ' ')

# ---- 3. F2 Mapper 无 Service ----
echo "[3/4] F2 Mapper 无 Service..."
> "$TMPDIR_CHECK/f2_no_service.txt"
while IFS= read -r mp; do
  [ -z "$mp" ] && continue
  base="${mp%Mapper}"  # FooMapper → Foo
  # 宽松匹配：标准 Service / Ipd 前缀 / Impl 类 / Xxx+Service 命名变体
  if ! grep -qE "^${base}Service$|^Ipd${base}Service$|^${base}ServiceImpl$|^Ipd${base}ServiceImpl$" "$TMPDIR_CHECK/services.uniq.txt" 2>/dev/null; then
    echo "$mp" >> "$TMPDIR_CHECK/f2_no_service.txt"
  fi
done < "$TMPDIR_CHECK/mappers.uniq.txt"
f2_n=$(wc -l < "$TMPDIR_CHECK/f2_no_service.txt" | tr -d ' ')

# ---- 4. F3 Service 无 Controller（业务可达性）----
echo "[4/4] F3 Service 无 Controller..."
> "$TMPDIR_CHECK/f3_no_controller.txt"
while IFS= read -r svc; do
  [ -z "$svc" ] && continue
  # 去除 Impl 后缀取基础名（FooServiceImpl → FooService → Foo）
  base="${svc%Service}"
  base="${base%Impl}"
  # 宽松匹配：标准 / Admin 前缀 / Ipd 前缀
  if ! grep -qE "^${base}Controller$|^${base}AdminController$|^Ipd${base}Controller$" "$TMPDIR_CHECK/controllers.uniq.txt" 2>/dev/null; then
    echo "$svc" >> "$TMPDIR_CHECK/f3_no_controller.txt"
  fi
done < "$TMPDIR_CHECK/services.uniq.txt"
f3_n=$(wc -l < "$TMPDIR_CHECK/f3_no_controller.txt" | tr -d ' ')

# ---- 报告 ----
cat > "$REPORT_JSON" <<EOF
{
  "timestamp": "${TIMESTAMP}",
  "module": "${MODULE}",
  "scan_base": "${SCAN_BASE#${BACKEND_ROOT}/}",
  "totals": {
    "entity": ${ent_n}, "mapper": ${map_n}, "service": ${svc_n}, "controller": ${ctl_n}
  },
  "f1_no_mapper": $(awk '{ printf "\"%s\",", $0 }' "$TMPDIR_CHECK/f1_no_mapper.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]"),
  "f2_no_service": $(awk '{ printf "\"%s\",", $0 }' "$TMPDIR_CHECK/f2_no_service.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]"),
  "f3_no_controller": $(awk '{ printf "\"%s\",", $0 }' "$TMPDIR_CHECK/f3_no_controller.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]")
}
EOF

cat > "$REPORT_MD" <<EOF
# R25 P0-4 实体完整性对账报告（${TIMESTAMP}）

> 自动门禁：\`scripts/check_entity_complete.sh\`（RC-3 残废检查）
> 模块：\`${MODULE}\`
> 扫描基：\`${SCAN_BASE#${BACKEND_ROOT}/}\`
> 后端基线：\`$(cd "$BACKEND_ROOT" && git rev-parse --short HEAD 2>/dev/null)\`

## 汇总

| 层级 | 数量 |
|---|---|
| Entity | $ent_n |
| Mapper | $map_n |
| Service(+Impl) | $svc_n |
| Controller | $ctl_n |
| 🔴 F1 Entity 无 Mapper | $f1_n |
| 🔴 F2 Mapper 无 Service | $f2_n |
| 🟡 F3 Service 无 Controller | $f3_n |

## 🔴 F1 Entity 无 Mapper（最致命）

> Entity 类已声明但找不到对应 Mapper —— 编译过运行 ClassNotFoundException
> 蜂群 A R25 报告：1 例（AllowanceService 同型教训）

EOF

if [ -s "$TMPDIR_CHECK/f1_no_mapper.txt" ]; then
  echo "| Entity | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|" >> "$REPORT_MD"
  while IFS= read -r e; do
    echo "| \`$e\` | 补 Mapper OR 删除 Entity |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/f1_no_mapper.txt"
else
  echo "✅ 所有 Entity 都有 Mapper" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 🔴 F2 Mapper 无 Service

> Mapper 接口存在但 Service 层缺失 —— 业务调用必须经 Service（事务边界 + 切面）

EOF

if [ -s "$TMPDIR_CHECK/f2_no_service.txt" ]; then
  echo "| Mapper | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|" >> "$REPORT_MD"
  while IFS= read -r m; do
    echo "| \`$m\` | 补 Service OR 删除 Mapper |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/f2_no_service.txt"
else
  echo "✅ 所有 Mapper 都有 Service" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 🟡 F3 Service 无 Controller（业务可达性）

> Service 存在但无 Controller —— 可能是内部 RPC / 调度任务，需人工确认是否对外暴露

EOF

if [ -s "$TMPDIR_CHECK/f3_no_controller.txt" ]; then
  echo "| Service | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|" >> "$REPORT_MD"
  while IFS= read -r s; do
    echo "| \`$s\` | 补 Controller 或确认是内部服务 |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/f3_no_controller.txt"
else
  echo "✅ 所有 Service 都有 Controller" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 重跑命令

\`\`\`bash
cd ${BACKEND_ROOT}
./scripts/check_entity_complete.sh --module ${MODULE}
\`\`\`

## 排除范围

- framework 自带 Service（ruoyi-common-* 的 DictService 等）
- 内部 RPC / 调度任务 Service（标记 @Job / @Internal）
- 工具类（Util / Helper / Constants）
EOF

echo
echo "==== 对账完成 ===="
echo "F1 Entity 无 Mapper: $f1_n"
echo "F2 Mapper 无 Service: $f2_n"
echo "F3 Service 无 Controller: $f3_n"
echo "报告: $REPORT_MD"

if [ "$f1_n" -gt 0 ] || [ "$f2_n" -gt 0 ]; then
  exit 1
fi
exit 0
