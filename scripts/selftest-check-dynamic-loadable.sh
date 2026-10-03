#!/usr/bin/env bash
# =============================================================================
# selftest-check-dynamic-loadable.sh
# check-dynamic-loadable.sh 的 import.meta.glob 提取器 + 后端种子扫描范围 自证
#
# 背景（两条独立根因）：
#   1. 原提取器 grep -oE '"[^"]*"' 只吃双引号，而真实前端 access.ts 与
#      项目文档样例用的都是单引号 import.meta.glob('../views/**/*.vue')，
#      导致「前端 import.meta.glob 模式数」自 2026-09-19 起恒为 0。
#   2. 后端种子只扫 ruoyi-modules，但该目录下 .sql 文件数为 0，真实菜单种子
#      全在 docs/script/sql —— be_comp_count 恒为 0，交集恒为 0，门禁恒绿。
# 两条任一未修，整条门禁都对 R25 结论零约束力。
#
# 用法:
#   bash scripts/selftest-check-dynamic-loadable.sh
#
# 退出码:
#   0 = PASS（全部用例通过）
#   1 = FAIL（有用例失败）
#   2 = 脚本自身错误
#
# 纪律：所有夹具一律建在 mktemp -d 里并在退出时清理，仓库内不留任何测试文件。
#       BACKEND_ROOT 指向临时目录，脚本生成的 md/json 报告因此也落在临时目录。
# =============================================================================

set -o pipefail

# 固定 locale：脚本输出含中文，macOS 自带 BSD sed 在非 UTF-8 locale 下对含中文的
# 正则会抛 "illegal byte sequence"（实测会把整条用例的判据吞成 none，症状与「门禁
# 没生效」一模一样，极易误判）。显式钉死，不依赖调用者的环境。
# 注意：macOS 上不存在 en_US.UTF-8 这个 locale（只有 en_US.US-ASCII），设了它等于
# 没设 —— 必须用一个 `locale -a` 里真有的 UTF-8 locale，可用 GATE_SELFTEST_LOCALE 覆盖。
export LC_ALL=${GATE_SELFTEST_LOCALE:-zh_CN.UTF-8}
export LANG=$LC_ALL

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/check-dynamic-loadable.sh"

PASS=0
FAIL=0
TEST_RESULTS=()

TMPROOT=$(mktemp -d)
trap 'rm -rf "$TMPROOT"' EXIT

# 建一个假的 IPD 仓库骨架：后端放 SQL 种子，前端 src/router 放 access.ts
# 用法: make_fixture <名字>  → 填充全局 FE / BE 两个变量
# 两个后端种子根目录都建出来（docs/script/sql 与 ruoyi-modules），
# 与脚本的多根目录扫描口径保持一致。
make_fixture() {
  local name="$1"
  FE="$TMPROOT/$name/fe"
  BE="$TMPROOT/$name/be"
  mkdir -p "$FE/src/router" "$FE/src/views/ipd/foo" "$FE/src/views/ipd/bar" \
           "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources" \
           "$BE/docs/script/sql/update"
  : > "$FE/src/router/access.ts"
  : > "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources/menu.sql"
  : > "$BE/docs/script/sql/ruoyi-ai.sql"
}

# 跑目标脚本，回填 RUN_OUT / RUN_EXIT
# 第三个可选参数 = EXEMPTION_FILE（不传则用脚本默认的 $BACKEND_ROOT/scripts/gate-exemptions/...，
# 夹具里没有该文件 → 视为无豁免，T1~T12 的既有语义不受影响）
run_target() {
  local fe="$1" be="$2" ex="${3:-}"
  if [ -n "$ex" ]; then
    RUN_OUT=$(FRONTEND_ROOT="$fe" BACKEND_ROOT="$be" EXEMPTION_FILE="$ex" \
      bash "$TARGET" --output "$be/manifest.json" 2>&1)
  else
    RUN_OUT=$(FRONTEND_ROOT="$fe" BACKEND_ROOT="$be" \
      bash "$TARGET" --output "$be/manifest.json" 2>&1)
  fi
  RUN_EXIT=$?
}

