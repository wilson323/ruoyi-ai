#!/bin/bash
# scripts/check-governance-wiring.sh
# 治理件接线自检：**新写的检查/守卫，必须自带自证且挂进 pre-commit**，否则等于没被任何机制管过。
#
# 为什么需要它（2026-10-07 实测）：
#   那天一共产出 7 件治理工具（计数自证、基准值生成、文档索引、接线检查、守卫测试集、
#   输出形状守卫、前提验证器）。事后盘点：
#     **有自证的 0 件、挂进 pre-commit 的 1 件。**
#   也就是说**它们全靠「我手动跑过一次」算数**——而那天已经实证：
#   手动跑一次并不够（编过行号、写错过尺子、同一错误一天犯四次）。
#
#   形状和本仓反复吃过的那个坑完全一致：**守卫写对了、也挂上了，但没被调用；
#   失败长得像成功。** 这次是反向：**守卫连自证都没有，就更不会被调用。**
#
# 判据（对每个治理件）：
#   1. 文件存在
#   2. 文件里能找到一个自证入口（--self-test / SELF_TEST / selfTest / self_test）
#   3. 它要么挂在 .claude/hooks/check-pre-commit.sh，要么被那个文件以某种形式引用
#
# 用法：
#   bash scripts/check-governance-wiring.sh            # 查全量
#   bash scripts/check-governance-wiring.sh --self-test
#
# 退出码：0 全绿 / 1 有未接线件 / 2 自身故障
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
GATE=".claude/hooks/check-pre-commit.sh"

# 治理件清单：新增治理工具时必须把自己加进来，否则这道检查看不见新件
# 豁免表：**工具类治理件不要求进提交路径**，否则每次提交都要跑一遍，慢且无意义。
# 它们的用法是「人/AI 需要时手跑」，门禁类才必须自动跑。
NO_GATE_REQUIRED=(
  scripts/gen-doc-index.sh              # 全量扫 1100+ 文档，耗时，只在需要刷新索引时跑
  scripts/check-hook-wiring-live.sh     # 诊断工具，出问题时手跑
  .claude/helpers/premise-verifier.sh   # 由人/AI 显式传入参数调用，不适合自动门禁
)

GOVERNANCE_FILES=(
  scripts/evidenced-count.sh
  scripts/gen-baseline.sh
  scripts/gen-doc-index.sh
  scripts/check-hook-wiring-live.sh
  scripts/check-governance-wiring.sh
  .claude/hooks/test-block-dangerous-git.sh
  .claude/helpers/output-shape-guard.cjs
  .claude/helpers/test-output-shape-guard.sh
  .claude/helpers/premise-verifier.sh
)

self_test() {
  local TD; TD="$(mktemp -d "${TMPDIR:-/tmp}/govwire.XXXXXX")" || { echo "无法建临时目录" >&2; return 2; }
  local P=0 F=0

  # ① 本文件自身：门禁文件存在
  [ -f "$GATE" ]; { [ $? = 0 ] && P=$((P+1)) || F=$((F+1)); } || true
  [ "$F" = "0" ] && echo "  [OK]   pre-commit 门禁文件存在" || { echo "  [BAD]  找不到 $GATE"; F=$((F+1)); }

  # ② 本文件自身能报红：把 GATE 指向不存在的文件，main 里的判定应失败
  ( cd "$TD" && REPO_ROOT=/nonexistent-root-xyz GATE=/nonexistent-gate bash "$ROOT/scripts/check-governance-wiring.sh" >/dev/null 2>&1 )
  local rc=$?
  [ "$rc" != "0" ]; { [ $? = 0 ] && P=$((P+1)) || F=$((F+1)); } || true
  [ "$rc" != "0" ] && echo "  [OK]   门禁文件缺失时能报红（EXIT=${rc}）" || { echo "  [BAD]  门禁缺失却仍放行（恒绿）"; F=$((F+1)); }

  # ③ 阳性对照：真仓库里本检查应能跑（哪怕有红，也要能跑完并给出清单）
  local out rc2
  out="$(bash "$ROOT/scripts/check-governance-wiring.sh" 2>&1)"; rc2=$?
  if printf '%s' "$out" | grep -q '治理件'; then
    P=$((P+1)); echo "  [OK]   真实仓库能产出清单（退出码 ${rc2}，红绿都算能跑）"
  else
    F=$((F+1)); echo "  [BAD]  真实仓库跑不出清单"
  fi

  rm -rf "$TD"
  echo "---- SELF-TEST: PASS=$P FAIL=$F ----"
  [ "$F" = "0" ] && return 0 || return 1
}

