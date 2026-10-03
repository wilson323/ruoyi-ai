# F 分区独立复核及授权最小修复

裁决：`PARTIAL`。来源显示合同缺口已修复，定向 Vitest 40 项通过；完整类型检查、全量测试、构建、新运行包浏览器验收仍待协调者统一执行。记录 UTC：2026-10-02T17:45:45.476115+00:00。

## 六行缺口

- 仓库：`/Users/mac/Documents/ruoyi-ipd-web`。
- 入口：`RunTimeline` → `buildTimelineItems`，四个 Cursor 任务5文件。
- 服务边界：仅 SOURCE 展示及测试，不改发送轨、布局、后端、状态合同。
- 字段：`retrievalStatus/reasonCode/sourceEvidence.reviewStatus`，无 DDL。
- 现状与目标：补无权显示、未知原因码原型成员过滤、知识出处精确合同。
- 证据等级：A 级源码与 diff、当前文件 SHA-256、真实定向 Vitest；Cursor 浏览器记录为历史执行证据，不冒充本次重验。

## 独立发现与修复

1. 后端 `ProjectKnowledgeSearchTool.retrievalStatus` 返回 UNAUTHORIZED，但原前端没有对应分支，导致无权结果显示为空。本次显式映射为「无权查看」，不因 hits 或 MCP reasonCode 改成失败/空命中。
2. 原 `SOURCE_REASONS[code]` 普通对象查询能返回 constructor/__proto__/toString 等原型成员，未知原因码并非总留空。本次使用 own-property 判断，仅本地11码可以翻译。
3. 原知识出处接受任何 `reviewStatus !== REVIEWED`，缺状态或 UNKNOWN 也可能显示为合格知识。已核后端 `ProjectKnowledgeFragmentTextSearch` 生产者使用 NOT_PROJECT_DOCUMENT，本次收严为该精确值并保留完整ID要求。

协调者随后明确授予 F 这四个文件独占修改；实际只改 timeline-model.ts、对应 model test 和 run-timeline.test.ts，run-timeline.vue 原补丁保留。未修改 SOURCE/reasonCode 后端合同。新增 model 与渲染测试覆盖无权、原型键、缺/未知审核状态及私密 query/preview/citationText 不展示。原 PARTIAL/FAILED/NO_HIT/SUCCESS/unknown 分支与11个原因码保持。

隐私审查：SOURCE 视图仅映射固定结果文案、白名单原因短句、明确出处名称和原有 title/ref/url；不展开 SOURCE 原始 payload，不展示 query、preview、citationText、异常类型、阶段、token等字段。Vue插值不会执行出处名称HTML。原有来源链接 safeUrl 不是地址/凭据的通用脱敏器，不能把任意后端字段安全断言扩大到全 payload；本次新增字段无链接或原始异常输出。

## 中文状态搜索：根因与最小兼容方案（未修改）

Panel.loadHistory 原样传 q；ProjectAgentRunListing.page 保留中文 q 搜产物标题正文；MybatisAgentRunStore.listOwnRuns 用同一 q 匹配 action_code/status 的 LIKE，加产物命中运行ID的 OR。数据库状态为 FAILED，中文「失败」自然不能以 status LIKE 命中，Cursor5浏览器观察与源码一致。

最小方案应由 G/协调者在现有列表查询内实现：q 继续原样用于动作与中文产物检索，在同一个受 tenant/project/person 限制的 OR 中加入命中界面状态中文名的状态枚举条件（至少「失败」→FAILED，并以同一页面状态标签映射覆盖其他状态）。不要把 q 替换成 FAILED，否则丢失中文正文；不要把 status=FAILED 作为额外 AND，否则丢失正文含「失败」的已完成运行。cursor/limit/排序、精确status筛选及权限外层保持。需测试既能命中FAILED、也保留正文中文命中、且不越用户项目。未跨写 G RunService/Mapper/Store，也没有提前实施第二条搜索轨。

## 验证

仓库根实际执行：

`pnpm exec vitest run --config vitest.ipd.config.mts apps/web-antd/src/views/ipd/_shared/ai-agent/timeline-model.test.ts apps/web-antd/src/views/ipd/_shared/ai-agent/run-timeline.test.ts`

退出0。Vitest v3.2.4，2026-10-02本机展示10:44:57开始，耗时6.02秒，2文件通过：timeline-model 19项、run-timeline 21项，合计40项。未写dist，未执行完整typecheck/build。协调者仍须统一验收实际当前产物。

## 修改前后 SHA-256

| 完整路径 | 修改前 | 修改后 |
|---|---|---|
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-agent/timeline-model.ts` | `d4fc0d9f6cbded480a381542c9d1a7666b1deea1b6e872b6dc63fda48aac64c9` | `9fccccd32349c5d965c96a8edfa7f7c79f10a3a1de63895e7a48188f27c02fd1` |
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-agent/timeline-model.test.ts` | `afcd2b9c8314d8c15f4536e33a56c17e836a1d32d6a344bf6f2264a72f9fda81` | `e3ef8289a9482f1ef742479f983ba6b6e0ed06bf2b8fafd78796c5e49e9c4a1d` |
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-agent/run-timeline.vue` | `fd364c3b62716f39f3bda9b8426c6a33d8c920a70985353969375771ff25b5bc` | `fd364c3b62716f39f3bda9b8426c6a33d8c920a70985353969375771ff25b5bc` |
| `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-agent/run-timeline.test.ts` | `c27f2210d34d52c1c20adadc1fb746136c80d35c958d61756c66b1d217760d9b` | `7d169356b7c9113dd8cf535fbd4a93e06132a601e670c83016962671540ebf1a` |

工作区其他在途变更保留；未提交、未推送、未写索引、未改总计划/看板。以上哈希仅证明记录时磁盘内容。

## 中文状态搜索补丁草案（未应用）

新增 `codex-f-status-search-draft-20261002.patch` 与对应 JSON，状态 DRAFT_ONLY。只允许 MybatisAgentRunStore.java 和 MybatisAgentRunStoreTest.java 两个路径；Listing/Mapper/OwnRunQuery/FE 不需要修改。生产者中文标签现查是「排队中/运行中/等待审批/取消中/已完成/失败/已取消」，不新增「执行中/等确认」同义词。采用标签 contains(q) 与现有LIKE子串语义对齐，原始q、产物命中OR、英文状态LIKE、动作LIKE继续保留；中文状态候选的 status IN 仅加在同一括号OR中。所有用户/项目/租户、精确筛选、cursor、排序和limit保持外层。

草案附两项测试：查询构造保留原中文pattern+FAILED+产物ID且scope在外层；7个现有标签可映射，英文状态与通配字符仍走原literal LIKE，未增加的新词不映射。仅做 git apply --check 退出0；没有应用、编译、Maven、DB/HTTP验收，测试草案不称通过。应用前须重新核JSON中的两个before hash，变更冲突要重审，不能覆盖兄弟修改。内存测试替身未同步，因为该刀只针对MyBatis查询；后续服务测试要验证真实Store路径，不能以旧内存替身代表新查询语义。
