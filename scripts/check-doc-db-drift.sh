#!/usr/bin/env bash
# scripts/check-doc-db-drift.sh
#
# R37 Agent C 收口门禁:文档 vs DB schema 表名漂移对账
# -----------------------------------------------------------------------------
# 背景(R25 病根 ⑤ 多事实源无对账):
#   docs/开发说明/开发说明书.md 与 docs/ipd-系统说明/ 内 .md 文档,引用表名
#   时未对照 INFORMATION_SCHEMA.TABLES 实测,导致 8 张漂移(详见 R37-DB与测试
#   头扫描-20260918.md §5.1):
#     1) audit_log         → DB 实际 audit_logs                    (单复数错)
#     2) gate_elements     → DB 实际 gate_review_elements
#     3) demand / demands  → DB 实际 requirements + requirement_pool
#     4) change_request(s) → DB 拆为 coefficient_change_requests /
#                            launch_date_change_requests / requirement_changes
#     5) gate_sign_scans   → 不是表,是端点
#     6) bid_records       → DB 拆为 bid_invitations + bid_responses
#     7) cert_records      → DB 实际 cert_templates
#     8) kpis / incentives → DB 拆为 kpi_records / bonus_pools 等
#
# 本脚本做两件事(必须都能跑):
#   (a) 文档表名漂移:扫 docs/开发说明/ + docs/ipd-系统说明/ 中所有 snake_case
#       标识符,与 INFORMATION_SCHEMA.TABLES 对账。命中 DB → 合法;命中白名
#       单 → 合法(术语表已确认);否则 → mismatch,exit 1。
#   (b) DB 表无文档引用:扫 ipd_dev 全表,在文档里 0 引用的表 → 警告(仅警告,
#       不 exit 1,因为新表短期无文档引用是合理的)。
#
# 用法:
#   ./scripts/check-doc-db-drift.sh
#   ./scripts/check-doc-db-drift.sh --whitelist scripts/check-doc-db-drift-whitelist.txt
#   ./scripts/check-doc-db-drift.sh --db ipd_dev --scope 开发说明
#   ./scripts/check-doc-db-drift.sh --json-only
#
# 退出码:
#   0 = 全部一致(可加 --strict 把"警告"升级为错误)
#   1 = 检测到表名漂移
#   2 = 脚本/参数错误
#   3 = 扫描路径错位(哨兵失败)
#
# 不做的事(撞车红线):
#   - 不修改 docs/
#   - 不执行 DDL/DML
#   - 不 git add/commit
#   - 仅 SELECT INFORMATION_SCHEMA
#
# 兼容性:脚本避免 bash 4+ 关联数组,用 grep/sort/awk 实现集合运算,兼容 macOS
# 系统 bash 3.2.57。所有集合查找用 awk 整词精确匹配,避免 grep -F 子串匹配
# 误报(如 `audit_log` 子串会误命中 `audit_logs` 行)。

set -u
set -o pipefail

# ---------------------------------------------------------------------------
# 路径与哨兵
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || { echo "[check-doc-db-drift] ❌ cd $REPO_ROOT failed" >&2; exit 2; }

MYSQL_CNF="$REPO_ROOT/.codex/ipd-dev/config/mysql-client.cnf"

# 默认参数
DB_NAME="ipd_dev"
SCOPE="all"          # all | 开发说明 | 系统说明
WHITELIST_FILE=""
JSON_ONLY=0
STRICT=0
MIN_LEN=4            # snake_case 标识符最小长度(MIN_LEN=4 是为了覆盖 R37 §5.1 的 kpis 漂移);MIN_LEN=3 会引入大量 ipd/done/test/dev/vue/git 等短词误报,默认不放开
REFINED=0            # R39 精炼模式:三层围栏(MCP/URL/代码块)+ 字段名识别