# 从 stdout 里抓某个计数
grab() {
  printf '%s\n' "$RUN_OUT" | sed -n "s/.*$1: *\([0-9][0-9]*\).*/\1/p" | head -1
}

record() {
  if [ "$1" = "pass" ]; then
    PASS=$((PASS+1)); TEST_RESULTS+=("✅ $2")
  else
    FAIL=$((FAIL+1)); TEST_RESULTS+=("❌ $2")
  fi
}

# ---------- T1：单引号 glob 必须被提取到（回退到只吃双引号的旧版即红）----------
make_fixture t1
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
run_target "$FE" "$BE"
GLOB_N=$(grab 'import.meta.glob 模式数')
if [ "${GLOB_N:-none}" = "1" ]; then
  record pass "T1 单引号 glob 提取到 1 个模式（原版为 0）"
else
  record fail "T1 单引号 glob 提取失败（得到 ${GLOB_N:-none}，期望 1）"
fi

# ---------- T2：双引号 glob 仍必须被提取到（防止修成只吃单引号）----------
make_fixture t2
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob("../views/**/*.vue");
EOF
run_target "$FE" "$BE"
GLOB_N=$(grab 'import.meta.glob 模式数')
if [ "${GLOB_N:-none}" = "1" ]; then
  record pass "T2 双引号 glob 提取到 1 个模式（未回退成只吃单引号）"
else
  record fail "T2 双引号 glob 提取失败（得到 ${GLOB_N:-none}，期望 1）"
fi

# ---------- T3：单双引号混用各取一次 ----------
make_fixture t3
cat > "$FE/src/router/access.ts" <<'EOF'
const a = import.meta.glob('../views/**/*.vue');
const b = import.meta.glob("../views/**/*.vue");
EOF
printf '<template/></template>\n' > "$FE/src/views/ipd/foo/index.vue"
printf '<template/></template>\n' > "$FE/src/views/ipd/bar/index.vue"
run_target "$FE" "$BE"
GLOB_N=$(grab 'import.meta.glob 模式数')
VIEWS_N=$(grab '前端动态可加载视图数')
if [ "${GLOB_N:-none}" = "2" ] && [ "${VIEWS_N:-0}" -ge 2 ]; then
  record pass "T3 单双引号混用提取到 2 个模式 + 2 个视图（视图展开链路未被打断）"
else
  record fail "T3 混用提取异常（模式数 ${GLOB_N:-none} / 视图数 ${VIEWS_N:-none}，期望 2 / >=2）"
fi

# ---------- T4：空输入（无 glob、无 SQL）必须合法返回 0，不得误报红 ----------
make_fixture t4
run_target "$FE" "$BE"
GLOB_N=$(grab 'import.meta.glob 模式数')
if [ "$RUN_EXIT" -eq 0 ] && [ "${GLOB_N:-none}" = "0" ]; then
  record pass "T4 空输入合法返回 exit 0（模式数 0，未因修复而误报红）"
else
  record fail "T4 空输入行为异常（exit=$RUN_EXIT / 模式数 ${GLOB_N:-none}，期望 0 / 0）"
fi

# ---------- T5：access.ts 缺失时也不得崩溃或误红 ----------
make_fixture t5
rm -f "$FE/src/router/access.ts"
run_target "$FE" "$BE"
GLOB_N=$(grab 'import.meta.glob 模式数')
if [ "$RUN_EXIT" -eq 0 ] && [ "${GLOB_N:-none}" = "0" ]; then
  record pass "T5 access.ts 缺失合法返回 exit 0（模式数 0）"
else
  record fail "T5 access.ts 缺失行为异常（exit=$RUN_EXIT / 模式数 ${GLOB_N:-none}，期望 0 / 0）"
fi

# ---------- T6：自证能红——单引号 glob + 命中后端菜单 → 交集 1 → 必须 exit 1 ----------
# 这是本次修复最关键的一条：修复前 glob 模式恒为 0，此处永远绿。
make_fixture t6
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
printf '<template/></template>\n' > "$FE/src/views/ipd/foo/index.vue"
printf '<template/></template>\n' > "$FE/src/views/ipd/bar/index.vue"
cat > "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources/menu.sql" <<'EOF'
INSERT INTO sys_menu (component) VALUES ('ipd/foo/index');
EOF
run_target "$FE" "$BE"
INTERSECT_N=$(grab '真正 dynamic-loadable 视图数')
if [ "$RUN_EXIT" -eq 1 ] && [ "${INTERSECT_N:-none}" = "1" ]; then
  record pass "T6 自证能红：交集 1 → exit 1（门禁第一次真有牙齿）"
