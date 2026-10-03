#!/usr/bin/env bash
# grep 的 1 只表示无匹配；2+ 是输入/读取错误，不能当作 0 个匹配。
gate_grep() {
  local status=0
  command grep "$@" || status=$?
  if [ "$status" -gt 1 ]; then
    echo "[gate] grep 读取失败 (exit=$status)" >&2
    return "$status"
  fi
  return 0
}

# 枚举完整输入，并检查读取；空目录、仅空文件及遍历失败均为门禁错误。
# === 污染目录排除（公共可复用函数，全仓门禁共用）====================
#
# 为什么必须显式排除：仓库里存着大量「历史工作树 / 阶段快照 / 恢复备份」的
# 完整 Java 拷贝，它们与现网源码同包名同类名，不排除会让任何全树统计虚高一个
# 量级。实测证据（2026-10-03，均为真实统计）：
#
#   路径                                          append 命中   ipd/controller/*.java
#   ruoyi-modules + ruoyi-common（现网）              177              74
#   .harness/（历史 worktree + 备份）                 782            707
#   .codex/（ipd-integration 阶段快照 + ruflo 草稿）   ——            442
#
# 也就是说：只排除 .harness/ 仍然不够，.codex/ 同样是污染源。
#
# 用法：
#   gate_source_files <root> [find 的附加 -name 条件...]
#   输出：每行一个可读的非空文件路径；失败 exit 2。
#
# 语义：遍历失败 / 无输入 / 全部为空 一律 exit 2，**绝不降级成 0 条**
# （假 0 会让门禁报出「扫描通过」的相反结论）。
gate_source_files() {
  local root="$1" files file nonempty=0
  shift
  [ -n "$root" ] || { echo "[gate] 缺少扫描根目录参数" >&2; return 2; }
  [ -d "$root" ] || { echo "[gate] 输入目录不存在: $root" >&2; return 2; }
  # 污染目录：历史 worktree / 备份 / 阶段快照 / 编译产物 / 依赖包
  files=$(find "$root" \
    ! -path '*/target/*' \
    ! -path '*/node_modules/*' \
    ! -path '*/.harness/*' \
    ! -path '*/.codex/*' \
    ! -path '*/.worktrees/*' \
    ! -path '*/worktrees/*' \
    ! -path '*/.git/*' \
    "$@") || { echo "[gate] 目录遍历失败: $root" >&2; return 2; }
  [ -n "$files" ] || { echo "[gate] 没有扫描输入: $root" >&2; return 2; }
  while IFS= read -r file; do
    [ -f "$file" ] || continue
    cat "$file" >/dev/null 2>&1 || { echo "[gate] 文件不可读: $file" >&2; return 2; }
    [ -s "$file" ] && nonempty=1
  done <<< "$files"
  [ "$nonempty" -eq 1 ] || { echo "[gate] 扫描输入全为空: $root" >&2; return 2; }
  printf '%s\n' "$files"
}

gate_require_tree() {
  local root="$1" files file nonempty=0
  shift
  [ -d "$root" ] || { echo "[gate] 输入目录不存在: $root" >&2; return 2; }
  files=$(find "$root" -type f ! -path '*/target/*' ! -path '*/node_modules/*' "$@") || return 2
  [ -n "$files" ] || { echo "[gate] 没有扫描输入: $root" >&2; return 2; }
  while IFS= read -r file; do
    cat "$file" >/dev/null || return 2
    if [ -s "$file" ]; then nonempty=1; fi
  done <<< "$files"
  [ "$nonempty" -eq 1 ] || { echo "[gate] 扫描输入全为空: $root" >&2; return 2; }
}

