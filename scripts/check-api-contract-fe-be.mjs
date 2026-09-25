#!/usr/bin/env node
/**
 * check-api-contract-fe-be.mjs — 前后端 API 契约门禁（2026-09-18）
 *
 * 根因: R25 病根 ④ — 前后端契约无门禁（重演 R34 platform-token 漏报根因）
 * 依据: R37-API契约diff-20260918.md
 *       - 前端 apps/web-antd/src/api/ipd/*.ts 56 文件
 *       - 后端 ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ 51 Controller
 *       - 跨模块 ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/ 1 Controller（避免 R34 漏报）
 *       - R37 实测: 0 P0 孤儿路径 + 10 字段名错位 + 101 BE 孤儿端点
 *
 * 三向 diff（按 R25 病根 ④ 要求）:
 *   (a) 孤儿路径 — 前端调/后端无 → 默认 exit 1（P0 白屏风险）
 *   (b) 孤儿端点 — 后端有/前端未调 → 仅警告（exit 0，流程待办不阻断）
 *   (c) 字段错位 — 路径可路由但变量名错（如 `{documentId}` vs `{id}`）→
 *                  默认警告（exit 0），加 `--strict` 时 exit 1
 *
 * 用法:
 *   node scripts/check-api-contract-fe-be.mjs                 # 默认: 孤儿路径阻断 + 孤儿棘轮门禁(ratchet=fail)
 *   node scripts/check-api-contract-fe-be.mjs --strict         # 字段错位也阻断
 *   node scripts/check-api-contract-fe-be.mjs --json           # JSON 输出（CI 友好）
 *   node scripts/check-api-contract-fe-be.mjs --json --strict  # 组合
 *   node scripts/check-api-contract-fe-be.mjs --update-baseline # 生成/收缩 baseline（脚本独占写,只减不增）
 *   node scripts/check-api-contract-fe-be.mjs --ratchet=off    # 逃生阀:关闭孤儿棘轮门禁(恢复 R212 前行为)
 *
 * 孤儿棘轮门禁(R212 / 卡 7b76b7cd API-GATE-RATCHET, owner 2026-09-24 拍板):
 *   --whitelist <p>   白名单文件(默认 docs/ipd-系统说明/api-internal-whitelist.json)
 *   --baseline <p>    baseline 文件(默认 scripts/baselines/api-contract-orphan-baseline.json)
 *   --update-baseline 以当前扫描结果收缩 baseline(只减不增,新孤儿拒绝入账)
 *   --ratchet=off|fail  默认 fail;off=跳过门禁(逃生阀)
 *   --min-fe-files N / --min-be-files N  输入哨兵阈值(默认 56/52, S1 参数化)
 *
 * 前端根解析(R110,对齐 tri-source R98 修法):
 *   IPD_FE_API_DIR 环境变量（前端 <fe-root>/src/api/ipd 目录）
 *   > 仓库同级 ../ruoyi-ipd-web/apps/web-antd 自动推断
 *   > 本机默认值。--fe-root 参数仍可显式覆盖以上三者。
 *
 * 退出码（R212 方案 A 位掩码, 2026-09-24 owner 拍板; 既有 1/2 语义不变, 4 为新增位, 可叠加）:
 *   0 = PASS
 *   1 = 发现 P0 孤儿路径；或 --strict 时发现字段错位/白名单漂移(stale)
 *   2 = 脚本/输入错误（缺路径/解析异常/白名单防伪失败/baseline 被手工编辑）
 *   4 = 新增孤儿端点未在白名单/baseline 内（棘轮只减不增被破坏）
 *   叠加示例: 1|4=5 (P0+新孤儿)  2|4=6 (环境错+新孤儿)  判定优先级 ENV(2) > BLOCK(1) > NEW_ORPHAN(4)
 *
 * 自证能红(R37 数据):
 *   - 0 孤儿路径
 *   - 10 字段错位(ai-document×6 + bid×2 + project-circle×1 + project×1)
 *   - ~80 孤儿端点（warnings, exit 0；略小于 R37 报告 101 是因 portal.ts 公共通道排除）
 */
'use strict';

import { readFile, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, basename, relative, resolve as resolvePath } from 'node:path';
import { fileURLToPath } from 'node:url';

// R212 孤儿棘轮门禁核心库（与扫描逻辑解耦；卡 7b76b7cd API-GATE-RATCHET）
import {
  EXIT_BITS, GateEnvError, gitHead, gitShowHeadFile,
  loadJsonFile, loadBaseline, validateWhitelist, classifyOrphans, writeBaseline, todayStr,
} from './api-contract/orphan-gate-lib.mjs';

// ---------- 默认扫描路径(R37 实测工作区; R212-S1: beRoot 由 REPO_ROOT 推断,去本机绝对路径硬编码) ----------
const DEFAULT_FE_ROOT  = '/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd';

