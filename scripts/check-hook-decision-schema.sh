#!/usr/bin/env bash
# 校验钩子脚本实际吐出的 JSON 是否符合 Claude Code 的输出契约。
#
# 背景（2026-10-03 实证）：~/.claude/hooks/irreversible-guard.cjs 与 reflection-gate.cjs
# 都写了顶层 {"decision":"allow"}。Claude Code 校验失败后**静默丢弃整份输出**——
# irreversible-guard 是不可逆操作拦截（rm -rf 一/二级目录），它其实一直没生效，
# 界面只在控制台刷一行红字。这类错误不会自己暴露。
#
# 为什么是「执行」而不是 grep：
#   第一版门禁用 grep 扫 `'decision':` 字面量，结果对真实 bug **假绿**。
#   因为出事的两处写的是 JS 简写属性 `{ decision, reason }`——字面量里根本没有
#   'decision' 这个词，grep 永远匹配不到。变异自证一跑就露馅。
#   钩子合不合法，取决于它往 stdout 吐的 JSON 形状，不取决于源码怎么写。
#   所以这里直接喂 payload 跑一遍，验输出。
#
# 契约（Claude Code 钩子输出）：
#   - 顶层 decision 是遗留字段，只接受 "approve" | "block"
#   - PreToolUse 的 allow/deny/ask/defer 走 hookSpecificOutput.permissionDecision
#   - Stop 的提示语走 hookSpecificOutput.additionalContext
#   - 允许 exit code 0 且无 stdout（什么都不输出 = 不干预）
#
# 用法：
#   bash scripts/check-hook-decision-schema.sh                  # 检查
#   HOOK_FAIL_SEED=1 bash scripts/check-hook-decision-schema.sh  # 变异自证，应 EXIT=1

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL_SEED="${HOOK_FAIL_SEED:-0}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

violations=()

# ── 契约校验器 ──────────────────────────────────────────────
# stdin = 钩子 stdout。event = 该钩子注册的事件名。
verify() {
  local event="$1" out="$2" label="$3"
  [ -z "$out" ] && return 0
  local err
  err=$(EVENT="$event" python3 -c '
import sys, json, os
EVENT = os.environ.get("EVENT", "")
raw = sys.stdin.read().strip()
if not raw:
    sys.exit(0)
try:
    j = json.loads(raw)
except Exception as e:
    print(f"输出不是合法 JSON: {e}"); sys.exit(1)
if not isinstance(j, dict):
    print("输出不是 JSON 对象"); sys.exit(1)

errs = []
if "decision" in j and j["decision"] not in ("approve", "block"):
    errs.append(
        "顶层 decision=%r 非法（只接受 approve|block）；"
        "PreToolUse 请改用 hookSpecificOutput.permissionDecision" % (j["decision"],)
    )
hso = j.get("hookSpecificOutput")
if isinstance(hso, dict):
    hev = hso.get("hookEventName")
    if hev != EVENT:
        errs.append("hookEventName=%r，但该钩子注册在 %s" % (hev, EVENT))
    pd = hso.get("permissionDecision")
    if pd is not None and pd not in ("allow", "deny", "ask", "defer"):
        errs.append("permissionDecision=%r 非法" % (pd,))
    if EVENT == "Stop" and "permissionDecision" in hso:
        errs.append("Stop 钩子不该有 permissionDecision")
for e in errs:
    print("    " + e)
sys.exit(1 if errs else 0)
' <<< "$out")
  if [ -n "$err" ]; then
    violations+=("$label"$'\n'"$err")
  fi
}

# ── 探针：hook 文件 → 事件 + payload 列表 ───────────────────
# 覆盖今天真实出事的两个钩子，以及项目内所有自己构造输出的钩子。
# 每次探针喂多个 payload，确保放行/拦截/异常三条路径都被走到。
run_hook() {
  local hook="$1" event="$2"; shift 2
  local label="$event $(basename "$hook")"
  # 2026-10-03 修复假绿计数：原为 `|| return 0`，钩子缺失时静默跳过，
  # 而调用方无条件 scanned+1 —— 在 CI（没有 ~/.claude/hooks/）里会打印
  # 「实跑 9 个钩子 ✅ PASS」,实际只测了 5 个。现改为返回 1 并记录缺失。
  [ -f "$hook" ] || { echo "[SKIP] 钩子文件不存在，未纳入校验：$hook" >&2; return 1; }
  case "$hook" in *.sh) local runner=(bash "$hook");; *) local runner=(node "$hook");; esac

  local i=0
  for payload in "$@"; do
    i=$((i + 1))
    local out
    out=$(printf '%s' "$payload" | "${runner[@]}" 2>/dev/null)
    verify "$event" "$out" "$label  [payload#$i]"
  done
  return 0
}

