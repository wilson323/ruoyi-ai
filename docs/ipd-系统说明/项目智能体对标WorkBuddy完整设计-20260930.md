# IPD 项目智能体对标 Work Buddy 完整设计

日期：2026-09-30。

事实来源是当天工作区里的当前代码，不是旧文档里的「已实现」。前端 `/Users/mac/Documents/ruoyi-ipd-web`，后端 `/Users/mac/Documents/ruoyi-ai`。本文只设计，不改业务 Java / Vue，不改 `docs/开发说明/`，不改六阶段导航实现。

对标对象是腾讯 WorkBuddy（企业智能体工作台）。本地 `最佳实践` 没有这份产品的一手规格，公开资料与可引用观点见第 3 节。对不上 IPD 硬边界的能力不搬：不能上网、不能跨项目、不能代写 Gate 结论、不能存用户提示词原文。

---

## 1. 裁决：差距在设计粒度，不在再堆一块组件

**部分闭环。** 一次运行已经能创建、落事件、出正文、把草稿定到「待审核」文档。它仍然不像一次可验收的任务：用户看见的是聊天气泡加一条被压扁的步骤，服务端刚写出来的意图、澄清和计划没有界面，也没有执行闸门。

根因是：执行状态已经按事件存在，产品语义仍按「一段模型正文」呈现。补一个卡片组件不改变这件事。下面每一条都标现状。

| 环节 | 现状 | 证据 |
|---|---|---|
| 发送走项目智能体单轨 | 闭环 | `apps/web-antd/src/views/ipd/_shared/ai-assistant.vue`：`workspaceMode === 'ai'` 时 `sendViaProjectAgent` → `ProjectAgentPanel.submitText`。classic 走 `streamCopilot`。切模式中止并清空另一侧（同文件注释与 `workspaceMode` 监听）。 |
| 三栏 + 顶栏六阶段 | 闭环，且必须保留 | 左 `aside.ipd-ai-runs-col`（`data-testid="ipd-ai-runs"`），中 `IpdAiWorkspace` 的 `cards`/`steps` 互斥（`ai-workspace.vue` 用 `v-show` 只显示当前 pane），右对话列。顶栏浏览阶段在 `ai-assistant.vue` 的 `ipd-ai-stage-nav`：`selectViewStage` 只改 `viewStageCode`；徽章文案是「当前进度」，条件是 `projectCurrentStage === stage.code`。全局轨道在 `layouts/ipd.vue` 的 `stage-rail`，`active` 绑定 `currentProject.currentStage`，节点不是按钮，点不了、也改不了进度。 |
| 加号里选能力 | 闭环 | `project-agent-panel.vue` `controlsExternal` 把 `CapabilityPicker` 传送到 `#ipd-ai-agent-controls`。`capability-selection.ts` 的 `buildRunInput`：动作码不在当前包 `actionCodes` 里就不提交；技能与包取交集。模型 id 来自服务端可用 `modelConfigId`。 |
| 意图事件 | 部分闭环 | 后端已写 `STEP` / `kind=INTENT`。前端 `timeline-model.ts` 的 `STEP` 只取 `title`/`detail`，丢掉 `needsPlan`、`needsClarification`、`questions`、`steps`。对话用 `timelineTranscript`，只拼接 `kind==='text'`。 |
| 澄清 / 计划闸门 | 未闭环 | `ProjectAgentIntent.prompt` 要求「不要调用工具，不要写交付物」，但 `ProjectAgentRunExecutor.start` 在 `publishIntent` 之后无条件 `kernel.execute`。提示词不是闸门。 |
| 计划步骤与动作一致 | 未闭环 | `planSteps` 按句切用户原文，再按「功能/价格/…」补「核对×」。能力包 `capability-packs.json` 没有步骤数组；真正的步骤在已加载技能正文，例如 `ipd-skills/competitor-analysis-ipd/SKILL.md` 的「步骤」。模型仍可能改口，因为没有把技能步骤锁进事件，也没有拒绝改口的执行约束。 |
| 开始执行切到本次运行 | 未闭环 | `focusPane` 会把 pane 写成目标并持久化（`use-ai-workspace.ts`）。副驾出卡时 `handleDoneCard` 调 `focusPane('cards')`。`sendViaProjectAgent` 的 `case 'started'` 直接 `return`。 |
| 思考区 | 部分闭环 | `model-message.ts` 的 `modelMessageParts` 循环吃掉全部 `<think>`。`assistant-turn.vue` 的 `.think-body` 已有 `max-height: 9.5em`。但它包在 `.msg .bubble { white-space: pre-wrap }` 里，summary 的可访问名只有「思考」，长行会把 details 撑开。用户观察约 633px；本设计按这条 CSS 链解释，本次没有再量像素。 |
| 历史可搜索 | 未闭环 | `project-agent-panel.vue` 仍写「更早的运行没有列表接口」。测试 `ai-assistant.test.ts` 约第 954 行钉死这句。`ProjectAgentController` 只有单次 `GET /agent-runs/{runId}` 和 events，没有按人列表。重开只能靠已有的 `fetchAgentRun` + `fetchAgentRunEvents`，前提是界面手里有 runId。 |
| 原文不落库 | 闭环，必须保持 | `IpdAgentRun` 注释审计规约 L0-5：只存 `inputDigest`、`inputChars`、`requestDigest`，不存用户原文。因此历史搜索不能搜提示词，回放也不能还原用户气泡原文。 |
| 定档与知识库 | 闭环 | `POST /api/v1/agent-runs/{runId}/artifacts/{artifactId}/apply` → `ProjectAgentRunService.applyArtifact` → `AiDocumentService.createGeneratedAuthorized`，状态 `GENERATED`，`statusLabel` 为「待审核」，`indexStatus` 固定 `NOT_INDEXED`。`embedAsync` 只在审核路径（`AiDocumentService` 里 review 那段）出现，禁止从 apply 再调。 |
| 产物点赞 | 未闭环 | 合同：`ARTIFACT_VERSION` 的 `targetId` 是 `ipd_agent_artifact_version.id`（`ProjectAgentConstants`、`AiFeedbackService`）。`persistArtifactDraft` 写入事件时只有逻辑 `artifactId`、`title`、`contentHash`、硬编码 `version=1`，没有 `version.getId()`。`run-timeline.vue` 把 `row.item.artifactId` 交给 `FeedbackBar` 的 `target-type="ARTIFACT_VERSION"`。点赞会打到不存在的版本主键。 |
| 事件合同与测试 | 部分闭环，下一期会红 | `AgentEventType` 仍是闭合 9 类，INTENT 走的是 STEP 的 payload，没有新枚举。`ProjectAgentRunServiceCreateTest` 期望事件恰好 `RUN_STARTED, STEP`。`ProjectAgentRunServiceLifecycleTest`：首包 seq 恰为 1、2；取消运行中期望 `RUN_STARTED, STEP, TEXT_DELTA, RUN_FINISHED` 共 4 条；内核失败期望 `RUN_STARTED, STEP, ERROR`。多一条 INTENT 的 STEP 后这些断言都会破。设计要求同步改断言，本次不改测试。 |