else
  record fail "T6 自证能红失败（exit=$RUN_EXIT / 交集 ${INTERSECT_N:-none}，期望 1 / 1）"
fi

# ---------- T7：种子必须扫到 docs/script/sql（根因 R120-B）----------
# 回退到只扫 ruoyi-modules 的旧版即红：那份种子不在 ruoyi-modules 下，交集恒 0。
make_fixture t7
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
printf '<template/></template>\n' > "$FE/src/views/ipd/foo/index.vue"
printf '<template/></template>\n' > "$FE/src/views/ipd/bar/index.vue"
rm -f "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources/menu.sql"
cat > "$BE/docs/script/sql/update/2026-01-01-ipd-menu.sql" <<'EOF'
INSERT INTO sys_menu (component) VALUES ('ipd/bar/index');
EOF
run_target "$FE" "$BE"
INTERSECT_N=$(grab '真正 dynamic-loadable 视图数')
if [ "$RUN_EXIT" -eq 1 ] && [ "${INTERSECT_N:-none}" = "1" ]; then
  record pass "T7 docs/script/sql 下的菜单种子被扫到（交集 1 → exit 1）"
else
  record fail "T7 未扫到 docs/script/sql 种子（exit=$RUN_EXIT / 交集 ${INTERSECT_N:-none}，期望 1 / 1）"
fi

# ---------- T8：种子文件数不得少算（尾随换行计数回归）----------
# 命令替换剥掉尾随换行，若落盘时不补 \n，最后一个种子文件不被 wc -l 计入。
# 这条用 3 个种子文件守住：旧写法会数成 2。
make_fixture t8
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
printf '<template/></template>\n' > "$FE/src/views/ipd/foo/index.vue"
rm -f "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources/menu.sql"
: > "$BE/docs/script/sql/ruoyi-ai.sql"
printf 'INSERT INTO sys_menu (component) VALUES ("x");\n' > "$BE/docs/script/sql/update/a-1.sql"
printf 'INSERT INTO sys_menu (component) VALUES ("y");\n' > "$BE/docs/script/sql/update/a-2.sql"
printf 'INSERT INTO sys_menu (component) VALUES ("z");\n' > "$BE/docs/script/sql/update/a-3.sql"
run_target "$FE" "$BE"
SEED_N=$(grab 'SQL 种子文件')
if [ "${SEED_N:-none}" = "3" ]; then
  record pass "T8 种子文件数精确计数 3（未因缺尾随换行少算为 2）"
else
  record fail "T8 种子文件计数错误（得到 ${SEED_N:-none}，期望 3）"
fi

# ---------- T9：.harness/ 历史快照必须被排除 ----------
# 实测 .harness/ 下有 491 个 .sql 备份快照。若未排除，快照里的旧菜单路径会
# 混进 component 清单，虚增 be_comp_count。
make_fixture t9
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
mkdir -p "$BE/.harness/backup"
cat > "$BE/.harness/backup/stale-menu-snapshot.sql" <<'EOF'
INSERT INTO sys_menu (component) VALUES ('ipd/ghost/from-harness-snapshot');
EOF
mkdir -p "$BE/target/classes"
cat > "$BE/target/classes/compiled-menu.sql" <<'EOF'
INSERT INTO sys_menu (component) VALUES ('ipd/ghost/from-target');
EOF
run_target "$FE" "$BE"
BE_N=$(grab '后端菜单 component 字段数')
if [ "${BE_N:-none}" = "0" ]; then
  record pass "T9 .harness/ 与 target/ 快照被排除（component 数 0）"
else
  record fail "T9 快照目录未被排除（component 数 ${BE_N:-none}，期望 0）"
fi

