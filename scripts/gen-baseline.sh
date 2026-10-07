#!/bin/bash
# scripts/gen-baseline.sh
# 从代码里生成「基准值」文档，并可自检能否报红。
#
# 为什么要它（2026-10-07 owner 指示「需求整合在一起禁止散落」的落地手段）：
#   本仓的数字漂移了几个月，根子是**数字是手写进文档的**。
#   手写的数字必然过期，而且过期后没人知道——因为没有任何东西会检查它。
#   实测教训：`治理/退役口径基准值-20261007.md` 初版写「测试夹具里仍有 BonusPool（16 个文件）」，
#   实测 20 处命中里 19 处只是注释——**手写的基准值自己就是漂移源**。
#
# 所以：数字只能由脚本从代码读出来，且必须有一个能红的守门测试。
#
# 用法：
#   bash scripts/gen-baseline.sh            # 生成 docs/ipd-系统说明/治理/基准值.md
#   bash scripts/gen-baseline.sh --check    # 只比对，不写盘；不一致则退出 1（供 pre-commit 用）
#
set -u

OUT="docs/ipd-系统说明/治理/基准值.md"

# ---- 自证：--check 能红（改内容不一致即非 0）+ 尺子失效拒绝产出 ----
if [ "${1:-}" = "--self-test" ]; then
  P=0; F=0
  # ① 尺子有效时正常产出
  bash "$0" >/dev/null 2>&1; [ $? = 0 ] && P=$((P+1)) || F=$((F+1))
  # ② --check 在一致时应通过
  bash "$0" --check >/dev/null 2>&1; [ $? = 0 ] && P=$((P+1)) || F=$((F+1))
  # ③ 篡改基准值文件后 --check 必须报红
  BK="$(mktemp)"; cp "$OUT" "$BK"
  printf '
<!-- 篡改 -->
' >> "$OUT"
  bash "$0" --check >/dev/null 2>&1; [ $? != 0 ] && P=$((P+1)) || F=$((F+1))
  cp "$BK" "$OUT"; rm -f "$BK"
  echo "self-test: PASS=$P FAIL=$F"
  [ "$F" = 0 ] && exit 0 || exit 1
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

AC="ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/seed/ActionCatalog.java"
GATE="ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/IpdGateElementSeedInitializer.java"

# ---- 阳性对照先行：任何计数为 0 时先证明尺子没瞎 ----
probe() { # probe <描述> <实际值> <阳性对照命令> <阳性对照期望非零>
  local desc="$1" val="$2" ctl_cmd="$3"
  local ctl
  ctl=$(eval "$ctl_cmd" 2>/dev/null)
  if [ "$ctl" = "0" ]; then
    echo "ABORT: $desc 的阳性对照为 0，说明 grep/尺子本身失效，拒绝产出基准值。" >&2
    exit 3
  fi
  printf '  [尺子自检] %-22s 实际=%-4s 阳性对照=%s ✅\n' "$desc" "$val" "$ctl"
}

DEEP=$(grep -c 'new ActionDef(".*", ".*", ".*", ".*", "DEEP"' "$AC" 2>/dev/null)
LIGHT=$(grep -c 'new ActionDef(".*", ".*", ".*", ".*", "LIGHT"' "$AC" 2>/dev/null)
TOTAL=$((DEEP + LIGHT))
ALL_DEF=$(grep -c 'new ActionDef(' "$AC" 2>/dev/null)
RETIRED=$(grep -cE '"LC01"|"LC03"' "$AC" 2>/dev/null)

echo "=== 1. 动作数 ==="
probe "DEEP"      "$DEEP"   "grep -c '\"LC02\"' $AC"
probe "LIGHT"     "$LIGHT"  "grep -c '\"LC04\"' $AC"
probe "已退役码"   "$RETIRED" "grep -c '\"LC05\"' $AC"

G2_6_VETO=$(grep 'G2-6' "$GATE" 2>/dev/null | grep -oE '"[YN]"' | head -1)
G2_ALL=$(grep -cE 'new String\[\]\{"G[1-5]"' "$GATE" 2>/dev/null)

