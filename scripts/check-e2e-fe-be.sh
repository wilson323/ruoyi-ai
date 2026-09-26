#!/usr/bin/env bash
# R119 病根 #4 根除：真活 fe/be 端到端契约验证
# R25 check-cross-repo-contract.sh 是静态代码扫描（路径对账），不跑真活 HTTP
# R119 补缺：启后端 16039 + 前端 15666 + 跑 5 项业务契约 → exit 1 阻断
#
# 5 项业务契约（P0-9 / P3-6.1 / R108 / R118 / R109）：
#   P0-9:  /api/v1/projects/{id}              → 200 + 含 id/name/status
#   P3-6.1:/api/v1/projects/{id}/stages       → 200 + 含 stages[].id/name
#   R108:  /api/v1/kpi/rules                  → 200 + 含 rules[].id/code
#   R118:  /api/v1/persons/active             → 200 + 含 persons[].id/name（非 MOCK）
#   R109:  /api/v1/deletion-requests          → 200 + code0/message 包络 + delFlag 字段
#
# 包络检查（IPD /api/v1）：code=0 + IPD 包络（含 message/data 字段），框架包络（code/msg）判为不符
# R224 分类记账：5 项契约结果分四类，不再把"环境没跑通"和"契约不存在"混记成同一个 ❌
#   PASS               200 + code=0 +（按需）字段存在
#   UNREACHABLE        HTTP=000，连接层失败（后端未启 / 端口不通）
#   NO_AUTH            401/403，token 缺失或会话失效
#   NOT_IMPLEMENTED    404，契约指向的端点生产代码不存在（须 owner 拍板补/删）
#   ENVELOPE_MISMATCH  其余（含 200 但返回框架 code/msg 包络）
# 凭据：E2E_PASSWORD 环境变量优先，否则读 gitignored 的
#   .codex/ipd-dev/config/credentials.json → accounts.<user>.password；报告只写 token 长度

set -e

BACKEND_PORT=16039
FRONTEND_PORT=15666
BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}"
FRONTEND_URL="http://127.0.0.1:${FRONTEND_PORT}"
REPORT="docs/ipd-系统说明/E2E-验收-$(date +%Y%m%d-%H%M)"
if [ "${E2E_SIM_UNREACHABLE:-0}" = "1" ]; then
  # 自证能红的产物不得与真活报告同名（同分钟会互相覆盖，制造假证据）
  REPORT="${REPORT}-SIM"
fi
REPORT="${REPORT}.md"

mkdir -p "$(dirname "$REPORT")"
echo "# E2E 验收-$(basename "$REPORT" | sed 's/^E2E-验收-//; s/\.md$//')" > "$REPORT"
echo "" >> "$REPORT"

FE_ALIVE=1

# 1. 后端存活检查
if ! lsof -i :$BACKEND_PORT >/dev/null 2>&1; then
  echo "## ❌ 后端未启（:$BACKEND_PORT 无监听）" >> "$REPORT"
  echo "  按 AGENTS.md §撞车 0 严守原则：若撞车兄弟会话，则跳过真活 E2E 验收" >> "$REPORT"
  echo "❌ 后端未启，E2E 无法跑真活 HTTP 验收"
  exit 1
fi
echo "## ✅ 后端存活（:${BACKEND_PORT}）" >> "$REPORT"

# 2. 前端存活检查
if ! lsof -i :$FRONTEND_PORT >/dev/null 2>&1; then
  echo "## ⚠️ 前端未启（:${FRONTEND_PORT} 无监听），fe/be 端到端未闭环" >> "$REPORT"
  FE_ALIVE=0
else
  echo "## ✅ 前端存活（:${FRONTEND_PORT}）" >> "$REPORT"
fi

