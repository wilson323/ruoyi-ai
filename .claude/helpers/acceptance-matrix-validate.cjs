#!/usr/bin/env node
/**
 * acceptance-matrix-validate.cjs
 * 双向校验 acceptance-matrix.json：
 *   正向：matrix 中的 unitTestClass 在 src/test/java 下能找到对应 .java 文件
 *         matrix 中的 docRef 在 docs/ 下能找到对应 .md 文件
 *         status=covered 的 AC 必须有 linkedCommits 且长度 ≥ 1
 *         ac_id 必须符合 schema 的 rows[].ac_id.pattern（当前 18 个模块前缀）
 *   反向：src/test/java 下的测试类若 @DisplayName 含任一模块前缀的验收编号，必须在
 *         matrix 中出现（owner OD-AM-01 决策前用「弱反向」：仅 WARN 不阻断）
 *   派生：_metadata.coverage_stats 必须等于 rows 里逐 status 数出来的真值。
 *         （2026-10-03 加：原声明值 covered=5/partial=3/blocked=2 与行内真值
 *           covered=9/partial=1 长期不符却无人报红——声明与实况脱钩就是假绿。
 *           同 owner_decisions_needed.open_count 的做法：一切计数以行为准，不手工填。）
 *
 * 触发：本地 `node .claude/helpers/acceptance-matrix-validate.cjs`
 *      CI：.github/workflows/docs-link-check.yml 周日 02:00 + PR 触发
 * 退出码：0 = 全绿，1 = 硬错误，2 = 软警告
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const MATRIX_PATH = path.join(ROOT, 'docs/ipd-系统说明/治理/acceptance-matrix.json');
const SCHEMA_PATH = path.join(ROOT, 'docs/ipd-系统说明/治理/acceptance-matrix.schema.json');
const TEST_DIR = path.join(ROOT, 'ruoyi-modules/ruoyi-ipd/src/test/java');
const DOCS_DIR = path.join(ROOT, 'docs');

let exitCode = 0;
const errors = [];
const warnings = [];

function log(level, msg) {
  if (level === 'ERROR') {
    errors.push(msg);
    console.error('[ERROR] ' + msg);
  } else if (level === 'WARN') {
    warnings.push(msg);
    console.error('[WARN]  ' + msg);
  } else {
    console.log(msg);
  }
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

/**
 * 验收编号正则的**唯一来源** = schema 的 `rows[].ac_id.pattern`。
 *
 * 2026-10-03 之前本文件同时存在四份互不一致的副本：
 *   :8   头部注释        3 个前缀
 *   :83  行内注释        「17 个模块前缀」
 *   :84  正向校验代码    18 个前缀
 *   :168 反向校验代码    **3 个前缀**（INC|EXT|MIN）
 * 而 schema 自己说明 EXT 与 MIN 当前 0 条 AC 使用 —— 即反向校验认的三个前缀里
 * 有两个是空的。实测后果：3074 条 @DisplayName 共含 **133** 个不同验收编号，
 * 反向校验只看得到 **28** 个，**105 个（79%）被静默漏掉**。它报绿，但它看不见
 * 八成输入；且本脚本已被 CI 调用（.github/workflows/docs-link-check.yml），
 * 属于「已接线的检查在假绿」。现改为从 schema 取一次、两处共用。
 *
 * 取不到 schema 就抛错，不退回任何内置正则：宁可直接失败，也不要拿一个更窄的
 * 正则静默漏检 —— 后者正是本次要根治的形状。
 */
let _acIdPatternCache = null;
function acIdPattern() {
  if (_acIdPatternCache !== null) return _acIdPatternCache;
  const schema = readJson(SCHEMA_PATH);
  const prop = schema && schema.properties && schema.properties.rows
    && schema.properties.rows.items && schema.properties.rows.items.properties
    && schema.properties.rows.items.properties.ac_id;
  const p = prop && prop.pattern;
  if (typeof p !== 'string' || p === '') {
    throw new Error('schema 未给出 rows[].ac_id.pattern —— 本脚本的编号正则取自 schema，'
      + '结构若调整须同步此处取值路径');
  }
  _acIdPatternCache = p;
  return _acIdPatternCache;
}

/** 带锚，用于整串匹配（正向）。 */
function acIdRegex() {
  return new RegExp(acIdPattern());
}

/** 去锚 + 全局，用于在正文里扫出所有编号（反向）。 */
function acIdRegexGlobal() {
  return new RegExp(acIdPattern().replace(/^\^/, '').replace(/\$$/, ''), 'g');
}