# ---------- T10：.harness/ 快照里的 component 不得进入交集 ----------
# T9 只验计数；这条再验交集，确保污染不会直接让门禁误报红。
make_fixture t10
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
mkdir -p "$FE/src/views/ipd/ghost" "$BE/.harness/backup"
printf '<template/></template>\n' > "$FE/src/views/ipd/ghost/from-harness-snapshot.vue"
cat > "$BE/.harness/backup/stale-menu-snapshot.sql" <<'EOF'
INSERT INTO sys_menu (component) VALUES ('ipd/ghost/from-harness-snapshot');
EOF
run_target "$FE" "$BE"
INTERSECT_N=$(grab '真正 dynamic-loadable 视图数')
if [ "$RUN_EXIT" -eq 0 ] && [ "${INTERSECT_N:-none}" = "0" ]; then
  record pass "T10 .harness/ 快照组件未进入交集（exit 0 / 交集 0）"
else
  record fail "T10 .harness/ 快照污染进入交集（exit=$RUN_EXIT / 交集 ${INTERSECT_N:-none}，期望 0 / 0）"
fi

# ---------- T11：ruoyi-modules 种子仍必须被扫到（多根目录不能只留 docs）----------
# 与 T7 互补：T7 验 docs 侧，T11 验旧根目录没被拆掉。
make_fixture t11
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
printf '<template/></template>\n' > "$FE/src/views/ipd/foo/index.vue"
rm -f "$BE/docs/script/sql/ruoyi-ai.sql"
rm -f "$BE/docs/script/sql/update/2026-01-01-ipd-menu.sql"
cat > "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources/menu.sql" <<'EOF'
INSERT INTO sys_menu (component) VALUES ('ipd/foo/index');
EOF
run_target "$FE" "$BE"
INTERSECT_N=$(grab '真正 dynamic-loadable 视图数')
if [ "$RUN_EXIT" -eq 1 ] && [ "${INTERSECT_N:-none}" = "1" ]; then
  record pass "T11 ruoyi-modules 种子仍被扫到（交集 1 → exit 1）"
else
  record fail "T11 ruoyi-modules 种子丢失（exit=$RUN_EXIT / 交集 ${INTERSECT_N:-none}，期望 1 / 1）"
fi

# ---------- T12：不得用 head -5 截断种子文件清单 ----------
# 真实仓库实测：docs/script/sql 下有 11 个命中 sys_menu 的种子，
# 恢复 `head -5` 后只剩 5 个，ipd/product-lines/index 被整条漏掉，
# be_comp_count 从 2 掉回 0、交集 0 → 门禁重新变成恒绿假绿。
# 本用例造 7 个种子，命中 component 的那个排在最后一位。
make_fixture t12
cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
printf '<template/></template>\n' > "$FE/src/views/ipd/foo/index.vue"
rm -f "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources/menu.sql"
rm -f "$BE/docs/script/sql/ruoyi-ai.sql"
i=1
while [ "$i" -le 6 ]; do
  printf 'INSERT INTO sys_menu (component) VALUES ("filler-%s");\n' "$i" \
    > "$BE/docs/script/sql/update/0$i-filler.sql"
  i=$((i + 1))
done
# 排序后最后一个：head -5 恰好把它切掉
printf "INSERT INTO sys_menu (component) VALUES ('ipd/foo/index');\n" \
  > "$BE/docs/script/sql/update/99-real-hit.sql"
run_target "$FE" "$BE"
SEED_N=$(grab 'SQL 种子文件')
INTERSECT_N=$(grab '真正 dynamic-loadable 视图数')
if [ "${SEED_N:-none}" = "7" ] && [ "$RUN_EXIT" -eq 1 ] && [ "${INTERSECT_N:-none}" = "1" ]; then
  record pass "T12 7 个种子全部扫描、无 head -5 截断（交集 1 → exit 1）"
else
  record fail "T12 种子被截断或漏扫（种子数 ${SEED_N:-none} / exit=$RUN_EXIT / 交集 ${INTERSECT_N:-none}，期望 7 / 1 / 1）"
fi

