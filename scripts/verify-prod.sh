#!/usr/bin/env bash
# scripts/verify-prod.sh —— IPD 生产系统级验收（2026-09-11 PLAN-ROOT-3）
# 验证项：HTTP 活体 / 鉴权包络 / 关键端点 / 审计链 / DB 探针 / Redis 探针
# 退出码 0 = 全绿；非 0 = 有失败项

set -uo pipefail

cd "$(dirname "$0")/.."

BACKEND_URL=${BACKEND_URL:-http://127.0.0.1:16039}
DB_HOST=${IPD_DB_HOST:-127.0.0.1}
DB_PORT=${IPD_DB_PORT:-13306}
DB_NAME=${IPD_DB_NAME:-ipd_dev}
PASS=0
FAIL=0

green() { printf "\033[32m%s\033[0m\n" "$1"; }
red()   { printf "\033[31m%s\033[0m\n" "$1"; }
yellow(){ printf "\033[33m%s\033[0m\n" "$1"; }

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then
    green "  [PASS] $name"
    PASS=$((PASS+1))
  else
    red "  [FAIL] $name"
    FAIL=$((FAIL+1))
  fi
}

echo "=========================================="
echo "  IPD 生产系统级验收 ($(date '+%F %T'))"
echo "  Backend: $BACKEND_URL"
echo "  DB: $DB_HOST:$DB_PORT/$DB_NAME"
echo "=========================================="
echo

# 真实 Person 会话由 IPD_GATE_TOKEN 环境传入（裸 token 或完整 Bearer 头均可）；不打印、不落盘，不把 401 当业务通过。
export BACKEND_URL
echo "── 1. 当前 Person 身份 ──"
check "GET /api/v1/auth/me HTTP200 + code0" python3 scripts/lib/prod-http-probe.py /api/v1/auth/me

echo "── 2. 业务接口与审计链 ──"
check "工作台 HTTP200 + code0" python3 scripts/lib/prod-http-probe.py /api/v1/workbench/summary
check "项目列表 HTTP200 + code0" python3 scripts/lib/prod-http-probe.py /api/v1/projects
check "审计链真实验证 OK" python3 scripts/lib/prod-http-probe.py /api/v1/audit-logs/verify audit

# ─── 3. DB 探针 ───
echo "── 3. DB 连通与表数探针（不证明种子、约束或权限）──"
MYSQL_CMD="/opt/homebrew/opt/mysql-client/bin/mysql"
if [ -f .codex/ipd-dev/config/mysql-client.cnf ]; then
  TBL_CNT=$($MYSQL_CMD --defaults-extra-file=.codex/ipd-dev/config/mysql-client.cnf -N -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB_NAME';" 2>/dev/null)
else
  TBL_CNT=$(MYSQL_PWD="${IPD_DB_PASSWORD:-}" $MYSQL_CMD -h "$DB_HOST" -P "$DB_PORT" -u "${IPD_DB_USER:-ipd_app}"  "$DB_NAME" -N -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$DB_NAME';" 2>/dev/null)
fi
if [ -n "$TBL_CNT" ] && [ "$TBL_CNT" -ge 140 ]; then
  green "  [PASS] $DB_NAME 表数 = $TBL_CNT (>=140 期望)"
  PASS=$((PASS+1))
else
  red "  [FAIL] $DB_NAME 表数 = ${TBL_CNT:-无连接} (期望 >=140)"
  FAIL=$((FAIL+1))
fi

echo

# ─── 5. Redis 探针 ───
echo "── 5. Redis 探针 ──"
if docker exec $(docker-compose ps -q redis 2>/dev/null) redis-cli ping 2>/dev/null | grep -q PONG; then
  green "  [PASS] redis PONG"
  PASS=$((PASS+1))
else
  red "  [FAIL] redis 容器未起或无 ping，生产依赖未验证"
  FAIL=$((FAIL+1))
fi

echo
echo "=========================================="
echo "  结果：PASS=$PASS  FAIL=$FAIL"
echo "=========================================="

exit $FAIL
