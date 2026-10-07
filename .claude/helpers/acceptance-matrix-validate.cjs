#!/usr/bin/env node
/**
 * acceptance-matrix-validate.cjs
 * 双向校验 acceptance-matrix.json：
 *   正向：matrix 中的 unitTestClass 在 src/test/java 下能找到对应 .java 文件
 *         matrix 中的 docRef 在 docs/ 下能找到对应 .md 文件
 *         **docRef 指向的文件正文里必须真的写着这个 ac_id**（2026-10-07 加，见
 *           checkDocAcContent；原先只 existsSync 判文件在不在，等于「指向验收清单
 *           就当已被验收清单背书」，编号凭空填进 matrix 也永远看不见）
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
// 幽灵 AC（matrix 收录但验收清单正文没有 / 清单正文有但 matrix 没收录）单独成桶，
// 不混进 errors —— 混进去就看不出「这是本次新增的检查」与「原有检查」的区别。
// 但它必须计入退出码，否则又是「报了出来却仍报绿」。
const ghosts = [];

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

/**
 * 幽灵 AC 双向核对（2026-10-07 新增 · N-B 施工单）。
 *
 * 原实现的第 5 条只做 `fs.existsSync(dp)`：确认 docRef 指向的**文件**在，
 * 从不打开它确认 ac_id 这个编号是否真的写在里面。于是 docRef 指向
 * 《IPD系统_验收清单.md》就等于「该 AC 已被验收清单背书」，与清单正文
 * 实际写没写这个编号毫无关系 —— 一个编号完全可以凭空填进 matrix 而校验器
 * 永远看不见。matrix 自称 SSOT，而它对文档的引用是单向不断言的。
 *
 * 两个方向：
 *   正向 ghostForward：matrix 有、docRef 文件正文里搜不到 → 幽灵
 *   反向 ghostReverse：docRef 文件正文里有、matrix 没有 → 漏收录
 *
 * 边界口径：编号后不允许再跟 [0-9A-Za-z-]，否则 AC-INC-15 会「命中」
 * AC-INC-15b —— 差一个字母的编号是两条不同的验收项，不能互相顶替。
 *
 * 阳性对照先行：任何「说某编号不存在」的结论，必须先证明本函数能找回一条
 * 确定存在的编号，否则「找不到」可能只是搜索方法本身坏了（0 与全绿一样危险）。
 */
function checkDocAcContent(matrix) {
  const files = [...new Set((matrix.rows || []).map(r => r.docRef).filter(Boolean))];
  const docText = new Map();
  for (const f of files) {
    const p = path.isAbsolute(f) ? f : path.join(ROOT, f);
    docText.set(f, fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : null);
  }
  return diffDocAcContent(matrix, docText, findToken, log);
}

/**
 * 抽出来的纯函数：给定「matrix 行 + docRef 文件正文」，产出幽灵清单。
 * 单独抽出来是为了能被 --self-test 用构造样例直接调用——真实文件驱动的检查
 * 无法自证，因为它只会在真实数据恰好出错时才红。
 */

/**
 * 搜索方法探针：在**任何「说某编号不存在」的结论之前**先证明 findToken 本身有效。
 *
 * 2026-10-07 返工记：原先这里挑「matrix 第一条非 deprecated 行」当阳性对照。
 * 但单行 matrix 样例里，那条唯一的行往往**就是**待检测的幽灵行，于是对照
 * 必然失败，检查直接 return [] —— 报不出任何东西，却也不报红，形状上跟
 * 「一切正常」完全一样。用真数据当对照还会连带一个问题：真数据恰好全对时，
 * 这条断言恒绿，恒绿的断言证明不了任何事。
 *
 * 现改为对方法本身用合成串断言：有、没有、以及「短编号不能被长编号顶替」
 * 三种情形各自钉死。它与真实数据无关，因此永远有效。
 */
/** 整串命中，且后面不再跟编号字符。 */
function findToken(text, acId) {
  return new RegExp(escapeRe(acId) + '(?![0-9A-Za-z-])').test(text);
}

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function methodProbe(tokenFn) {
  const t = (text, id) => (tokenFn(text, id) === true);
  return {
    能找到: t('| AC-GATE-01 | 一行 |', 'AC-GATE-01'),
    找不到时确实找不到: !t('| AC-GATE-01 | 一行 |', 'AC-GATE-99'),
    短编号不被长编号顶替: !t('| AC-INC-15b | 另一条 |', 'AC-INC-15'),
  };
}

