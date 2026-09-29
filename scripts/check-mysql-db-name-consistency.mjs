#!/usr/bin/env node
/**
 * scripts/check-mysql-db-name-consistency.mjs
 * ----------------------------------------------------------------------
 * 编排库名一致性门禁（2026-09-28 门禁失明补齐切片，看板卡 2e380293）。
 * 零外部依赖（仅 node:fs / node:path / node:process），node >= 18。
 *
 * 病根背景：docker-compose-all.yaml 曾出现 MYSQL_DATABASE 孤值
 * ruoyi-ai-agent（与 backend JDBC 库名 ruoyi-ai 不一致），使零 USE 的增量
 * SQL（docs/script/sql/update/kb-partA-ddl-draft-20260928.sql）在空库上
 * ALTER 报 ERROR 1146、initdb 中止（git 修复 commit 0bf8477c）。此类
 * 「多编排 MYSQL_DATABASE 互不一致且与 backend JDBC 库名不一致」此前
 * 无任何门禁覆盖——本脚本补齐。
 *
 * 一致性域（以下取值必须全等，任何孤值/不一致 EXIT=1）：
 *   A. MYSQL_DATABASE 生效值：
 *      - docs/docker 下递归的 docker-compose 点 yml/yaml（跳过隐藏目录）
 *      - 仓根 docker-compose*.{yml,yaml}
 *      - docs/wiki/raw/docker-source/*.md（编排文档同步源）
 *      - 仓根 .env*（MYSQL_DATABASE= 形式，如有）
 *   B. backend 主数据源 JDBC 库名：
 *      - compose 各服务 SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL
 *        （字面 jdbc: 值才入域）
 *      - ruoyi-admin/src/main/resources/application-{dev,prod}.yml 的
 *        master url（支持 ${VAR:default} 剥壳取默认值；注释行跳过）
 *   C. 基线 DDL 建库/会话库：docs/script/sql/ruoyi-ai.sql 的
 *      CREATE DATABASE / USE 库名（基线自带 USE，决定 initdb 实际落库）
 *
 * 对齐性检查（域 D）：
 *   - docs/docker/ruoyi-ai/Dockerfile.mysql 的 initdb COPY 清单 ↔
 *     docs/docker/ruoyi-ai/docker-compose.yaml 的 /docker-entrypoint-initdb.d
 *     挂载清单：目标文件名集合一致、同名目标指向同一源文件、源文件存在。
 *
 * 域外（informational，不判红）：
 *   - .env* 中 IPD_* 前缀注入键（如 IPD_DB_URL）：IPD 生产模板独立部署域，
 *     由根 docker-compose.yml ${IPD_DB_URL} 注入外部托管库，非本仓编排主库；
 *     且示例值含 <db-host> 占位符，非生效值。
 *   - ruoyi-extend/ruoyi-snailjob-server 自带库（snail_job/ry-vue）：
 *     独立组件库，不在本仓主库一致性域。
 *   - 开发库 ipd_dev（.codex/ipd-dev 通道）：与基座库刻意双库，不在本域。
 *
 * 用法：
 *   node scripts/check-mysql-db-name-consistency.mjs [--root <dir>]
 *   （--root 默认 CWD；红绿自证可用 --root 指向临时假仓）
 *
 * 退出码：0 = 一致；1 = 存在不一致/孤值/对齐缺口。
 * 证据输出格式：路径 + 键名（禁行号，供门禁日志稳定引用）。
 * ----------------------------------------------------------------------
 */
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, resolve, dirname, relative, basename } from 'node:path';
import process from 'node:process';

// ---- 参数 ----
function parseArgs() {
  const out = { root: null };
  const it = process.argv.slice(2)[Symbol.iterator]();
  for (const a of it) {
    if (a === '--root') out.root = it.next().value;
    else if (a.startsWith('--root=')) out.root = a.slice('--root='.length);
    else if (a === '-h' || a === '--help') {
      console.log('usage: node scripts/check-mysql-db-name-consistency.mjs [--root <dir>]');
      process.exit(0);
    } else {
      console.error(`ERROR: 未知参数 ${a}`);
      process.exit(2);
    }
  }
  return out;
}
const ROOT = resolve(parseArgs().root ?? process.cwd());
const rel = (p) => relative(ROOT, p);

