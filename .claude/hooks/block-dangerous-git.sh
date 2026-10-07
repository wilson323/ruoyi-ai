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
#
# ============================================================================
# 2026-10-07 第二轮修补：`-C` / `--git-dir` 换目录绕过
# ============================================================================
# 复核发现的洞：判据与取远端名的正则都要求 `git` 与 `push` 之间**只夹单 token
# 的 -xxx 选项**。`git -C <目录> push upstream main` 夹的是「选项 + 值」两段，
# 值不以 `-` 开头 → 两个正则同时失配 → REMOTE 为空 → 回落 origin → 查的是
# **本仓** origin（私有）→ 放行。而 `-C` 指向的仓库与本仓毫无关系。
#
# 实测（旧版 vs 新版，逐用例对照）：
#     git -C <公开仓> push upstream main          旧 0 → 新 2   ✅补上
#     git push -C <公开仓> upstream main          旧 0 → 新 2   ✅补上
#     git -C <不存在目录> push upstream main       旧 0 → 新 2   ✅补上
#     bash -c 'git -C <公开仓> push upstream main' 旧 0 → 新 2   ✅补上
#     其余 13 条用例全部「未变」，**无一条放松**。
#
# 修法不是「见到 -C 就拦」（那会误杀 `git -C <本仓> push origin <分支>`），
# 而是解析出**推送真正落到的仓库**，用那个仓库查远端；解析不出仓库 → 按未知阻断。
#
# 踩过的坑（都靠「先验仪器再读数」抓出来，记下来防重犯）：
#   1. sed 写 `s# ##$##` —— `#` 兼作分隔符与字面量，sed 报 bad flag 并**输出空串**，
#      而整条管道退出码仍是 0 → 判据永失配 → 守卫变永放行。已加空值自检兜底。
#   2. 剥 `-c k=v` 时写成 `-c <任意>` → 把 **shell 自己的 `bash -c`** 当成 git 的
#      `-c`，连带吃掉后面的 `git`，残留文本里没有 `git push` → 又漏。
#      已改为只剥「值里含 `=`」的 `-c`。
#
# 已知遗留局限（**修前就有，非本次引入**，实测旧版行为完全相同）：
#   `echo 'git push upstream main'` 这类「字符串里提到 push」的**非推送命令**会被
#   误拦。因为引号被抹平后，文本里已无法区分 echo 的参数与真实命令。
#   根治需引入真正的 shell 解析 = 沙箱职责，超出本守卫定位，故显式登记不修。
#
# 对抗测试集：`.claude/hooks/test-block-dangerous-git.sh`
#   改动本文件后必须重跑，应全绿（17 条应拦 + 8 条应放行）。
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
FLAT=$(printf '%s' "$COMMAND" | tr '\n\r\t' '   ' | tr -d '\042\047\140' | tr -s ' ')
# 判据用的文本：引号换成**空格**而不是删掉。
# 理由：`bash -c 'git push upstream main'` 里，剥掉 -C <目录> 这类带值参数后，
# `git` 后面紧跟引号，判据要求 `git<空格>+push` 就会失配 → 整条包裹命令漏放行。
# 2026-10-07 复核实测：`bash -c 'git -C <公开仓> push upstream main'` 旧版即 EXIT=0，
# 而本文件原注释却声称「bash -c 这类包裹必须照样识别」——**声明与实况不符**。
# 这里用八进制写引号字符，避免 tr 字符集写法歧义（'\047' 在部分写法下不生效）。
JUDGE=$(printf '%s' "$COMMAND" | python3 -c '
import sys, re
text = sys.stdin.read()
# 剥掉 heredoc 正文。2026-10-07 自伤实录：写一条「推送守卫已加分支判据」的
# commit message 时，message 里出现推送命令字样，被守卫当成真命令拦下，
# 自己把自己锁在门外——规则文本长得像命令，就被当命令判了。
# 只保留 `<<TAG` 之前的部分（那里才是真命令），正文直到 TAG 行整段丢弃。
out, lines, i = [], text.split("\n"), 0
while i < len(lines):
    line = lines[i]
    m = re.search(r"<<-?[ \t]*[\"\x27]?([A-Za-z_][A-Za-z0-9_]*)[\"\x27]?", line)
    if m:
        out.append(line[:m.start()])
        tag = m.group(1)
        i += 1
        while i < len(lines) and lines[i].strip() != tag:
            i += 1
        i += 1
        continue
    out.append(line)
    i += 1
flat = " ".join(out)
# 引号换成空格（不删）：删掉会把 git 与 push 粘成一个词反而漏判，
# 且 shell -c 的内层命令要靠剥引号才暴露得出来。
flat = flat.replace(chr(34), " ").replace(chr(39), " ").replace(chr(96), " ")
flat = flat.replace("\t", " ")
print(re.sub(r"[ ]{2,}", " ", flat).strip())
')
# 带值参数段（-C <目录> / -c k=v / --git-dir=<目录>）会让「git 与 push 之间只夹
# 单 token -xxx」的判据失配。2026-10-07 实测：`git -C <公开仓> push upstream main`
# 与 `git -c k=v push upstream main` 都在这里被放行。先剥掉再判。
STRIPPED=$(printf '%s' "$JUDGE" \
  | sed -E -e 's#(^|[[:space:]])-C[[:space:]]+[^[:space:]]+##g' \
          -e 's#(^|[[:space:]])--git-dir=[^[:space:]]+##g' \
          -e 's#(^|[[:space:]])-c[[:space:]]+[^[:space:]]*=[^[:space:]]+##g' \
          -e 's#[[:space:]]+# #g' -e 's#^ ##' -e 's#[[:space:]]+$##')
# 3a. 只认「命令位置」上的 git 推送命令。第二段自伤实录：echo 一段含推送命令的
#     文字时，那串字只是被打印出来的文本，不是命令，却照样被判成推送而阻断。
#     命令位置 = 字符串开头，或 ; && || | ( 之后；其余位置出现的一律不算。
#     shell -c 包裹单独处理：剥引号后内层文本暴露，递归按同规则再判一次（深度封顶 2）。
#     必须跑在 STRIPPED 之后：-C <目录> 这类带值参数不先剥掉，段首就不是 git。
SEP=$(printf '\001')
IS_PUSH=$(printf '%s' "$STRIPPED" | SEP="$SEP" python3 -c '
import os, sys, re
SEP = os.environ["SEP"]
PUSH_HEAD = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*=[^ ]* )*(?:[^ ]*/)?git( -[^ ]+)* push(?![A-Za-z])")
PUSH_TIGHT = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*=[^ ]*)*(?:[^/]*/)?git(-[^ ]+)*push(?![A-Za-z])")
WRAPPER = re.compile(r"^(?:ba|z|k|da)?sh +-c +(.*)$")
_WS = re.compile(r"[ \t]+")
def is_push(seg):
    seg = seg.strip()
    if not seg:
        return False
    if PUSH_HEAD.match(seg):
        return True
    return bool(PUSH_TIGHT.match(_WS.sub("", seg)))
