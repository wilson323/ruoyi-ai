#!/usr/bin/env bash
# check-rag-docType-20260923.sh
# 门禁：RAG 真活触发 + docType 索引就位（方案文档阶段 1 + 阶段 3 索引前置）
# 检查项:
#   (1) ai_doc_embeddings 表行数 > 0                (阶段 1 验收)
#   (2) idx_emb_doctype 索引存在                     (阶段 3 索引前置)
#
# 设计要点（避免假绿）:
#   - 直连 MySQL（用 .codex/ipd-dev/config/mysql-client.cnf）—— 不依赖后端 HTTP
#   - check1 永远要求 row_count > 0（基础硬门槛）
#   - MIN_ROWS 是在「> 0 基础」之上的额外门槛（默认 = 1，即只要 > 0 就过）
#   - 索引判定用 SHOW INDEX 反向断言（行数 == 0 即红）
#   - FAIL_SEED=1 强制红：开发期自证能红，避免假绿脚本蒙混过关（R134 纪律）
#
# 用法:
#   ./scripts/check-rag-docType-20260923.sh              # 正常模式
#   FAIL_SEED=1 ./scripts/check-rag-docType-20260923.sh   # 自证能红模式
#   MIN_ROWS=5 ./scripts/check-rag-docType-20260923.sh   # 自定义最小行数门槛
#
# 环境变量覆盖:
#   MYSQL_CNF   默认 .codex/ipd-dev/config/mysql-client.cnf
#   DB_NAME     默认 ipd_dev
#   EMB_TABLE   默认 ai_doc_embeddings
#   MIN_ROWS    默认 1（即 row_count > 0 即可；MIN_ROWS>1 时再加门槛）

set -u

MYSQL_CNF="${MYSQL_CNF:-/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf}"
DB_NAME="${DB_NAME:-ipd_dev}"
EMB_TABLE="${EMB_TABLE:-ai_doc_embeddings}"
MIN_ROWS="${MIN_ROWS:-1}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

pass=0; fail=0
script_fault=0; business_fail=0

# ---- 自证能红（R134）----
if [ "${FAIL_SEED:-0}" = "1" ]; then
  echo "${YELLOW}[R134 self-red] FAIL_SEED=1, 强制走 BUSINESS_FAIL 自证能红${RESET}"
  echo "${RED}FAIL${RESET} [check1] ${BOLD}ai_doc_embeddings${RESET} 行数 > 0   ${RED}期望自证: 行数 0 (FAIL_SEED)${RESET}"
  echo "${RED}FAIL${RESET} [check2] ${BOLD}idx_emb_doctype${RESET} 索引存在      ${RED}期望自证: 索引 0 行 (FAIL_SEED)${RESET}"
  business_fail=2
  echo ""
  echo "${BOLD}==== 汇总 ====${RESET}"
  echo "  pass=${pass} fail=${fail}  script_fault=${script_fault}  business_fail=${business_fail}"
  echo "${RED}BUSINESS_FAIL${RESET} (FAIL_SEED=1 自证能红 OK; 移除 FAIL_SEED 重跑验真)"
  exit 1
fi

# ---- 前置：mysql 客户端与 cnf 可用性 ----
if ! command -v mysql >/dev/null 2>&1; then
  echo "${RED}SCRIPT_FAULT${RESET}: mysql 客户端未安装或不在 PATH" >&2
  script_fault=$((script_fault+1))
  exit 2
fi
if [ ! -r "$MYSQL_CNF" ]; then
  echo "${RED}SCRIPT_FAULT${RESET}: MYSQL_CNF 不可读: $MYSQL_CNF" >&2
  script_fault=$((script_fault+1))
  exit 2
fi

mysql_q() {
  mysql --defaults-file="$MYSQL_CNF" -N -B -e "$1" 2>&1
}

echo "${BOLD}==== check-rag-docType-20260923 ====${RESET}"
echo "  DB=${DB_NAME}  table=${EMB_TABLE}  MIN_ROWS=${MIN_ROWS}"
echo ""

# ---- check1: ai_doc_embeddings 行数 > 0 (基础) + MIN_ROWS (可选高级) ----
echo "${BOLD}[check1] ai_doc_embeddings 行数 > 0${RESET}"
row_count="$(mysql_q "USE ${DB_NAME}; SELECT COUNT(*) FROM ${EMB_TABLE};")"
if [ -z "$row_count" ] || ! echo "$row_count" | grep -qE '^[0-9]+$'; then
  echo "${RED}SCRIPT_FAULT${RESET}: 行数查询失败: '$row_count'"
  script_fault=$((script_fault+1))
else
  echo "  当前行数 = ${row_count}"
  if [ "$row_count" -le 0 ]; then
    echo "${RED}FAIL${RESET}: 行数 ${row_count} <= 0（阶段 1 RAG 真活未触发）"
    business_fail=$((business_fail+1))
  elif [ "$row_count" -lt "$MIN_ROWS" ]; then
    echo "${YELLOW}WARN${RESET}: 行数 ${row_count} 满足 > 0 但未达 MIN_ROWS=${MIN_ROWS}"
    pass=$((pass+1))
  else
    echo "${GREEN}PASS${RESET}: 行数 ${row_count} > 0  (>= MIN_ROWS=${MIN_ROWS})"
    pass=$((pass+1))
  fi
fi
echo ""

# ---- check2: idx_emb_doctype 索引存在 ----
echo "${BOLD}[check2] idx_emb_doctype(doc_type) 索引存在${RESET}"
idx_rows="$(mysql_q "USE ${DB_NAME}; SHOW INDEX FROM ${EMB_TABLE};" | awk -F'\t' '$3 == "idx_emb_doctype" {print}' | wc -l | tr -d ' ')"
if [ -z "$idx_rows" ] || ! echo "$idx_rows" | grep -qE '^[0-9]+$'; then
  echo "${RED}SCRIPT_FAULT${RESET}: 索引查询失败: '$idx_rows'"
  script_fault=$((script_fault+1))
else
  echo "  命中 idx_emb_doctype 行数 = ${idx_rows}"
  if [ "$idx_rows" -le 0 ]; then
    echo "${RED}FAIL${RESET}: 索引不存在（阶段 3 前置 DDL 未 apply）"
    business_fail=$((business_fail+1))
  else
    # 进一步校验 Column_name == doc_type（防"索引名撞号但列错"）
    col_check="$(mysql_q "USE ${DB_NAME}; SHOW INDEX FROM ${EMB_TABLE};" | awk -F'\t' '$3 == "idx_emb_doctype" && $5 == "doc_type" {print}' | wc -l | tr -d ' ')"
    if [ "$col_check" -le 0 ]; then
      echo "${RED}FAIL${RESET}: 索引名存在但 Column_name 不是 doc_type（撞号）"
      business_fail=$((business_fail+1))
    else
      echo "${GREEN}PASS${RESET}: idx_emb_doctype(doc_type) 索引就位"
      pass=$((pass+1))
    fi
  fi
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
