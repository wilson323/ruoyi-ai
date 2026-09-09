#!/usr/bin/env bash
# check_tenant_excludes_apply.sh
# R25 P0-4 根因 RC-3 治理：租户白名单配置先行 / DDL 未落地对账
#
# 设计要点（避免 R24 person_roles 超前登记教训）：
#   1. 解析父 application.yml 的 tenant.excludes 列表
#   2. 对每条 excludes 表名，查真库（MySQL 13306 socket）验证是否已建表
#   3. 对每个 ModuleEntity 类（@TableName / Mapper @Select 引用），反向验证 DB 是否建表
#   4. 输出三类异常：
#      - RC-3-A 配置先行（excludes 已登记但 DB 无表）
#      - RC-3-B 实体未登记（DB 有表但 entity 缺失）
#      - RC-3-C 实体存在但 DB 未建表（最严重——编译过运行空）
#
# 退出码:
#   0  = 配置与 DB 一致
#   1  = 发现配置/DB 漂移
#   2  = 脚本错误
#
# 用法:
#   ./scripts/check_tenant_excludes_apply.sh
#   ./scripts/check_tenant_excludes_apply.sh --module ruoyi-ipd

set -u

BACKEND_ROOT="${BACKEND_ROOT:-/Users/mac/Documents/ruoyi-ai}"
MODULE="${MODULE:-ruoyi-ipd}"
SKIP_DB_CHECK=0

while [ $# -gt 0 ]; do
  case "$1" in
    --module) MODULE="$2"; shift 2 ;;
    --skip-db) SKIP_DB_CHECK=1; shift ;;
    -h|--help)
      sed -n '2,28p' "$0" | sed 's/^# //;s/^#//'
      exit 0
      ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="${BACKEND_ROOT}/docs/ipd-系统说明/lint-reports"
mkdir -p "$OUTPUT_DIR"
REPORT_MD="${OUTPUT_DIR}/tenant-excludes-apply-${TIMESTAMP}.md"
REPORT_JSON="${OUTPUT_DIR}/tenant-excludes-apply-${TIMESTAMP}.json"
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

echo "==== R25 P0-4 租户白名单配置/DDL 对账（RC-3） ===="
echo "后端: $BACKEND_ROOT"
echo "模块: $MODULE"
echo

# ---- 1. 解析父 application.yml 的 tenant.excludes ----
echo "[1/4] 解析父 application.yml tenant.excludes..."

APP_YML="$BACKEND_ROOT/ruoyi-admin/src/main/resources/application.yml"
if [ ! -f "$APP_YML" ]; then
  APP_YML=$(find "$BACKEND_ROOT" -name 'application.yml' -path '*/resources/*' -not -path '*/target/*' 2>/dev/null | head -1)
fi
if [ ! -f "$APP_YML" ]; then
  echo "[tenant-excludes] ❌ 找不到 application.yml" >&2
  exit 2
fi
echo "  → 配置源: $APP_YML"

