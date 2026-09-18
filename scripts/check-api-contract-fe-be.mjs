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
 *   node scripts/check-api-contract-fe-be.mjs                 # 默认: 仅孤儿路径阻断
 *   node scripts/check-api-contract-fe-be.mjs --strict         # 字段错位也阻断
 *   node scripts/check-api-contract-fe-be.mjs --json           # JSON 输出（CI 友好）
 *   node scripts/check-api-contract-fe-be.mjs --json --strict  # 组合
 *
 * 退出码:
 *   0 = 无 P0 孤儿路径（默认）；无任何问题（--strict）
 *   1 = 发现 P0 孤儿路径；或 --strict 时发现字段错位
 *   2 = 脚本/输入错误（缺路径/解析异常）
 *
 * 自证能红(R37 数据):
 *   - 0 孤儿路径
 *   - 10 字段错位(ai-document×6 + bid×2 + project-circle×1 + project×1)
 *   - ~80 孤儿端点（warnings, exit 0；略小于 R37 报告 101 是因 portal.ts 公共通道排除）
 */
'use strict';

import { readFile, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, basename } from 'node:path';

// ---------- 默认扫描路径(R37 实测工作区) ----------
const DEFAULT_FE_ROOT  = '/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd';
const DEFAULT_BE_ROOT  = '/Users/mac/Documents/ruoyi-ai';

const FE_API_DIR      = 'src/api/ipd';
const BE_CTRL_DIRS    = [
  'ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller',
  'ruoyi-admin/src/main/java/org/ruoyi/ipd/controller', // R34: IpdPlatformAuthController 物理位置
];

// 内部占位符：使用 NULL 字符包裹避免与 `{VAR}` 字面冲突
const INTERNAL_PLACEHOLDER = '\x00VAR\x00';
const CANONICAL_PLACEHOLDER = '{VAR}';