def scan(text, depth=0):
    try:
        segs = re.sub(r"&&|\|\||;|\(|\)", SEP, text).split(SEP)
    except Exception:
        return False
    for seg in segs:
        seg = seg.strip()
        if not seg:
            continue
        if is_push(seg):
            return True
        if depth < 2:
            m = WRAPPER.match(seg)
            if m and scan(m.group(1), depth + 1):
                return True
    return False
print("1" if scan(sys.stdin.read()) else "0")
') || { echo "BLOCKED: 守卫自身判据执行失败——无法判断是否推送，按铁律一不放行。" >&2; exit 2; }

# sed 静默失败会让 STRIPPED 变空 → 判据全失配 → 守卫变成永放行（失败长得像成功）。
# 2026-10-07 实测踩到过：`s# ##$##` 里 `#` 兼作分隔符与字面量，sed 报 bad flag
# 并输出空串，而整条管道退出码仍是 0。这里自检一次形状，非空才允许继续。
if [ -z "$STRIPPED" ] && [ -n "$FLAT" ]; then
  echo "BLOCKED: 守卫自检失败（剥参数后文本为空，无法判断是否推送）——按铁律一不放行。" >&2
  exit 2
fi
RE_PUSH='git[[:space:]]+(-[^[:space:]]+[[:space:]]+)*push([[:space:]]|$)'
# 判据取命令位置扫描结果（IS_PUSH，定义见 3a）。原来的「整段文本 grep」有两个问题，
# 本轮实测各踩一次：
#   ① 假阳性：echo / commit message 里出现的推送命令**字样**也被当成命令 → 阻断自己干活。
#   ② 叠加分支判据后更糟：仓库对、分支不对，于是这条假阳性直接变成硬阻断。
# 保留 RE_PUSH 仅供日志旁证，不再作为放行/阻断依据——依据必须是 IS_PUSH。
case "$IS_PUSH" in
  1) PUSH_SEEN=1 ;;
  0) PUSH_SEEN=0 ;;
  *) echo "BLOCKED: 守卫判据返回异常值（IS_PUSH=${IS_PUSH:-空}）——按铁律一不放行。" >&2; exit 2 ;;
