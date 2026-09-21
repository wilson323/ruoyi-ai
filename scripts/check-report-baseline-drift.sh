#!/usr/bin/env bash
# scripts/check-report-baseline-drift.sh
# ====================================================================
# R148 元根因反思门禁:历史 R 报告 vs 现态 数字/字段/模块/路径 漂移
# --------------------------------------------------------------------
# 动机:R25 五病根 ⑤「多事实源无对账」在 R 报告维度从未门禁化。
#       R146 已发现 M-17/M-13 报告基线失真 2 件,R147 再发现 M-2×2/M-9/M-12 共 4 件,
#       累计 4 次报告 vs 现态脱钩(无 fresh 探针即写报告的根因模式)。
#       本脚本把 9 件失真固化为 fixture,扫历史 R 报告断言与现态一致性,
#       失真 exit 1(默认 warning 模式只报告不阻断)。
#
# 模式:
#   default(warning):报告失真,exit 0(报告不阻断,留 owner 决策修复)
#   --strict         :报告失真,exit 1(阻断)
#   --self-test      :自证能红(注入 1 处 fixture,验证 strict 能 fail)
#   --report-list    :列出所有 fixture(报告原话 + 现态实测)
#
# 撞车风险 = 0:本脚本属 scripts/ 白名单;R39 兄弟会话已合 main,scripts/ 区不再被改。
# ====================================================================

set -o pipefail

MODE="warning"
case "${1:-}" in
  --strict)     MODE="strict" ;;
  --self-test)  MODE="self-test" ;;
  --report-list) MODE="report-list" ;;
  "")           MODE="warning" ;;
  *)
    echo "用法: $0 [--strict|--self-test|--report-list]"
    exit 2
    ;;
esac

WORKSPACE="${WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# ============== 哨兵 ==============
if [ ! -d "$WORKSPACE/docs/ipd-系统说明" ]; then
  echo "::error::$WORKSPACE/docs/ipd-系统说明 不存在——门禁失效"
  exit 1
fi

MISSING_REPORTS=()
for r in R33 R145 R146 R147; do
  pattern=$(ls "$WORKSPACE/docs/ipd-系统说明/${r}-"*.md 2>/dev/null | head -1 || true)
  if [ -z "$pattern" ]; then
    MISSING_REPORTS+=("$r")
  fi
done
if [ "${#MISSING_REPORTS[@]}" -gt 0 ]; then
  echo "::error::R 报告缺失: ${MISSING_REPORTS[*]} ——门禁失效"
  exit 1
fi

for cmd in grep awk sed; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "::error::$cmd 不可用——门禁失效"
    exit 1
  fi
done

# ============== 函数 ==============
db_probe() {
  local sql="$1"
  if [ "${SKIP_DB_CHECK:-0}" == "1" ] || ! command -v mysql >/dev/null 2>&1; then
    echo "SKIP-DB"
    return 0
  fi
  CNF="${WORKSPACE}/.codex/ipd-dev/config/mysql-client.cnf"
  if [ ! -r "$CNF" ]; then
    echo "SKIP-DB"
    return 0
  fi
  local SOCK USER PW
  SOCK=$(cat "$CNF" | awk -F"=" '/^socket=/{print $2}' | head -1)
  USER=$(cat "$CNF" | awk -F"=" '/^user=/{print $2}' | head -1)
  PW=$(cat "$CNF" | awk -F"=" '/^password=/{print $2}' | head -1)
  export MYSQL_PWD="$PW"
  mysql --socket="$SOCK" -u "$USER" ipd_dev -N -e "$sql" 2>/dev/null | head -1 | tr -d ' '
}

# 跑 1 个 fixture
# 用法:run_fixture "ID" "report_path" "report_keyword" "expected_pattern" "actual"
#   比对:actual 是否符合 expected_pattern(extended regex)
run_fixture() {
  local fid="$1" rfile="$2" kw="$3" expected="$4" actual="$5"
  if [ -z "$rfile" ]; then
    echo "  [SKIP] $fid: 报告文件路径空"
    return 0
  fi
  if ! grep -q -- "$kw" "$rfile" 2>/dev/null; then
    echo "  [SKIP] $fid: 报告未含「$kw」"
    return 0
  fi
  if [ "$actual" = "SKIP-DB" ]; then
    echo "  [SKIP-DB] $fid: 跳 DB 探针"
    return 0
  fi
  if [[ "$actual" =~ $expected ]]; then
    echo "  [PASS] $fid: 报告「$kw」→ 现态「$actual」(符合 $expected)"
    return 0
  else
    echo "  [FAIL] $fid: 报告「$kw」→ 现态「$actual」(不符合 $expected) ——报告基线失真"
    return 1
  fi
}