function diffDocAcContent(matrix, docText, tokenFn, logFn) {
  const probe = methodProbe(tokenFn);
  if (!probe.能找到 || !probe.找不到时确实找不到 || !probe.短编号不被长编号顶替) {
    logFn('ERROR', `幽灵 AC 核对的搜索方法自检失败（${JSON.stringify(probe)}）：本检查的「未找到」结论不可采信`);
    return [];
  }
  // 局部收集再返回。不要直接用模块级 ghosts：--self-test 会多次调用本函数，
  // 写全局会让第 N 次的结果包含前 N-1 次的残留（实测断言数被放大成 7）。
  const out = [];

  // 正向：matrix 有、文档正文没有
  for (const r of matrix.rows || []) {
    if (!r.docRef) continue;
    const t = docText.get(r.docRef);
    if (t === null) continue; // 文件不存在已由第 5 条报过，不重复报
    if (!tokenFn(t, r.ac_id)) {
      const g = { direction: 'forward', ac_id: r.ac_id, docRef: r.docRef };
      out.push(g);
      console.error(`[GHOST] 幽灵 AC：${r.docRef} 正文里没有 ${r.ac_id}（matrix ${r.ac_id} 无出处；补正文或删行，二选一）`);
    }
  }

  // 反向：文档正文有、matrix 没有
  const acIds = new Set((matrix.rows || []).map(r => r.ac_id));
  const scanRe = new RegExp(acIdPattern().replace(/^\^/, '').replace(/\$$/, ''), 'g');
  const seen = new Set();
  for (const [f, t] of docText) {
    if (t === null) continue;
    let m;
    while ((m = scanRe.exec(t)) !== null) {
      const ac = m[0];
      if (acIds.has(ac)) continue;
      // 去掉 "AC-XXX" 这种光秃秃的分组标题（schema 正则要求带 -N\d+，此处兜底）
      if (seen.has(f + '::' + ac)) continue;
      seen.add(f + '::' + ac);
      const g = { direction: 'reverse', ac_id: ac, docRef: f };
      out.push(g);
      console.error(`[GHOST] 漏收录 AC：${f} 正文里有 ${ac}，acceptance-matrix.json 未收录（OD-AM-01 待 owner 决策）`);
    }
  }
  return out;
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

/**
 * --self-test：用构造样例证明 diffDocAcContent 在「该红时红、该绿时绿」。
 *
 * 为什么必须有：真实文件驱动的检查只会恰好在真实数据出错时才红，
 * 平时全绿证明不了它还会红。派单里 N-B 一次返工正是因为「口径对但漏一个分支」
 * 这类静默失效（判据写对了却没覆盖所有输入），而当时没有样例能暴露它。
 *
 * 两条断言（派单指定）：
 *   T1 阴性：docRef 指向的正文里**没有**该编号 → 必须被抓成幽灵。
 *   T2 阳性：该编号在自己的 docRef 正文里，**另一份文件里没有** → 必须放过。
 *      这一条正是防返工的那条 —— 它挡住「为了多报而放宽到全文盲搜」，
 *      也挡住「只看主清单、不看各行自己的 docRef」这两种错法。
 *   T3 边界：AC-INC-15 不得靠 AC-INC-15b 顶替过关。
 * 退出码：0 全过，1 有断言失败（门禁自身失效 ≠ 代码有问题）。
 */
function selfTest() {
  // 反向扫描要用 schema 正则；缺文件时给一句能读懂的报错，而不是抛栈。
  // 自测必须能在任意目录跑（变异测试就在 /tmp 副本上跑），不能隐式依赖仓库。
  try {
    acIdPattern();
  } catch (e) {
    console.error(`[ERROR] --self-test 无法启动：读不到 schema（${e.message}）。`
      + '自测不读仓库里的 matrix/doc，只读 schema 正则——请在含 '
      + path.relative(process.cwd(), SCHEMA_PATH) + ' 的工作目录下运行。');
    process.exit(1);
  }
  const cases = [];
  const noop = () => {};
  const run = (name, matrix, docText, want) => {
    const sink = [];
    const logFn = (lvl, m) => sink.push({ lvl, m });
    let got = [];
    const saved = ghosts.splice(0, ghosts.length);
    got = diffDocAcContent(matrix, docText, findToken, logFn) || [];
    const selfCheckFailed = sink.some(s => s.m.includes('自检失败'));
    const pass = selfCheckFailed ? false : (got.length === want);
    cases.push({ name, pass, got, want });
    ghosts.push(...saved);
  };

  // T1 阴性：AC-GATE-99 在 docRef 正文里没有
  run('T1 阴性：docRef 正文没有该编号 → 必须报幽灵',
    { rows: [{ ac_id: 'AC-GATE-99', docRef: 'D1', status: 'partial' },
             { ac_id: 'AC-GATE-01', docRef: 'D1', status: 'partial' }] },
    new Map([['D1', '| AC-GATE-01 | 正常行 |\n']]),
    1);

  // T2 阳性：该编号在自己的 docRef 正文里，另一份文件里没有 → 必须放过。
  // D3 里那条 AC 也必须在 matrix 里，否则反向扫描会正确地把它判成「漏收录」，
  // 混进来就分不清红的是本断言还是那一条。
  run('T2 阳性：编号在自己 docRef 里、另一份文件里没有 → 必须放过',
    { rows: [{ ac_id: 'AC-GATE-15', docRef: 'D2', status: 'partial' },
             { ac_id: 'AC-GATE-01', docRef: 'D3', status: 'partial' }] },
    new Map([['D2', '| AC-GATE-15 | 否决项硬阻断 |\n'], ['D3', '| AC-GATE-01 | 无关 |\n']]),
    0);

  // T3 边界：短编号不得被长编号顶替
  run('T3 边界：AC-INC-15 不得靠 AC-INC-15b 顶替过关',
    { rows: [{ ac_id: 'AC-INC-15', docRef: 'D1', status: 'partial' },
             { ac_id: 'AC-INC-15b', docRef: 'D1', status: 'partial' }] },
    new Map([['D1', '| AC-INC-15b | 另一条验收项 |\n']]),
    1);

  // T4 反向：正文有、matrix 没有 → 必须报漏收录
  run('T4 反向：正文有、matrix 没有 → 必须报漏收录',
    { rows: [{ ac_id: 'AC-GATE-01', docRef: 'D1', status: 'partial' }] },
    new Map([['D1', '| AC-GATE-01 | a |\n| AC-GATE-98 | 变异注入 |\n']]),
    1);

  // T5 方法失效：搜索方法被换成恒假的坏实现时，必须硬错误并放弃报幽灵，
  // 而不是照着坏方法报一堆假幽灵（那会把「工具坏了」说成「数据坏了」）。
  {
    const sink = [];
    const brokenFn = () => false; // 永远找不到任何编号
    diffDocAcContent(
      { rows: [{ ac_id: 'AC-GATE-01', docRef: 'D1', status: 'partial' }] },
      new Map([['D1', '| AC-GATE-01 | 一行 |\n']]),
      brokenFn,
      (lvl, m) => sink.push({ lvl, m })
    );
    const pass = sink.length > 0 && sink[0].lvl === 'ERROR' && sink[0].m.includes('自检失败');
    cases.push({ name: 'T5 方法失效 → 必须硬错误而非照报幽灵', pass, got: sink.length, want: '1 条 ERROR' });
  }

  // T6 接线断言：**在真 main() 路径上**验证幽灵数被并入模块级 ghosts 并影响退出码。
  // 2026-10-07 实测踩到：diffDocAcContent 改成局部收集返回后，main 里仍写着裸调用，
  // 于是全局永远是空数组 —— 检查报 0 条、退出码退回 2，**看起来和修好前一模一样**。
  //
  // 第一版 T6 直接调纯函数、自称能覆盖这条，实测把接线改回裸调用它照样 PASS：
  // 纯函数返回正常，只能证明函数本身没问题，证明不了 main 有没有接住返回值。
  // 这类「测了但没测到点上」的断言比没有断言更糟——它提供的是假安全感。
  // 现改为**子进程跑真实脚本**：造一份含 1 条幽灵的最小 fixture，断言输出里的
  // ghostAC 计数为 1 且退出码非 0。接线一断，两项同时失败。
  {
    const os = require('os');
    const { spawnSync } = require('child_process');
    const fx = fs.mkdtempSync(path.join(os.tmpdir(), 'am-selftest-'));
    let pass = false, got = 'fixture 构造失败';
    try {
      const rel = 'docs/ipd-系统说明/治理/';
      fs.mkdirSync(path.join(fx, '.claude/helpers'), { recursive: true });
      fs.mkdirSync(path.join(fx, rel), { recursive: true });
      fs.copyFileSync(__filename, path.join(fx, '.claude/helpers/acceptance-matrix-validate.cjs'));
      fs.copyFileSync(SCHEMA_PATH, path.join(fx, rel, 'acceptance-matrix.schema.json'));
      const docRel = 'docs/ipd-系统说明/外部资源/D1.md';
      fs.mkdirSync(path.join(fx, 'docs/ipd-系统说明/外部资源'), { recursive: true });
      fs.writeFileSync(path.join(fx, docRel), '| AC-GATE-01 | 一行 |\n');
      fs.writeFileSync(path.join(fx, rel, 'acceptance-matrix.json'), JSON.stringify({
        version: 'fixture', _metadata: { coverage_stats: { covered: 0, partial: 2, blocked: 0, manual: 0, deprecated: 0, total_sample: 2 } },
        rows: [{ ac_id: 'AC-GATE-01', category: 'GATE', docRef: docRel, status: 'partial', linkedCommits: [] },
               { ac_id: 'AC-GATE-99', category: 'GATE', docRef: docRel, status: 'partial', linkedCommits: [] }],
      }));
      // 用 spawnSync 而非 execFileSync：fixture 有幽灵时子进程**本来就该**退出 1，
      // 而 execFileSync 遇非 0 就抛异常 —— 断言「应该红」却因为红而崩在工具层，
      // 读起来像自测失败，实际是自测没跑到判据。spawnSync 把退出码当返回值。
      const r = spawnSync(process.execPath,
        [path.join(fx, '.claude/helpers/acceptance-matrix-validate.cjs')],
        { cwd: fx, encoding: 'utf8' });
      const out = (r.stdout || '') + (r.stderr || '');
      got = `ghostAC=${(out.match(/ghostAC:\s*(\d+)/) || [, '?'])[1]} exit=${r.status}`;
      pass = got === 'ghostAC=1 exit=1';
    } catch (e) {
      got = '子进程失败: ' + String(e.message || '').split('\n')[0];
      pass = false;
    } finally {
      fs.rmSync(fx, { recursive: true, force: true });
    }
    cases.push({ name: 'T6 接线：真实 main() 路径必须报出幽灵并影响退出码', pass, got, want: '1' });
  }

  console.log('=== 幽灵 AC 核对自测（--self-test）===');
  for (const c of cases) {
    // got 可能是数组也可能是字符串（子进程那两条），别一律取 .length ——
    // 对字符串取长度会把 "ghostAC=0 exit=2" 显示成 "16"，读的人完全看不懂哪错了。
    const gotText = Array.isArray(c.got) ? `${c.got.length} 条` : String(c.got);
    console.log(`  ${c.pass ? 'PASS' : 'FAIL'}  ${c.name}` + (c.pass ? '' : `（期望 ${c.want}，实得 ${gotText}）`));
  }
  const failed = cases.filter(c => !c.pass).length;
  console.log(`  结果：${cases.length - failed}/${cases.length} 通过`);
  process.exit(failed > 0 ? 1 : 0);
}

function main() {
  if (process.argv.includes('--self-test')) { selfTest(); return; }
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
  // 必须接住返回值：diffDocAcContent 改为局部收集后返回（让 --self-test 能多次调用），
  // 若这里仍写成裸调用，模块级 ghosts 会永远是空数组 → 检查静默失效、退出码回到 2。
  // 这是本轮实测踩到的坑，不是假想。
  ghosts.push(...checkDocAcContent(matrix));

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
  console.log(`  ghostAC:     ${ghosts.length}（正向 ${ghosts.filter(g => g.direction === 'forward').length}`
    + ` / 反向 ${ghosts.filter(g => g.direction === 'reverse').length}）`);

  // 幽灵 AC 必须能红：只要有幽灵就退出 1。它单独成桶只为输出可读，不为放行。
  if (errors.length > 0 || ghosts.length > 0) exitCode = 1;
  else if (warnings.length > 0) exitCode = 2;
  process.exit(exitCode);
}

main();
