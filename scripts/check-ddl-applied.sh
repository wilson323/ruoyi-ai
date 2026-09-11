#!/usr/bin/env bash
# scripts/check-ddl-applied.sh
# ----------------------------------------------------------------------
# R30+ 治理门禁：DDL commit 不等于真库生效防御。
# 封装 docs/script/sql/check-entity-db-drift.py，提供统一入口 + 退出码语义。
#
# 用法（在 ruoyi-ai 仓根目录）：
#   ./scripts/check-ddl-applied.sh                # 用 cnf socket 通道跑真库校验
#   ./scripts/check-ddl-applied.sh --docker       # 用 docker container 通道跑
#   ./scripts/check-ddl-applied.sh --static       # 不连真库，只跑 CI 同样的静态扫描（CI 友好）
#   ./scripts/check-ddl-applied.sh --module <m>   # 只校验指定模块（默认 ruoyi-ipd）
#   ./scripts/check-ddl-applied.sh --table <t>    # 只校验指定表
#
# 退出码：
#   0 = entity↔真库列对齐（或静态扫描无新增漂移提示）
#   1 = 检测到漂移（commit DDL 与真库列不一致 / 静态扫描发现新增漂移）
#   2 = 脚本/参数错误
#   3 = 真库不可达（cnf/docker 连不上）
#
# 背景与根因：见 .claude/skills/ipd-guard-ddl-apply/SKILL.md
# ----------------------------------------------------------------------
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MODE="cnf"            # cnf | docker | static
MODULE="ruoyi-ipd"
TABLE=""
PYTHON_BIN="${PYTHON_BIN:-python3}"

# ---- 解析参数 ----
while [ $# -gt 0 ]; do
  case "$1" in
    --docker)   MODE="docker"; shift ;;
    --static)   MODE="static"; shift ;;
    --cnf)      MODE="cnf"; shift ;;
    --module)   MODULE="$2"; shift 2 ;;
    --table)    TABLE="$2"; shift 2 ;;
    --python)   PYTHON_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,21p' "$0"
      exit 0
      ;;
    *)
      echo "ERROR: 未知参数 $1" >&2
      exit 2
      ;;
  esac
done

cd "$REPO_ROOT" || { echo "ERROR: cd repo root failed: $REPO_ROOT" >&2; exit 2; }

DRIFT_PY="docs/script/sql/check-entity-db-drift.py"
CNF=".codex/ipd-dev/config/mysql-client.cnf"
DB="ipd_dev"

if [ ! -f "$DRIFT_PY" ]; then
  echo "ERROR: drift 脚本不存在: $DRIFT_PY" >&2
  exit 2
fi

echo "▶ check-ddl-applied: mode=$MODE module=$MODULE table=${TABLE:-ALL}"
echo "▶ 仓根: $REPO_ROOT"

# ---- 静态扫描分支（CI 友好，不连真库）----
if [ "$MODE" = "static" ]; then
  echo "▶ 静态扫描：实体↔DDL 文件对齐（CI 模式，不连真库）"
  ENT_DIR="ruoyi-modules/$MODULE/src/main/java/org/ruoyi/${MODULE#ruoyi-}/domain"
  ENT_DIR="${ENT_DIR%/ipd/ipd}"  # 处理 ruoyi-ipd → ruoyi/ipd
  ENT_DIR="ruoyi-modules/$MODULE/src/main/java/org/ruoyi/ipd/domain"

  if [ ! -d "$ENT_DIR" ]; then
    echo "ERROR: 实体目录不存在: $ENT_DIR" >&2
    exit 2
  fi

  # 扫描器失效自检——门禁自己坏掉必须拦下
  ENT_COUNT=$(find "$ENT_DIR" -maxdepth 1 -name '*.java' | wc -l | tr -d ' ')
  echo "▶  实体文件数: $ENT_COUNT"
  if [ "$ENT_COUNT" -lt 10 ]; then
    echo "::error::实体文件数 < 10，扫描路径错位或模块未挂载——门禁失效，直接 fail"
    exit 1
  fi

  # 简易 DDL 覆盖检查
  if [ -d "docs/script/sql/update" ]; then
    MISSING=$(python3 - <<PY
import os, re
ent_dir = "$ENT_DIR"
sql_dir = "docs/script/sql/update"
tbl_pat = re.compile(r'@TableName\s*\(([^)]*)\)')
val_pat = re.compile(r'value\s*=\s*"(\w+)"')
bare_pat = re.compile(r'"(\w+)"')
tables = set()
for f in os.listdir(ent_dir):
    if f.endswith('.java'):
        am = tbl_pat.search(open(os.path.join(ent_dir, f), encoding='utf-8').read())
        if am:
            tm = val_pat.search(am.group(1)) or bare_pat.search(am.group(1))
            if tm: tables.add(tm.group(1))
sql_text = ''
for f in os.listdir(sql_dir):
    if f.endswith('.sql'):
        sql_text += open(os.path.join(sql_dir, f), encoding='utf-8').read() + '\n'
missing = [t for t in tables if t not in sql_text]
print('\n'.join(missing) if missing else '')
PY
    )
    if [ -n "$MISSING" ]; then
      echo "⛔ 静态扫描发现以下实体在 DDL update 目录中无迁移片段:"
      echo "$MISSING" | sed 's/^/  - /'
      echo
      echo "处置: docs/script/sql/update/ 添加 ALTER/CREATE 迁移后重跑"
      exit 1
    fi
  fi
  echo "✅ 静态扫描通过：所有实体在 docs/script/sql/update/ 中均有 DDL 覆盖"
  exit 0
fi

# ---- 真库校验分支 ----
DRIFT_ARGS=(--db "$DB")
case "$MODE" in
  cnf)
    if [ ! -f "$CNF" ]; then
      echo "ERROR: cnf 文件不存在: $CNF（不在 ipd-dev 配置下无法连接真库）" >&2
      exit 3
    fi
    DRIFT_ARGS+=(--cnf "$CNF")
    ;;
  docker)
    DRIFT_ARGS+=(--docker ruoyi-ai-mysql)
    ;;
  *)
    echo "ERROR: 未知 mode=$MODE" >&2
    exit 2
    ;;
esac

if [ -n "$TABLE" ]; then
  DRIFT_ARGS+=(--table "$TABLE")
fi

echo "▶ 真库校验: $PYTHON_BIN $DRIFT_PY ${DRIFT_ARGS[*]}"
echo

# 跑 drift 脚本（捕获退出码）
set +e
OUT=$("$PYTHON_BIN" "$DRIFT_PY" "${DRIFT_ARGS[@]}" 2>&1)
RC=$?
set -e

echo "$OUT"
echo
if [ "$RC" -eq 0 ]; then
  echo "✅ entity↔真库列对齐（无 drift）"
  exit 0
fi

# 区分"真库不可达"与"真有漂移"
if echo "$OUT" | grep -qiE "can't connect|Access denied|Unknown MySQL server|Connection refused|timeout"; then
  echo "❌ 真库不可达（mode=$MODE）。检查 cnf / docker 服务后再跑"
  exit 3
fi

echo "⛔ 检测到 drift：entity 字段↔真库列不一致"
echo
echo "修复指引："
echo "  1. 缺列 → docs/script/sql/update/ 加 ALTER TABLE ADD COLUMN（用幂等模板）"
echo "  2. 缺表 → 加 CREATE TABLE（含 BaseEntity 必备字段 + 索引 + 注释）"
echo "  3. apply 后跑此脚本验证 exit 0"
echo "  4. 详细规约见 .claude/skills/ipd-guard-ddl-apply/SKILL.md"
exit 1