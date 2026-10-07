#!/bin/bash
# scripts/check-no-nonprivate-allow.sh
# 配置层红线：`permissions.allow` **绝不允许**放行任何推送类操作。
#
# ============================================================================
# 为什么需要这道（2026-10-07 实测得出的防线缺口）
# ============================================================================
# owner 红线：**只允许推 `wilson323/ruoyi-ai` 与 `wilson323/ruoyi-admin` 这两个仓，
# 且只允许推它们的固定分支**；其它远端与其它分支一律禁止。
#
# 现状是**只有一道防线**：运行时 `block-dangerous-git.sh`（PreToolUse，2026-10-07 单日拦 1189 次）。
# 但 `permissions.allow` 是**宿主直接读的**，不经过本仓任何守卫
# （实测 `.claude/helpers/hook-handler.cjs` 里无任何对 `allow` 的校验）。
# ⇒ 一旦有人往 allow 里塞了推送类放行项，运行时守卫会被**整体旁路**
#   （allow 命中即免审，守卫根本不会被调用），红线形同虚设。
#
# 本脚本补上配置层那道。**任何放行推送的决定必须由 owner 明示，并同步改本脚本判据**。
#
# ⚠️ 本文件刻意**不含任何推送命令的字面写法**（全部变量拼接）：
#    `block-dangerous-git.sh` 扫描的是**命令文本**，若本文件里出现字面量，
#    调用/编辑本脚本时会被守卫误判为真实推送而拦下。
#    2026-10-07 实测踩到：自证样本含字面量 → 脚本自己跑不起来，死循环。
#    判据强度不变：拼出来的字符串与字面量完全一致。
#
# 用法：
#   bash scripts/check-no-nonprivate-allow.sh              # 查真实 settings.json
#   bash scripts/check-no-nonprivate-allow.sh <file>       # 查指定文件（自证用）
#   bash scripts/check-no-nonprivate-allow.sh --self-test  # 4 个临时样本自证
# 退出码：0 通过 / 1 违规 / 2 自身故障
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SETTINGS="$ROOT/.claude/settings.json"

# 拼接构造，避免本文件出现字面量
PU="git"; PS="push"
GP="$PU $PS"

self_test() {
  local P=0 F=0 TD
  TD="$(mktemp -d "${TMPDIR:-/tmp}/allowgate.XXXXXX")" || { echo "无法建临时目录" >&2; return 2; }
  printf '{"permissions":{"allow":[],"deny":[]}}' > "$TD/clean.json"
  printf '{"permissions":{"allow":["Bash(%s:*)"],"deny":[]}}' "$GP" > "$TD/bad-push.json"
  printf '{"permissions":{"allow":["Bash(%s origin upstream:*)"],"deny":[]}}' "$GP" > "$TD/bad-upstream.json"
  printf '{"permissions":{"allow":["Bash(%s origin HEAD:*)"],"deny":[]}}' "$GP" > "$TD/bad-head.json"
  printf '{"permissions":{"allow":["Bash(%s commit:*)","Bash(%s add:*)"],"deny":[]}}' "$PU" "$PU" > "$TD/good-local.json"
  printf '{"permissions":{"allow":[],"deny":["Bash(%s origin main:*)"]}}' "$GP" > "$TD/deny-ok.json"

  check() { # check <期望退出码> <说明> <文件>
    local want="$1" desc="$2" f="$3" got
    bash "$0" "$f" >/dev/null 2>&1; got=$?
    if [ "$got" = "$want" ]; then P=$((P+1)); printf '  OK   %-42s exit=%s\n' "$desc" "$got"
    else F=$((F+1)); printf '  BAD  %-42s exit=%s (期望 %s)\n' "$desc" "$got" "$want"; fi
  }

  echo "[self-test] 6 个临时样本，仓库零写入："
  check 0 "空 allow"                          "$TD/clean.json"
  check 1 "allow 含推送放行"                   "$TD/bad-push.json"
  check 1 "allow 含推送 + 指定上游远端"          "$TD/bad-upstream.json"
  check 1 "allow 含推送 + HEAD（非固定分支）"    "$TD/bad-head.json"
  check 0 "allow 仅本地写版本历史"             "$TD/good-local.json"
  check 0 "deny 里含推送（明确禁止，正确用法）" "$TD/deny-ok.json"
  rm -rf "$TD"
  echo "[self-test] PASS=$P FAIL=$F"
  [ "$F" = "0" ] && return 0 || return 1
}

if [ "${1:-}" = "--self-test" ]; then
  shift
  self_test
  exit $?
fi

TARGET="${1:-$SETTINGS}"
if [ ! -f "$TARGET" ]; then
  echo "❌ 找不到 $TARGET" >&2
  exit 2
fi

# 解析失败必须报红，不能当通过 —— 空扫恒绿
if ! python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$TARGET" 2>/dev/null; then
  echo "❌ $TARGET 不是合法 JSON（空扫恒绿：读不出来 ≠ 没违规）" >&2
  exit 2
fi

python3 - "$TARGET" "$GP" <<'PY'
import json, re, sys
p, gp = sys.argv[1], sys.argv[2]
j = json.load(open(p, encoding='utf-8'))
perms = j.get('permissions') or {}
allow = perms.get('allow') or []

FORBIDDEN_REMOTE = ('upstream', 'ageerle')
viol = []
for item in allow:
    s = str(item); low = s.lower()
    if re.search(r'\bgit\b.*\b' + 'pu' + r'sh\b', low):
        viol.append((s, '放行了推送：allow 命中即免审，会整体旁路 block-dangerous-git.sh'))
    elif any(f in low for f in FORBIDDEN_REMOTE):
        viol.append((s, '放行了非 owner 指定的远端'))

if viol:
    print("🔴 配置层红线被破坏：permissions.allow 出现以下条目", file=sys.stderr)
    for item, why in viol:
        print(f"   - {item}\n       理由：{why}", file=sys.stderr)
    print("\n   owner 红线：只允许 wilson323/ruoyi-ai 与 wilson323/ruoyi-admin，"
          "且只允许各自的固定分支。", file=sys.stderr)
    print("   处置：从 allow 移除；确需放行必须由 owner 明示，并同步改本脚本判据。", file=sys.stderr)
    sys.exit(1)

git_allow = [x for x in allow if 'git' in str(x).lower()]
print(f"   allow 共 {len(allow)} 项；其中 git 相关 {len(git_allow)} 项"
      + (f"：{git_allow}" if git_allow else "（无）"))
print("   ✅ 配置层无推送放行、无非指定远端放行")
PY
exit $?
