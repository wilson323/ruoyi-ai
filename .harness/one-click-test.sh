#!/usr/bin/env bash
# .harness/one-click-test.sh — 一键测试入口（聚合 5 段，不自己发明口径）
#
# 定位：本脚本只做「编排 + 分类 + 汇总」，每一段都原样调用仓内既有的既定入口，
#       不复制它们的判据，也不另拼一套更严/更松的命令。
#       每段失败了也继续往下跑——聚合器的意义就是一次给你看全，不是第一个红就停。
#
# 用法:
#   bash .harness/one-click-test.sh                  # 五段全跑，单模块单测
#   bash .harness/one-click-test.sh --full           # 单测跑全量(不分模块)
#   bash .harness/one-click-test.sh --only=gate      # 只跑某一段(test|gate|e2e|fe)
#   bash .harness/one-click-test.sh --task=IPD-XXX   # 传给前端 harness 的 task id
#   bash .harness/one-click-test.sh --no-fe          # 跳过前端段(它最慢)
#   bash .harness/one-click-test.sh --help
#
# 退出码(沿用仓内约定):
#   0 = 全绿
#   1 = 有真实失败(含代码错/假绿/门禁定义坏了)
#   2 = 用法错或前置条件缺失，根本跑不起来
#
# 环境变量:
#   ONE_CLICK_SELF_RED=1    注入一个合成失败段，验证「分类与汇总」本身是对的
#   ONE_CLICK_FAIL_SEED=1   跑完全部段、打印完整报告之后再强制 exit 1（不短路）
#   IPD_FRONTEND_ROOT       前端仓根，默认 /Users/mac/Documents/ruoyi-ipd-web
#
# 铁律（都是本仓吃过亏写进 AGENTS.md / 证据规范的，别改）:
#   - 绝不用 clean、绝不用 -am：并发会话共用 target/ 会交叉重写，制造大面积假红
#   - 绝不写行首裸 mvn：一律走 scripts/mvn-locked.sh（自带 JDK 路径 + 跨会话互斥锁）
#   - 绝不 mvn package：会重写 ruoyi-admin.jar，把正在 16039 上服务、第 3 段要用的进程打坏
#   - 绝不 commit / push / 杀进程 / 抢端口
set -uo pipefail   # 刻意不加 -e：单段失败不能中断后面的段（同 .claude/hooks/check-pre-commit.sh:20）

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
cd "$ROOT" || exit 2   # check-e2e-fe-be.sh / gate.sh 全是 CWD 相对路径

BACKEND_PORT=16039
FRONTEND_PORT=15666
FRONTEND_ROOT="${IPD_FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web}"
IPD_MODULE="ruoyi-modules/ruoyi-ipd"

# ── 参数 ──────────────────────────────────────────────────────────────
FULL=false; ONLY=""; TASK="ONECLICK-LOCAL"; SKIP_FE=false
for arg in "$@"; do
  case "$arg" in
    --full)     FULL=true ;;
    --only=*)   ONLY="${arg#--only=}" ;;
    --task=*)   TASK="${arg#--task=}" ;;
    --no-fe)    SKIP_FE=true ;;
    --help|-h)  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)          echo "未知参数: ${arg}（--help 看用法）" >&2; exit 2 ;;
  esac
done
case "$ONLY" in
  ""|test|gate|e2e|fe) ;;
  *) echo "--only 只接受 test|gate|e2e|fe，收到: $ONLY" >&2; exit 2 ;;
esac

want() { [[ -z "$ONLY" || "$ONLY" == "$1" ]]; }

RUN_ID="$(date -u +%Y%m%dT%H%M%S)-$$"
RUN_DIR="$ROOT/.harness/runs/one-click-$RUN_ID"
mkdir -p "$RUN_DIR" || exit 2

# 分类计数（刻意把「代码问题」与「非代码问题」分开，见 summarize）
N_PASS=0; N_CODE=0; N_ENV=0; N_AUTH=0; N_FAKE=0; N_GATE=0; N_SKIP=0
declare -a ROWS=()

record() { # record <段> <判定> <分类> <证据>
  ROWS+=("$1|$2|$3|$4")
  case "$3" in
    PASS)          N_PASS=$((N_PASS+1)) ;;
    CODE_FAIL)     N_CODE=$((N_CODE+1)) ;;
    ENV_MISSING)   N_ENV=$((N_ENV+1)) ;;
    AUTH_MISSING)  N_AUTH=$((N_AUTH+1)) ;;
    FAKE_GREEN)    N_FAKE=$((N_FAKE+1)) ;;
    GATE_DEFECT)   N_GATE=$((N_GATE+1)) ;;
    SKIPPED)       N_SKIP=$((N_SKIP+1)) ;;
  esac
}
banner() { echo ""; echo "── [$1/5] $2"; }