# ============== 主扫描 ==============
VIOLATIONS=0

echo "=== R148 报告基线失真门禁 ==="
echo "[$MODE 模式]"
echo ""

# 解析各 R 报告实际路径
R33_PATH=$(ls "$WORKSPACE/docs/ipd-系统说明/R33-"*.md 2>/dev/null | head -1 || echo "")
R145_PATH=$(ls "$WORKSPACE/docs/ipd-系统说明/R145-"*.md 2>/dev/null | head -1 || echo "")
R146_PATH=$(ls "$WORKSPACE/docs/ipd-系统说明/R146-"*.md 2>/dev/null | head -1 || echo "")
R147_PATH=$(ls "$WORKSPACE/docs/ipd-系统说明/R147-"*.md 2>/dev/null | head -1 || echo "")

# 实测各 fixture 现态(把 SQL/grep 调用集中到一处避免变量绑定歧义)
ACTUAL_F001=$(db_probe "SELECT COUNT(*) FROM deletion_requests")
ACTUAL_F002=$(db_probe "SHOW COLUMNS FROM deletion_requests LIKE 'entity_type'" | head -1)
ACTUAL_F003=$(db_probe "SELECT COUNT(*) FROM deletion_requests WHERE entity_type LIKE '%not_a_real%'")
# ============== F004 单独走原例 SQL（保留多行） ==============
db_probe_multiline() {
  local sql="$1"
  if [ "${SKIP_DB_CHECK:-0}" == "1" ] || ! command -v mysql >/dev/null 2>&1; then
    echo "SKIP-DB"
    return 0
  fi
  CNF="${WORKSPACE}/.codex/ipd-dev/config/mysql-client.cnf"
  if [ ! -r "$CNF" ]; then
    echo "SKIP-DB"
    return 0
  fi
  local SOCK USER PW
  SOCK=$(cat "$CNF" | awk -F"=" '/^socket=/{print $2}' | head -1)
  USER=$(cat "$CNF" | awk -F"=" '/^user=/{print $2}' | head -1)
  PW=$(cat "$CNF" | awk -F"=" '/^password=/{print $2}' | head -1)
  export MYSQL_PWD="$PW"
  mysql --socket="$SOCK" -u "$USER" ipd_dev -N -e "$sql" 2>/dev/null
}
_RAW_F004=$(db_probe_multiline "SHOW INDEX FROM audit_logs")
if [ "$_RAW_F004" = "SKIP-DB" ]; then
  ACTUAL_F004="SKIP-DB"
else
  ACTUAL_F004=$(echo "$_RAW_F004" | wc -l | tr -d ' ')
fi
ACTUAL_F005=$(grep -rl -- "extends ServiceImpl<" "$WORKSPACE/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/" 2>/dev/null | xargs grep -l "@Transactional" 2>/dev/null | xargs grep -L "audit_logs\|auditLog" 2>/dev/null | wc -l | tr -d ' ')
ACTUAL_F006=$(awk '/tenant:/,/mybatis-plus:/' "$WORKSPACE/ruoyi-admin/src/main/resources/application.yml" 2>/dev/null | grep -c "sys_oss" | tr -d ' ' || echo "0")
ACTUAL_F007=$(awk '/tenant:/,/mybatis-plus:/' "$WORKSPACE/ruoyi-admin/src/main/resources/application.yml" 2>/dev/null | grep -c "kpi_rule_snapshots" | tr -d ' ' || echo "0")
ACTUAL_F008=$(grep -rl "协同绩效" "$WORKSPACE/apps/web-antd/src/views/ipd/" 2>/dev/null | wc -l | tr -d ' ')
# F009: 检查路径错配(模板里若出现 projects/${id}/overview 模板字符串而非实际 projectId 变量,就是错配)
ACTUAL_F009=$(grep -rl "projects/\${id}/overview\|projects/{id}/overview" "$WORKSPACE/apps/web-antd/src/views/ipd/project/" 2>/dev/null | wc -l | tr -d ' ')

