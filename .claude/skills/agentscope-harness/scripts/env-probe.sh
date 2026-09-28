#!/usr/bin/env bash
# env-probe.sh — agentscope-harness 跑前环境探测
# 归因纪律（.claude/skills/_templates/IPD-SKILL-DISCO-TEMPLATE.md「失败归因三类」）：
#   环境跑不通 → WARNING（不 FAIL），只有「知识自身错」与「检查发现真违规」才 FAIL。
# macOS bash 3.2 兼容：不用关联数组 / mapfile / gawk match 第三参。
set -uo pipefail

EXIT_CODE=0
warn() { printf '  [WARN] %s\n' "$1"; }
ok()   { printf '  [ OK ] %s\n' "$1"; }
info() { printf '  [INFO] %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1"; EXIT_CODE=1; }

# 定位仓根：向上找同时含 pom.xml 与 .git 的目录
locate_root() {
  local dir
  dir="$(cd "$(dirname "$0")" && pwd)"
  while [ "$dir" != "/" ] && [ -n "$dir" ]; do
    if [ -f "$dir/pom.xml" ] && [ -e "$dir/.git" ]; then printf '%s' "$dir"; return 0; fi
    dir="$(dirname "$dir")"
  done
  return 1
}

echo "== [1/5] 仓库根定位 =="
if ROOT="$(locate_root)"; then
  ok "repo root = $ROOT"
else
  fail "找不到仓根（需同时存在 pom.xml 与 .git）；本 skill 的 harness-contract-check 无对象"
  ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
  info "降级使用 skill 上三级目录：$ROOT"
fi

echo "== [2/5] 基础工具 =="
for t in bash grep sed awk find git; do
  if command -v "$t" >/dev/null 2>&1; then ok "$t = $(command -v "$t")"; else fail "$t 缺失"; fi
done
info "bash ${BASH_VERSION}（脚本按 3.2 语法写，勿引入关联数组 / mapfile）"
# ↑ 必须写 ${BASH_VERSION}：裸 $BASH_VERSION 后紧跟全角括号时，非 UTF-8 locale 下
#   bash 会把多字节首字节当成变量名的一部分 → set -u 报 unbound variable（实测踩过）。

echo "== [3/5] Java 构建链（缺失只 WARN，不阻断契约检查） =="
MVN_BIN="$(command -v mvn 2>/dev/null || true)"
if [ -z "$MVN_BIN" ] && [ -x "$HOME/tools/maven/bin/mvn" ]; then MVN_BIN="$HOME/tools/maven/bin/mvn"; fi
if [ -n "$MVN_BIN" ]; then
  ok "mvn = $MVN_BIN"
else
  warn "mvn 不在 PATH（项目惯例：export PATH=\"\$HOME/tools/maven/bin:\$PATH\"）→ 复跑 PoC IT 会失败，但不影响静态契约检查"
fi
if [ -n "${JAVA_HOME:-}" ] && [ -d "${JAVA_HOME:-}" ]; then
  ok "JAVA_HOME = $JAVA_HOME"
elif [ -d "$HOME/tools/jdk-17/Contents/Home" ]; then
  warn "JAVA_HOME 未设，但存在 $HOME/tools/jdk-17/Contents/Home（项目惯例 JDK17）"
else
  warn "JAVA_HOME 未设且未找到项目 JDK17"
fi

echo "== [4/5] AgentScope 接入现状 =="
AS_POMS="$(grep -rl "io\.agentscope" "$ROOT" --include=pom.xml 2>/dev/null | grep -v "/node_modules/" | head -20)"
if [ -n "$AS_POMS" ]; then
  ok "已声明 io.agentscope 依赖的 pom："
  printf '%s\n' "$AS_POMS" | sed 's|^|         |'
  AS_VER="$(grep -rhoE "<version>2\.[0-9]+\.[0-9]+</version>" $AS_POMS 2>/dev/null | head -1)"
  [ -n "$AS_VER" ] && info "探测到版本片段：${AS_VER}（以 pom 现态为准，勿凭记忆写版本号）"
else
  info "本工作树未声明 io.agentscope 依赖 → harness-contract-check 预计报 SKIP(no HarnessAgent usage)"
  info "已知接入分支：poc/agentscope-kernel（worktree: $ROOT/.worktrees/poc-agentscope-kernel）"
fi

echo "== [5/5] 凭据与真库探针（只查存在性，绝不打印内容） =="
CNF="$ROOT/.codex/ipd-dev/config/mysql-app.cnf"
if [ -f "$CNF" ]; then
  ok "存在 ${CNF}（gitignored；凭证不上命令行、不打印）"
else
  warn "缺 $CNF → 需要真库实证的 IT（AgentScopeKernelPocIT 等）会 BLOCKED_ENVIRONMENT，不得伪造输出"
fi
if git -C "$ROOT" check-ignore -q .codex 2>/dev/null; then
  ok ".codex 已被 gitignore（凭据不入库）"
else
  warn ".codex 未被 gitignore —— 提交前必须人工确认无凭证入库"
fi

echo
if [ "$EXIT_CODE" -eq 0 ]; then
  echo "env-probe: PASS（WARN 项见上，不阻断）"
else
  echo "env-probe: FAIL"
fi
exit $EXIT_CODE
