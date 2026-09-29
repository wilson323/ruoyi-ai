# AgentScope 接入 ADR 与 Agent Contract 六问（W0 · 看板卡 61217664）

> 日期：2026-09-28 · 作者：AgentScope PoC 接入 W0 阶段
> 范围：`poc/agentscope-kernel` worktree 已 merge main（merge commit `d8231596`，分叉计数 0/7），本文在 merge 后事实上产出。
> 事实源分级（沿用 agentscope-harness skill 口径）：
> - ✅ **已实证** = 本仓代码存在且本次 merge 后实测跑过（命令与 EXIT 码见文末附录）
> - 🔍 **现查存在** = 本次已读源码确认类/方法存在，但未跑其行为
> - 📄 **文档级** = 出自 `java.agentscope.io` 官方文档，本仓未验证，接线前必须 PoC 实证
> 引用纪律：一律「文件路径 + 类名/方法名/键名」，不写行号；未实证的能力不写「已实现」。

---

## 〇、结论速览（owner 决策要点）

1. **单轨原则**：自研 harness（`org.ruoyi.service.coding.harness`，17 子包）与 AgentScope 禁止并行演进。本次 ADR 对 17 子包逐一落「替换/包装/保留」。
2. **替换面收敛为最小**：只有 `loop`（Agent Loop 执行核）与 `modelruntime`（模型装配/路由）进入替换/包装射程；权限三态、副作用恢复、计划版本、预算、上下文压缩、审批、日志脱敏七大治理资产**全部保留**。
3. **W1 的真正工作不是换内核，是换身份与门**：`PocSseController` 的 `userId` 请求参数直传必须改 Sa-Token 可信会话身份；知识库检索必须过 `KnowledgeAccessGate`（B0 判据：`owned(create_by) || share=1`）。现有 5 个 SSE/WS 端点/契约全部不动前端，后端内核替换在服务层内部完成。

---

## 一、Agent Contract 六问（对照自研 harness 既有实现逐问答）

> 六问来自 `.claude/skills/agentscope-harness/references/engineering-contracts.md`（三份工程契约：任务/信息/行动）。
> 自研侧代码根：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness/`（main 254 个 `.java`，零 `io.agentscope` 引用，🔍 本次现查）。
> AgentScope 侧代码根：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/`（PoC 分支，✅ G1–G5 实证）。

### 问 1：身份如何建立与传递？

| 维度 | 内容 |
|---|---|
| **自研现状** 🔍 | 身份三元组在 `runtime/HarnessRunRequest.java`：`record HarnessRunRequest(HarnessOwner owner, String sessionId, String runId)`；归属主体 `model/HarnessOwner.java`：`record HarnessOwner(String tenantId, Long userId)`，构造即校验（tenantId 非空、userId 正数，fail-fast）。并发收口在 `runtime/HarnessSessionGate.java` 的 `withSession(HarnessOwner, String sessionId, Supplier)`，按 `owner.hashCode()*31 + sessionId.hashCode()` 做同会话串行化。 |
| **AgentScope 对应机制** ✅/📄 | 原生身份只有二元：`RuntimeContext.builder().userId(uid).sessionId(sid)`（✅ `PocSseController`/`KernelScopeKey` 实证）。四维（project/user/agent/session）由 PoC 的 `KernelScopeKey.of(projectId, userId, agentId, sessionId)` 折叠：复合 userId=`p{projectId}:u{userId}`、复合 sessionId=`a{agentId}:s{sessionId}`、最终 slotId 落 `MysqlAgentStateStore` 单列；fail-closed 拒 `':'` 与 `..`；userId 空时坍缩 `__anon__` 命名空间。tenant 维原生缺失，只能折入 project 段（📄 官方无更细方案）。 |
| **W 波次接线点** | W1：`PocSseController.stream` 的 `@RequestParam userId` 删除，改从 Sa-Token 会话取可信 userId（落点清单见第三节）；`KernelScopeKey` 是唯一收口（C2/C3 门禁已锁，`harness-contract-check.sh`）。W2+：自研 `HarnessOwner(tenantId,userId)` → `KernelScopeKey` userId 段的映射器做一个适配函数，禁止散落第二处转换。 |

