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
#   (a) 文档表名漂移:扫 docs/开发说明/ + docs/ipd-系统说明/ 中的**表名语境
#       候选标识符**(O-6-3 四围栏,见下),与 INFORMATION_SCHEMA.TABLES 对账。
#       命中 DB → 合法;命中白名单 → 合法(术语表已确认);否则 → mismatch,exit 1。
#   (b) DB 表无文档引用:扫 ipd_dev 全表,在文档里 0 引用的表 → 警告(仅警告,
#       不 exit 1,因为新表短期无文档引用是合理的)。
#
# 【2026-09-28 O-6-3 精度修订(审计 §S6 证据③裁决:曾报 73,212~85,697 处漂移,
#   量级不可能;R37/R39 有 66→1→3→2 式精度迭代先例)】
#   旧口径病根:候选提取用 \b[a-z][a-z_]+[a-z]\b —— 把文档中**一切小写英文单词**
#   (含无下划线普通词)都当 snake_case 表名候选,白名单仅 ~470 词,导致普通
#   英文词成片误报(73k+ 假红)。
#   新口径 = 四层「表名语境」围栏(R37 式逐轮收敛,73,212 → 246,297 倍收敛):
#     S1 `tbl.col`   : 反引号包裹的 表.字段 引用(如 `change_requests.code`),
#                      col 非文件扩展名 —— 最强表名声称信号
#     S2 SQL 位置词   : FROM/JOIN/INTO/UPDATE/TABLE 紧随词(含 db.table 取 table),
#                      须含下划线、非字段后缀形态
#     S3 「表」+反引号: 行内含中文「表」字的反引号词(如 「数据表 `gate_elements`」),
#                      排除字段名后缀(_id/_at/_name 等 42 种)与 hex-hash 形态
#     S4 entityType 行: 含 entityType 的行(排除 notification/通知语境)中的
#                      含下划线词 —— R37 §5.1 失真#1 的检出通道
#   【2026-09-28 O-6-3 补丁·扫描截断修复】首轮修订验证时发现:docs/ipd-系统说明/
#     E2E-验收-20260919-{2304,2255,2355}.md 含非法 UTF-8 字节(emoji 截断产物 0xBC 0x8B 0x8E),
#     macOS BWK awk(UTF-8 locale)处理时 towc multibyte conversion failure 直接 fatal(exit 2),
#     且原调用 `2>/dev/null` 吞掉了错误 —— find 序在首个坏文件之后的文档全部漏扫,
#     960 hits/444 drift 实为截断值。修复:LC_ALL=C 字节模式 + index(line,"表") 字节匹配。
#     修复后 hits/drift 数字为全量扫描值(较截断值增大属预期,漏扫面首次纳入)。
#   已知口径限制(如实披露):
#     - 无「表」字/无反引号/无 SQL 动词的纯概念词散引用(如段落里的 demand)
#       不在候选内 —— 概念层对账需术语表支撑(R43-β 方向),静态形态抓不全
#     - 以字段后缀结尾的真表名(仅 sys_dict_data/sys_dict_type/sys_url 三张,
#       均在 DB 集合内)在 S3 通道漏检 —— 不产生误报,仅检出面收窄
#   --refined 语义重构:R39 版三层围栏实现有缺陷(文件缓存只读首行+URL 围栏
#     未实现);现改为「强信号子集」= S1+S2 因栏的漂移(pre-commit hook 兼容
#     入口,drift_count 为阻断级强信号数;主模式输出四围栏全量)。
#   白名单棘轮只减不增:本轮未向白名单加任何词,纯靠围栏精度收敛。
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

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/audit-gate-input.sh"

# ---------------------------------------------------------------------------
# 路径与哨兵
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$REPO_ROOT" || { echo "[check-doc-db-drift] ❌ cd $REPO_ROOT failed" >&2; exit 2; }

MYSQL_CNF="${MYSQL_CNF:-$REPO_ROOT/.codex/ipd-dev/config/mysql-client.cnf}"

# 默认参数
DB_NAME="ipd_dev"
SCOPE="all"          # all | 开发说明 | 系统说明
WHITELIST_FILE=""
JSON_ONLY=0
STRICT=0
MIN_LEN=4            # snake_case 标识符最小长度(MIN_LEN=4 是为了覆盖 R37 §5.1 的 kpis 漂移);MIN_LEN=3 会引入大量 ipd/done/test/dev/vue/git 等短词误报,默认不放开
REFINED=0            # O-6-3 强信号子集模式(S1+S2 围栏漂移;hook 兼容入口)

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
  --refined            强信号子集(S1 tbl.col + S2 SQL 位置词;O-6-3 重构,hook 兼容入口)
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
  gate_require_tree "$root" -name '*.md'
  doc_files=$(find "$root" -type f -name '*.md')
  while IFS= read -r f; do
    DOC_FILES_ALL+=("$f")
  done <<< "$doc_files"
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

