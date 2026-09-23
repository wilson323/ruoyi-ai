#!/usr/bin/env bash
# check-copilot-rag-20260923.sh
# 门禁：AiCopilotService 接 RAG（方案文档阶段 2 验收）
# 检查项:
#   HTTP 调 /api/v1/ai-copilot/chat 后,data.sources 数组含 "project.history_docs"
#
# 设计要点（避免假绿）:
#   - 必须真登录拿 token,不能硬编码假 token
#   - 校验 code==0（业务成功）才能继续判 sources
#   - sources 数组必须含字面量 "project.history_docs"
#   - FAIL_SEED=1 强制红：开发期自证能红,避免假绿脚本蒙混过关（R134 纪律）
#
# 用法:
#   ./scripts/check-copilot-rag-20260923.sh                 # 正常模式
#   FAIL_SEED=1 ./scripts/check-copilot-rag-20260923.sh      # 自证能红模式
#   PROJECT_ID=9140001 ./scripts/check-copilot-rag-20260923.sh  # 改项目 ID
#
# 环境变量覆盖:
#   BASE_URL     默认 http://127.0.0.1:16039
#   USERNAME     默认 ipd-admin
#   PASSWORD     默认 Ipd@123456
#   PROJECT_ID   默认 9140001（projectId=9140001 已有 ai_documents 行）
#   CURL_TIMEOUT 默认 15 秒

set -u

BASE_URL="${BASE_URL:-http://127.0.0.1:16039}"
USERNAME="${USERNAME:-ipd-admin}"
PASSWORD="${PASSWORD:-Ipd@123456}"
PROJECT_ID="${PROJECT_ID:-9140001}"
CURL_TIMEOUT="${CURL_TIMEOUT:-15}"
RAG_MARKER="${RAG_MARKER:-project.history_docs}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

pass=0; fail=0
script_fault=0; business_fail=0

# ---- 自证能红（R134）----
if [ "${FAIL_SEED:-0}" = "1" ]; then
  echo "${YELLOW}[R134 self-red] FAIL_SEED=1, 强制走 BUSINESS_FAIL 自证能红${RESET}"
  echo "${RED}FAIL${RESET} [check1] ${BOLD}登录拿 token${RESET}                              ${RED}期望自证: FAIL (FAIL_SEED)${RESET}"
  echo "${RED}FAIL${RESET} [check2] ${BOLD}/ai-copilot/chat 返回 sources 含 ${RAG_MARKER}${RESET}  ${RED}期望自证: FAIL (FAIL_SEED)${RESET}"
  business_fail=2
  echo ""
  echo "${BOLD}==== 汇总 ====${RESET}"
  echo "  pass=${pass} fail=${fail}  script_fault=${script_fault}  business_fail=${business_fail}"
  echo "${RED}BUSINESS_FAIL${RESET} (FAIL_SEED=1 自证能红 OK; 移除 FAIL_SEED 重跑验真)"
  exit 1
fi

# ---- 前置 ----
if ! command -v curl >/dev/null 2>&1; then
  echo "${RED}SCRIPT_FAULT${RESET}: curl 未安装或不在 PATH" >&2
  exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "${RED}SCRIPT_FAULT${RESET}: python3 未安装或不在 PATH（JSON 解析依赖）" >&2
  exit 2
fi

echo "${BOLD}==== check-copilot-rag-20260923 ====${RESET}"
echo "  BASE=${BASE_URL}  user=${USERNAME}  projectId=${PROJECT_ID}"
echo "  RAG_MARKER=${RAG_MARKER}  TIMEOUT=${CURL_TIMEOUT}s"
echo ""

# ---- check1: 登录拿 token ----
echo "${BOLD}[check1] 登录拿 token${RESET}"
login_body="{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}"
login_resp="$(curl -s -m "$CURL_TIMEOUT" -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "$login_body" 2>&1)" || login_resp=""
if [ -z "$login_resp" ]; then
  echo "${RED}SCRIPT_FAULT${RESET}: 登录请求无响应（后端未启?端口 ${BASE_URL} 不通?）"
  script_fault=$((script_fault+1))
  token=""
else
  token="$(echo "$login_resp" | python3 -c 'import sys,json
try:
  d=json.loads(sys.stdin.read())
  if d.get("code")==0:
    print(d.get("data",{}).get("token",""))
  else:
    print("",end="")
