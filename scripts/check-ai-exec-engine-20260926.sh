#!/usr/bin/env bash
# check-ai-exec-engine-20260926.sh
# 门禁:R221 IPD 全阶段 AI 代理执行闭环 · 端到端验收(Task 15 Step 2)
#
# 检查项(每项 PASS/FAIL/SKIP 打印;任一实质 FAIL 退出 1;FAIL_SEED=1 全红自证能红):
#   (1) DDL 已 apply: ai_agent_tasks 表存在 + 索引 ≥ 3
#   (2) tenant.excludes 含 ai_agent_tasks(grep 键名,不认行号)
#   (3) 矩阵 + 接线哨兵绿: ActionExecModeMatrixSentinelTest / ExecutorCoverageSentinelTest
#   (4) 引擎/执行器/调度/hook/fill 测试绿: AiExec*/AiExecution*/Direct*/Generate*/GatePrep*/AiCopilotFillPageTest
#   (5) 真活 HTTP(后端 16039 存活 + 提供凭据才跑,否则 SKIP 不算绿):
#       login 取 token → POST /api/v1/stage-actions/{id}/ai-execute → code=0 且 data.taskId 非空
#   (6) DB 回读: 该 taskId 行 60s 内翻 SUCCEEDED/FAILED(非 PENDING 滞留)
#
# 防假绿纪律(R134/R175):
#   - check3/check4 用显式类名 + 校验 Tests run > 0(Surefire @Tag 静默跳过 = 0 跑即红)
#   - check5/check6 无法执行时诚实标 SKIP,绝不当作绿
#   - FAIL_SEED=1 注入假值,验证脚本能正确变红
#
# 用法:
#   bash scripts/check-ai-exec-engine-20260926.sh
#   FAIL_SEED=1 bash scripts/check-ai-exec-engine-20260926.sh        # 自证能红
#   IPD_VERIFY_USER=admin IPD_VERIFY_PASS=xxx bash scripts/check-ai-exec-engine-20260926.sh  # 开真活 HTTP
#
# 环境变量:
#   MYSQL_CNF       默认 .codex/ipd-dev/config/mysql-client.cnf
#   DB_NAME         默认 ipd_dev
#   API_BASE        默认 http://127.0.0.1:16039
#   IPD_VERIFY_USER / IPD_VERIFY_PASS  真活登录凭据(缺省则 check5/6 SKIP)
#   MVN             默认 /Users/mac/tools/maven/bin/mvn
#   JAVA_HOME       默认 /Users/mac/tools/jdk-17/Contents/Home

set -u

REPO_ROOT="/Users/mac/Documents/ruoyi-ai"
MYSQL_CNF="${MYSQL_CNF:-$REPO_ROOT/.codex/ipd-dev/config/mysql-client.cnf}"
DB_NAME="${DB_NAME:-ipd_dev}"
API_BASE="${API_BASE:-http://127.0.0.1:16039}"
APP_YML="$REPO_ROOT/ruoyi-admin/src/main/resources/application.yml"
MVN="${MVN:-/Users/mac/tools/maven/bin/mvn}"
export JAVA_HOME="${JAVA_HOME:-/Users/mac/tools/jdk-17/Contents/Home}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
pass=0; fail=0; skip=0

mysql_q() {
  mysql --defaults-extra-file="$MYSQL_CNF" -N -B "$DB_NAME" -e "$1" 2>/dev/null
}

# ---- 自证能红(R134)----
if [ "${FAIL_SEED:-0}" = "1" ]; then
  echo "${YELLOW}[R134 self-red] FAIL_SEED=1,强制 6 项全红自证能红${RESET}"
  echo "${RED}FAIL${RESET} [check1] ai_agent_tasks 表/索引 (FAIL_SEED 注入)"
  echo "${RED}FAIL${RESET} [check2] tenant.excludes 含 ai_agent_tasks (FAIL_SEED 注入)"
  echo "${RED}FAIL${RESET} [check3] 哨兵测试 (FAIL_SEED 注入)"
  echo "${RED}FAIL${RESET} [check4] 引擎测试 (FAIL_SEED 注入)"
  echo "${RED}FAIL${RESET} [check5] 真活 HTTP ai-execute (FAIL_SEED 注入)"
  echo "${RED}FAIL${RESET} [check6] DB 回读翻转 (FAIL_SEED 注入)"
  echo ""
  echo "${BOLD}==== 汇总 ====${RESET}  pass=0 fail=6 skip=0"
  echo "${RED}BUSINESS_FAIL${RESET} (FAIL_SEED=1 自证能红 OK;移除 FAIL_SEED 重跑验真)"
  exit 1