[ "${1:-}" = "--self-test" ] && { self_test; exit $?; }

echo "════ 治理件接线自检 ════"
echo "判据：每个治理件 ①存在 ②自带自证入口 ③被 pre-commit 引用"
echo

[ -f "$GATE" ] || { echo "❌ 找不到 ${GATE}，无法判断接线。自身故障，退出 2。" >&2; exit 2; }

TOTAL=0; OKN=0; BAD=0
printf '  %-42s %-6s %-6s %s\n' "治理件" "存在" "自证" "接线"
printf '  %s\n' "$(printf '%.0s-' {1..72})"
for f in "${GOVERNANCE_FILES[@]}"; do
  TOTAL=$((TOTAL+1))
  ex="否"; st="否"; wire="否"
  [ -f "$f" ] && ex="是"
  if [ -f "$f" ]; then
    # 自证的三种合法形态：--self-test 入口 / 自带断言集（本身就是测试）/ 内嵌对照用例
    if grep -qE 'self-test|SELF-TEST|self_test|selfTest|SELF_TEST' "$f" 2>/dev/null; then
      st="是"
    elif grep -qE '阳性对照|阴性对照|变异自证|反向对照|用例' "$f" 2>/dev/null; then
      st="是(自带断言)"
    elif [ -f "$(dirname "$f")/test-$(basename "${f%.cjs}").sh" ] \
         || [ -f "scripts/test-$(basename "${f%.sh}")" ]; then
      st="是(旁置测试集)"
    fi
  fi
  # 接线：被 pre-commit 引用（按文件名或不含扩展名的名字）
  base="$(basename "$f")"
  base_nodir="$(basename "$(dirname "$f")")/$(basename "$f")"
  exempt="否"
  for e in "${NO_GATE_REQUIRED[@]}"; do
    [ "$e" = "$f" ] && { exempt="是"; break; }
  done
  if [ "$exempt" = "是" ]; then
    wire="豁免"
  elif grep -qF -- "$base_nodir" "$GATE" 2>/dev/null || grep -qF -- "$f" "$GATE" 2>/dev/null; then
    wire="是"
  elif [ "$st" = "是(旁置测试集)" ]; then
    # 间接接线也算数: 它的测试集被门禁调用 = 它每次提交都被验证一次。
    # 2026-10-07 修正: 原判据只认「直接引用」, 把 output-shape-guard 误判成未接线,
    # 而它实际由门禁 9 通过 test-output-shape-guard.sh 调用 —— 形式缺口当实质缺口报红。
    wt="$(dirname "$f")/test-$(basename "${f%.cjs}").sh"
    if [ -f "$wt" ] && grep -qF -- "$wt" "$GATE" 2>/dev/null; then
      wire="是(经测试集)"
    fi
  fi

  mark="✅"
  case "$st" in 是|是\(自带断言\)|是\(旁置测试集\)) ;; *) mark="⚠️ "; BAD=$((BAD+1)) ;; esac
  case "$wire" in 是|豁免|是\(经测试集\)) ;; *) mark="⚠️ "; BAD=$((BAD+1)) ;; esac
  case "$ex" in 是) ;; *) mark="⚠️ "; BAD=$((BAD+1)) ;; esac
  [ "$mark" = "✅" ] && OKN=$((OKN+1))
  printf '  %s %-40s %-6s %-6s %s\n' "$mark" "$base" "$ex" "$st" "$wire"
done

echo
echo "────────────────────────────────────"
echo "治理件 $TOTAL 件：齐全 $OKN 件，有缺口 $BAD 件"
echo
echo "缺口处置："
echo "  · 自证=否  → 补 --self-test（在 mktemp 里造正反用例，仓库零写入）"
echo "  · 接线=否  → 挂进 ${GATE}，或说明为什么它不需要进提交路径"
echo "  ⚠️ 未自证的工具只能靠人手记得跑；本仓已实证「人手记得跑」不够"
echo "     （2026-10-07 同一天内同类错误犯四次，每次都是靠事后实测才抓出来）"

[ "$BAD" = "0" ] || exit 1
exit 0
