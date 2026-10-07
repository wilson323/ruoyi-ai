#!/bin/bash
# scripts/find-thing.sh —— **查名字的唯一入口**（表名 / 列名 / 文件 / 类 / 接口）
#
# ============================================================================
# 为什么有这个脚本（2026-10-07 根除「拿没对准的尺子量」）
# ============================================================================
# 那天我反复犯同一个错：**先写下表名/列名/文件路径/类名，再去查**。
# 三次实测翻车：
#   1. 猜表名 `ipd_project` → 14 个表全「不存在」，真实表名是 `projects`（无前缀）
#   2. 猜列名 `endpoint_url` → 查询返回空，真实列名是 `api_host`
#   3. 猜文件路径 `.../ruoyi-ipd/.../KnowledgeAttachServiceImpl.java`
#      → 文件不存在，真实位置在 `ruoyi-chat` 模块下
#
# **共同点**：猜的成本是 0（直接写进命令），查的成本是一条命令。
# 所以「不查」永远是局部最优 —— **这不是态度问题，是成本结构问题**。
# 本脚本的作用就是**改变成本结构**：查名字从此是一条命令（不需要记得方法论），
# 而猜名字导致的「表不存在 / 列不存在 / 文件不存在」在**几秒内就会被本脚本抓到**。
#
# ============================================================================
# 用法
# ============================================================================
#   bash scripts/find-thing.sh table    <关键词>       # 查数据库表名
#   bash scripts/find-thing.sh column   <表名>         # 查列名
#   bash scripts/find-thing.sh file     <文件名关键词>  # 查文件真实路径
#   bash scripts/find-thing.sh class    <类名>         # 查类定义位置
#   bash scripts/find-thing.sh route    <路径片段>     # 查前端路由/菜单 path
#   bash scripts/find-thing.sh endpoint <路径片段>     # 查后端接口路径
#
# 全部**只读**、**零外部依赖**、**排掉归档目录**（.codex/.harness/target/node_modules）。

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

# ---------------------------------------------------------------------------
# 自证（--self-test）：用**今天真实翻车的三次**做用例，
# 而不是编造样例 —— 样例必须是真发生过的，才证明得了它挡得住同类错误。
# ---------------------------------------------------------------------------
if [ "${1:-}" = "--self-test" ]; then
  P=0; F=0
  chk() { # chk <描述> <期望退出码> <命令...>
    local desc="$1" want="$2"; shift 2
    "$@" >/dev/null 2>&1; local got=$?
    if [ "$got" = "$want" ]; then P=$((P+1)); printf '  OK   %-52s exit=%s\n' "$desc" "$got"
    else F=$((F+1)); printf '  BAD  %-52s exit=%s (期望 %s)\n' "$desc" "$got" "$want"; fi
  }
  echo "[self-test] 用今天真实翻车的三次做用例："
  # ① 我曾猜表名 ipd_project（真实无 ipd_ 前缀）→ 必须报「猜错」
  chk "猜错的表名 ipd_project 被拦下" 1 bash "$0" table ipd_project
  # ② 我曾猜列名；本脚本能列真实列名 → 必须列得出来
  chk "能列出 chat_model 真实列名" 0 bash "$0" column chat_model
  # ③ 我曾猜文件在 ruoyi-ipd（真实在 ruoyi-chat）→ 必须查到真位置
  chk "能查到 KnowledgeAttachServiceImpl 真实位置" 0 bash "$0" file KnowledgeAttachServiceImpl
  # ④ 归档必须被排除：.codex 下存在该文件，正常版不得报它
  chk "归档 .codex 不被当成现役代码" 1 bash "$0" file AuditProbe
  # ⑤ 阳性对照：确实存在的文件必须查得到（防工具恒返回非 0 而看起来很严）
  chk "阳性对照：能查到 IpdZkScenarioInitializer" 0 bash "$0" file IpdZkScenarioInitializer
  echo "[self-test] PASS=$P FAIL=$F"
  [ "$F" = "0" ] && exit 0 || exit 1
fi

KIND="${1:-}"
ARG="${2:-}"

if [ -z "$KIND" ] || [ -z "$ARG" ]; then
  sed -n '/^# 用法/,/^# 全部/p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

# 排掉归档：.codex/ 与 .harness/ 含历史工作树副本，是现役代码的 15 倍，
# 不排会把旧实现当现役（本仓 CLAUDE.md 明令要求全仓扫描必须排除）。
EXCL=(-not -path './.codex/*' -not -path './.harness/*'
      -not -path '*/target/*' -not -path '*/node_modules/*'
      -not -path './.git/*' -not -path './.repowise/*')

MYSQL_CNF=".codex/ipd-dev/config/mysql-client.cnf"
q() { mysql --defaults-file="$MYSQL_CNF" -N -B ipd_dev -e "$1" 2>/dev/null; }