R="$ROOT"
G="$HOME/.claude"

# PreToolUse 探针：普通放行 / rm 递归删除 / sudo / 空输入
BASH_PAYLOADS=(
  "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"echo hi\"},\"cwd\":\"$R\"}"
  "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"rm -rf ~\"},\"cwd\":\"$R\"}"
  "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"sudo rm /etc/hosts\"},\"cwd\":\"$R\"}"
  "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"\"},\"cwd\":\"$R\"}"
)
EDIT_PAYLOADS=(
  "{\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"/tmp/ok.txt\",\"content\":\"x\"},\"cwd\":\"$R\"}"
  "{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"/etc/hosts\",\"content\":\"x\"},\"cwd\":\"$R\"}"
  "{}"
)
STOP_PAYLOADS=(
  "{\"hook_event_name\":\"Stop\",\"session_id\":\"t\",\"cwd\":\"$R\"}"
  "{\"hook_event_name\":\"Stop\",\"session_id\":\"t\",\"transcript_path\":\"/dev/null\",\"cwd\":\"$R\"}"
  "{}"
)

scanned=0
required=0
skipped=()
# 仓内钩子：CI 里必须存在。缺失即计入 violations——否则门禁在 CI 里
# 一个钩子都没跑到却仍打印「✅ PASS」，是比原缺陷更隐蔽的假绿。
run_required() { local h="$1" e="$2"; shift 2; required=$((required+1));
                 if run_hook "$h" "$e" "$@"; then scanned=$((scanned+1)); else skipped+=("$h"); fi; }
# 全局钩子（~/.claude/hooks/）：只存在于开发机，CI 里没有属正常，缺失仅提示。
run_optional() { local h="$1" e="$2"; shift 2;
                 if run_hook "$h" "$e" "$@"; then scanned=$((scanned+1)); else skipped+=("$h"); fi; }

run_required "$R/.claude/hooks/block-dangerous-git.sh" PreToolUse "${BASH_PAYLOADS[@]}"
run_required "$R/.claude/helpers/sensitive-field-guard.cjs" PreToolUse "${EDIT_PAYLOADS[@]}"
run_required "$R/.claude/helpers/ratchet-data-guard.cjs" PreToolUse "${EDIT_PAYLOADS[@]}"
run_required "$R/.claude/helpers/ssot-write-guard.cjs" PreToolUse "${EDIT_PAYLOADS[@]}"
run_required "$R/.claude/helpers/ipd-frontend-drift-guard.cjs" PreToolUse "${EDIT_PAYLOADS[@]}"
run_required "$R/.claude/hooks/pre-java-yml-write.sh" PreToolUse "${EDIT_PAYLOADS[@]}"

run_optional "$G/hooks/irreversible-guard.cjs" PreToolUse "${BASH_PAYLOADS[@]}"
run_optional "$G/hooks/premature-done-guard.cjs" PreToolUse "${BASH_PAYLOADS[@]}"
run_optional "$G/hooks/reflection-gate.cjs" Stop "${STOP_PAYLOADS[@]}"

missing_required=0
for h in ${skipped[@]+"${skipped[@]}"}; do
  case "$h" in "$R"/*) missing_required=$((missing_required+1));; esac
done
if [ "$missing_required" -gt 0 ]; then
  violations+=("(钩子覆盖)"$'\n'"    仓内钩子缺失 $missing_required 个（共需 $required 个）—— CI 中必须存在，"
$'\n'"    否则本门禁没测到任何东西却仍报 PASS")
fi

if [ "$FAIL_SEED" = "1" ]; then
  echo "[HOOK_FAIL_SEED] 注入假阳性以验证门禁自身会红：顶层 decision='allow'"
  violations+=("(FAIL_SEED 注入)"$'\n'"    顶层 decision='allow' 非法（只接受 approve|block）")
fi

echo "校验钩子输出契约…  实跑 $scanned 个钩子 × 多路 payload（仓内必需 $required 个，缺失 ${#skipped[@]} 个）"

if [ ${#violations[@]} -eq 0 ]; then
  echo "✅ PASS：所有钩子的输出都符合契约"
  exit 0
fi

echo "❌ FAIL：发现 ${#violations[@]} 处"
for v in "${violations[@]}"; do
  echo "───────────────────────────────────────"
  echo "$v"
done
echo "───────────────────────────────────────"
echo
echo "后果：Claude Code 校验失败会**静默丢弃整份输出**——"
echo "      拦截类钩子等于没装，界面只在控制台刷一行红字。"
echo "修法："
echo "  PreToolUse  → hookSpecificOutput.permissionDecision = allow|deny|ask|defer"
echo "  Stop        → 放行就不输出；提示语走 hookSpecificOutput.additionalContext"
echo "  顶层 decision 只在需要 approve/block 时使用"
exit 1