生产化差在三处粒度，缺任何一个，界面都会继续像聊天：

1. **判定没有变成用户可见的任务状态。** INTENT 已经算出直答 / 澄清 / 计划，事件里也有问题和步骤，渲染层把它们揉成一行「意图判断 + 一句话摘要」。
2. **判定没有约束执行。** 澄清和计划都只写进系统提示。内核照样调工具、照样在成功时把全文落成产物。
3. **任务活在单次内存里。** 左栏不列出本人历史；刷新后用户原文不可回读（这是对的）；可用的回放材料（动作、状态、事件、产物标题和正文）没有列表和搜索。

---

## 2. 现状事实链

用户在现有三栏里发送，到定档和反馈，逐步如下。「已有代码」= 主路径已接线并落事件或落库。「只有事件没有渲染」= 后端写了，界面不用这些字段。「根本没有」= 没有接口、没有状态迁移或没有闸门。

1. **选项目、选浏览阶段、可选子阶段动作。** 已有代码。项目来自全局 `layouts/ipd.vue` 传入的当前项目。浏览阶段只切视图。对话列 `ipd-ai-step-actions` 点选动作，写入 `sessionAction`。下一次 `buildRunInput` 仅当该 `actionCode` 属于当前能力包才放进请求；`actionSkillNames` 与包内可用技能取并集去重。
2. **加号选定能力包、模型、技能、工具。** 已有代码。未启用模型不进选择器（`sanitizeSelection` / 仅 `available`）。工具 id 必须是包内 id，例如 `project_knowledge_search`（`capability-packs.json`）。`/mcp/market` 的数字 id 若被提交，`ProjectAgentRunPlanner.plan` 抛「工具不属于该能力包」。
3. **发送。** 已有代码。`onComposerSend` → `send` → `sendViaProjectAgent`。返回 `started` 后输入框清空，**不切**中间栏。返回 `need-project` / `need-selection` / `busy` / `failed` 只弹 `antMessage`。
4. **创建运行。** 已有代码。`POST /api/v1/projects/{projectId}/agent-runs`。`requireVisible` 校验项目可见；形状校验；可选产品线校验；`requestDigest` 做幂等；`planner.plan` 冻结包、模型、技能、工具、动作。`tryReserve` 领并发额度，满则 `RATE_LIMITED` 并释放。插入 `ipd_agent_run`，状态 `PENDING`。用户原文只进 `inputDigest` + `inputChars`，然后随 `ProjectAgentRunSpec.message` 交给当次执行，不另存原文。
5. **CAS RUNNING。** 已有代码。`start` 里 `PENDING → RUNNING` 失败则释放额度并返回（取消抢先时走这条）。
6. **RUN_STARTED。** 已有代码，渲染成「运行开始」，没有任务标题（也不能用原文当标题）。
7. **SKILL_LOADED。** 已有代码。每个已加载技能一条 `STEP`，`kind=SKILL_LOADED`，含 name、version、sha256。界面把任意 STEP 画成 `strong + span`，kind 被丢弃，用户分不出「技能已加载」和「意图判断」。
8. **意图判断。** 已有代码，**只有事件没有渲染**。`publishIntent` 调 `ProjectAgentIntent.decide(message, actionCode)`，`onStep("INTENT", payload)`。`onStep` 先写 `kind` 再 `putAll`，所以 payload 里的 title/detail/questions/steps 会在，kind 保持 `INTENT`（当前 payload 自己不带 kind 键）。系统提示 `ProjectAgentPrompt.build` 再拼一遍 `ProjectAgentIntent.prompt`。
9. **澄清则停。** **根本没有闸门。** 需要澄清时步骤列表被清空、提示词禁止工具和交付物，但下一步仍 `kernel.execute`。
10. **计划先给人看。** **根本没有。** `needsPlan=true` 时步骤只进提示词和事件 payload，内核立即执行。`AgentRunStatus.WAITING_APPROVAL` 在状态机里（`RUNNING` 可以进去，也可以回到 `RUNNING`），生产代码没有写入者。
11. **内核执行。** 已有代码。`AgentScopeProjectAgentKernel`：`skillsEnabled(false)`、`disableDynamicSkills`、关掉文件系统 / shell / 记忆 / 子智能体 / 动态技能。工具集不得超出本次 `toolIds`。不启用 `chat.kernel.agentscope.enabled`，不给聊天内核填 Toolkit。
12. **工具调用 / 结果 / 来源。** 已有代码。`TOOL_CALL`、`TOOL_RESULT`、`SOURCE`。中间栏 `tool-call-rows.ts` 把成对调用折成 `tool-call-card.vue`。对话列的 `timelineTranscript` **丢掉这些事件**，所以对话里没有同一张工具卡。
13. **文本增量。** 已有代码。`TEXT_DELTA` 合并进时间线文本，对话栏只拿到这段拼接。思考标签由 `modelMessageParts` 拆出。推理原文不进事件以外的库（内核注释：推理原文不外发不落库；界面上的思考来自模型写进正文的 `<think>`）。
14. **产物。** 已有代码。成功且全文非空时 `persistArtifactDraft`：逻辑 `artifactId`（UUID）、`versionNo=1`、title、content、sha256、状态 `DRAFT`，写入 `ipd_agent_artifact_version`，再写 `ARTIFACT` 事件。事件没有版本行主键，正文也不在事件 payload 里（左栏预览因此经常是空的：`timeline-model` 向 payload 要 content/preview，落库正文不在 payload）。左栏 `variant="artifacts"` 只渲染 ARTIFACT 事件。
15. **终态。** 已有代码。成功或取消：唯一 `RUN_FINISHED`。失败：唯一 `ERROR`。取消运行中是两阶段：`CANCEL_REQUESTED` 后迟到帧不再落库。PENDING 取消可以直接 `CANCELLED`，随后调度到的 start 放弃、不调内核。
16. **定档。** 已有代码。按钮在左栏产物时间线，调用 apply。文档 `GENERATED` / 「待审核」，索引回执 `NOT_INDEXED`。定档不表示知识库已可检索。
17. **反馈。** 运行点赞部分闭环：`RUN_MESSAGE` 的 `targetId=runId`，与 `ProjectAgentConstants` 一致。产物点赞未闭环，见第 1 节。
18. **历史回放。** 单次回放函数已有：`fetchAgentRun`、`fetchAgentRunEvents`。人员范围的列表和搜索 **根本没有**。当前会话的用户气泡只来自内存 `lastTask`，刷新即丢，这符合 L0-5，但左栏没有任何可点的历史 runId 把事件重新拉回来。

---

## 3. 对标 Work Buddy 的能力差距

### 3.1 出处

本地检索 `/Users/mac/Documents/最佳实践`：