// ---------- CLI ----------
function parseArgs(argv) {
  const opts = { strict: false, json: false, feRoot: DEFAULT_FE_ROOT, beRoot: DEFAULT_BE_ROOT };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--strict')      opts.strict = true;
    else if (a === '--json')   opts.json = true;
    else if (a === '--fe-root' && argv[i + 1]) { opts.feRoot = argv[++i]; }
    else if (a === '--be-root' && argv[i + 1]) { opts.beRoot = argv[++i]; }
    else if (a === '-h' || a === '--help') {
      process.stdout.write('Usage: node scripts/check-api-contract-fe-be.mjs [--strict] [--json] [--fe-root <p>] [--be-root <p>]\n');
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

function fePathToCanonical(p) {
  const norm = normalizePath(p);
  if (norm.startsWith('api/v1')) return '/' + norm;
  return '/api/v1/' + norm;
}

function keyOf(method, canonical) { return `${method} ${canonical}`; }

// ---------- 前端扫描 ----------
async function scanFrontend(feApiDir) {
  const files = await listFiles(feApiDir, '.ts');
  const calls = [];
  for (const file of files) {
    const isTest = basename(file).endsWith('.test.ts');
    const src = await readFile(file, 'utf8');

    // 模式 1: ipdGet / ipdPost / ipdPut / ipdDelete / ipdPatch (camelCase 或大写都可)
    //   - 可选泛型: <T> / <T = unknown>
    //   - 字符串参数: '..' | ".." | `..`
    const reIpdCall = /ipd(GET|POST|PUT|DELETE|PATCH)\s*(?:<[^>]*>)?\s*\(\s*([`'"])(.+?)\2/gi;
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

    // 模式 2: requestIpd('path', { method: 'POST', ... }) 或 (..., 'POST')
    const reReqIpd = /requestIpd\s*\(\s*([`'"])(.+?)\1\s*,\s*(?:\{([^}]*)\}|['"]([A-Z]+)['"])/g;
    while ((m = reReqIpd.exec(src)) !== null) {
      let method = 'GET';
      const objBody = m[3];
      const literalMethod = m[4];
      if (literalMethod) method = literalMethod;
      else if (objBody) {
        const mm = /method\s*:\s*['"]([A-Z]+)['"]/i.exec(objBody);
        if (mm) method = mm[1].toUpperCase();
      }
      calls.push({
        kind: 'requestIpd',
        method,
        rawPath: m[2],
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

      // 方法级 @(Get|Post|Put|Delete|Patch)Mapping("/yyy") — 路径可选
      const reMethod = /@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*(?:value\s*=\s*)?["']([^"']*)["']\s*\))?/g;
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
function detectFieldMismatches(feCanonicalList, beByCanonical) {
  const mismatches = [];
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
        if (!(sameLen && feInBe && beInFe)) {
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
  return deduped;
}

// ---------- 主流程 ----------
async function main() {
  const opts = parseArgs(process.argv);
  const feApiDir  = join(opts.feRoot, FE_API_DIR);
  const beCtrls   = BE_CTRL_DIRS.map(d => join(opts.beRoot, d));

  if (!existsSync(feApiDir)) {
    process.stderr.write(`[check-api-contract-fe-be] ❌ 前端 API 目录不存在: ${feApiDir}\n`);
    process.exit(2);
  }
  for (const d of beCtrls) {
    if (!existsSync(d)) {
      process.stderr.write(`[check-api-contract-fe-be] ❌ 后端 controller 目录不存在: ${d}\n`);
      process.exit(2);
    }
  }

  const { files: feFiles, calls: feCalls }     = await scanFrontend(feApiDir);
  const { files: beFiles, endpoints: beEps }   = await scanBackend(opts.beRoot, BE_CTRL_DIRS);

  // 输入层哨兵（R37 基线）
  const feNonTest = feFiles.filter(f => !basename(f).endsWith('.test.ts')).length;
  if (feFiles.length < 56) {
    process.stderr.write(`[check-api-contract-fe-be] ❌ 前端 .ts 文件 ${feFiles.length} < 56（R37 基线，扫描路径错位？）\n`);
    process.exit(2);
  }
  if (beFiles.length < 52) {
    process.stderr.write(`[check-api-contract-fe-be] ❌ 后端 controller ${beFiles.length} < 52（R37 基线 + ruoyi-admin 跨模块，扫描路径错位？）\n`);
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
    const canonical = fePathToCanonical(c.rawPath);
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
  const fieldMismatches = detectFieldMismatches(feCanonicalList, beByCanonical);

  // ---------- 判定 ----------
  const hasP0  = orphanPaths.length > 0;
  const hasP1  = fieldMismatches.length > 0;
  const failByStrict = opts.strict && hasP1;
  const pass = !hasP0 && !failByStrict;

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
    },
    orphan_paths: orphanPaths,
    orphan_endpoints: orphanEndpoints,
    field_mismatches: fieldMismatches,
    summary: {
      orphan_paths_count: orphanPaths.length,
      orphan_endpoints_count: orphanEndpoints.length,
      field_mismatches_count: fieldMismatches.length,
    },
    pass,
    mode: { strict: opts.strict, json: opts.json },
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
    process.stdout.write(`\n-- (c) 字段名错位 (P1${opts.strict ? ',strict 阻断' : ',警告'}) -- ${fieldMismatches.length}\n`);
    for (const m of fieldMismatches) {
      process.stdout.write(`  ${m.canonical}\n    FE vars: [${m.feVars.join(', ')}]  ← ${m.feFile}:${m.feLine}\n    BE vars: [${m.beVars.join(', ')}]  ← ${m.beFile}:${m.beLine}\n`);
    }
    process.stdout.write(`\n${pass ? '✅ PASS' : '❌ FAIL'}  (strict=${opts.strict})\n`);
  }

  if (!pass) process.exit(1);
}

main().catch(err => {
  process.stderr.write(`[check-api-contract-fe-be] ❌ 未捕获异常: ${err.stack || err.message}\n`);
  process.exit(2);
});
