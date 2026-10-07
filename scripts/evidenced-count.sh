#!/bin/bash
# scripts/evidenced-count.sh
# 带口径自证的计数入口。任何用于**结论**的计数都应经由本脚本，理由见文件末尾。
#
# 用法：
#   evidenced-count.sh find <path> --name '*.java'
#   evidenced-count.sh git  rev-list --count <range>
#   evidenced-count.sh sql   <sql>            （经 docker exec，见 --sql-db）
#   evidenced-count.sh expect-zero <cmd...>    （强制声明「预期为 0」）
#
# 输出一段机器可读的自证块（KEY=VALUE），人读和 grep 都行：
#   EC_COUNT=<n>
#   EC_ROOT=<扫描根>
#   EC_EXCLUDE=<排除项,逗号分隔>
#   EC_PATTERN=<模式>
#   EC_SAMPLE=<样本首条>
#   EC_GOVERNANCE=yes|no        （本次计数是否触及归档目录 .codex/.harness/target）
#   EC_ECHO=<零/全绿时必须显式声明的意图>
#
# 为什么必须这样：
#   本仓反复吃过的亏是「读数来自一把没对准目标的尺子」——猜字段名、漏排除项、
#   被多写者污染。裸 `find | wc -l` / `grep -c` 的输出里没有任何东西能证明
#   它扫的是哪里、排除了什么、样本长什么样，于是错数字和真数字长得一模一样。
#   本脚本让「未经证实的读数」在形式上就拿不出交付形态。

set -u

# ---- 自证：零值必须声明 + 空计数必须拒绝（mktemp 内，仓库零写入）----
if [ "${1:-}" = "--self-test" ]; then
  TD="$(mktemp -d)"; P=0; F=0
  mkdir -p "${TD}/pkg"; : > "${TD}/pkg/a.txt"
  # ① 计数有值时通过
  bash "$0" find "$TD/pkg" --name '*.txt' >/dev/null 2>&1
  [ $? = 0 ] && P=$((P+1)) || F=$((F+1))
  # ② 数到 0 且未声明 -> 必须非 0（这是「零先当坏」的语法化）
  bash "$0" find "$TD/pkg" --name '*.nope' >/dev/null 2>&1
  [ $? != 0 ] && P=$((P+1)) || F=$((F+1))
  # ③ 数到 0 且已声明 -> 必须通过
  EC_EXPECT_ZERO="自证用例" bash "$0" find "$TD/pkg" --name '*.nope' >/dev/null 2>&1
  [ $? = 0 ] && P=$((P+1)) || F=$((F+1))
  rm -rf "$TD"
  echo "self-test: PASS=$P FAIL=$F"
  [ "$F" = 0 ] && exit 0 || exit 1
fi

# 本仓归档目录：历史工作树副本，含 19000+ 个 .java，是现役源码的 15 倍。
# 全仓计数必须排掉它们，否则会把归档里的旧实现当现役代码。
ARCHIVE_DIRS=".codex,.harness,target,node_modules,.git,.repowise"

die() { echo "evidenced-count: $*" >&2; exit 2; }

MODE="${1:-}"
[ -n "$MODE" ] || die "用法见文件头"

# ---- 组装命令与扫描根 ----
PATTERN=""
ROOT="."
CMD=()
case "$MODE" in
  find)
    shift; [ $# -ge 1 ] || die "find 需要路径"
    ROOT="$1"; shift
    while [ $# -gt 0 ]; do
      case "$1" in
        --name) PATTERN="${2:-}"; shift 2 ;;
        *) CMD+=("$1"); shift ;;
      esac
    done
    [ -d "$ROOT" ] || die "路径不存在: $ROOT"
    ;;
  git)
    shift
    CMD=(git "$@")
    ROOT="$(git rev-parse --show-toplevel 2>/dev/null || echo .)"
    ;;
  sql)
    shift
    die "sql 模式需显式提供数据库连接；请改用 git/curl 口径，或自行补充 --db 参数"
    ;;
  cmd)
    shift; CMD=("$@"); ROOT="$(pwd)" ;;
  *)
    die "未知模式: ${MODE}（支持 find | git | cmd）"
    ;;
esac

EXCLUDE_ARGS=()
# ARCHIVE_DIRS 为空时循环不执行，find 会因缺参数而报错 → 必须先判空再展开。
# 2026-10-07 变异自证实测到这条：把 ARCHIVE_DIRS 清空后 EC_COUNT 直接变空字符串，
# 而脚本退出码仍是 0 —— 又一次「失败长得像成功」。故此处显式守卫。
if [ -n "$ARCHIVE_DIRS" ]; then
  OLDIFS="$IFS"; IFS=','
  for d in $ARCHIVE_DIRS; do
    [ -n "$d" ] || continue
    EXCLUDE_ARGS+=(-not -path "*/$d/*" -not -path "*/$d")
  done
  IFS="$OLDIFS"
fi

# ---- 归一化扫描根为绝对路径 ----
ROOT_ABS="$(cd "$ROOT" 2>/dev/null && pwd)" || die "无法解析路径: $ROOT"

