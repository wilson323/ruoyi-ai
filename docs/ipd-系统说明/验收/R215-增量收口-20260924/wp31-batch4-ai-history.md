# WP3.1 批次4：AI 文档 history 端点真接 + 时间显示修复（R215）

日期：2026-09-25 ｜ 看板卡：AI-FUSION-B4（e5a86adc）｜ 前置修正：B4 范围因盘点误报缩小

## 范围修正（重要）

初版盘点（ai-fusion-gap-survey.md）称「ai-document.ts 封装零调用（假接线）」——**系误报**：
grep 时 shell cwd 漂到后端仓。磁盘重核：ai-document.ts 被 5 个视图使用
（ai-docs/index.vue 第 42 页 + 项目详情 documents/changes/circle/index），10 端点 9 接。
B4 实际范围收窄为：唯一未真接的 `GET /ai-documents/{id}/history`。

## 本批改动（前端仓 3 文件）

1. `api/ipd/ai-document.ts`
   - `getAiDocumentHistory` 原实现是**假封装**（内部转调 `/versions`，注释自认"同构"），
     导致 history 端点长期挂契约孤儿、且被老测试锁成契约。改真调 `/history`，
     新增 `AiDocumentHistoryItem` 类型（后端 HistoryItem 七字段投影：versionId/versionNo/
     author/createdAt/status/reviewedBy/archivedAt）。
   - 新增 `toTimeText` 时间兼容层：HTTP 真活实测后端 Date 序列化为 **epoch 毫秒数字**
     （1790261069000），旧 parseAiDocument 只认字符串 → 版本链/审核时间一直显示「待补充」。
     本批顺带修复（createTime/reviewedAt/createdAt/archivedAt 全部走兼容层）。
2. `views/ipd/ai-docs/index.vue`：版本链卡片底部新增「操作历史」小节
   （Divider + 行式：v 号 Tag / 状态 Tag / 创建时间 / 审核人 / 归档时间；
   history 失败降级不阻断链操作；data-testid=ipd-ai-history）。
3. `api/ipd/ai-document.test.ts`：重写 history 契约测（断言请求必须打在 /history 而非
   /versions + HistoryItem 字段解析 + null 保留）；新增响应非数组抛错测；
   新增 epoch 毫秒时间兼容测（锁死本批发现的真库形态）。

## 四证

| 证 | 结果 | 证据 |
|---|---|---|
| 单测 | 全量 1043 passed / 0 failed（本文件 18 用例含重写 history） | vitest 3.2.4 全量输出 23:25 |
| 类型 | vue-tsc --noEmit EXIT=0 | apps/web-antd |
| 契约门禁 | 孤儿 77→**75**，AI 相关孤儿**清零**（copilot×2 批次3 消 + history×1 本批消） | contract-verify-batch4.json |
| HTTP 真活 | GET /ai-documents/2103133473576321025/history → 200，HistoryItem 形态实测（versionId 字符串 ✅、createdAt epoch 毫秒 ⚠️→已修）；/versions createTime 同形态（时间显示 bug 波及面确认） | 本文件上方改动 1 |
| 浏览器 | 登录 ipd-admin → /ipd/ai-assistant → 加载文档 2103133473576321025 → 「操作历史」渲染 v1/待审核/创建：2026-09-24T07:44:29（可读，非长数字）；console error 0；同框可见批次3 AI 副驾浮钮 | batch4-ai-history.png（playwright 全页） |

## 附带发现（登记不修）

- 契约门禁孤儿从 77 降到 75（-2）：除本批 history 外另有 1 条被兄弟会话/批次3 侧效应消除，
  具体条目未逐一对账，属好方向漂移，留 WP4 总盘点核。
- GET /api/v1/ai-copilot 扫描器误报（Controller 无类级 GET）仍在候选清单口径内，
  修复归门禁扫描器侧（WP4）。
