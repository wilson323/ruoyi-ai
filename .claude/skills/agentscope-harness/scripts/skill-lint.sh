#!/usr/bin/env bash
# skill-lint.sh — agentscope-harness 自身契约门禁（知识自身错 → FAIL）
# 规范来源：
#   .claude/skills/_templates/IPD-SKILL-DISCO-TEMPLATE.md（SKILL.md ≤100 行 + references 6 槽 + 自证能红）
#   Qoder create-skill（frontmatter name/description 约束、链接一级深度、第三人称 description）
# macOS bash 3.2 / BSD sed 兼容。
set -uo pipefail

SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SKILL_NAME="$(basename "$SKILL_DIR")"
REPO_ROOT="$(cd "$SKILL_DIR/../../.." && pwd)"
EXIT_CODE=0

ok()   { printf '  [ OK ] %s\n' "$1"; }
warn() { printf '  [WARN] %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1"; EXIT_CODE=1; }

SKILL_MD="$SKILL_DIR/SKILL.md"

echo "== [L1] SKILL.md 存在且 ≤100 行（DisCo 入口纪律） =="
if [ ! -f "$SKILL_MD" ]; then
  fail "缺 $SKILL_MD"
else
  LINES=$(wc -l < "$SKILL_MD" | tr -d ' ')
  if [ "$LINES" -le 100 ]; then ok "SKILL.md = $LINES 行（≤100）"; else fail "SKILL.md = $LINES 行，超 100 行入口上限，细节下沉 references/"; fi
fi

echo "== [L2] frontmatter：name / description =="
if [ -f "$SKILL_MD" ]; then
  FM_NAME=$(sed -nE '2,20{ s/^name:[[:space:]]*([A-Za-z0-9._-]+)[[:space:]]*$/\1/p; }' "$SKILL_MD" | head -1)
  FM_DESC=$(sed -nE '2,20{ s/^description:[[:space:]]*(.*)$/\1/p; }' "$SKILL_MD" | head -1)

  if [ -z "$FM_NAME" ]; then
    fail "frontmatter 缺 name:"
  else
    if [ "$FM_NAME" = "$SKILL_NAME" ]; then ok "name=$FM_NAME 与目录名一致"; else fail "name=$FM_NAME 与目录名 $SKILL_NAME 不一致"; fi
    if printf '%s' "$FM_NAME" | grep -qE '^[a-z0-9]+(-[a-z0-9]+)*$'; then ok "name 全小写+连字符合法"; else fail "name 含非法字符（只允许小写字母/数字/连字符）"; fi
    NL=${#FM_NAME}
    if [ "$NL" -le 64 ]; then ok "name 长度 $NL ≤64"; else fail "name 长度 $NL >64"; fi
  fi

  if [ -z "$FM_DESC" ]; then
    fail "frontmatter 缺 description:（模型靠它决定何时应用本 skill）"
  else
    DL=${#FM_DESC}
    if [ "$DL" -le 1024 ]; then ok "description 长度 $DL ≤1024"; else fail "description 长度 $DL >1024"; fi
    # 第三人称 + WHAT/WHEN 双要素
    if printf '%s' "$FM_DESC" | grep -qE '^(I |You can use|我可以|你可以)'; then
      fail "description 用了第一/第二人称，应第三人称陈述能力"
    else
      ok "description 非第一/第二人称开头"
    fi
    if printf '%s' "$FM_DESC" | grep -qE '当用户|当需要|Use when|使用场景|时使用'; then
      ok "description 含 WHEN 触发条件"
    else
      fail "description 缺 WHEN 触发条件（何时应用），会导致 skill 不被自动发现"
    fi
  fi
fi

echo "== [L3] SKILL.md 内相对链接可达 + 一级深度 =="
if [ -f "$SKILL_MD" ]; then
  LINKS=$(sed -nE 's/.*\]\(([^)]+)\).*/\1/gp' "$SKILL_MD" | grep -vE '^(https?:|#|mailto:)' | sort -u)
  if [ -z "$LINKS" ]; then
    warn "SKILL.md 无相对链接（渐进式披露未生效？）"
  else
    # 不用 while 管道：子 shell 改不了父进程的 EXIT_CODE
    BROKEN=0
    for l in $LINKS; do
      if [ ! -e "$SKILL_DIR/$l" ]; then
        fail "链接指向不存在的文件: $l"; BROKEN=1; continue
      fi
      D=$(printf '%s' "$l" | awk -F/ '{print NF}')
      if [ "$D" -gt 2 ]; then
        fail "链接层级过深($D 段 >2)，可能被部分读取: $l"; BROKEN=1
      fi
    done
    [ "$BROKEN" -eq 0 ] && ok "全部相对链接可达且一级深度（$(printf '%s\n' "$LINKS" | grep -c .) 条）"
  fi
fi

echo "== [L4] references/ 每篇含 6 槽（是什么/为什么/怎么识别/怎么修或怎么做/验证/来源） =="
REFS=$(find "$SKILL_DIR/references" -name '*.md' -type f 2>/dev/null | sort)
if [ -z "$REFS" ]; then
  fail "references/ 下无 .md（DisCo 形态要求细节下沉到 references/）"
else
  for f in $REFS; do
    MISS=""
    grep -qE '^#{2,3} .*是什么' "$f"                 || MISS="$MISS 是什么"
    grep -qE '^#{2,3} .*为什么' "$f"                 || MISS="$MISS 为什么"
    grep -qE '^#{2,3} .*怎么识别' "$f"               || MISS="$MISS 怎么识别"
    grep -qE '^#{2,3} .*(怎么修|怎么做)' "$f"        || MISS="$MISS 怎么修/怎么做"
    grep -qE '^#{2,3} .*验证' "$f"                   || MISS="$MISS 验证"
    grep -qE '^#{2,3} .*来源' "$f"                   || MISS="$MISS 来源"
    if [ -z "$MISS" ]; then
      ok "$(basename "$f") 6 槽齐全"
    else
      fail "$(basename "$f") 缺槽:$MISS"
    fi
  done
fi

echo "== [L5] Qoder 运行时镜像同步（.agents/skills/ 才是本 IDE 自动发现目录） =="
MIRROR="$REPO_ROOT/.agents/skills/$SKILL_NAME"
if [ ! -d "$MIRROR" ]; then
  warn "镜像缺失: $MIRROR"
  warn "  .agents/skills/ 已被 .gitignore 忽略（fresh clone 必然缺），但本机缺 → 本 IDE 不会自动应用本 skill"
  warn "  修复: cp -R .claude/skills/$SKILL_NAME .agents/skills/"
else
  DIFFOUT=$(diff -r "$SKILL_DIR" "$MIRROR" 2>&1)
  if [ -z "$DIFFOUT" ]; then
    ok "镜像与事实源字节一致: $MIRROR"
  else
    fail "镜像与事实源不一致（会出现「文档说 A、运行时用 B」的双轨）："
    printf '%s\n' "$DIFFOUT" | head -20 | sed 's|^|         |'
    printf '  [HINT] 修复: rm -rf %s && cp -R %s %s\n' "$MIRROR" "$SKILL_DIR" "$MIRROR"
  fi
fi

echo "== [L6] scripts/ 语法与可执行性 =="
SCRIPTS=$(find "$SKILL_DIR/scripts" -name '*.sh' -type f 2>/dev/null | sort)
if [ -z "$SCRIPTS" ]; then
  fail "scripts/ 下无 .sh（DisCo 形态要求 scripts/verify.sh 动态断言）"
else
  for s in $SCRIPTS; do
    if bash -n "$s" 2>/dev/null; then ok "bash -n 通过: $(basename "$s")"; else fail "bash -n 失败: $(basename "$s")"; bash -n "$s"; fi
  done
  [ -f "$SKILL_DIR/scripts/verify.sh" ] && ok "verify.sh 主入口在场" || fail "缺 scripts/verify.sh 主入口"
fi

echo "== [L7] 跨平台路径与禁用写法 =="
if grep -rnE '\]\((references|scripts|examples)\\' "$SKILL_DIR" >/dev/null 2>&1; then
  fail "发现 Windows 风格反斜杠路径引用"
else
  ok "无 Windows 风格路径"
fi
# --exclude 必需：不加就会匹配到本脚本自己的模式字面量（实测自指假阳性，永报 WARN）
if grep -rn --exclude='skill-lint.sh' 'skill_registry.*latest' "$SKILL_DIR" >/dev/null 2>&1; then
  warn "出现 latest 指针式版本引用（生产执行需可追溯到具体版本）"
else
  ok "无 latest 指针式版本引用"
fi

echo "== [L8] 裸变量紧跟多字节字符（bash -n 抓不到的运行期炸） =="
# 实测连踩两次：写 "...：$AS_VER（..." 时，bash 3.2 在非 UTF-8 locale 下会把全角括号
# 的首字节归入变量名 → set -u 报 `AS_VER\xef\xbc: unbound variable`，而 bash -n 完全通过。
# 单轨纪律（owner 明确要求「严格避免双轨」）：这条判据的**权威实现属于仓级门禁**
#   scripts/check-shell-var-multibyte.sh（R224，同一正则、整行注释不误计、自带 --self-test
#   夹具，且已接入 pre-commit 门禁 4）。故本段**委托**它，不在此另养一套正则；
#   只有脱离本仓运行（仓脚本不存在）才退回等价的 LC_ALL=C grep 兜底。
REPO_GATE="$REPO_ROOT/scripts/check-shell-var-multibyte.sh"
if [ -f "$REPO_GATE" ]; then
  bash "$REPO_GATE" "$SKILL_DIR"/scripts/*.sh >"${TMPDIR:-/tmp}/skill-lint-l8.$$" 2>&1
  L8_RC=$?
  if [ "$L8_RC" -eq 0 ]; then
    ok "委托仓级门禁 R224 通过（scripts/check-shell-var-multibyte.sh）"
  elif [ "$L8_RC" -eq 2 ]; then
    warn "仓级门禁 R224 环境错（exit=2，多为缺 python3 或不在 git 仓），非代码违例"
    sed 's|^|         |' "${TMPDIR:-/tmp}/skill-lint-l8.$$"
  else
    fail "仓级门禁 R224 报变量吞字节违例，必须改写为 \${VAR}："
    sed 's|^|         |' "${TMPDIR:-/tmp}/skill-lint-l8.$$" | head -10
  fi
  rm -f "${TMPDIR:-/tmp}/skill-lint-l8.$$"
else
  VARBUG=$(LC_ALL=C grep -rnE --exclude='skill-lint.sh' '\$[A-Za-z_][A-Za-z0-9_]*[^ -~]' "$SKILL_DIR/scripts" 2>/dev/null)
  if [ -n "$VARBUG" ]; then
    fail "发现裸变量紧跟多字节字符，必须改写为 \${VAR}："
    printf '%s\n' "$VARBUG" | head -10 | sed 's|^|         |'
  else
    warn "兜底 grep 通过（未找到仓级门禁 ${REPO_GATE}，脱离本仓运行）"
  fi
fi

echo
if [ "$EXIT_CODE" -eq 0 ]; then echo "skill-lint: PASS"; else echo "skill-lint: FAIL"; fi
exit $EXIT_CODE
