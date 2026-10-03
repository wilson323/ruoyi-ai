# Cursor任务5结果：项目智能体前端单轨与真实浏览器验收

状态：`PENDING_VALIDATION`。已提交前端 `2797223` 的运行、返工和历史已在真实 Person 浏览器核过。来源显示已按任务 1/2 字段合同做了最小适配，开发服务器能区分「部分查到 / 已查到 / 没有查成」。PID 13520 仍是旧包，页面上看不到新的 `reasonCode`。产物 `69b9c84f9fe34eb088c9d0c7920a093e` 仍是 `DRAFT`，没有批准。未提交、未推送、未改总画布、未跑主构建。

## 六行缺口

- 仓库：前端 `ruoyi-ipd-web`，HEAD `2797223d4c68490e9eeb9b4c75a718f937ce3c3a`。后端 `ruoyi-ai`，HEAD `29b64b97c1729335abe22955b06a7597d68a9fab`。
- 入口：`ProjectAgentPanel` / `RunTimeline`。发送仍走 `createProjectAgentRun`。
- 服务边界：只改时间线来源展示和对应测试。未改布局、路由、产品目录、后端。
- 表和字段：只读 `ipd_agent_run`、`ipd_agent_artifact_version`、`ai_documents`。无 DDL。
- 现状与目标：旧包来源事件已有 `retrievalStatus`，但提交版时间线不读它。任务 2 的 `reasonCode` 还没装进 16039。
- 证据等级：A。浏览器 DOM、截图、库回读、定向 vitest。全量 typecheck / build 未跑。

## 运行基线

| 项 | 现查 |
|---|---|
| 前端 HEAD | `2797223d4c68490e9eeb9b4c75a718f937ce3c3a` |
| 后端 HEAD | `29b64b97c1729335abe22955b06a7597d68a9fab` |
| 后端 PID | 13520，2026-10-02 09:50:50 启动 |
| 已加载包 | `ruoyi-admin.codex-global-20261002-729e587af650.jar` |
| 包 SHA-256 | `729e587af6504d12ebdf20e4320141f688408fafeda15511482931e2ad8e1003` |
| 前端 PID | 38773，2026-10-02 08:02:53 起，`127.0.0.1:15666` |
| 后端监听 | `127.0.0.1:16039` |

`ai-agent` 在改来源显示之前与 `2797223` 一致。开发服务器同时吃着工作区里未提交的 `ai-assistant.vue`、`layouts/ipd.vue`、`routes/modules/ipd.ts`。这三份不是本任务改的。发送仍进项目智能体，没有改走副驾。

## 浏览器

人员：`ipd-admin`。项目：`2103659612308828162`。时间约 2026-10-02 17:20Z–17:38Z。

1. 刷新后历史仍在。刷新前 5 条，刷新后同一 5 条，项目编号不变，模式仍是项目智能体，六阶段仍在。
2. 搜索 `FAILED` 只剩运行 `2106047284264341506`。搜索「失败」命中两条已完成运行的产物正文，没有命中那条状态为 `FAILED` 的运行。空结果文案这次没走到。
3. 运行 `2106061095016882177` 上有「根据退回意见再做」。第一次点击被浏览器拦在发出前，请求体已是三元组：`actionCode=C02`，`previousRunId=2106061095016882177`，`targetDocumentId=2106061816428781569`，`baseVersionId=2106061816428781569`。页面文案是「无法连接服务，请检查网络后重试」。库中没有新运行。
4. 这次失败之后的普通发送正文是「普通核对，不绑定退回。」请求里没有 `previousRunId`、`targetDocumentId`、`baseVersionId`。`actionCode` 仍是 `C02`，因为页面上还选着竞品分析，不是返工关联带出来的。这次同样被拦在浏览器里，没有入库。
5. 再次点击「根据退回意见再做」真正打到 16039。新运行 `2106074253571919874`，`RUNNING`，`action_code=C02`，库时钟 `2026-10-03 01:30:06`。请求体三元组与上面相同。
6. 刷新页面后该运行仍是 `RUNNING`。历史里能看到它。随后点「取消运行」，界面变为「已取消」，库状态 `CANCELLED`，`finished_at=2026-10-03 01:31:20`。没有产物行。
7. 运行 `2106064774604263425` 的工具卡把两次 FastGPT 调用显示为失败，项目资料检索显示为已完成。运行结束显示「已完成」。意图六个步骤都是「待核实」。这不是动作批准。
8. 已结束运行的正文预览出现在「本次运行」。这次打开的正文没有单独的「思考」折叠，因为这段回读没有可拆开的思考段。新返工在取消前只看到运行开始和步骤，没有等到产物预览。
9. 产物 `69b9c84f9fe34eb088c9d0c7920a093e` 仍是 `DRAFT`，`document_id` 为空。文档 `2106061816428781569` 仍是 `REJECTED` 版本 2。没有点「工作成果定档」，没有审核通过。

截图在 `cursor-task-5-evidence/`。

## 来源显示

任务 1/2 结果到达后，只改了时间线。`retrievalStatus=PARTIAL` 显示「部分查到」，`SUCCESS` 且有命中显示「已查到」，`SUCCESS` 且 `hits=0` 或 `NO_HIT` 显示「没有命中」，`FAILED` 显示「没有查成」。`FAILED` 即使带着旧的 hits 数组，也不显示成没有命中。`reasonCode` 只翻译合同里的 11 个码；没有该字段或未知码时不编原因，也不读 preview。`sourceEvidence` 只展示合格的项目已审核文档和产品知识库名称。

开发服务器刷新后，运行 `2106064774604263425` 的 9 条来源是：部分查到 3、已查到 4、没有查成 2。原因行为空。这和旧包事件一致：两次 MCP 失败没有 `reasonCode`。不能把这次页面说成新诊断包已生效。

## 测试

2026-10-02 10:36:44-07:00，仓库根，退出码 0：

`pnpm exec vitest run --config vitest.ipd.config.mts apps/web-antd/src/views/ipd/_shared/ai-agent/timeline-model.test.ts apps/web-antd/src/views/ipd/_shared/ai-agent/run-timeline.test.ts`

2 个文件，37 项通过。随后 `tool-call-rows.test.ts` 4 项通过，退出码 0。

全量 typecheck、全量 vitest、`build:antd` 未跑。主构建窗口留给协调者。没有写 `dist`。

## 差异

相对 `2797223`，本任务只动这 4 个文件，`+205/-1`：

- `timeline-model.ts` `d4fc0d9f6cbded480a381542c9d1a7666b1deea1b6e872b6dc63fda48aac64c9`
- `timeline-model.test.ts` `afcd2b9c8314d8c15f4536e33a56c17e836a1d32d6a344bf6f2264a72f9fda81`
- `run-timeline.vue` `fd364c3b62716f39f3bda9b8426c6a33d8c920a70985353969375771ff25b5bc`
- `run-timeline.test.ts` `c27f2210d34d52c1c20adadc1fb746136c80d35c958d61756c66b1d217760d9b`

工作区里其他已改文件保持原样，没有删除工作树。

## 未验证

- PID 13520 没有任务 1/2 的新来源和诊断补丁。真实运行里没有出现「查询超时」这类新原因句。
- 全量类型检查和隔离 dist 构建未做。
- 第一次返工失败和随后的普通发送是浏览器拦截，不是服务端拒绝。
- 新返工已被取消，没有跑完，也没有生成预览或定档。
- 搜索「失败」不能按界面上的「失败」状态找到 `FAILED` 运行。这次没有改搜索。