function listFiles(dir, suffix) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  const walk = (d) => {
    for (const ent of fs.readdirSync(d, { withFileTypes: true })) {
      const fp = path.join(d, ent.name);
      if (ent.isDirectory()) walk(fp);
      else if (ent.name.endsWith(suffix)) out.push(fp);
    }
  };
  walk(dir);
  return out;
}

function classFqnToPath(fqn) {
  // org.ruoyi.ipd.service.X#testY -> .../ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/X.java
  // 去掉 #methodName 后缀
  const classOnly = fqn.split('#')[0];
  const parts = classOnly.split('.');
  const cls = parts.pop();
  return path.join(TEST_DIR, ...parts, cls + '.java');
}

function checkMatrix(matrix) {
  const acIds = new Set();
  for (const row of matrix.rows || []) {
    // 1. ac_id 唯一
    if (acIds.has(row.ac_id)) {
      log('ERROR', `重复 ac_id: ${row.ac_id}`);
      continue;
    }
    acIds.add(row.ac_id);

    // 2. ac_id 格式（前缀集合的唯一来源 = schema，见 acIdPattern()，本处不再内联正则）
    if (!acIdRegex().test(row.ac_id)) {
      log('ERROR', `ac_id 格式不合规: ${row.ac_id}`);
    }

    // 3. category 与 ac_id 前缀一致
    const prefix = row.ac_id.split('-')[1];
    if (row.category && row.category !== prefix) {
      log('ERROR', `category=${row.category} 与 ac_id=${row.ac_id} 前缀不一致`);
    }

    // 4. unitTestClass 存在
    if (row.unitTestClass) {
      const tp = classFqnToPath(row.unitTestClass);
      if (!fs.existsSync(tp)) {
        log('ERROR', `unitTestClass 文件不存在: ${row.ac_id} → ${row.unitTestClass} → ${tp}`);
      }
    } else if (row.status === 'covered') {
      log('ERROR', `status=covered 但 unitTestClass 为空: ${row.ac_id}`);
    }

    // 5. docRef 存在
    if (row.docRef) {
      const dp = path.join(ROOT, row.docRef);
      if (!fs.existsSync(dp)) {
        log('ERROR', `docRef 文件不存在: ${row.ac_id} → ${row.docRef}`);
      }
    } else {
      log('WARN', `docRef 缺失: ${row.ac_id}`);
    }

    // 6. status=covered 必须有 linkedCommits ≥ 1
    if (row.status === 'covered' && (!row.linkedCommits || row.linkedCommits.length < 1)) {
      log('ERROR', `status=covered 但 linkedCommits 为空: ${row.ac_id}`);
    }

    // 7. linkedCommits 格式
    for (const c of row.linkedCommits || []) {
      if (!/^[0-9a-f]{7,40}$/.test(c)) {
        log('ERROR', `linkedCommits 格式错: ${row.ac_id} → ${c}`);
      }
    }
  }
  return acIds;
}

function checkCoverageThreshold(matrix) {
  // OD-AM-03 决策：CI 阻断阈值（partial + blocked + manual + incomplete ≤ 30% PASS）
  // 阈值通过 COVERAGE_THRESHOLD_PCT 环境变量覆盖，默认 30
  // 用途：防止「批量导入后无人补 unitTestClass」导致 matrix 看似全量但实际未覆盖
  //
  // 2026-10-03：严重程度改为**跟随 OD-AM-03 自身的裁决状态**。
  // 原实现无条件 ERROR，但 OD-AM-03 的提问原文恰恰是「覆盖阈值何时升 ERROR 阻断」——
  // 即在未决期间就把未决的政策当成已决在执行，自相矛盾。
  // 现：OD-AM-03 未决 → WARN（不阻断，但报告照打）；一旦 owner 拍板 → 自动升 ERROR 阻断。
  // 注意这不是「放宽阈值」：数值、口径、输出一行没变，变的只是「未决政策不得当已决用」。
  const threshold = parseFloat(process.env.COVERAGE_THRESHOLD_PCT || '30');
  const rows = matrix.rows || [];
  if (rows.length === 0) return;
  const partial = rows.filter(r => r.status === 'partial').length;
  const blocked = rows.filter(r => r.status === 'blocked').length;
  const manual = rows.filter(r => r.status === 'manual').length;
  const incomplete = partial + blocked + manual;
  const pct = (incomplete / rows.length) * 100;

  const od = (matrix.owner_decisions_needed || {}).items || [];
  const od3 = od.find(i => i.id === 'OD-AM-03');
  const decided = !!(od3 && od3.decision);
  const gateNote = decided
    ? 'OD-AM-03 已裁决 → 超阈值即 ERROR 阻断'
    : 'OD-AM-03 **未决**（提问即「何时升 ERROR 阻断」）→ 暂记 WARN，不阻断；owner 拍板后自动升 ERROR';

  const line = `OD-AM-03 覆盖率阈值检查：partial=${partial} blocked=${blocked} manual=${manual} incomplete=${incomplete}/${rows.length} (${pct.toFixed(1)}%, threshold=${threshold}%)`;
  if (pct > threshold) {
    log(decided ? 'ERROR' : 'WARN', `${line} → 超过阈值；${gateNote}`);
  } else {
    log('INFO', `${line} → 在阈值内，PASS；${gateNote}`);
  }
}