esac
[ "$PUSH_SEEN" = "1" ] || exit 0
# ---------------------------------------------------------------------------
# 2. 取出远端名：push 之后第一个不以 - 开头的 token；没有则取 origin。
#
#    ⚠ 2026-10-07 复核抓到的漏报：上面的两个正则都要求 `git` 与 `push` 之间
#    只夹**单 token 的 -xxx 选项**。而 `git -C <目录> push upstream main` 夹的是
#    `-C <目录>`，其中 `<目录>` 不以 `-` 开头 → 两个正则同时失配 → REMOTE 为空
#    → 落回 origin → 查的是**本仓**的 origin（私有）→ 放行。
#    于是「换个目录推公开仓」这条铁律一最典型的绕过姿势畅通无阻。
#    对照：`cd <公开仓> && git push upstream main` 是拦的（EXIT=2），形态一换就漏。
#
#    修法（不是「见到 -C 就拦」——那会误杀 `git -C <本仓> push origin <分支>`）：
#    先把推送**真正落到的仓库**解析出来，用那个仓库去查远端。
#    -C / --git-dir 指向别处 → 用那边的仓库查远端，查不到就按未知仓库阻断。
# ---------------------------------------------------------------------------

# 2a. 先取出 git 的工作目录参数（-C <dir> 与 --git-dir=<dir>，位置可在 push 前后）
GIT_DIR_ARG=$(printf '%s' "$FLAT" \
  | grep -oE '(^|[[:space:]])(-C|--git-dir)[[:space:]]+[^[:space:]]+' \
  | head -1 | sed -E 's/^.*(-C|--git-dir)[[:space:]]+//')
[ -z "$GIT_DIR_ARG" ] && GIT_DIR_ARG=$(printf '%s' "$FLAT" \
  | grep -oE '(^|[[:space:]])--git-dir=[^[:space:]]+' \
  | head -1 | sed -E 's/^.*--git-dir=//')

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null)