### 问 2：权限如何裁决（三态）？

| 维度 | 内容 |
|---|---|
| **自研现状** 🔍 | `tool/PolicyDecision.java`：`enum PolicyDecision { ALLOW, ASK, DENY }` 三态；契约面 `tool/ToolPolicyContract.java`；ASK 态的人工闭环由 `approval/` 子包承接（18 文件：`ApprovalDecision`、`ApprovalClaimReceipt`、`ApprovalExpiredException` 等，claim→裁决→回执全生命周期）。 |
| **AgentScope 对应机制** 📄 | 官方 permission-system（`/v2/zh/docs/building-blocks/permission-system`）与 workspace `tools.json` 工具白名单均为 📄 文档级，本仓未实证；PoC 未接任何工具（G1–G5 范围是模型/状态/流式/RAG，无 tool 调用路径）。 |
| **W 波次接线点** | W3（预计工具波次）：AgentScope 工具执行的外围包一层适配器——裁决入口仍是自研 `PolicyDecision`，ALLOW 放行进 AgentScope 工具协议、DENY 直接拒、ASK 走 `approval/` 流程后回注。**禁止**在 AgentScope 侧再造一套白名单语义。 |

### 问 3：副作用如何记账与恢复？

| 维度 | 内容 |
|---|---|
| **自研现状** 🔍 | `recovery/ToolEffectLedgerReconciler.java`：`updateAtomically(HarnessRunState, now, ...)` 把工具效果账本与消息账本原子对账（内部 `AssistantIdentity`/`ReceiptIdentity`/`Observation` 三级比对）；`recovery/UncertainToolEffectGuard.java`：`firstFinding(run)` 找出不确定效果、`requiresAdjudication(effect)` 判需仲裁、`suspend(run, reasonCode, now)` 挂起 run——「上次到底发生没有」的不确定态显式建模，不是简单重试。 |
| **AgentScope 对应机制** 📄/✅ | AgentScope 的 `AgentStateStore`（✅ `MysqlAgentStateStore` 实证）只管会话状态快照，**没有工具效果账本与不确定态仲裁**的概念（📄 官方 going-to-production 文档亦无对应物）。 |
| **W 波次接线点** | 保留资产，W3 起在工具执行外围包住 AgentScope：每次工具调用前后写自研账本，AgentScope 崩溃恢复时先过 `ToolEffectLedgerReconciler` 对账再续跑。这是「最难重建的资产」（skill 对照表原话），任何波次不得删除。 |

### 问 4：计划如何版本化？

| 维度 | 内容 |
|---|---|
| **自研现状** 🔍 | `plan/PlanAggregate.java`：record 聚合根，`CURRENT_SCHEMA_VERSION=2`；每次变更 `replacePlan/requestRevision/startStep/blockStep/failStep/retryStep/skipStep` 均要求 `expectedRevision` 乐观锁，失配抛 `plan/StalePlanRevisionException`；`canonicalHash()` 委托 `plan/CanonicalPlanHasher.java`——length-prefixed 定长编码（FORMAT_V1/V2 前缀区版本化，与 JSON mapper 配置无关）+ SHA-256，覆盖 revision/mode/steps/feedback/evidence/receipts 全字段。 |
| **AgentScope 对应机制** 📄 | Builder 选项 `.enablePlanMode()` / `.planFileDirectory("plans")`：只读探索→写 `plans/PLAN.md`→HITL 确认→执行（📄 Builder 速查，未实证）。无版本号、无乐观锁、无 canonical hash 的文档证据。 |
| **W 波次接线点** | W4（预计规划波次）：若启用 AgentScope Plan Mode，`PLAN.md` 的每次落盘必须同步过自研 `PlanAggregate.replacePlan`（吃 revision + canonicalHash），Plan Mode 文件只是视图、`PlanAggregate` 仍是权威状态机。 |