// R110:前端根参数化(对齐 check-contract-tri-source.sh R98 修法)
// 优先级:IPD_FE_API_DIR 环境变量(语义:前端 api/ipd 目录) > 仓库同级 ruoyi-ipd-web 推断 > 本机默认值
// 本地与 CI 均可跑,不再硬绑定本机绝对路径。
const REPO_ROOT = resolvePath(dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_BE_ROOT = REPO_ROOT; // R212-S1: 脚本自身就在 BE 仓内,默认后端根 = REPO_ROOT

// R212 孤儿棘轮门禁数据文件（r212-gate-design.md §3/§4.1 指定位置）
const DEFAULT_WHITELIST_REL = 'docs/ipd-系统说明/api-internal-whitelist.json'; // 相对 REPO_ROOT
const DEFAULT_BASELINE_REL  = 'scripts/baselines/api-contract-orphan-baseline.json'; // 相对 REPO_ROOT

function resolveFeRoot() {
  // IPD_FE_API_DIR 与 tri-source 同语义:指向前端 <fe-root>/src/api/ipd 目录,
  // 此处剥离 src/api/ipd 三层还原 feRoot(内部 feApiDir = join(feRoot, FE_API_DIR) 还原回同一目录)。
  // R212-S1 验收③: 显式设置但目录不存在 → exit 2 指名缺失目录(不再静默 fallback;
  //   静默回退会让 CI「以为在测 A 前端实际测了 B」。未设置时的同级推断/默认值链路保持不变)。
  const envDir = process.env.IPD_FE_API_DIR;
  if (envDir) {
    if (!existsSync(envDir)) {
      process.stderr.write(`[check-api-contract-fe-be] ❌ IPD_FE_API_DIR 指向的目录不存在: ${envDir}\n`);
      process.exit(2);
    }
    return dirname(dirname(dirname(envDir)));
  }
  const sibling = resolvePath(REPO_ROOT, '..', 'ruoyi-ipd-web', 'apps', 'web-antd');
  if (existsSync(sibling)) {
    return sibling;
  }
  return DEFAULT_FE_ROOT;
}

// R215 盲区修正：src/api/core 是 IPD 会话桥接层（requestIpd/ipdGet 直调 /auth/me、/auth/logout、
// /system/menu/getRouters 等）；src/store 含 authenticatedRequest('/auth/change-password') 等 store 层直调。
// 此前只扫 src/api/ipd 导致这些真调用被误判为孤儿端点。
const FE_API_DIRS    = ['src/api/ipd', 'src/api/core', 'src/store', 'src/utils'];
const BE_CTRL_DIRS    = [
  'ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller',
  'ruoyi-admin/src/main/java/org/ruoyi/ipd/controller', // R34: IpdPlatformAuthController 物理位置
];

// 内部占位符：使用 NULL 字符包裹避免与 `{VAR}` 字面冲突
const INTERNAL_PLACEHOLDER = '\x00VAR\x00';
const CANONICAL_PLACEHOLDER = '{VAR}';

// ---------- CLI ----------
function parseArgs(argv) {
  // R212 改动点 A: 新增棘轮门禁参数（白名单/baseline 路径、--update-baseline、--ratchet 三态逃生阀、
  // 哨兵阈值参数化 S1）。默认 ratchet=fail（owner 拍板②：棘轮门禁默认生效）。
  const opts = {
    strict: false, json: false,
    feRoot: resolveFeRoot(), beRoot: DEFAULT_BE_ROOT,
    whitelist: join(DEFAULT_BE_ROOT, DEFAULT_WHITELIST_REL),
    baseline: join(DEFAULT_BE_ROOT, DEFAULT_BASELINE_REL),
    updateBaseline: false,
    ratchet: 'fail',
    minFeFiles: 56, minBeFiles: 52, // R37 基线阈值（S1 参数化,默认值不变）
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--strict')      opts.strict = true;
    else if (a === '--json')   opts.json = true;
    else if (a === '--fe-root' && argv[i + 1]) { opts.feRoot = argv[++i]; }
    else if (a === '--be-root' && argv[i + 1]) { opts.beRoot = argv[++i]; }
    else if (a === '--whitelist' && argv[i + 1]) { opts.whitelist = argv[++i]; }
    else if (a === '--baseline' && argv[i + 1]) { opts.baseline = argv[++i]; }
    else if (a === '--update-baseline') { opts.updateBaseline = true; }
    else if (a === '--no-ratchet') { opts.ratchet = 'off'; }
    else if (a.startsWith('--ratchet=')) {
      const v = a.slice('--ratchet='.length);
      if (v !== 'off' && v !== 'fail') {
        process.stderr.write(`[check-api-contract-fe-be] ❌ --ratchet 仅支持 off|fail,收到: ${v}\n`);
        process.exit(2);
      }
      opts.ratchet = v;
    }
    else if (a === '--min-fe-files' && argv[i + 1]) { opts.minFeFiles = Number(argv[++i]); }
    else if (a === '--min-be-files' && argv[i + 1]) { opts.minBeFiles = Number(argv[++i]); }
    else if (a === '-h' || a === '--help') {
      process.stdout.write('Usage: node scripts/check-api-contract-fe-be.mjs [--strict] [--json] [--fe-root <p>] [--be-root <p>]\n' +
        '  [--whitelist <p>] [--baseline <p>] [--update-baseline] [--ratchet=off|fail] [--min-fe-files N] [--min-be-files N]\n');
      process.exit(0);
    }
    else {
      process.stderr.write(`[check-api-contract-fe-be] ❌ 未知参数: ${a}\n`);
      process.exit(2);
    }
  }
  return opts;
}

// ---------- 工具 ----------
async function listFiles(dir, suffix) {
  if (!existsSync(dir)) return [];
  const out = [];
  for (const ent of await readdir(dir, { withFileTypes: true })) {
    if (ent.isFile() && ent.name.endsWith(suffix)) out.push(join(dir, ent.name));
  }
  return out.sort();
}

// ---------- 路径归一 ----------
/**
 * 归一规则:
 *   - ${var} / ${encodeURIComponent(var)} / {var} / {var:.+}  → {VAR}
 *   - query string (?xxx=yyy) 忽略
 *   - 重复 / 折叠
 *   - 去除首尾 /
 * 修复（2026-09-18 v3）: 使用 \x00VAR\x00 作为内部占位符避免与 `{VAR}` 字面量冲突
 *   (避免对同一占位符重复 push 到 varNames)
 */
function normalizePath(rawPath, opts) {
  const keepVarNames = !!(opts && opts.keepVarNames);
  const noQuery = rawPath.split('?')[0];
  const varNames = [];
  let s = noQuery
    // 1) ${var} / ${encodeURIComponent(var)}
    .replace(/\$\{([^}]+)\}/g, (_m, inner) => {
      const id = inner.split(/[().]/).filter(Boolean).pop();
      if (keepVarNames) varNames.push(id);
      return INTERNAL_PLACEHOLDER;
    })
    // 2) {var} / {var:.+}
    .replace(/\{([^}]+)\}/g, (_m, inner) => {
      const id = inner.split(':')[0];
      // 跳过纯字面量 {VAR} / {var} 等占位符别名 — 但现在不可能，因为我们已经替换为 INTERNAL_PLACEHOLDER
      if (id === 'VAR' || id === 'var') return `{${inner}}`;
      if (keepVarNames) varNames.push(id);
      return INTERNAL_PLACEHOLDER;
    });
  // 3) 把内部占位符转为规范化字面量
  s = s.split(INTERNAL_PLACEHOLDER).join(CANONICAL_PLACEHOLDER);
  // 4) 折叠 / 去首尾
  s = s.replace(/\/+/g, '/').replace(/^\/|\/$/g, '');
  return keepVarNames ? { path: s, varNames } : s;
}

