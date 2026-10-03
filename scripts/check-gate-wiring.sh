#!/usr/bin/env bash
# 元门禁：检查「门禁脚本是否真的被接线」。
#
# 治的是「检查脚本从未被接线」这类问题——单看每个脚本自身是否正确，
# 无法发现它压根没人跑。本脚本扫描 scripts/ 下所有 check-* / test-* 门禁，
# 判定每个是否被真实执行；未被执行且未在人工清单登记的，判定失败。
#
# 接线判定（2026-10-03 起委托 scripts/lib/gate-wiring-detect.py，只认「真实执行」）：
#   CI = .github/workflows/*.yml 的 run: 块内出现执行引用
#        （paths: 触发过滤、步骤 name:、注释、echo 文案一律不算）
#   本地hook = .claude/hooks / .claude/helpers / .claude/settings.json 的执行引用
#        （含 VAR=…; bash "$VAR" 间接调用、perl/timeout 包装、JS spawn、
#          Python 拼子进程命令；echo 提示文案不算）
#   传递 = 被上述已接线脚本转手执行（如 check-write-endpoint-ownership.py
#        被 ownership.sh 的 exec perl 包装执行；check-api-contract-fe-be.mjs
#        被 check-staged-snapshot.py 拼 command 执行）—— 归入其调用方的类别
# 次级语料（harness 间接调用，不等于接线，单独报告）：
#   .harness/*.sh 顶层入口（gate.sh / loop.sh / verify.sh）。
#   注意：只扫顶层脚本，不递归 .harness —— runs/ 是 438MB/3 万+ 文件的执行产物，
#   引用记录不等于接线，且递归 grep 会让本脚本慢到超时（实测 >120s）。
#
# 2026-10-03 重写缘由：旧实现 `grep -rlF "$b" <目录>` 把纯字符串出现当接线，
#   注释行也算。实测 7 个门禁被误判「已接 CI」（其中 3 个既未接线也未登记，
#   对任何检查不可见）。「已接 CI: 42 / 0 孤儿」是虚高后的假全绿。
#
# 用法：
#   bash scripts/check-gate-wiring.sh                          # 正常态，期望 EXIT=0
#   GATE_WIRING_FAIL_SEED=1 bash scripts/check-gate-wiring.sh # 自证态，期望 EXIT!=0
#
# FAIL_SEED 语义：**先完整走完扫描逻辑并打印完整清单**，再在汇总之后强制失败。
# 不允许短路 return —— 现存 selftest 的缺陷正是在没跑扫描的情况下直接返回 1，
# 那只能证明「脚本能返回非零」，证明不了「扫描逻辑真的在工作」。
#
# 兼容性：macOS 自带 bash 3.2，无关联数组；集合用临时文件实现。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || { echo "[gate-wiring] FAIL: 无法进入仓库根目录 $REPO_ROOT" >&2; exit 2; }

REGISTRY="scripts/gate-manual-registry.txt"
TAB="$(printf '\t')"
FAIL_SEED="${GATE_WIRING_FAIL_SEED:-0}"

ACT_TMP="$(mktemp)"; SUS_TMP="$(mktemp)"
trap 'rm -f "$ACT_TMP" "$SUS_TMP"' EXIT

# ── 1. 读人工清单（必须先读，否则孤儿无处申报） ──
if [ ! -f "$REGISTRY" ]; then
  echo "[gate-wiring] FAIL: 人工清单 $REGISTRY 不存在。未接线的门禁脚本将全部判为孤儿。" >&2
  exit 2
fi
while IFS="$TAB" read -r name status reason; do
  [ -z "${name:-}" ] && continue
  case "$name" in '#'*) continue ;; esac
  [ -n "${reason:-}" ] || continue
  [ -n "${status:-}" ] || continue
  case "$status" in
    ACTIVE)  printf '%s\t%s\n' "$name" "$reason" >> "$ACT_TMP" ;;
    SUSPECT) printf '%s\t%s\n' "$name" "$reason" >> "$SUS_TMP" ;;
    *) echo "[gate-wiring] FAIL: $REGISTRY 中 '$name' 状态 '$status' 非法（只允许 ACTIVE / SUSPECT）" >&2; exit 2 ;;
  esac
done < "$REGISTRY"