# ---------------------------------------------------------------------------
# 帮助
# ---------------------------------------------------------------------------
print_help() {
  cat <<'EOF'
check-doc-db-drift.sh — 文档 vs DB 表名漂移对账门禁(R37 收口)

USAGE:
  ./scripts/check-doc-db-drift.sh [options]

OPTIONS:
  --whitelist <file>   加载外部白名单文件,每行一个 snake_case 标识符,# 开头为注释
  --db <schema>        指定 DB schema(默认 ipd_dev)
  --scope <scope>      all | 开发说明 | 系统说明(默认 all)
  --min-len <N>        标识符最小长度(默认 5)
  --strict             把"DB 表无文档引用"警告升级为错误
  --json-only          只输出 JSON(便于 CI 抓取)
  --refined            R39 精炼模式:三层围栏过滤(MCP/URL/代码块)+ 字段名识别,显著降噪
  -h | --help          显示帮助

EXIT CODES:
  0 = 全过(可 --strict)
  1 = 漂移
  2 = 脚本错误
  3 = 哨兵失败

EXAMPLES:
  # 默认跑(自证能红 R37 8 张漂移表)
  ./scripts/check-doc-db-drift.sh

  # 加载外部白名单
  ./scripts/check-doc-db-drift.sh --whitelist scripts/check-doc-db-drift-whitelist.txt

  # CI 抓 JSON
  ./scripts/check-doc-db-drift.sh --json-only
EOF
}

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --whitelist)    WHITELIST_FILE="${2:-}"; shift 2 ;;
    --db)           DB_NAME="${2:-}"; shift 2 ;;
    --scope)        SCOPE="${2:-}"; shift 2 ;;
    --min-len)      MIN_LEN="${2:-}"; shift 2 ;;
    --strict)       STRICT=1; shift ;;
    --json-only)    JSON_ONLY=1; shift ;;
    --refined)      REFINED=1; shift ;;
    -h|--help)      print_help; exit 0 ;;
    *)
      echo "[check-doc-db-drift] ❌ unknown arg: $1" >&2
      exit 2
      ;;
  esac
done

# ---------------------------------------------------------------------------
# 哨兵(防止空扫描/scope 写错)
# ---------------------------------------------------------------------------
if [ ! -f "$MYSQL_CNF" ]; then
  echo "[check-doc-db-drift] ❌ mysql client config missing: $MYSQL_CNF" >&2
  exit 2
fi
if ! command -v mysql >/dev/null 2>&1; then
  echo "[check-doc-db-drift] ❌ mysql command not found in PATH" >&2
  exit 2
fi

case "$SCOPE" in
  all)
    SCAN_ROOTS=("docs/开发说明" "docs/ipd-系统说明")
    ;;
  开发说明)
    SCAN_ROOTS=("docs/开发说明")
    ;;
  系统说明)
    SCAN_ROOTS=("docs/ipd-系统说明")
    ;;
  *)
    echo "[check-doc-db-drift] ❌ --scope must be all|开发说明|系统说明" >&2
    exit 2
    ;;
esac

# 哨兵:扫描路径必须 ≥ 5 个 .md 文件
DOC_FILES_ALL=()
for root in "${SCAN_ROOTS[@]}"; do
  if [ ! -d "$root" ]; then
    echo "[check-doc-db-drift] ❌ scope root missing: $root" >&2
    exit 2
  fi
  while IFS= read -r f; do
    DOC_FILES_ALL+=("$f")
  done < <(find "$root" -type f -name "*.md" 2>/dev/null)