# =============================================================================
# 豁免机制（3b 节）用例 —— 守住「门禁保持武装，不被已知项永久堵死」
#
# 四条不可让步的性质，每条一个用例：
#   T13 豁免内的项不算违规 → exit 0
#   T14 豁免行没有理由     → exit 2（门禁拒绝执行，不是「无理由也放行」）
#   T15 豁免文件不存在     → 视为无豁免、全量报红 exit 1（不是静默通过）
#   T16 豁免项对应页面已删 → 报「豁免项失效」exit 1（清单不许只增不减）
#   T17 豁免外的新项照常红 → exit 1（检出能力没被削弱）
#   T18 混合场景           → 只对豁免项放行，未豁免的照样进 DIFFS
# =============================================================================

# 造一个「单命中」夹具：菜单下发 ipd/foo/index，前端存在对应 vue。
# 用法: make_hit_fixture <名字>
make_hit_fixture() {
  local name="$1"
  make_fixture "$name"
  cat > "$FE/src/router/access.ts" <<'EOF'
const pageMap = import.meta.glob('../views/**/*.vue');
EOF
  printf '<template/></template>\n' > "$FE/src/views/ipd/foo/index.vue"
  cat > "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources/menu.sql" <<'EOF'
INSERT INTO sys_menu (component) VALUES ('ipd/foo/index');
EOF
}

# ---------- T13：带理由的豁免项不算违规 → exit 0 ----------
make_hit_fixture t13
EX="$TMPROOT/t13/exemptions.txt"
cat > "$EX" <<'EOF'
# 这行是纯注释，不是豁免项
ipd/foo/index  # 现役后台菜单动态加载页，人工确认非死代码
EOF
run_target "$FE" "$BE" "$EX"
INTERSECT_N=$(grab '真正 dynamic-loadable 视图数')
DIFFS_N=$(grab '未豁免违规数 DIFFS')
EXEMPT_HIT_N=$(grab '已豁免命中数')
if [ "$RUN_EXIT" -eq 0 ] && [ "${INTERSECT_N:-none}" = "1" ] \
   && [ "${DIFFS_N:-none}" = "0" ] && [ "${EXEMPT_HIT_N:-none}" = "1" ]; then
  record pass "T13 豁免内项不算违规（交集仍可见 1 / DIFFS 0 → exit 0）"
else
  record fail "T13 豁免未生效（exit=$RUN_EXIT / 交集 ${INTERSECT_N:-none} / DIFFS ${DIFFS_N:-none} / 已豁免 ${EXEMPT_HIT_N:-none}，期望 0 / 1 / 0 / 1）"
fi

# ---------- T14：豁免行没有理由注释 → exit 2 拒绝执行 ----------
# 这是豁免机制的自毁开关：空理由 = 没审过 = 不许放行。
make_hit_fixture t14
EX="$TMPROOT/t14/exemptions.txt"
printf 'ipd/foo/index\n' > "$EX"
run_target "$FE" "$BE" "$EX"
if [ "$RUN_EXIT" -eq 2 ] \
   && printf '%s\n' "$RUN_OUT" | grep -q '缺少理由注释'; then
  record pass "T14 豁免行无理由 → exit 2 且明确报出（门禁拒绝执行）"
else
  record fail "T14 无理由豁免未被拒绝（exit=${RUN_EXIT}，期望 2 且输出含「缺少理由注释」）"
fi

# ---------- T15：豁免文件不存在 → 无豁免、全量报红 exit 1 ----------
# 绝不能是「视为全部豁免」或「静默通过」——那正是假绿门禁的成因。
make_hit_fixture t15
EX="$TMPROOT/t15/does-not-exist.txt"
rm -f "$EX"
run_target "$FE" "$BE" "$EX"
DIFFS_N=$(grab '未豁免违规数 DIFFS')
if [ "$RUN_EXIT" -eq 1 ] && [ "${DIFFS_N:-none}" = "1" ]; then
  record pass "T15 豁免文件不存在 → 视为无豁免全量报红（DIFFS 1 → exit 1）"
else
  record fail "T15 豁免文件缺失被当成了放行（exit=$RUN_EXIT / DIFFS ${DIFFS_N:-none}，期望 1 / 1）"
fi

