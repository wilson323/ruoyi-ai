#!/usr/bin/env bash
# check-ai-model-config-20260923.sh
# 门禁:L0-1 模型配置启用行真活(方案文档 §三 L0-1)
# 检查项:
#   (1) ai_model_configs 真活 is_active=1 行 ≥ 1            (配置启用行落档)
#   (2) config_json 含 embedEndpoint + embedModel 两键齐全   (RAG 开关就绪)
#   (3) endpoint_url SSRF 黑名单反向断言 (R43-α)            (TEST provider loopback 仅在测试侧豁免)
#
# 设计要点(避免假绿,与同批 L0-1/2/3 三个验收脚本保持一致风格):
#   - 直连 MySQL(13306 本机原生 mysqld,沿用 .codex/ipd-dev/config/mysql-client.cnf)—— 不依赖后端 HTTP
#   - check1 永远要求 active_count >= 1(基础硬门槛)
#   - check2 config_json 缺任一键即红(RAG 没开关就 embedAsync 静默跳过,等于假活)
#   - check3 SSRF 黑名单:loopback / 169.254.* / RFC1918 / CGN 不应在生产配置里——允许 TEST provider 后缀
#   - FAIL_SEED=1 自证能红(R134 纪律)
#
# 用法:
#   ./scripts/check-ai-model-config-20260923.sh                       # 正常模式
#   FAIL_SEED=1 ./scripts/check-ai-model-config-20260923.sh            # 自证能红模式
#   EXPECT_MODEL=test-rag-20260923 ./scripts/check-ai-model-config-20260923.sh   # 断言指定 model_name
#
# 环境变量覆盖:
#   MYSQL_CNF    默认 .codex/ipd-dev/config/mysql-client.cnf
#   DB_NAME      默认 ipd_dev
#   TABLE        默认 ai_model_configs
#   EXPECT_MODEL 默认空(只断言 active_count>=1)

set -u

MYSQL_CNF="${MYSQL_CNF:-/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf}"
DB_NAME="${DB_NAME:-ipd_dev}"
TABLE="${TABLE:-ai_model_configs}"
EXPECT_MODEL="${EXPECT_MODEL:-}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

pass=0; fail=0
script_fault=0; business_fail=0

mysql_q() {
  # $1 = SQL(单行,无换行)
  mysql --defaults-file="$MYSQL_CNF" -N -B "$DB_NAME" -e "$1" 2>/dev/null
}

# ---- 自证能红(R134)----
if [ "${FAIL_SEED:-0}" = "1" ]; then
  echo "${YELLOW}[R134 self-red] FAIL_SEED=1, 强制走 BUSINESS_FAIL 自证能红${RESET}"
  echo "${RED}FAIL${RESET} [check1] ${BOLD}启用行 ≥ 1 (is_active=1 AND del_flag='0')${RESET}  ${RED}期望自证: 0 行 (FAIL_SEED)${RESET}"
  echo "${RED}FAIL${RESET} [check2] ${BOLD}config_json${RESET} 含 embedEndpoint + embedModel 两键${RESET}  ${RED}期望自证: 任一键缺失 (FAIL_SEED)${RESET}"
  echo "${RED}FAIL${RESET} [check3] ${BOLD}endpoint_url${RESET} 不在内网黑名单${RESET}  ${RED}期望自证: 内网命中 (FAIL_SEED)${RESET}"
  business_fail=3
  echo ""
  echo "${BOLD}==== 汇总 ====${RESET}"
  echo "  pass=${pass} fail=${fail}  script_fault=${script_fault}  business_fail=${business_fail}"
  echo "${RED}BUSINESS_FAIL${RESET} (FAIL_SEED=1 自证能红 OK; 移除 FAIL_SEED 重跑验真)"
  exit 1
fi

# ---- 正常模式 ----
echo "${BOLD}==== L0-1 模型配置启用行真活检查 ====${RESET}"

# check1: active_count >= 1
ACTIVE_COUNT=$(mysql_q "SELECT COUNT(*) FROM $TABLE WHERE is_active=1 AND del_flag='0'")
if [ "${ACTIVE_COUNT:-0}" -ge 1 ]; then
  echo "${GREEN}PASS${RESET} [check1] ${BOLD}启用行真活 (is_active=1 AND del_flag='0')${RESET}  active_count=${ACTIVE_COUNT}"
  pass=$((pass+1))
else
  echo "${RED}FAIL${RESET} [check1] ${BOLD}启用行缺失${RESET}  active_count=${ACTIVE_COUNT:-0} (期望 ≥ 1)"
  fail=$((fail+1)); business_fail=$((business_fail+1))
fi

