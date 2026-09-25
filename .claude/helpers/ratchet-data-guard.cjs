#!/usr/bin/env node
/**
 * ratchet-data-guard.cjs  (R212 / 卡 7b76b7cd API-GATE-RATCHET · 方案 D)
 * PreToolUse hook: 拦截 agent 会话直接编辑孤儿棘轮门禁的两个数据文件。
 *
 * 触发条件: Write | Edit | MultiEdit 工具
 * 阻断目标（r212-gate-design.md §5-D「治手工改基线这一最现实绕过路径」的 agent 侧）:
 *   1. scripts/baselines/*.json          —— baseline 只允许脚本独占写
 *      (唯一合法入口: node scripts/check-api-contract-fe-be.mjs --update-baseline)
 *   2. docs/ipd-系统说明/api-internal-whitelist.json —— 白名单变更须挂看板卡人审(卡号+reason+expire)
 *
 * 说明: 本 hook 只拦 agent 编辑工具;人手 vim / git commit --no-verify 由
 *       baseline 的 sha256 自洽 + git show HEAD 硬闸兜底(门禁脚本层)。
 * 路径判定: 以 CLAUDE_PROJECT_DIR(缺省 cwd)为仓库根,仅拦本仓内相对路径精确命中;
 *           仓外同名路径不拦。
 *
 * 退出码: 0 = 允许  2 = 阻断(stderr 输出原因与合规入口)
 */
'use strict';

// 相对仓库根的保护路径规则
const PROTECTED_RULES = [
  { re: /^scripts\/baselines\/[^/]+\.json$/, label: 'API 契约孤儿 baseline（脚本独占写）',
    hint: '合法入口: node scripts/check-api-contract-fe-be.mjs --update-baseline（只减不增,经白名单排除+count 单调校验）' },
  { re: /^docs\/ipd-系统说明\/api-internal-whitelist\.json$/, label: 'API 内部/运维口白名单（人审数据文件）',
    hint: '白名单新增/变更须挂看板卡执行(条目含 owner_card 可验卡号 + reason + expire);六条防伪校验在门禁运行时强制' },
];

function readStdinSync() {
  try {
    const fs = require('fs');
    return fs.readFileSync(0, 'utf8');
  } catch {
    return '';
  }
}

function main() {
  const raw = readStdinSync();
  if (!raw) process.exit(0);
  let input;
  try {
    input = JSON.parse(raw);
  } catch {
    process.exit(0); // 非 JSON 输入不阻断（与其他 guard 一致）
  }
  const filePath = (input.tool_input && (input.tool_input.file_path || input.tool_input.notebook_path)) || '';
  if (!filePath) process.exit(0);

  const projRoot = String(process.env.CLAUDE_PROJECT_DIR || process.cwd()).replace(/\\/g, '/').replace(/\/+$/, '');
  const norm = String(filePath).replace(/\\/g, '/');
  const abs = norm.startsWith('/') ? norm : `${projRoot}/${norm}`;
  // 仓外路径一律放行
  if (abs !== projRoot && !abs.startsWith(`${projRoot}/`)) process.exit(0);
  const rel = abs === projRoot ? '' : abs.slice(projRoot.length + 1);

  for (const p of PROTECTED_RULES) {
    if (p.re.test(rel)) {
      process.stderr.write(`[BLOCKED by ratchet-data-guard] ${p.label}: ${filePath}\n`);
      process.stderr.write(`  ${p.hint}\n`);
      process.stderr.write(`  依据: R212 孤儿棘轮门禁(卡 7b76b7cd) 方案D 数据文件保护;直接编辑将被 baseline sha256/HEAD 硬闸判为「未通过脚本写入口」。\n`);
      process.exit(2);
    }
  }
  process.exit(0);
}

main();