# ── 2. 收集候选门禁脚本 ──
CAND_TMP="$(mktemp)"
ls scripts/check-*.sh scripts/check-*.py scripts/check-*.mjs \
   scripts/test-*.sh  scripts/test-*.py  scripts/test-*.mjs 2>/dev/null | sort > "$CAND_TMP"
total="$(wc -l < "$CAND_TMP" | tr -d ' ')"
if [ "$total" -eq 0 ]; then
  echo "[gate-wiring] FAIL: scripts/ 下未发现任何 check-*/test-* 门禁脚本" >&2
  rm -f "$CAND_TMP"; exit 2
fi

wired=0; registered=0; harness_only=0; orphan_fail=0; stale_registry=0
n_ci=0; n_hook=0
L_ACTIVE=""; L_SUSPECT=""; L_ORPHAN=""; L_HARNESS=""; L_STALE=""; L_CI=""; L_HOOK=""

# ── 3. 逐个判定（经 scripts/lib/gate-wiring-detect.py，只认「真实执行」）──
# 2026-10-03 重写：原实现是 `grep -rlF "$b" <目录>` —— 纯字符串出现即算接线，
#   注释行、echo 文案、workflow 的 paths: 过滤、步骤 name: 全被当成接线。
#   实测 7 个门禁因此被误判「已接 CI」，其中 3 个既未接线也未登记。
#   现改为调检测器判定执行引用（含 CI 的 run: 块、shell 间接调用 VAR=…;bash "$VAR"、
#   perl/timeout 包装、JS spawn、settings.json hook command、Python 子进程拼命令、
#   以及沿已接线脚本的传递调用）。CI 与本地 hook 分开报告。
DETECT="scripts/lib/gate-wiring-detect.py"
if [ ! -f "$DETECT" ]; then
  echo "[gate-wiring] FAIL: 检测器 $DETECT 不存在（2026-10-03 起为判定核心，非可选依赖）" >&2
  exit 2
fi
CAND_NAMES="$(mktemp)"
sed 's|.*/||' "$CAND_TMP" > "$CAND_NAMES"
DET_TMP="$(mktemp)"
if ! python3 "$DETECT" "$CAND_NAMES" > "$DET_TMP"; then
  echo "[gate-wiring] FAIL: 检测器运行失败" >&2
  rm -f "$CAND_NAMES" "$DET_TMP"; exit 2
fi

n_ci=0; n_hook=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  b="$(basename "$f")"

  det_line="$(grep -m1 "^$b$TAB" "$DET_TMP" 2>/dev/null)"
  cls="NONE"; ev=""
  if [ -n "$det_line" ]; then
    cls="$(printf '%s' "$det_line" | cut -f2)"
    ev="$(printf '%s' "$det_line" | cut -f3)"
    ev="${ev#"$REPO_ROOT"/}"   # 检测器输出的绝对路径转仓库相对，报告更可读
  fi
  # h_hit 仅作登记项的附注（.harness 顶层入口直连），不算接线 —— 语义同旧版
  h_hit="$(grep -lF "$b" .harness/*.sh 2>/dev/null | head -1)"

  act_line="$(grep -m1 "^$b$TAB" "$ACT_TMP" 2>/dev/null)"
  sus_line="$(grep -m1 "^$b$TAB" "$SUS_TMP" 2>/dev/null)"

  if [ "$cls" = "CI" ]; then
    n_ci=$((n_ci+1)); wired=$((wired+1))
    L_CI="$L_CI  - $b  <-  $ev"$'\n'
    continue
  fi
  if [ "$cls" = "HOOK" ]; then
    n_hook=$((n_hook+1)); wired=$((wired+1))
    L_HOOK="$L_HOOK  - $b  <-  $ev"$'\n'
    continue
  fi
  # cls ∈ HARNESS(仅 .harness 直连) / SCRIPT(仅被未接线脚本调用) / NONE → 视为未接线

  if [ -n "$act_line" ]; then
    registered=$((registered+1))
    desc="${act_line#*$TAB}"
    if [ -n "$h_hit" ]; then
      harness_only=$((harness_only+1))
      L_HARNESS="$L_HARNESS  - $b  <- $h_hit"$'\n'
    fi
    L_ACTIVE="$L_ACTIVE  - $b  |  $desc"$'\n'
  elif [ -n "$sus_line" ]; then
    desc="${sus_line#*$TAB}"
    L_SUSPECT="$L_SUSPECT  - $b  |  $desc"$'\n'
  else
    orphan_fail=$((orphan_fail+1))
    L_ORPHAN="$L_ORPHAN  FAIL  $b"$'\n'
  fi