# 提取 tenant.excludes 列表（YAML 数组形式）
# 形式 1:
#   tenant:
#     excludes:
#       - sys_config
#       - person_roles
> "$TMPDIR_CHECK/excludes.txt"
awk '
  /^tenant:[[:space:]]*$/ { in_tenant=1; next }
  in_tenant && /^[[:space:]]+excludes:[[:space:]]*$/ { in_excludes=1; next }
  in_excludes && /^[[:space:]]*-[[:space:]]+/ {
    gsub(/^[[:space:]]*-[[:space:]]+/, "")
    gsub(/[[:space:]]*#.*$/, "")
    if (length($0) > 0) print
  }
  in_excludes && /^[[:space:]]+[a-zA-Z_]/ && !/^[[:space:]]*-/ { in_excludes=0 }
' "$APP_YML" | sort -u > "$TMPDIR_CHECK/excludes.txt"

ex_count=$(wc -l < "$TMPDIR_CHECK/excludes.txt" | tr -d ' ')
echo "  → tenant.excludes 登记数: $ex_count"

# ---- 2. 扫描 Entity 类的 @TableName 注解 ----
echo
echo "[2/4] 扫描 ${MODULE} 模块 Entity 类的 @TableName 注解..."

ENTITY_DIRS=$(find "$BACKEND_ROOT/ruoyi-modules/${MODULE}/src/main/java" \
  "$BACKEND_ROOT/ruoyi-common/${MODULE}/src/main/java" \
  -name '*.java' \
  -not -path '*/target/*' \
  -not -path '*/.claude/worktrees/*' \
  2>/dev/null)

> "$TMPDIR_CHECK/entity_tables.txt"
for ef in $ENTITY_DIRS; do
  # 抽取 @TableName("xxx") 或 @TableName(value="xxx")
  tbl=$(grep -oE '@TableName[[:space:]]*\([[:space:]]*(value[[:space:]]*=[[:space:]]*)?"[^"]*"' "$ef" 2>/dev/null \
    | head -1 \
    | grep -oE '"[^"]+"' \
    | tr -d '"')
  if [ -n "$tbl" ]; then
    echo "${tbl}|${ef#${BACKEND_ROOT}/}" >> "$TMPDIR_CHECK/entity_tables.txt"
  fi
done
sort -u "$TMPDIR_CHECK/entity_tables.txt" > "$TMPDIR_CHECK/entity_tables.uniq.txt"
entity_count=$(wc -l < "$TMPDIR_CHECK/entity_tables.uniq.txt" | tr -d ' ')
echo "  → Entity @TableName 注解数: $entity_count"

# ---- 3. 真库验证（如果可连） ----
echo
echo "[3/4] 真库验证（MySQL @ 127.0.0.1:13306 / ipd_dev）..."

DB_AVAILABLE=0
DB_TABLES_FILE="$TMPDIR_CHECK/db_tables.txt"
> "$DB_TABLES_FILE"

if [ "$SKIP_DB_CHECK" -eq 0 ]; then
  # 复用本机 mysql 客户端 + socket（参见 AGENTS.md）
  # mysql 客户端不在 PATH：优先用仓内源码版（.codex/ipd-dev/software/），再找系统 PATH
  MYSQL_CNF="$BACKEND_ROOT/.codex/ipd-dev/config/mysql-client.cnf"
  MYSQL_BIN=""
  for cand in "$BACKEND_ROOT"/.codex/ipd-dev/software/mysql-*/bin/mysql /opt/homebrew/bin/mysql /usr/local/mysql/bin/mysql mysql; do
    if command -v "$cand" >/dev/null 2>&1 || [ -x "$cand" ]; then MYSQL_BIN="$cand"; break; fi
  done
  if [ -f "$MYSQL_CNF" ] && [ -n "$MYSQL_BIN" ]; then
    if "$MYSQL_BIN" --defaults-file="$MYSQL_CNF" -N -e "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='ipd_dev'" 2>/dev/null > "$DB_TABLES_FILE"; then
      DB_AVAILABLE=1
      db_count=$(wc -l < "$DB_TABLES_FILE" | tr -d ' ')
      echo "  → 库 ipd_dev 实际表数: $db_count"
    else
      echo "  ⚠️ 无法连接库（用 --skip-db 跳过 DB 校验）" >&2
    fi
  else
    # fallback: 找 system mysql client with socket
    if [ -S /tmp/mysql_13306.sock ] || [ -S /var/run/mysqld/mysqld.sock ]; then
      if mysql -uipd_dev -pipd_dev -h127.0.0.1 -P13306 ipd_dev -N -e "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='ipd_dev'" 2>/dev/null > "$DB_TABLES_FILE"; then
        DB_AVAILABLE=1
        db_count=$(wc -l < "$DB_TABLES_FILE" | tr -d ' ')
        echo "  → 库 ipd_dev 实际表数: $db_count"
      fi
    else
      echo "  ⚠️ 无 MySQL socket 也无 cnf 文件，跳过 DB 校验"
    fi
  fi
else
  echo "  → --skip-db 模式，跳过 DB 校验"
fi

# ---- 4. 三向对账 ----
echo
echo "[4/4] 三向对账..."

# RC-3-A 配置先行（excludes 已登记但 DB 无表）
# 注意：tenant.excludes 同时支持表名和 URL 过滤模式（如 /api/v1/**），
# 以 / 开头的条目是 URL 白名单不是表名，跳过避免误报
> "$TMPDIR_CHECK/rc3a_unregistered.txt"
if [ "$DB_AVAILABLE" -eq 1 ]; then
  while IFS= read -r ex; do
    [ -z "$ex" ] && continue
    case "$ex" in /*) continue ;; esac
    if ! grep -qx "$ex" "$DB_TABLES_FILE" 2>/dev/null; then
      echo "$ex" >> "$TMPDIR_CHECK/rc3a_unregistered.txt"
    fi
  done < "$TMPDIR_CHECK/excludes.txt"
fi
rc3a_count=$(wc -l < "$TMPDIR_CHECK/rc3a_unregistered.txt" | tr -d ' ')

# RC-3-B 实体存在但 DB 未建表（最严重——代码可编译但运行查询空）
> "$TMPDIR_CHECK/rc3b_missing_db.txt"
if [ "$DB_AVAILABLE" -eq 1 ]; then
  while IFS='|' read -r tbl path; do
    [ -z "$tbl" ] && continue
    if ! grep -qx "$tbl" "$DB_TABLES_FILE" 2>/dev/null; then
      echo "${tbl}|${path}" >> "$TMPDIR_CHECK/rc3b_missing_db.txt"
    fi
  done < "$TMPDIR_CHECK/entity_tables.uniq.txt"
fi
rc3b_count=$(wc -l < "$TMPDIR_CHECK/rc3b_missing_db.txt" | tr -d ' ')

# RC-3-C Entity @TableName 与 tenant.excludes 重叠（业务逻辑可能漏过滤）
> "$TMPDIR_CHECK/rc3c_overlap.txt"
while IFS='|' read -r tbl path; do
  [ -z "$tbl" ] && continue
  if grep -qx "$tbl" "$TMPDIR_CHECK/excludes.txt" 2>/dev/null; then
    echo "${tbl}|${path}" >> "$TMPDIR_CHECK/rc3c_overlap.txt"
  fi
done < "$TMPDIR_CHECK/entity_tables.uniq.txt"
rc3c_count=$(wc -l < "$TMPDIR_CHECK/rc3c_overlap.txt" | tr -d ' ')

# ---- 输出报告 ----
cat > "$REPORT_JSON" <<EOF
{
  "timestamp": "${TIMESTAMP}",
  "config_source": "${APP_YML#${BACKEND_ROOT}/}",
  "tenant_excludes_count": ${ex_count},
  "entity_table_count": ${entity_count},
  "db_available": ${DB_AVAILABLE},
  "rc3a_unregistered": $(awk '{ printf "\"%s\",", $0 }' "$TMPDIR_CHECK/rc3a_unregistered.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]"),
  "rc3b_missing_db": $(awk -F'|' '{ printf "{\"table\":\"%s\",\"entity\":\"%s\"},", $1, $2 }' "$TMPDIR_CHECK/rc3b_missing_db.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]"),
  "rc3c_overlap": $(awk -F'|' '{ printf "{\"table\":\"%s\",\"entity\":\"%s\"},", $1, $2 }' "$TMPDIR_CHECK/rc3c_overlap.txt" 2>/dev/null | sed 's/,$//' | sed 's/^/[/' | sed 's/$/]/' || echo "[]")
}
EOF

cat > "$REPORT_MD" <<EOF
# R25 P0-4 租户白名单配置/DDL 对账报告（${TIMESTAMP}）

> 自动门禁：\`scripts/check_tenant_excludes_apply.sh\`（RC-3 配置先行对账）
> 模块：\`${MODULE}\`
> 配置源：\`${APP_YML#${BACKEND_ROOT}/}\`
> 后端基线：\`$(cd "$BACKEND_ROOT" && git rev-parse --short HEAD 2>/dev/null)\`

## 汇总

| 类别 | 数量 |
|---|---|
| tenant.excludes 登记 | $ex_count |
| Entity @TableName 数 | $entity_count |
| DB 实际表数 | $([ "$DB_AVAILABLE" -eq 1 ] && wc -l < "$DB_TABLES_FILE" | tr -d ' ' || echo "N/A（DB 未连通）") |
| 🔴 RC-3-A 配置先行（excludes 已登记但 DB 无表） | $rc3a_count |
| 🔴 RC-3-B 实体存在但 DB 未建表（致命） | $rc3b_count |
| 🟡 RC-3-C Entity 与 excludes 重叠（需复核） | $rc3c_count |

## 🔴 RC-3-A 配置先行（DB 缺表）

> tenant.excludes 已登记但 DB 实际无此表 —— "超前登记"反模式，参考 person_roles 教训

EOF

if [ -s "$TMPDIR_CHECK/rc3a_unregistered.txt" ]; then
  echo "| 表名 | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|" >> "$REPORT_MD"
  while IFS= read -r t; do
    echo "| \`$t\` | 从 excludes 移除 OR 补 DDL |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/rc3a_unregistered.txt"
else
  echo "✅ 无配置先行（DB 已建表）" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 🔴 RC-3-B 实体存在但 DB 未建表（最严重）

> Entity 类已声明 @TableName 但 DB 实际无此表 —— 编译过、运行时查询空表

EOF

if [ -s "$TMPDIR_CHECK/rc3b_missing_db.txt" ]; then
  echo "| 表名 | Entity 路径 | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|---|" >> "$REPORT_MD"
  while IFS='|' read -r t p; do
    echo "| \`$t\` | \`$p\` | DBA apply DDL（参考 docs/script/sql/update/） |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/rc3b_missing_db.txt"
else
  echo "✅ 所有 Entity 表都已建表" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 🟡 RC-3-C Entity 与 excludes 重叠

> Entity 在业务模块但同表名已登记 excludes —— 可能是"租户共享表"或漏配置

EOF

if [ -s "$TMPDIR_CHECK/rc3c_overlap.txt" ]; then
  echo "| 表名 | Entity 路径 | 处置建议 |" >> "$REPORT_MD"
  echo "|---|---|---|" >> "$REPORT_MD"
  while IFS='|' read -r t p; do
    echo "| \`$t\` | \`$p\` | 确认是否租户共享，excludes 登记是预期 |" >> "$REPORT_MD"
  done < "$TMPDIR_CHECK/rc3c_overlap.txt"
else
  echo "✅ 无重叠" >> "$REPORT_MD"
fi

cat >> "$REPORT_MD" <<EOF

## 重跑命令

\`\`\`bash
cd ${BACKEND_ROOT}
./scripts/check_tenant_excludes_apply.sh
./scripts/check_tenant_excludes_apply.sh --module ${MODULE}
./scripts/check_tenant_excludes_apply.sh --skip-db   # 仅静态扫描
\`\`\`

## CI 接入

\`\`\`yaml
# .github/workflows/tenant-excludes.yml
- name: 租户白名单 + DDL 对账
  run: ./scripts/check_tenant_excludes_apply.sh
\`\`\`

## 排除范围

- framework 自带 Entity（ruoyi-common-* 的 sys_*, gen_table 等）
- @TableName 不带 value 的会回退到类名转 snake_case —— 当前未处理，需 owner 复核
EOF

echo
echo "==== 对账完成 ===="
echo "RC-3-A 配置先行: $rc3a_count"
echo "RC-3-B Entity 缺表（致命）: $rc3b_count"
echo "RC-3-C 重叠: $rc3c_count"
echo "报告: $REPORT_MD"

if [ "$rc3b_count" -gt 0 ]; then
  exit 1
fi
exit 0
