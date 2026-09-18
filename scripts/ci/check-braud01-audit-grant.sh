#!/usr/bin/env bash
# IPD BR-AUD-01 静态门禁——audit_logs 表 GRANT 限制登记件完整性检查
# ----------------------------------------------------------------------------
# 为什么需要这道门（2026-09-17 fresh 抽测发现）：
#   产品圣经 §12.1 BR-AUD-01 明确要求 ipd_app 对 audit_logs 仅 INSERT 权限。
#   DEF-5 治理报告 2026-09-07 owner 拍板 PROPOSAL-01 脚本兜底（task_id=
#   081fbd58-de76-4222-a7ae-22b59faa464f），DCL 草稿已 commit 到
#   docs/ipd-系统说明/治理轮/DEF-5/PROPOSAL-01-脚本兜底.sql，但 R-NEW 反思
#   报告未识别这条产品圣经红线缺自动化门禁的盲点——本脚本补齐。
#
# 检查语义（CI 友好静态扫描，不连真库）：
#   扫 docs/script/sql/update/*.sql，必须同时满足：
#     ① 至少 1 条活语句 REVOKE INSERT.*FROM 'ipd_app'.*audit_logs
#        （库级 INSERT 兜底收回，纵深防御链 anchor 不可丢）
#     ② 至少 1 条活语句 REVOKE (UPDATE,?DELETE|UPDATE|DELETE).*FROM 'ipd_app'.*audit_logs
#        （与 commit e9e6631d Q6 配套，覆盖 UPDATE/DELETE 库级收回）
#     ③ 不允许活语句 GRANT (INSERT,)?UPDATE,?DELETE.*TO.*'ipd_app'.*audit_logs
#        （禁止复原 UPDATE/DELETE 全开，与 Q6 红线冲突）
#   注释行（-- 起头）不计入有效线索。
#
# 用法：
#   bash scripts/ci/check-braud01-audit-grant.sh                # 检查仓库现状
#   bash scripts/ci/check-braud01-audit-grant.sh --selftest     # 门禁自测
#
# 退出码：
#   0 = OK / 1 = FAIL（缺任一必备语句或检出违规授权）
# ----------------------------------------------------------------------------

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
SQL_DIR="$REPO/docs/script/sql/update"
TABLE="audit_logs"
USER_PAT="ipd_app"