### 问 5：预算如何消耗与熔断？

| 维度 | 内容 |
|---|---|
| **自研现状** 🔍 | `model/HarnessBudget.java`：`record HarnessBudget(maxIterations, maxToolCalls, maxInputTokens, maxOutputTokens, maxWallTimeMillis)`（构造校验非负；iterations/wall-time 遗留字段恒 0 保 wire 兼容）。裁决在 `app/HarnessBudgetPolicy.java`：`enforce(requested)` 夹逼上限、`forFollowUp(inherited)` 继承续跑预算、`secureDefaults()` 安全默认；消费状态落 `model/HarnessRunState`（🔍 grep 消费点：`app/CodingHarnessApplicationService`、`app/CreateHarnessRunCommand`）。 |
| **AgentScope 对应机制** 📄 | 仅 `.maxContextTokens(int)`（MEMORY 注入预算，📄）。多维预算（步数/时长/token 双向/工具次数）无文档级对应物；流式侧 ✅ 实证的 `streamEvents` 事件流（`MODEL_CALL_START`/`TextBlockDeltaEvent` 等）可作为 token 消耗的计量信号源，但计量→累计→熔断逻辑本仓未实现也未实证。 |
| **W 波次接线点** | W2（模型波次）：保留 `HarnessBudgetPolicy` 为唯一裁决点；新增一个从 AgentScope 事件流累计 token 的计量器（新代码，需 PoC 实证 `AgentEvent` 是否携带 usage 字段——目前只见类型名，未见 usage，实证前不得声称可计量）。 |

### 问 6：上下文如何压缩？

| 维度 | 内容 |
|---|---|
| **自研现状** 🔍 | `context/ContextEngine.java`：`project(state, budget)` 投影活动窗口、`compact(state, budget, ...)` 压缩（内部 `MessageGroup`/`Grouping`/`Cut` 三段算法）；预算面 `context/ContextTokenBudget.java`（`reservedTokens()`/`usableInputTokens()`）；钉住面 `context/ContextPins.java`；状态面 `context/ContextState.java`（`resetCompactionCircuit()` 压缩熔断防循环）。 |
| **AgentScope 对应机制** 📄 | `.compaction(CompactionConfig.builder().triggerMessages(30).keepMessages(10).build())` 触发式压缩 + `.toolResultEviction(...)` 大结果落盘占位 + workspace `memory/`/`MEMORY.md` 长期记忆（全部 📄 未实证）。语义与自研差异明显：AgentScope 按消息条数触发，自研按 token 预算 + pins + 熔断。 |
| **W 波次接线点** | W5+（长会话波次）：先 PoC 实证 `CompactionConfig` 行为（何时触发、保留什么、压缩后状态可否回读），再决定「包装」（AgentScope 管条数触发、自研 `ContextEngine` 管 token 预算与 pins）或「保留」（完全自研，AgentScope 侧不开 compaction）。**W1 不动上下文**。 |

---

## 二、自研 harness 能力 ADR 表（17 子包逐一「替换/包装/保留」）

> 子包实名来自 `ls ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness/`（🔍 2026-09-28 现查，共 17 个）。
> 规模排序（skill reference 现查口径）：tool 48 / model 38 / loop 35 / plan 28 / approval 18 / artifact 15 / context 15 / app 8 / journal 7 / recovery 7 / runtime 7 / modelruntime 6 / prompt 6 / store 6 / event 5 / skill 4 / config 1。
> 判定原则：**执行路径（Agent Loop、模型装配）才替换；治理资产（权限/审批/恢复/计划/预算/上下文/日志）保留；边界适配包装。**

