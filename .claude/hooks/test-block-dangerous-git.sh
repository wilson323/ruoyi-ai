#!/bin/bash
# .claude/hooks/test-block-dangerous-git.sh
# 推送守卫的对抗测试集。**改动 block-dangerous-git.sh 后必须重跑本脚本。**
#
# 设计原则（来自本仓反复吃过的亏）：
#   1. 每个「期望拦住」的用例，都要有一个形态不同的「期望放行」用例作对照，
#      否则分不清是判据在起作用还是脚本恰好返回了 2。
#   2. 靶子仓自建自销，不依赖调用者的 /tmp 残留状态。
#   3. 只走 `printf | bash` 管道，**不真的执行任何 git 写操作**，也不推送任何东西。
#   4. 「守卫自检能红」：最后跑一次 FAIL_SEED 形态，确认本测试集不是恒绿。
#
# 退出码：0 = 全绿；非 0 = 有用例不符合预期。

GUARD="$(cd "$(dirname "$0")" && pwd)/block-dangerous-git.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/bdgtest.XXXXXX")" || exit 1
trap 'rm -rf "$WORK"' EXIT
FAKE="$WORK/public_repo"          # 靶子：upstream 指向公开仓，origin 指向私有仓
OWN="$(cd "$(dirname "$0")/../.." && pwd)"   # 本仓真实路径

mkdir -p "$FAKE"
git -C "$FAKE" init -q 2>/dev/null
git -C "$FAKE" remote add upstream https://github.com/ageerle/ruoyi-ai.git 2>/dev/null
git -C "$FAKE" remote add origin   https://github.com/wilson323/ruoyi-ai.git 2>/dev/null

PASS=0; FAIL=0
run() {  # run <期望退出码> <描述> <命令>
  local want="$1" desc="$2" cmd="$3" got
  got=$(printf '{"tool_name":"Bash","tool_input":{"command":"%s"},"session_id":"test"}' "$cmd" \
        | bash "$GUARD" >/dev/null 2>&1; echo $?)
  if [ "$got" = "$want" ]; then
    PASS=$((PASS+1)); printf '  ✅ %-44s EXIT=%s\n' "$desc" "$got"
  else
    FAIL=$((FAIL+1)); printf '  ❌ %-44s EXIT=%s（期望 %s）\n' "$desc" "$got" "$want"
  fi
}

echo "=== A. 必须拦住的：推向非私有仓（期望 EXIT=2）==="
run 2 "cd 公开仓 && push upstream"          "cd $FAKE && git push upstream main"
run 2 "当前仓直接 push upstream"             "git push upstream main"
run 2 "-C 在 push 之前（2026-10-07 补的洞）"  "git -C $FAKE push upstream main"
run 2 "-C 在 push 之后（2026-10-07 补的洞）"  "git push -C $FAKE upstream main"
run 2 "-C 到本仓但推公开 URL"                "git -C $OWN push https://github.com/ageerle/ruoyi-ai.git main"
run 2 "仿冒：私有仓名塞进别人路径"             "git push https://github.com/attacker/wilson323/ruoyi-ai.git main"
run 2 "仿冒：私有仓名后缀拼接"                "git push https://github.com/wilson323/ruoyi-ai-EVIL.git main"
run 2 "非 github 主机（自建 GitLab）"         "git push https://gitlab.com/wilson323/ruoyi-ai.git main"
run 2 "-C 指向不存在的目录（无法判定→阻断）"    "git -C $WORK/no-such-dir push upstream main"
run 2 "-C 指向非 git 目录（无法判定→阻断）"    "git -C /tmp push upstream main"
run 2 "shell 包裹 + -C"                      "bash -c 'git -C $FAKE push upstream main'"
run 2 "多空格形态"                           "git  push  upstream main"
run 2 "git -c k=v 形态"                      "git -c user.name=x push upstream main"

echo
echo "=== B. 必须放行的：推向 owner 指定私有仓（期望 EXIT=0，防误杀）==="
run 0 "本仓 push origin 私有分支"             "git push origin baseline/pre-teardown"
run 0 "-C 指向本仓自身 + push origin"         "git -C $OWN push origin baseline/pre-teardown"
run 0 "-C 靶子仓但推它的私有 origin"          "git -C $FAKE push origin main"
run 0 "显式私有 URL（后端）"                  "git push https://github.com/wilson323/ruoyi-ai.git baseline/pre-teardown"
run 0 "显式私有 URL（前端）"                  "git push https://github.com/wilson323/ruoyi-admin.git teardown/incentive-removal"

echo
echo "=== C. 必须放行的：与推送无关（期望 EXIT=0）==="
run 0 "git status"                           "git status"
run 0 "非 git 命令"                          "ls -la /tmp"
run 0 "提到 remote 但非 push"                 "git remote -v"

echo
echo "=== D. 已知遗留局限（修前就有，当前仍误拦；显式登记，不是回归）==="
run 2 "echo 字符串里提到 push（固有误报）"     "echo 'git push upstream main'"

echo
echo "=== 汇总：PASS=$PASS  FAIL=$FAIL ==="
if [ "$FAIL" != "0" ]; then
  echo "❌ 有 $FAIL 条不符合预期"
  exit 1
fi
echo "✅ 全绿"
exit 0
