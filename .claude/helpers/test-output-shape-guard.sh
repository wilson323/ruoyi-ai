#!/bin/bash
# output-shape-guard 的行为测试集（阳性对照 + 阴性对照用例；本文件即该守卫的自证）
# output-shape-guard 的行为测试
G="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/output-shape-guard.cjs"
node --check "$G" || exit 1
pass=0; fail=0
t() { # t <描述> <期望: ALERT|QUIET> <payload-json>
  local desc="$1" want="$2" json="$3" got
  err=$(printf '%s' "$json" | node "$G" 2>&1 >/dev/null; true)
  if echo "$err" | grep -q '输出形状守卫'; then got=ALERT; else got=QUIET; fi
  if [ "$got" = "$want" ]; then pass=$((pass+1)); printf '  ✅ %-44s %s\n' "$desc" "$got"
  else fail=$((fail+1)); printf '  ❌ %-44s %s（期望 %s）\n' "$desc" "$got" "$want"; fi
}
mkcmd() { printf '{"tool_name":"Bash","tool_input":{"command":"%s"},"tool_response":{"stdout":"%s","exit_code":%s}}' "$1" "$2" "$3"; }

echo "=== A. 应告警：工具级失败但退出码 0（今天 sed bug 的形态）==="
t "sed bad flag 但 exit 0"  ALERT "$(mkcmd 'bash x.sh' 'x.sh: bad flag in substitute command' 0)"
t "unbound variable"       ALERT "$(mkcmd 'bash y.sh' 'y.sh: line 5: FOO: unbound variable' 0)"
t "node 崩溃栈"             ALERT "$(mkcmd 'node z.js' 'Node.js v22.22.3' 0)"
t "静默降级 fallback"       ALERT "$(mkcmd 'node w.js' 'distill filter failed; falling back to raw' 0)"

echo
echo "=== B. 应告警：成功但空输出（本应有输出的命令族）==="
t "git status 返回空"      ALERT "$(mkcmd 'git status' '' 0)"
t "find 返回空"            ALERT "$(mkcmd 'find . -name x' '' 0)"

echo
echo "=== C. 必须安静：正常输出（误伤是这类守卫最大的风险）==="
t "正常 git status"        QUIET "$(mkcmd 'git status' 'On branch main' 0)"
t "正常脚本输出"           QUIET "$(mkcmd 'bash x.sh' 'done ok' 0)"
t "退出码非 0"             QUIET "$(mkcmd 'bash x.sh' 'error: something' 1)"
t "mkdir 无输出"           QUIET "$(mkcmd 'mkdir -p a' '' 0)"
t "非 Bash 工具"          QUIET '{"tool_name":"Read","tool_input":{"file_path":"/x"}}'
t "含 error 字样的业务日志" QUIET "$(mkcmd 'grep -r error .' 'src/a.java:12: log.error(\"x\")' 0)"

echo
echo "=== D. 自身故障必须放行（最关键：守卫坏了不能阻断一切）==="
t "空 stdin"               QUIET ''
t "非法 JSON"              QUIET 'not-json{'
t "无 output 字段"         QUIET '{"tool_name":"Bash","tool_input":{"command":"git status"}}'

echo
echo "=== 汇总：PASS=$pass FAIL=$fail ==="
[ "$fail" = "0" ] && echo "✅ 全绿" || echo "❌ 有 $fail 条异常"
exit "$fail"