#!/bin/bash
# .claude/helpers/premise-verifier.sh
# 派单/文档里的数字，必须先验证才能往下用。
#
# 为什么要它（2026-10-07 当天实测三次）：
#   1. 「GateReviewVisibilityTest 的 8 个测试一次都没跑过」——派单写的。
#      实测：该模块 pom 有 <groups combine.self="override"/>（9-29 已落地），
#      **那 8 个测试一直在跑**。我照着派单开了一卡，改了代码才发现前提是假的。
#   2. 「37 条幽灵 AC」——同上。我又量了一遍，还是 29，最后才对出真实值 **2**。
#   3. 「补完 @Tag 后 8 个测试真跑了」——这条是我自己量的，**但只量了改完那一侧**，
#      改前没量，于是「0→8」实际是「8→8」。拿单侧证据下了双侧结论。
#
# 三次的形状完全一样：**读一个数字比量它便宜，于是读了。**
# 派单上的数字、文档里的数字、我自己上一轮说过的数字——**都不能直接当事实用**。
#
# 怎么用（不是纪律，是格式要求）：
#   任何来自派单/文档/对话的数字，在写进结论或代码前，先跑一次
#   `bash .claude/helpers/premise-verifier.sh <数字> <该数字声称来自哪里> <验它的命令>`
#   验不过 → 停，先报「前提不成立」，不要继续做。
#
set -u

NUM="${1:-}"
SRC="${2:-未说明来源}"
CMD="${3:-}"

if [ -z "$NUM" ] || [ -z "$CMD" ]; then
  cat <<'USAGE'
用法: premise-verifier.sh <数字> <来源> <验证命令>

例:
  bash premise-verifier.sh 8 "派单:N-A 说 8 个测试没跑过" \
    "grep -c '<groups combine.self=.override' ruoyi-modules/ruoyi-ipd/pom.xml"

  bash premise-verifier.sh 37 "派单:N-B 说 37 条幽灵 AC" \
    "node -e \"...数一遍...\""   # 把命令用双引号包起来当一个参数

退出码:
  0  前提成立（命令跑通且输出与声称一致或可解释）
  1  前提不成立（命令失败 / 输出与声称明显不符）
  2  用法错误
USAGE
  exit 2
fi

echo "════ 前提验证 ════"
echo "  声称的数字: $NUM"
echo "  来源:       $SRC"
echo "  验证命令:   $CMD"
echo "────────────────────────────────"

# 关键：**先确认这条命令本身能跑**。
# 零输出 / 报错 / 找不到可执行文件，都不能读成「前提成立」。
OUT="$(eval "$CMD" 2>&1)"
RC=$?

echo "  命令退出码: $RC"
echo "  命令输出:"
if [ -z "$OUT" ]; then
  echo "    （空）"
else
  printf '%s\n' "$OUT" | head -12 | sed 's/^/    /'
fi

echo "────────────────────────────────"

# ---- 三种失效形态，每种都要在这里被抓住 ----
if [ "$RC" -ne 0 ]; then
  echo "❌ 前提不成立：验证命令本身失败（退出码 ${RC}）。"
  echo "   这是最危险的一种：命令失败常被误读成「数字是 0」。"
  exit 1
fi

if [ -z "$OUT" ]; then
  echo "❌ 前提无法验证：命令成功但输出为空。"
  echo "   按本仓纪律「零/全绿先当坏」——空输出不等于 0，也不等于成立。"
  echo "   请换一条能真正区分「不存在」与「数错」的命令（阳性对照）。"
  exit 1
fi

if printf '%s' "$OUT" | grep -qE 'command not found|No such file|未找到|不存在|error:|Error:'; then
  echo "❌ 前提不成立：命令输出里含错误标记，说明量它的尺子坏了。"
  exit 1
fi

# 输出里是否出现了被声称的数字
if printf '%s' "$OUT" | grep -qF "$NUM"; then
  echo "✅ 前提成立：输出里出现了 ${NUM}。"
  exit 0
fi

echo "⚠️  数字对不上：声称 ${NUM}，但验证输出里没有它。"
echo "   两种可能，不要急着下结论："
echo "     a) 前提确实是错的（最常见：派单数字来自另一个口径）"
echo "     b) 验证命令用的口径和声称的口径不同（也常见）"
echo "   先确认口径一致，再判对错。"
exit 1