| # | 子包（实名为准） | 处置 | 一句理由 |
|---|---|---|---|
| 1 | `app/`（`CodingHarnessApplicationService`、`HarnessBudgetPolicy`、`CreateHarnessRunCommand` 等 8 文件） | **保留** | 对外应用门面与命令对象，内核替换发生在其内部委托，门面契约不动即前端/调用方零感知。 |
| 2 | `approval/`（`ApprovalDecision`、`ApprovalClaimReceipt`、`ApprovalExpiredException` 等 18 文件） | **保留** | ASK 态人工闭环（claim→裁决→回执）是自研独有治理资产，AgentScope 无对应实证物（问 2）。 |
| 3 | `artifact/`（`ArtifactManifestEntry`、`ArtifactScope`、`ArtifactLimitExceededException` 等 15 文件） | **保留** | 产物清单/限额/校验生命周期与 AgentScope workspace 文件是两码事（skill 对照表明示 Workspace 六区在本仓未见对应包）。 |
| 4 | `config/`（`CodingHarnessConfiguration`） | **保留** | Spring 装配点，W1 起在此组装 AgentScope `HarnessAgent` bean，配置职责本身不替换。 |
| 5 | `context/`（`ContextEngine`、`ContextTokenBudget`、`ContextPins`、`ContextState` 等 15 文件） | **保留** | token 预算 + pins + 压缩熔断的成熟管线；AgentScope `CompactionConfig` 为 📄 未实证且语义更弱（问 6），W5+ 再评估是否局部包装。 |
| 6 | `event/`（`HarnessEventHub`、`HarnessEventOutboxService`、`HarnessModelLifecycleIntegrity` 等 5 文件） | **保留** | SSE/WS 发布与 outbox 完整性校验属于投递层，与推理内核正交，换内核反而依赖它保兼容。 |
| 7 | `journal/`（`FileRunJournalProjector`、`JournalSecretRedactor`、`StructuredContextSnapshotFactory` 等 7 文件） | **保留** | 事件投影 + 密钥脱敏是合规资产，AgentScope 会话日志（workspace `sessions/*.jsonl`，📄）不覆盖脱敏语义。 |
| 8 | `loop/`（`DurableHarnessRunProcessor`、`HarnessDeltaEventPublisher`、`CheckpointCrossingToolProjector` 等 35 文件 + model/protocol 内包） | **替换**（分波次） | Agent Loop 五步（Prepare→Model→Act→Observe→Verify）正是 `io.agentscope.harness.agent.HarnessAgent` 所承载（✅ PoC call/streamEvents 已实证最小环）；W1–W8 逐波把 run 处理器内核让位给 AgentScope，`DurableHarnessRunProcessor` 的持久化外壳在过渡期保留为包装层。 |
| 9 | `model/`（`HarnessBudget`、`HarnessRunState`、`HarnessEvent`、`HarnessApproval` 等 38 文件） | **保留** | 业务 run 状态与 AgentScope `AgentState` 是「三表示」中的两种表示（skill 契约二 §2.5：Event Log / Snapshot / Checkpoint 不能互替），`HarnessRunState` 仍是权威业务视图。 |
| 10 | `modelruntime/`（`HarnessChatModelFactory`、`RuoYiHarnessChatModelFactory`、`HarnessModelRouter`、`HarnessModelRegistry` 等 6 文件） | **包装** | 模型装配面收敛：`ModelRegistry.resolve("provider:model")`（✅ PoC 实证 MiniMax 路由）替换工厂内部实现，`HarnessModelRouter` 的选型/降级策略接口保留为门面。 |
| 11 | `plan/`（`PlanAggregate`、`CanonicalPlanHasher`、`StalePlanRevisionException`、`ExecutionEvidence` 等 28 文件） | **保留** | revision 乐观锁 + SHA-256 canonical hash 是单轨最强版本化资产（问 4）；AgentScope Plan Mode 📄 且无版本语义，只能当视图。 |
| 12 | `prompt/`（`DefaultHarnessPromptAssembler`、`HarnessPromptBundle`、`ProjectInstructionLoader` 等 6 文件） | **保留** | 系统提示组装（含项目指令加载）是业务资产；AgentScope `.sysPrompt(...)` 与 workspace `AGENTS.md` 只是注入出口（✅ sysPrompt 已实证），Assembler 产物喂给它即可。 |
| 13 | `recovery/`（`ToolEffectLedgerReconciler`、`UncertainToolEffectGuard`、`HarnessStartupRecovery` 等 7 文件） | **保留** | 副作用账本原子对账 + 不确定态挂起是本仓最难重建资产（问 3），AgentScope 无对应物，任何波次不得删。 |
| 14 | `runtime/`（`HarnessScheduler`、`HarnessSessionGate`、`HarnessActiveTurnRegistry` 等 7 文件） | **保留** | 调度/会话串行门/活跃 turn 登记与内核正交；AgentScope 侧 per-(userId,sessionId) 串行化语义（✅ skill 记载）与 `HarnessSessionGate` 是同层两实现，以自研门为唯一入口防双门竞态。 |
| 15 | `skill/`（`HarnessSkillCatalog`、`HarnessSkillDefinition`、`HarnessSkillTools` 等 4 文件） | **包装** | skill 对照表明示这是「最可能的缺口」：AgentScope workspace `skills/` + 四层同名优先级（📄）更完整；中期以 `HarnessSkillCatalog` 为门面、底层逐步对齐 AgentScope 目录形态。 |
| 16 | `store/`（`FileHarnessStore`、`HarnessOptimisticLockException`、`HarnessRunScanPage` 等 6 文件） | **保留** | 自研 run 持久层与乐观锁；AgentScope `AgentStateStore`（✅ Mysql 实证）只存会话状态，两者不同物，见第 9 条「三表示」口径。 |
| 17 | `tool/`（`PolicyDecision`、`ToolBatchPlanner`、`ToolDescriptor`、`ToolInvocation` 等 48 文件） | **保留为主，执行器局部替换** | 三态裁决与批计划是治理资产全保留；其中工具执行协议部分在 W3 若被 AgentScope 工具接口接管，则仅对该执行器做包装（裁决前置，见问 2）。 |