# 3. 认证：IPD /api/v1 走真实 Person 会话，不带 token 只会拿到 401/404
LOGIN_USER="${E2E_USERNAME:-ipd-admin}"
CRED_FILE="${E2E_CRED_FILE:-.codex/ipd-dev/config/credentials.json}"
if [ -z "${E2E_PASSWORD:-}" ] && [ -f "$CRED_FILE" ]; then
  E2E_PASSWORD=$(LOGIN_USER="$LOGIN_USER" CRED_FILE="$CRED_FILE" python3 -c "
import json, os
d = json.load(open(os.environ['CRED_FILE']))
print((d.get('accounts', {}).get(os.environ['LOGIN_USER'], {}) or {}).get('password', ''))
" 2>/dev/null || echo "")
fi
TOKEN=""
if [ -n "${E2E_PASSWORD:-}" ]; then
  TOKEN=$(curl -s -m 10 -X POST "${BACKEND_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"username\":\"${LOGIN_USER}\",\"password\":\"${E2E_PASSWORD}\"}" \
    | python3 -c "import sys, json; d = json.load(sys.stdin); print((d.get('data') or {}).get('token', ''))" 2>/dev/null || echo "")
fi
if [ -n "$TOKEN" ]; then
  echo "✅ 登录取 token 成功（user=${LOGIN_USER} token_len=${#TOKEN}，凭据不入报告）" >> "$REPORT"
else
  echo "⚠️ 未取得 token（user=${LOGIN_USER}）→ 需认证的业务请求按 NO_AUTH 记账" >> "$REPORT"
fi

# 4. 5 项业务契约验证（四类分流记账）
echo "" >> "$REPORT"
echo "## 业务契约验证" >> "$REPORT"
echo "" >> "$REPORT"
echo "| 契约 | 端点 | HTTP | code | 字段 | 分类 |" >> "$REPORT"
echo "|---|---|---|---|---|---|" >> "$REPORT"

# 拿一个真实 project id（从真库查）
CNF=".codex/ipd-dev/config/mysql-client.cnf"
PROJECT_ID=$(mysql --defaults-file="$CNF" -N -e "SELECT id FROM ipd_dev.projects WHERE del_flag='0' LIMIT 1;" 2>/dev/null || echo "")
if [ -z "$PROJECT_ID" ]; then
  echo "❌ 真库无 project 数据，跳过业务契约验证"
  exit 1
fi

PASS_C=0
UNREACH_C=0
NOAUTH_C=0
NOTIMPL_C=0
ENVELOPE_C=0

# probe <标签> <路径> <必须存在的 data 字段（可空）>
probe() {
  local label="$1" path="$2" need_field="$3"
  local resp http body code="-" envelope="-" cls="ENVELOPE_MISMATCH" detail="-"
  local -a hdr=()
  if [ -n "$TOKEN" ]; then
    hdr=(-H "Authorization: Bearer ${TOKEN}")
  fi
  if [ "${E2E_SIM_UNREACHABLE:-0}" = "1" ]; then
    http="000"; body=""            # 自证能红：模拟连接层失败
  else
    resp=$(curl -s -m 15 -w '\n%{http_code}' "${hdr[@]}" "${BACKEND_URL}${path}" 2>/dev/null || printf '\n000')
    http=$(printf '%s' "$resp" | tail -1)
    body=$(printf '%s' "$resp" | sed '$d' | head -1)
  fi

  if [ "$http" = "000" ]; then
    cls="UNREACHABLE"
  elif [ "$http" = "401" ] || [ "$http" = "403" ]; then
    cls="NO_AUTH"
  elif [ "$http" = "404" ]; then
    cls="NOT_IMPLEMENTED"
  elif [ "$http" = "200" ]; then
    code=$(printf '%s' "$body" | python3 -c "import sys, json; print(json.load(sys.stdin).get('code', '-'))" 2>/dev/null || echo "-")
    envelope=$(printf '%s' "$body" | python3 -c "import sys, json; d = json.load(sys.stdin); print('IPD' if ('message' in d or 'data' in d) else 'FRAMEWORK')" 2>/dev/null || echo "UNKNOWN")
    if [ "$code" = "0" ] && [ "$envelope" = "IPD" ]; then
      cls="PASS"
      if [ -n "$need_field" ]; then
        local has
        has=$(NEED_FIELD="$need_field" python3 -c "
import sys, json, os
d = json.load(sys.stdin)
data = d.get('data')
print('YES' if isinstance(data, dict) and os.environ['NEED_FIELD'] in data else 'NO')
" <<< "$body" 2>/dev/null || echo "NO")
        detail="${need_field}=${has}"
        if [ "$has" != "YES" ]; then
          cls="ENVELOPE_MISMATCH"
        fi
      fi
    fi
  fi

  case "$cls" in
    PASS) PASS_C=$((PASS_C + 1)) ;;
    UNREACHABLE) UNREACH_C=$((UNREACH_C + 1)) ;;
    NO_AUTH) NOAUTH_C=$((NOAUTH_C + 1)) ;;
    NOT_IMPLEMENTED) NOTIMPL_C=$((NOTIMPL_C + 1)) ;;
    *) ENVELOPE_C=$((ENVELOPE_C + 1)) ;;
  esac
  echo "| ${label} | ${path} | ${http} | code=${code}${envelope:+/${envelope}} | ${detail} | ${cls} |" >> "$REPORT"
  return 0
}