DB_TABLE_COUNT=$(gate_grep -c . "$DB_TXT")
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
# ⚠ R37 报告的 8 张漂移原为「自证能红」样本（故意不加白名单）；2026-09-28 owner
# 拍板随 114 条历史漂移一并吸收到外部 --whitelist 文件（只减不增棘轮）。
# 自证能力保障：临时在文档目录造一个含伪造表名的 .md 注入 FAIL_SEED 验证
# 脚本仍会检出（检出后删除种子文件）——吸收白名单不等于关闭检出门禁。
# 后续新漂移必须修文档，不许再加白名单。

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
# 3) 扫描文档 → 提取「表名语境」候选标识符(O-6-3 四围栏,单遍 awk)
#    输出格式:每行 "<id>\t<file>:<line>\t<fence>"
#    fence ∈ {S1,S2,S3,S4},供 --refined 强信号子集(S1+S2)过滤
# ---------------------------------------------------------------------------
AWK_PROG="$(mktemp -t cdbd_fence.XXXXXX)"
trap 'rm -f "$DB_TXT" "$ERR_TXT" "$HITS_TXT" "$DRIFT_TXT" "$WL_TXT" "$DOC_CONCAT_TXT" "$ORPHAN_TXT" "$AWK_PROG"' EXIT
cat > "$AWK_PROG" <<'AWKEOF'
BEGIN {
  # S3 字段名后缀黑名单(42 种;真表名以此结尾的仅 sys_dict_data/type/url,均在 DB 集合内)
  FIELD_RE = "_(id|at|time|name|code|key|type|status|flag|count|seq|hash|version|url|ref|data|value|from|to|by|dept|json|model|score|level|order|path|text|title|label|note|desc|period|reason|mode|format|total|amount|date|day|year|rate|num|size)$"
  EXT_RE = "^(sh|py|mjs|js|ts|tsx|md|json|yml|yaml|xml|sql|java|vue|css|html|txt|log|cnf|conf|lock)$"
}
function ishash(w) { return w ~ /^[0-9a-f]{7,40}$/ }
function oklen(w)  { return length(w) >= minlen }
function emit(w, fn, fnc) { printf "%s\t%s\t%s\n", w, fn, fnc }
{
  line = $0; ln = FNR; fn = FILENAME
  hasbiao = (index(line, "表") > 0)  # LC_ALL=C 字节匹配(免疫非法 UTF-8,见头注释 towc 修复)
  iset    = (line ~ /entityType/ && line !~ /notification|通知/)
  issql   = (line ~ /(FROM|from|JOIN|join|INTO|into|UPDATE|update|TABLE|table)[ \t]+/)

  # 反引号 token:S1(tbl.col) + S3(「表」字语境裸词)
  rest = line
  while (match(rest, /`[^`]+`/)) {
    tok = substr(rest, RSTART + 1, RLENGTH - 2)
    rest = substr(rest, RSTART + RLENGTH)
    if (tok ~ /^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/) {
      split(tok, pp, ".")
      if (pp[1] ~ /_/ && oklen(pp[1]) && pp[2] !~ EXT_RE && !ishash(pp[1]))
        emit(pp[1], fn ":" ln, "S1")
    } else if (tok ~ /^[a-z][a-z0-9_]*$/) {
      if (hasbiao && oklen(tok) && tok !~ /_$/ && !ishash(tok) && tok !~ FIELD_RE)
        emit(tok, fn ":" ln, "S3")
    }
  }

  # S4:entityType 行(排除通知语境)的含下划线词
  if (iset) {
    rest = line
    while (match(rest, /[a-z][a-z0-9]*(_[a-z0-9]+)+/)) {
      w = substr(rest, RSTART, RLENGTH)
      rest = substr(rest, RSTART + RLENGTH)
      if (oklen(w)) emit(w, fn ":" ln, "S4")
    }
  }

  # S2:SQL 位置词(FROM/JOIN/INTO/UPDATE/TABLE 紧随词;db.table 取 table)
  if (issql) {
    rest = line
    while (match(rest, /(FROM|from|JOIN|join|INTO|into|UPDATE|update|TABLE|table)[ \t]+`?[a-z][a-z0-9_.]*/)) {
      seg = substr(rest, RSTART, RLENGTH)
      rest = substr(rest, RSTART + RLENGTH)
      w = seg; sub(/^.*[^a-z0-9_.]/, "", w)
      if (w ~ /\./) { split(w, pp, "."); w = pp[2] }
      if (w ~ /_/ && oklen(w) && !ishash(w) && w !~ FIELD_RE)
        emit(w, fn ":" ln, "S2")
    }
  }
}
AWKEOF