> 汇总：**替换 1（loop）/ 包装 2（modelruntime、skill）/ 保留 14**。替换面刻意最小——与 skill 对照表「先审计再引入」纪律一致。

---

## 三、W1 接线方案（SSE/WS 兼容矩阵 + 身份接线点 + 知识库门）

### 3.1 SSE/WS 兼容矩阵（5 个现态端点/契约 × 换内核影响）

| # | 端点/契约 | 现态实现（证据） | 身份机制 | 换 AgentScope 内核影响 |
|---|---|---|---|---|
| 1 | `GET /api/v1/ai-copilot/chat/stream`（IPD AI 副驾 SSE 四帧 meta/delta/done/error） | `ruoyi-modules/ruoyi-ipd/.../controller/AiCopilotController.java` 的 `stream(...)` → `SseEmitter`，`SSE_EXECUTOR` 异步 `pushChunks`；前端契约 `ruoyi-ipd-web/apps/web-antd/src/api/ipd/ai-copilot.ts`（fetch + ReadableStream 自解析，因 EventSource 无法带自定义 header） | `IpdPermission.requireInternal()`（`.../ipd/security/IpdPermission.java`）→ `record IpdActor(Long id, String name, String role, Long groupId)`（ipd 域 Sa-Token） | **前端零改动**。替换发生在 `AiCopilotService.chatStream` 内部：chunk 生产源从现实现切 `HarnessAgent.streamEvents`，出口仍按 meta/delta/done/error 四帧映射（AgentScope `TextBlockDeltaEvent.getDelta()` → delta 帧，✅ PoC `PocSseController.sendEvent` 已验证此映射可行）。 |
| 2 | `GET /api/v1/resource/sse`（IPD 通知 SSE）+ 平台 `/resource/sse` | `ruoyi-modules/ruoyi-ipd/.../controller/IpdSseController.java`（token 走 URL query `Authorization`，sa-token 陷阱：必须 `getLoginIdByToken(tokenValue)` 查 dao，`isLogin(String)` 永假）；平台侧 `ruoyi-common/ruoyi-common-sse` 的 `SseController`（默认 sa-token，路径不冲突） | ipd 域 JWT（query 传 token） | **不受内核替换影响**。这是通知推送通道（notify store 消费），与推理内核无关；仅当 W 波次把「AI 进度通知」也走此通道时，发布端换 `HarnessDeltaEventPublisher`（自研 `event/` 子包，保留处置）即可。 |
| 3 | `/chat/ws`（小程序对话 WS） | `ruoyi-modules/ruoyi-chat/.../websocket/chat/MpChatWebSocketConfig.java` 注册 + `MpChatHandshakeInterceptor.java` 握手验签 + `MpChatWebSocketHandler.java` 消费 | 握手期 `StpUtil.getStpLogic().getLoginIdByTokenNotThinkFreeze(token)` + `getTokenSessionByToken(token, false)` 取 `LoginHelper.LOGIN_USER_KEY` 的 `LoginUser`，双写 session attributes：`MpChatHandshakeInterceptor.USER_ID_KEY`（键名 `mpChatUserId`）与 `LOGIN_USER_KEY`；消息线程 `session.getAttributes().get(USER_ID_KEY)` 取 Long userId | **WS 协议帧不动**。内核替换在 handler 的对话处理内层；外层已接 `knowledgeAccessGate.checkRetrievalAccess(kid, userId)`（B0 合入，双参显式身份变体），换内核后此门调用点原样保留且必须前置到任何 RAG 检索之前。 |
| 4 | `/resource/websocket`（IPD 业务通知 WS，`ipd.websocket.enabled` 默认 false） | `ruoyi-modules/ruoyi-ipd/.../websocket/IpdWebSocketConfig.java`：`IpdHandshakeInterceptor` 走 ipd 域 Sa-Token（loginType="ipd"），复用平台 `PlusWebSocketHandler` 从 `session.attributes.loginUser` 取 `LoginUser`（userId=persons.id, userType="ipd"）挂 `WebSocketSessionHolder` | ipd 域 Sa-Token（握手 attributes） | **不受内核替换影响**。通知通道同 #2；与 #3 双通道由前端 `ruoyi-ipd-web/apps/web-antd/src/store/notify.ts` 消费（`useSseMessage` + `useWebSocketMessage` 双 watch）。 |
| 5 | `GET /poc/kernel/chat/stream`（PoC 样例 SSE，事件 scope/text_delta/event/error） | PoC 分支 `ruoyi-modules/ruoyi-chat/.../poc/kernel/PocSseController.java`：`stream(text, projectId, userId, agentId, sessionId)` 全 query 参数，`KernelScopeKey.of(...)` 收口后 `streamEvents(msg, ctx).subscribe(三参)` | **无鉴权**：userId 等全由请求参数直传（PoC 样例定位） | **W1 必改项**（见 3.2）。此控制器不接前端契约，正式接线不沿用其路由 `/poc/kernel/**`，只复用其「四维收口 + subscribe 三参 + text_delta 映射」模式。 |

