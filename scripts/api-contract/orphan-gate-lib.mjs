#!/usr/bin/env node
/**
 * orphan-gate-lib.mjs — API 契约孤儿「只减不增」棘轮门禁核心库（R212 / 卡 7b76b7cd API-GATE-RATCHET）
 *
 * 设计依据: docs/ipd-系统说明/验收/R212-孤儿跟进-20260924/r212-gate-design.md（owner 2026-09-24 拍板 4 项）
 *   ① exit code 位掩码方案 A：0 通过 / 1 P0 孤儿路径 / 2 环境错 / 4 新孤儿未白名单（可叠加）
 *   ② 存量分诊：真内部/运维口 → 白名单（reason+卡号+expire，六条防伪校验）；其余 → baseline 老账（脚本独占写，棘轮只减不增）
 *   ③ method 维度首版不收紧，维持 canonical path 口径
 *   ④ 本期只做本机 pre-commit（方案A）+ 数据文件保护（方案D）
 *
 * 本库职责（与扫描逻辑完全解耦，靶脚本扫描集原样传入）:
 *   - loadJsonFile            读取 + 基础 schema
 *   - validateWhitelist       §3.2 六条防伪校验（卡号可验/path 真存/evidence 行号可机械校验/过期即失效/反向清账/文案防伪）
 *   - loadBaseline            baseline 加载 + sha256 自洽 + git show HEAD 硬闸（防手工编辑/删基线重生）
 *   - classifyOrphans         分诊: exempt_baseline / exempt_whitelist / violations / stale / expired
 *   - writeBaseline           --update-baseline 唯一合法写入口（count 单调不增，growth_log 记账）
 *
 * exit code 位掩码（方案 A，与靶脚本共用）:
 *   0 = PASS   1 = P0 孤儿路径(或 --strict 阻断)   2 = 环境/输入错   4 = 新孤儿未白名单   可叠加(如 2|4=6)
 */
'use strict';

import { readFile, writeFile, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execSync } from 'node:child_process';
import { basename } from 'node:path';

export const EXIT_BITS = Object.freeze({ PASS: 0, BLOCK: 1, ENV: 2, NEW_ORPHAN: 4 });

// 白名单 reason 防伪黑名单（泛词，设计 §3.2-6）
const REASON_BANLIST = ['内部接口', '待办', 'N/A', 'TODO', 'TBD', '内部使用', '暂缓', '后续处理'];

export class GateEnvError extends Error {
  constructor(msg, { detail = [] } = {}) {
    super(msg);
    this.name = 'GateEnvError';
    this.detail = detail;
  }
}

