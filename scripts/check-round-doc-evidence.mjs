#!/usr/bin/env node
/**
 * scripts/check-round-doc-evidence.mjs — 轮次文档 ↔ 证据文件一致性门禁（R212-根因，2026-09-24）
 *
 * 根除对象（R212 偏差复盘，详见 docs/ipd-系统说明/事实源五必现查规约-20260908.md §7）：
 *   RC-1 文档数字来自记忆/旧快照而非证据文件（草稿「孤儿 115→98」，证据实为 105→98）
 *   RC-2 结论不绑定证据路径与采集时间（HEAD「过时」实为采集时点正确、被自身提交推过）
 *   RC-3 同轮次同主题多个非指针 md（R212 V2 / 非 V2 双事实源）
 *   RC-5 「全部补齐 / 100%」完成断言无判定规则
 *
 * 约定（写在轮次主文里）：
 *   <!-- round-evidence
 *   {"round":"R212","claims":[
 *     {"key":"traverse_ok","value":62,"file":"验收/R212-可见性-20260924/traverse-pinned-20260924.json",
 *      "json":"summary.ok","at":{"json":"meta.collectedAt"}},
 *     {"key":"be_head","value":"7868a26a","file":"验收/.../verify-baseline-20260924-rerun.txt",
 *      "kv":"BE_HEAD","at":{"kv":"R212_VERIFY_AT"}}
 *   ]}
 *   -->
 *   正文引用：<!-- ev:traverse_ok -->**62**  （标记后第一个 token 必须等于 claim.value；每个 claim 至少被引用一次）
 *   - file 相对文档所在目录；json 为点路径（数组可用 .length）；kv 取 KEY=VALUE 文本首个匹配
 *   - at 必填（可带 file 指向另一证据文件），值须为 ISO 时间；HEAD 类只比对「证据记录值」，不要求等于当前 HEAD
 *   - 指针文件：首行后含 <!-- round-doc: pointer --> 或 ≤15 行且含「指针」
 *
 * 检查项（round ≥ --min-round 为 FAIL，更早轮次只计 WARN，避免历史存量一次性炸红）：
 *   EVIDENCE_*   证据块 claim 与证据文件不一致 / 文件缺失 / 无采集时间戳
 *   INLINE_*     正文 ev 标记与 claim 不一致 / 引用未知 key / claim 未被正文引用
 *   ROUND_NO_EVIDENCE_BLOCK  该轮次没有任何带证据块的主文
 *   DUP_TOPIC    同目录同轮次号、主题相似（bigram Jaccard ≥ 0.6）的多个非指针 md
 *   COMPLETION_NO_EVIDENCE   「全部补齐/全部完成/100%」等断言所在段落未引用证据路径
 *
 * 用法：
 *   node scripts/check-round-doc-evidence.mjs                       # 扫 docs/ipd-系统说明/
 *   node scripts/check-round-doc-evidence.mjs --file X.md [--base-dir 目录]   # 单文件（base-dir 供 /tmp 副本解析证据相对路径）
 *   ROUNDDOC_FAIL_SEED=1 node scripts/check-round-doc-evidence.mjs  # 真注入自证能红：篡改副本首个 claim，检出 → exit 1；未检出 → exit 3
 * 退出码：0 PASS；1 FAIL；2 参数/脚本错误；3 自证失败（门禁失效）
 */
import { readFileSync, readdirSync, existsSync, writeFileSync, mkdtempSync } from 'node:fs';
import { join, dirname, resolve, basename } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const opt = (name, dflt) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : dflt; };
const DIR = resolve(REPO, opt('--dir', 'docs/ipd-系统说明'));
const FILE = opt('--file', null);
const BASE_DIR = opt('--base-dir', null);
const MIN_ROUND = Number(opt('--min-round', '212'));
const JSON_OUT = args.includes('--json');