- 没有 WorkBuddy 产品规格。命中的是会话目录名、语料路径，以及一篇把商用桌面 Agent 比作「精装房」的文章：`AgentScope-Java-知识库-20260928/07-本地微信文章/TierC-背景工程化/啤酒的夏天/我翻了_DeepSeek_Harness_的插件：大多数人忙着装修，聪明人在装大脑.md`（「商用桌面 Agent（WorkBuddy 们）卖你的是精装房」）。这只说明它是完整产品，不能当交互规格。
- 可迁移的工程观点，而且和本设计一致的：
  - `agent-scope/v2/zh/docs/harness/plan-mode.md`：动手前只读，计划写下来，人确认后再执行；未授权的写操作要被拒绝，而不是靠提示词自觉。
  - `最佳实践/2026-09-28-AgentScope-W1语义兼容性独立复核.md` 第 5 点：不要打开原生 Plan Mode 去写 `plans/PLAN.md`，否则和 IPD 自己的计划权威形成两个可写状态源。本设计的计划权威是 `STEP/kind=INTENT` 事件里的 `steps`，来自确定性函数或技能「步骤」节，不开 `enablePlanMode()`。
  - 同文第 6 点：执行前权限必须在副作用发生前拦截。对 IPD 即：澄清未答、计划未确认时，不要调用 `kernel.execute`。
  - `agent-scope/v2/zh/blogs/usecases/finxscope.md` 把「工作搭档」定义成能规划和执行的智能体，而不是聊天助手。只作方向，不把金融用例的权限搬进 IPD。

公开产品（2026-09-30 检索，不是本仓实现）：