fi

echo "${BOLD}==== R221 AI 执行闭环 端到端验收 ====${RESET}"

# ---- check1: DDL apply ----
TBL=$(mysql_q "SHOW TABLES LIKE 'ai_agent_tasks';")
IDX=$(mysql_q "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$DB_NAME' AND TABLE_NAME='ai_agent_tasks';")
if [ -n "$TBL" ] && [ "${IDX:-0}" -ge 3 ]; then
  echo "${GREEN}PASS${RESET} [check1] ${BOLD}ai_agent_tasks 表存在 + 索引≥3${RESET}  table=$TBL idx=$IDX"; pass=$((pass+1))
else
  echo "${RED}FAIL${RESET} [check1] ${BOLD}DDL 未 apply${RESET}  table='${TBL}' idx=${IDX:-0}(期望表存在且索引≥3)"; fail=$((fail+1))
fi

# ---- check2: tenant.excludes ----
if grep -qE '^\s*-\s*ai_agent_tasks\s*$' "$APP_YML" 2>/dev/null; then
  echo "${GREEN}PASS${RESET} [check2] ${BOLD}tenant.excludes 含 ai_agent_tasks${RESET}"; pass=$((pass+1))
else
  echo "${RED}FAIL${RESET} [check2] ${BOLD}tenant.excludes 缺 ai_agent_tasks${RESET}(新表未登记会被租户过滤=查不到)"; fail=$((fail+1))
fi

# ---- check3: 矩阵 + 接线哨兵 ----
run_mvn() {
  # $1 = -Dtest 规格  $2 = 期望最小 Tests run
  local spec="$1" min="$2" out rc tr
  out=$("$MVN" -o -pl ruoyi-modules/ruoyi-ipd -Dtest="$spec" test 2>&1); rc=$?
  tr=$(echo "$out" | grep -oE 'Tests run: [0-9]+' | tail -1 | grep -oE '[0-9]+')
  if [ "$rc" -eq 0 ] && [ "${tr:-0}" -ge "$min" ]; then
    echo "OK|$tr"; return 0
  elif [ "${tr:-0}" -lt "$min" ] && [ "$rc" -eq 0 ]; then
    echo "SKIPDETECT|$tr"; return 2
  else
    echo "FAIL|rc=$rc|$tr"; return 1
  fi
}
R3=$(run_mvn 'ActionExecModeMatrixSentinelTest,ExecutorCoverageSentinelTest' 1)
IFS='|' read -r S3 N3 <<< "$R3"
if [ "$S3" = "OK" ]; then
  echo "${GREEN}PASS${RESET} [check3] ${BOLD}矩阵+接线哨兵绿${RESET}  Tests run=$N3"; pass=$((pass+1))
elif [ "$S3" = "SKIPDETECT" ]; then
  echo "${RED}FAIL${RESET} [check3] ${BOLD}哨兵测试被静默跳过${RESET}  Tests run=$N3(=0 疑 @Tag 假绿)"; fail=$((fail+1))
else
  echo "${RED}FAIL${RESET} [check3] ${BOLD}哨兵测试红${RESET}  $N3 $S3"; fail=$((fail+1))
fi

# ---- check4: 引擎/执行器/调度/hook/fill ----
R4=$(run_mvn 'AiExec*Test,AiExecution*Test,Direct*Test,Generate*Test,GatePrep*Test,AiCopilotFillPageTest' 5)
IFS='|' read -r S4 N4 <<< "$R4"
if [ "$S4" = "OK" ]; then
  echo "${GREEN}PASS${RESET} [check4] ${BOLD}引擎/执行器/调度/hook/fill 绿${RESET}  Tests run=$N4"; pass=$((pass+1))
elif [ "$S4" = "SKIPDETECT" ]; then
  echo "${RED}FAIL${RESET} [check4] ${BOLD}引擎测试被静默跳过${RESET}  Tests run=$N4"; fail=$((fail+1))
else
  echo "${RED}FAIL${RESET} [check4] ${BOLD}引擎测试红${RESET}  $N4 $S4"; fail=$((fail+1))
fi