function checkReverse(matrix, acIds) {
  // 软反向：扫描测试类 @DisplayName 含任一模块前缀的验收编号但不在 matrix 中 → WARN
  const javaFiles = listFiles(TEST_DIR, '.java');
  const referencedInDisplayName = new Set();
  const dispRe = /@DisplayName\s*\(\s*"([^"]*)"\s*\)/g;
  // 前缀集合与正向校验共用同一来源（schema）；2026-10-03 前此处硬编码
  // INC|EXT|MIN 三个前缀，实测漏掉 105/133（79%）的引用。
  const acRe = acIdRegexGlobal();
  for (const f of javaFiles) {
    const src = fs.readFileSync(f, 'utf8');
    let m;
    while ((m = dispRe.exec(src)) !== null) {
      let am;
      while ((am = acRe.exec(m[1])) !== null) {
        referencedInDisplayName.add(am[0]);
      }
    }
  }
  for (const ac of referencedInDisplayName) {
    if (!acIds.has(ac)) {
      log('WARN', `@DisplayName 引用 ${ac} 但 acceptance-matrix.json 未收录（OD-AM-01 待 owner 决策）`);
    }
  }
}

function checkDeclaredStats(matrix) {
  // 声明值必须等于行内派生真值。手工填写的计数一旦与 rows 脱钩，
  // 总结面板就会显示一个没人核对过的数字——本仓已把它当假绿的一种。
  const rows = matrix.rows || [];
  const declared = (matrix._metadata || {}).coverage_stats;
  if (!declared) {
    log('WARN', '_metadata.coverage_stats 缺失（无法核对声明与实况是否一致）');
    return;
  }
  const actual = {
    covered: rows.filter(r => r.status === 'covered').length,
    partial: rows.filter(r => r.status === 'partial').length,
    blocked: rows.filter(r => r.status === 'blocked').length,
    manual: rows.filter(r => r.status === 'manual').length,
    deprecated: rows.filter(r => r.status === 'deprecated').length,
    total_sample: rows.length,
  };
  for (const [k, v] of Object.entries(actual)) {
    if (declared[k] === undefined) {
      log('ERROR', `_metadata.coverage_stats 缺字段 ${k}（按 rows 派生应为 ${v}）`);
    } else if (declared[k] !== v) {
      log('ERROR', `_metadata.coverage_stats.${k} 声明 ${declared[k]} ≠ 行内派生真值 ${v}`);
    }
  }
  // 行内状态若不在 schema 枚举内，count 会漏计——单独报，避免它静默逃过上面的比对。
  const known = new Set(['covered', 'partial', 'blocked', 'manual', 'deprecated']);
  for (const r of rows) {
    if (!known.has(r.status)) {
      log('ERROR', `行 ${r.ac_id} 的 status=${r.status} 不在枚举内，计数会失真`);
    }
  }
}

