#!/bin/bash
# .claude/hooks/block-dangerous-git.sh
# PreToolUse(Bash) 守卫：命令文本里出现危险 git 字面量 → 阻断（exit 2）。
# 来源：mattpocock-skills 的 git-guardrails-claude-code。
#
# ============================================================================
# 2026-10-03 实测记录 —— 改这个文件之前必读（以下每条都跑过，是实证不是推测）
# ============================================================================
# ① 判定标准是「命令字符串里有没有这个子串」，**与「这条命令会不会执行该动作」无关**。
#    因此它两个方向都会错，且两个方向当天都实测坐实：
#
#    误报（命令只是「提到」该字面量，实测全部 EXIT=2，被拦）：
#      echo "the string git push inside a quoted echo"
#      grep -rn 'git push' docs/
#      git commit -m "note: never run git push here"
#      # git push is documented in deploy.md
#      printf '%s' 'git clean -fd'
#      ls docs/ | grep -i 'git branch -D'
#
#    漏报（真的是危险动作，实测全部 EXIT=0，放行）：
#      git  push -u origin main          （`git` 与 `push` 之间两个空格）
#      git pu"sh" -u origin main         （引号拼接）
#      git -c http.sslVerify=true push -u origin main
#
#    即：文本匹配不是「命令解析的严格版」，它是**另一把尺子**。
#    「被本守卫拦住」不蕴含「该动作危险」；放行也不蕴含「安全」。
#    凡引用本守卫的结论，都必须先问：它判的是文本还是动作？
#
# ② 本守卫**没有任何出口**：无 SKIP_ 变量、无白名单、无签字路径。
#    （对照：~/.claude/hooks/hook_guard_backtick.py 明写三种替代做法。）
#    ⚠ 别被同目录的 pre-commit-coverage.sh 误导：它的报错信息里教用户用
#      `SKIP_COVERAGE_GATE=1` 绕过，但**该变量从未被任何代码读取**——2026-10-03
#      实测全仓 5 处命中全是提示文案与文档，0 处是读取代码（含它调用的
#      scripts/check-test-coverage-by-domain.sh，其中无 SKIP* 字样）。
#      即那句「逃生口」本身就是一个「说明与行为不符」的实例。
#      （本条的教训：我最初是从它的报错文案里读到这个变量名就写进了上一版注释，
#        没去读代码——「读了提示语就当成行为」正是本文件在防的那个形状。）
#    后果：撞上误报时唯一出路是「改命令措辞去避开匹配器」，而**改措辞**与
#    **放弃那个动作**在外部看起来完全一样 —— 光看「被拦」分不出来。
#    故 2026-10-03 起：判定不动，但**留痕**（见 ⑤），让事后可分类。
#
# ③ 全仓没有任何测试断言本脚本的行为（2026-10-03 grep：只有 docs 与
#    settings.json 提到它，无测试文件）。改完必须手动跑判定表：
#      真动作 3 例必须仍 EXIT=2；误报 6 例仍 EXIT=2（行为未变）；漏报 3 例仍 EXIT=0。
#
# ④ 与 .claude/settings.json 里那条 inline Layer-3 规则重叠：那条用
#    `git[[:space:]]+push[[:space:]].*--force` 只拦强推，措辞是「发布/上线命令
#    需要具名负责人授权，写进 deploy.md 由负责人签字」——比本守卫的措辞好。
#    但本守卫的 `git push` 更宽、先命中，那条更精确的措辞在强推场景下用不上。
#    （两条 hook 的先后、以及首个阻断后是否继续执行后续 hook：**未实测，标注未证实**。）
#
# ⑤ 2026-10-03 变更：**只加留痕 + 把判据写进提示语，判定逻辑一个字节没动**
#    （同一组 DANGEROUS_PATTERNS、同一套 grep -qE、同样只 exit 2）。
#    留痕落在 .harness/audit/blocked-<日期>.jsonl（该目录在 .gitignore:126，
#    不进版本库）。**只记 命中模式 + 命令 sha256 + 长度 + 命中处上下文 60 字符，
#    不记命令全文**——避免把命令里的凭证原样落盘。
# ============================================================================

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

DANGEROUS_PATTERNS=(
  "git push"
  "git reset --hard"
  "git clean -fd"
  "git clean -f"
  "git branch -D"
  "git checkout \."
  "git restore \."
  "push --force"
  "reset --hard"
)

# ---- 判定：与本文件 2026-10-03 之前完全一致，未改 ----
BLOCKED_BY=""
for pattern in "${DANGEROUS_PATTERNS[@]}"; do
  if echo "$COMMAND" | grep -qE "$pattern"; then
    BLOCKED_BY="$pattern"
    break
  fi
done

[ -z "$BLOCKED_BY" ] && exit 0

# ---- 留痕 ----
# 硬约束：下面任何一步失败都不得改变阻断结论。故整体吞掉错误（|| true），
# 退出码只由文件末尾那条 exit 2 决定。写不出日志也照拦。
{
  # 注意：必须分两步判。若写成 "$(git rev-parse ...)/.harness/audit"，
  # git 失败时结果退化成 "/.harness/audit"（非空！），非空判断形同虚设。
  REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
  LOGDIR="$REPO_ROOT/.harness/audit"
  if [ -n "$REPO_ROOT" ] && [ -d "$LOGDIR" ]; then
    FLAT=$(printf '%s' "$COMMAND" | tr '\n\r\t' '   ')
    HASH=$(printf '%s' "$COMMAND" | shasum -a 256 2>/dev/null | cut -d' ' -f1)
    CTX=$(printf '%s' "$FLAT" | grep -oE ".{0,60}${BLOCKED_BY}.{0,60}" 2>/dev/null | head -1)
    SESSION=$(printf '%s' "$INPUT" | jq -r '.session_id // empty' 2>/dev/null)
    printf '{"ts":"%s","session":"%s","pattern":"%s","cmd_sha256":"%s","cmd_len":%s,"match_context":"%s"}\n' \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      "$(printf '%s' "$SESSION" | sed 's/\\/\\\\/g; s/"/\\"/g')" \
      "$(printf '%s' "$BLOCKED_BY" | sed 's/\\/\\\\/g; s/"/\\"/g')" \
      "$HASH" "${#COMMAND}" \
      "$(printf '%s' "$CTX" | sed 's/\\/\\\\/g; s/"/\\"/g')" \
      >> "$LOGDIR/blocked-$(date -u +%Y-%m-%d).jsonl"
  fi
} 2>/dev/null || true

# ---- 阻断 ----
# 第一行保持原文（历史措辞，未改）；其后补上判据说明与分类动作。
{
  echo "BLOCKED: '$COMMAND' matches dangerous pattern '$BLOCKED_BY'. The user has prevented you from doing this."
  echo "  ⚠ 本条判定的是「命令文本里出现了这个子串」，不是「这条命令会执行该动作」。两者不相关——"
  echo "    本守卫对 'echo \"... git push ...\"' 与真正的推送给出完全相同的判定和措辞。"
  echo "    所以先分类，再决定，不要直接把「被拦」当结论："
  echo "      · 命令确实要执行该动作 → 交给人执行。不要自己改写措辞去让匹配器放行，那是绕过守卫。"
  echo "      · 命令只是「提到」该字面量（误报）→ 换一种不含该字面量的等价写法，实际动作不变，不算绕过。"
  echo "  本守卫无 SKIP 出口。本次已留痕（命中模式 + 命令哈希 + 长度 + 命中处上下文，不含命令全文）："
  echo "      .harness/audit/blocked-$(date -u +%Y-%m-%d).jsonl"
} >&2

exit 2