# LC_ALL=C(字节模式):BWK awk 在 UTF-8 locale 下遇非法字节(如 E2E-验收-20260919-*.md:3 的
# 截断 emoji 0xBC 0x8B 0x8E)会 towc fatal 退出,且 `2>/dev/null` 曾吞掉错误 —— 导致 find 序
# 在坏文件之后的全部文档漏扫(960/444 实为截断值)。字节模式下 index(line,"表") 等价匹配,
# 且不再 fatal;stderr 保持可见,fatal 即刻暴露。
LC_ALL=C awk -v minlen="$MIN_LEN" -f "$AWK_PROG" "${DOC_FILES_ALL[@]}" >> "$HITS_TXT"

TOTAL_HITS=$(wc -l < "$HITS_TXT" | tr -d ' ')
[ "$JSON_ONLY" -eq 0 ] && echo "[check-doc-db-drift] fenced table-context hits in docs (O-6-3): $TOTAL_HITS"

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
# awk 只有写入时才创建文件；零匹配也是正常结果，提前建空输出。
: > "${HITS_TXT}.pass"
: > "${HITS_TXT}.fail"
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
fail[$1] { print $1 "\t" $2 "\t" ($3 == "" ? "S?" : $3) }
' "${HITS_TXT}.fail.txt" "$HITS_TXT" | sort -u > "$DRIFT_TXT"
DRIFT_COUNT=$(wc -l < "$DRIFT_TXT" | tr -d ' ')

# 4a-4 O-6-3 强信号子集模式(--refined,hook 兼容入口)
#   R39 版三层围栏实现有缺陷(文件缓存只读首行 + URL 围栏未实现),已废弃。
#   现语义:S1(`tbl.col` 表名字段引用) + S2(SQL 位置词) 两因栏的漂移子集 ——
#   即「文档以最强形态声称了某表名而 DB 无此表」的阻断级信号。
#   主模式(不带 --refined)输出四围栏(S1+S2+S3+S4)全量,供人工对账。
if [ "$REFINED" -eq 1 ]; then
  REFINED_TXT="${DRIFT_TXT}.refined"
  awk -F'\t' '$3 == "S1" || $3 == "S2"' "$DRIFT_TXT" > "$REFINED_TXT"
  RAW_COUNT=$DRIFT_COUNT
  DRIFT_COUNT=$(wc -l < "$REFINED_TXT" | tr -d ' ')
  FILTERED_OUT=$(( RAW_COUNT - DRIFT_COUNT ))
  [ "$JSON_ONLY" -eq 0 ] && echo "[check-doc-db-drift] REFINED(strong-signal S1+S2): $RAW_COUNT all-fence -> $DRIFT_COUNT strong (excluded $FILTERED_OUT contextual S3/S4)"
  mv "$REFINED_TXT" "$DRIFT_TXT"
fi

# 4b) 反向:DB 表在文档中 0 引用
{
  for f in "${DOC_FILES_ALL[@]}"; do
    cat "$f"
    printf '\n'
  done
} > "$DOC_CONCAT_TXT"

> "$ORPHAN_TXT"
while IFS= read -r t; do
  [ -z "$t" ] && continue
  refs=$(gate_grep -F -c -- "$t" "$DOC_CONCAT_TXT")
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
echo "  漂移(表名语境引用了 DB 不存在的标识符,O-6-3 四围栏): $DRIFT_COUNT"
echo "  孤儿(DB 表在文档中 0 引用):              $ORPHAN_COUNT"
echo "  白名单条目数:        $WL_COUNT"
echo

if [ "$DRIFT_COUNT" -gt 0 ]; then
  echo "---- 漂移明细(前 30 条,第三列为围栏来源)----"
  head -30 "$DRIFT_TXT" | while IFS=$'\t' read -r id loc fence; do
    printf "  ⛔ [%s] %-28s  in  %s\n" "${fence:-S?}" "$id" "$loc"
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
  echo "❌ 检测到 $DRIFT_COUNT 处表名语境漂移(R37 §3 失真点,O-6-3 围栏口径)"
  echo "   修复方向:按 R37-DB与测试头扫描-20260918.md §3 逐条改正"
  echo "   或:将确认无误的标识符加入 --whitelist 文件(棘轮只减不增,慎加)"
  echo "   上下文词(S3/S4)误判可人工裁决;主模式全量/强信号(--refined)口径见脚本头注释"
  exit 1
fi
if [ "$STRICT" -eq 1 ] && [ "$ORPHAN_COUNT" -gt 0 ]; then
  echo "❌ --strict 模式下 $ORPHAN_COUNT 张 DB 表无文档引用"
  exit 1
fi
echo "⚠  仅警告,无漂移"
exit 0