const BLOCK_RE = /<!--\s*round-evidence\s*([\s\S]*?)-->/;
const INLINE_RE = /<!--\s*ev:([\w.-]+)\s*-->\s*[*`]*\s*([^\s*`|，,；;。）)（(]+)/g;
const COMPLETION_RE = /全部补齐|全部完成|全部闭环|全量完成|全部通过|全绿|100\s*%/;
const QUOTED_OR_NEGATED_RE = /「[^」]*(全部补齐|全部完成|全部闭环|全量完成|全部通过|全绿|100\s*%)[^」]*」|不得|禁止|否决|部分成立|并非|不是/;
const EVIDENCE_REF_RE = /验收\/|\.json|\.txt|\.log|<!--\s*ev:/;

/** 从文件名解析轮次：R212-xxx → {key:'R212', num:212}；R211c-xxx → {key:'R211c', num:211} */
function parseRound(name) {
  const m = name.match(/^R(\d+)([a-z]?)[-_]/);
  return m ? { key: `R${m[1]}${m[2]}`, num: Number(m[1]) } : null;
}

/** 指针文件判定：显式标记，或 ≤15 行且自称指针 */
function isPointer(text) {
  if (/<!--\s*round-doc:\s*pointer\s*-->/.test(text)) return true;
  return text.split('\n').length <= 15 && /指针/.test(text);
}

/** 主题归一：去轮次号、日期、版本号与常见虚词，用于同主题判定 */
function topicOf(name) {
  return name.replace(/\.md$/, '').replace(/^R\d+[a-z]?[-_]/, '').replace(/[-_]?20\d{6}$/, '')
    .replace(/V\d+/gi, '').replace(/项目|与|和|的|[-_\s]/g, '');
}

function bigramJaccard(a, b) {
  const grams = (s) => new Set(Array.from({ length: Math.max(s.length - 1, 0) }, (_, i) => s.slice(i, i + 2)));
  const A = grams(a); const B = grams(b);
  if (!A.size || !B.size) return 0;
  let inter = 0; for (const g of A) if (B.has(g)) inter++;
  return inter / (A.size + B.size - inter);
}

