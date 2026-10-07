#!/bin/bash
# .claude/hooks/block-dangerous-git.sh
# PreToolUse(Bash) 守卫：**只管一件事——不把代码推到非私有仓库**。
#
# ============================================================================
# 2026-10-07 owner 拍板的口径变更
# ============================================================================
# 旧口径（2026-09~10-07）：命令文本里出现 `git push` / `reset --hard` /
# `clean -fd` / `branch -D` 等任一字面量 → 一律 exit 2 阻断。
# 实际后果：本仓的私有仓推送也被一并拦住，AI 无法完成「任务结束必提交推送」，
# 只能每次把命令交还给人。而本文件 2026-10-03 自己的注释第 ① 条早就写明：
#   判定标准是「命令字符串里有没有这个子串」，**与这条命令会不会真的执行该动作无关**，
#   两个方向都错（既误报又漏报）。
# 即：旧守卫为了防误伤，把正常业务动作（推自己的私有仓）也一起杀了。
#
# 新口径（owner 2026-10-07 原话「严格限制不推送到非私有库即可」）：
#   ✅ 推送到 owner 指定的私有仓 → 放行
#   ❌ 推送到其它任何地方（upstream / 公开仓 / 未知远端）→ 阻断
#   ✅ 其余命令（reset --hard / clean -fd / branch -D / push --force 等）→ 一律放行
#
# 允许清单（判据是**远端 URL**，不是分支名）：
#   wilson323/ruoyi-ai     后端仓，分支固定 baseline/pre-teardown
#   wilson323/ruoyi-admin  前端仓，分支固定 teardown/incentive-removal
# 判据取 URL 而非分支名：同一个私有名下换个分支也推不出去，这比按名字判严。
# ============================================================================

set -u

INPUT=$(cat)
COMMAND=$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty' 2>/dev/null)
[ -z "$COMMAND" ] && exit 0