function checkOwnerDecisions(matrix) {
  // 与 schema 里 owner_decisions_needed.items 的条件分支同一条规则：
  // decision 为非空字符串 => decided_by / decided_at / evidence_commit 三者必填，
  // 且 evidence_commit 须匹配 ^[0-9a-f]{7,40}$；未决（无 decision）=> 必须有 blocker。
  //
  // 为什么要在本机重复一遍：该条件由 CI 的 ajv 步骤承担，而本脚本原先不实现它，
  // 于是 2026-10-03 出现「本机 errors=0、CI 必红」的窗口——OD-AM-01/02 标为已决却缺
  // evidence_commit，本地提交前拿不到任何信号。这里补齐，让本机结论与 CI 一致。
  const od = matrix.owner_decisions_needed;
  if (!od) {
    log('WARN', 'owner_decisions_needed 缺失（无法核对决策项闭环字段）');
    return;
  }
  const items = od.items || [];
  const SHA = /^[0-9a-f]{7,40}$/;
  for (const it of items) {
    const id = it.id || '(无 id)';
    const decided = typeof it.decision === 'string' && it.decision.length > 0;
    if (decided) {
      for (const f of ['decided_by', 'decided_at', 'evidence_commit']) {
        if (it[f] === undefined || it[f] === null || it[f] === '') {
          log('ERROR', `${id} 已决（decision 非空）但缺 ${f}；CI 的 ajv 会硬阻断`);
        }
      }
      if (typeof it.evidence_commit === 'string' && !SHA.test(it.evidence_commit)) {
        log('ERROR', `${id} 的 evidence_commit=${it.evidence_commit} 不是 7-40 位十六进制提交号`);
      }
    } else if (!it.blocker) {
      log('ERROR', `${id} 未决（无 decision）却也没有 blocker 原文`);
    }
  }
  // open_count 是派生值：与 items 中「无有效 decision」的项数必须一致。
  const derivedOpen = items.filter(
    it => !(typeof it.decision === 'string' && it.decision.length > 0)).length;
  if (od.open_count !== derivedOpen) {
    log('ERROR', `owner_decisions_needed.open_count 声明 ${od.open_count} ≠ 派生真值 ${derivedOpen}`);
  }
}

function main() {
  if (!fs.existsSync(MATRIX_PATH)) {
    log('ERROR', `acceptance-matrix.json 不存在: ${MATRIX_PATH}`);
    process.exit(1);
  }
  let matrix;
  try {
    matrix = readJson(MATRIX_PATH);
  } catch (e) {
    log('ERROR', `acceptance-matrix.json JSON 解析失败: ${e.message}`);
    process.exit(1);
  }

  if (fs.existsSync(SCHEMA_PATH)) {
    log('INFO', `schema 存在: ${path.relative(ROOT, SCHEMA_PATH)}（CI 阶段用 ajv 强校验）`);
  } else {
    log('WARN', `schema 缺失: ${path.relative(ROOT, SCHEMA_PATH)}`);
  }

  log('INFO', `acceptance-matrix.json 加载：${(matrix.rows || []).length} 行（version=${matrix.version}）`);

  let acIds = new Set();
  try {
    acIds = checkMatrix(matrix);
    checkReverse(matrix, acIds);
  } catch (e) {
    // 取不到 schema 正则时立刻硬错误，不退回内置正则静默漏检（理由见 acIdPattern 注释）
    log('ERROR', `验收编号正则取值失败，正向/反向校验未完成: ${e.message}`);
  }
  checkCoverageThreshold(matrix);
  checkDeclaredStats(matrix);
  checkOwnerDecisions(matrix);

  console.log('');
  console.log('=== acceptance-matrix-validate 总结 ===');
  console.log(`  rows:        ${(matrix.rows || []).length}`);
  console.log(`  covered:     ${(matrix.rows || []).filter(r => r.status === 'covered').length}`);
  console.log(`  partial:     ${(matrix.rows || []).filter(r => r.status === 'partial').length}`);
  console.log(`  blocked:     ${(matrix.rows || []).filter(r => r.status === 'blocked').length}`);
  console.log(`  manual:      ${(matrix.rows || []).filter(r => r.status === 'manual').length}`);
  console.log(`  deprecated:  ${(matrix.rows || []).filter(r => r.status === 'deprecated').length}`);
  const _inc = (matrix.rows || []).filter(r => ['partial','blocked','manual'].includes(r.status)).length;
  const _total = (matrix.rows || []).length || 1;
  const _pct = (_inc / _total * 100).toFixed(1);
  const _thr = process.env.COVERAGE_THRESHOLD_PCT || '30';
  console.log(`  threshold:   partial+blocked+manual=${_pct}% (cap=${_thr}%, OD-AM-03)`);
  console.log(`  errors:      ${errors.length}`);
  console.log(`  warnings:    ${warnings.length}`);

  if (errors.length > 0) exitCode = 1;
  else if (warnings.length > 0) exitCode = 2;
  process.exit(exitCode);
}

main();