**矩阵结论**：5 端点中 #2/#4 是纯通知通道与内核无关；#1/#3 是 W1 回归范围（对话链路），前端契约全部保持不动，替换收敛在服务层内部；#5 是 PoC 样例，W1 改造后仅作回归参照。

### 3.2 会话身份接线点清单（PocSseController userId → Sa-Token）

1. **删参数**：`PocSseController.stream` 签名中 `@RequestParam userId`（含 `defaultValue = "U1"`）移除——客户端自报身份是越权根因，W1 起禁止默认值兜底匿名。
2. **HTTP 线程取身份（platform 域，ruoyi-chat 模块内）**：对齐 chat 域现状用 `LoginHelper.getLoginUser().getUserId()`（`ruoyi-common/ruoyi-common-satoken` 的 `org.ruoyi.common.satoken.utils.LoginHelper`，与 `MpChatHandshakeInterceptor` 同源）；未登录直接抛 `NotLoginException`，SSE 出口按 `AiCopilotController.stream` 的既有模式推 `error` 帧 + `complete()`（不能走 JSON advice，EventSource 会报 MIME 错——该陷阱已在 `AiCopilotController` 注释实证）。若该出口未来划入 ipd 域，则换 `IpdPermission.requireInternal()` 取 `IpdActor.id`，两域不可混用 token。
3. **userId 入 KernelScopeKey 唯一入口**：可信 userId 取得后只经 `KernelScopeKey.of(projectId, userId, agentId, sessionId)` 进 `RuntimeContext`（C2/C3 门禁锁死，业务代码禁止手拼）。
4. **WS 握手 userId 复用模式**（若 W1 后段把对话搬 WS）：完整复用 `MpChatHandshakeInterceptor` 三件套——① `resolveToken(URI)` 从 query `Authorization` 取 token（WS API 不能设自定义 header）；② `getLoginIdByTokenNotThinkFreeze(token)` + `getTokenSessionByToken(token, false)` 双查，`LoginUser.loginId` 与 loginId 一致才放行；③ `attributes.put(USER_ID_KEY/LOGIN_USER_KEY)` 双写，消息线程从 attributes 取（`MpChatWebSocketHandler` 消费点即此模式）。**禁止**在消息线程调单参 `LoginHelper`（无 ThreadLocal，恒 null 判未登录——`KnowledgeAccessGate` 接口注释已明示该陷阱）。
5. **sa-token API 陷阱清单**（接线时照抄既有解法）：`isLogin(String)` 把 token 当 loginId 查永远 false；`getLoginIdAsLong()` 无参版依赖 header 上下文，query-token 场景必须 `getLoginIdByToken(tokenValue)`（`IpdSseController` 注释实证）。