# ---------------------------------------------------------------------------
# 1. 是不是 git push？
#    先把引号/换行/多余空白抹平再判，避免旧口径记在案的两种漏报形态：
#    `git  push`（多空格）、`git pu"sh"`（引号拼接）。
#    ⚠ 仍不是严格命令解析：形如 `alias gp='git push'; gp` 这类别名间接调用
#    依旧判不出来。本守卫是「便宜的前置检查」，不是沙箱，别把它的放行当安全证明。
# ---------------------------------------------------------------------------
# 换行/制表符要**换成空格**而不是删掉——删掉会把 `git status\ngit push upstream`
# 粘成 `git statusgit push upstream`，反而让第二条命令消失（2026-10-07 自测抓到的自伤 bug）。
FLAT=$(printf '%s' "$COMMAND" | tr '\n\r\t' '   ' | tr -d '"\''`' | tr -s ' ')
# 不要求 git 前面是分隔符：`bash -c "git push upstream"` 这类包裹必须照样识别，
# 否则换个 shell 包一层就绕过去了（2026-10-07 自测抓到的绕过）。
printf '%s' "$FLAT" | grep -qE 'git[[:space:]]+(-[^[:space:]]+[[:space:]]+)*push([[:space:]]|$)' || exit 0

# ---------------------------------------------------------------------------
# 2. 取出远端名：push 之后第一个不以 - 开头的 token；没有则取 origin。
# ---------------------------------------------------------------------------
REMOTE=$(printf '%s' "$FLAT" \
  | sed -nE 's/.*git[[:space:]]+(-[^[:space:]]+[[:space:]]+)*push[[:space:]]+//p' \
  | awk '{for (i=1;i<=NF;i++) if ($i !~ /^-/) {print $i; exit}}')
[ -z "$REMOTE" ] && REMOTE="origin"

# ---------------------------------------------------------------------------
# 3. 解析远端 URL 并对照允许清单。
#    三种形态都要认，否则会误杀（2026-10-07 实测修的两个 bug）：
#      a) push 的位置参数就是远端名：git push origin main
#      b) push 的位置参数就是 URL  ：git push https://github.com/x/y.git main
#      c) 没给远端名，第一个参数其实是分支：git push main
#         —— 此时不能把分支名当远端名去查（查不到 → 误判为非私有 → 误杀），
#            必须回落到 origin。
# ---------------------------------------------------------------------------
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
URL=""
case "$REMOTE" in
  *://*|*@*:*) URL="$REMOTE" ;;   # (b) 本身就是 URL
esac
if [ -z "$URL" ]; then
  URL=$(cd "$REPO_ROOT" 2>/dev/null && git remote get-url "$REMOTE" 2>/dev/null)
fi
if [ -z "$URL" ] && [ "$REMOTE" != "origin" ]; then
  # (c) $REMOTE 查不到 → 多半是分支名而非远端名，回落 origin
  URL=$(cd "$REPO_ROOT" 2>/dev/null && git remote get-url origin 2>/dev/null)
fi

# --- 精确比对 owner/repo，绝不用「包含」匹配 ---
# 2026-10-07 自测抓到的洞：原来写 `case "$URL" in *wilson323/ruoyi-ai*)`，
# 于是下面三个**公开仓**全部被误判成私有仓放行：
#     github.com/attacker/wilson323/ruoyi-ai.git      （私有仓名塞进别人的路径）
#     github.com/wilson323/ruoyi-ai-EVIL.git         （后缀拼接）
#     https://evil@github.com/x/wilson323/ruoyi-ai    （用户名里带私有仓名）
# 现在：剥掉 scheme、剥掉 userinfo（user:pass@）、剥掉 .git 与尾斜杠，
# 只取路径最后两段作为 owner/repo，与允许清单做**整串相等**比较。
SAFE_URL=$(printf '%s' "$URL" \
  | sed -E 's#^[A-Za-z][A-Za-z0-9+.-]*://##; s#^[^/@]*@##; s#^([^/:]+):(.*)$#\1/\2#' \
  | sed -E 's#\.git$##; s#/*$##')
HOST=$(printf '%s' "$SAFE_URL" | cut -d/ -f1)
OWNER_REPO=$(printf '%s' "$SAFE_URL" | cut -d/ -f2-)
# 主机名也要锁：本仓两个目标都在 github.com 且路径正好是 owner/repo 两段。
# 只取「最后两段」比对会漏掉嵌套路径（2026-10-07 自测抓到）：
#     github.com/attacker/wilson323/ruoyi-ai   → 末两段恰好等于允许项 → 误放行
# 锁死主机 + 要求路径整串相等，仿冒就没有落身之处。
ALLOWED=0
if [ "$HOST" = "github.com" ]; then
  case "$OWNER_REPO" in
    wilson323/ruoyi-ai|wilson323/ruoyi-admin) ALLOWED=1 ;;
  esac
fi
[ "$ALLOWED" = "1" ] && exit 0

# ---------------------------------------------------------------------------
# 4. 拦非私有仓 + 留痕（只记 远端 + URL + 命中上下文 60 字符，不记命令全文，
#    避免命令里的凭证原样落盘）。写不出日志也照拦。
# ---------------------------------------------------------------------------
{
  [ -n "$REPO_ROOT" ] && [ -d "$REPO_ROOT/.harness/audit" ] || exit 0
  HASH=$(printf '%s' "$COMMAND" | shasum -a 256 2>/dev/null | cut -d' ' -f1)
  # 上下文片段同样会连着 URL 一起把 user:pass 带出来，必须一起洗掉。
  # 2026-10-07 自测实测到：只洗 url 字段不够，remote 与 match_context 两个字段
  # 都会把明文口令落进 .harness/audit/。三处都过一遍脱敏。
  # 读标准输入，不是 $1：调用方式是 `printf '%s' "$X" | scrub`，
  # 若函数体写 printf '%s' "$1" 则 $1 为空，输出整段变空串（日志会丢字段）。
  # 2026-10-07 自测：这是脱敏第一版把 remote / match_context 洗成空的原因。
  scrub() { sed -E 's#(://)?[^/@[:space:]]+:[^/@[:space:]]+@#\1***:***@#g'; }
  CTX=$(printf '%s' "$FLAT" | grep -oE '.{0,60}push.{0,60}' 2>/dev/null | head -1 | scrub)
  REMOTE_LOG=$(printf '%s' "$REMOTE" | scrub)
  SESSION=$(printf '%s' "$INPUT" | jq -r '.session_id // empty' 2>/dev/null)
  # 绝不把带凭证的 URL（https://user:pass@…）原样落盘——用 SAFE_URL（已剥 userinfo）
  printf '{"ts":"%s","session":"%s","rule":"push-non-private","remote":"%s","url":"%s","cmd_sha256":"%s","cmd_len":%s,"match_context":"%s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$(printf '%s' "$SESSION" | sed 's/\\/\\\\/g; s/"/\\"/g')" \
    "$(printf '%s' "$REMOTE_LOG" | sed 's/\\/\\\\/g; s/"/\\"/g')" \
    "$(printf '%s' "$SAFE_URL" | sed 's/\\/\\\\/g; s/"/\\"/g')" \
    "$HASH" "${#COMMAND}" \
    "$(printf '%s' "$CTX" | sed 's/\\/\\\\/g; s/"/\\"/g')" \
    >> "$REPO_ROOT/.harness/audit/blocked-$(date -u +%Y-%m-%d).jsonl"
} 2>/dev/null || true

{
  echo "BLOCKED: 拒绝推送到非私有仓库。"
  echo "  远端: $REMOTE"
  echo "  目标: ${OWNER_REPO:-<解析不到>}"
  echo "  允许清单（owner 指定的私有仓）：wilson323/ruoyi-ai、wilson323/ruoyi-admin"
  echo "  处置：这是本项目铁律一——只允许推送到 owner 指定的私有仓。"
  echo "        确认目标确实是私有仓后，改用显式远端名重试（如 git push origin <branch>）。"
  echo "  留痕: .harness/audit/blocked-$(date -u +%Y-%m-%d).jsonl"
} >&2

exit 2