# 产品扫描只枚举源码；标准测试/规格文件及测试目录不是运行代码。
gate_frontend_files() {
  local root="$1" files file nonempty=0
  [ -d "$root" ] || { echo "[gate] 输入目录不存在: $root" >&2; return 2; }
  files=$(find "$root" -type f ! -path '*/target/*' ! -path '*/node_modules/*' \
    ! -path '*/__tests__/*' ! -path '*/__mocks__/*' ! -path '*/test-helpers/*' ! -path '*/tests/*' ! -path '*/test/*' ! -path '*/spec/*' \
    ! -name '*.test.*' ! -name '*.spec.*' \
    \( -name '*.vue' -o -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.jsx' \)) || return 2
  [ -n "$files" ] || { echo "[gate] 没有生产源码输入: $root" >&2; return 2; }
  while IFS= read -r file; do
    cat "$file" >/dev/null || return 2
    if [ -s "$file" ]; then nonempty=1; fi
  done <<< "$files"
  [ "$nonempty" -eq 1 ] || { echo "[gate] 生产源码输入全为空: $root" >&2; return 2; }
  printf '%s\n' "$files"
}

gate_frontend_matching_files() {
  local pattern="$1" files="$2" file status
  while IFS= read -r file; do
    status=0
    command grep -qE "$pattern" "$file" || status=$?
    if [ "$status" -gt 1 ]; then return "$status"; fi
    if [ "$status" -eq 0 ]; then printf '%s\n' "$file"; fi
  done <<< "$files"
}

# 使用当前前端已安装的 TypeScript AST；注释和字符串不产生调用节点。
# 仅报告调用/清理数量差异，不推断卸载时序、句柄配对或证明实际泄漏。
gate_frontend_call_counts() {
  local files="$1" helper_root typescript_root
  helper_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  typescript_root="${GATE_TYPESCRIPT_ROOT:-$helper_root/../ruoyi-ipd-web/node_modules/typescript}"
  command -v node >/dev/null || { echo "[gate] node不可用" >&2; return 2; }
  GATE_SOURCE_FILES="$files" node - "$typescript_root" <<'NODECODE'
const fs = require('node:fs');
const ts = require(process.argv[2]);
const calls = new Set(['setInterval', 'setTimeout', 'clearInterval', 'clearTimeout',
  'addEventListener', 'removeEventListener', 'close']);
function nameOf(expression) {
  if (ts.isIdentifier(expression)) return expression.text;
  if (ts.isPropertyAccessExpression(expression)) return expression.name.text;
  return '';
}
for (const file of process.env.GATE_SOURCE_FILES.split('\n')) {
  const text = fs.readFileSync(file, 'utf8');
  const counts = Object.fromEntries([...calls, 'sockets', 'window'].map(name => [name, 0]));
  const pieces = file.endsWith('.vue')
    ? [...text.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)]
      .map(match => [match[2], /lang\s*=\s*['"]tsx['"]/.test(match[1]) ? ts.ScriptKind.TSX : ts.ScriptKind.TS])
    : [[text, /\.(jsx|tsx)$/.test(file) ? ts.ScriptKind.TSX : ts.ScriptKind.TS]];
  for (const [code, kind] of pieces) {
    const source = ts.createSourceFile(file, code, ts.ScriptTarget.Latest, true, kind);
    if (source.parseDiagnostics.length) {
      const error = source.parseDiagnostics[0];
      throw new Error(`${file}: ${ts.flattenDiagnosticMessageText(error.messageText, ' ')}`);
    }
    function visit(node) {
      if (ts.isCallExpression(node)) {
        const name = nameOf(node.expression);
        if (calls.has(name)) counts[name]++;
      }
      if (ts.isNewExpression(node) && ['WebSocket', 'EventSource'].includes(nameOf(node.expression))) counts.sockets++;
      if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken
          && ts.isPropertyAccessExpression(node.left) && ts.isIdentifier(node.left.expression)
          && node.left.expression.text === 'window') counts.window++;
      ts.forEachChild(node, visit);
    }
    visit(source);
  }
  console.log([file, counts.setInterval + counts.setTimeout, counts.clearInterval + counts.clearTimeout,
    counts.addEventListener, counts.removeEventListener, counts.sockets, counts.close, counts.window].join('\t'));
}
NODECODE
}

# 统计文件行数。计数失败必须让调用方看见，不允许退化成 0 或空串：
#   历史上 `wc -l < f 2>/dev/null | tr -d ' ' || echo 0` 的 `|| echo 0` 永远不生效
#   （2>/dev/null 吞掉 wc 报错，管道末端 tr 收到空输入并成功退出），
#   结果变量是空字符串，下游做数值比较时报 "integer expression expected" 并静默算错。
#
# 语义决策：**读取失败 = 门禁错误（exit 2），不降级为 0**。
# 理由：本仓门禁的输入文件均由脚本自己生成，生成失败或路径写错属于脚本 bug；
# 若降级成 0，报告会输出一个看起来正常的 0 并让 CI 给出方向相反的结论。
# 「确实为 0」必须由调用方显式创建空文件来表达，而不是靠文件缺失。
#
# 用法：count=$(gate_count_lines "$f") || exit 2
gate_count_lines() {
  local file="$1" count
  if [ ! -f "$file" ] || [ ! -r "$file" ]; then
    echo "[gate] 计数输入不存在或不可读: $file" >&2
    return 2
  fi
  count=$(command wc -l < "$file") || {
    echo "[gate] 计数读取失败: $file" >&2
    return 2
  }
  count="${count//[[:space:]]/}"
  case "$count" in
    ''|*[!0-9]*)
      echo "[gate] 计数结果不是整数 (got='$count'): $file" >&2
      return 2
      ;;
  esac
  printf '%s' "$count"
}