# ---- 执行计数 ----
if [ "$MODE" = "find" ]; then
  # 注意：先把结果落到临时文件，才能同时拿到总数与样本首条
  LIST="$(mktemp "${TMPDIR:-/tmp}/ecl.XXXXXX")" || die "临时文件创建失败"
  KIND="list"
  trap 'rm -f "$LIST"' EXIT
  if [ -n "$PATTERN" ]; then
    # `${A[@]+"${A[@]}"}` 是 set -u 下引用**可能为空的数组**的写法。
    # 直接写 "${EXCLUDE_ARGS[@]}" 在数组为空时 bash 会抛 unbound variable 并中断脚本
    # ——2026-10-07 变异实测踩到：清空排除项后脚本在第 92 行崩掉，
    # 外部只看到「没有结果」，非常像「扫到了 0 个」。这两种必须区分开。
    find "$ROOT_ABS" ${EXCLUDE_ARGS[@]+"${EXCLUDE_ARGS[@]}"} -name "$PATTERN" -type f > "$LIST" 2>/dev/null
  else
    find "$ROOT_ABS" ${EXCLUDE_ARGS[@]+"${EXCLUDE_ARGS[@]}"} -type f > "$LIST" 2>/dev/null
  fi
  COUNT=$(wc -l < "$LIST" | tr -d ' ')
  SAMPLE=$(head -1 "$LIST" 2>/dev/null || true)
  [ -n "$SAMPLE" ] || SAMPLE="(空)"
elif [ "$MODE" = "git" ]; then
  OUT="$("${CMD[@]}" 2>/dev/null)"
  # git 的 --count / rev-parse 之类输出的是**单个标量**而非清单。
  # 若按行数去数会得到 1，把真实值 662 藏进 SAMPLE——这正是本仓反复吃过的
  # 「读数形状没验就当成结论」。这里按形状分派：单行纯数字即视为标量，原样透出。
  LINES_N="$(printf '%s\n' "$OUT" | grep -c . || true)"
  if [ "$LINES_N" = "1" ] && printf '%s' "$OUT" | grep -qE '^-?[0-9]+$'; then
    COUNT="$OUT"; KIND="scalar"
    SAMPLE="$OUT"
  else
    COUNT="$LINES_N"; KIND="list"
    SAMPLE="$(printf '%s' "$OUT" | head -1)"
    [ -n "$SAMPLE" ] || SAMPLE="(空)"
  fi
else
  OUT="$("${CMD[@]}" 2>&1)"
  KIND="list"
  COUNT="$(printf '%s' "$OUT" | grep -c . || true)"
  SAMPLE="$(printf '%s' "$OUT" | head -1)"
  [ -n "$SAMPLE" ] || SAMPLE="(空)"
fi

# ---- 自检 0：计数本身不得为空 ----
# 2026-10-07 实测到的形态：某处配置被清空 → find 参数残缺 → 脚本什么也没扫到，
# EC_COUNT 变成空字符串，而退出码仍是 0。一条空读数进了结论，下游无从发现。
# 所以：拿不到数字就算失败，绝不产出「看起来正常」的自证块。
if [ -z "$COUNT" ]; then
  echo "evidenced-count: 未能取得计数（EC_COUNT 为空）。判定：读数不可信，拒绝产出自证块。" >&2
  echo "  请检查：路径=$ROOT_ABS 模式=${PATTERN:--} 命令=${CMD[*]:-}" >&2
  exit 3
fi

# ---- 自检 1：零必须显式声明意图 ----
# 「零/全绿先当坏」是本仓纪律，但纪律靠自觉。把它做成语法：
# 计数为 0 时，若调用方没声明 expect-zero，就在自证块里标红并让脚本失败。
EXPECT_ECHO="${EC_EXPECT_ZERO:-}"
if [ "$COUNT" = "0" ] && [ -z "$EXPECT_ECHO" ]; then
  echo "evidenced-count: 计数为 0。按本仓纪律「零/全绿先当坏」，" >&2
  echo "  必须显式声明这是预期：EC_EXPECT_ZERO='<为什么预期为 0>' bash \$0 $*" >&2
  echo "  若你确认这就是预期，重跑时带上该环境变量。" >&2
  exit 1
fi

# ---- 自检 2：是否触及归档 ----
GOV="no"
case "$ROOT_ABS" in
  */.codex|*/.harness|*/target|*/.repowise) GOV="yes" ;;
esac

printf 'EC_COUNT=%s\n'    "$COUNT"
printf 'EC_KIND=%s\n'     "${KIND:-list}"
printf 'EC_ROOT=%s\n'     "$ROOT_ABS"
printf 'EC_EXCLUDE=%s\n'  "$ARCHIVE_DIRS"
printf 'EC_PATTERN=%s\n'  "${PATTERN:--}"
printf 'EC_SAMPLE=%s\n'   "$SAMPLE"
printf 'EC_GOVERNANCE=%s\n' "$GOV"
printf 'EC_ECHO=%s\n'     "${EXPECT_ECHO:-n/a}"
exit 0