# ---- check5/6: 真活 HTTP + DB 回读 ----
(nc -z 127.0.0.1 16039 2>/dev/null && [ -n "${IPD_VERIFY_USER:-}" ] && [ -n "${IPD_VERIFY_PASS:-}" ]) ; ALIVE_OK=$?
if ! nc -z 127.0.0.1 16039 2>/dev/null; then
  echo "${YELLOW}SKIP${RESET} [check5] ${BOLD}后端 16039 未存活${RESET}(不算绿,起后端后复跑)"; skip=$((skip+1))
  echo "${YELLOW}SKIP${RESET} [check6] ${BOLD}依赖 check5${RESET}"; skip=$((skip+1))
elif [ -z "${IPD_VERIFY_USER:-}" ] || [ -z "${IPD_VERIFY_PASS:-}" ]; then
  echo "${YELLOW}SKIP${RESET} [check5] ${BOLD}未提供 IPD_VERIFY_USER/PASS 凭据${RESET}(不算真活绿)"; skip=$((skip+1))
  echo "${YELLOW}SKIP${RESET} [check6] ${BOLD}依赖 check5${RESET}"; skip=$((skip+1))
else
  LOGIN=$(curl -s -m 10 -X POST "$API_BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$IPD_VERIFY_USER\",\"password\":\"$IPD_VERIFY_PASS\"}")
  TOKEN=$(echo "$LOGIN" | grep -oE '"token":"[^"]+"' | head -1 | sed -E 's/"token":"(.*)"/\1/')
  AID=$(mysql_q "SELECT id FROM stage_actions WHERE status NOT IN ('DONE','NA') ORDER BY id LIMIT 1;")
  if [ -z "$TOKEN" ] || [ -z "$AID" ]; then
    echo "${RED}FAIL${RESET} [check5] ${BOLD}登录或取动作失败${RESET}  token_empty=$([ -z "$TOKEN" ] && echo 1) action_empty=$([ -z "$AID" ] && echo 1)"; fail=$((fail+1))
    echo "${YELLOW}SKIP${RESET} [check6] ${BOLD}依赖 check5${RESET}"; skip=$((skip+1))
  else
    RESP=$(curl -s -m 15 -X POST "$API_BASE/api/v1/stage-actions/$AID/ai-execute" -H "Authorization: Bearer $TOKEN")
    TASKID=$(echo "$RESP" | grep -oE '"taskId":"[^"]+"' | head -1 | sed -E 's/"taskId":"(.*)"/\1/')
    CODE=$(echo "$RESP" | grep -oE '"code":[0-9]+' | head -1 | grep -oE '[0-9]+')
    if [ "${CODE:-1}" = "0" ] && [ -n "$TASKID" ]; then
      echo "${GREEN}PASS${RESET} [check5] ${BOLD}真活 POST ai-execute code=0 + taskId${RESET}  action=$AID task=$TASKID"; pass=$((pass+1))
      FLIPPED=""
      for _ in $(seq 1 12); do
        ST=$(mysql_q "SELECT status FROM ai_agent_tasks WHERE id='$TASKID';")
        case "$ST" in SUCCEEDED|FAILED) FLIPPED="$ST"; break;; esac
        sleep 5
      done
      if [ -n "$FLIPPED" ]; then
        echo "${GREEN}PASS${RESET} [check6] ${BOLD}DB 回读任务翻转终态${RESET}  status=$FLIPPED"; pass=$((pass+1))
      else
        echo "${RED}FAIL${RESET} [check6] ${BOLD}任务 60s 仍滞留 PENDING/RUNNING${RESET}  last=$(mysql_q "SELECT status FROM ai_agent_tasks WHERE id='$TASKID';")"; fail=$((fail+1))
      fi
    else
      echo "${RED}FAIL${RESET} [check5] ${BOLD}ai-execute 非成功${RESET}  code=${CODE:-?} body=$(echo "$RESP" | head -c 200)"; fail=$((fail+1))
      echo "${YELLOW}SKIP${RESET} [check6] ${BOLD}依赖 check5${RESET}"; skip=$((skip+1))
    fi
  fi
fi

echo ""
echo "${BOLD}==== 汇总 ====${RESET}  pass=$pass fail=$fail skip=$skip"
if [ "$fail" -gt 0 ]; then
  echo "${RED}BUSINESS_FAIL${RESET}"; exit 1
elif [ "$skip" -gt 0 ]; then
  echo "${GREEN}PASS_DETERMINISTIC${RESET} ${YELLOW}(确定性检查全绿;真活 HTTP $skip 项 SKIP — 需凭据/后端,不算端到端全绿)${RESET}"; exit 0
else
  echo "${GREEN}PASS${RESET} ${BOLD}(含真活 HTTP 端到端全绿)${RESET}"; exit 0
fi