### 3.3 知识库访问控制复用 KnowledgeAccessGate 的接入点

1. **判据与实现**（main 已合入，B0）：接口 `ruoyi-modules/ruoyi-chat/.../service/knowledge/KnowledgeAccessGate.java`，实现 `.../knowledge/impl/UserIdShareKnowledgeAccessGate.java`——判据 `owned = knowledgeInfo.getUserId().equals(userId)`（即 create_by 归属）`|| shared = Long.valueOf(1L).equals(knowledgeInfo.getShare())`，不可见即抛业务异常（非静默、非返 null）；单参变体 `checkRetrievalAccess(kid)` 走 HTTP 线程 LoginHelper，双参 `checkRetrievalAccess(kid, userId)` 供非 HTTP 线程显式传身份。
2. **SSE 链路接入点**（W1）：`AiCopilotService.chatStream`（或其替换后的 AgentScope 服务层）在把用户 kid 列表转检索上下文之前，逐 kid 调 `checkRetrievalAccess(kid)`；拒绝时按 #1 端点的 error 帧模式回给前端。
3. **WS 链路接入点**（已在位，回归确认）：`MpChatWebSocketHandler` 对 `kids.forEach(kid -> knowledgeAccessGate.checkRetrievalAccess(kid, userId))` 的调用点——换内核后此调用保持在任何 `SimpleKnowledge.retrieve` 之前。
4. **AgentScope RAG 侧硬隔离（PoC 实证口径）**：`vectorName` 是硬桶（跨桶召不回，✅ `AgentScopeRagPocIT` 负例实证「故意用错 vectorName 应召不回」），project 维隔离用 `vectorName`；`payload` 只做桶内软过滤。**gate（kid 级授权）与 vectorName（project 级隔离）是两层，不可互替**：gate 挡「无权看这个库」，vectorName 挡「跨项目串桶」。
5. **禁止项**：检索测试/向量查询/RAG 增强任何新入口直传 kid 不过门（B0 已收敛四处直传口，新增入口必须复用 gate——`ChatServiceFacadeKnowledgeAccessTest` 等 4 个测试类是回归哨兵，✅ 本次 merge 后在 PoC 树 18/18 通过）。