const fails = [];
const infos = [];
const oks = [];
const fail = (file, key, note) => fails.push({ file, key, note });
const info = (file, key, note) => infos.push({ file, key, note });
const ok = (file, key, note) => oks.push({ file, key, note });

// ---- 文件枚举 ----
function walk(dir, keep, acc = []) {
  let entries;
  try { entries = readdirSync(dir); } catch { return acc; }
  for (const name of entries) {
    if (name.startsWith('.') || name === 'node_modules' || name === 'target') continue;
    const p = join(dir, name);
    let st;
    try { st = statSync(p); } catch { continue; }
    if (st.isDirectory()) walk(p, keep, acc);
    else if (keep(p)) acc.push(p);
  }
  return acc;
}
const isCompose = (p) => /^docker-compose.*\.(yml|yaml)$/.test(basename(p));
const composeFiles = [
  ...walk(join(ROOT, 'docs', 'docker'), isCompose),
  ...readdirSync(ROOT).filter((n) => isCompose(n)).map((n) => join(ROOT, n)),
];
const wikiMds = (() => {
  try {
    return readdirSync(join(ROOT, 'docs', 'wiki', 'raw', 'docker-source'))
      .filter((n) => n.endsWith('.md')).map((n) => join(ROOT, 'docs', 'wiki', 'raw', 'docker-source', n));
  } catch { return []; }
})();
const envExamples = readdirSync(ROOT).filter((n) => n.startsWith('.env')).map((n) => join(ROOT, n));

// ---- 解析工具 ----
const readLines = (p) => readFileSync(p, 'utf-8').split(/\r?\n/);
const stripYamlComment = (v) => v.replace(/\s+#.*$/, '').trim().replace(/^["']|["']$/g, '');
const isCommentLine = (l) => l.trim().startsWith('#');
const JDBC_DB = /jdbc:mysql:\/\/[^/]+\/([^/?\s]+)/;

/** 剥 ${VAR:default} 壳；返回 {kind:'literal'|'ref'|'refdefault', value} */
function unwrapEnvExpr(v) {
  const m = /^\$\{([^:}]+)(?::([\s\S]*))?\}$/.exec(v);
  if (!m) return { kind: 'literal', value: v };
  return m[2] === undefined ? { kind: 'ref', varName: m[1] }
    : { kind: 'refdefault', varName: m[1], value: m[2] };
}

// ---- 域 A：MYSQL_DATABASE 生效值 ----
const domainA = []; // {file, key, db}
function scanMysqlDatabase(p, keyStyle) {
  for (const line of readLines(p)) {
    if (isCommentLine(line)) continue;
    const m = keyStyle === 'env'
      ? /^MYSQL_DATABASE=(\S+)/.exec(line)
      : /^[\s-]*MYSQL_DATABASE\s*:\s*(.+)$/.exec(line);
    if (!m) continue;
    const db = stripYamlComment(m[1]);
    if (!db) continue;
    domainA.push({ file: p, key: 'MYSQL_DATABASE', db });
  }
}
for (const p of composeFiles) scanMysqlDatabase(p, 'yaml');
for (const p of wikiMds) scanMysqlDatabase(p, 'yaml');
for (const p of envExamples) scanMysqlDatabase(p, 'env');

// ---- 域 B：backend 主数据源 JDBC 库名 ----
const domainB = []; // {file, key, db}
const MASTER_URL_KEY = /^[\s-]*(?:SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL|[A-Z0-9_]*MASTER_URL)\s*:\s*(.+)$/;
for (const p of composeFiles) {
  for (const line of readLines(p)) {
    if (isCommentLine(line)) continue;
    const m = MASTER_URL_KEY.exec(line);
    if (!m) continue;
    const raw = stripYamlComment(m[1]);
    const u = unwrapEnvExpr(raw);
    if (u.kind === 'literal') {
      const d = JDBC_DB.exec(raw);
      if (d) domainB.push({ file: p, key: 'MASTER_URL', db: d[1] });
    } else if (u.varName.startsWith('IPD_')) {
      info(rel(p), u.varName, 'IPD 生产模板独立部署域（外部托管库注入，非本仓编排主库），不参与一致性比对');
    }
  }
}
for (const prof of ['application-dev.yml', 'application-prod.yml']) {
  const p = join(ROOT, 'ruoyi-admin', 'src', 'main', 'resources', prof);
  if (!existsSync(p)) continue;
  for (const line of readLines(p)) {
    if (isCommentLine(line)) continue;
    const m = /^\s*url\s*:\s*(.+)$/.exec(line);
    if (!m) continue;
    const raw = stripYamlComment(m[1]);
    if (!raw.startsWith('jdbc:mysql') && !raw.startsWith('${')) continue;
    const u = unwrapEnvExpr(raw);
    if (u.kind === 'literal') {
      const d = JDBC_DB.exec(raw);
      if (d) domainB.push({ file: p, key: `datasource.master.url`, db: d[1] });
    } else if (u.kind === 'refdefault') {
      const d = JDBC_DB.exec(u.value);
      if (d) domainB.push({ file: p, key: `datasource.master.url(${u.varName} 默认值)`, db: d[1] });
    } else if (u.varName.startsWith('IPD_')) {
      info(rel(p), u.varName, 'IPD 独立部署域注入键，不参与一致性比对');
    }
  }
}