# 有工作目录参数时：解析成绝对路径。解析不出来 → 未知仓库 → 后面按阻断处理。
TARGET_ROOT="$REPO_ROOT"
if [ -n "$GIT_DIR_ARG" ]; then
  case "$GIT_DIR_ARG" in
    /*) CANDIDATE="$GIT_DIR_ARG" ;;
    *)  CANDIDATE="$(pwd 2>/dev/null)/$GIT_DIR_ARG" ;;
  esac
  RESOLVED=$(cd "$CANDIDATE" 2>/dev/null && git rev-parse --show-toplevel 2>/dev/null)
  if [ -z "$RESOLVED" ]; then
    # 目录不存在 / 不是 git 仓库 / 没有权限 —— 一律视为未知仓库，放行等于放飞
    TARGET_ROOT=""
  else
    TARGET_ROOT="$RESOLVED"
  fi
fi

# 2b. 取远端名。在**剥完 -C/-c 等带值参数**的文本上取（STRIPPED，见上），
#     否则 `git push -C /path upstream` 会把目录路径当成远端名。
REMOTE=$(printf '%s' "$STRIPPED" \
  | sed -nE 's/.*git[[:space:]]+(-[^[:space:]]+[[:space:]]+)*push[[:space:]]+//p' \
  | awk '{for (i=1;i<=NF;i++) if ($i !~ /^-/) {print $i; exit}}')
[ -z "$REMOTE" ] && REMOTE="origin"

# 未知仓库（-C 指向处解析不出 git 仓库）：本守卫无法证明它是私有仓 → 直接阻断。
if [ -z "$TARGET_ROOT" ]; then
  ALLOWED=0
  UNRESOLVED=1
fi

# ---------------------------------------------------------------------------
# 3. 解析远端 URL 并对照允许清单。
#    三种形态都要认，否则会误杀（2026-10-07 实测修的两个 bug）：
#      a) push 的位置参数就是远端名：git push origin main
#      b) push 的位置参数就是 URL  ：git push https://github.com/x/y.git main
#      c) 没给远端名，第一个参数其实是分支：git push main
#         —— 此时不能把分支名当远端名去查（查不到 → 误判为非私有 → 误杀），
#            必须回落到 origin。
# ---------------------------------------------------------------------------
URL=""
case "$REMOTE" in
  *://*|*@*:*) URL="$REMOTE" ;;   # (b) 本身就是 URL
esac
if [ -z "$URL" ] && [ -n "$TARGET_ROOT" ]; then
  URL=$(cd "$TARGET_ROOT" 2>/dev/null && git remote get-url "$REMOTE" 2>/dev/null)
fi
if [ -z "$URL" ] && [ "$REMOTE" != "origin" ] && [ -n "$TARGET_ROOT" ]; then
  # (c) $REMOTE 查不到 → 多半是分支名而非远端名，回落 origin
  URL=$(cd "$TARGET_ROOT" 2>/dev/null && git remote get-url origin 2>/dev/null)
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

# ---------------------------------------------------------------------------
# 3b. 分支维度（owner 2026-10-07 追加：这两个仓的**其他分支**同样禁止推送）。
#     仓库对上了不代表分支也对得上：`git push origin main` 目标仓合法、写入分支不合法。
#     这里要求「恰好一个 refspec，且逐字等于该仓的固定分支」，以下全部阻断：
#       git push origin                              （无 refspec = 无法证明写哪里）
#       git push origin HEAD                         （HEAD 不是固定分支名）
#       git push origin main                         （非固定分支）
#       git push origin baseline/pre-teardown:main   （会把固定分支的内容写进 main）
#     宁可误杀也不放行：写错分支的代价（污染 main / 动别人的分支）远大于重敲一遍命令。
# ---------------------------------------------------------------------------
FIXED_BRANCH=""
case "$OWNER_REPO" in
  wilson323/ruoyi-ai)    FIXED_BRANCH="baseline/pre-teardown" ;;
  wilson323/ruoyi-admin) FIXED_BRANCH="teardown/incentive-removal" ;;
esac

PUSH_TAIL=$(printf '%s' "$STRIPPED" \
  | sed -nE 's/.*git[[:space:]]+(-[^[:space:]]+[[:space:]]+)*push[[:space:]]+//p')
# 第 1 个非 flag token 是远端名/URL（上面 REMOTE 就是这么取的），其余才是 refspec。
REFSPECS=$(printf '%s' "$PUSH_TAIL" \
  | tr ' \t' '\n\n' \
  | grep -vE '^(-|$)' \
  | sed -n '2,$p' \
  | sed 's/[[:space:]]*$//')
REFSPEC_COUNT=$(printf '%s' "$REFSPECS" | grep -c . || true)
BRANCH_OK=0
if [ "$REFSPEC_COUNT" = "1" ] && [ "$REFSPECS" = "$FIXED_BRANCH" ]; then
  BRANCH_OK=1
fi

# ALLOWED=0 的三种成因，日志/提示里要能区分，否则排查时看不出是哪一种漏网：
#   UNRESOLVED=1 → -C/--git-dir 指向处不是可解析的 git 仓库，本守卫无从判断
#   HOST 非 github.com → 目标在别的主机（自建 GitLab 等）
#   两者皆非 → 是远端名/URL 明确解析出来了，但不在允许清单里
# ALLOWED=1 但 BRANCH_OK=0 是**第四种**成因：仓库对、分支不对。
[ "$ALLOWED" = "1" ] && [ "$BRANCH_OK" = "1" ] && exit 0

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
  echo "BLOCKED: 拒绝推送。"
  echo "  远端: $REMOTE"
  if [ -n "${UNRESOLVED:-}" ]; then
    echo "  目标: <无法确认> —— 命令里的 -C / --git-dir 指向处解析不出 git 仓库"
    echo "        本守卫无法证明目标是私有仓，按铁律一不放行。"
  else
    echo "  目标: ${OWNER_REPO:-<解析不到>}"
  fi
  if [ "$ALLOWED" = "1" ]; then
    echo "  分支: 仓库在允许清单里，但分支不符。"
    echo "        该仓固定分支: ${FIXED_BRANCH}"
    echo "        实际 refspec: ${REFSPECS:-<无>}"
    echo "  处置：owner 2026-10-07 明令——这两个仓的**其他分支**同样禁止推送；"
    echo "        不新建、不切换、不向 main 或其他分支合并/变基作为最终交付。"
    echo "        重试请写明固定分支：git push origin ${FIXED_BRANCH}"
  else
    echo "  允许清单（owner 指定的私有仓）：wilson323/ruoyi-ai、wilson323/ruoyi-admin"
    echo "  处置：这是本项目铁律一——只允许推送到 owner 指定的私有仓。"
    echo "        确认目标确实是私有仓后，改用显式远端名重试（如 git push origin <branch>）。"
  fi
  echo "  留痕: .harness/audit/blocked-$(date -u +%Y-%m-%d).jsonl"
} >&2

exit 2