red() { printf "\033[31m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }

# ---------- 1. 输入层哨兵 ----------
[ -d "$SQL_DIR" ] || { red "⛔ FAIL: SQL 目录不存在: $SQL_DIR"; exit 1; }

shopt -s nullglob
SQL_FILES=( "$SQL_DIR"/*.sql )
shopt -u nullglob

# 解析层哨兵: 至少要扫到一定数量 SQL 文件（防误跑目录）
[ "${#SQL_FILES[@]} -ge 50" ] || {
  red "⛔ FAIL: SQL 文件数异常 (${#SQL_FILES[@]} < 50)，可能路径错或目录被清"
  exit 1
}

# ---------- 2. 抽取活语句 (过滤注释行) ----------
# 压平空白 + 过滤 -- 注释行, 留下真正的活 SQL 块
strip_comments() {
  python3 - "$1" <<'PY'
import sys, re
raw = open(sys.argv[1], encoding='utf-8', errors='replace').read()
# 删除整行注释
lines = raw.split('\n')
out = []
for ln in lines:
        s = ln.strip()
        if s.startswith('--'):
                continue
        # 删行内注释（最简：找 -- 截断）
        idx = ln.find('--')
        if idx >= 0:
                ln = ln[:idx]
        out.append(ln)
flat = re.sub(r'\s+', ' ', '\n'.join(out))
# 多语句以分号断开
for stmt in re.split(r';\s*', flat):
        s = stmt.strip()
        if s:
                print(s + ';')
PY
}

LIVE_STMTS=()
for f in "${SQL_FILES[@]}"; do
  while IFS= read -r stmt; do
    LIVE_STMTS+=( "$stmt" )
  done < <(strip_comments "$f")
done

# ---------- 3. 业务规则判定 ----------
FAIL_REASONS=()

# 规则① 必须有 REVOKE INSERT 库级收回 (audit_logs + ipd_app)
has_revoke_insert_lib=0
for s in "${LIVE_STMTS[@]}"; do
  if [[ "$s" =~ REVOKE[[:space:]]+INSERT[[:space:]]+ON[[:space:]]+ipd_dev\.${TABLE}[[:space:]]+FROM[[:space:]]+\'${USER_PAT} ]] \
     || [[ "$s" =~ REVOKE[[:space:]]+INSERT[[:space:]]+ON[[:space:]]+ipd_dev\.${TABLE}[[:space:]]+FROM[[:space:]]+${USER_PAT} ]]; then
        has_revoke_insert_lib=1
        break
  fi
done
[ "$has_revoke_insert_lib" -eq 1 ] || FAIL_REASONS+=( "缺活语句: REVOKE INSERT ON ipd_dev.${TABLE} FROM '${USER_PAT}'" )

# 规则② 必须有 REVOKE UPDATE/DELETE 库级收回 (audit_logs + ipd_app)
has_revoke_ud_lib=0
for s in "${LIVE_STMTS[@]}"; do
  if [[ "$s" =~ REVOKE[[:space:]]+(UPDATE[[:space:]]*,[[:space:]]*DELETE|UPDATE|DELETE)[[:space:]]+ON[[:space:]]+ipd_dev\.${TABLE}[[:space:]]+FROM[[:space:]]+\'${USER_PAT} ]] \
     || [[ "$s" =~ REVOKE[[:space:]]+(UPDATE[[:space:]]*,[[:space:]]*DELETE|UPDATE|DELETE)[[:space:]]+ON[[:space:]]+ipd_dev\.${TABLE}[[:space:]]+FROM[[:space:]]+${USER_PAT} ]]; then
        has_revoke_ud_lib=1
        break
  fi
done
[ "$has_revoke_ud_lib" -eq 1 ] || FAIL_REASONS+=( "缺活语句: REVOKE UPDATE, DELETE ON ipd_dev.${TABLE} FROM '${USER_PAT}' (与 e9e6631d Q6 配套)" )

# 规则③ 禁止复原授权 (audit_logs + UPDATE/DELETE 三件套给 ipd_app)
has_violating_grant=0
for s in "${LIVE_STMTS[@]}"; do
  if [[ "$s" =~ GRANT[[:space:]]+.*(UPDATE|DELETE)[[:space:]]+ON[[:space:]]+ipd_dev\.${TABLE}[[:space:]]+TO[[:space:]]+\'${USER_PAT} ]]; then
        has_violating_grant=1
        echo "  ⚠️ 检出违规授权: ${s:0:200}..."
        break
  fi
done
[ "$has_violating_grant" -eq 0 ] || FAIL_REASONS+=( "检出违规活语句: GRANT UPDATE/DELETE ON audit_logs TO ipd_app (与 BR-AUD-01 红线冲突)" )

# ---------- 4. 输出 ----------
if [ "${#FAIL_REASONS[@]}" -gt 0 ]; then
  red "⛔ FAIL: BR-AUD-01 audit_logs GRANT 限制登记件缺失/违规 (${#FAIL_REASONS[@]} 项)"
  for r in "${FAIL_REASONS[@]}"; do
        echo "  - $r"
  done
  echo ""
  yellow "修复模板（拷贝到 docs/script/sql/update/<日期>-ipd-braud01-audit-grant-restrict.sql）:"
  cat <<'TPL'

-- 1. 收回 audit_logs 库级 INSERT（应用业务已走表级 GRANT，仍可写）
REVOKE INSERT ON ipd_dev.audit_logs FROM 'ipd_app'@'127.0.0.1';

-- 2. 收回 audit_logs 库级 UPDATE/DELETE（与 commit e9e6631d Q6 配套）
REVOKE UPDATE, DELETE ON ipd_dev.audit_logs FROM 'ipd_app'@'127.0.0.1';

-- 3. audit_log_chain_heads 同步收敛（GLOBAL 锚点防伪造）
REVOKE INSERT ON ipd_dev.audit_log_chain_heads FROM 'ipd_app'@'127.0.0.1';
REVOKE UPDATE, DELETE ON ipd_dev.audit_log_chain_heads FROM 'ipd_app'@'127.0.0.1';
TPL
  exit 1
fi

green "✅ OK: BR-AUD-01 audit_logs GRANT 限制登记件齐全"
echo "  - REVOKE INSERT 库级收回: 命中"
echo "  - REVOKE UPDATE/DELETE 库级收回: 命中"
echo "  - 无违规 GRANT UPDATE/DELETE TO ipd_app 复原授权"
exit 0