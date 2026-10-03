#!/usr/bin/env node
/**
 * post-commit-update-kanban.cjs 的回归用例（可执行，无外部依赖）。
 *
 * 事故（2026-10-03）：该钩子在每次 git commit 后运行，把 commit message 里的卡号
 * 交给 manage.py set 改写 SSOT 卡面状态格（manage.py 里 PLAN =
 * docs/ipd-系统说明/开发计划-看板镜像.md，Markdown 为权威源）。当时有两处缺陷：
 *   A. 切分失效——pretty 用 %s%n---BODY---%b，%b 前没有 %n，而切分正则要求
 *      /\n---BODY---\n/，永不匹配，subject 变成整条消息，commit 正文连同分隔符
 *      一起被写进卡面状态格。实测 3f53e937 顶掉 P0-9、e5bb1de1 顶掉 QA-08。
 *   B. 卡号来源过宽——从整条消息（含正文）提取卡号，正文顺带提及某卡即改写该卡；
 *      而 detectStatus 默认返回 done，等于把「被讨论的卡」刷成「已完成」。
 *
 * 断言面是 planReconcile（main 的唯一数据来源），不是底层纯函数——
 * 否则把 main 里的入参从 subject 换成 fullMessage 时用例仍为绿（实测如此，属假绿）。
 *
 * 运行：node .claude/hooks/post-commit-update-kanban.test.cjs
 * 期望：全部通过退出 0；任一失败退出 1。
 * 自证能红（已实测）：把 planReconcile 内 extractCards(subject) 换成
 * extractCards(fullMessage)，或把切分正则改回 /\n---BODY---\n/，用例均转红退出 1。
 */

'use strict';

const assert = require('assert');
const { planReconcile, splitMessage, extractCards } = require('./post-commit-update-kanban.cjs');

let passed = 0;
const failures = [];

function check(name, fn) {
  try {
    fn();
    passed++;
    console.log(`  PASS  ${name}`);
  } catch (e) {
    failures.push(`${name}: ${e.message}`);
    console.log(`  FAIL  ${name}\n        ${e.message}`);
  }
}

const ACCIDENT = 'fix(matrix): 清理 9 条已证伪的 auditLog 假证据\n'
  + '---BODY---\n'
  + '- QA-08 249 AC 全量执行仍 ⬜ 未实施；P0-9 汇总卡已 done\n'
  + '- 登记 Docker 阻塞与 wip 事项\n';

// 1. 【事故正例】整条链路：正文提及的卡号不得成为改动对象
check('planReconcile 忽略正文提及的卡号（事故正例）', () => {
  const r = planReconcile(ACCIDENT);
  assert.deepStrictEqual(r.cards, [], '正文提及的 QA-08/P0-9 不应进入 cards');
});

// 2. 【事故正例】subject 不得残留正文或分隔符（也就不会写进卡面状态格）
check('planReconcile 的 subject 不含 ---BODY--- 与正文', () => {
  const r = planReconcile(ACCIDENT);
  assert.strictEqual(r.subject, 'fix(matrix): 清理 9 条已证伪的 auditLog 假证据');
  assert.ok(!r.subject.includes('---BODY---'), 'subject 不应含分隔符');
  assert.ok(!r.subject.includes('QA-08'), 'subject 不应含正文内容');
});

// 3. 标题里带卡号时仍要生效（不能修成永不生效）
check('planReconcile 取到标题中的卡号', () => {
  const r = planReconcile('fix(QA-08): 补齐验收脚本 | P0-9 汇总\n---BODY---\n无\n');
  assert.deepStrictEqual(r.cards, ['QA-08', 'P0-9']);
});

// 4. 状态判定不受正文关键词影响（正文里的「阻塞」不得把 done 拉成 todo）
check('planReconcile 的状态判定不被正文关键词影响', () => {
  assert.strictEqual(planReconcile(ACCIDENT).status, 'done');
  assert.strictEqual(planReconcile('wip: 半成品\n---BODY---\n无\n').status, 'inprogress');
});

// 5. 【反向对照】旧口径确实会误抓——证明本用例有区分力
check('反向对照：旧口径（整条消息）会误抓正文卡号', () => {
  const { subject, fullMessage } = splitMessage(ACCIDENT);
  assert.deepStrictEqual(extractCards(subject), [], '新口径：不抓');
  assert.ok(extractCards(fullMessage).includes('QA-08'), '旧口径：会抓');
  assert.ok(extractCards(fullMessage).includes('P0-9'), '旧口径：会抓');
});

// 6. 切分单元：%b 前有/无换行两种 git 输出都要切得开
check('splitMessage 两种 git 输出形态均可切分', () => {
  const a = splitMessage('subj\n---BODY---\nbody\n');
  const b = splitMessage('subj\n---BODY---body\n');
  assert.strictEqual(a.subject, 'subj');
  assert.strictEqual(b.subject, 'subj');
  assert.ok(a.body.includes('body') && b.body.includes('body'));
});

console.log(`\n  ${passed} passed, ${failures.length} failed`);
if (failures.length > 0) {
  console.error('\n  失败项：');
  for (const f of failures) console.error(`   - ${f}`);
  process.exit(1);
}
process.exit(0);