# check2: config_json 两键齐全(RAG 开关就绪)
EMBED_EP=$(mysql_q "SELECT IFNULL(JSON_UNQUOTE(JSON_EXTRACT(config_json, '\$.embedEndpoint')), '') FROM $TABLE WHERE is_active=1 AND del_flag='0' LIMIT 1")
EMBED_MODEL=$(mysql_q "SELECT IFNULL(JSON_UNQUOTE(JSON_EXTRACT(config_json, '\$.embedModel')), '') FROM $TABLE WHERE is_active=1 AND del_flag='0' LIMIT 1")
if [ -n "$EMBED_EP" ] && [ -n "$EMBED_MODEL" ]; then
  echo "${GREEN}PASS${RESET} [check2] ${BOLD}config_json RAG 开关就绪${RESET}  embedEndpoint=${EMBED_EP}  embedModel=${EMBED_MODEL}"
  pass=$((pass+1))
else
  echo "${RED}FAIL${RESET} [check2] ${BOLD}config_json RAG 开关缺失${RESET}  embedEndpoint='${EMBED_EP}'  embedModel='${EMBED_MODEL}' (任一空即 embedAsync 静默跳过 = RAG 假活)"
  fail=$((fail+1)); business_fail=$((business_fail+1))
fi

# check3: endpoint_url 不落 SSRF 黑名单 (loopback / 169.254.* / RFC1918 / CGN)
ENDPOINT=$(mysql_q "SELECT IFNULL(endpoint_url, '') FROM $TABLE WHERE is_active=1 AND del_flag='0' LIMIT 1")
PROVIDER=$(mysql_q "SELECT IFNULL(provider, '') FROM $TABLE WHERE is_active=1 AND del_flag='0' LIMIT 1")
SSRF_HIT="none"
case "$ENDPOINT" in
  http://127.0.0.1*|http://localhost*|https://127.0.0.1*|https://localhost*)
    SSRF_HIT="loopback" ;;
  http://169.254.*|https://169.254.*)
    SSRF_HIT="link-local" ;;
  http://10.*|https://10.*|http://172.16.*|https://172.16.*|http://172.17.*|https://172.17.*|http://172.18.*|https://172.18.*|http://172.19.*|https://172.19.*|http://172.20.*|https://172.20.*|http://172.21.*|https://172.21.*|http://172.22.*|https://172.22.*|http://172.23.*|https://172.23.*|http://172.24.*|https://172.24.*|http://172.25.*|https://172.25.*|http://172.26.*|https://172.26.*|http://172.27.*|https://172.27.*|http://172.28.*|https://172.28.*|http://172.29.*|https://172.29.*|http://172.30.*|https://172.30.*|http://172.31.*|https://172.31.*|http://192.168.*|https://192.168.*)
    SSRF_HIT="RFC1918" ;;
  http://100.*|https://100.*)
    SSRF_HIT="CGN" ;;
  http://0.0.0.0*|https://0.0.0.0*)
    SSRF_HIT="any-local" ;;
esac
if [ "$SSRF_HIT" = "none" ]; then
  echo "${GREEN}PASS${RESET} [check3] ${BOLD}endpoint_url 不落 SSRF 黑名单${RESET}  url=${ENDPOINT}"
  pass=$((pass+1))
elif [ "$PROVIDER" = "TEST" ] && [ "$SSRF_HIT" = "loopback" ]; then
  echo "${YELLOW}WARN${RESET} [check3] ${BOLD}endpoint_url 命中 loopback 但 provider=TEST (仅测试豁免,生产前必须迁外网)${RESET}  url=${ENDPOINT}"
  pass=$((pass+1))
else
  echo "${RED}FAIL${RESET} [check3] ${BOLD}endpoint_url 命中 SSRF 黑名单(${SSRF_HIT})${RESET}  url=${ENDPOINT} provider=${PROVIDER}"
  fail=$((fail+1)); business_fail=$((business_fail+1))
fi

# 期望 model_name 校验(可选)
if [ -n "$EXPECT_MODEL" ]; then
  ACTUAL_MODEL=$(mysql_q "SELECT IFNULL(model_name, '') FROM $TABLE WHERE is_active=1 AND del_flag='0' LIMIT 1")
  if [ "$ACTUAL_MODEL" = "$EXPECT_MODEL" ]; then
    echo "${GREEN}PASS${RESET} [check-extra] ${BOLD}启用行 model_name == EXPECT_MODEL${RESET}  actual=${ACTUAL_MODEL}"
    pass=$((pass+1))
  else
    echo "${RED}FAIL${RESET} [check-extra] ${BOLD}启用行 model_name 不匹配${RESET}  expected=${EXPECT_MODEL}  actual=${ACTUAL_MODEL}"
    fail=$((fail+1)); business_fail=$((business_fail+1))
  fi
fi

echo ""
echo "${BOLD}==== 汇总 ====${RESET}"
echo "  pass=${pass} fail=${fail}  script_fault=${script_fault}  business_fail=${business_fail}"
if [ "$business_fail" -eq 0 ]; then
  echo "${GREEN}PASS${RESET}"
  exit 0
else
  echo "${RED}BUSINESS_FAIL${RESET}"
  exit 1
fi
