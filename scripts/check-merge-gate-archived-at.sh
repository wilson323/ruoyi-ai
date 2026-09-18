#!/usr/bin/env bash
# R42-D: 扫真库 ipd_dev status='ARCHIVED' 但 archived_at IS NULL 的行
# ====================================================================
# 动机:R35 cleanup + R36 C1 archived_at 回填后发现 archived_at 一致性
#       是高频遗漏项(R34 P0-3 报告 11 条 + R36 C1 单独回填 9140004)。
#       merge gate 第 8 项 = PR 合入前必须 archived_at 全一致。
#
# 模式:
#   default(warning):报告违规,exit 0(不阻断 PR,留 owner 决策修复)
#   --strict         :报告违规,exit 1(阻断 PR 合入)
#   --self-test      :自证能红(检查脚本本身能 fail,门禁失效自检)
#
# 与既有 CI 的关系：仅新增,不修改既有 workflow / merge gate。

set -euo pipefail

MODE="warning"
case "${1:-}" in
  --strict)    MODE="strict" ;;
  --self-test) MODE="self-test" ;;
  "")          MODE="warning" ;;
  *)
    echo "用法: $0 [--strict|--self-test]"
    echo "  default:  warning 模式,exit 0(报告不阻断)"
    echo "  --strict: 阻断模式,违规 exit 1"
    echo "  --self-test: 自证能红"
    exit 2
    ;;
esac

WORKSPACE="${WORKSPACE:-$(pwd)}"

# 哨兵 1:MySQL 客户端必须存在(扫描工具错位自检)
MYSQL_BIN="${MYSQL_BIN:-/opt/homebrew/bin/mysql}"
if [ ! -x "$MYSQL_BIN" ]; then
  echo "::error::$MYSQL_BIN 不存在或不可执行,扫描工具错位——门禁失效"
  exit 1
fi

# 哨兵 2:mysql-client.cnf 必须存在(连接配置错位自检)
MYSQL_CNF="$WORKSPACE/.codex/ipd-dev/config/mysql-client.cnf"
if [ ! -f "$MYSQL_CNF" ]; then
  echo "::error::$MYSQL_CNF 不存在,连接配置错位——门禁失效"
  exit 1
fi

# 哨兵 3:必须能连真库(连接错位自检)
if ! "$MYSQL_BIN" --defaults-file="$MYSQL_CNF" ipd_dev -e "SELECT 1" >/dev/null 2>&1; then
  echo "::error::无法连接真库 ipd_dev@13306,连接错位——门禁失效"
  exit 1
fi

# 哨兵 4:必须找到至少 1 个目标表(扫描对象错位自检)
TABLES=("projects" "products" "zk_gate_projects" "zk_gate_products")
EXISTING_TABLES=()
for t in "${TABLES[@]}"; do
  if "$MYSQL_BIN" --defaults-file="$MYSQL_CNF" ipd_dev -N -e "SHOW TABLES LIKE '$t'" 2>/dev/null | grep -q "^${t}$"; then
    EXISTING_TABLES+=("$t")
  fi
done
if [ "${#EXISTING_TABLES[@]}" -lt 1 ]; then
  echo "::error::4 个目标表(projects/products/zk_gate_projects/zk_gate_products)都不存在,扫描对象错位——门禁失效"
  exit 1
fi

# 主扫描:status='ARCHIVED' AND archived_at IS NULL
VIOLATIONS=0
DETAILS=""
for t in "${EXISTING_TABLES[@]}"; do
  # 5a. 表是否有 archived_at 列(无列跳过)
  HAS_COL=$("$MYSQL_BIN" --defaults-file="$MYSQL_CNF" ipd_dev -N -e "
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema='ipd_dev' AND table_name='$t' AND column_name='archived_at'
  " 2>/dev/null | tr -d ' ')
  if [ "${HAS_COL:-0}" -eq 0 ]; then
    continue
  fi
  # 5b. 扫违规行数
  COUNT=$("$MYSQL_BIN" --defaults-file="$MYSQL_CNF" ipd_dev -N -e "
    SELECT COUNT(*) FROM $t WHERE status='ARCHIVED' AND archived_at IS NULL
  " 2>/dev/null | tr -d ' ')
  COUNT=${COUNT:-0}
  if [ "$COUNT" -gt 0 ]; then
    DETAILS="${DETAILS}  - $t: $COUNT 行 status='ARCHIVED' 但 archived_at IS NULL\n"
    VIOLATIONS=$((VIOLATIONS + COUNT))
    # 5c. 详情列前 5 行违规 id
    SAMPLE=$("$MYSQL_BIN" --defaults-file="$MYSQL_CNF" ipd_dev -N -e "
      SELECT id FROM $t WHERE status='ARCHIVED' AND archived_at IS NULL LIMIT 5
    " 2>/dev/null | tr '\n' ',' | sed 's/,$//')
    if [ -n "$SAMPLE" ]; then
      DETAILS="${DETAILS}    sample ids: $SAMPLE\n"
    fi
  fi
done

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "⚠️  发现 $VIOLATIONS 处 archived_at 一致性违规(详见下表):"
  echo -e "$DETAILS"
  echo ""
  echo "建议:对每行执行 archived_at = NOW() 回填,模板见:"
  echo "      docs/script/sql/update/2026-09-18-r36-archived-at-backfill-9140004.sql"
fi

case "$MODE" in
  strict)
    if [ "$VIOLATIONS" -gt 0 ]; then
      echo "❌ strict mode: 阻断(archived_at 不一致禁止合入)"
      exit 1
    fi
    echo "✅ strict mode: 无违规"
    exit 0
    ;;
  self-test)
    # 自证能红:如果没扫到任何违规,门禁无法 fail,反而是失效
    if [ "$VIOLATIONS" -eq 0 ]; then
      echo "❌ self-test FAIL: 0 处违规,strict 模式无 fail 触发点——门禁失效自检"
      echo "   排查:确认真库 4 表存在 + status='ARCHIVED' 行 + archived_at IS NULL"
      exit 1
    fi
    echo "✅ self-test PASS: 发现 $VIOLATIONS 处违规,strict 模式可 fail"
    echo "   验证触发: bash $0 --strict  → 应 exit 1"
    exit 0
    ;;
  warning)
    if [ "$VIOLATIONS" -gt 0 ]; then
      echo "✅ warning mode: exit 0(已报告 $VIOLATIONS 处违规,owner 决策修复后再切 strict)"
    else
      echo "✅ warning mode: 无违规"
    fi
    exit 0
    ;;
esac