done < "$CAND_TMP"
rm -f "$CAND_NAMES" "$DET_TMP"

# ── 4. 清单失效：登记了但脚本已不存在（登记失效，会让人误以为它还在跑） ──
while IFS="$TAB" read -r name _rest; do
  [ -z "$name" ] && continue
  [ -f "scripts/$name" ] || { stale_registry=$((stale_registry+1)); L_STALE="$L_STALE  - scripts/$name"$'\n'; }
done < "$ACT_TMP"
rm -f "$CAND_TMP"

n_active=$(printf '%s' "$L_ACTIVE" | grep -c . )
n_suspect=$(printf '%s' "$L_SUSPECT" | grep -c . )
n_orphan=$(printf '%s' "$L_ORPHAN" | grep -c . )
n_harness=$(printf '%s' "$L_HARNESS" | grep -c . )
n_stale=$(printf '%s' "$L_STALE" | grep -c . )

# ── 5. 汇总（FAIL_SEED 也必须走到这里，即扫描真的跑完了） ──
echo "================================================================================"
echo "[gate-wiring] 门禁接线元门禁"
echo "================================================================================"
printf '门禁脚本总数            : %s\n' "$total"
printf '已接线合计              : %s  (CI %s + 本地hook %s)\n' "$wired" "$n_ci" "$n_hook"
printf '未接线·已登记人工(ACTIVE) : %s\n' "$registered"
printf '未接线·已隔离待裁决(SUSPECT): %s\n' "${n_suspect:-0}"
printf '未接线·孤儿(判失败)     : %s\n' "$orphan_fail"
printf '清单失效(脚本已删除)     : %s\n' "$stale_registry"

[ -n "$L_CI" ] && { echo; echo "-- 已接 CI（${n_ci}）-------------------------------------------------------------"; printf '%s' "$L_CI"; }
[ -n "$L_HOOK" ] && { echo; echo "-- 已接本地 hook（${n_hook}）-----------------------------------------------------"; printf '%s' "$L_HOOK"; }

[ -n "$L_ACTIVE" ] && { echo; echo "-- 未接线但已登记为人工执行（${n_active}）---------------------------------------"; printf '%s' "$L_ACTIVE"; }
if [ -n "$L_HARNESS" ]; then
  echo; echo "-- 其中仅被 .harness 间接调用，不等于 CI 接线（${n_harness}）----------------------"; printf '%s' "$L_HARNESS"
fi
[ -n "$L_SUSPECT" ] && { echo; echo "-- 未接线·判定过时或损坏，已隔离待 owner 裁决（${n_suspect}）------------------------"; printf '%s' "$L_SUSPECT"; }
[ -n "$L_STALE" ]   && { echo; echo "-- 清单失效：登记了但 scripts/ 下已无此文件（${n_stale}）-----------------------------"; printf '%s' "$L_STALE"; }

if [ -n "$L_ORPHAN" ]; then
  echo; echo "-- 孤儿：未接线且未登记（${n_orphan}）----------------------------------------------"
  printf '%s' "$L_ORPHAN"
  echo
  echo "  处置二选一，登记进 ${REGISTRY}（三字段 TAB 分隔：名称 状态 理由）："
  echo "    <脚本>  ACTIVE   <该跑但没接线>    -> 人工执行 / 后续接进 CI"
  echo "    <脚本>  SUSPECT  <已过时或已损坏>  -> 等 owner 裁决删除，不算已登记"
fi

# ── 6. 判定 ──
if [ "$orphan_fail" -gt 0 ] || [ "$stale_registry" -gt 0 ]; then
  echo
  echo "[gate-wiring] FAIL: ${orphan_fail} 个未登记的孤儿门禁 / ${stale_registry} 条失效登记。"
  exit 1
fi

if [ -n "$FAIL_SEED" ] && [ "$FAIL_SEED" != "0" ]; then
  echo
  echo "[gate-wiring] FAIL_SEED 触发：上面 $total 个门禁脚本已全部扫描完毕并输出清单，"
  echo "[gate-wiring] 现强制失败以自证「本门禁能变红」。这是刻意行为，非真实缺陷。"
  exit 1
fi

echo
echo "[gate-wiring] PASS: $total 个门禁全部已接线或在人工清单中显式登记，无未登记孤儿。"
exit 0
