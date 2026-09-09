#!/usr/bin/env node
// E5 (R25) PreToolUse：SSOT 5 文件写保护——只允许本机主协调会话修改
// 判定：仓库 CLAUDE.md 含 `OPS-09-MAIN-COORDINATOR=true` 标记 → 放行；
//       无标记的会话写 SSOT → exit 2 拦截（提示谁该打标记 / 如何临时放行）。
// 覆盖文件（长期 SSOT，不含临时验收 json）：
//   docs/ipd-系统说明/开发计划-看板镜像.md
//   docs/ipd-系统说明/log.md
//   AGENTS.md
//   CLAUDE.md
//   README-IPD-OVERRIDE.md
// 临时放行：SSOT_WRITE_OVERRIDE=1（owner 明确授权场景，如一次性接手轮）。
const fs = require('fs');
const path = require('path');

let raw = '';
try { raw = fs.readFileSync(0, 'utf8'); } catch { process.exit(0); }
if (!raw) process.exit(0);
if (process.env.SSOT_WRITE_OVERRIDE === '1') process.exit(0);

let filePath = '';
const m = raw.match(/"file_path"\s*:\s*"([^"]+)"/);
if (m) filePath = m[1];
if (!filePath) process.exit(0);

const ROOT = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const SSOT_FILES = [
  'docs/ipd-系统说明/开发计划-看板镜像.md',
  'docs/ipd-系统说明/log.md',
  'AGENTS.md',
  'CLAUDE.md',
  'README-IPD-OVERRIDE.md',
];
const norm = (p) => path.normalize(p).replace(/\\/g, '/');
const isSSOT = SSOT_FILES.some((f) => {
  const abs = norm(path.join(ROOT, f));
  return norm(filePath) === abs || norm(filePath).endsWith('/' + f);
});
if (!isSSOT) process.exit(0);

// 主协调判定：CLAUDE.md 含主协调标记
let claudeMd = '';
try { claudeMd = fs.readFileSync(path.join(ROOT, 'CLAUDE.md'), 'utf8'); } catch {}
if (/OPS-09-MAIN-COORDINATOR\s*=\s*true/.test(claudeMd)) process.exit(0);

console.error(`::error::[ssot-write-guard] ${path.basename(filePath)} 是 SSOT 文件，当前会话未声明为主协调者，写入被拦。`);
console.error('  合规路径（三选一）：');
console.error('  ① 本会话是当前主协调者 → 在 CLAUDE.md 头部写一行标记 OPS-09-MAIN-COORDINATOR=true（写 CLAUDE.md 本身需 owner 授权）');
console.error('  ② owner 明确授权的一次性接手 → 环境变量 SSOT_WRITE_OVERRIDE=1');
console.error('  ③ 非主协调会话 → 不要直接写 SSOT，交付证据给主协调会话代写（OPS-09 默认纪律）');
process.exit(2);