// ---- 基线 DDL 建库/会话库（供主库存在性核验）----
// 语义：基线 docs/script/sql/ruoyi-ai.sql 是多库拼合文件（主库 + 内嵌组件库
// 如 snail_job），不要求基线所有库一致；只要求「主库 X」在基线有
// CREATE DATABASE 与 USE（保证 initdb 会话落库、增量 SQL 有库可 ALTER）。
const baselineCreates = new Set();
const baselineUses = new Set();
const baselineSql = join(ROOT, 'docs', 'script', 'sql', 'ruoyi-ai.sql');
if (existsSync(baselineSql)) {
  const text = readFileSync(baselineSql, 'utf-8');
  for (const m of text.matchAll(/CREATE DATABASE (?:IF NOT EXISTS )?`?([\w-]+)`?/g)) {
    baselineCreates.add(m[1]);
  }
  for (const line of text.split(/\r?\n/)) {
    if (isCommentLine(line)) continue;
    const m = /^\s*USE\s+`?([\w-]+)`?\s*;/.exec(line);
    if (m) baselineUses.add(m[1]);
  }
} else {
  fail('docs/script/sql/ruoyi-ai.sql', 'baseline', '基线 DDL 文件不存在，无法校验建库一致性');
}

// ---- 域 D：Dockerfile.mysql initdb COPY ↔ compose 挂载对齐 ----
function checkInitdbAlignment() {
  const dockerfile = join(ROOT, 'docs', 'docker', 'ruoyi-ai', 'Dockerfile.mysql');
  if (!existsSync(dockerfile)) {
    info('docs/docker/ruoyi-ai/Dockerfile.mysql', 'initdb', '文件不存在（镜像内置 initdb 场景），跳过对齐检查');
    return;
  }
  const copies = new Map(); // dstName -> absSrc（构建上下文 = 仓根）
  for (const line of readLines(dockerfile)) {
    const m = /^COPY\s+(\S+)\s+\/docker-entrypoint-initdb\.d\/(\S+)\s*$/.exec(line.trim());
    if (m) copies.set(m[2], resolve(ROOT, m[1]));
  }
  const mounts = new Map(); // dstName -> absSrc（相对 compose 文件目录）
  for (const p of composeFiles) {
    for (const line of readLines(p)) {
      if (isCommentLine(line)) continue;
      const m = /^\s*-\s*(\S+):\/docker-entrypoint-initdb\.d\/(\S+)$/.exec(line);
      if (m) mounts.set(m[2].replace(/:[a-z,]+$/, ''), resolve(dirname(p), m[1]));
    }
  }
  if (copies.size === 0 && mounts.size === 0) {
    info(rel(dockerfile), 'initdb', '双方均无 initdb 清单，跳过');
    return;
  }
  const onlyCopy = [...copies.keys()].filter((k) => !mounts.has(k));
  const onlyMount = [...mounts.keys()].filter((k) => !copies.has(k));
  if (onlyCopy.length) fail(rel(dockerfile), 'initdb-copy', `Dockerfile COPY 有而 compose 挂载无: ${onlyCopy.join(', ')}`);
  if (onlyMount.length) fail(rel(dockerfile), 'initdb-mount', `compose 挂载有而 Dockerfile COPY 无: ${onlyMount.join(', ')}`);
  for (const [dst, src] of copies) {
    if (!existsSync(src)) fail(rel(dockerfile), `COPY ${dst}`, `源文件不存在: ${rel(src)}`);
    else if (mounts.has(dst) && mounts.get(dst) !== src) {
      fail(rel(dockerfile), `initdb ${dst}`, `Dockerfile COPY 与 compose 挂载源不一致: ${rel(src)} vs ${rel(mounts.get(dst))}`);
    } else if (mounts.has(dst)) {
      ok(rel(dockerfile), `initdb ${dst}`, `COPY 与挂载同源对齐: ${rel(src)}`);
    }
  }
  for (const [dst, src] of mounts) {
    if (!existsSync(src)) fail('compose-mount', `initdb ${dst}`, `源文件不存在: ${rel(src)}`);
  }
}
checkInitdbAlignment();