case "$KIND" in

table)
  echo "== 数据库表名匹配 '$ARG'（含 LIKE 子串匹配）=="
  if [ ! -f "$MYSQL_CNF" ]; then
    echo "   ⚠️ 找不到 ${MYSQL_CNF}，无法查库。"
    echo "      若连的是别的实例，请改用该实例的 --defaults-file。"
    exit 2
  fi
  out=$(q "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE '%${ARG}%'
          ORDER BY TABLE_NAME;")
  if [ -z "$out" ]; then
    echo "   ❌ 没有任何表名含 '$ARG'。"
    echo "      ⇒ 表名大概率猜错了。先跑 'bash $0 table <更短的片段>' 逐步放宽。"
    exit 1                       # 非 0 = 名字猜错，调用方应停下改名字而不是继续
  fi
  echo "$out" | sed 's/^/   /'
  echo "   （TABLE_ROWS 是估算值，仅供定位；取行数请用 SELECT COUNT(*)）"
  ;;

column)
  echo "== 表 '$ARG' 的列 =="
  if [ ! -f "$MYSQL_CNF" ]; then echo "   ⚠️ 找不到 $MYSQL_CNF"; exit 2; fi
  out=$(q "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='${ARG}'
          ORDER BY ORDINAL_POSITION;")
  if [ -z "$out" ]; then
    echo "   ❌ 表 '$ARG' 没有列（或表不存在）。"
    echo "      ⇒ 先用 'bash $0 table ${ARG}' 确认表名，再来看列。"
    exit 1
  fi
  echo "$out" | sed 's/^/   /'
  ;;

file)
  echo "== 文件名匹配 '$ARG' =="
  out=$(find . -name "*${ARG}*" -type f "${EXCL[@]}" 2>/dev/null | sort)
  if [ -z "$out" ]; then
    echo "   ❌ 没有文件名含 '$ARG'。"
    echo "      ⇒ 路径猜错了，或该文件确不存在。放宽片段再试。"
    exit 1
  fi
  n=$(printf '%s\n' "$out" | wc -l | tr -d ' ')
  echo "$out" | sed 's/^/   /'
  echo "   —— 共 $n 个。若远多于预期，说明你搜的片段太短，结论要按全量数而非前几条。"
  ;;

class)
  echo "== 类 '$ARG' 的定义位置 =="
  out=$(grep -rn "class $ARG\b\|interface $ARG\b\|enum $ARG\b" \
        ruoyi-modules ruoyi-common ruoyi-admin --include='*.java' 2>/dev/null \
        | grep -v '/target/' | head -20)
  if [ -z "$out" ]; then
    echo "   ❌ 未找到类 '$ARG' 的定义。"
    echo "      ⇒ 模块路径或类名可能猜错了；用 'bash $0 file ${ARG}' 再确认。"
    exit 1
  fi
  echo "$out" | sed 's/^/   /'
  ;;

route)
  echo "== 前端路由 / 菜单 path 匹配 '$ARG' =="
  found=0
  for d in ../ruoyi-ipd-web apps ../../ruoyi-ipd-web/apps; do
    [ -d "$d" ] || continue
    out=$(grep -rhoE "path: *'[^']*${ARG}[^']*'" "$d/src/router" "$d/src/views" 2>/dev/null | sort -u)
    [ -n "$out" ] && { echo "$out" | sed 's/^/   /'; found=1; }
  done
  if [ "$found" = "0" ]; then
    echo "   （前端仓不在本仓同级目录下，或没匹配到。若本仓无前端代码，请去 ruoyi-ipd-web 执行）"
  fi
  echo "   ⚠️ 路由是**嵌套**结构：父 path 为 '/ipd'，子 path 常写成相对片段（不带前导斜杠）。"
  echo "      比对菜单与路由时必须先看清嵌套层级，否则会全判「无匹配」。"
  ;;

endpoint)
  echo "== 后端接口路径匹配 '$ARG' =="
  out=$(grep -rnE "(Get|Post|Put|Delete|Patch|Request)Mapping\(\"?[^)]*${ARG}" \
        ruoyi-modules ruoyi-admin --include='*.java' 2>/dev/null | grep -v '/target/' | head -20)
  if [ -z "$out" ]; then
    echo "   ❌ 未找到含 '$ARG' 的接口映射。"
    echo "      ⇒ 路径片段可能猜错。注意类级 @RequestMapping 前缀 + 方法级路径才拼成完整路径。"
    exit 1
  fi
  echo "$out" | cut -c1-160 | sed 's/^/   /'
  ;;

*)
  echo "未知类型: ${KIND}（支持 table|column|file|class|route|endpoint）"
  exit 2
  ;;
esac