# ── 段 1：单测 ───────────────────────────────────────────────────────
stage_test() {
  if $FULL; then
    banner 1 "Maven 单测（全量，不分模块；profile=dev，仅 @Tag(\"dev\") 会被 Surefire 执行）"
  else
    banner 1 "Maven 单测（单模块 ${IPD_MODULE}；profile=dev，仅 @Tag(\"dev\") 会被 Surefire 执行）"
  fi
  local log="$RUN_DIR/stage-1.log"
  local -a cmd=(bash scripts/mvn-locked.sh -o test -pl "$IPD_MODULE")
  $FULL && cmd=(bash scripts/mvn-locked.sh -o test)
  echo "   \$ ${cmd[*]}"
  ( "${cmd[@]}" ) > "$log" 2>&1
  local rc=$?

  # 该模块测试树里 @Tag("dev") 的出现次数 —— 用来判断「跑 0 个」是不是本来就该跑 0 个
  local tagged
  tagged=$(grep -rl '@Tag("dev")' --include="*.java" \
      "$IPD_MODULE/src/test" 2>/dev/null | wc -l | tr -d ' ')
  echo "   该模块带 @Tag(\"dev\") 的测试文件数: $tagged"

  # surefire 汇总行：Results: / Tests run: X, Failures: Y, Errors: Z, Skipped: W
  local summary run_n fail_n err_n skip_n
  summary=$(grep -hoE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' "$log" | tail -1)
  if [[ -z "$summary" ]]; then
    record "1-单测" "红" "CODE_FAIL" "$log"
    echo "   ✗ 没读到 surefire 汇总行（构建可能没跑到测试阶段，详见 ${log}）"
    return
  fi
  run_n=$(echo "$summary" | grep -oE 'Tests run: [0-9]+' | grep -oE '[0-9]+')
  fail_n=$(echo "$summary" | grep -oE 'Failures: [0-9]+' | grep -oE '[0-9]+')
  err_n=$(echo "$summary" | grep -oE 'Errors: [0-9]+' | grep -oE '[0-9]+')
  skip_n=$(echo "$summary" | grep -oE 'Skipped: [0-9]+' | grep -oE '[0-9]+')
  echo "   $summary"

  # 假绿守卫：Surefire 按 <groups>${profiles.active}</groups> 过滤，缺 @Tag("dev")
  # 的类会被静默跳过，「全绿」可能意味着一个都没跑。
  if [[ "$run_n" == "0" ]]; then
    record "1-单测" "红" "FAKE_GREEN" "$log"
    echo "   ✗ FAKE_GREEN：跑了 0 个测试却没报错。该模块有 $tagged 个带 @Tag(\"dev\") 的文件，说明过滤口径出了问题，不是「没有测试」。"
    return
  fi
  if [[ "$fail_n" != "0" || "$err_n" != "0" || $rc -ne 0 ]]; then
    record "1-单测" "红" "CODE_FAIL" "$log"
    echo "   ✗ CODE_FAIL：failures=$fail_n errors=$err_n (exit $rc)"
    return
  fi
  record "1-单测" "绿" "PASS" "$log"
  echo "   ✓ PASS（skipped=${skip_n}）"
}

# ── 段 2：仓内静态门禁 ────────────────────────────────────────────────
stage_gate() {
  banner 2 "仓内静态门禁 (.harness/gate.sh，原样调用)"
  local log="$RUN_DIR/stage-2.log"
  echo "   \$ bash .harness/gate.sh"
  bash .harness/gate.sh > "$log" 2>&1
  local rc=$?
  local line; line=$(grep -hoE 'PASS=[0-9]+ FAIL=[0-9]+ SKIP=[0-9]+' "$log" | tail -1)
  echo "   ${line:-（没读到汇总行）}"

  # 「必需脚本不存在 —— 拒绝，不跳过」是 gate.sh 自身的漂移形态（脚本被删、门禁还指着它），
  # 与「门禁真的查出了违规」必须分开，否则会把别人的漂移算成你的代码错。
  if grep -q '必需脚本不存在' "$log"; then
    record "2-门禁" "红" "GATE_DEFECT" "$log"
    echo "   ✗ GATE_DEFECT：gate.sh 里有门禁指向已删除的脚本（与你的改动无关，是仓内漂移）"
    grep -h '必需脚本不存在' "$log" | sed 's/^/     /'
    return
  fi
  if [[ $rc -ne 0 ]]; then
    record "2-门禁" "红" "CODE_FAIL" "$log"
    echo "   ✗ CODE_FAIL：至少一道门禁查出了违规（exit ${rc}）"
    grep -E '✗ FAIL \(exit' "$log" | sed 's/^/     /'
    return
  fi
  record "2-门禁" "绿" "PASS" "$log"
  echo "   ✓ PASS"
}

# ── 段 3：真活前后端 E2E ──────────────────────────────────────────────
stage_e2e() {
  banner 3 "真活前后端 E2E（后端 ${BACKEND_PORT} + 前端 ${FRONTEND_PORT}）"
  local log="$RUN_DIR/stage-3.log"

  # 先查前置，缺了就归 ENV_MISSING —— 不要让「服务没起」被记成「契约失败」。
  local missing=()
  lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1  || missing+=("后端 $BACKEND_PORT 未监听")
  lsof -nP -iTCP:"$FRONTEND_PORT" -sTCP:LISTEN >/dev/null 2>&1 || missing+=("前端 $FRONTEND_PORT 未监听")
  [[ -f .codex/ipd-dev/config/credentials.json ]] || missing+=("缺 E2E 凭据 .codex/ipd-dev/config/credentials.json")
  [[ -f .codex/ipd-dev/config/mysql-client.cnf ]]    || missing+=("缺 MySQL 配置 .codex/ipd-dev/config/mysql-client.cnf")
  if [[ ${#missing[@]} -gt 0 ]]; then
    record "3-E2E" "红" "ENV_MISSING" "$log"
    echo "   ✗ ENV_MISSING（不是契约失败，是环境没就位）："
    printf '     %s\n' "${missing[@]}"
    return
  fi
  echo "   前置齐备（两端在听 + 凭据与 MySQL 配置在位）"
  echo "   \$ bash scripts/check-e2e-fe-be.sh"
  bash scripts/check-e2e-fe-be.sh > "$log" 2>&1
  local rc=$?

  # 沿用该脚本自身的 5 类分类，不重新判定
  local report; report=$(ls -t docs/ipd-系统说明/E2E-验收-*.md 2>/dev/null | head -1)
  grep -hoE '\b(PASS|UNREACHABLE|NO_AUTH|NOT_IMPLEMENTED|ENVELOPE_MISMATCH)\b' "$log" \
    | sort | uniq -c | sed 's/^/     /' || true

  if [[ $rc -eq 0 ]]; then
    record "3-E2E" "绿" "PASS" "${report:-$log}"
    echo "   ✓ PASS（报告: ${report:-未生成}）"
    return
  fi
  local cls="CODE_FAIL"
  if   grep -q 'UNREACHABLE' "$log"; then cls="ENV_MISSING"
  elif grep -q 'NO_AUTH' "$log";      then cls="AUTH_MISSING"
  fi
  record "3-E2E" "红" "$cls" "${report:-$log}"
  echo "   ✗ ${cls}（报告: ${report:-$log}）"
}

# ── 段 4：前端仓（走它自己的官方 harness 入口） ────────────────────────
stage_fe() {
  if $SKIP_FE; then
    banner 4 "前端仓 —— 已按 --no-fe 跳过"
    record "4-前端" "跳过" "SKIPPED" "-"
    return
  fi
  banner 4 "前端仓 typecheck + vitest + build（engineering_harness.py --profile frontend）"
  local log="$RUN_DIR/stage-4.log"
  if [[ ! -f "$FRONTEND_ROOT/scripts/engineering_harness.py" ]]; then
    record "4-前端" "红" "ENV_MISSING" "-"
    echo "   ✗ ENV_MISSING：找不到 $FRONTEND_ROOT/scripts/engineering_harness.py（用 IPD_FRONTEND_ROOT 覆盖）"
    return
  fi
  echo "   \$ python3 $FRONTEND_ROOT/scripts/engineering_harness.py --root <前端仓> verify --profile frontend --task $TASK"
  ( cd "$FRONTEND_ROOT" && python3 scripts/engineering_harness.py --root "$FRONTEND_ROOT" \
      verify --profile frontend --task "$TASK" ) > "$log" 2>&1
  local rc=$?

  # 退出码：0=PASSED / 1=非 PASSED / 2=HARNESS_REFUSED
  if [[ $rc -eq 0 ]]; then
    record "4-前端" "绿" "PASS" "$log"
    echo "   ✓ PASS（收据: $FRONTEND_ROOT/.harness/runs/<run_id>/receipt.json）"
    return
  fi
  if grep -q 'RESOURCE_BUSY' "$log"; then
    record "4-前端" "红" "ENV_MISSING" "$log"
    echo "   ✗ ENV_MISSING：另一个 verify 正占着单飞锁，稍后重跑（不是你的代码错）"
    return
  fi
  if [[ $rc -eq 2 ]]; then
    record "4-前端" "红" "ENV_MISSING" "$log"
    echo "   ✗ ENV_MISSING：harness 拒绝执行（缺依赖/参数不合规），详见 $log"
    tail -3 "$log" | sed 's/^/     /'
    return
  fi
  record "4-前端" "红" "CODE_FAIL" "$log"
  echo "   ✗ CODE_FAIL：typecheck / vitest / build 有失败，详见 $log"
}

# ── 段 5：汇总 ────────────────────────────────────────────────────────
summarize() {
  echo ""
  echo "════════════════ 一键测试汇总 ════════════════"
  # 时效三元组（仓规硬要求）：没有这三样，下面所有结论一律视为过期
  echo "观测时刻 : $(date '+%F %T')"
  echo "git HEAD : $(git rev-parse --short HEAD 2>/dev/null || echo 未知)"
  local recent
  recent=$(find . -newermt '-20 minutes' -type f \
      -not -path './.git/*' -not -path './.codex/*' -not -path './.harness/runs/*' \
      -not -path '*/target/*' -not -path '*/node_modules/*' 2>/dev/null | head -10)
  if [[ -n "$recent" ]]; then
    echo "近 20 分钟被改动的文件（结论可能已被兄弟会话改变）："
    printf '  %s\n' $recent
  else
    echo "近 20 分钟被改动的文件: 无"
  fi
  # 自证能红 ①：注入一个合成失败段，验证分类与汇总链路本身是对的。
  # 必须在表格与计数打印「之前」注入，否则注入的失败不会出现在两者里，自证就白做了。
  if [[ "${ONE_CLICK_SELF_RED:-0}" == "1" ]]; then
    echo "[SELF-RED] 注入 1 个合成 CODE_FAIL 段，用于验证分类与统计链路。"
    record "[自证]" "红" "CODE_FAIL" "$RUN_DIR"
  fi
  echo ""
  printf '%-10s %-6s %-16s %s\n' "段" "判定" "分类" "证据"
  printf '%-10s %-6s %-16s %s\n' "----" "----" "----------------" "----"
  for row in "${ROWS[@]:-}"; do
    IFS='|' read -r a b c d <<< "$row"
    printf '%-10s %-6s %-16s %s\n' "$a" "$b" "$c" "$d"
  done
  echo ""
  echo "计数: PASS=$N_PASS  代码错=$N_CODE  环境缺=$N_ENV  鉴权缺=$N_AUTH  假绿=$N_FAKE  门禁自身坏=$N_GATE  按参数跳过=$N_SKIP"
  echo "证据目录: $RUN_DIR"
  echo ""

  # 自证能红 ① 的注入已在上方表格打印前完成，此处不再重复

  local rc=0
  # 只有「代码问题」类才算真失败；环境/鉴权缺失同样阻断（不能替你把环境跑通），但分类不同。
  if [[ $N_CODE -gt 0 || $N_FAKE -gt 0 || $N_GATE -gt 0 || $N_ENV -gt 0 || $N_AUTH -gt 0 ]]; then
    rc=1
  fi
  if [[ $N_PASS -eq 0 ]]; then rc=1; echo "⚠ 一段都没跑成，不能当作通过。"; fi

  echo ""
  if [[ $rc -eq 0 ]]; then
    echo "结论：全绿。"
  else
    echo "结论：未全绿。下一步按分类走——"
    [[ $N_CODE -gt 0 ]] && echo "  · 代码错 $N_CODE 项：看上面「证据」列的日志，那是真实的测试/门禁失败。"
    [[ $N_FAKE -gt 0 ]] && echo "  · 假绿 $N_FAKE 项：测试跑了 0 个却报绿，是 Surefire 的 @Tag 过滤口径问题，先别信这个绿。"
    [[ $N_GATE -gt 0 ]] && echo "  · 门禁自身坏 $N_GATE 项：仓内门禁指向了已删除的脚本，与你的改动无关，需单独修。"
    [[ $N_ENV -gt 0  ]] && echo "  · 环境缺 $N_ENV 项：服务没起/依赖没装/锁被占，先把环境弄好再重跑。"
    [[ $N_AUTH -gt 0 ]] && echo "  · 鉴权缺 $N_AUTH 项：E2E 没拿到 token，检查 credentials.json 或会话是否过期。"
  fi

  # 自证能红 ②：先跑完全部段并打印完整报告，最后才强制失败（不短路）
  if [[ "${ONE_CLICK_FAIL_SEED:-0}" == "1" ]]; then
    echo ""
    echo "[FAIL_SEED] 全部段已跑完、报告已打印完毕，现在强制 exit 1。"
    rc=1
  fi
  exit $rc
}

# ── 跑 ────────────────────────────────────────────────────────────────
want test && stage_test
want gate && stage_gate
want e2e  && stage_e2e
want fe   && stage_fe
summarize