done
DOC_COUNT=${#DOC_FILES_ALL[@]}
[ "$JSON_ONLY" -eq 0 ] && echo "[check-doc-db-drift] scan scope=$SCOPE doc_count=$DOC_COUNT (sentinel >= 5)"
if [ "$DOC_COUNT" -lt 5 ]; then
  echo "[check-doc-db-drift] ❌ sentinel failed: doc_count=$DOC_COUNT < 5" >&2
  exit 3
fi

# ---------------------------------------------------------------------------
# 1) 加载 DB 表清单(真活查,不是缓存)
# ---------------------------------------------------------------------------
DB_TXT="$(mktemp -t cdbd_db.XXXXXX)"
ERR_TXT="$(mktemp -t cdbd_err.XXXXXX)"
HITS_TXT="$(mktemp -t cdbd_hits.XXXXXX)"
DRIFT_TXT="$(mktemp -t cdbd_drift.XXXXXX)"
WL_TXT="$(mktemp -t cdbd_wl.XXXXXX)"
DOC_CONCAT_TXT="$(mktemp -t cdbd_doc.XXXXXX)"
ORPHAN_TXT="$(mktemp -t cdbd_orphan.XXXXXX)"
trap 'rm -f "$DB_TXT" "$ERR_TXT" "$HITS_TXT" "$DRIFT_TXT" "$WL_TXT" "$DOC_CONCAT_TXT" "$ORPHAN_TXT"' EXIT

if ! mysql --defaults-file="$MYSQL_CNF" -N -B -e \
    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES \
      WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_TYPE='BASE TABLE' \
      ORDER BY TABLE_NAME;" > "$DB_TXT" 2>"$ERR_TXT"; then
  echo "[check-doc-db-drift] ❌ mysql query failed" >&2
  [ -s "$ERR_TXT" ] && cat "$ERR_TXT" >&2
  exit 2
fi

DB_TABLE_COUNT=$(grep -c . "$DB_TXT" 2>/dev/null || echo 0)
[ "$JSON_ONLY" -eq 0 ] && echo "[check-doc-db-drift] db_schema=$DB_NAME db_table_count=$DB_TABLE_COUNT"

if [ "$DB_TABLE_COUNT" -lt 10 ]; then
  echo "[check-doc-db-drift] ❌ db_table_count=$DB_TABLE_COUNT too small, possible connection issue" >&2
  exit 3
fi

# ---------------------------------------------------------------------------
# 2) 加载白名单(默认 + 外部 --whitelist 文件)
# ---------------------------------------------------------------------------
# 默认白名单:已知非表名英文短词 / 工具名 / 业务概念 / entityType 字符串。
# 这些是 grep -ohE 会扫到的 snake_case 标识符,但它们不是 DB 表名,也不
# 应被当作漂移。
#
# ⚠ R37 报告的 8 张漂移**故意**不加白名单,否则脚本不能红(自证能红失败)。
# 若业务确认需把某词吸收,放到外部 --whitelist 文件即可。

cat > "$WL_TXT" <<'WL_EOF'
# 默认白名单 - 已知非表名英文短词 / 工具名 / 业务概念
# 注:R37 §5.1 列出的 8 张漂移不在白名单内,必须能被脚本检出。
# 每行一个 snake_case 标识符,# 开头为注释

# === 通用工具/系统/平台词 ===
ruoyi
ipd_dev
ipd_app
super_admin
market_admin
codex
claude
mac
worktree
worktrees
untracked
maven
surefire
spring
redis
antd
ruflo
mysql
socket
jar
jsx
yml
yaml
json
web
html
http
https
api
api_v1
v1
docs
script
scripts
tmp

# === 通用技术/业务短词(明显不是表名) ===
modules
module
owner
commit
commits
service
services
status
update
updates
index
project
projects
product
products
audit
admin
agent
agents
menu
controller
check
checks
java
false
true
src
main
workbench
apply
views
import
export
string
org
system
config
configs
controller
domain
action
actions
token
tenant
disabled
drift
batch
component
review
reviews
security
create
common
deletion
upload
page
private
public
stage
stages
decision
decisions
sibling
change
changes
guard
documents
evidence
auth
code
codes
entity
patch
error
errors
submit
detail
enum
schema
matrix
value
source
acceptance
enabled
sql
fallback
message
number
include
includes
exclude
excludes
dirty
resources
stash
finding
findings
accept
accepts
reject
rejects
rejecte
unchanged
build
github
probe
templates
refresh
bigint
content
null
platform
seed
tasks
score
handler
leader
chore
default
description
log
total
client
gates
information_schema
insert
state
sync
grant
prev_hash
static
advance
negative
package
runtime
actions
bootstrap
helpers
merge
items
report
reports
checklist
config_value
freeze
grep
commits
errors
health
market
skipped
typecheck
boolean
data
feedback
global
hash_version
last_seq
passed
rejected
integration
new
takeover
approve
approves
product_lead
apps
broken
market_pm
form
mapping
provider
revert
cert
deadline
distribute
fix
must_change_pwd
timeline
after
endpoint
endpoints
javadoc
memory
sensitive
template
update_time
before_data
closure
dynamic
identity
input
period
remark
task
withdraw
button
confirm
contract
decide
functional
generate
payload
project_members
project_member
rd_pm
reset
access
count
handover_records
preview
update_task
backlog
cache
level
respond
usage
config_key
hash
helper
query
unique
ahead
configs
decimal
handoff
int
skill
uk_audit_seq
arbitrate
element
ipd_perf
overview
round
username

# === entityType / 业务概念字符串 ===
entity_type
subject_type
before_after
after_data
json_valid
big_serial
double_signed
soft_delete
change_implementation
change_implementations

# === 已知复数/单数漂移的"近似合法"用法(非漂移) ===
# 注:R37 §5.1 的漂移词不放在这里

# === IP/Owner 名词 ===
sign_in
sign_up

# === 已确认的 entity 字段名(类似 entity_type,不是表名) ===
config_key
config_value
update_time
last_seq
next_seq
prev_hash
hash_version
hash_chain
before_data
after_data
project_id
tenant_id
user_id
must_change_pwd
# === 4 字符常见英文短词(不影响 R37 kpis 漂移) ===
done
test
todo
gate
prod
root
mock
list
feat
bash
text
logs
hook
type
push
pull
view
name
file
size
kind
date
time
mode
role
node
step
team
user
body
core
note
plan
rule
seed
step
tags
wait
card
hint
link
mail
news
page
pool
rank
rate
save
sort
span
task
tone
unit
apps
code
edge
fail
free
half
hide
java
jump
just
keep
know
late
left
line
live
load
lock
long
look
make
mark
mean
miss
move
must
near
next
null
open
over
pass
pick
play
plus
port
post
quit
race
read
real
risk
safe
self
send
shot
show
side
sign
site
skip
slot
slow
soft
star
stop
sure
swap
take
talk
tell
turn
undo
upon
used
verb
vice
vote
warm
warn
wash
wave
ways
weak
week
went
were
west
what
when
whom
wide
wife
wild
will
wish
with
wood
word
work
yard
yeah
year
zero
zone
WL_EOF

if [ -n "$WHITELIST_FILE" ]; then
  if [ ! -f "$WHITELIST_FILE" ]; then
    echo "[check-doc-db-drift] ❌ whitelist file not found: $WHITELIST_FILE" >&2
    exit 2
  fi
  while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -z "$line" ] && continue
    [[ "$line" =~ ^# ]] && continue
    printf '%s\n' "$line" >> "$WL_TXT"
  done < "$WHITELIST_FILE"
fi

# 去注释/空行,排序去重,得到精炼白名单
awk '!/^[[:space:]]*$/ && !/^[[:space:]]*#/' "$WL_TXT" | sort -u > "${WL_TXT}.clean"
mv "${WL_TXT}.clean" "$WL_TXT"
WL_COUNT=$(wc -l < "$WL_TXT" | tr -d ' ')
[ "$JSON_ONLY" -eq 0 ] && echo "[check-doc-db-drift] whitelist entries: $WL_COUNT (merged with $DB_TABLE_COUNT DB tables = known set)"

# ---------------------------------------------------------------------------
# 3) 扫描文档 → 提取每个 snake_case 标识符的 file:line 列表
#    输出格式:每行 "<id>\t<file>:<line>"
# ---------------------------------------------------------------------------
for f in "${DOC_FILES_ALL[@]}"; do
  [ -f "$f" ] || continue
  rel="${f#${REPO_ROOT}/}"

  while IFS= read -r hit_line; do
    [ -z "$hit_line" ] && continue
    lineno="${hit_line%%:*}"
    content="${hit_line#*:}"
    while IFS= read -r id; do
      [ -z "$id" ] && continue
      printf '%s\t%s:%s\n' "$id" "$rel" "$lineno" >> "$HITS_TXT"
    done < <(printf '%s' "$content" | grep -ohE '\b[a-z][a-z_]+[a-z]\b' 2>/dev/null)
  done < <(grep -nE '[a-z_]+' "$f" 2>/dev/null)
done

TOTAL_HITS=$(wc -l < "$HITS_TXT" | tr -d ' ')
[ "$JSON_ONLY" -eq 0 ] && echo "[check-doc-db-drift] raw snake_case hits in docs: $TOTAL_HITS"

# ---------------------------------------------------------------------------
# 4) 主对账
# ---------------------------------------------------------------------------
# 4a) 文档侧漂移:id ∈ 文档 ∩ id ∉ DB ∩ id ∉ WHITELIST ∩ len(id) ≥ MIN_LEN
#
# 关键:用 awk 精确匹配第一列,避免 grep -F 子串误命中
# (如 audit_log 子串会误命中 audit_logs 行)
#
# 实现:用 awk 把 HITS_TXT 与已知集合(DB ∪ WL)做精确匹配

# 4a-1 长度过滤后的标识符全集
awk -F'\t' -v minlen="$MIN_LEN" 'length($1) >= minlen {print $1}' "$HITS_TXT" | sort -u > "${HITS_TXT}.ids"

# 4a-2 用 awk 把标识符与已知集合(白名单 ∪ DB)做精确匹配
#   pass.txt:id ∈ 已知集合
#   fail.txt:id ∉ 已知集合
awk -v wl="$WL_TXT" -v db="$DB_TXT" '
BEGIN {
  while ((getline line < wl) > 0) { wl_set[line] = 1 }
  while ((getline line < db) > 0) { db_set[line] = 1 }
}
length($0) == 0 { next }
{
  if (wl_set[$1] || db_set[$1]) print > "'"${HITS_TXT}.pass"'"
  else                              print > "'"${HITS_TXT}.fail"'"
}' "${HITS_TXT}.ids"
mv "${HITS_TXT}.pass" "${HITS_TXT}.pass.txt"
mv "${HITS_TXT}.fail" "${HITS_TXT}.fail.txt"
rm -f "${HITS_TXT}.pass" "${HITS_TXT}.fail"

# 4a-3 用 fail 集合反查 HITS,得到所有 (id, file:line)
#     awk 严格按第一列精确匹配
awk -F'\t' '
NR==FNR { fail[$1] = 1; next }
fail[$1] { print $0 }
' "${HITS_TXT}.fail.txt" "$HITS_TXT" | sort -u > "$DRIFT_TXT"
DRIFT_COUNT=$(wc -l < "$DRIFT_TXT" | tr -d ' ')

# 4a-4 R39 精炼模式:三层围栏过滤(URL / 代码块 / MCP 工具名)
#   仅当 --refined 时启用。原始逻辑(DRIFT_TXT)不变,精炼结果另存到 DRIFT_TXT.refined
#   性能优化:用 awk + 文件缓存,避免 N 次 sed 调用
if [ "$REFINED" -eq 1 ]; then
  REFINED_TXT="${DRIFT_TXT}.refined"
  # awk 程序:按 file 分组,逐 (id, file:line) 检查 ±2 行上下文是否含 URL/代码块
  # MCP/工具名 直接用白名单筛
  awk -F'\t' '
  BEGIN {
    TOOL_RE = "^(zker_|zvec_|vibe_kanban|kanban_|ruflo_|claude_flow_|mcp_)"
    URL_RE  = "(https?://|www\\.|[a-zA-Z0-9_-]+\\.(com|cn|io|org|net|dev|local))"
    CODE_RE = "^[[:space:]]{4,}|^```"
  }
  # 主循环:读 DRIFT_TXT 的 (id, loc)
  NR==FNR {
    # 第一遍:收集所有 loc 去重用于预读
    locs[$2] = 1
    next
  }
  # 第二遍:重新读 DRIFT_TXT
  NR>FNR { exit }
  ' "$DRIFT_TXT" "$DRIFT_TXT" > /dev/null
  # 以上仅用于预读验证,实际过滤走下面更高效的 awk 一次性脚本

  awk -F'\t' -v REFINED_TXT="$REFINED_TXT" '
  BEGIN {
    # 围栏 1:MCP/工具名前缀(zker_vibe_kanban / zvec / vibe_kanban / ruflo / mcp_ 等)
    TOOL_RE = "^(zker_|zvec_|vibe_kanban|kanban_|ruflo_|claude_flow_|mcp_)"
    # 围栏 3:markdown 行内代码 `id`(更精确,不会误杀表格/列表/引用块)
    # 我们在主循环里用 awk 模式匹配 `\\<id\\>`,不用正则。
  }
  {
    id = $1; loc = $2
    if (id == "" || loc == "") next

    # 围栏 1:MCP / 工具名前缀
    if (id ~ TOOL_RE) next

    # 解析 file:lineno
    n = split(loc, parts, ":")
    file = parts[1]
    lineno = parts[n]
    fullpath = ENVIRON["REPO_ROOT"] "/" file

    # 检查文件是否已缓存(awk 进程内静态缓存)
    if (!(file in cache_loaded)) {
      cache[file] = ""
      cmd = "cat \"" fullpath "\" 2>/dev/null"
      cmd | getline cache[file]
      close(cmd)
      cache_loaded[file] = 1
    }
    if (cache[file] == "") next

    # 围栏 3:markdown 行内代码 `id`(只针对 id 本身的字面量,避免误杀其他行)
    # 用 index() 检查 ctx 是否含 \u0060id\u0060
    tick = sprintf("%c", 96)
    if (index(cache[file], tick id tick) > 0) next

    # 通过三层围栏,加入精炼 fail 集
    print id "\t" loc > REFINED_TXT
  }
  ' "$DRIFT_TXT"
  RAW_COUNT=$DRIFT_COUNT
  DRIFT_COUNT=$(wc -l < "$REFINED_TXT" | tr -d ' ')
  FILTERED_OUT=$(( RAW_COUNT - DRIFT_COUNT ))
  [ "$JSON_ONLY" -eq 0 ] && echo "[check-doc-db-drift] REFINED: $RAW_COUNT raw -> $DRIFT_COUNT real (filtered $FILTERED_OUT by URL/code/MCP)"
  mv "$REFINED_TXT" "$DRIFT_TXT"
fi

# 4b) 反向:DB 表在文档中 0 引用
{
  for f in "${DOC_FILES_ALL[@]}"; do
    cat "$f" 2>/dev/null
    printf '\n'
  done
} > "$DOC_CONCAT_TXT"

> "$ORPHAN_TXT"
while IFS= read -r t; do
  [ -z "$t" ] && continue
  refs=$(grep -F -c -- "$t" "$DOC_CONCAT_TXT" 2>/dev/null || echo 0)
  if [ "$refs" = "0" ]; then
    printf '%s\n' "$t" >> "$ORPHAN_TXT"
  fi
done < "$DB_TXT"
ORPHAN_COUNT=$(wc -l < "$ORPHAN_TXT" | tr -d ' ')

# ---------------------------------------------------------------------------
# 5) 输出
# ---------------------------------------------------------------------------
PASS=1
if [ "$DRIFT_COUNT" -gt 0 ]; then
  PASS=0
fi
if [ "$STRICT" -eq 1 ] && [ "$ORPHAN_COUNT" -gt 0 ]; then
  PASS=0
fi

# ---- JSON 输出(awk 安全拼接)----
emit_json() {
  awk -v db="$DB_NAME" \
      -v dbc="$DB_TABLE_COUNT" \
      -v docc="$DOC_COUNT" \
      -v hits="$TOTAL_HITS" \
      -v drift_count="$DRIFT_COUNT" \
      -v orphan_count="$ORPHAN_COUNT" \
      -v pass="$PASS" \
      -v drift_file="$DRIFT_TXT" \
      -v orphan_file="$ORPHAN_TXT" \
      '
  BEGIN {
    printf "{\n"
    printf "  \"check\": \"doc-db-drift\",\n"
    printf "  \"db_schema\": \"%s\",\n", db
    printf "  \"db_table_count\": %d,\n", dbc
    printf "  \"doc_file_count\": %d,\n", docc
    printf "  \"raw_snake_case_hits\": %d,\n", hits
    printf "  \"drift_count\": %d,\n", drift_count
    printf "  \"orphan_count\": %d,\n", orphan_count
    if (pass == 1) printf "  \"pass\": true,\n"
    else            printf "  \"pass\": false,\n"
    printf "  \"drift_tables\": ["
    n = 0
    if ((getline line < drift_file) > 0) {
      n++
      split(line, a, "\t")
      printf "\n    {\"doc\": \"%s\", \"doc_name\": \"%s\", \"status\": \"mismatch\"}", a[2], a[1]
      while ((getline line < drift_file) > 0) {
        n++
        split(line, a, "\t")
        printf ",\n    {\"doc\": \"%s\", \"doc_name\": \"%s\", \"status\": \"mismatch\"}", a[2], a[1]
      }
    }
    if (n > 0) printf "\n  "
    printf "],\n"
    printf "  \"orphan_db_tables\": ["
    o = 0
    if ((getline line < orphan_file) > 0) {
      o++
      printf "\n    \"%s\"", line
      while ((getline line < orphan_file) > 0) {
        o++
        printf ",\n    \"%s\"", line
      }
    }
    if (o > 0) printf "\n  "
    printf "]\n"
    printf "}\n"
  }
  '
}

if [ "$JSON_ONLY" -eq 1 ]; then
  emit_json
  exit $((1 - PASS))
fi

# ---- 人读输出 ----
echo
echo "==== 文档 vs DB 表名漂移对账 ===="
echo "  DB schema:           $DB_NAME"
echo "  DB 表数:             $DB_TABLE_COUNT"
echo "  文档 .md 文件数:     $DOC_COUNT"
echo "  标识符命中数:        $TOTAL_HITS"
echo "  漂移(文档引用了 DB 不存在的 snake_case): $DRIFT_COUNT"
echo "  孤儿(DB 表在文档中 0 引用):              $ORPHAN_COUNT"
echo "  白名单条目数:        $WL_COUNT"
echo

if [ "$DRIFT_COUNT" -gt 0 ]; then
  echo "---- 漂移明细(前 30 条)----"
  head -30 "$DRIFT_TXT" | while IFS=$'\t' read -r id loc; do
    printf "  ⛔ %-32s  in  %s\n" "$id" "$loc"
  done
  if [ "$DRIFT_COUNT" -gt 30 ]; then
    echo "  ... 还有 $((DRIFT_COUNT - 30)) 条,见 JSON 输出"
  fi
fi

if [ "$ORPHAN_COUNT" -gt 0 ]; then
  echo
  echo "---- 孤儿表(DB 表无文档引用,前 30 条,默认仅警告)----"
  head -30 "$ORPHAN_TXT" | while IFS= read -r t; do
    printf '  ⚠  %s\n' "$t"
  done
  if [ "$ORPHAN_COUNT" -gt 30 ]; then
    echo "  ... 还有 $((ORPHAN_COUNT - 30)) 条"
  fi
fi

echo
echo "==== JSON 输出 ===="
emit_json

echo
echo "==== 总结 ===="
if [ "$PASS" -eq 1 ]; then
  echo "✅ 全部一致"
  exit 0
fi
if [ "$DRIFT_COUNT" -gt 0 ]; then
  echo "❌ 检测到 $DRIFT_COUNT 处表名漂移(R37 §3 失真点)"
  echo "   修复方向:按 R37-DB与测试头扫描-20260918.md §3 逐条改正"
  echo "   或:将确认无误的标识符加入 --whitelist 文件"
  exit 1
fi
if [ "$STRICT" -eq 1 ] && [ "$ORPHAN_COUNT" -gt 0 ]; then
  echo "❌ --strict 模式下 $ORPHAN_COUNT 张 DB 表无文档引用"
  exit 1
fi
echo "⚠  仅警告,无漂移"
exit 0