function getJsonPath(obj, path) {
  return path.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

function getKv(text, key) {
  const m = text.match(new RegExp(`(?:^|\\s)${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}=(\\S+)`, 'm'));
  return m ? m[1] : undefined;
}

/** 按 json / kv 规格从证据文件取值；返回 {value} 或 {error} */
function extract(baseDir, file, spec) {
  const p = resolve(baseDir, file);
  if (!existsSync(p)) return { error: `证据文件不存在: ${file}` };
  const text = readFileSync(p, 'utf8');
  if (spec.json) {
    let obj; try { obj = JSON.parse(text); } catch { return { error: `非合法 JSON: ${file}` }; }
    const v = getJsonPath(obj, spec.json);
    return v === undefined ? { error: `JSON 路径不存在: ${file}#${spec.json}` } : { value: v };
  }
  if (spec.kv) {
    const v = getKv(text, spec.kv);
    return v === undefined ? { error: `KV 键不存在: ${file}#${spec.kv}` } : { value: v };
  }
  return { error: 'claim 缺 json/kv 取值规格' };
}

/** 去掉代码块与证据块，按空行/表格行切段 */
function paragraphs(text) {
  const body = text.replace(/```[\s\S]*?```/g, '').replace(BLOCK_RE, '');
  const out = [];
  for (const chunk of body.split(/\n\s*\n/)) {
    if (/^\s*\|/m.test(chunk)) out.push(...chunk.split('\n').filter((l) => l.trim()));
    else out.push(chunk);
  }
  return out;
}

/** 单文件校验：证据块 / 正文引用 / 完成断言 */
function checkFile(file, text, { baseDir, strict }) {
  const v = [];
  const push = (code, detail) => v.push({ level: strict ? 'FAIL' : 'WARN', code, file, detail });
  const bm = text.match(BLOCK_RE);
  const claims = new Map();
  if (bm) {
    let block;
    try { block = JSON.parse(bm[1]); } catch (e) { push('EVIDENCE_BLOCK_INVALID', `证据块非合法 JSON: ${e.message}`); block = { claims: [] }; }
    for (const c of block.claims || []) {
      if (!c.key || c.value === undefined || !c.file) { push('EVIDENCE_CLAIM_INVALID', JSON.stringify(c)); continue; }
      claims.set(c.key, c);
      const got = extract(baseDir, c.file, c);
      if (got.error) push('EVIDENCE_FILE_MISSING', `${c.key}: ${got.error}`);
      else if (String(got.value) !== String(c.value)) push('EVIDENCE_MISMATCH', `${c.key}: 文档=${c.value} 证据=${got.value} (${c.file})`);
      if (!c.at || !(c.at.json || c.at.kv)) { push('EVIDENCE_NO_TIMESTAMP', `${c.key}: 缺 at 采集时间规格`); continue; }
      const at = extract(baseDir, c.at.file || c.file, c.at);
      if (at.error) push('EVIDENCE_NO_TIMESTAMP', `${c.key}: ${at.error}`);
      else if (!/T\d{2}:\d{2}/.test(String(at.value)) || Number.isNaN(Date.parse(at.value))) push('EVIDENCE_NO_TIMESTAMP', `${c.key}: 采集时间非 ISO: ${at.value}`);
    }
    const referenced = new Set();
    for (const m of text.replace(BLOCK_RE, '').matchAll(INLINE_RE)) {
      const c = claims.get(m[1]);
      if (!c) { push('INLINE_UNKNOWN_KEY', `正文 ev:${m[1]} 不在证据块`); continue; }
      referenced.add(m[1]);
      if (m[2] !== String(c.value)) push('INLINE_MISMATCH', `ev:${m[1]} 正文=${m[2]} 证据块=${c.value}`);
    }
    for (const k of claims.keys()) if (!referenced.has(k)) push('CLAIM_UNBOUND', `claim ${k} 未被正文 <!-- ev:${k} --> 引用`);
  }
  for (const p of paragraphs(text)) {
    if (!COMPLETION_RE.test(p) || QUOTED_OR_NEGATED_RE.test(p) || EVIDENCE_REF_RE.test(p)) continue;
    push('COMPLETION_NO_EVIDENCE', `完成断言未引用证据：${p.trim().slice(0, 80)}`);
  }
  return { violations: v, hasBlock: Boolean(bm), claimCount: claims.size };
}

/** 目录级校验：每个 ≥MIN_ROUND 轮次须有带证据块主文；同轮同主题非指针 md 不得多份 */
function checkDir(dir) {
  const all = [];
  const byRound = new Map();
  for (const name of readdirSync(dir).filter((n) => n.endsWith('.md'))) {
    const r = parseRound(name); if (!r) continue;
    const text = readFileSync(join(dir, name), 'utf8');
    const strict = r.num >= MIN_ROUND;
    const pointer = isPointer(text);
    const res = pointer ? { violations: [], hasBlock: false } : checkFile(name, text, { baseDir: dir, strict });
    // 更早轮次只对带证据块的文件做证据核对，完成断言存量不计，避免噪声
    all.push(...(strict || res.hasBlock ? res.violations : []));
    if (!byRound.has(r.key)) byRound.set(r.key, { num: r.num, docs: [] });
    byRound.get(r.key).docs.push({ name, pointer, hasBlock: res.hasBlock });
  }
  let legacyDup = 0;
  for (const [key, { num, docs }] of byRound) {
    const strict = num >= MIN_ROUND;
    const real = docs.filter((d) => !d.pointer);
    if (strict && real.length && !real.some((d) => d.hasBlock)) {
      all.push({ level: 'FAIL', code: 'ROUND_NO_EVIDENCE_BLOCK', file: key, detail: `${key} 无任何带 round-evidence 证据块的主文` });
    }
    for (let i = 0; i < real.length; i++) for (let j = i + 1; j < real.length; j++) {
      const s = bigramJaccard(topicOf(real[i].name), topicOf(real[j].name));
      if (s < 0.6) continue;
      if (strict) all.push({ level: 'FAIL', code: 'DUP_TOPIC', file: key, detail: `${real[i].name} ≈ ${real[j].name} (jaccard=${s.toFixed(2)})；应保留 1 份 canonical，其余改指针` });
      else legacyDup++;
    }
  }
  return { violations: all, rounds: byRound.size, legacyDup };
}

function report(violations, extra = {}) {
  const fails = violations.filter((x) => x.level === 'FAIL');
  const warns = violations.filter((x) => x.level === 'WARN');
  if (JSON_OUT) console.log(JSON.stringify({ check: 'round-doc-evidence', minRound: MIN_ROUND, fails: fails.length, warns: warns.length, violations, ...extra }, null, 1));
  else {
    for (const x of violations) console.log(`ROUNDDOC|${x.level}|${x.code}|${x.file}|${x.detail}`);
    console.log(`=== 轮次文档证据门禁：FAIL=${fails.length} WARN=${warns.length} ${Object.entries(extra).map(([k, v]) => `${k}=${v}`).join(' ')}`);
    console.log(fails.length ? '[FAIL] 轮次文档与证据不一致' : '[PASS] 轮次文档与证据一致');
  }
  return fails.length ? 1 : 0;
}

// === 自证能红（真注入，不是硬编码 exit 1）===
if (process.env.ROUNDDOC_FAIL_SEED === '1') {
  const target = FILE ? resolve(FILE) : readdirSync(DIR).filter((n) => parseRound(n) && n.endsWith('.md'))
    .map((n) => join(DIR, n)).find((p) => BLOCK_RE.test(readFileSync(p, 'utf8')));
  if (!target) { console.error('[SEED] 找不到带证据块的文档'); process.exit(2); }
  const text = readFileSync(target, 'utf8');
  const block = JSON.parse(text.match(BLOCK_RE)[1]);
  const c0 = block.claims[0];
  const forged = typeof c0.value === 'number' ? c0.value + 1 : `${c0.value}x`;
  block.claims[0] = { ...c0, value: forged };
  const seeded = text.replace(BLOCK_RE, `<!-- round-evidence\n${JSON.stringify(block)}\n-->`);
  const tmp = join(mkdtempSync(join(tmpdir(), 'rounddoc-seed-')), basename(target));
  writeFileSync(tmp, seeded);
  const { violations } = checkFile(basename(target), seeded, { baseDir: dirname(target), strict: true });
  const hit = violations.some((x) => x.code === 'EVIDENCE_MISMATCH' && x.detail.startsWith(`${c0.key}:`));
  console.log(`[SEED] 篡改 ${c0.key}: ${c0.value} → ${forged}（副本 ${tmp}）`);
  if (!hit) { console.log('[SEED] 门禁未检出篡改 → 门禁失效'); process.exit(3); }
  for (const x of violations) console.log(`ROUNDDOC|SEED|${x.code}|${x.file}|${x.detail}`);
  console.log('[FAIL] ROUNDDOC_FAIL_SEED=1 真注入被检出（自证能红）');
  process.exit(1);
}

try {
  if (FILE) {
    const p = resolve(FILE);
    const r = checkFile(basename(p), readFileSync(p, 'utf8'), { baseDir: BASE_DIR ? resolve(BASE_DIR) : dirname(p), strict: true });
    if (!r.hasBlock) r.violations.push({ level: 'FAIL', code: 'ROUND_NO_EVIDENCE_BLOCK', file: basename(p), detail: '单文件模式要求证据块' });
    process.exit(report(r.violations, { claims: r.claimCount }));
  }
  const r = checkDir(DIR);
  process.exit(report(r.violations, { rounds: r.rounds, legacyDupWarn: r.legacyDup }));
} catch (e) {
  console.error(`[ERROR] ${e.stack || e}`);
  process.exit(2);
}