# ---------- T16：豁免项对应视图已不存在 → 报「豁免项失效」exit 1 ----------
# 防止清单只增不减。造法：清掉菜单种子（交集归 0、DIFFS 归 0），
# 只留一条指向已删除页面的豁免行 —— 这样 exit 1 只可能由「失效豁免」触发，
# 不会被 DIFFS 顺带带出来，用例才有牙齿。
make_hit_fixture t16
: > "$BE/ruoyi-modules/ruoyi-ipd/src/main/resources/menu.sql"
: > "$BE/docs/script/sql/ruoyi-ai.sql"
EX="$TMPROOT/t16/exemptions.txt"
cat > "$EX" <<'EOF'
ipd/ghost/removed-page  # 历史豁免：页面早已删除，本行应被报出来
EOF
run_target "$FE" "$BE" "$EX"
STALE_N=$(grab '豁免项失效数')
DIFFS_N=$(grab '未豁免违规数 DIFFS')
if [ "$RUN_EXIT" -eq 1 ] && [ "${STALE_N:-none}" = "1" ] && [ "${DIFFS_N:-none}" = "0" ] \
   && printf '%s\n' "$RUN_OUT" | grep -q 'ipd/ghost/removed-page'; then
  record pass "T16 豁免项失效被检出并报红（DIFFS 0 / 失效 1 → exit 1，点名报出）"
else
  record fail "T16 豁免项失效未被检出（exit=$RUN_EXIT / DIFFS ${DIFFS_N:-none} / 失效数 ${STALE_N:-none}，期望 1 / 0 / 1）"
fi

# ---------- T17：豁免之外的新项照常报红（检出能力不被削弱） ----------
# 门禁最容易栽的坑：加豁免时顺手把判定也放松了。这条守住「豁免只减命中项，不减检出」。
make_hit_fixture t17
EX="$TMPROOT/t17/exemptions.txt"
cat > "$EX" <<'EOF'
ipd/foo/index  # 已审过的现役页
EOF
printf '<template/></template>\n' > "$FE/src/views/ipd/bar/index.vue"
cat > "$BE/docs/script/sql/update/2026-10-03-new-menu.sql" <<'EOF'
INSERT INTO sys_menu (component) VALUES ('ipd/bar/index');
EOF
run_target "$FE" "$BE" "$EX"
DIFFS_N=$(grab '未豁免违规数 DIFFS')
INTERSECT_N=$(grab '真正 dynamic-loadable 视图数')
if [ "$RUN_EXIT" -eq 1 ] && [ "${DIFFS_N:-none}" = "1" ] && [ "${INTERSECT_N:-none}" = "2" ]; then
  record pass "T17 豁免外的新项照常报红（交集 2 / DIFFS 1 → exit 1，检出能力未削弱）"
else
  record fail "T17 豁免削弱了检出能力（exit=$RUN_EXIT / 交集 ${INTERSECT_N:-none} / DIFFS ${DIFFS_N:-none}，期望 1 / 2 / 1）"
fi

# ---------- T18：纯注释豁免文件 = 等价于无豁免，不能变成全豁免 ----------
# 防「把清单写成一大段注释就绕过门禁」这条退路。
make_hit_fixture t18
EX="$TMPROOT/t18/exemptions.txt"
cat > "$EX" <<'EOF'
# 这个文件只是说明，没有登记任何豁免项
# 登记标准：现役菜单动态加载页
EOF
run_target "$FE" "$BE" "$EX"
EXEMPT_N=$(grab '豁免条目数')
DIFFS_N=$(grab '未豁免违规数 DIFFS')
if [ "$RUN_EXIT" -eq 1 ] && [ "${EXEMPT_N:-none}" = "0" ] && [ "${DIFFS_N:-none}" = "1" ]; then
  record pass "T18 纯注释豁免文件不被当成全豁免（豁免条目 0 / DIFFS 1 → exit 1）"
else
  record fail "T18 纯注释文件被误当成豁免（exit=$RUN_EXIT / 豁免条目 ${EXEMPT_N:-none} / DIFFS ${DIFFS_N:-none}，期望 1 / 0 / 1）"
fi

# ---------- 输出 ----------
echo
for r in "${TEST_RESULTS[@]}"; do echo "  $r"; done
echo
echo "==== selftest-check-dynamic-loadable: PASS=$PASS FAIL=$FAIL ===="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
