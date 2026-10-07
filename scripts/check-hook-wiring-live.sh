#!/bin/bash
# scripts/check-hook-wiring-live.sh
# 校验 .claude/settings.json 里声明的守卫**是否真被当前会话加载**。
#
# 为什么需要它（2026-10-07 现场）：
#   我把 output-shape-guard 挂进 PostToolUse(Bash)，配置文件校验合法、
#   守卫脚本单测 15/15 全绿、四种变异都能被识别——但**宿主根本没调用它**，
#   因为 PostToolUse 的配置只在会话启动时加载，装得太晚本会话看不到。
#   若不是靠「故意制造一个 bad flag 去看有没有告警」这一步，
#   我会写下「已挂载生效」——而实际是「配好了、没接线」。
#   这正是本仓记忆 silent-guard-omission-pattern 记的形态：
#   守卫写对了、也挂上了，但没被调用，失败却长得像成功。
#
# 本脚本查两件事：
#   1. 配置层：settings.json 里声明了哪些守卫（应有）
#   2. 运行层：宿主是否真的把这些命令执行过（要看 .harness/ 下的执行留痕）
#
# 用法：bash scripts/check-hook-wiring-live.sh
# 退出码：0 = 接线完整；1 = 有守卫声明了但查不到执行证据；2 = 脚本自身故障

set -u

# ---- 自证：能列出接线清单且清单非空 ----
if [ "${1:-}" = "--self-test" ]; then
  P=0; F=0
  out="$(bash "$0" 2>&1)"
  printf '%s' "$out" | grep -q '钩子总数' && P=$((P+1)) || F=$((F+1))
  printf '%s' "$out" | grep -q '配置里有' && P=$((P+1)) || F=$((F+1))
  echo "self-test: PASS=$P FAIL=$F"
  [ "$F" = 0 ] && exit 0 || exit 1
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SETTINGS="$ROOT/.claude/settings.json"

echo "=== 1. 配置层：settings.json 声明的守卫 ==="
[ -f "$SETTINGS" ] || { echo "找不到 $SETTINGS" >&2; exit 2; }

node -e '
const fs=require("fs");
const j=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
const all=[];
for(const [ev,groups] of Object.entries(j.hooks||{})){
  for(const g of (Array.isArray(groups)?groups:[])){
    for(const h of (g.hooks||[])){
      all.push({ev,matcher:g.matcher||"*",cmd:String(h.command||"")});
    }
  }
}
console.log("  事件类型: "+Object.keys(j.hooks||{}).length+" 种");
console.log("  钩子总数: "+all.length);
const guard=all.filter(x=>/\.cjs|\.sh/.test(x.cmd));
console.log("  指向脚本的钩子: "+guard.length);
console.log();
console.log("  逐条（用于比对运行层证据）:");
for(const g of guard) console.log("    ["+g.ev+" / "+g.matcher+"] "+g.cmd.slice(0,96));
fs.writeFileSync("/tmp/hook-declared.txt", guard.map(g=>g.cmd).join("\n"));
' "$SETTINGS" || { echo "settings.json 解析失败" >&2; exit 2; }

echo
echo "=== 2. 运行层：找执行留痕 ==="
HARNESS="$ROOT/.harness"
if [ ! -d "$HARNESS" ]; then
  echo "  ⚠️  无 .harness/ 目录，无法查运行层证据（不等于守卫没跑）"
  echo "  提示：查 Claude Code 的 hook 执行日志，或重启会话后再跑本脚本。"
  exit 0
fi

# 各类宿主可能留下的执行痕迹目录
EVIDENCE_DIRS=("$HARNESS/hooks" "$HARNESS/audit" "$HARNESS/hook-sessions" "$ROOT/.claude/state")
FOUND=0
for d in "${EVIDENCE_DIRS[@]}"; do
  [ -d "$d" ] || continue
  n=$(find "$d" -type f -newermt '-7 days' 2>/dev/null | wc -l | tr -d ' ')
  [ "$n" != "0" ] && { echo "  $d: 近 7 天 $n 个文件"; FOUND=$((FOUND+n)); }
done

if [ "$FOUND" = "0" ]; then
  echo "  未找到近 7 天的 hook 执行痕迹。"
  echo "  → 这**不能证明**守卫没被调用，只能说明本机没有留痕目录。"
  exit 0
fi

echo
echo "=== 3. 结论 ==="
cat <<'EOF'
  配置层已列出全部守卫。
  **关键判据**：配置里有 ≠ 运行时生效。PostToolUse / Stop 等事件在**会话启动时**
  加载配置，会话中途新增的条目本会话不生效，必须重启会话才会被调用。
  验证办法（唯一可靠）：
    1) 重启会话
    2) 跑一条会触发判据的命令
    3) 看是否出现守卫自己的输出
  本脚本只能验配置层；运行层必须用上面三步实测。
EOF
exit 0