// ---- 域外固定披露 ----
info('ruoyi-extend/ruoyi-snailjob-server', 'datasource.url', '独立组件库（snail_job/ry-vue），不在主库一致性域');
info('.codex/ipd-dev', 'mysql-client 通道', '开发库 ipd_dev 与基座库刻意双库，不在主库一致性域');

// ---- 汇总比对 ----
const all = [
  ...domainA.map((e) => ({ ...e, domain: 'MYSQL_DATABASE' })),
  ...domainB.map((e) => ({ ...e, domain: 'backend-JDBC' })),
];
const dbs = [...new Set(all.map((e) => e.db))];

console.log(`▶ mysql-db-name-consistency: root=${ROOT}`);
console.log(`▶ 扫描: compose ${composeFiles.length} 个 / wiki-md ${wikiMds.length} 个 / env 示例 ${envExamples.length} 个`);
console.log(`▶ 一致性域取值点 ${all.length} 个，去重库名: [${dbs.join(', ')}]`);
for (const e of all) {
  const mark = dbs.length > 1 ? '✗' : '✓';
  console.log(`  ${mark} [${e.domain}] ${rel(e.file)} :: ${e.key} = ${e.db}`);
}

if (dbs.length === 0) {
  fail('(scan)', 'MYSQL_DATABASE/JDBC', 'MYSQL_DATABASE 与 backend-JDBC 两域均未取到任何库名——扫描失效，拒绝空洞通过');
} else if (dbs.length > 1) {
  const holders = dbs.map((d) => `${d} <- ${all.filter((e) => e.db === d).map((e) => `${rel(e.file)}#${e.key}`).join(' | ')}`);
  fail('(consistency)', 'db-name', `编排/JDBC 库名不一致（${dbs.length} 个值）:\n    ${holders.join('\n    ')}`);
} else {
  const X = dbs[0];
  ok('(consistency)', 'db-name', `编排/JDBC 全部一致: ${X}`);
  if (!baselineCreates.has(X)) {
    fail(rel(baselineSql), 'CREATE DATABASE', `主库 ${X} 在基线无建库语句（CREATE DATABASE 现有: [${[...baselineCreates].join(', ')}]）——initdb 增量 SQL 将无库可落`);
  } else if (!baselineUses.has(X)) {
    fail(rel(baselineSql), 'USE', `主库 ${X} 在基线有 CREATE DATABASE 但无 USE——initdb 会话不落主库，零 USE 增量 SQL 将 ERROR 1146`);
  } else {
    ok(rel(baselineSql), 'CREATE DATABASE+USE', `基线为主库 ${X} 建库并 USE（内嵌组件库 [${[...baselineCreates].filter((d) => d !== X).join(', ') || '无'}] 属域外）`);
  }
}

for (const i of infos) console.log(`  · [INFO] ${i.file} :: ${i.key} — ${i.note}`);
if (oks.length) for (const o of oks) console.log(`  ✓ [OK] ${o.file} :: ${o.key} — ${o.note}`);

if (fails.length) {
  console.log(`\n⛔ 检测到 ${fails.length} 处不一致/缺口:`);
  for (const f of fails) console.log(`  ✗ ${f.file} :: ${f.key}\n    ${f.note}`);
  console.log('\n修复指引：MYSQL_DATABASE 必须与 backend master JDBC 库名、基线 CREATE DATABASE/USE 库名');
  console.log('保持同一值（现约 ruoyi-ai）；孤值会让零 USE 增量 SQL 在空库 ERROR 1146（见 git 0bf8477c）。');
  process.exit(1);
}
console.log(`\n✅ mysql-db-name-consistency 通过：${all.length} 个取值点全部一致${dbs[0] ? `（${dbs[0]}）` : ''}`);
process.exit(0);