except Exception:
  print("",end="")' 2>/dev/null)"
  if [ -z "$token" ]; then
    # 输出响应片段辅助诊断
    echo "${RED}FAIL${RESET}: 登录未返回 token（code!=0 或响应异常）"
    echo "    响应片段: $(echo "$login_resp" | head -c 200)"
    business_fail=$((business_fail+1))
  else
    echo "${GREEN}PASS${RESET}: 登录成功 token_len=${#token}"
    pass=$((pass+1))
  fi
fi
echo ""

# ---- check2: 调 ai-copilot/chat 验 sources ----
if [ -n "${token:-}" ] && [ "$script_fault" -eq 0 ] && [ "$business_fail" -eq 0 ]; then
  echo "${BOLD}[check2] /ai-copilot/chat 返回 sources 含 \"${RAG_MARKER}\"${RESET}"
  chat_body="{\"projectId\":\"${PROJECT_ID}\",\"message\":\"历史文档里这个项目有什么？\"}"
  chat_resp="$(curl -s -m "$CURL_TIMEOUT" -X POST "$BASE_URL/api/v1/ai-copilot/chat" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json; charset=utf-8' \
    --data-binary "$chat_body" 2>&1)" || chat_resp=""
  if [ -z "$chat_resp" ]; then
    echo "${RED}SCRIPT_FAULT${RESET}: chat 请求无响应（后端挂?）"
    script_fault=$((script_fault+1))
  else
    # 同时给出响应片段（受控截断,避免日志爆炸）
    preview="$(echo "$chat_resp" | head -c 400)"
    echo "  响应预览: ${preview}"
    # 用 python3 解析 JSON 拿 code + sources 数组
    parse_out="$(echo "$chat_resp" | python3 -c '
import sys,json
try:
  d=json.loads(sys.stdin.read())
  print("CODE="+str(d.get("code")))
  srcs=d.get("data",{}).get("sources",[])
  print("SOURCES_COUNT="+str(len(srcs)))
  print("SOURCES_JSON="+json.dumps(srcs,ensure_ascii=False))
except Exception as e:
  print("PARSE_ERROR="+str(e))
' 2>/dev/null)"
    code_val="$(echo "$parse_out" | grep '^CODE=' | head -1 | sed 's/^CODE=//')"
    src_count="$(echo "$parse_out" | grep '^SOURCES_COUNT=' | head -1 | sed 's/^SOURCES_COUNT=//')"
    src_json="$(echo "$parse_out" | grep '^SOURCES_JSON=' | head -1 | sed 's/^SOURCES_JSON=//')"
    if [ "$code_val" != "0" ]; then
      echo "${RED}FAIL${RESET}: HTTP 业务 code=${code_val:-?}（非 0，AI 副驾未返成功）"
      business_fail=$((business_fail+1))
    elif [ -z "$src_json" ]; then
      echo "${RED}SCRIPT_FAULT${RESET}: 解析 sources 失败（python 解析异常）"
      script_fault=$((script_fault+1))
    else
      echo "  sources 数组长度 = ${src_count:-?}"
      echo "  sources 内容     = ${src_json}"
      if echo "$src_json" | grep -qF "$RAG_MARKER"; then
        echo "${GREEN}PASS${RESET}: sources 含 \"${RAG_MARKER}\"（RAG 已接）"
        pass=$((pass+1))
      else
        echo "${RED}FAIL${RESET}: sources 不含 \"${RAG_MARKER}\"（RAG 未接或 AI 模型未启用）"
        business_fail=$((business_fail+1))
      fi
    fi
  fi
else
  echo "${YELLOW}SKIP${RESET} [check2] 跳过（check1 失败或脚本故障,继续判汇总）"
fi
echo ""

# ---- 汇总 ----
echo "${BOLD}==== 汇总 ====${RESET}"
echo "  pass=${pass}  fail=${fail}  script_fault=${script_fault}  business_fail=${business_fail}"
if [ "$script_fault" -gt 0 ]; then
  echo "${RED}SCRIPT_FAULT${RESET} (脚本自身故障,非业务 FAIL)"
  exit 2
elif [ "$business_fail" -gt 0 ]; then
  echo "${RED}BUSINESS_FAIL${RESET}"
  exit 1
else
  echo "${GREEN}BUSINESS_OK${RESET}"
  exit 0
fi