---

## 四、遗留风险与未实证项（如实）

| 项 | 状态 | 说明 |
|---|---|---|
| merge 冲突 | 无 | 双方改动文件集零交集（poc 侧 12 文件全为 PoC 专有+门禁脚本，main 侧 55 文件未触碰），ort 策略自动合并，`docs/script/sql/ruoyi-ai.sql` 基线自动取 main 修复版（`git diff main HEAD` 该文件为空）。 |
| G1–G5 验证 | 全部 RUN 且 PASS | 见附录命令清单；无 NOT-RUN 项（真库 13306 在跑、MINIMAX_API_KEY 在环境）。 |
| AgentScope 权限/计划/预算/压缩机制 | 📄 文档级 | permission-system、enablePlanMode、CompactionConfig、maxContextTokens 均未在本仓实证，本文一律标注 📄，接线前须逐项 PoC（对应 W3/W4/W5 波次前置）。 |
| `AgentEvent` usage 计量 | 未实证 | 流式事件是否携带 token usage 字段未查证，问 5 的计量器设计依赖它，W2 前必须实证。 |
| 第二套 harness 风险 | 持续管控 | 单轨靠本 ADR 处置表 + `harness-contract-check.sh` 扫描收窄（只扫 `io.agentscope`/`RuntimeContext` 引用文件，避免误伤自研 harness 内部 `+ ":" +`）+ G2 langchain4j 棘轮（基线 111）三重锁。 |
| W1 尚未开工 | 0/8 | 本文档是 W0 产出；W1 实施时须先过 `verify.sh --self-red`（期望 EXIT=0）再动代码。 |

## 附录：本次验证命令与 EXIT 码（merge 后，worktree 内实跑）

| # | 命令 | EXIT | 结果 |
|---|---|---|---|
| 1 | `bash .claude/skills/agentscope-harness/scripts/verify.sh --self-red` | 0 | self-red: PASS（SR1 违规→红=1 / SR2 合规→绿=0 / SR3 skill变异→红=1 / SR4 传播→红=1 / SR5 空对象→SKIP=0） |
| 2 | `bash .claude/skills/agentscope-harness/scripts/verify.sh`（正向） | 0 | env-probe=0 skill-lint=0 harness-contract=0（repo root 正确解析到本 worktree，pom 契约无假红） |
| 3 | `bash scripts/check-langchain4j-ratchet.sh` | 0 | count=111 vs baseline=111 |
| 4 | `mvn -pl ruoyi-modules/ruoyi-chat test-compile` | 0 | merge 后 B0 + PoC 共存编译通过 |
| 5 | `mvn -pl ruoyi-modules/ruoyi-chat test -Dtest=AgentScopeKernelPocIT` | 0 | G3：Tests run 7, Failures 0（真库 127.0.0.1:13306/ipd_poc） |
| 6 | `mvn -pl ruoyi-modules/ruoyi-chat test -Dtest=AgentScopeStreamingPocIT` | 0 | G4：Tests run 1（MINIMAX 真模型流式，chunk 序列 AGENT_START→MODEL_CALL_START→THINKING_BLOCK_*） |
| 7 | `mvn -pl ruoyi-modules/ruoyi-chat test -Dtest=AgentScopeRagPocIT` | 0 | G5：Tests run 4（Stub embedding + InMemoryStore 离线） |
| 8 | `mvn -pl ruoyi-modules/ruoyi-chat test -Dtest="UserIdShareKnowledgeAccessGateTest,KnowledgeInfoServiceImplWrapperTest,ChatServiceFacadeKnowledgeAccessTest,KnowledgeRetrievalCacheIdentityTest"` | 0 | B0 合入测试 18/18（merge 健康性附加验证） |

> 构建规范：worktree 根执行，`PATH=$HOME/tools/maven/bin`、`JAVA_HOME=$HOME/tools/jdk-17/Contents/Home`，单模块、不带 -am、不带 clean。