MAIN_SRC=$(find . -name '*.java' -not -path './.codex/*' -not -path './.harness/*' \
  -not -path '*/target/*' -not -path './.repowise/*' -path '*/src/main/java/*' 2>/dev/null | wc -l | tr -d ' ')
TEST_SRC=$(find . -name '*.java' -not -path './.codex/*' -not -path './.harness/*' \
  -not -path '*/target/*' -path '*/src/test/java/*' 2>/dev/null | wc -l | tr -d ' ')
CTRL_IPD=$(find ruoyi-modules/ruoyi-ipd -name '*Controller.java' -not -path '*/target/*' -path '*/src/main/*' 2>/dev/null | wc -l | tr -d ' ')

# ⚠️ 不要把 commit 号写进生成的文件内容（2026-10-07 实测教训）。
#   第一次实现把 HEAD 写进了「生成时间戳」那一行，于是**别人提交一次、
#   代码一行没动，这个基准值文件就变成"不一致"** —— 守门变成日常噪音，
#   大家会开始习惯性忽略它，那时它比没有更糟。
#   正确做法：内容里只放**从代码读出来的**事实。commit 号属于「什么时候生成的」，
#   那是元信息，不是被校验的内容。想知道就问 git，别写进被比对的文件。
STAMP="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

gen() {
cat <<EOF
# 基准值（**由 scripts/gen-baseline.sh 自动生成，请勿手改**）

> 本文件内容**只含从代码读出的事实**。想知道生成于哪个 commit，问 git（`git log -1 -- 基准值.md`）。
> 重新生成：\`bash scripts/gen-baseline.sh\`
> 校验（CI 用）：\`bash scripts/gen-baseline.sh --check\` —— 手改了本文件或代码变了都会退出 1

| 指标 | 值 | 怎么数出来的 |
|---|---:|---|
| 有效动作总数 | **$TOTAL** | DEEP $DEEP + LIGHT ${LIGHT}（\`ActionCatalog.java\`） |
| 深管动作 | $DEEP | \`grep -c 'new ActionDef(".*",".*",".*",".*","DEEP"'\` |
| 轻管动作 | $LIGHT | 同上，\`LIGHT\` |
| 已退役码残留（LC01/LC03） | $RETIRED | \`grep -cE '"LC01"\|"LC03"'\` |
| \`new ActionDef(\` 总行数 | $ALL_DEF | ⚠️ 比 $TOTAL 多 $((ALL_DEF - TOTAL))，那是**非目录用途**，不要当动作数 |
| G1~G5 要素总数 | $G2_ALL | \`IpdGateElementSeedInitializer.java\` |
| G2-6 是否否决项 | \`${G2_6_VETO:-未知}\` | 同上，G2-6 行的 veto 列。**权威=非否决**，见 \`工程合同/DOC-05.md\` 决策 1 |
| 主源码 java 文件 | $MAIN_SRC | 排掉 \`.codex/\` \`.harness/\` \`target/\` \`.repowise/\` |
| 测试源码 java 文件 | $TEST_SRC | 同上，仅 \`src/test/java\` |
| IPD 模块 Controller | $CTRL_IPD | \`find ruoyi-modules/ruoyi-ipd -name '*Controller.java'\` |

## 引用纪律

**文档里写这些数字时，必须写「见基准值.md」而不是复述数字本身。**
复述 = 制造下一个漂移点。今天 83 份文件写着 69 个动作、只有 12 份写着 67，
就是这么来的。
EOF
}

if [ "${1:-}" = "--check" ]; then
  [ -f "$OUT" ] || { echo "FAIL: $OUT 不存在，先跑 gen-baseline.sh 生成" >&2; exit 1; }
  gen > /tmp/baseline-check.md
  if diff -q "$OUT" /tmp/baseline-check.md >/dev/null 2>&1; then
    echo "PASS: 基准值与代码一致"
    exit 0
  fi
  echo "FAIL: 基准值与代码不一致——要么代码变了，要么有人手改了这个文件。" >&2
  echo "      正确做法：跑 bash scripts/gen-baseline.sh 重新生成，不要手改。" >&2
  diff "$OUT" /tmp/baseline-check.md | head -20 >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
gen > "$OUT"
echo "已生成 ${OUT}（HEAD ${STAMP}）"