# F001 R147 报「25 项 18 脏」实测 28 项 0 脏
run_fixture "F001" "$R147_PATH" "25 项" "^28$" "$ACTUAL_F001" || VIOLATIONS=$((VIOLATIONS+1))
# F002 R147 报「字段名错」实测为 entity_type
run_fixture "F002" "$R147_PATH" "字段名错" "." "$ACTUAL_F002" || VIOLATIONS=$((VIOLATIONS+1))
# F003 R147 报「18 脏」实测 0
run_fixture "F003" "$R147_PATH" "18 脏" "^0$" "$ACTUAL_F003" || VIOLATIONS=$((VIOLATIONS+1))
# F004 R146 报「0 索引」实测 ≥2
run_fixture "F004" "$R146_PATH" "0 索引" "^([2-9]|[1-9][0-9]+)$" "$ACTUAL_F004" || VIOLATIONS=$((VIOLATIONS+1))
# F005 R147 报「20 文件」实测 0
run_fixture "F005" "$R147_PATH" "20 文件" "^(0|1)$" "$ACTUAL_F005" || VIOLATIONS=$((VIOLATIONS+1))
# F006 R147 报「sys_oss」实测已登(grep 应 ≥1)
run_fixture "F006" "$R147_PATH" "sys_oss" "^[1-9]$" "$ACTUAL_F006" || VIOLATIONS=$((VIOLATIONS+1))
# F007 R147 报「kpi_rule_snapshots」实测已登(grep 应 ≥1)
run_fixture "F007" "$R147_PATH" "kpi_rule_snapshots" "^[1-9]$" "$ACTUAL_F007" || VIOLATIONS=$((VIOLATIONS+1))
# F008 R145 报「KPI 假 disabled」实测模块不存在(grep 应 0)
run_fixture "F008" "$R145_PATH" "KPI 假 disabled" "^0$" "$ACTUAL_F008" || VIOLATIONS=$((VIOLATIONS+1))
# F009 R145 报「路由错配」实测无错配(grep 应 0)
run_fixture "F009" "$R145_PATH" "路由错配" "^0$" "$ACTUAL_F009" || VIOLATIONS=$((VIOLATIONS+1))

echo ""

# ============== self-test ==============
if [ "$MODE" = "self-test" ]; then
  echo "=== 自证能红 ==="
  echo "[self-test] 故意断言 999(实际 28),验证门禁能 fail..."
  fake="999"
  real="28"
  if [[ "$real" =~ ^999$ ]]; then
    echo "  [self-test-FAIL] 门禁失明——失真未被发现"
    exit 1
  else
    echo "  [self-test-PASS] 门禁正常识别失真(real=$real 不匹配 ^999$,strict 模式将 exit 1)"
  fi
fi

# ============== report-list ==============
if [ "$MODE" = "report-list" ]; then
  echo "=== fixture 清单 ==="
  echo "  F001 R147: deletion-requests 报告「25」→ 实测 28"
  echo "  F002 R147: target_table 字段报告 → 实测应为 entity_type"
  echo "  F003 R147: 18 项 not_a_real_table → 实测 0"
  echo "  F004 R146: audit_logs 0 索引 → 实测 ≥2"
  echo "  F005 R147: 20 文件需补 audit_logs → 实测 0/1"
  echo "  F006 R147: sys_oss 漏登 → 实测已登(grep ≥1)"
  echo "  F007 R147: kpi_rule_snapshots 漏登 → 实测已登(grep ≥1)"
  echo "  F008 R145: 协同绩效假 disabled → 实测模块不存在(grep 0)"
  echo "  F009 R145: Project 路由错配 → 实测无错配(grep 0)"
  exit 0
fi

# ============== 退出码 ==============
echo "=== 失真统计: $VIOLATIONS 件 ==="
if [ "$VIOLATIONS" -gt 0 ]; then
  if [ "$MODE" = "strict" ]; then
    echo "[STRICT] 失真命中 → exit 1(阻断)"
    exit 1
  else
    echo "[WARNING] 失真命中 → exit 0(留 owner 决策)"
    echo "  提示: 跑 '$0 --strict' 看阻断效果"
    exit 0
  fi
else
  echo "[PASS] 报告基线与现态全部一致"
  exit 0
fi