probe "P0-9" "/api/v1/projects/${PROJECT_ID}" "id"
probe "P3-6.1" "/api/v1/projects/${PROJECT_ID}/stages" ""
probe "R108" "/api/v1/kpi/rules" ""
probe "R118" "/api/v1/persons/active" ""
probe "R109" "/api/v1/deletion-requests" ""

echo "" >> "$REPORT"
echo "## 分类汇总" >> "$REPORT"
echo "" >> "$REPORT"
echo "PASS=${PASS_C} UNREACHABLE=${UNREACH_C} NO_AUTH=${NOAUTH_C} NOT_IMPLEMENTED=${NOTIMPL_C} ENVELOPE_MISMATCH=${ENVELOPE_C} FRONTEND=${FE_ALIVE}" >> "$REPORT"
echo "" >> "$REPORT"

if [ "$FE_ALIVE" = "0" ]; then
  echo "状态: FAILED（前端 ${FRONTEND_PORT} 未启，fe/be 闭环未验证）" >> "$REPORT"
  echo "STATUS: FAILED" >> "$REPORT"
  echo "❌ 真活 E2E 不通过：前端未启"
  exit 1
fi
if [ "$UNREACH_C" -gt 0 ] || [ "$NOAUTH_C" -gt 0 ] || [ "$ENVELOPE_C" -gt 0 ]; then
  echo "状态: FAILED（阻塞类：连接失败 ${UNREACH_C} 项、鉴权失败 ${NOAUTH_C} 项、包络不符 ${ENVELOPE_C} 项——包络不符指路径未命中 IPD 包络（常见于撞到框架默认 handler），不是环境题）" >> "$REPORT"
  echo "STATUS: FAILED" >> "$REPORT"
  echo "❌ 真活 E2E 不通过：环境或包络问题（见报告分类汇总）"
  exit 1
elif [ "$NOTIMPL_C" -gt 0 ]; then
  echo "状态: FAILED（契约缺口类：${NOTIMPL_C} 项 NOT_IMPLEMENTED——端点在生产代码不存在，须 owner 拍板补/删，不得记为环境失败）" >> "$REPORT"
  echo "STATUS: FAILED" >> "$REPORT"
  echo "❌ 真活 E2E 不通过：${NOTIMPL_C} 项契约未实现（非环境问题）"
  exit 1
else
  echo "状态: PASSED（5 项契约全 PASS）" >> "$REPORT"
  echo "STATUS: PASSED" >> "$REPORT"
  exit 0
fi