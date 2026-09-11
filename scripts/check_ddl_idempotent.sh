#!/usr/bin/env bash
# scripts/check_ddl_idempotent.sh
# R30+ 治理门禁：DDL 迁移脚本必须幂等，否则多会话共工重放会漏对象。
# 背景：仓库无 Flyway/Liquibase，"兄弟会话已 apply"导致裸 ALTER/CREATE 重放即 1050/1060 报错，
#      后续语句全部失效（含真正缺的对象）。

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT" || exit 2

echo "[check_ddl_idempotent]"

# Sentinel: scan path integrity
SQL_FILES=$(find docs/script/sql/update -name "*.sql" -not -path "*/.claude/worktrees/*" 2>/dev/null)
SQL_COUNT=$(printf '%s\n' "$SQL_FILES" | wc -l | tr -d ' ')
echo "Scan found $SQL_COUNT SQL migration files (sentinel >= 5)"
[ "$SQL_COUNT" -lt 5 ] && { echo "ERROR: scan path wrong"; exit 3; }

problems=0
warnings=0

# Patterns that REQUIRE information_schema guard
# MySQL 8 does NOT support IF NOT EXISTS for ALTER TABLE ADD COLUMN
# NOTE: MODIFY / CHANGE COLUMN alone is idempotent (absolute value set); see below.
# NOTE: macOS BSD grep does NOT support PCRE lookahead (?!), so exclusions
#       (IF NOT EXISTS / INSERT IGNORE) are handled by two-step grep -v below.
NON_IDEM_ALTER_HINT='(ADD[[:space:]]+(COLUMN|INDEX|CONSTRAINT|UNIQUE|PRIMARY|FOREIGN|KEY)|DROP[[:space:]]+(COLUMN|INDEX|CONSTRAINT|PRIMARY|FOREIGN|KEY))'

GUARD_PATTERNS=(
  'information_schema\.COLUMNS'
  'information_schema\.TABLES'
  'information_schema\.STATISTICS'
  'IF[[:space:]]+NOT[[:space:]]+EXISTS'
  'CREATE[[:space:]]+TABLE[[:space:]]+IF[[:space:]]+NOT[[:space:]]+EXISTS'
)

# Rollback scripts are exempt (their job is to be destructive)
is_rollback() {
  case "$1" in
    *rollback*|*rollback_*) return 0 ;;
    *) return 1 ;;
  esac
}

# Single transaction wrap = acceptable idempotent form
HAS_TX_WRAP='^[[:space:]]*SET[[:space:]]+AUTOCOMMIT[[:space:]]*=[[:space:]]*0'

while IFS= read -r f; do
  [ -z "$f" ] && continue
  is_rollback "$f" && { echo "  SKIP: $f (rollback)"; continue; }

  # Strip SQL comments to reduce false positives
  CONTENT=$(grep -vE '^[[:space:]]*(--|#)' "$f" | tr -s ' ')

  # Short-circuit: only count genuinely non-idempotent forms.
  # Two-step grep -v for exclusions (BSD grep lacks PCRE lookahead):
  #   CREATE TABLE ... IF NOT EXISTS  → excluded
  #   INSERT IGNORE INTO ...          → excluded
  HAS_NON_IDEM_ALTER=$(printf '%s' "$CONTENT" | grep -cE "$NON_IDEM_ALTER_HINT" || true)
  HAS_CREATE_TBL=$(printf '%s' "$CONTENT" | grep -E 'CREATE[[:space:]]+TABLE' | grep -cvE 'IF[[:space:]]+NOT[[:space:]]+EXISTS' || true)
  HAS_CREATE_IDX=$(printf '%s' "$CONTENT" | grep -cE 'CREATE[[:space:]]+(UNIQUE[[:space:]]+)?INDEX' || true)
  HAS_INSERT=$(printf '%s' "$CONTENT" | grep -E 'INSERT[[:space:]]+INTO' | grep -cvE 'INSERT[[:space:]]+IGNORE' || true)

  TOTAL_NON_IDEM=$((HAS_NON_IDEM_ALTER + HAS_CREATE_TBL + HAS_CREATE_IDX + HAS_INSERT))
  [ "$TOTAL_NON_IDEM" -eq 0 ] && continue

  # Check for guard
  HAS_GUARD=0
  for gpat in "${GUARD_PATTERNS[@]}"; do
    if printf '%s' "$CONTENT" | grep -qiE "$gpat"; then
      HAS_GUARD=1
      break
    fi
  done

  # Build summary of what triggered
  DDL_HITS="ALTER_non_idem=$HAS_NON_IDEM_ALTER CREATE_TABLE=$HAS_CREATE_TBL CREATE_INDEX=$HAS_CREATE_IDX INSERT=$HAS_INSERT"

  if [ "$HAS_GUARD" -eq 0 ]; then
    echo "  FAIL: $f"
    echo "    DDL without idempotent guard. First hit: $(echo "$DDL_HITS" | head -c 200)"
    echo "    Fix: wrap with information_schema.* check + prepare stmt (see docs/script/sql/update/2026-09-07-ipd-drift-backfill-entity-gap.sql)"
    problems=$((problems + 1))
  else
    echo "  OK: $f (guarded)"
  fi
done <<< "$SQL_FILES"

echo
echo "==== Summary ===="
echo "  Non-idempotent migrations: $problems"
echo "  (Rollback scripts excluded; auto-guarded migrations not counted)"

[ "$problems" -gt 0 ] && {
  echo
  echo "FAIL: DDL migration scripts must be idempotent"
  echo "  Why: multi-session replay will fail on already-applied objects"
  echo "  Fix template:"
  echo "    SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS"
  echo "      WHERE TABLE_SCHEMA='ipd_dev' AND TABLE_NAME='<t>' AND COLUMN_NAME='<c>');"
  echo "    SET @ddl := IF(@col_exists=0, 'ALTER TABLE ...', 'SELECT \"exists\" AS msg');"
  echo "    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;"
  exit 1
}

echo
echo "OK: all DDL migrations are idempotent or rollback-only"
exit 0