function fePathToCanonical(p, kind) {
  const norm = normalizePath(p);
  // R215：requestPortal 私有封装 base 为 /api/v1/public（portal.ts L101 fetch(`/api/v1/public${path}`)）
  if (kind === 'requestPortal') return '/api/v1/public/' + norm;
  if (norm.startsWith('api/v1')) return '/' + norm;
  return '/api/v1/' + norm;
}

function keyOf(method, canonical) { return `${method} ${canonical}`; }

// ---------- 前端扫描 ----------
async function scanFrontend(feApiDirs) {
  const files = [];
  for (const d of feApiDirs) {
    files.push(...await listFiles(d, '.ts'));
  }
  const calls = [];
  for (const file of files) {
    const isTest = basename(file).endsWith('.test.ts');
    const src = await readFile(file, 'utf8');

    // 模式 1: ipdGet / ipdPost / ipdPut / ipdDelete / ipdPatch (camelCase 或大写都可)
    //   - 可选泛型: <T> / <T = unknown> / 嵌套一层 <Record<string, unknown>>（R215 修正：
    //     旧版 <[^>]*> 遇嵌套泛型只吞一半，残留 ">(" 导致整条调用被跳过 → as-of 端点假孤儿）
    //   - 字符串参数: '..' | ".." | `..`
    const reIpdCall = /ipd(GET|POST|PUT|DELETE|PATCH)\s*(?:<(?:[^<>]|<[^<>]*>)*>)?\s*\(\s*([`'"])(.+?)\2/gi;
    let m;
    while ((m = reIpdCall.exec(src)) !== null) {
      calls.push({
        kind: 'ipd',
        method: m[1].toUpperCase(),
        rawPath: m[3],
        file,
        line: src.slice(0, m.index).split('\n').length,
        isTest,
      });
    }

    // 模式 2: requestIpd / authenticatedRequest / requestPortal('path', { method: 'POST', ... }) 或 (..., 'POST')
    // R215 修正：① 函数名扩至 store 层 authenticatedRequest 与 portal 私有 requestPortal；
    // ② 第二参数改为可选（authenticatedRequest('/auth/me') 单参形式）；③ 支持一层嵌套泛型。
    // requestPortal 的 base 为 /api/v1/public（归一化时按 kind 分派，见 fePathToCanonical）。
    // R215：选项对象支持两层嵌套大括号（如 { method:'POST', body:{ x:(d as {t}).x } }——body 内还有类型断言大括号）
    const reReqIpd = /(?:requestIpd|authenticatedRequest|requestPortal)\s*(?:<(?:[^<>]|<[^<>]*>)*>)?\s*\(\s*([`'"])(.+?)\1(?:\s*,\s*(?:\{((?:[^{}]|\{(?:[^{}]|\{[^{}]*\})*\})*)\}|['"]([A-Z]+)['"]))?/g;
    while ((m = reReqIpd.exec(src)) !== null) {
      // 纯变量模板（如 http.ts 封装层 `${path}${buildQuery(query)}`）无静态路径段，跳过
      if (m[2].startsWith('${')) continue;
      let method = 'GET';
      const objBody = m[3];
      const literalMethod = m[4];
      if (literalMethod) method = literalMethod;
      else if (objBody) {
        const mm = /method\s*:\s*['"]([A-Z]+)['"]/i.exec(objBody);
        if (mm) method = mm[1].toUpperCase();
      }
      const fn = src.slice(m.index, m.index + 40).split(/[\s(<]/)[0];
      calls.push({
        kind: fn.includes('requestPortal') ? 'requestPortal' : 'requestIpd',
        method,
        rawPath: m[2],
        file,
        line: src.slice(0, m.index).split('\n').length,
        isTest,
      });
    }

    // 模式 3: raw fetch / EventSource 直调字面量（R215 五连修正：ai-copilot SSE 流与 resource/sse
    // 不经 ipdGet/requestIpd 封装层，旧版三模式均漏采 → 两条假孤儿；此前 BE 侧多属性注解盲区已同步修复）
    //   3a) fetch('/api/v1/...') 绝对字面量（单/双/反引号都可，query 由 normalizePath 剔除）
    //   3b) `${apiURL}/resource/sse...` 模板字面量（apiURL 已含 /api/v1，VITE_GLOB_API_URL），拼回前缀归一
    //       实际形态为 const sseAddr = `${apiURL}/resource/sse?...` 再传 useEventSource，故直接采模板字面量而非函数调用
    //   防误采：先剥整行注释（message.ts:29 注释里的历史串 `${apiURL}/v1/resource/sse` 曾造出假孤儿）；
    //   跳过 /api/v1/public${...}（portal 私有封装已由模式 2 requestPortal 按 kind 归一，重复采集会因拼接形态不一致产生假孤儿）
    const srcCode = src.replace(/^\s*\/\/[^\n]*$/gm, '');
    const reRawFetch = /\bfetch\s*\(\s*([`'"])(\/api\/v1\/[^`'"]*)\1/g;
    while ((m = reRawFetch.exec(srcCode)) !== null) {
      if (/\/api\/v1\/public\$\{/.test(m[2])) continue;
      calls.push({
        kind: 'ipd',
        method: 'GET',
        rawPath: m[2],
        file,
        line: src.slice(0, m.index).split('\n').length,
        isTest,
      });
    }
    const reApiUrlTpl = /`\$\{apiURL\}(\/[A-Za-z0-9_\-/]+)/g;
    while ((m = reApiUrlTpl.exec(srcCode)) !== null) {
      calls.push({
        kind: 'ipd',
        method: 'GET',
        rawPath: '/api/v1' + m[1],
        file,
        line: src.slice(0, m.index).split('\n').length,
        isTest,
      });
    }
  }
  return { files, calls };
}

// ---------- 后端扫描 ----------
async function scanBackend(beRoot, ctrlDirs) {
  const endpoints = [];
  const files = [];
  for (const d of ctrlDirs) {
    const full = join(beRoot, d);
    if (!existsSync(full)) continue;
    const list = await listFiles(full, '.java');
    for (const f of list) {
      files.push(f);
      const src = await readFile(f, 'utf8');
      // 类级 @RequestMapping("/api/v1/xxx")
      let classPrefix = '';
      const reCls = /@RequestMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']+)["']/;
      const clsM = reCls.exec(src);
      if (clsM) classPrefix = clsM[1];

      // 方法级 @(Get|Post|Put|Delete|Patch)Mapping("/yyy") — 路径可选；
      // R215 五连修正：支持多属性注解 @GetMapping(value="/x", produces=...)（旧式要求引号后紧跟右括号，SSE 类端点被解析成类前缀幽灵路径，ai-copilot/resource 两条假孤儿即此因）
      const reMethod = /@(Get|Post|Put|Delete|Patch)Mapping\b\s*(?:\(\s*(?:(?:value|path)\s*=\s*)?(?:\{\s*)?["']([^"']*)["'][^)]*\))?/g;
      let mm;
      while ((mm = reMethod.exec(src)) !== null) {
        const method = mm[1].toUpperCase();
        const sub = mm[2] === undefined ? '' : mm[2];
        const fullPath = (classPrefix + sub).replace(/\/+/g, '/');
        const { path: canonical, varNames } = normalizePath(fullPath, { keepVarNames: true });
        endpoints.push({
          method,
          rawPath: fullPath,
          canonical: '/' + canonical,
          varNames,
          file: f,
          line: src.slice(0, mm.index).split('\n').length,
        });
      }
    }
  }
  return { files, endpoints };
}

// ---------- 字段错位检测 ----------
// R215 口径修正：两侧 canonical（{VAR} 归一化）能匹配即意味着段数/位置一致，
// 前端模板变量名（形参名）与后端 @PathVariable 名不同只是命名差异，URL 按位置传值，
// 运行时零影响（R215 逐条验证 15 条全为此类误报）。
// 保留两类真错位的抓取能力：① 变量「数量」不同（可抓前端 `${id}/x/${id}` 复用笔误）；② 未来归一化器变化时的防御。
function detectFieldMismatches(feCanonicalList, beByCanonical) {
  const mismatches = [];
  const nameDiffs = []; // info 级：路径变量命名差异（同数不同名，运行时无影响）
  const feByCanonical = new Map();
  for (const c of feCanonicalList) {
    if (!feByCanonical.has(c.canonical)) feByCanonical.set(c.canonical, []);
    feByCanonical.get(c.canonical).push(c);
  }
  for (const [canonical, feList] of feByCanonical) {
    const beList = beByCanonical.get(canonical);
    if (!beList || beList.length === 0) continue;
    for (const fe of feList) {
      for (const be of beList) {
        const feVars = new Set(fe.varNames);
        const beVars = new Set(be.varNames);
        const sameLen = feVars.size === beVars.size;
        const feInBe = [...feVars].every(v => beVars.has(v));
        const beInFe = [...beVars].every(v => feVars.has(v));
        if (!sameLen) {
          // 数量不同：可能是前端复用同一变量的笔误（如 `${id}/x/${id}`），仍算 P1
          mismatches.push({
            canonical,
            feVars: [...feVars],
            beVars: [...beVars],
            feRawPath: fe.rawPath,
            beRawPath: be.rawPath,
            feFile: fe.file,
            feLine: fe.line,
            beFile: be.file,
            beLine: be.line,
          });
        } else if (!(feInBe && beInFe)) {
          // 同数不同名：纯命名差异（前端形参名 vs 后端 PathVariable 名），降级 info
          nameDiffs.push({
            canonical,
            feVars: [...feVars],
            beVars: [...beVars],
            feFile: fe.file,
            feLine: fe.line,
            beFile: be.file,
            beLine: be.line,
          });
        }
      }
    }
  }
  // 去重
  const seen = new Set();
  const deduped = [];
  for (const m of mismatches) {
    const key = `${m.canonical}|${m.feVars.join(',')}|${m.beVars.join(',')}`;
    if (seen.has(key)) continue;
    seen.add(key);
    deduped.push(m);
  }
  const seenDiff = new Set();
  const dedupedDiffs = [];
  for (const m of nameDiffs) {
    const key = `${m.canonical}|${m.feVars.join(',')}|${m.beVars.join(',')}`;
    if (seenDiff.has(key)) continue;
    seenDiff.add(key);
    dedupedDiffs.push(m);
  }
  return { mismatches: deduped, nameDiffs: dedupedDiffs };
}

// ---------- 主流程 ----------
async function main() {
  const opts = parseArgs(process.argv);
  const feApiDirs = FE_API_DIRS.map(d => join(opts.feRoot, d));
  const beCtrls   = BE_CTRL_DIRS.map(d => join(opts.beRoot, d));

  for (const d of feApiDirs) {
    if (!existsSync(d)) {
      process.stderr.write(`[check-api-contract-fe-be] ❌ 前端 API 目录不存在: ${d}\n`);
      process.exit(2);
    }
  }
  for (const d of beCtrls) {
    if (!existsSync(d)) {
      process.stderr.write(`[check-api-contract-fe-be] ❌ 后端 controller 目录不存在: ${d}\n`);
      process.exit(2);
    }
  }

  const { files: feFiles, calls: feCalls }     = await scanFrontend(feApiDirs);
  const { files: beFiles, endpoints: beEps }   = await scanBackend(opts.beRoot, BE_CTRL_DIRS);

  // 输入层哨兵（R37 基线; R212-S1 阈值参数化 --min-fe-files/--min-be-files, 默认 56/52 不变）
  const feNonTest = feFiles.filter(f => !basename(f).endsWith('.test.ts')).length;
  if (feFiles.length < opts.minFeFiles) {
    process.stderr.write(`[check-api-contract-fe-be] ❌ 前端 .ts 文件 ${feFiles.length} < ${opts.minFeFiles}（R37 基线，扫描路径错位？）\n`);
    process.exit(2);
  }
  if (beFiles.length < opts.minBeFiles) {
    process.stderr.write(`[check-api-contract-fe-be] ❌ 后端 controller ${beFiles.length} < ${opts.minBeFiles}（R37 基线 + ruoyi-admin 跨模块，扫描路径错位？）\n`);
    process.exit(2);
  }

  const feProd = feCalls.filter(c => !c.isTest);
  const beKeys = new Set();
  const beByCanonical = new Map();
  for (const ep of beEps) {
    const k = keyOf(ep.method, ep.canonical);
    beKeys.add(k);
    if (!beByCanonical.has(ep.canonical)) beByCanonical.set(ep.canonical, []);
    beByCanonical.get(ep.canonical).push(ep);
  }

  // (a) 孤儿路径
  const orphanPaths = [];
  const feCanonicalList = [];
  const feSeenKeys = new Set();
  for (const c of feProd) {
    const canonical = fePathToCanonical(c.rawPath, c.kind);
    const item = {
      method: c.method,
      fePath: c.rawPath,
      canonical,
      file: c.file,
      line: c.line,
    };
    feCanonicalList.push({
      ...item,
      varNames: normalizePath(c.rawPath, { keepVarNames: true }).varNames,
    });
    const uniq = `${c.method} ${canonical}|${c.file}:${c.line}`;
    if (!feSeenKeys.has(uniq)) {
      feSeenKeys.add(uniq);
      if (!beKeys.has(keyOf(c.method, canonical))) {
        orphanPaths.push(item);
      }
    }
  }

  // (b) 孤儿端点
  const feUsedCanonical = new Set(feCanonicalList.map(c => c.canonical));
  const orphanEndpoints = [];
  const beSeenCanon = new Set();
  for (const ep of beEps) {
    if (beSeenCanon.has(ep.canonical)) continue;
    beSeenCanon.add(ep.canonical);
    if (!feUsedCanonical.has(ep.canonical)) {
      orphanEndpoints.push({
        method: ep.method,
        path: ep.canonical,
        rawPath: ep.rawPath,
        file: ep.file,
        line: ep.line,
      });
    }
  }

  // (c) 字段错位
  const { mismatches: fieldMismatches, nameDiffs: pathVarNameDiffs } = detectFieldMismatches(feCanonicalList, beByCanonical);

  // ---------- (d) R212 孤儿棘轮门禁（改动点 B: classifyOrphans; ratchet=off 时跳过,恢复旧口径仅警告） ----------
  const gitHeadVal = gitHead(opts.beRoot);
  const genCmd = 'node ' + [relative(process.cwd(), fileURLToPath(import.meta.url)) || 'scripts/check-api-contract-fe-be.mjs', ...process.argv.slice(2)].join(' ');
  let exitBits = EXIT_BITS.PASS;
  let orphanGate = { ratchet: opts.ratchet, skipped: true };
  let envErrors = [];   // 可恢复环境错(bit 2): 白名单防伪失败 / baseline 损坏 —— 不硬退,可与 bit 4 叠加(exit 6)

  if (opts.ratchet === 'off') {
    orphanGate = { ratchet: 'off', skipped: true, note: '逃生阀已启用(--ratchet=off/--no-ratchet): 孤儿端点退回仅警告口径' };
  } else {
    // ① 白名单加载 + 六条防伪校验（§3.2）
    const beCanonicalSet = new Set(beEps.map(e => e.canonical));
    const beFilesContent = new Map(); // basename → 行数组（evidence 第3条机械校验）
    for (const f of beFiles) {
      const srcTxt = await readFile(f, 'utf8');
      beFilesContent.set(basename(f), srcTxt.split('\n'));
    }
    const mirrorPath = join(opts.beRoot, 'docs/ipd-系统说明/开发计划-看板镜像.md');
    let mirrorText = '';
    try { mirrorText = await readFile(mirrorPath, 'utf8'); }
    catch { envErrors.push(`看板镜像文件不可读(卡号防伪降级): ${mirrorPath}`); }

    const wlDoc = await loadJsonFile(opts.whitelist, { required: true, label: '白名单' });
    const wl = validateWhitelist(wlDoc, { beCanonicalSet, beFilesContent, mirrorText, today: todayStr() });
    if (wl.errors.length > 0) {
      // 防伪失败: bit 2 叠加 + 该文件不作为豁免来源(全部豁免失效,继续分类让问题全量暴露)
      envErrors.push(`白名单六条防伪校验失败 ${wl.errors.length} 条:`, ...wl.errors);
    }
    for (const w of wl.warnings) process.stderr.write(`[check-api-contract-fe-be] ⚠ ${w}\n`);

    // ② baseline 加载 + 双层防篡改（sha256 自洽 + git show HEAD 硬闸）
    let baselineDoc = null, baselineInfo = { valid: false, warnings: [], hardFail: null, checked_against_head: false };
    const baselineRel = relative(opts.beRoot, opts.baseline);
    if (!opts.updateBaseline) {
      try {
        const lb = await loadBaseline(opts.baseline, { repoRoot: opts.beRoot, relPath: baselineRel });
        baselineDoc = lb.doc;
        baselineInfo = { valid: !lb.hardFail, warnings: lb.warnings, hardFail: lb.hardFail, checked_against_head: lb.checkedAgainstHead };
        if (lb.hardFail) envErrors.push(lb.hardFail);
        for (const w of lb.warnings) process.stderr.write(`[check-api-contract-fe-be] ⚠ baseline: ${w}\n`);
      } catch (e) {
        if (e instanceof GateEnvError) { envErrors.push(e.message, ...(e.detail || [])); }
        else throw e;
      }
    }

    // ③ --update-baseline: 唯一合法写入口（防伪失败时拒绝生成,写入口必须干净）
    if (opts.updateBaseline) {
      if (wl.errors.length > 0) {
        process.stderr.write(`[check-api-contract-fe-be] ❌ --update-baseline 拒绝: 白名单防伪未通过,先修复再生成\n`);
        process.exit(EXIT_BITS.ENV);
      }
      let prevDoc = null;
      if (existsSync(opts.baseline)) {
        try { prevDoc = JSON.parse(await readFile(opts.baseline, 'utf8')); }
        catch { prevDoc = null; } // 损坏的旧文件不阻塞首写,但 growth_log 从零计
      }
      const headRaw = gitShowHeadFile(opts.beRoot, baselineRel);
      let headDoc = null;
      if (headRaw !== null) { try { headDoc = JSON.parse(headRaw); } catch { headDoc = null; } }
      try {
        const { doc, delta } = await writeBaseline(opts.baseline, {
          orphanEndpoints, whitelistActive: wl.activeMap, prevDoc, headDoc,
          generatedAt: new Date().toISOString(), gitHeadVal, genCmd, feRoot: opts.feRoot,
        });
        baselineDoc = doc;
        baselineInfo = { valid: true, warnings: [], hardFail: null, checked_against_head: headDoc !== null };
        process.stderr.write(`[check-api-contract-fe-be] 📝 baseline 已更新: count=${doc.count} (Δ${delta >= 0 ? '+' : ''}${delta}) → ${opts.baseline}\n`);
      } catch (e) {
        if (e instanceof GateEnvError) {
          process.stderr.write(`[check-api-contract-fe-be] ❌ ${e.message}\n`);
          for (const d of e.detail || []) process.stderr.write(`    - ${d}\n`);
          process.exit(EXIT_BITS.ENV);
        }
        throw e;
      }
    }

    // ④ 分诊 classifyOrphans（改动点 B 核心）
    const baselinePaths = new Set(baselineDoc && Array.isArray(baselineDoc.paths) ? baselineDoc.paths : []);
    const classified = classifyOrphans(orphanEndpoints, baselinePaths, wl.activeMap, wl.entriesByCanonical);

    // ⑤ baseline 无效时其豁免不可信 → 相关条目视为未覆盖,全量暴露为 violations(不静默放行)
    let violations = classified.violations;
    if (baselineInfo.hardFail && baselineDoc) {
      const demoted = classified.exempt_baseline;
      if (demoted.length > 0) {
        violations = [...violations, ...demoted];
        process.stderr.write(`[check-api-contract-fe-be] ⚠ baseline 无效: ${demoted.length} 条 baseline 豁免降级为待处置(不静默放行)\n`);
      }
    }

    // vs baseline 方向行（R212「105→98 净消亡」同口径）
    const curSet = new Set(orphanEndpoints.map(o => o.path));
    const resolved = [...baselinePaths].filter(x => !curSet.has(x)).length;   // 消亡(baseline 有/现非孤儿)
    const added = [...curSet].filter(x => !baselinePaths.has(x)).length;      // 豁免外新增

    orphanGate = {
      ratchet: 'fail',
      skipped: false,
      whitelist: {
        path: opts.whitelist, entries: wlDoc.entries.length, active: wl.activeMap.size,
        expired: wl.expiredEntries.length, validation_errors: wl.errors.length,
      },
      baseline: {
        path: opts.baseline, count: baselineDoc ? baselineDoc.count : null,
        git_head_ref: baselineDoc ? baselineDoc.git_head : null,
        generated_at: baselineDoc ? baselineDoc.generated_at : null,
        checked_against_head: baselineInfo.checked_against_head,
        valid: baselineInfo.valid,
      },
      exempt_baseline_count: classified.exempt_baseline.length,
      exempt_whitelist_count: classified.exempt_whitelist.length,
      violations,
      expired_orphans: classified.expired_orphans,
      stale_whitelist: classified.stale_whitelist,
      vs_baseline: { resolved, added },
      env_errors: envErrors,
    };
    if (violations.length > 0) exitBits |= EXIT_BITS.NEW_ORPHAN;
    if (envErrors.length > 0) exitBits |= EXIT_BITS.ENV;
    // §3.2-5 反向清账: strict 模式下白名单漂移(stale)也阻断(bit 1)
    if (opts.strict && classified.stale_whitelist.length > 0) exitBits |= EXIT_BITS.BLOCK;
  }

  // ---------- 判定（改动点 C: pass 语义扩展,既有 1/2 不动） ----------
  const hasP0  = orphanPaths.length > 0;
  const hasP1  = fieldMismatches.length > 0;
  const failByStrict = opts.strict && hasP1;
  const pass = !hasP0 && !failByStrict && exitBits === EXIT_BITS.PASS;

  const report = {
    check: 'api-contract-fe-be',
    scan: {
      fe_files_total: feFiles.length,
      fe_files_non_test: feNonTest,
      fe_calls_total: feCalls.length,
      fe_calls_non_test: feProd.length,
      backend_files_total: beFiles.length,
      backend_dirs: BE_CTRL_DIRS,
      timestamp: new Date().toISOString(),
      // R212-S1 自证字段（堵 P4 溯源缺口: 无 git HEAD / 无生成命令）
      git_head: gitHeadVal,
      cmd: genCmd,
      fe_root: opts.feRoot,
      be_root: opts.beRoot,
    },
    orphan_paths: orphanPaths,
    orphan_endpoints: orphanEndpoints,
    field_mismatches: fieldMismatches,
    path_var_name_diffs: pathVarNameDiffs,
    orphan_gate: orphanGate, // R212 棘轮门禁段（ratchet=off 时仅含 skipped 标记）
    summary: {
      orphan_paths_count: orphanPaths.length,
      orphan_endpoints_count: orphanEndpoints.length,
      field_mismatches_count: fieldMismatches.length,
      path_var_name_diffs_count: pathVarNameDiffs.length,
      new_orphan_violations_count: (orphanGate.violations || []).length, // R212: 未白名单新孤儿数
    },
    pass,
    mode: { strict: opts.strict, json: opts.json, ratchet: opts.ratchet },
  };

  if (opts.json) {
    process.stdout.write(JSON.stringify(report, null, 2) + '\n');
  } else {
    process.stdout.write(`==== 前后端 API 契约门禁 (R25 病根 ④) ====\n`);
    process.stdout.write(`前端 ${feNonTest}/${feFiles.length} 个 .ts 文件（剔除 .test.ts）, 端点调用 ${feProd.length} 处\n`);
    process.stdout.write(`后端 ${beFiles.length} 个 controller, 跨模块扫描: ${BE_CTRL_DIRS.join(' + ')}\n\n`);
    process.stdout.write(`-- (a) 孤儿路径 (P0,默认阻断) -- ${orphanPaths.length}\n`);
    for (const o of orphanPaths.slice(0, 20)) {
      process.stdout.write(`  [${o.method}] ${o.canonical}  ← ${o.file}:${o.line} (raw: ${o.fePath})\n`);
    }
    process.stdout.write(`\n-- (b) 孤儿端点 (P2,警告) -- ${orphanEndpoints.length}\n`);
    for (const o of orphanEndpoints.slice(0, 20)) {
      process.stdout.write(`  [${o.method}] ${o.path}  ← ${o.file}:${o.line}\n`);
    }
    if (!orphanGate.skipped && orphanGate.baseline && orphanGate.baseline.count !== null) {
      const vb = orphanGate.vs_baseline;
      process.stdout.write(`  vs baseline: -${vb.resolved} / +${vb.added}  (baseline=${orphanGate.baseline.count}, 白名单豁免=${orphanGate.exempt_whitelist_count}, 老账豁免=${orphanGate.exempt_baseline_count})\n`);
    }
    process.stdout.write(`\n-- (c) 字段名错位 (P1${opts.strict ? ',strict 阻断' : ',警告'}) -- ${fieldMismatches.length}\n`);
    for (const m of fieldMismatches) {
      process.stdout.write(`  ${m.canonical}\n    FE vars: [${m.feVars.join(', ')}]  ← ${m.feFile}:${m.feLine}\n    BE vars: [${m.beVars.join(', ')}]  ← ${m.beFile}:${m.beLine}\n`);
    }
    process.stdout.write(`\n-- (d) 路径变量命名差异 (info，同数不同名，运行时无影响；R215 口径修正) -- ${pathVarNameDiffs.length}\n`);
    for (const m of pathVarNameDiffs) {
      process.stdout.write(`  ${m.canonical}  FE:${m.feVars.join('/')} vs BE:${m.beVars.join('/')}  ← ${m.feFile}:${m.feLine}\n`);
    }
    // -- (e) R212 孤儿棘轮门禁 --
    if (orphanGate.skipped) {
      process.stdout.write(`\n-- (e) 孤儿棘轮门禁 (R212) -- SKIPPED  (${orphanGate.note || 'ratchet=off'})\n`);
    } else {
      process.stdout.write(`\n-- (e) 孤儿棘轮门禁 (R212, 只减不增) --\n`);
      process.stdout.write(`  白名单: ${orphanGate.whitelist.entries} 条登记 / ${orphanGate.whitelist.active} 条生效 / 防伪错误 ${orphanGate.whitelist.validation_errors}\n`);
      process.stdout.write(`  baseline: ${orphanGate.baseline.count} 条老账 (HEAD 硬闸=${orphanGate.baseline.checked_against_head ? '已比对' : '未生效(未提交)'}, 有效=${orphanGate.baseline.valid})\n`);
      if ((orphanGate.env_errors || []).length > 0) {
        process.stdout.write(`  ❌ 环境错(bit2) ${orphanGate.env_errors.length} 条:\n`);
        for (const e2 of orphanGate.env_errors.slice(0, 8)) process.stdout.write(`    - ${e2}\n`);
      }
      if ((orphanGate.violations || []).length > 0) {
        process.stdout.write(`  ❌ 新孤儿未白名单(bit4) ${orphanGate.violations.length} 条:\n`);
        for (const v of orphanGate.violations.slice(0, 20)) process.stdout.write(`    [${v.method}] ${v.path}  ← ${v.file}:${v.line}\n`);
        process.stdout.write(`    处置: 补前端消费 / 删除端点 / 白名单登记(卡号+reason+expire, 须看板卡)\n`);
      }
      if ((orphanGate.expired_orphans || []).length > 0) {
        process.stdout.write(`  ⚠ 白名单过期回落(WARN,首版不阻断) ${orphanGate.expired_orphans.length} 条:\n`);
        for (const v of orphanGate.expired_orphans.slice(0, 10)) process.stdout.write(`    [${v.method}] ${v.path} (expire=${v.expire})\n`);
      }
      if ((orphanGate.stale_whitelist || []).length > 0) {
        process.stdout.write(`  ⚠ 白名单漂移 stale(前端已接线,应清账${opts.strict ? ',strict 下阻断' : ''}) ${orphanGate.stale_whitelist.length} 条:\n`);
        for (const s of orphanGate.stale_whitelist.slice(0, 10)) process.stdout.write(`    ${s.path} (${s.owner_card})\n`);
      }
    }
    process.stdout.write(`\n${pass ? '✅ PASS' : '❌ FAIL'}  (strict=${opts.strict}, ratchet=${opts.ratchet})\n`);
  }

  // 出口（R212 改动点 E: 方案 A 位掩码; 既有 1(P0/strict)=bit1、2(输入错)=bit2 语义不变, 新增 bit4=新孤儿）
  let exitCode = EXIT_BITS.PASS;
  if (hasP0) exitCode |= EXIT_BITS.BLOCK;              // 1: P0 孤儿路径（原语义）
  if (failByStrict) exitCode |= EXIT_BITS.BLOCK;       // 1: strict 字段错位（原语义）
  exitCode |= exitBits;                                // 2/4: 棘轮门禁段结论（可叠加: 5/6/7）
  if (exitCode !== EXIT_BITS.PASS) {
    if (!opts.json) {
      const parts = [];
      if (exitCode & EXIT_BITS.BLOCK) parts.push('1=阻断(P0孤儿路径/strict)');
      if (exitCode & EXIT_BITS.ENV) parts.push('2=环境或输入错');
      if (exitCode & EXIT_BITS.NEW_ORPHAN) parts.push('4=新孤儿未白名单');
      process.stdout.write(`exit code = ${exitCode} (${parts.join(' | ')})\n`);
    }
    process.exit(exitCode);
  }
}

main().catch(err => {
  process.stderr.write(`[check-api-contract-fe-be] ❌ 未捕获异常: ${err.stack || err.message}\n`);
  process.exit(2);
});
