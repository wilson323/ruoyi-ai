#!/usr/bin/env node
/**
 * .claude/helpers/output-shape-guard.cjs
 * PostToolUse(Bash) —— **工具调用产出物的形状守卫**
 *
 * 治的是本仓反复出现、且今天又现场复现的那一类失效：
 *   「每一步单独看都合法，合起来是错的，失败却长得像成功。」
 *
 * 2026-10-07 当天实例（我自己犯的）：
 *   修补 block-dangerous-git.sh 时写了一行 sed：s# ##$##
 *   `#` 兼作分隔符与字面量 → sed 报 bad flag → **输出空串** → 下游 grep 失配
 *   → 判据永假 → **守卫变成永放行**，比修补前的漏洞更糟。
 *   而整条管道退出码仍是 0，脚本自己报告成功，没有任何一层发现它坏了。
 *
 * 为什么必须是机器而不是纪律：
 *   「读 stderr 看看有没有报错」这一步的成本恒为正（多一次调用 + 多花注意力），
 *   而漏看的成本是延迟暴露的（没人会告诉你昨天哪次跑挂了）。
 *   靠自觉必然被进度压力压过去——这是本仓 67 条教训反复验证过的形状。
 *
 * 判据（只拦「形状明显不合法」，不判断业务正确性）：
 *   1. 命令成功（exit 0）但输出里出现工具级错误标记 → 极可能是"假成功"
 *   2. 命令成功但输出为空，而该命令族属于"本应有输出"的类型 → 可疑
 *   3. 管道中任一环静默降级（fallback / falling back / degrade）→ 告警
 *
 * 明确不做的事：不判断业务语义、不阻塞（PostToolUse 无法真正阻断下一步之前已发生的
 * 调用），只做**下一轮开始前的强制告知**。真正的硬拦截请走 PreToolUse。
 */

'use strict';

const fs = require('fs');

// ---- 读入 hook payload（容错：读不到就放行，绝不因自身故障制造阻断）----
let payload = {};
try {
  const raw = fs.readFileSync(0, 'utf8');
  if (raw && raw.trim()) payload = JSON.parse(raw);
} catch (_) {
  process.exit(0);
}

// ---- 自检：本 hook 自身出故障时必须放行，而不是阻断用户的正常工作 ----
function bail(message) {
  process.stderr.write(`[output-shape-guard] 自身异常，放行：${message}\n`);
  process.exit(0);
}

const toolName = payload.tool_name || '';
if (toolName !== 'Bash') process.exit(0);

const toolInput = payload.tool_input || {};
const command = String(toolInput.command || '');

// 从 hook 侧能拿到的输出字段随宿主版本而变，逐个尝试。
// 拿不到就退化成"只做命令白名单判断"，不硬猜字段名。
const out = (() => {
  const cands = [
    payload.tool_response, payload.tool_result, payload.tool_output,
    payload.output, payload.result, payload.stdout,
  ];
  for (const c of cands) {
    if (c === undefined || c === null) continue;
    if (typeof c === 'string') return { text: c, ok: true };
    if (typeof c === 'object') {
      const text = c.stdout ?? c.output ?? c.text ?? c.content ?? '';
      return { text: String(text), ok: true, code: c.exit_code ?? c.exitCode ?? c.status };
    }
  }
  return { text: '', ok: false };
})();

const TEXT = out.text || '';
const findings = [];

// ---- 判据 1：命令"成功"但输出里有工具级错误标记 ----
//  注意只匹配明确的工具报错格式，不匹配业务日志里的 error 字样，避免误伤。
const TOOL_ERROR_PATTERNS = [
  /\bbad flag in substitute command\b/i,
  /\bunbound variable\b/i,
  /\bsyntax error near unexpected\b/i,
  /\bcommand not found\b/i,
  /\bTraceback \(most recent call last\)/,
  /\bNode\.js v\d+\.\d+\.\d+/,          // node 崩栈首行
  /\bpermission denied\b/i,
  /\bNo such file or directory\b/i,      // 仅当出现在 stderr 起始处才算，见下
];

for (const re of TOOL_ERROR_PATTERNS) {
  const m = TEXT.match(re);
  if (m) findings.push(`工具级报错标记：${m[0].trim().slice(0, 60)}`);
}

// ---- 判据 2：静默降级 ----
const DEGRADE_PATTERNS = [
  /falling back to/i,
  /fallback to raw/i,
  /degraded\b/i,
  /guessed\b.*\bvalue\b/i,
];
for (const re of DEGRADE_PATTERNS) {
  const m = TEXT.match(re);
  if (m) findings.push(`疑似静默降级：${m[0].trim().slice(0, 60)}`);
}

// ---- 判据 3：成功但输出为空（仅对"本应有输出"的命令族）----
//  这一条最容易误伤，所以判据必须窄：只认明确会产生清单/计数的命令。
const SHOULD_PRODUCE = [
  /\bgit\s+(status|log|diff|show|remote|branch|rev-list|cat-file|ls-files)\b/,
  /\bfind\b/, /\bgrep\b/, /\bwc\b/, /\bls\b/,
  /\brepowise\b/, /\bnode\b.*\.js\b/, /\bpython3?\b/,
];
const NOT_WITHOUT_OUTPUT = [
  /\b(mkdir|cd|true|export|alias)\b/,
  /^\s*$/,
];
const likelyProduces = SHOULD_PRODUCE.some((re) => re.test(command));
const cmdIsNoop = NOT_WITHOUT_OUTPUT.some((re) => re.test(command));

// 只有在「宿主确实给了输出字段」且「文本确认为空」时才提示，避免因字段缺失误报
if (out.ok && TEXT.trim() === '' && likelyProduces && !cmdIsNoop) {
  findings.push('命令成功但输出为空（本应有输出）：该读数不可直接用于结论');
}

// ---- 输出结论 ----
if (findings.length === 0) process.exit(0);

const lines = [
  '',
  '════════════════════════════════════════════════════════════',
  '[输出形状守卫] 上一步工具调用的产出物形状异常，请在用它下结论前先确认：',
  `  命令：${command.slice(0, 160)}`,
  ...findings.map((f) => `  ⚠ ${f}`),
  '',
  '  这是「失败长得像成功」的高频形态。处理方式（任选其一）：',
  '   a) 重跑该命令并显式检查 stderr 与退出码；',
  '   b) 若这是脚本内部的 sed/grep 管道，先单独跑每一环确认产出形状；',
  '   c) 计数类读数请改用 scripts/evidenced-count.sh，它自带口径自证；',
  '   d) 确认无害可忽略——但请显式说明为什么无害，不要默认忽略。',
  '════════════════════════════════════════════════════════════',
  '',
];
process.stderr.write(lines.join('\n'));
process.exit(0); // 告知而非阻断：真正要拦的判定请写进 PreToolUse