- [腾讯云 WorkBuddy Enterprise](https://cloud.tencent.com.cn/product/workbuddy-enterprise)：企业 AI 工作台、技能、模型、权限与审计。它连腾讯文档 / 网盘 / 乐享，这些 IPD 不接。
- [入门指南](https://cloud.tencent.cn/document/product/1831/134389)：任务卡片、列表可搜索；状态含进行中、已完成、失败、待处理、规划中；中间对话看执行过程；另一侧看产物。
- [产品说明 PDF](https://main.qcloudimg.com/raw/document/product/pdf/1831_134383_cn.pdf)：左侧是任务与工作空间，不是无限聊天记录；默认 / Plan / Ask 三种权限。Plan：「先生成执行计划，确认后再操作」。
- [三种模式说明](https://developer.cloud.tencent.cn/article/2734254)（文内注明画面来自 2026-07-26 录屏）：Plan 先列出步骤，可调整再「开始执行」；信息不足先列待确认项；Ask 不直接改东西。

### 3.2 差距表（全部落在现有三栏，不开新布局）

| Work Buddy 能力 | IPD 现有怎么承接 | 差距 |
|---|---|---|
| 任务卡，而不是一条聊天气泡 | 左栏已有「历史 / AI 产物」两节。一张卡 = 一次 `ipd_agent_run`：动作名、状态、时间、产物标题。 | 没有列表接口，左栏只有当前内存中的 runId。 |
| 意图分流：直答 / 澄清 / 计划 | 中间「本次运行」挂唯一意图卡，数据来自同一条 `kind=INTENT`。对话列只留用户句与思考/正文。 | 事件已写；意图/澄清/计划曾与对话糊成一段。 |
| 澄清问题卡 | 意图卡下面的问题列表。回答仍走下一次 `create run`（第 7 节）。 | 没有卡，也没有「未回答则不调内核」。 |
| 计划确认 | 计划卡列步骤。已绑定动作的步骤锁死为技能「步骤」节，只展示、不让模型改。未绑定动作的多步计划停在 `WAITING_APPROVAL`。 | 步骤被切句生成，且立刻执行。 |
| 执行中的步骤状态 | 计划卡的每一步跟随后续 `STEP`（如 `MODEL_CALL`）和工具卡。不新开步骤页。 | 步骤页是项目小阶段（`StageStepNav`），和本次运行互斥，这个分工保持。本次运行里的步骤还没有状态。 |
| 工具卡 | 继续用 `tool-call-card.vue`（中间栏已有）。对话里放同一组件，数据仍来自同一事件数组，不另打一次模型。 | 对话用转录丢掉了工具事件。 |
| 产物 | 左栏定档保留。中间本次运行显示标题和「待定档」；正文以版本表为准，事件补预览字段或打开详情时用已落库正文（不把全文塞进事件也可以，但现在事件没有正文，预览经常空）。 | 预览与点赞 id 都缺。 |
| 人审 | 定档后文档「待审核」，Gate 双签仍走原评审页。智能体不写通过/不通过。 | 这条已对齐，不要为了像 WorkBuddy 的「完全访问」去加写文件或联网。 |
| 历史回放 | 左栏点一条，`fetchAgentRun` + `fetchAgentRunEvents` 灌进现有时间线。用户原文位置显示「原文不保存」，用动作和产物标题识别这条任务。 | 没有列表和搜索。 |

不搬的能力：多任务并行写任意工作目录、Ask/Plan/Craft 三套模式开关、联网、跨项目资料、定时自动化、把任务标题存成用户原文。IPD 的「工作空间」就是当前项目；模式只有顶栏已有的传统 / AI，AI 内部用意图结果分流，不再加第三套模式按钮。

---

## 4. 交互状态机

一次运行在提交之后只处于下面之一。澄清未回答：不调工具、不写 `ipd_agent_artifact_version`、不调 apply。计划在内核执行前对用户可见。已绑定动作时，计划步骤等于该动作已加载技能的「步骤」节，服务端写进 INTENT，提示词声明不得改口；不要再让模型自拟一份计划覆盖它。

```text
提交
  → 领额度失败：没有运行，输入框保留（幂等键可复用，现有面板已这样）
  → PENDING
      → 用户立刻取消：CANCELLED + RUN_FINISHED，不调内核（已有）
      → CAS RUNNING
          → RUN_STARTED → SKILL_LOADED → INTENT
              → 直答：内核执行 → 文本 / 工具 → 产物草稿 → SUCCEEDED
              → 需要澄清：WAITING_APPROVAL，只展示问题。禁止 kernel.execute
                    → 用户另一次 create：把回答写进新 run 的 message（第 7 节）
                    → 用户取消：CANCELLED
              → 需要计划且已绑定动作：计划卡展示技能步骤，然后内核执行（步骤已是业务权威，不再等人改写）
              → 需要计划且未绑定动作：WAITING_APPROVAL，展示步骤
                    → 确认：RUNNING，按事件里的 steps 执行
                    → 改步骤：不在本轮改事件；用户用新 message 再 create（第 7 节）
              → 执行中失败：FAILED + ERROR，可重试 = 新的 idempotencyKey 再 create
  → 产物待定档：SUCCEEDED 且存在 DRAFT。定档成功：版本 APPLIED，文档 GENERATED / 待审核，索引 NOT_INDEXED
```

`WAITING_APPROVAL` 已在 `AgentRunStatus` 中，允许的迁移是回到 `RUNNING`、进入 `CANCEL_REQUESTED` 或 `FAILED`。不要新事件枚举。进入等待时再写一条 `STEP`，`kind=AWAIT_USER`，`reason=CLARIFICATION` 或 `PLAN_CONFIRM`，`title` 用人能读的句子。没有这条 kind 的旧运行保持现在的行为。

直答条件：不需要澄清，且不需要计划。此时提示词已写「直接回答，不要先写一份计划」，界面不渲染空计划卡。

失败可重试：沿用面板「失败保留幂等键；改了输入才换新键」。业务重试是一次新运行，不在 FAILED 上原地复活（终态不可再迁移，`AgentRunStatus` 已规定）。

---

## 5. UI / UX（现有三栏 + 顶栏六阶段 + 加号）

不换视觉语言。继续 Vue、Ant Design Vue、`--ipd-*`。工具卡、加载态、输入框继续用已经对齐 21st 的三个件：`tool-call-card.vue`、`ai-loading-state.vue`、`ai-composer.vue`（注释写明对齐 AiPromptInput demo 23958）。禁止安装 lucide-react、framer-motion、`@radix-ui`、clsx、tailwind-merge，禁止新建 `/components/ui`，禁止用框架 shadcn。

### 5.1 左栏 `ipd-ai-runs-col`

信息架构（上到下）：

- 标题仍是「项目智能体」加状态 Tag。
- **历史**：搜索框占一行。占位「搜索动作、状态或产物」。结果是按钮列表，不是聊天气泡。每行：动作名（没有动作则「未绑定动作」）、状态中文、创建时间、产物标题（没有则「无产物」）。禁止出现用户原文、摘要哈希。
- 点一行：用 `fetchAgentRun` + `fetchAgentRunEvents(runId, 0)` 替换当前面板事件，并 `focusPane('cards')`。空状态不要再写「更早的运行没有列表接口」——那句话只在列表接口落地前成立；接口落地后改成「没有匹配的运行」。测试第 954 行要跟着改，算后续，不放进下一期最小闭环。
- **AI 产物**：仍只挂当前打开的这次运行里的 ARTIFACT。定档按钮、回执「待审核 / 未索引」留在这里。中间栏可以显示同一产物的只读卡，定档主动作不搬到中间，避免两个按钮。

搜索范围见第 8 节。没有列表接口之前，左栏继续只显示本次运行，禁止编造历史。

### 5.2 中栏

页签保持两个，互斥：

- **本次运行**（`cards`）：意图卡、澄清卡、计划卡、工具卡、流式正文、产物卡。顺序按事件 `seq`。`SKILL_LOADED` 收成一行「已加载技能：名称@version」，不要和计划步骤混成同一张卡。
- **步骤**（`steps`）：仍是 `StageStepNav` 的项目小阶段。上面一行继续显示「正在浏览：某阶段 · 仅切换视图，不推进项目」。不要把本次运行的计划步骤渲染进这个页签。

`sendViaProjectAgent` 在 `started` 时调用已有 `focusPane('cards')`。这会切换 pane 并滚动，和副驾出卡同一条路。用户正停在「步骤」页看动作时，发送后被带到本次运行；步骤选中状态还在（`v-show` 不卸载）。不自动改 `viewStageCode`，不改 `currentStage`。

意图卡字段（全部来自 payload，缺了就留空，不补句）：

- 标题：`title`（现为「意图判断」）
- 结论：`detail`
- 若 `needsClarification`：问题列表，编号；底部一句「回答前不会检索，也不会写文档」
- 若 `needsPlan`：步骤有序列表，状态点：待执行 / 执行中 / 已写出正文（正文 `TEXT_DELTA` 已开始则最后一步标执行中，终态成功则全部标已完成——这是展示推断，只根据已有事件，不发明新步骤）
- 直答：只显示结论「可以直接回答，不单独列计划」，不渲染空步骤

产物卡：标题、版本号、定档状态。正文预览若事件没有 content，显示「正文在产物版本中」，定档仍走左栏。不要在本次运行里再放一个 apply。

### 5.3 右栏对话

结构保持：用户气泡（仅当前会话内存里的 `lastTask`）+ 助手区。

助手区**不再**挂意图卡。意图 / 澄清选项 / 计划确认只出现在中间「本次运行」（`run-timeline.vue` → `intent-card.vue`），与左栏历史、中间回读共用同一份 run 事件。对话列只渲染 `AssistantTurn`（思考折叠摘要「思考」+ 限高正文）。点澄清选项仍走面板 `answerClarification` → `createProjectAgentRun`（发送文「已选：…。」），成功后 `focusPane('cards')`。

刷新或打开历史时：不伪造用户气泡。助手区继续只回放正文；意图卡、工具、计划仍在中间栏，因为它们在事件和版本表里。

思考区：

- `.msg .bubble` 的 `white-space: pre-wrap` 保留给用户纯文本气泡。
- `.assistant-turn`、`details.think`、`summary` 覆盖为 `white-space: normal`；summary 单行：`display: flex`，标签 `nowrap`，不把推理正文放进 summary。
- `.think-body` 保持限高滚动（现有 9.5em 可保留）。可访问名称仍是「思考」或「思考中」，但盒子宽度不得超过气泡。

加载：发送等待创建时继续 `AiLoadingState`「正在生成」。运行已创建后，加载态改读事件：没有 `TEXT_DELTA` 且未终态时，中间栏和对话都显示「正在生成」，不要再造「思考中 / 分析中」假步骤（`timeline-model.ts` 文件头已经禁止）。

### 5.3.1 任务链四段在现有三栏里怎么分（2026-09-30 补）

差距不在多堆卡片，而在意图 / 澄清 / 计划 / 执行不要糊成同一段对话。

| 段 | 挂哪一栏 | 事件依据 | 用户怎么点 |
|---|---|---|---|
| 意图 | 中间「本次运行」 | `STEP` `kind=INTENT` | 只读结论与 flags |
| 澄清 | 同一张意图卡下半 | `questions` → 可点选项；闸门 `AWAIT_USER` `CLARIFICATION` | 点选项 → `已选：…。` → 再 `createProjectAgentRun`；切 pane=`cards`；左栏历史随新 run 刷新 |
| 计划确认 | 同一张意图卡 | 未绑定动作且 `needsPlan` / `PLAN_CONFIRM` | 「开始执行」首行「按已确认计划执行」；「先改范围」回输入框。已绑定动作只出固定句 |
| 执行正文 | 中间时间线 + 右栏 `AssistantTurn` | `TEXT_DELTA` / 工具卡 | 不可点选；思考摘要一行「思考」 |

选项交互结构对齐收藏组件 **21st Question Tool**（id 12420）：字母徽章、选中态、已答/已结束禁用。不安装 shadcn 依赖，不换皮。

### 5.4 顶栏与加号

- 六阶段导航实现不动。徽章文案保持「当前进度」。点击只调用 `selectViewStage`。
- 能力包、模型、技能、工具只留在加号 `AiPromptInput` 槽。提交体里的 `toolIds` 只来自能力包目录。模型 id 只来自服务端返回且 `available` 的 `modelConfigId`。禁止把 Opus 4.5、Cursor Grok、GPT-5、Gemini 之类演示名写入请求。

---

## 6. 后端执行链（细步骤）

单轨：`org.ruoyi.ipd.agent` + `ProjectAgentController`。不要开第二套 run/document API。事件类型保持 9 个。意图用 `STEP.payload.kind`。

每一步都写：触发、输入、判定、写哪个事件、模型允许做什么、失败怎么收口。

### 6.1 领额度

- 触发：`ProjectAgentRunService.create` 在插入运行之前。
- 输入：进程内并发计数（`ProjectAgentRunExecutor.tryReserve`）。
- 判定：未超过上限才继续。
- 事件：不写。此时还没有 runId。
- 模型：不允许调用。
- 失败：抛 `RATE_LIMITED`「项目智能体并发运行已满，请稍后重试」。不得插入半条运行。

### 6.2 创建运行

- 触发：额度已领到，`insertRun`。
- 输入：会话 `IpdActor`、projectId、请求体。请求体里的 message 只用于当次 spec 和摘要。
- 判定：开关打开；项目对该人可见；`validateShape`；产品线若传了则属于该项目；幂等键同人同键同 `requestDigest` 则回放原 runId，同键不同 digest 则 `STATE_CONFLICT`；`plan()` 冻结包/模型/技能/工具/动作。动作不在包内、技能或工具不在包内、模型不可用：拒绝，不插入。
- 事件：不写（仍是 `PENDING`）。
- 模型：不允许。
- 失败：插入冲突则 `release` 额度，再按幂等键回放；仍没有行则 `STATE_CONFLICT`。当前源码是先 `planner.plan` 再 `tryReserve`，plan 抛错不会占额度。

### 6.3 CAS RUNNING

- 触发：执行器 `start`。
- 输入：运行行，期望前态 `PENDING`。
- 判定：CAS 成功才持有写事件的权利。
- 事件：成功后才写 `RUN_STARTED`。失败不写业务事件（已被取消时，取消路径已经写了 `RUN_FINISHED`）。
- 模型：不允许。
- 失败：`release()` 后返回。

### 6.4 RUN_STARTED

- 触发：CAS 成功。
- 输入：agentId、能力包 code/version、modelConfigId（字符串）、actionCode。
- 判定：无。这是快照，不是第二套配置。
- 事件：`RUN_STARTED`，字段如上。不要放 message。
- 模型：不允许。
- 失败：写事件失败按现有存储错误收口；不得带着「已开始」去调内核。

### 6.5 SKILL_LOADED

- 触发：spec 里每个已加载技能循环一次。
- 输入：`LoadedSkill` 的 name、version、sha256。正文已经在提示词里，事件不重复贴正文。
- 判定：技能列表在 `plan()` 时已确定。显式技能不可加载则创建阶段已失败；动作绑定技能加载不到会跳过（`ProjectAgentRunPlanner.loadSkills`），这里不会出现一条假的 SKILL_LOADED。
- 事件：`STEP`，`kind=SKILL_LOADED`。
- 模型：不允许。
- 失败：无技能则零条，合法。

### 6.6 意图判断

- 触发：技能步骤写完之后、`kernel.execute` 之前。
- 输入：`spec.message()`、`spec.actionCode()`、已加载技能正文。现有实现只看前两个。收紧规则见 6.10，仍然是确定性函数，禁止为了意图再打一次模型。
- 判定：`ProjectAgentIntent.decide`。同一输入在 `ProjectAgentPrompt.build` 里会再算一次；两处必须调用同一个函数、同一组规则，避免提示词和事件不一致。
- 事件：`STEP`，`kind=INTENT`，字段保持现在的 `title`、`detail`、`needsPlan`、`needsClarification`、`questions`、`steps`。`onStep` 的写入顺序保持「先 kind 再 putAll」；payload 不要自带 `kind` 键，否则会覆盖。
- 模型：此步不允许。判定结果随后写入系统提示，并写明「必须遵守，不得改口」。
- 失败：函数无 IO。message 空已当成需要澄清。不要因为判定抛错而把运行留在 RUNNING 不收口。

### 6.7 澄清则停

- 触发：`needsClarification=true`。
- 输入：INTENT 里的 `questions`。
- 判定：问题列表非空。空列表不算澄清，按直答，避免卡死。
- 事件：再写 `STEP` `kind=AWAIT_USER` `reason=CLARIFICATION`。状态 CAS `RUNNING → WAITING_APPROVAL`。不写 `RUN_FINISHED`（还不是终态），不写 `ERROR`。
- 模型：不调用 `kernel.execute`。因此不会出现 `TOOL_CALL`，也不会在成功路径写 ARTIFACT。
- 失败：CAS 失败（用户已取消）则走已有取消收口，不覆盖终态。用户稍后用新的 create 回答，见第 7 节。当前代码没有这一步，所以现在澄清仍会进入 6.9。下一期最小闭环只渲染，不接这条闸门；界面要如实显示「已判定需要澄清；执行闸门尚未接通」直到 6.7 落地，避免用户以为系统已经停住。

### 6.8 计划先展示

- 触发：`needsPlan=true` 且不需要澄清。
- 输入：`steps`。绑定动作时这些字符串必须等于技能「步骤」节的有序列表（6.10），不是用户句子切片。
- 判定：
  - 有 `actionCode`：步骤已是能力包动作的技能说明，展示即可，接着 6.9。人不在这里改步骤；要改就发新运行。
  - 无 `actionCode`：步骤来自用户原文里的多个动作，必须等人确认。CAS 到 `WAITING_APPROVAL`，`kind=AWAIT_USER` `reason=PLAN_CONFIRM`。确认不是新协议：客户端再 `POST` 一次，message 里带上「按已确认计划执行」以及步骤的原文复述（复述来自上一条事件，因为用户原文已不在库里）。服务端在新运行里重新 `decide`；若新 message 明确是确认且附带步骤清单，判定表应得到 `needsClarification=false` 且 steps 与附带清单一致。简单做法（推荐）：确认请求仍走 create，并带同一个 `actionCode`（若原本没有动作，则确认文本必须包含上一轮步骤的逐字拷贝，新一轮 `planSteps` 的输入就是这段拷贝——所以未绑定动作的计划确认会变成一次新运行，旧运行在确认请求被接受时由服务把旧运行收到 `CANCELLED` 或留在 `WAITING_APPROVAL` 由用户取消。推荐用户点确认时：旧运行 `cancel`，新运行 create。两个 runId，事件不改写。
- 模型：等待期间不允许。确认后的新运行才允许。
- 失败：等待超时沿用运行超时策略，收口 `FAILED` / `RUN_TIMEOUT`（`ProjectAgentErrorTexts` 已有文案「运行超时，已终止」）。不要在超时后补调工具。

当前代码没有确认闸门。计划确认放在后续，不在下一期最小闭环。

### 6.9 内核执行

- 触发：6.7 / 6.8 判定为可以执行。直答和「已绑定动作的计划」走这里。
- 输入：`ProjectAgentRunSpec`（含当次 message、技能正文、toolIds、模型）。
- 判定：`kernel == null` → `FAILED` + `KERNEL_ERROR`。`execute` 抛运行时异常同样 `KERNEL_ERROR`。工具名超出 `toolIds` → 装配失败，同一错误码（内核已有「exposes unselected tools」）。
- 事件：之后由内核回调写入，不在这里发明 TEXT。
- 模型：可以按系统提示回答。允许的工具只有本次 toolIds。禁止联网、禁止其他项目、禁止 Gate 结论（提示词第 2、3 条）。澄清/计划约束以 INTENT 为准。
- 失败：`onError` → `FAILED` + 对应错误码，唯一 `ERROR`。不得再写 `RUN_FINISHED`。

### 6.10 工具、文本、产物、终态

**工具调用**

- 触发：内核 `ToolCallStartEvent` → `onToolCall`。
- 输入：`toolCallId`、`toolName`。现有 payload 没有 arguments（`timeline-model` 会去猜 arguments，没有就是空摘要）。
- 判定：工具名必须在本次 toolIds。`project_knowledge_search` 只查运行绑定的 projectId，模型传入的别的项目 id 被忽略（`ProjectKnowledgeSearchToolTest`）。
- 事件：`TOOL_CALL`。结果：`TOOL_RESULT`（toolCallId、toolName、state）。命中资料再 `SOURCE`（项目内引用，不放外链）。
- 模型：只看见工具返回。检索为空就说未取得，不得编造。
- 失败：工具错误写入 `TOOL_RESULT` 的失败态，运行可以继续；内核自身断流才 `STREAM_ERROR`。取消探测发现 `CANCEL_REQUESTED` 时丢掉后续帧。

**文本增量**

- 触发：模型文本。
- 输入：缓冲，按字符数或时间间隔刷出（`TEXT_FLUSH_*`）。
- 判定：取消中不刷。
- 事件：`TEXT_DELTA`，payload 含文本。不含另外一份思维链库。
- 模型：正文里的 `<think>` 只是文本。前端拆，后端不解析。
- 失败：终态前 `flushText`，避免最后一段丢在缓冲里。

**产物**

- 触发：`finish(SUCCEEDED)` 且 `fullText` 非空，且 `artifactStore` 已装配。
- 输入：助手全文。标题：有动作则 `{actionCode} 产物`，否则「项目智能体产物」，截断 200。
- 判定：插入 `DRAFT`、`versionNo=1`。澄清停住的运行不会走到 SUCCEEDED，因此不会有产物。这是闸门，不是靠模型听话。
- 事件：`ARTIFACT`。现有字段 `artifactId`、`title`、`contentHash`、`version=1`。下一期点赞修复要增加 `versionId`（`version.getId()` 的十进制字符串）。不要用新事件类型。
- 模型：不直接写文档表。定档是人点按钮。
- 失败：插入失败只记日志，仍然写终态事件（现有注释：产物失败不得吞掉 `RUN_FINISHED`）。界面就没有产物卡，运行仍是成功。重试定档没有意义，只能重跑生成新产物。

**终态**

- 触发：完成、失败、取消。
- 判定：CAS 胜者写唯一终态。`SUCCEEDED`/`CANCELLED` → `RUN_FINISHED`（payload.status）。`FAILED` → `ERROR`（errorCode + `ProjectAgentErrorTexts`）。
- 模型：终态后 `subscription.dispose`，迟到回调进入已关闭句柄被丢弃。
- 失败：关回调抛错只记日志，不把终态改回去。额度在 handle 关闭时 `release`。

### 6.11 意图规则：以现有 `ProjectAgentIntent` 为基线，并收紧

现有 `decide`（`ProjectAgentIntent.java`）是对的方向：不调模型，澄清优先于计划，澄清时 `steps` 为空。它太粗，具体如下。

| 现有规则 | 误伤或缺口 | 例子 |
|---|---|---|
| `text.indexOf("还是")` 且前面至少 2 字、后面还能再有字 | 「还是」作「仍然」也会澄清，计划被清空 | 「我还是要做竞品的功能和价格」 |
| `length<=4` 即含糊 | 绑定动作后，短指令仍被当成没说清；同时「做一下」类套话靠正则，短但明确的「出报告」也会被拦 | 动作已是 C02，用户写「出报告」 |
| 已绑定动作且不澄清时，至少生成「按动作 X 完成本轮任务」，从而 `needsPlan=true` | 计划只有一句，和技能步骤无关 | C02 + 任意非含糊句子 |
| `planSteps` 按 `。；;\n、` 切句，句子里要有动词表；不够两句就按「功能、价格、渠道…」追加「核对×」 | 模型被提示按这些短语执行，覆盖技能正文里的检索→缺项→四维对比 | 用户写「分析竞品的功能和价格」会得到「核对功能」「核对价格」，而不是 `SKILL.md` 第 21–26 行的 6 步 |
| 动作已绑定但输入缺项 | 技能写明「无竞品名单则停止比较，只出缺项」。意图层若再强迫澄清，会阻断这条合法分支 | 只有定位、没有竞品名单 |
| 澄清只进提示词 | 模型仍可能调 `project_knowledge_search` 并在成功时落产物 | 任意 `needsClarification=true` |

收紧后的判定表。仍然一个纯函数，输入只有 message、actionCode、已加载技能正文（已在内存，不读盘第二次、不调模型）。能力包 JSON 没有步骤数组，**步骤权威是技能 Markdown 的「## 步骤」有序列表**，不是包结构，也不是模型。

| # | 条件（自上而下，先匹配先生效） | needsClarification | needsPlan | steps | 随后 |
|---|---|---|---|---|---|
| 1 | message 空白，或命中现有套话正则（看看 / 分析一下 / 做一下 / 处理一下 / 继续 / 你好 / 在吗），无论有没有动作 | 是。问题：「请说明要交付的结果，以及范围限定在本项目的哪一块。」有动作时追加：「动作 {code} 已选定，请补充这次要覆盖的范围。」 | 否 | 空 | 停在澄清 |
| 2 | 命中**选择型**「还是」：两侧各 2～12 字，且不是「还是要 / 还是继续 / 还是需要 / 还是先」 | 是。问题：「这句话里有未选定的方向。请指定其中一个后再执行。」把两侧原文放进问题，不要只说「有方向」 | 否 | 空 | 停在澄清 |
| 3 | 有 actionCode，且至少一个已加载技能能解析出「## 步骤」下的有序列表 | 否 | 是 | 该列表，最多 8 条，逐字来自技能，不加「核对功能」 | 展示后执行。提示词粘贴这份列表并写「不得增删改序」 |
| 4 | 有 actionCode，但技能没有「## 步骤」 | 否 | 是 | 一条：「按动作 {code} 与已加载技能执行，不另列计划。」 | 展示后执行 |
| 5 | 无动作，原文里能切出 ≥2 个含动词的分句 | 否 | 是 | 那些分句，最多 6 条。禁止再用 FACETS 补「核对×」 | 等人确认后再执行 |
| 6 | 其余（含「我还是要做竞品分析」、短但非套话、只有一个动词、技能内部的缺项） | 否 | 否 | 空 | 直接回答。缺项由技能自己停在比较并列出缺项 |

动词表和面表可以继续用于规则 5，不再用于规则 3。规则 2 的负例必须有单测：「我还是要做竞品的功能和价格」走规则 3 或 6，不走规则 2。正例：「做功能对比还是做价格对比」走规则 2。

不要把「是否缺少竞品名单」交给第二次模型。技能正文已经规定缺名单时的行为。若产品以后要对某个动作做**硬性**输入清单，把它写进该技能 frontmatter 的闭合字段（例如 `required-slots`），由这个函数做字符串包含判断，仍然不调模型。现在的 C02 技能没有这种字段，不要先发明。

提示词片段继续由 `ProjectAgentIntent.prompt` 生成。澄清：只许提问。计划：只许按列表。直答：禁止先写计划。事件 payload 的 steps 与提示词编号列表必须同源。

---

## 7. 人机交互合同

不新开会话协议，不新开 SSE。每一轮都是 `POST /api/v1/projects/{projectId}/agent-runs`。前端只增加 `ipdPost` 已有创建函数的调用参数，不增加 HTTP 动词。

**澄清怎么回传**

上一轮停在 `WAITING_APPROVAL`（闸门接通之后）。问题文本在那次 INTENT 事件里，刷新仍能读到。用户在澄清卡点选或填写。客户端 `cancel` 掉等待中的运行（已有 `POST .../cancel`），再用新的 idempotencyKey `create`。新 message 由用户回答构成，并带上问题的短句，例如：

```text
已选方向：功能对比。
动作：C02。交付范围：本项目已选的三家竞品，只做功能矩阵。
```

这是新的用户输入，服务端只存它的摘要。旧 message 不会被读出来拼接——库里没有原文。所以客户端必须把回答写完整，不能只传「第一个」。

闸门未接通之前，不要假装取消了一个并未暂停的内核。下一期只展示问题，回答方式暂时仍是用户自己再发一段话（现有输入框）。

**计划确认 / 改步骤**

- 已绑定动作：没有确认按钮。计划卡标明「步骤来自动作技能，本次按此执行」。用户要改范围：再发一次，新 message 写新范围；动作码仍由步骤页选中项决定。
- 未绑定动作：计划卡有「开始执行」和「先改范围」。「开始执行」= cancel 等待运行 + create，message 逐字包含事件里的 steps。「先改范围」= 聚焦现有输入框，不调内核。
- 不提供「在卡片里增删步骤再写回同一 run」。事件表是追加日志，不更新旧 payload。改步骤就是新运行。

**取消、失败、重试**

- 取消：已有两阶段。PENDING 直接 CANCELLED。RUNNING 或 WAITING_APPROVAL 先 `CANCEL_REQUESTED`。本机有句柄则收到 CANCELLED + `RUN_FINISHED`。本机无句柄停在 `CANCEL_REQUESTED`，不越权代写终态（`LifecycleTest.cancelWithoutLocalHandleStaysRequested`）。
- 失败：展示 `ERROR.message`，按钮「重试」= 使用保留的幂等键再 create（同一正文）或在用户改字后换新键。终态运行不能 resume。
- 重试成功是新的 runId。左栏历史里两条都在（列表落地之后）。

---

## 8. 历史、产物、点赞、定档

### 8.1 列表

新增只读接口，仍是 `code=0` / `message`，id 全字符串，只用 `ipdGet`：

`GET /api/v1/projects/{projectId}/agent-runs`

查询参数：`q`（可空）、`status`（可空，仅 7 个 `AgentRunStatus`）、`actionCode`（可空）、`cursor`（上一页最后的 runId，字符串）、`limit`（默认 20，最大 50）。

权限：与 `requireOwnRun` 相同。先 `access.requireVisible`，再限制 `tenantId` + `personId` = 当前人。别人的运行当作不存在。不返回提示词、不返回 `inputDigest` 的十六进制（避免被当成正文）、不返回系统提示和技能正文。

每条字段：

| 字段 | 来源 | 说明 |
|---|---|---|
| runId | 雪花转字符串 | |
| status | 运行行 | |
| actionCode | 运行行，可空 | 搜索命中动作码或动作中文名（中文名来自前端已有动作目录，接口可以只返 code） |
| capabilityPackCode / version | 运行行 | 展示用 |
| createdAt / finishedAt | 运行行 | ISO 字符串，与现有 `ProjectAgentViews` 一致 |
| inputChars | 运行行 | 只说明当时输入多长，不是内容 |
| artifactTitles | 版本表 title | 可多条 |
| artifactExcerpt | 版本表 content 的前 80 字 | 只为搜索预览。这是产物正文，不是提示词 |

`q` 的匹配范围：`actionCode`、`status`、产物 `title`、产物 `content`。实现用 SQL `LIKE` 参数绑定，禁止把 `q` 拼进原文检索 `input`（列不存在也不许加）。空 `q` 返回本人该项目最近运行。

没有这个接口时，不要用前端本地缓存冒充历史。

打开一条：已有 `GET /api/v1/agent-runs/{runId}` 与 `GET /api/v1/agent-runs/{runId}/events?afterSeq=`。事件接口已拒绝他人（测试期望 `NOT_FOUND`）。

### 8.2 点赞

`PUT /api/v1/ai-feedback/{targetType}/{targetId}` 不改语义。

- `RUN_MESSAGE`：`targetId` 继续是 runId。
- `ARTIFACT_VERSION`：`targetId` 必须是版本行雪花 id。修复：`persistArtifactDraft` 和 `onArtifact` 的 payload 增加 `versionId` 字符串。`timeline-model` 的 artifact 条目增加 `versionId`。`FeedbackBar` 的 `:target-id` 改为 `versionId`；没有 `versionId` 的旧事件不渲染产物点赞，避免再打逻辑 id。apply 路径仍用逻辑 `artifactId`，与点赞主键分开。apply 响应里已有 `ProjectAgentViews.id(version.getId())`，前端定档回执可以顺带记住它，但事件回放仍以 payload 为准。

### 8.3 定档与知识库

保持 `applyArtifact`：

- 文档状态库内码 `GENERATED`，用户可见「待审核」。
- `indexStatus=NOT_INDEXED`。
- 不从 apply 调用 `embedAsync`。索引进知识库只发生在既有审核通过路径。
- 不把产物标成 `READY`。
- 不另开第二条文档写入轨。重复 apply 且已有 `documentId` 时返回同一回执（现有分支已这样）。

左栏文案保持「定档回填本项目文档链。知识库须审核后入库，不会标成已索引。」

---

## 9. 与业务的边界

- **六阶段。** `Project.currentStage` 只由项目流程改。顶栏轨道和 AI 内浏览导航都只表示实况或浏览位置。智能体运行、定档、点赞都不写阶段。
- **Gate 双签。** 系统提示已写「不输出 Gate 评审通过或不通过的结论」。技能 C02 写「判定由人」「禁止给出 Gate 签署结论」。预审卡、结论卡仍属于副驾 classic 的卡片通道，不塞进项目智能体事件。智能体可以列出缺项，不能替代评审人。
- **动作清单。** 69 动作里哪些可交给智能体，由能力包 `actionCodes` 和 `ipd_action_skill_map` 决定。不在包内的动作码，前端不提交，后端 `plan()` 拒绝。
- **能力包。** 技能来自 classpath `ipd-skills/<name>/SKILL.md`，要过清单 sha256。`AgentScopeProjectAgentKernel` 关掉动态技能，避免模型再 `load_skill` 换一套正文。MCP 市场 id 不是 `toolIds`。
- **项目权限。** 创建、详情、事件、取消、定档、反馈都走当前人 + 项目可见性。检索工具忽略模型自报的 projectId。
- **不能上网、不能跨项目。** 内核不装 `web_fetch` / `web_search`。资料仅本项目已审核内容加本轮输入。

---

## 10. 分期落地

### 10.1 下一期必做（最小闭环）

只做已经写进事件、却没被人看见的部分，加上发送后的导航和思考区高度。不接澄清闸门、不加列表接口、不改点赞主键。

| 项 | 改哪里 | 测试 | 用户可见 |
|---|---|---|---|
| 意图卡 | `timeline-model.ts`：`STEP` 在 `kind===INTENT` 时保留 `needsPlan`、`needsClarification`、`questions`、`steps`（questions/steps 必须是字符串数组，否则空数组）。`run-timeline.vue` 按第 5.2 节画卡，不再只有一行 strong。 | `timeline-model.test.ts` 增加一条 INTENT 夹具。`run-timeline.test.ts` 断言问题文本和步骤文本出现。 | 中间「本次运行」能看见「范围还没定」或计划步骤，而不只是「意图判断」。 |
| 意图卡只挂中间 | 对话列去掉意图卡；澄清/计划只在 `ipd-ai-run-readout`。选项交互对齐 21st Question Tool（A/B 徽章、选中、已答禁用），点选仍走 `answerClarification`。 | `ai-assistant.test.ts`：意图卡在 readout；对话无卡；点选项二次 create 且 message 以「已选：」起头。 | 任务链四段分栏可见，不糊成一段对话。 |
| 发送即切到本次运行 | `sendViaProjectAgent` 的 `started` 调用 `focusPane('cards')`。 | `ai-assistant.test.ts`：先 `setPane('steps')` 再模拟 started，pane 为 `cards`。 | 用户停在步骤页发送后，中间栏变成「本次运行」。步骤页内容还在，顶栏阶段不变。 |
| 思考区高度 | `assistant-turn.vue`：summary 单行；`.think` 覆盖父级 `pre-wrap`；正文只在 `.think-body`。 | `assistant-turn.test.ts`：summary 的文本仍是「思考」，`.think-body` 有 max-height。像素 633 不必写入断言，避免环境抖动。 | 折叠时只有一行「思考」，展开后正文在限高滚动区。 |
| 后端契约测试同步 | `ProjectAgentRunServiceCreateTest`：`containsExactly("RUN_STARTED","STEP")` 改为含第二条 STEP（SKILL_LOADED 与 INTENT 的顺序：先技能后意图）。无技能的用例则是 `RUN_STARTED` + INTENT 一步。`LifecycleTest`：首包 seq 从 `1,2` 改为包含 INTENT 之后的 seq；取消运行中的 4 条、内核失败的 3 条各加一条 STEP；增量 `nextSeq` 和后续 3、4、5、6 全部顺延。只改期望，不改状态机。 | 上述两个测试类绿。 | 无。这是防止下一期后端一跑就红。 |

若 Create 用例的消息仍是「请对本项目做竞品分析：功能、价格、渠道、技术路线」，按**现有** `decide` 会 `needsPlan=true`（「还是」不在句中，长度够，含功能/价格等面）。加上 INTENT 后事件是 `RUN_STARTED`、`SKILL_LOADED` 的 STEP、INTENT 的 STEP。断言要检查第二条 STEP 的 payload.kind 为 `SKILL_LOADED`、第三条为 `INTENT`，不要只数条数。

闸门未接通时，意图卡上若 `needsClarification` 为真，显示固定句：「判定为先澄清。当前版本仍会继续生成，执行前切断尚未接通。」这句是诚实提示，不是新业务状态。闸门接通后删掉这句。

### 10.2 后续

| 项 | 验收 |
|---|---|
| 澄清与自由计划的执行闸门 | `ProjectAgentIntent` 收紧（6.11）有单测：负例「我还是要做…」、正例选择型「还是」、绑定 C02 时 steps 等于 `competitor-analysis-ipd` 的「步骤」节、不再出现「核对功能」。`start` 在澄清或自由计划时不调用内核，状态 `WAITING_APPROVAL`，事件有 `kind=AWAIT_USER`。测试：澄清运行的事件类型里没有 `TOOL_CALL` 和 `ARTIFACT`。用户可见：问题卡出现后左侧无产物，中间无工具卡，直到再次发送。 |
| 历史搜索 | 第 8.1 节接口。单测：他人 run 不可见；`q` 能命中产物标题和正文；响应 JSON 不含 `inputDigest` 和请求 message。前端左栏替换「没有列表接口」文案。`ai-assistant.test.ts` 第 954 行改预期。用户可见：换项目再回来，能按「C02」或产物标题点开旧运行，对话不出现旧提问原文。 |
| 产物版本点赞 | 事件含 `versionId`。反馈 PUT 的 targetId 等于版本行 id 时成功；仍传逻辑 artifactId 时维持 `STATE_CONFLICT`。`run-timeline.test.ts` 断言点赞组件拿到的是 `versionId`。用户可见：给产物点赞不再报版本不存在。 |
| 计划确认（仅未绑定动作） | 第 7 节的「开始执行」产生新 run，旧 run 取消。用户可见：未选动作的多步要求会先停住；已选 C02 则只展示技能步骤并继续。 |

每一期做完才勾用户可见结果。单测绿但没有上述界面结果，不算闭环。

---

## 11. 明确不做的事

- 不存用户提示词原文，不加可逆的原文列，不把 `inputDigest` 当搜索语料。
- 不把定档说成知识库已入库，不从 apply 调 `embedAsync`，不写 `READY`。
- 不把 MCP 市场的数字 id 放进 `toolIds`，不和 `project_knowledge_search` 混在一次提交里。
- 不改六阶段实况，不改 `layouts/ipd.vue` 轨道的 `currentStage` 展示，不让浏览点击推进项目。
- 不新开第二套智能体 API，不开 `chat.kernel.agentscope.enabled`，不给聊天内核装 Toolkit，不让前端改走另一套 run/document。
- 不新增 `AgentEventType` 枚举值。意图、等待、技能加载都用 `STEP.kind`。
- 不开 AgentScope 原生 Plan Mode，不写 `plans/PLAN.md`。
- 不安装新的前端视觉依赖，不建一套平行的 `/components/ui`。
- 不让智能体签署 Gate，不代写评审结论。
- 本次不改 Java、不改 Vue、不改上述会红的测试。测试同步写在 10.1，留给下一期实现时一起改。