// ---------- git ----------
export function gitHead(cwd) {
  try {
    return execSync('git rev-parse HEAD', { cwd, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
  } catch {
    return null;
  }
}

/** git show HEAD:<relPath>；不存在/无 git 返回 null（首版未提交场景） */
export function gitShowHeadFile(cwd, relPath) {
  try {
    return execSync(`git show HEAD:${JSON.stringify(relPath)}`, {
      cwd, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch {
    return null;
  }
}

// ---------- 通用 ----------
function sha256OfPaths(paths) {
  return createHash('sha256').update(JSON.stringify(paths)).digest('hex');
}

export function todayStr() {
  return new Date().toISOString().slice(0, 10);
}

export async function loadJsonFile(p, { required = true, label = 'JSON 文件' } = {}) {
  if (!existsSync(p)) {
    if (!required) return null;
    throw new GateEnvError(`${label} 不存在: ${p}`, { detail: ['先运行 --update-baseline 生成 / 或按 schema 创建（见 r212-gate-design.md §3）'] });
  }
  let doc;
  try {
    doc = JSON.parse(await readFile(p, 'utf8'));
  } catch (e) {
    throw new GateEnvError(`${label} JSON 解析失败: ${p} (${e.message})`);
  }
  return doc;
}

// ---------- 白名单 §3.2 六条防伪 ----------
/**
 * @param {object}   doc                白名单 JSON 文档
 * @param {object}   ctx
 * @param {Set}      ctx.beCanonicalSet 后端扫描 canonical path 全集
 * @param {Map}      ctx.beEndpointByCanonical  canonical → {file,line}（evidence 校验基准，扫描器产出）
 * @param {string}   ctx.mirrorText     看板镜像文件全文（卡号防伪第 1 条）
 * @param {string}   ctx.today          YYYY-MM-DD
 * @param {object[]} ctx.beFilesContent basename → 文本行数组（evidence 第 3 条：该行含 Mapping）
 * @returns {{errors:string[], warnings:string[], activeMap:Map, expiredEntries:object[], entriesByCanonical:Map}}
 */
export function validateWhitelist(doc, ctx) {
  const errors = [];
  const warnings = [];
  const activeMap = new Map();        // canonical → entry（expire 未过期）
  const expiredEntries = [];
  const entriesByCanonical = new Map();

  if (!doc || typeof doc !== 'object') {
    return { errors: ['白名单根节点必须是 JSON object'], warnings, activeMap, expiredEntries, entriesByCanonical };
  }
  if (doc.$schema_version !== 1) errors.push(`$schema_version 必须为 1，当前: ${JSON.stringify(doc.$schema_version)}`);
  if (!Array.isArray(doc.entries)) {
    errors.push('entries 必须是数组');
    return { errors, warnings, activeMap, expiredEntries, entriesByCanonical };
  }

  const ninetyDaysAgo = new Date(ctx.today + 'T00:00:00Z');
  ninetyDaysAgo.setUTCDate(ninetyDaysAgo.getUTCDate() - 90);

  const seenPaths = new Set();
  for (let i = 0; i < doc.entries.length; i++) {
    const e = doc.entries[i];
    const tag = `entries[${i}] ${e && e.path ? e.path : '(缺 path)'}`;
    const errBefore = errors.length; // 条目级降级判定基准: 本条防伪失败 ⇒ 豁免完全失效(不进任何豁免 map)
    // --- 基础 schema ---
    if (!e || typeof e !== 'object') { errors.push(`${tag}: 必须是 object`); continue; }
    for (const f of ['path', 'methods', 'reason', 'owner_card', 'review_date', 'expire', 'added_by', 'evidence']) {
      if (e[f] === undefined || e[f] === null || e[f] === '') {
        errors.push(`${tag}: 缺必填字段 ${f}（schema 设计 §3：缺 expire 视为无限期 ⇒ 直接 FAIL）`);
      }
    }
    if (!e.path) continue;
    if (seenPaths.has(e.path)) errors.push(`${tag}: path 重复登记（主键冲突）`);
    seenPaths.add(e.path);

    // --- 防伪 1: 卡号必须可验（看板镜像 | KEY | 形式） ---
    if (e.owner_card && !ctx.mirrorText.includes(`| ${e.owner_card} |`)) {
      errors.push(`${tag}: owner_card "${e.owner_card}" 在看板镜像（开发计划-看板镜像.md）无 "| KEY |" 命中 —— 不给随手编卡号留缝`);
    }
    // --- 防伪 2: path 必须真实存在于后端扫描集 ---
    if (!ctx.beCanonicalSet.has(e.path)) {
      errors.push(`${tag}: path 不存在于后端扫描集（防污染 + 防死条目）`);
    }
    // --- 防伪 3: evidence 可机械校验（File.java:NN，NN ≤ 行数且该行含 Mapping） ---
    if (e.evidence) {
      const m = /^([A-Za-z0-9_]+\.java):(\d+)$/.exec(e.evidence);
      if (!m) {
        errors.push(`${tag}: evidence "${e.evidence}" 不符合 File.java:NN 格式`);
      } else {
        const lines = ctx.beFilesContent.get(m[1]);
        if (!lines) {
          errors.push(`${tag}: evidence 文件 ${m[1]} 不在后端扫描集内`);
        } else {
          const ln = Number(m[2]);
          if (ln < 1 || ln > lines.length) {
            errors.push(`${tag}: evidence 行号 ${ln} 超出 ${m[1]} 总行数 ${lines.length}`);
          } else if (!/Mapping/.test(lines[ln - 1])) {
            errors.push(`${tag}: evidence ${e.evidence} 该行不含 Mapping 注解（行内容: "${lines[ln - 1].trim().slice(0, 60)}"）`);
          }
        }
      }
    }
    // --- 防伪 6: 文案防伪（reason ≥12 字、非泛词；review_date 近 90 天） ---
    if (e.reason !== undefined) {
      if (typeof e.reason !== 'string' || e.reason.length < 12) {
        errors.push(`${tag}: reason 长度 < 12（当前 ${String(e.reason).length}），须写明真实用途`);
      }
      for (const w of REASON_BANLIST) {
        if (typeof e.reason === 'string' && e.reason.includes(w) && e.reason.length - w.length < 6) {
          errors.push(`${tag}: reason 为泛词（含 "${w}" 且无实质内容）`);
        }
      }
    }
    if (e.review_date !== undefined) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(e.review_date)) {
        errors.push(`${tag}: review_date 格式须为 YYYY-MM-DD`);
      } else {
        const d = new Date(e.review_date + 'T00:00:00Z');
        if (Number.isNaN(d.getTime()) || d < ninetyDaysAgo || d > new Date(ctx.today + 'T23:59:59Z')) {
          errors.push(`${tag}: review_date ${e.review_date} 不在近 90 天窗口（${ninetyDaysAgo.toISOString().slice(0, 10)} ~ ${ctx.today}）`);
        }
      }
    }
    // --- 防伪 4: expire 格式 + 过期分流（过期即失效，回落警告，设计 §3.2-4 首版 WARN） ---
    if (e.expire !== undefined) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(e.expire)) {
        errors.push(`${tag}: expire 格式须为 YYYY-MM-DD（缺 expire 已在 schema 段 FAIL）`);
      } else if (e.expire < ctx.today) {
        expiredEntries.push(e);
        warnings.push(`${tag}: 已过期（${e.expire} < ${ctx.today}）→ 豁免失效，对应孤儿回落（首版 WARN，杜绝永久豁免沉积）`);
        if (errors.length === errBefore) entriesByCanonical.set(e.path, e); // 过期: 不进 activeMap,保留归因 map 供 expired_orphans 分类
        continue;
      }
    }
    if (errors.length > errBefore) continue; // 条目级降级: 本条防伪失败 ⇒ 无任何豁免效力(不进 activeMap/entriesByCanonical),对应孤儿将回落 violation(bit4)与文件级 bit2 叠加
    entriesByCanonical.set(e.path, e); // 防伪通过(含未过期): 正常豁免
    activeMap.set(e.path, e);
  }
  return { errors, warnings, activeMap, expiredEntries, entriesByCanonical };
}

// ---------- baseline ----------
/**
 * 加载 + 双层防篡改:
 *   层1 sha256 自洽: 文件内 paths_sha256 与重算不符 → hardFail（拦截「append 一行」式手工编辑）
 *   层2 git HEAD 硬闸: HEAD 有 baseline 时，磁盘相对 HEAD 只减不增；增 ⇒ hardFail（绕过者必须改历史）
 * @returns {{doc, warnings:string[], hardFail:string|null, checkedAgainstHead:boolean}}
 */
export async function loadBaseline(p, { repoRoot, relPath, label = 'baseline' }) {
  const warnings = [];
  const doc = await loadJsonFile(p, { required: true, label });
  let hardFail = null;
  let checkedAgainstHead = false;

  if (!Array.isArray(doc.paths) || typeof doc.count !== 'number') {
    throw new GateEnvError(`${label} schema 错误: 须含 count:number 与 paths:array`);
  }
  if (doc.count !== doc.paths.length) {
    hardFail = `${label}.count(${doc.count}) 与 paths.length(${doc.paths.length}) 不一致`;
  }
  if (typeof doc.paths_sha256 !== 'string') {
    hardFail = hardFail || `${label} 缺 paths_sha256（脚本生成文件必有此字段）`;
  } else if (sha256OfPaths(doc.paths) !== doc.paths_sha256) {
    hardFail = `${label} paths_sha256 自洽校验失败 —— 文件被手工编辑，未通过脚本写入口（--update-baseline）；请还原后用 node scripts/check-api-contract-fe-be.mjs --update-baseline 重新生成`;
  }

  // git show HEAD 硬闸（首版 baseline 未提交时 HEAD 无此文件，跳过；提交后自动生效）
  const headRaw = gitShowHeadFile(repoRoot, relPath);
  if (headRaw !== null) {
    checkedAgainstHead = true;
    try {
      const headDoc = JSON.parse(headRaw);
      const headSet = new Set(headDoc.paths || []);
      const added = (doc.paths || []).filter(x => !headSet.has(x));
      if (added.length > 0) {
        hardFail = hardFail || `git show HEAD 硬闸: baseline 相对 HEAD 新增 ${added.length} 条（${added.slice(0, 3).join(', ')}${added.length > 3 ? ' …' : ''}）—— 只减不增被破坏，未通过脚本写入口`;
      }
      if ((doc.paths || []).length > (headDoc.paths || []).length) {
        hardFail = hardFail || `git show HEAD 硬闸: baseline count ${doc.paths.length} > HEAD ${headDoc.paths.length}（单调不增被破坏）`;
      }
    } catch {
      warnings.push('HEAD baseline 解析失败（HEAD 版本损坏？），硬闸降级为仅 sha256 层');
    }
  } else {
    warnings.push('HEAD 中尚无 baseline（首版未提交）——git 硬闸暂未生效，当前依赖 sha256 自洽层；baseline 提交后硬闸自动生效');
  }
  return { doc, warnings, hardFail, checkedAgainstHead };
}

// ---------- 分诊 ----------
/**
 * @param {object[]} orphanEndpoints 当前实测孤儿（含 method/path/file/line）
 * @param {Set}      baselinePaths   baseline paths
 * @param {Map}      whitelistActive canonical → entry（防伪通过且未过期）
 * @param {Map}      whitelistAll    canonical → entry（含过期，用于 expired 归因）
 */
export function classifyOrphans(orphanEndpoints, baselinePaths, whitelistActive, whitelistAll) {
  const result = {
    exempt_baseline: [],
    exempt_whitelist: [],
    violations: [],
    expired_whitelist: [],
    stale_whitelist: [],
    expired_orphans: [],
  };
  const orphanSet = new Set(orphanEndpoints.map(o => o.path));
  for (const o of orphanEndpoints) {
    if (whitelistActive.has(o.path)) {
      result.exempt_whitelist.push(o);
    } else if (whitelistAll.has(o.path)) {
      // 防伪通过但已过期 → 回落（首版 WARN，不阻断）
      result.expired_orphans.push({ ...o, expire: whitelistAll.get(o.path).expire });
    } else if (baselinePaths.has(o.path)) {
      result.exempt_baseline.push(o);
    } else {
      result.violations.push(o);
    }
  }
  for (const [canonical, entry] of whitelistAll) {
    if (!orphanSet.has(canonical)) {
      result.stale_whitelist.push({ path: canonical, owner_card: entry.owner_card, expire: entry.expire });
    }
  }
  for (const e of [...whitelistAll.values()]) {
    if (whitelistActive.has(e.path)) continue;
    if (orphanSet.has(e.path)) result.expired_whitelist.push({ path: e.path, expire: e.expire });
  }
  return result;
}

// ---------- --update-baseline 唯一合法写入口 ----------
/**
 * 新 baseline = 当前孤儿集 - 白名单豁免集。只减不增：
 *   相对磁盘旧版/HEAD 版出现新 path ⇒ 拒绝（新孤儿不得混入老账，须走白名单卡号流程或先消缺）。
 */
export async function writeBaseline(p, { orphanEndpoints, whitelistActive, prevDoc, headDoc, generatedAt, gitHeadVal, genCmd, feRoot }) {
  const exempt = new Set(whitelistActive.keys());
  const newPaths = [...new Set(orphanEndpoints.map(o => o.path))].filter(x => !exempt.has(x)).sort();
  const growthLog = Array.isArray(prevDoc && prevDoc.growth_log) ? [...prevDoc.growth_log] : [];

  for (const [refLabel, refDoc] of [['磁盘', prevDoc], ['HEAD', headDoc]]) {
    if (!refDoc || !Array.isArray(refDoc.paths)) continue;
    const refSet = new Set(refDoc.paths);
    const added = newPaths.filter(x => !refSet.has(x));
    if (added.length > 0) {
      throw new GateEnvError(
        `--update-baseline 拒绝写入: 相对 ${refLabel} baseline 新增 ${added.length} 条新孤儿（${added.slice(0, 5).join(', ')}${added.length > 5 ? ' …' : ''}）—— 棘轮只减不增；新端点豁免须登记白名单（卡号+reason+expire），或先补前端消费/删除端点`,
        { detail: added },
      );
    }
  }

  const prevCount = prevDoc && typeof prevDoc.count === 'number' ? prevDoc.count : 0;
  const delta = newPaths.length - prevCount;
  if (prevDoc) growthLog.push({ at: generatedAt, from: prevCount, to: newPaths.length, delta });

  const doc = {
    $schema_version: 1,
    generated_at: generatedAt,
    git_head: gitHeadVal,
    gen_cmd: genCmd,
    fe_root: feRoot,
    count: newPaths.length,
    paths: newPaths,
    paths_sha256: sha256OfPaths(newPaths),
    growth_log: growthLog,
    notice: '脚本独占写（node scripts/check-api-contract-fe-be.mjs --update-baseline）。人工编辑必被 sha256 自洽 + git HEAD 硬闸拦截。棘轮只减不增；新孤儿豁免一律走 docs/ipd-系统说明/api-internal-whitelist.json（卡号+reason+expire）。',
  };
  await writeFile(p, JSON.stringify(doc, null, 2) + '\n', 'utf8');
  return { doc, delta };
}

// ---------- 白名单文件骨架（首版生成辅助，供人审后落盘） ----------
export function whitelistSkeleton() {
  return {
    $schema_version: 1,
    entries: [],
  };
}
