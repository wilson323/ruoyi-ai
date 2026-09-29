# ADR-0075：AgentScope 内核替换「替换/包装/保留」三选一 + Agent Contract 六问

- 状态：~~`proposed`~~ → **`partially-approved`**（★ 2026-09-29 owner 授权「按照推荐完整执行」波 · P-14 分阶段升）
  - **原文逐字保留**：「`proposed`（设计裁决待审核；W1 已在 PoC 工作树实施，尚未获切换批准）」
  - **升 `partially-approved` 含义**：**已裁决部分生效**（P-01..P-04 三选一采纳 + P-05/P-06 实证），**未裁决部分维持 `proposed`**（P-07/P-08/P-09/P-12/P-14 下游）。
  - **不是全量 `approved`** —— 15 项 U 表未清零；升 `approved` 后才可称「**切换许可证**」。详见 §附录·P-14 升级记录。
  - **owner 授权记录**：2026-09-29「按照推荐完整执行」= 同意 3 条红线组合推荐（P-14 分阶段升 / P-12 Phase 1 拆 G3+G8 / P-09 W1 订阅桥接退场）。
- 日期：2026-09-28 · 决策域：AgentScope Java 内核替换（单轨化）
- 关联卡：W0 系列（看板 61217664）、B0（卡 5c1cc6f8 / commit 238dd9be）
- 本节记录 W0 提案时的文档产出；W1 在途代码与验证见 §8–9。ADR 当前仍为 `proposed`，不得视为全量切换批准。

## 0. 编号登记与格式对齐（现查结果）

- 目标目录 `docs/ipd-系统说明/ADR/` 为首建：`find` 现查主仓与 `poc-agentscope-kernel` worktree 均**无任何 `ADR-*.md` 文件**（仅第三方 vendored：`.agents/skills/domain-modeling/ADR-FORMAT.md` 模板、`.codex/ruflo/**/adrs/`，不算本项目序列）。
- 树内既有正文引用的项目自有 ADR 决策编号：`ADR-1`~`ADR-4`（`IPD全阶段AI代理执行闭环设计-20260926.md` 内联裁决）、`ADR-001`（R129 模块依赖白名单）、**`ADR-0074`**（`真库fresh健康快照-20260919.md`「字符集统一」已闭环）——序列已用至 **0074**。
- **实际编号 = ADR-0075**。用户指定 `ADR-0040` 顺延：0040 低于已用最高号 0074，大概率被拍板系列占用，按「下一个可用编号为准」取 0075 并登记于此。
- 格式：沿用用户指定的 `ADR-NNNN-主题-日期.md` 命名（与 `BCP-NNN-主题-日期.md` 同族）；内部结构对齐 `.agents/skills/domain-modeling/ADR-FORMAT.md` 的「Status frontmatter + 决策 + 后果」骨架，按本决策体量扩展。

## 1. 判断框架（红线——所有三选一不得违反）

来自仓库 `AGENTS.md` 与 `agentscope-harness` skill，逐条即验收判据：

1. **禁双轨**：本仓已有自研 harness `org.ruoyi.service.coding.harness`（17 子包 / main 254 个 `.java`，零 `io.agentscope` 引用），已自实现 Permission 三态（`tool/PolicyDecision`）、副作用幂等（`recovery/ToolEffectLedgerReconciler` / `UncertainToolEffectGuard`）、Plan 版本（`plan/CanonicalPlanHasher`）、预算（`model/HarnessBudget`）、Context 压缩（`context/ContextEngine`）等。引入 AgentScope 最大风险是**静默长出第二套 harness**。
2. **三选一强制**：内核替换必须对每个能力做「替换 / 包装 / 保留」显式决策并记 ADR（本文档即该登记）。
3. **门禁不得误伤自研**：自研 harness 内部大量 `+ ":" +` 是自身 run-state 命名空间，**不是**四维隔离键违规；`harness-contract-check.sh` 扫描范围只看「引用 `io.agentscope` 或 `RuntimeContext` 的 `.java`」，**不得改宽**。
4. **成熟方案优先（owner 硬指令）**：尽可能应用成熟解决方案、避免自定义代码过多。默认倾向「包装/替换为 AgentScope 成熟能力」；**只有 AgentScope 确实缺失或语义不符时才「保留」自研**。本文档据此重判（与 W0 保守稿差异见 §7）。
5. **语义定义**：**替换** = 自研实现删除、由 AgentScope 成熟能力承接；**包装** = AgentScope 成熟能力为底座、自研只留薄适配/策略门面（自研代码量净减）；**保留** = AgentScope 缺失或语义不符、自研继续承担，其上禁止再长第二套。
6. 每轮/每波单一变更面、gate 失败即 `git revert`；langchain4j 棘轮只减不增（基线 111，pre-commit 门禁 5）；`verify.sh --self-red` 必须 EXIT=0 才可声称已验证。

## 2. Agent Contract 六问（对 PoC 与正式接线逐问给答案与证据）

> 证据分级：✅ 已实证（真跑过）/ 🔍 现查存在（读过源码未跑行为）/ 📄 仅文档（java.agentscope.io，未验证）。
> AgentScope 侧根：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/`（G1–G5 ✅）；自研侧根：`.../service/coding/harness/`（17 子包 🔍）。

### ① 身份与会话

**答案**：身份只来自可信会话（Sa-Token 登录态 / WS 握手验签），**userId 严禁来自请求参数**；四维 `project × user × agent × session` 一律经 `KernelScopeKey.of(...)` 唯一收口折入 `RuntimeContext(userId, sessionId)` 二元；**Session ≠ Task**——`RuntimeContext.sessionId` 只承担推理连续性，业务 `taskId`/Agent 版本/Artifact/Outcome 业务侧另存。

**证据**：
- PoC 红线样例：`PocSseController.java` 类注释明示「刻意不接现有前端契约……W-cutover 时删除或改写为正式出口」，且 `@RequestParam userId` 直传（含 `defaultValue="U1"`）——**该模式不得进正式面**。
- B0 已做 S2 会话派生身份（卡 5c1cc6f8 / commit `238dd9be`）：`KnowledgeInfoServiceImpl.queryPageList/queryList` 入口无条件 `bo.setUserId(LoginHelper.getUserId())`；独立安全审计 `docs/ipd-系统说明/验收/2026-09-28-B0安全审计.md` 四问核验留档。
- 四维收口 ✅ G3：`KernelScopeKey` fail-closed 拒 `':'`/`..`，空 userId 降级 `__anon__` SESSION 语义，正例 2 + 负例 6 全过、真库 8 桶回读对号。
- 自研侧 🔍：`runtime/HarnessRunRequest(HarnessOwner, sessionId, runId)`、`model/HarnessOwner(tenantId, userId)` 构造 fail-fast。

**正式接线（W1）**：删 `@RequestParam userId`；HTTP 线程取 `LoginHelper.getLoginUser().getUserId()`（未登录走 SSE `error` 帧 + `complete()`，不能走 JSON advice）；WS 复用 `MpChatHandshakeInterceptor` 三件套（query 取 token → `getLoginIdByTokenNotThinkFreeze` + `getTokenSessionByToken` 双查 → attributes 双写）；禁 `isLogin(String)` / 无参 `getLoginIdAsLong()` 陷阱；两域（platform/ipd）token 不可混用。

### ② 工具白名单与 Permission 三态

**答案**：白名单两层——能力注册面用 AgentScope `tools.json` 静态白名单（成熟机制，📄）；**裁决语义唯一源 = 自研 `PolicyDecision`（ALLOW/ASK/DENY）**，AgentScope permission-system（📄）只作执行面拦截点（包装）。DENY 返回结构化原因 + 允许替代项；ASK 走 `approval/` claim→裁决→回执闭环，**ASK 不做默认兜底**（先用更窄 Tool/参数约束/Preview 降风险）。

**证据**：🔍 `tool/PolicyDecision.java`、`tool/ToolPolicyContract.java`、`tool/ToolPolicyEngine.java`；`approval/` 18 文件全生命周期。AgentScope permission-system 与 `tools.json` 均 📄 未实证（PoC 无 tool 调用路径，C2 未覆盖）→ **W3 前置 PoC**。

**红线**：禁止在 AgentScope 侧再造第二套白名单/裁决语义（双轨风险点 §5-D1）。

### ③ 副作用幂等 / 取消语义

**答案**：**保留自研（AgentScope 缺失）**。有副作用的动作重试/恢复前必须回答「上次到底发生没有」：`recovery/ToolEffectLedgerReconciler.updateAtomically` 把工具效果账本与消息账本原子对账（AssistantIdentity/ReceiptIdentity/Observation 三级比对）；`recovery/UncertainToolEffectGuard.firstFinding/requiresAdjudication/suspend` 对不确定态显式挂起待仲裁，**禁止机械重试**。取消 = `CANCELLED` 终态并保留审计事实；已提交 Action 需等返回再补偿（不重放失效写操作）。

**证据**：AgentScope `AgentStateStore`（✅ `MysqlAgentStateStore` 只存会话状态快照，G3 实证）**没有工具效果账本与不确定态仲裁概念**（📄 going-to-production 亦无对应物）→ 缺失成立，保留正当。自研侧 🔍 7 文件（`HarnessStartupRecovery`、`ToolEffectLedgerFailureReason` 等）。

**接线**：W3 起工具执行外围包账本——每次工具调用前后写自研账本，AgentScope 崩溃恢复先过 Reconciler 对账再续跑；**任何波次不得删除**（最难重建资产）。

### ④ 预算 / 超时

**答案**：**保留自研裁决**（AgentScope 语义不符）——`model/HarnessBudget`（maxIterations/maxToolCalls/maxInputTokens/maxOutputTokens/maxWallTimeMillis）+ `app/HarnessBudgetPolicy.enforce/forFollowUp/secureDefaults` 为唯一裁决点。AgentScope 侧仅 `.maxContextTokens(int)`（MEMORY 注入预算，📄），无步数/时长/token 双向/工具次数/并发/高风险次数多维熔断。计量源可**包装** AgentScope 事件流，但 `AgentEvent` 是否携带 usage **未实证**——W2 前必须实证，实证前不得声称可计量。超时/耗尽必须产生**明确终态 + 未完成清单**，绝不悄然截断。

**证据**：🔍 `HarnessBudget`（构造校验非负）、`HarnessBudgetPolicy`、消费点 `app/CodingHarnessApplicationService`/`CreateHarnessRunCommand`；📄 Builder 速查仅 `maxContextTokens`。PoC SSE `SseEmitter(120_000L)` 仅样例值。

### ⑤ 记忆与上下文边界（含 KnowledgeAccessGate）

**答案**：三层分治，不可互替——
1. **访问控制（保留）**：`KnowledgeAccessGate`（B0）kid 级授权 `owned(create_by) || share==1`，不可见即抛业务异常（fail-closed）；S3 检索缓存身份段防跨身份缓存泄漏。AgentScope 无授权概念（缺失）。
2. **隔离（包装）**：AgentScope RAG 两级隔离 ✅ G5——`vectorName` 硬桶（跨桶召不回，project 维）+ `DocumentMetadata.payload` 软过滤（桶内）。**gate 挡「无权看库」、vectorName 挡「跨项目串桶」，两层不互替**。
3. **记忆/压缩（包装，条件式）**：会话状态用 `AgentState`（✅）；长期记忆 workspace `memory/`+`MEMORY.md` 与 `.compaction(CompactionConfig)`/`.toolResultEviction`（📄，C5 未实测）作底座，自研 `ContextEngine` 的 token 预算 + `ContextPins` + 压缩熔断语义保留薄层；**C5 实测语义不符则整体改判保留**。

**证据**：commit `238dd9be`（`KnowledgeAccessGate`/`UserIdShareKnowledgeAccessGate`/`KnowledgeRetrievalServiceImpl.cacheKey` 身份段）+ B0 安全审计；G5 正负例（正例 2 / 负例 6）；🔍 `context/ContextEngine`（MessageGroup/Grouping/Cut）、`ContextState.resetCompactionCircuit()`。

### ⑥ 可观测（trace / 事件帧与现有 SSE/WS 契约兼容）

**答案**：对外帧契约**保留不动**（前端零改动硬约束）——AiCopilot SSE 四帧 `meta/delta/done/error` + `code0/message` 包络、WS 帧不动；内核事件源**包装**：`HarnessAgent.streamEvents` 的 `AgentEvent` 映射既有帧（✅ `PocSseController.sendEvent` 已验证 `TextBlockDeltaEvent.getDelta()` → `text_delta`、泛事件 → `event`、错误单独 `error` 后 `complete()`）。trace 采集面**替换**为 Middleware 钩子（W6，📄）承接 15 个 langchain4j listener + argtrace；`journal/` 脱敏（`JournalSecretRedactor`）与 Event Log/Snapshot/Checkpoint 三表示**保留**（AgentScope `sessions/*.jsonl` 不覆盖脱敏语义）。

**证据**：✅ G4 45 事件流 + SSE curl 96 行；🔍 `event/HarnessEventHub`、`loop/HarnessDeltaEventPublisher`；📄 middleware 文档。**风险**：C10 未覆盖；自研 `HarnessEventHub` 与内核事件流并行发布 = 双轨观测（§5-D4），W6 出口唯一化。

## 3. 能力 × 三选一矩阵（AgentScope 成熟能力实名以 `references/agentscope-java.md` 为准）

> 判定基调（红线 4）：成熟方案优先——AgentScope 有成熟且语义相符的能力即「替换/包装」；仅缺失或语义不符才「保留」。

| # | 能力 | AgentScope 成熟能力（实名） | 自研对应物 | 三选一 | 理由（成熟方案优先） | 波次 | 风险 |
|---|---|---|---|---|---|---|---|
| 1 | 四维隔离键 | `RuntimeContext(userId,sessionId)` + `MysqlAgentStateStore` slotId（✅，原生缺 project/agent/tenant 维） | `KernelScopeKey.of(...)`（poc/kernel）+ `HarnessOwner(tenantId,userId)`/`IsolationScope` | **包装** | 二元寻址是成熟能力但缺维；四维折叠收口于 `KernelScopeKey` 唯一适配层（✅ G3），不造并行键 | W1 | tenant 折入 project 段语义挤压；slotId 冒号解析需唯一约定；**门禁扫描范围不得改宽** |
| 2 | 同键并发串行化 | 2.0.3 `LocalSessionTurnGate` 已在 PoC `streamEvents` 外验证**单进程**同 slot 不重叠；跨副本和业务准备/落库整轮互斥未验证 | `runtime/HarnessSessionGate.withSession` | **替换（条件式目标，未验收）** | 同一业务轮最终只保留一个串行机制；需先证明原生门或等价机制覆盖整轮与跨副本后才能删除自研门 | W1 接线 / W7 条件式收编 | 文档的 `call()` 串行结论不适用于当前直接 `streamEvents()`；进程内局部门不证明跨副本安全，过渡期不得双门并行执行 |
| 3 | run-state 命名空间（内部 `+ ":" +`） | 无对应物 | `model/HarnessRunState` 等内部拼接 | **保留** | AgentScope 缺失；且属自研命名空间、**不是四维键违规** | W7 迁移表示 | 误判违规而改宽门禁 = 反向风险 |
| 4 | 身份与会话 | `RuntimeContext` + `MysqlAgentStateStore.ANON_USER` 降级（✅）；无登录态 | `HarnessRunRequest`/`HarnessOwner` + Sa-Token `LoginHelper`/WS 握手 | **包装** | 登录态用成熟 Sa-Token；AgentScope 只承接推理连续性；userId 禁来自请求参数 | W1 | PocSseController 模式流入正式面；WS 消息线程无 ThreadLocal 陷阱；两域 token 混用 |
| 5 | Memory 会话状态 Store | `AgentState` + `MysqlAgentStateStore`（✅ G3 真库 8 桶） | `PersistentChatMemoryStore`（langchain4j） | **替换** | 外置会话状态已实证成熟，替换 langchain4j 会话记忆 | W1 | 历史回读口径差异需回归；表 DDL 预建（`createIfNotExist=false`） |
| 6 | 状态三表示（Event Log/Snapshot/Checkpoint） | `AgentStateStore`（仅 Snapshot） | `store/FileHarnessStore`+乐观锁、`HarnessRunState` 权威、`journal/` 投影 | **包装** | 存储面用成熟 `MysqlAgentStateStore`；但三表示不可互替、AgentScope 无 Event Log/Checkpoint，业务权威留自研 | W1/W7 | 把 Snapshot 当 Checkpoint 掩盖在途工具事务；双写需显式职责声明（§5-D6） |
| 7 | SSE/WS 事件帧出口 | `streamEvents`/`AgentEvent`（✅ 45 事件）+ `TextBlockDeltaEvent.getDelta()`（✅ 映射实证） | `event/HarnessEventHub`、`loop/HarnessDeltaEventPublisher`、四帧 meta/delta/done/error + code0 包络 | **包装** | 事件源换成熟内核流；帧契约保留（前端零改动硬约束） | W1 | 帧语义丢失（thinking/done 乱序）；双发（§5-D4） |
| 8 | 模型装配与调用（provider 归一） | `ModelRegistry.resolve("provider:model")` + provider SPI（✅ MiniMax）+ `agentscope-extensions-model-*` | `modelruntime/HarnessChatModelFactory`/`RuoYiHarnessChatModelFactory` + provider 13 家直连 | **替换** | 成熟 provider SPI 替换 13 家自装配；okhttp 5.3.2 钉版 + `banDuplicateClasses` 保运行态 | W2 | 其余 8 厂商未冒烟（C1）；`DoubaoStreamingChatModel` 自实现归一 OpenAI 兼容端点行为差异；langchain4j 直连层（13 家 `HarnessChatModelFactory` 等）按 W2 回滚点保留至回滚窗口关闭，删除时点单独定案（2026-09-28 W2 收口裁定增补 M1） |
| 9 | 模型动态切换（C9） | `ModelRegistry` 字符串路由（✅） | `HarnessModelRouter`/`HarnessModelRegistry`/`HarnessModelPolicy` + `ai_model_configs` 表驱动 | **包装** | 解析用成熟 ModelRegistry；选型/降级策略是业务政策留薄壳；禁第二套路由 | W2 | `ai_model_configs` 切换未实测；降级路径必须记录 |
| 10 | 多维预算 / 超时 | `.maxContextTokens`（📄，仅 MEMORY 注入） | `model/HarnessBudget` + `app/HarnessBudgetPolicy` | **保留** | AgentScope 无多维预算（缺失/语义不符）；计量可包装事件流 | W2（计量） | `AgentEvent` usage 未实证；耗尽须显式终态 + 未完成清单 |
| 11 | Tool 调用执行（C2） | HarnessAgent toolkit + `ToolSchema`（`io.agentscope.core.model.*`）+ `tools.json` 白名单（📄） | `tool/` 48 文件：`ToolDescriptor`/`ToolInvocation`/`ToolOutcome`/`ToolBatchPlanner`/`builtin/`/`command/` | **替换**（执行协议） | 成熟工具协议（toolkit/@Tool/MCP 注册）替换自研执行协议，自研代码净减；裁决/账本另行包装/保留 | W3 | C2 未覆盖；`ToolBatchPlanner` 批计划与 `ToolOutcomeStatus` 细粒度语义映射缺口 |
| 12 | Permission 三态 | permission-system（📄 未实证）+ `tools.json` | `tool/PolicyDecision`（ALLOW/ASK/DENY）+ `ToolPolicyContract`/`ToolPolicyEngine` | **包装** | 拦截点用成熟 permission-system；裁决语义唯一源 = 自研 `PolicyDecision`（AgentScope 策略表达力不足以覆盖 ASK 闭环） | W3 | permission-system 未实证（W3 前置 PoC）；ASK 兜底滥用 |
| 13 | HITL 审批（ASK 闭环） | Plan Mode `plan_exit` HITL（📄） | `approval/` 18 文件（claim→裁决→回执） | **保留** | AgentScope 无审批回执/claim 生命周期（缺失） | W3 | 与 Plan Mode 双审批流并存 → W7 归一，只开一处 |
| 14 | 副作用幂等 ledger / 取消 | 无对应物（`AgentStateStore` 仅会话快照） | `recovery/ToolEffectLedgerReconciler`、`UncertainToolEffectGuard`、`HarnessStartupRecovery` | **保留** | AgentScope 缺失（最难重建资产）；「上次到底发生没有」必须自研 | W3 接线 | 恢复时机械重放；CANCELLED 需保留审计事实 |
| 15 | 知识库访问控制 | 无（RAG 两级隔离只管串桶不管授权） | `KnowledgeAccessGate`/`UserIdShareKnowledgeAccessGate`（B0，commit `238dd9be`） | **保留** | AgentScope 缺失；gate（kid 授权）与 vectorName（project 隔离）两层不互替 | W4 前置 | 直传口回潮；`/system/info`、`/system/attach` 管理面 ownership 缺口（B0 审计已登记待修） |
| 16 | RAG / 知识链（C7/C8） | `SimpleKnowledge` + `InMemoryStore`/MilvusStore + `EmbeddingModel` + Reader（✅ InMemory 全链；rag 类 `@Deprecated(forRemoval)`） | embed 5 provider + rerank + vector 3 策略 + `CustomVectorRetriever` + loader | **包装** | 成熟知识链为底座（G5 两级隔离正负例实证）；自研只留 Knowledge 门面 + Gate；⚠ rag 包官方弃用方向 = 应用层检索，W4 对位 | W4 | Milvus 未联调；真实 embedding 未跑（MiniMax embeddings 非 OpenAI 格式）；`@Deprecated` 平迁风险 |
| 17 | MCP 工具注册 | AgentScope Toolkit/MCP 注册 `tools.json`（📄） | `LangChain4jMcpToolProviderService` + 6 文件工具 | **替换** | 成熟 MCP 注册替换 langchain4j MCP；与 W3 白名单同清单单轨 | W5 | 双套工具目录（§5-D2）；tools.json 与 policy 清单漂移 |
| 18 | Context 压缩 | `.compaction(CompactionConfig.triggerMessages/keepMessages)` + `.toolResultEviction`（📄） | `context/ContextEngine`（token+pins+熔断）+ `ContextTokenBudget`/`ContextPins`/`ContextState` | **包装**（条件式） | 成熟压缩触发 + 大结果卸载为底座；pins/token 预算缺口留薄层。**C5 实测语义不符则整体改判「保留」** | W7（C5 实测为入条件） | 按条数 vs token 语义错配；压缩后回读未实证；双开双套压缩（§5-D3） |
| 19 | Memory 长期记忆 | workspace `memory/`+`MEMORY.md` + `.maxContextTokens` 注入预算（📄） | 无独立子包（职能散于 context/prompt） | **包装** | 成熟长期记忆机制采纳；业务事实写入经既有 prompt/知识门面 | W7 | 与知识库职责重叠；注入预算与 `HarnessBudget` 归属需单一裁决 |
| 20 | trace / 事件采集（C10） | Middleware 钩子（📄）+ `AgentEvent`（✅）+ workspace `sessions/*.jsonl`（📄） | observability 15 listener + argtrace（langchain4j） | **替换** | 成熟 Middleware 替换 langchain4j listener 监听面（棘轮只减不增的正当出口） | W6 | C10 未覆盖；trace 契约字段缺失则 Trace→Harness Patch 闭环断链 |
| 21 | 日志脱敏 / 审计投影 | 无脱敏语义 | `journal/JournalSecretRedactor`、`FileRunJournalProjector`、`StructuredContextSnapshotFactory` | **保留** | 合规资产，AgentScope 缺失 | W6 | 事件帧与 journal 双脱敏口径不一致 |
| 22 | Plan 版本 / hash | `.enablePlanMode()`/`plans/PLAN.md`+HITL（📄）——无版本号/乐观锁/canonical hash | `plan/PlanAggregate`（revision 乐观锁）+ `CanonicalPlanHasher`（SHA-256）+ `StalePlanRevisionException` | **保留** | AgentScope 语义不符（无版本化）；版本化资产不可替代。Plan Mode 交互面另行包装（见 #13/W7） | W7 | PLAN.md 与 `PlanAggregate` 双权威 → **PLAN.md 只是视图**，落盘必过 `replacePlan` |
| 23 | Skill 渐进披露 | workspace `skills/` 四层同名优先级 + `skillRepository`（Git/Nacos/MySQL/Classpath，📄） | `skill/HarnessSkillCatalog` 等 4 文件 | **包装** | 成熟技能目录/市场形态采纳（对照表自判「最可能缺口」）；`HarnessSkillCatalog` 只留门面 | W7 | 同名冲突优先级与沙箱物化路径未对齐 |
| 24 | Artifact 生命周期 | workspace `agents/<id>/` + `RemoteFilesystemSpec`/`SandboxFilesystemSpec`（📄） | `artifact/` 15 文件（Draft→Validating→Ready→Published/Rejected→Archived） | **包装** | 存储面用成熟 workspace/filesystem；生命周期状态机保留（业务治理） | W7 | 「模型写出文件 = 产物完成」误判 |

**矩阵汇总是拟议目标，并非已完成替换**：24 项中目标替换 6（#2 为条件式，另 #5 #8 #11 #17 #20）、包装 11（#1 #4 #6 #7 #9 #12 #16 #18 #19 #23 #24）、保留 7（#3 #10 #13 #14 #15 #21 #22）。保留项有「AgentScope 缺失或语义不符」的显式理由；每项须在对应波次用真实业务和技术证据验收后才可升级状态。

## 4. W1–W8 波次排布（与矩阵挂钩；波次定义沿用《完整方案》§5.0）

**总则**：每波 = 一个聚合变更面（gate 失败即 `git revert` 该波 commit）；每波收口跑 `verify.sh --self-red`（EXIT=0）+ langchain4j 棘轮（count≤111 只减不增）；C1~C12 全绿才 cutover。**验收口径以《[agentscope-验收口径基准](./agentscope-验收口径基准.md)》（M4 基准文件）为准：波次收口与出条件核对引用该文件口径编号（K-01~K-07），不得写裸数字（2026-09-28 W2 收口裁定增补 M4）。**

### W1 对话内核 + 身份/权限接线（对应矩阵 #1 #2 #4 #5 #6 #7；PoC 样例收口）
- 内容：`ChatServiceFacade`+`AbstractChatService`+`PersistentChatMemoryStore`+WS handler → `HarnessAgent`+`AgentState`；**PocSseController 模式不得进正式面**（删 `@RequestParam userId`、不沿用 `/poc/kernel/**` 路由、不接前端契约的注释定位不带入）；既有 ruoyi-chat SSE、`/chat/ws` 与 IPD `meta/delta/done/error` 四帧 + code0 包络是不同入口合同，逐入口保持原消费者协议和必要数据行为，不能强制共用帧；`KernelScopeKey` 唯一收口。
- 入条件（目标门，不能倒推已通过）：W1 PoC 实施已经开始；`verify.sh --self-red`=0、配置与 C5 压缩负例、整轮及跨副本同键并发负例和设计审核仍须按各自证据关闭，未完成项限制扩大开关。
- 出条件：前端手工回归通过（C3/C4）；并发负例绿；B0 Gate 调用点回归（`ChatServiceFacadeKnowledgeAccessTest` 等 4 哨兵）绿。
- 回滚点：`ChatServiceFacade` 内核委托切换开关（保留 `AbstractChatService` 现实现一个版本周期）。

### W2 模型层（#8 #9 #10 计量）
- 内容：provider 13 家 → `agentscope-extensions-model-*` 五厂商 + OpenAI 兼容端点归一；`ModelRegistry` 替换装配；`ai_model_configs` 表驱动切换实测；`AgentEvent` usage 实证 → 预算计量器（喂 `HarnessBudgetPolicy`）。
- C9 实测表源与环境要求（2026-09-28 W2 收口裁定增补 M3）：C9 实测表源 = IPD `ai_model_configs`（唯一模型权威；现 `KernelModelRequest.from(ChatModelVo)` 读 `chat_model`，不计 C9 通过），与矩阵 #9、§10.2 W2 对齐；环境要求 = 真库 + `chat.kernel.agentscope.model-routing.enabled` 显式开启 + 模型 A→B→A 切换实测与回读一致。
- 入条件：W1 绿；usage 字段实证完成（未实证不得开发计量）。
- 出条件：C1 逐厂商冒烟（需 8 厂商真实 API 凭据——真实凭据环境依赖项）+ C9 切换实测绿；降级路径有记录。受真实凭据/环境阻塞时按 BLOCKED_ENVIRONMENT 收口规则挂账（2026-09-28 W2 收口裁定增补 M2，见下）。
- 回滚点：`HarnessModelRouter` 路由开关切回 langchain4j 直连（或 revert 波次 commit）。
- W2 收口裁定（2026-09-28 W2 收口裁定增补）：W2 记「**实施完成、出条件部分开放**」。实施面已交付：`KernelModelSelector` 替换装配 + `KernelModelRequest` 包装请求模型路由（13 家 langchain4j 直连按回滚点保留，见矩阵 #8 补注）；路由定向 14 项绿、全模块 165/165 绿、`verify.sh`/`check-sse-contract.sh`/并发负例绿，SSE/WS 契约 62/62 零回退（口径 K-01~K-07 见 [agentscope-验收口径基准](./agentscope-验收口径基准.md)）。
- 出条件两项未闭：**C1 逐厂商冒烟 = BLOCKED_ENVIRONMENT**（缺 8 厂商真实 API 凭据）；**C9 切换实测 = 未闭**（路由语义绿，但 `ai_model_configs` 表驱动端到端未实测，U9，生产装配来源仍 `chat_model`）。**BLOCKED_ENVIRONMENT 收口规则（M2）**：出条件项因真实凭据/真实环境不可得而无法执行时，允许记 `BLOCKED_ENVIRONMENT` 挂账收口——条件：①归因为环境缺失而非质量缺陷；②该项可实测面已绿；③挂账项进 **W3 前补证清单**并登记文末未尽事项。挂账项不得计绿、不得据此删旧实现或扩大开关；正式「**W2 DONE**」标记待 C1/C9 补证通过后追认。

### W3 工具与权限（#11 #12 #13 #14）
- 内容：toolkit/@Tool 替换工具执行协议；业务 DB 工具目录与绑定关系为唯一配置权威，`tools.json` 仅作可用工具投影，permission-system/Toolkit 执行前仍经唯一 `PolicyDecision` 裁决；ASK → `approval/` 闭环；副作用账本包工具执行外围。AgentScope 2.0.3 共享 `tools.json` 不是按用户/项目隔离的权限库，MCP `readOnlyHint` 也不得自动绕过业务 DENY/ASK。
- 入条件：permission-system 行为 PoC 实证（📄→✅）；W2 绿。
- 出条件：C2 覆盖绿；同一工具调用 trace 中恰有一个裁决事件 + 一个账本写（§5 验收）。
- 回滚点：工具注册开关（`tools.json` 未启用则走自研 `tool/` 执行器，互斥）。
- W3 入条件裁定（2026-09-28 W2 收口裁定增补）：W3 入条件按「**W2 实施面绿 + 受阻项挂账**」放行推进。理由：①回滚开关双保险在位——`chat.kernel.agentscope.model-routing.enabled` 默认 `false`、`chat.kernel.agentscope.enabled`（`matchIfMissing=false`），异常即时关断回旧链；②C1/C9 受阻为环境性非质量性（凭据/表源缺失），W2 实施面测试全绿；③SSE/WS 契约 62/62 零回退（口径 K-01）。原入条件「W2 绿」据此解释为「W2 实施面绿 + C1/C9 在补证清单挂账」；「permission-system 行为 PoC 实证（📄→✅）」不在放行豁免之列，仍为 W3 硬前置。
- W3 出条件不得豁免的硬项（BLOCKED_ENVIRONMENT 挂账规则**不适用**于下列各项）：①C2 工具调用正反例覆盖绿；②同一工具调用 trace 恰一个裁决事件 + 一个账本写（§5.2 行为判据）；③DENY/未批准 ASK 零副作用；④permission-system 行为 PoC 实证完成（📄→✅）；⑤副作用账本接线不删除（矩阵 #14「任何波次不得删除」）；⑥不长第二套白名单/裁决（§5.1-D1）。挂账仅适用「真实凭据/真实外部环境不可得」的实测项，且须入补证清单。

### W4 知识库链（#15 #16）
- 内容：`SimpleKnowledge`/MilvusStore/Reader/`EmbeddingModel` 替换 embed/rerank/vector 三策略；`KnowledgeAccessGate` 前置不变（任何检索前）；protobuf 地雷根除（摘 langchain4j-weaviate/qdrant/milvus 三依赖）；rag `@Deprecated` 对位官方「应用层集成检索」方向。
- 入条件：W1 绿；向量后端拍板（Milvus/Weaviate/适配）；DashScope 或 OpenAI 兼容 key。
- 出条件：C7/C8 实测（Milvus 联调 + 真实 embedding + Tika 分块）绿；两级隔离正负例复跑绿。
- 回滚点：Knowledge 门面后端切换（`CustomVectorRetriever` 现实现保留一个版本）。

### W5 MCP 工具链（#17）
- 内容：`LangChain4jMcpToolProviderService` + 6 文件工具 → AgentScope Toolkit/MCP 注册；现有 `mcp_tool_info`、员工绑定和业务授权仍为唯一配置/权限权威，`tools.json` 是按范围生成的运行投影，不独立管理第二张清单。
- 入条件：W3 绿。出条件：MCP 注册/发现、禁用重建、跨租户同名、ASK claim、stdio/http/sse 与管理回读正反例绿；目录投影与 DB/Policy 漂移检查脚本化。回滚点：MCP 注册开关，禁止旧/新目录同时执行同一工具。

### W6 可观测（#20 #21）
- 内容：15 个 langchain4j listener + argtrace → Middleware 钩子；事件出口唯一化（`HarnessEventHub` 与内核事件流只留一条发布路径）；journal 脱敏保留并入 Middleware 出口。
- 入条件：W1/W2 绿；trace 契约字段定义评审过（对齐 Trace→Harness Patch 七类）。
- 出条件：C10 覆盖绿；脱敏回归绿。回滚点：Middleware 开关（关掉即回自研 listener，保留一个版本）。

### W7 自研 harness 收编（#2 #3 #6 #13 #18 #19 #22 #23 #24）
- 内容：plan-mode/skill/subagent 原生对位收编；仅在整轮与跨副本串行化及失败恢复同一业务域实证通过后删除重复 `HarnessSessionGate`；`CompactionConfig` 归一（包装落地或按 C5 裁定改判保留）；`MEMORY.md` 长期记忆接线；PLAN.md 作视图、`PlanAggregate` 仍权威；run-state 命名空间表示迁移。**目标是删除经验证重复的实现，而非维护双实现**（W1–W6 稳定后）。
- 入条件：W1–W6 全绿 + C5 实测结论。出条件：被收编类删除或降 `@Deprecated` 空壳且有删除卡；§5 验收方法全过。回滚点：每子项独立 commit，逐项 revert。

### W8 aiflow / ipd AI 执行链
- 内容：`LLMAnswerNode`/`KnowledgeRetrievalNode`/`WorkflowUtil` 与正式 IPD `AiCopilotService`、生成/建议/预审/比对等活跃 `AiGateway` 消费者逐入口改经 AgentScope 唯一执行适配口；知识检索仍先过 `KnowledgeAccessGate`，IPD 模型权威仍为 `ai_model_configs`；LangGraph4j 工作流编排本身不属 agent 内核，**保留**。详见 §10.2 W8。
- 入条件：W2/W4 与 W6 绿；用到工具时 W3/W5 也须绿。出条件：aiflow 和 IPD 各入口的模型/知识/预算/权限/终态及业务写入正反例通过；`AiGateway` 所有活跃消费者迁移并有退场清单；新 IPD 不默认使用旧 `chat_message`。回滚点：逐入口互斥切换开关，不允许一次调用双内核执行。

## 5. 红线自查：双轨风险点清单 + 「没有第二套 harness」的验收方法

### 5.1 双轨风险点清单（D1–D10）

| # | 风险点 | 触发场景 | 遏制 |
|---|---|---|---|
| D1 | AgentScope 侧再造工具白名单/裁决 | W3 在 permission-system 里写第二套策略逻辑 | 裁决唯一源 `PolicyDecision`；permission-system 只做拦截点 |
| D2 | 双套工具目录 | W5 `tools.json` 与自研 `tool/command` 并行注册 | 同一张清单单轨；清单漂移脚本化检查 |
| D3 | 双套 Context 压缩 | `CompactionConfig` 与 `ContextEngine` 同时开 | W7 前只开一套；C5 实测裁定归一 |
| D4 | 双轨观测/事件双发 | `HarnessEventHub` 与 `streamEvents` 并行发布 | W6 出口唯一化 |
| D5 | 双门并发竞态 | `HarnessSessionGate` 与内核串行化并存 | 过渡期互斥开关；整轮与跨副本负例通过后才在 W7 删除重复门 |
| D6 | 状态双写混淆 | `HarnessRunState` 与 `AgentState` 被当同一物互拷 | 三表示职责显式声明：AgentState=Snapshot、HarnessRunState=业务权威、journal=Event Log |
| D7 | 双计量 | 自研计量器与内核 usage 双扣预算 | 计量点唯一（喂 `HarnessBudgetPolicy` 单入口） |
| D8 | langchain4j 面积反弹 | 新增 langchain4j 接线 | 棘轮门禁 5（基线 111 只减不增） |
| D9 | PoC 样例流入正式面 | `/poc/kernel/**` 路由、请求参数 userId 被沿用 | W1 出条件逐项核对；PocSseController cutover 时删除 |
| D10 | 门禁被改宽误伤自研 | 把 run-state `+ ":" +` 当四维键违规、扩扫描 | 扫描范围锁定「引用 `io.agentscope` 或 `RuntimeContext` 的 `.java`」，**不得改宽**（红线 3） |

### 5.2 「如何证明没有第二套 harness」验收方法（W7 出条件 + cutover 门禁）

1. **面积判据**：langchain4j count 每波只减不增（≤111）；`io.agentscope` 引用面 = `poc/kernel` + 各波替换点，逐波有清单。
2. **单实现判据**：每个能力域「同时活跃实现 ≤ 1」——被替换类删除或降 `@Deprecated` 空壳并挂删除卡；包装类只剩门面/策略（无算法副本）；grep/ArchUnit 断言：压缩、裁决、账本、计量各有**唯一**类被调用。
3. **行为判据**：同一次工具调用 trace 中恰有一个 PERMISSION_DECISION 事件、一个 ledger 写、一个 BUDGET 扣减（W3 出条件）。
4. **门禁判据**：`verify.sh --self-red` EXIT=0（防恒红/假绿）；`harness-contract-check.sh` 扫描范围与红线 3 一致。
5. **cutover 判据**：C1~C12 全绿（§5 门禁口径）+ `grep dev.langchain4j` 归零判据（cutover 后每波收口跑）。

## 6. 未证明项表（PoC 缺口逐条挂波次）

| # | 未证明项（来源：PoC 验收报告） | 缺口 | 挂波次 |
|---|---|---|---|
| U1 | C1 模型调用 | 其余 8 厂商未冒烟 | W2 |
| U2 | C2 工具调用 | 完全未覆盖 | W3 |
| U3 | C3 流式 SSE | 现有前端契约（code0/message 包络）回归未做 | W1 |
| U4 | C4 WebSocket | 未覆盖 | W1 |
| U5 | C5 记忆压缩 | `CompactionConfig` trigger/keep 行为单测未做（间接实证仅 AgentState 读写） | W1 前置单测 / W7 归一 |
| U6 | C7 RAG | Milvus 后端未联调（G5 用 `InMemoryStore`） | W4 |
| U7 | C7/C8 | 真实 embedding 未跑（stub 向量；MiniMax embeddings 非 OpenAI 格式）；Tika Reader 分块未跑 | W4 |
| U8 | C8 文档入库 | 部分实证 | W4 |
| U9 | C9 模型动态切换 | `ai_model_configs` 表驱动切换未测 | W1/W2 |
| U10 | C10 trace/Middleware | 完全未覆盖 | W6 |
| U11 | C11 依赖 | protobuf-java-util 4.29.3 ↔ protobuf-java 3.25.5 错配（存量地雷只登记） | W4 根除 |
| U12 | C12 四维隔离 | 状态键 PoC 正例 2+负例 6；工作区/文件及整轮跨副本隔离未实证。流式同键进程内局部门已测，不等于多副本通过 | W1/W7 |
| U13 | `AgentEvent` usage 计量 | 是否携带 token usage 未查证 | W2 前置 |
| U14 | permission-system / `tools.json` / Plan Mode / CompactionConfig / skillRepository 等 📄 面 | 全部未实证 | 各波前置 PoC（W3/W7） |
| U15 | rag 包 `@Deprecated(forRemoval)` 平迁 | 官方方向=应用层集成检索，2.0.3 仍可用但需对位 | W4 |

合计 **15 项**（其中 C 系 12 项 + 其他 3 类）；cutover 前必须逐项清零或由 owner 显式豁免登记。

## 7. 与既有文档的关系 + 事实源登记

### 7.1 与 W0 稿的差异（本文档为 SSOT）

main 树既有《`docs/ipd-系统说明/AgentScope接入ADR与Contract六问-20260928.md`》（W0 稿）判定「替换 1 / 包装 2 / 保留 14」，基调偏「替换面最小」。本文档按 owner 硬指令（红线 4：成熟方案优先）重判为「**替换 6 / 包装 11 / 保留 7**」，主要改判：

| 能力 | W0 稿 | 本文档 | 改判理由 |
|---|---|---|---|
| Tool 执行协议（C2） | 保留为主 | **替换** | 成熟 toolkit/@Tool/MCP 协议优先，治理件另行处置 |
| trace（C10） | 保留 | **替换** | Middleware 是 langchain4j listener 的成熟替代（棘轮正当出口） |
| 同键并发串行化 | 保留（双门） | **替换** | 双门竞态是双轨风险，成熟内核串行化优先 |
| Memory 会话 Store | 保留 | **替换** | `AgentState`/`MysqlAgentStateStore` 已 ✅ 实证 |
| MCP 注册 | —（未列） | **替换** | W5 明确以 AgentScope MCP 注册承接 |
| RAG/知识链 | —（未列） | **包装** | G5 两级隔离已实证 |

保留 7 项均有「AgentScope 缺失/语义不符」显式理由（副作用账本、多维预算、HITL 审批回执、KnowledgeAccessGate、脱敏、Plan 版本、run-state 命名空间）。本文是 `proposed`；与 W0 稿冲突的裁决须由 owner 审核后进入唯一 ADR，不能以本草案自动覆盖既有权威。W0 稿保留历史记录。

### 7.2 事实源（只读现查，2026-09-28）

1. `agentscope-harness` skill：`.claude/skills/agentscope-harness/`（SKILL.md、`references/agentscope-java.md` 对照表与实证 API 面、`references/engineering-contracts.md` 三契约、`verify.sh`）
2. PoC 验收报告：`/Users/mac/Documents/最佳实践/2026-09-28-AgentScope-PoC验收报告.md`（G1–G5、C1–C12、差异登记 4 条）
3. PoC 代码实面：`.worktrees/poc-agentscope-kernel/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/{KernelScopeKey,PocKernelSupport,PocSseController}.java`
4. 自研 harness 实面：主仓 `ruoyi-modules/**/org/ruoyi/service/coding/harness/`（17 子包，逐能力对照现查）
5. 波次定义：`/Users/mac/Documents/最佳实践/2026-09-28-ruoyi-ai×AgentScope-Java单轨内核替换完整方案.md` §5.0（W1–W8）与 §10.2（W1 四硬前置）
6. B0 身份/门：commit `238dd9be`（卡 5c1cc6f8）+ `docs/ipd-系统说明/验收/2026-09-28-B0安全审计.md`
7. 编号空间：`真库fresh健康快照-20260919.md`（ADR-0074）、`R129-系统性根因反思-20260920.md`（ADR-001）、`IPD全阶段AI代理执行闭环设计-20260926.md`（ADR-1~4）；格式参考 `.agents/skills/domain-modeling/ADR-FORMAT.md`
8. 红线：仓库根 `AGENTS.md`（禁双轨、门禁扫描范围、成熟方案优先指令）

---

**结论（拟议目标）**：按「成熟方案优先、单轨不留双实现」推进目标替换 6 项（#2 条件式）、包装 11 项、保留 7 项；六问已有设计答案，W1–W8 有入/出条件与回滚点，未证明缺口仍需逐项验收。**W1 已在 PoC 工作树实施但未切换正式运行；本 ADR 尚待审核。PocSseController 的请求参数 userId 模式不得进入正式面。**


## 8. 接续勘误与首批修复合同（codex-harness-rootfix-20260928）

本节更正前文受影响判断，其他在途设计保留；不是全量切换批准。

- 矩阵 #2 的原生串行化仅已证明 call 路径，W1 streamEvents 同键重叠；因此 W7 删除旧 SessionGate 的决定暂缓，必须先验正式流路径。
- CompactionConfig 在锁定 2.0.3 中有 triggerTokens/keepTokens；不得以“仅按条数”作为 ContextEngine 保留理由。
- PoC WS 工作树已新增委托，待真实握手/HTTP/DB 验收；主树尚未接入。
- 首批修复：缓存键按 agentId 和完整 systemPrompt 比较，禁止仅以32位哈希判配置相等；同步装配和异步流错误统一输出固定安全文案，错误码保留，服务端只记录异常类型以避免未脱敏异常进入日志。
- 验收：同agentId使用Aa/BB碰撞提示词必须得到两个正确实例，相同配置复用；异常含SECRET_CANARY时sink不得收到sentinel或异常类文本；后续实例换代/内存上限/同键串行另验，缓存修复不等于这些问题已完成。
- W7仍为自研harness收编（含记忆/Plan等）；W8仍为aiflow/IPD AI链路。新实施稿的“W8收编清理”映射回W7，不改上游波次。

### 同键流式与W1工具边界（本轮实验合同）

- 锁定2.0.3支持原生LocalSessionTurnGate，单例桥按scope.slotId获取lease，Flux.using在完成/错误/取消后释放。直接包装streamEvents，不经会重写sessionId的HarnessGateway。此为进程内方案；等待中取消、多副本、长期键回收和端到端断连仍待验。
- 修改前真实PoC库回归要求maxActive=1而得到2（EXIT=1），原“knownGap绿色”不作为验收。
- 新日志证明默认HarnessAgent会注册write_file/edit_file/execute/web_fetch等内建工具；“未注册业务工具=没有工具”的推断不成立。W1纯对话应通过原生ToolsConfig拒绝工具并核对实际toolkit；W3权限/审批/账本前置验收后按单一目录开放。额外记忆钩子先关闭，长期记忆策略另验。

## 9. W1 在途边界与未决裁决（2026-09-28）

- 正式桥已拒绝空 `userId` 和含路径分隔符/`.` 的 `agentId`，保留 PoC `KernelScopeKey` 匿名桶仅供独立隔离实验。两层语义不得混称为“正式 SESSION 降级”。工作区当前仍按 agent 配置共用，四维文件隔离未验。
- 锁定 2.0.3 的 `enableAgentTracingLog(false)` 关闭正式桥默认原文 trace；未建立四维文件隔离前关闭 transcript 与本地 session 持久化。独立直接构建 `HarnessAgent` 的 PoC 测试仍可能出现 SDK trace，不能把它当正式桥日志。真库重启回读、日志脱敏和保留期仍须运行验收。
- 内核 `stream` 返回 Reactor `Disposable`；SSE 生命周期和 WS 关闭/传输错误绑定取消。取消模型与释放单进程门已由 stub 模型加 PoC 真库测试验证；真实客户端断开、超时、等待中取消、重复终态、多副本仍未验。
- 当前同键门仅为本进程 `LocalSessionTurnGate`；`HarnessAgent` 默认冲突策略不应被误认成跨副本 CAS 保证。多实例同键一致性、同 slot A→B→A 配置变更历史回读未完成，故“删除自研会话门”仍待对应业务域证明单门可替代。
- SSE 旧历史/RAG、模型选路、WS 持久化完成语义、正式 DDL/HTTP/WS/前端与 W2–W8 仍是 cutover 阻断。矩阵的替换/包装/保留属于拟议目标，只有同一能力的新旧业务语义与技术兼容性实证 100% 对齐时才可升级为已替换。

### W1 接续核验与状态源裁定

- 同 slot 系统提示词 A→B→A 的 PoC 真库三轮测试通过：第三轮模型输入含前两轮用户/助手消息，最终 `agent_state` 行含三个轮次标记；结论仅限单进程顺序执行。当前 2.0.3 每轮从 StateStore 重载状态，多副本 `LocalSessionTurnGate` 与默认 `OVERWRITE` 冲突策略仍缺证明。
- 矩阵 #5 的“替换”只可能指推理会话快照。**仅对继续保留的既有 ruoyi-chat SSE/`/chat/ws` 入口**，现有 `chat_message` 才是旧业务历史的兼容基准；正式 SSE 目前仍未将旧的近 20 条按角色历史注入，不能宣布该旧入口的业务记忆已等价替换。其历史读取、当前用户入库与模型执行须进入同一会话串行边界。**新建 IPD 项目/数字员工不必使用 `chat_message`，也不应默认做旧数据回填**；IPD 正式副驾走 `/api/v1/ai-copilot/chat/stream`，原文历史是否持久化及唯一权威须由其产品合同另定。`AgentState` 是可重建推理快照，不能自动充当不可变审计历史。这一范围勘误依据用户 2026-09-28 的明确指正，覆盖本 ADR 其他未限定范围的旧表表述。
- SSE 新委托已复用原 `augmentAgentInput`，将知识库选择和 `KnowledgeAccessGate` 放回内核执行之前；该局部修复不代表旧历史或真实向量检索验收通过。
- 状态库必须显式配置 `chat.kernel.agentscope.state-database`；已从 `ipd_poc` 当前真表 `SHOW CREATE TABLE` 生成非自动迁移的 [状态表 DDL 草案](../AgentScope-状态表DDL草案-20260928.sql)。本地 `ipd_dev` 与源码 dev 默认 `ruoyi-ai` 均未见此表；尚未执行 DDL、启用内核或在独立进程验证。

### V2 版本与模型路由续验（codex-agentscope-full-replacement-ux-20260928）

- 2026-09-28 核对 AgentScope Java 官方 GitHub release：`v2.0.3` 标记 Latest；PoC `ruoyi-chat/pom.xml` 的 AgentScope 依赖均锁定 `2.0.3`。本地 `/Users/mac/Documents/最佳实践/agent-scope/v2` 是能力研究材料，版本及实际行为仍以所锁 JAR、官方发布和运行试验核准。2.0.3 发布说明中的版本化状态、乐观并发、AG-UI 断连中断、应用层 RAG 示例等仅为可用能力线索，不代表本项目已接线或验收。
- PoC `KernelModelRequest.from(ChatModelVo)` 当前读 `chat_model`，不是 IPD `ai_model_configs`；旧 ruoyi-chat SSE/WS 调用方现已传入模型对象，但生产 `chat.kernel.agentscope.model-routing.enabled` 默认 `false`，未见正式启用配置，因此不能称请求选型已在真实入口生效。已修 `KernelModelSelector` 对显式模型装配失败的处理：记录不含凭据的错误类型并上抛，同时禁用 Harness 构建期隐式默认模型回退，避免执行模型与审计/预算身份不一致。默认模型只用于内核调用方没有给出模型的路径；WS 入口自身 `resolveDefaultModel()` 的业务语义仍须核验。
- 本轮相关路由测试先红后绿，定向 14 项 0 失败；只证明 PoC 选型失败封闭，不证明模型 A→B→A、密钥轮换缓存失效、真实模型调用、IPD 业务配置生效或 W2 完成。正式接线前须给 Agent 实例缓存键加入配置版本/端点/密钥轮换标识并验证实际执行与回读，禁止配置修改后复用旧实例。
- `fallback(ModelPlan)` 原已恒返回 `null`，现删除该死方法及 `HarnessAgent.Builder.fallbackModel` 条件分支，避免保留看似可用的第二执行路径；删除后相关相邻测试 48/48 通过。默认配置仍仅在模型请求缺省时由 `defaultPlan` 解析。
- 对本地 V2 资料逐 API 核对：`agent.md` 提到的 `prepareRun/AgentRun.cancel()` 在锁定的 2.0.3 core/harness JAR 中不存在；可用的是 `streamEvents`、`HarnessAgent.interrupt(RuntimeContext)` 与 Reactor `Disposable.dispose()`，但现有 SSE/WS 断连能否取消实际模型和副作用仍待真实客户端测试。官方 AG-UI starter 的断连中断能力不能直接算作本项目手写 SSE/WS 已接线。`MysqlAgentStateStore` 有版本化/CAS API，`ReActAgent.Builder` 有 `conflictPolicy(FAIL)`，但本轮未证 `HarnessAgent.Builder` 可配置相同策略，跨副本仍不可宣称安全。

## 10. 全量替换接续执行计划（2026-09-28；拟议执行顺序，非验收结论）

本节把用户要求的旧 AI 全量退场、正式 IPD AI 体验和生产证据映射到本 ADR 的 W1–W8。**本机看板卡 `61217664-73c1-4858-bd73-c3cfcaf13bcb` 是事项状态权威，主树镜像是同步投影**；本节只记录动作/门禁，不新增平行任务源。前端《开发计划-AI工作界面六阶段小阶段化》自身的 W1/W2 是另一计划的交付批次，不表示下表 AgentScope W1/W2 通过。每片先重查工作树、运行 JAR、真实库和卡面 allowedPaths；同一文件一名写入者，独立验证者直接看原始日志/响应。ADR 仍为 proposed，矩阵裁决未获业务等价证据前不得当作已替换。

### 10.1 执行图与并行边界

```text
F0 事实/合同/失败复现
 ├─ W1 旧入口协议与隔离 ─ W2 模型/预算 ─┬─ W3 工具/治理 ─ W5 MCP 单目录 ─┐
 │                                    ├─ W4 真实知识链/RAG ──────────────┤
 │                                    └─ W6 事件与观测 ───────────────────┤
 │                                    W1–W6 → W7 Harness 收编/旧实现退场 ┘
 │                                    W2+W4(+所用工具 W3/W5)+W6 → W8 IPD/aiflow
 └─ U0 线框/设计合同 ─ U1 AI 工作台/模型目录 ─ U2 知识/员工/MCP 页面
                           W1–W8+U0–U2 → G1 真端到端 → G2 切换/回滚 → G3 独立裁决
```

W3/W4 可在 W2 的模型/embedding 合同稳定后按不同文件面并行；U0 可与后端基线并行，U1/U2 只消费已核准的接口和真实状态。W6 在 W1/W2 事件合同确定后开始，并作为 W7/W8/G1 前置；W8 不使用某类工具时不凭空要求该工具通过，但凡调用 W3/W5 所管工具就须先通过其门禁。任何并行流不得各建模型路由、MCP 目录、检索权限裁决、事件发布器或看板状态源。

### 10.2 可领取工作包与通过门

| 节点 | 必须完成的动作 | 原始证据与通过条件 | 失败路径/回滚 |
|---|---|---|---|
| F0 事实与合同 | 逐入口列 `旧调用方→权限→模型/知识/工具→数据权威→前端消费者`；核真实流量/运行 JAR/库 catalog，保留新 IPD 与旧 ruoyi-chat 两种合同；为每项替换建正反例及删除清单 | 清册覆盖 SSE、`/chat/ws`、IPD Copilot/生成/建议/预审/比对、aiflow、知识摄取/检索、模型/供应商、数字员工、MCP、观测/预算；每项有对应调用方和恢复办法；未证项标待验证 | 范围/消费者冲突先留在既有看板卡，不先删旧入口/表 |
| W1 会话与协议 | 旧 SSE 历史按角色进入推理；读取、用户写入、推理、助手落库、审计纳入同轮门；WS 错误不得发成功 `[DONE]`，`done` 在必需落库成功之后；补断连取消、四维状态及工作区/文件隔离、重启/双副本失败恢复；正式 DDL 只在批准窗口执行 | 真实认证 HTTP/WS 与 DB 回读；SSE/WS 帧与原消费者等价；无权/跨项目/跨 Person/员工/会话负例不触模型或不读他域；断连后模型取消，错误/取消无成功帧；双副本同键不重叠且状态不丢 | 关闭入口切换开关并按原入口恢复；正式 DDL 与数据另列回滚，未批准前只保留草案 |
| W2 模型与费用 | `ai_model_configs` 成为 IPD 唯一模型权威；`chat_model` 旧入口逐消费者裁决；用实际 ModelRegistry/ModelCreationContext 装配所选供应商、端点、参数；配置版本驱动实例换代；usage→预算/审计唯一账本；密钥掩码/留空保留、连接诊断、SSRF 与 embedding 成对配置 | 真实模型 A→B→A、同名模型改端点/密钥后实际调用与回读一致；禁固定兜底；超预算不出站；usage/费用/审计匹配；模型配置授权及负例通过 | 选型/配置错误拒绝本轮；按入口互斥开关回到原实现，不能在一次调用内静默混用内核 |
| W3 工具与治理 | AgentScope Toolkit 只装单目录授权工具；`PolicyDecision` 仍是执行前裁决权威；DENY/ASK、一次性 claim、效果账本包围真实副作用，Plan revision/hash 与真人确认保持业务权威 | DENY/未批准 ASK 零副作用；批准恰一次、重试不重复；未知结果进核对而非报成功；同调用恰一裁决/账本/预算事件；跨域负例通过 | 工具目录整体关闭；未知副作用按账本补偿/人工核对，不自动重试 |
| W4 知识与记忆 | 授权先于检索；真实摄取、Tika/Reader 分块、embedding、向量后端、重排、引用来源、文档版本/审核/归档状态；对 AgentScope 2.0.3 弃用 RAG API 只做稳定应用层适配；长期记忆与原文历史分权威 | 真实 embedding/vector 正反例、项目/Person/文档无权负例、撤销/归档后不可命中、引用可回源；模型与嵌入端点成对有效；无 stub 命中冒充验收 | 检索故障或权限不明时不注入内容；保留旧数据与索引恢复路径，切换开关互斥 |
| W5 MCP | 将旧 LangChain4j MCP 注册/市场/连接配置映射为 AgentScope Toolkit 唯一目录，工具策略与目录由同一权威生成；连接状态、凭据 write-only、SSRF 与失败反馈可见 | 本地/远程注册、发现、失效、重新连接及权限正反例；目录漂移检查和无密钥回显；旧目录调用方归零后删除 | 禁用该 MCP 注册项，保留配置与事件；不能同时让两套目录执行同工具 |
| W6 观测 | 事件流和 Middleware 唯一出口，保留业务脱敏/审计；统一 runId、scope、model、usage、工具决策、来源、错误与终态 | 同一次请求事件/账本可关联、无原文/密钥泄露、失败/取消/未知副作用可追踪；监听器消费者归零后删除 | 关闭新采集出口并恢复旧观测单轨，不能双发造成重复账本 |
| W7 Harness 收编 | 对 24 项矩阵逐项验收后删除可替换旧执行循环/压缩/门/监听器；保留业务权限、账本、PlanAggregate、Artifact 生命周期；Skills/Plan/MEMORY.md 以真实 2.0.3 API 与沙箱实验核准 | 每项有旧/新业务语义与技术兼容对照、负例、调用方零残留、回滚差异；没有第二个可执行内核；自主演进只改经验证的 Skill/策略，不改审批/权限权威 | 单项失败保留旧实现但该入口不得双轨运行；撤销该项替换结论 |
| W8 IPD/aiflow | `AiCopilotService` 保留 Person/项目/业务编排与四帧合同，经窄 AgentScope 适配口执行；AG-UI/CopilotKit 仍单宿主；生成、建议、预审、比对、`AiDocEmbeddingService` 及 aiflow 节点逐调用方迁移；LangGraph4j 编排本身保留 | 每个入口真实模型/授权/预算/审计/结果回读/取消/失败负例；AG-UI 与 SSE 终态一致；`AiGateway` 全部活跃消费者清零后才删依赖与旧 API | 按入口互斥切回旧链并记录差异；不能把模型输出当业务批准、生成结果或人审完成 |
| U0–U2 正式 UI | U0 在现有 IPD token/Ant Design Vue 下提出 2–3 个任务导向线框并选一；U1 单一 `ai-assistant.vue` 工作台和模型目录/当前生效配置/诊断/影响预览；U2 员工、知识来源/索引/检索试验、MCP 能力目录与授权/结果页；保留现有卡片注册表和真人确认路径 | 逐页权限、创建/测试/启停/错误/空态/窄屏/键盘/焦点/对比度；真实后端状态驱动；`check:type`、指定 vitest、`build:antd`，浏览器点击和截图；旧导航/页面只有消费者归零才退场 | 界面逐页回退；不得用假完成/模拟命中或密集 Table+Modal 换肤冒充体验重构 |
| G1–G3 验收切换 | G1 联合后端/前端/DB/双副本/断连/浏览器；G2 列删除与数据保留清单、按入口演练切回和恢复；G3 独立复核覆盖矩阵并给业务、协议、数据、技术逐项结论 | 真服务/真配置/真认证原始证据齐全，所有本次覆盖项均满足业务语义及技术兼容 100% 对照；看板/镜像/代码/运行版本一致；无 P0 未决才可声明替换完成 | 任一必需证据缺失即 PARTIAL，不启用正式切换或删除保留数据 |

### 10.3 首三片领取顺序与停止条件

1. **先关 P0 完成语义**：给旧 SSE/WS 保存异常吞噬、WS `[DONE]` 先发、错误补 `[DONE]` 写失败复现，再让 `done` 只在定义内必需持久化/审计成功后出现；同时将同键门扩到业务准备和最终落库。此片不能顺手删 `chat_message`：它仅是仍服务的旧入口历史合同，新 IPD 原文持久化另作合同裁决。
2. **再做模型配置纵切**：给配置 A→B→A、同名改密钥/端点、超预算、SSRF 和无权选型写反例；实现 IPD `ai_model_configs` 到 AgentScope 的单一适配，不复用旧 `ChatModelVo` 作为 IPD 权威。旧 SSE/WS 虽已传模型对象，生产路由默认关闭且正式 JAR 未切；二者分别验收。
3. **再做真实知识纵切与 UI 方向**：把 REVIEWED/归档/版本及 KnowledgeAccessGate 负例放在检索前；接真实 embedding/vector 与引用。U0 同时在现有前端合同内给“项目 AI 桌面”“任务时间线”“资料与证据侧栏”三个简短方向，确定一套信息层级后实现，不另造聊天宿主。

各片在原卡登记 owner、allowedPaths、原始红/绿证据、未决风险与恢复点；不因经过天数或测试退出 0 自动晋级。用户未另行授权时不提交、推送、建业务分支、执行正式 DDL、部署或写线上数据。

### 10.4 全局一致性对账门（每片变更前后必跑）

| 轴 | 唯一权威/边界 | 不一致即阻断的检查 |
|---|---|---|
| 事项 | 本机看板卡状态；主树镜像只投影，ADR 只记决策/合同 | 卡面、镜像与当前代码/运行证据相互矛盾，先纠偏再领取下一片；不得以旧测试数字翻 done |
| 版本与能力 | 锁定的 AgentScope JAR/API + 当前代码；本地 V2 资料与官方发布是输入 | 文档有 `prepareRun` 等 JAR 不存在的 API，或把示例能力写成项目已接线，必须停止实现并修正文档 |
| 身份与数据 | IPD Person/项目/员工/会话四维业务 scope；各域原文历史/AgentState/审计/Artifact 各自权威 | 客户端自报 userId/threadId 决定权限或隔离；新 IPD 无合同却复用旧 `chat_message`；AgentState 被当不可变业务历史 |
| 模型/知识/工具 | IPD `ai_model_configs` 是 IPD 模型权威；知识授权 `KnowledgeAccessGate`；工具 `PolicyDecision` 与单目录 | 同一 IPD 请求又读 `chat_model` 作执行兜底；无权资料先检索后过滤；工具出现两个执行目录或两次裁决/账本 |
| 事件与成功 | 每个入口自己的 SSE/WS/AG-UI 合同；业务写入/审计决定 `done` | 旧 chat SSE、`/chat/ws` 和 IPD 四帧混成一种协议；保存/审计失败却发成功；取消仍继续产生业务副作用 |
| 界面 | 正式 Vue 的 `ai-assistant.vue` 单宿主、`card-registry.ts`、既有真人确认 API；`--ipd-*` token | 第二聊天宿主/卡片体系/写入通道；旧 React 原型直入正式前端；静态假进度或密集表单仅换色 |
| 替换裁决 | 本 ADR 24 项矩阵逐项业务语义和技术兼容证据 | 旧调用方未归零即删除，或旧/新内核在同一入口同时执行；PoC/stub/test exit0 被写成真实产品验收 |

此门为关系核对，不复制业务规则。每片用同一组业务样本对照“前端点击→认证入口→模型/知识/工具→DB/审计→事件终态→浏览器回读”，并附无权、失败、取消、重启和双副本负例；只有相关轴全部一致才推进下一片。

### 10.5 U0 正式前端方向与接口缺口（六专业组只读核验）

| 方向 | 首屏层级 | 主交互 | 裁决 |
|---|---|---|---|
| A 项目 AI 桌面 | 项目/Person/员工及真实模型状态 → 任务对话和步骤/卡片 → 来源/权限/证据/待真人确认；窄屏按此顺序折叠 | 从当前 IPD 任务发起，沿唯一 `ai-assistant.vue` 宿主查看运行、引用和可恢复动作 | **后续实现基准**；将 B 的时间线和 C 的来源预览作为此宿主内部区块 |
| B 任务时间线 | 运行事件、暂停/恢复、失败重试优先 | 适合长任务回顾 | 资料与权限线索首屏较弱，不另起宿主 |
| C 资料与证据侧栏 | 来源集合、索引与引用优先 | 适合检索试验 | 任务流和人工动作首屏较弱，不另起宿主 |

模型配置页采用“当前生效配置 + 模型目录卡 + 连接诊断/切换影响 + 渐进高级设置”，保留既有 `ai-model-config.ts` 授权 API。当前 API 只有列表/详情/创建/更新/启用/测试，不提供健康、费用或受影响项目/员工的权威聚合；这些区域在后端合同完成前只能显示明确的“暂无数据/待接入”，不能由前端估算伪造。现页 48 仍 Table+Modal Form，`ai-workspace` 的 steps/canvas/doc 尚为占位，`/ipd/documents` 仍复用传统知识 CRUD；`ai-docs` 的生成、审核、版本与归档链承担业务合同，改工作台时不可直接删。

现有路由注释称浮动 AI 入口已移除，但 `ipd.vue` 仍挂 `ai-assistant.vue`，后者仍渲染 `ipd-ai-fab`；U1 实施前须核真实浏览器与产品入口裁决并统一注释、导航及实际按钮。页 48 route access 与按钮 edit 权限各自存在，须用只读/可写账号核是否误挡只读目录。当前这些是**设计/源码核验**，不代表视觉、交互、权限或浏览器验收通过。前端在途文件由其他 writer 占用，本轮不覆盖；按“模型目录页→唯一 AI 工作台→知识/文档页→员工/MCP 页”的互斥文件序列实施。

### 10.6 六专业首轮执行事实与复验顺序

- W1（协议）：PoC WS 入口现对内核和旧流分支统一要求用户写入成功才推理、助手写入成功才调用 `[DONE]`；部分错误发固定安全错误。因此旧分支协议/保存行为也发生变化，必须补旧消费者兼容回归，不能仅按内核测试裁决。新顺序/失败测试已写，但 Maven 在 testCompile 缺类、MapStruct NPE、FilerException 阶段阻断，**0 新断言执行**；真 WS/DB/浏览器无证据，状态 PENDING_VALIDATION。`sendMessage` 无客户端 ACK，只能断言服务端调用顺序，不能证明客户端收到。
- W2（模型）：PoC Agent 缓存键增加模型配置摘要，同名模型改端点/凭据不再命中旧键；A→B→A/脱敏测试已写。Maven 在 MapStruct compile 阶段阻断，**0 新断言执行**；旧实例等 kernel.close 才回收，频繁轮换可能累积；IPD `ai_model_configs` 尚未接线，状态 PENDING_VALIDATION。
- W4（知识）：主树 `AiDocEmbeddingService` 查询当前同项目 REVIEWED 文档（逻辑删除过滤）后约束向量 docId，补归档残留负例；静态 `git diff --check` 通过。`ruoyi-ipd` 对本地 `ruoyi-chat` 依赖缺符号，测试在断言前阻断；`AiGenerationService` 项目可见性未收口，全部审核文档 ID 的 `IN` 规模/执行计划未验，状态 PENDING_VALIDATION。
- W3/W5（工具）：只读核到 2.0.3 Toolkit/MCP API 和旧链消费者；`tools.json` 是共享 build-time 投影，不是租户权限权威；`readOnlyHint`/ASK 不能越过 `PolicyDecision`/一次性 claim/效果账本。尚未改代码或跑产品负例。
- U0（前端）：三方向及 A 的后续实现基准见 §10.5。正式 Vue 页面仍有 Table+Modal、工作区占位、路由注释与浮动按钮矛盾；前端在途写面未覆盖，浏览器未验。

**复验顺序**：先由共享工作树 owner 错峰恢复 PoC MapStruct 生成产物与主树 `ruoyi-chat` 本地依赖的一致性（不可把依赖故障算测试红例；不并发 `-am`/`clean`），随后分别重跑 W1/W2/W4 定向红绿并由独立验证者读取原始输出；再做真实 HTTP/WS/DB/模型/向量/浏览器和双副本。构建未恢复前保持 PENDING_VALIDATION，任何局部代码变更均不得推升 W1/W2/W4 完成状态。

### 2026-09-28 隔离构建复验补记

- W1/W2：将当前 PoC 源码复制到 `/tmp/agentscope-isolated-20260928.xbq6aY`（排除 `.git`、`target` 和嵌套工作树），避开共享生成产物，不修改共享 `target`。`/tmp/agentscope-isolated-target-tests-20260928.log` 显示定向 25/25，`/tmp/agentscope-isolated-adjacent-20260928.log` 显示相邻 53/53，均 BUILD SUCCESS；相关七个源码/测试文件与 PoC 当前文件 `cmp` 一致。因此上述“0 新断言执行”只描述原共享工作树构建尝试，W1/W2 局部单测已获得独立源码快照证据。仍未证明旧 WS 消费者兼容、真实 WS/DB、模型配置运行回读或生产切换。
- W4：主树 `AiDocEmbeddingService` 与同名测试在临时输出目录独立编译；补全纯 Mockito 测试的 MyBatis-Plus 表元数据初始化，查询条件断言先生成 SQL 片段再读参数。`/tmp/ipd-rag-isolated-tests-20260928.log` 为 14/14，含“已归档文档残留向量不得进入提示词”负例。独立运行器不替代模块 Maven/真实向量服务；项目可见性、真实权限与大 ID 集合性能仍待验。
- 全局状态仍为 **PARTIAL**。W1/W2/W4 仅局部代码与单测证据提升，W1–W8/U0–U2/G1–G3 的业务语义和技术兼容性门禁未全过；不可据此声明 AgentScope 全量替换或生产就绪。
- W1 追加显式模型防错：`/chat/ws` 在所选模型名或智能体绑定模型 ID 查无配置时立即返回安全错误，不再调用默认模型；补负例后从当前 PoC 源码更新隔离快照，`/tmp/agentscope-isolated-target-tests-20260928c.log` 为 26/26、BUILD SUCCESS。该证明只覆盖 WS 局部，尚未验真实消费者和业务配置。
- 用户后续明确授权清理旧代码、整合工作树、合并、提交、推送；执行门仍须先完成旧消费者/权限/协议/数据等价和正式验收。当前主树与正式前端均有其他在途未提交修改，整树合并会混入无关差异；按文件清册与独立证据确认归属后再进入提交与合并门。

### PoC 正式产品化核对（2026-09-28）

- 当前正式 `chat.kernel` 的调用方是旧 `ChatServiceFacade` SSE 与 `MpChatWebSocketHandler` WS，`chat.kernel.agentscope.enabled` 与模型路由默认仍关闭；IPD `AiCopilotService`、`AiGenerationService`、`GatePrecheckService`、建议及投标相关服务仍消费 `AiGateway`，`ai_model_configs` 尚未成为 AgentScope 装配权威。先按这些调用方逐项接线和回归，不能直接删 `AiGateway` 或宣布双轨结束。
- 正式前端已有 `ai-copilot.ts` 的 Bearer SSE `meta/delta/done/error`、单一 `ai-assistant.vue` 宿主及卡片确认通道。当前 AI 工作区的 steps/canvas/doc 仍含占位，历史传递被注释；读流异常/断连的取消和收尾、保存的工作区模式与展开状态一致性待修复。PoC 不得另开第二套前端事件或业务提交通道。
- `KernelScopeKey` 仍位于 `poc/kernel` 且被正式内核引用。清理实验包前需迁入正式包并保持四维键等价；`PocKernelSupport`、`PocSseController`、`PocSseApplication` 与现存 PoC IT 存在测试依赖，须成组迁移或退役。旧 AI 删除以零活跃消费者及运行兼容证据为准。
- 当前 main 比 PoC 分支有 11 个独立提交，PoC 有 7 个独立提交，且两树及前端均有未提交在途改动。提交前门禁 `check-doc-db-drift.sh --refined --json-only` 扫描超过 6 分钟无新输出，本轮主动中止（exit 130），未绕过门禁；已暂存的 16 个 PoC 归属文件仍在索引中，**没有形成提交、合并或推送**。需先诊断门禁耗时/结果，之后按归属独立提交，再解决 merge-tree 差异并做产品验收。
- 已将 `KernelScopeKey` 从 `chat.poc.kernel` 迁至正式 `chat.kernel`，正式桥、PoC 样例及原隔离测试共同引用同一实现；隔离源码快照定向 38/38、BUILD SUCCESS（`/tmp/agentscope-isolated-scope-move-20260928c.log`）。这是代码包边界清理，尚未清理 PoC 样例入口或旧 AI 执行链；跨副本与工作区文件隔离仍未验。
- W1 SSE 委托收口：断连/超时/错误后设置终态，晚到回调不再发帧或保存助手消息；助手完成用 `insertByBo` 可观察结果代替吞异常的 `saveChatMessage`，失败只发 error，不发 done。先红后修，隔离快照 `/tmp/agentscope-isolated-sse-persist-20260928c.log` 为 10/10、BUILD SUCCESS。用户消息写入、真实 SSE/DB 和客户端收帧未验，旧 SSE 分支未按此修改。
- 正式 Vue `ai-copilot.ts` 读流异常现在给固定安全 `TRANSPORT` 错误，主动 Abort 静默并释放 reader；聚焦 vitest 17/17。全仓类型检查被在途 `ai-guide/guide-script.test.ts` 语法错误阻断，不能记作前端完整三绿。前端旧页面与占位工作区尚未完成。
- W1 WS 生命周期独立复核发现关闭连接和注册交错时 `activeKernelStreams` 可能留空集合；终态和连接关闭后已原子移除空登记，补“注册前连接关闭”负例，隔离快照定向 11/11（`/tmp/agentscope-isolated-ws-lifecycle-20260928b.log`）。同轮复核还指出 SSE 完成与断连并发时业务提交时点须实测、旧 WS fallback 中途失败帧语义有兼容变化；两项仍列未验收，不以当前绿例替代竞态/旧消费者验收。
- W2 主树适配口仍未创建：`KernelModelRequest` 尚未合入主树，直接新增无消费者的 IPD spec 会成为第二套配置映射。先完成 PoC 窄接口的正式合入，再由 IPD `ai_model_configs` 唯一生效配置与现有 SSRF 语义装配；七类旧 `AiGateway` 消费者逐入口互斥迁移。
- 正式运行只读复核：`ruoyi-admin/target/ruoyi-admin.jar` 中 AgentScope 依赖数为 0、所含 `ruoyi-chat` 无 `AgentScopeChatKernel.class`；本机 16039/15666 有监听，但 `ipd_dev.agentscope_sessions` 表不存在（information_schema 计数 0）。因此正式入口→AgentScope→正式库同轮落库与重启回读当前没有可执行前提；`ipd_poc` 旧数据只能证明 PoC。正式 DDL、启动切换、真实 Person HTTP/WS/DB/浏览器与双副本验收前，状态不得高于 PARTIAL。
- 提交门禁根因：`check-doc-db-drift.sh --refined` 旧脚本未把仓库根传入 awk 且仅取每个文档首行，精炼输出文件不存在时继续使用空计数，出现 `pass=true` 与非空 mismatch 列表同在的假绿。现改为按引用行读取、缺文件/缺行即失败并检验 awk 退出；`bash -n` 通过，完整 809 文档真库门禁正在复跑，结果前不允许提交或推送。
- 当前 PoC 源码复制到隔离快照后的 W1/W2 相邻定向 `/tmp/agentscope-isolated-w1-adjacent-20260928.log` 为 54/54、BUILD SUCCESS；只覆盖列出的七个测试类，不提升正式运行或 W1–W8 完成状态。
- 根因续验：修复仓根/引用行后全量门禁退出 2，原始 stderr 为 macOS awk 多字节转换失败（文档中有无效 UTF-8 字节）；精炼阶段仅匹配 ASCII 规则，已固定 `LC_ALL=C` 字节模式并用非法字节小样本及 `bash -n` 验证，全量门禁再次复跑中。正式前端 `guide-script.test.ts` 的 `**/` 在块注释内提前结束注释已修，但其 API/解析器/组件源文件仍缺，typecheck 与相关 vitest 继续失败；不能靠删除测试或伪实现宣称前端三绿。
- SSE 完成/断连竞争收口：PoC `ChatServiceFacade` 内核分支用同一生命周期锁串行处理 cancel、增量、error、complete 和必需助手写入；完成先赢则写入/终帧后处理断连，取消先赢则晚到回调无写入/成功帧。隔离快照 `/tmp/agentscope-isolated-sse-race-20260928.log` 现有 10/10；真连接竞态与 DB 回读仍待验。

## 11. 未尽事项（2026-09-28 W2 收口裁定增补）

W2 波次收口同步（与 §6 U 表、W2 补证清单互为引用）：

| # | 未尽事项 | 状态 | 归属 |
|---|---|---|---|
| 1 | C1 逐厂商冒烟（需 8 厂商真实 API 凭据） | BLOCKED_ENVIRONMENT 挂账，待凭据环境补证 | W2 补证清单（C1） |
| 2 | C9 端到端：`ai_model_configs` 表驱动切换实测（U9；现装配源仍 `chat_model`） | 挂账，待真库 + 路由开关开启后补证 | W2 补证清单（C9） |
| 3 | 13 家 langchain4j 直连（`HarnessChatModelFactory` 等）删除时点 | 保留至 W2 回滚窗口关闭；删除时点单独定案（矩阵 #8 补注 M1） | 回滚窗口关闭时定案 |
| 4 | U13 `AgentEvent` usage 计量 → 预算计量器（喂 `HarnessBudgetPolicy`） | 未闭（usage 实证与计量器接线待续） | W2（计量）遗留 |
| 5 | 验收口径文档化（62/62、11+1、18、10、并发等裸数字口径） | **已解决**：[agentscope-验收口径基准](./agentscope-验收口径基准.md) 已落成，后续波次验收引用该文件 | 已闭环（2026-09-28） |

## 附录 · P-14 升级记录（2026-09-29）

**升级动作**：状态字段由 `proposed` → `partially-approved`。

**owner 授权**（2026-09-29「按照推荐完整执行」）：同意 3 条红线组合推荐：
1. **P-14 ADR-0075 状态升级** = 分阶段升（本轮动作）
2. **P-12 G1-G10 升判据** = 拆分升 Phase 1（G3 langchain4j 棘轮 + G8 双 bean 消歧）
3. **P-09 snail-ai 退场波次** = 3 波分批（W1 订阅桥接退场 / W2 chatSync 2 直连改造 / W3 RAG 另接）

**`partially-approved` 含义**（**不是全量 `approved`**）：

| 已裁决部分（**生效**） | 未裁决部分（**维持 `proposed`**） |
|---|---|
| P-01 表 1 全 24 项三选一建议采纳 | P-07 AG-UI 双栈收敛方案 |
| P-02 #10 预算表述订正 | P-08 AgentScopeDeAgentRunPort 装配接线 |
| P-03 #5 Memory 会话 Store 范围限缩 | P-09 snail-ai 退场波次（W2/W3） |
| P-04 #14 副作用幂等「保留」+ 不得删除 | P-12 G1-G10 Phase 2（G1/G5/G6/G9/G10 拆分） |
| P-05 AgentEvent usage 计量实证（部分有） | P-13 仓外 28KB 方案适用性 |
| P-06 enablePendingToolRecovery 语义实证 | P-15 已 RESOLVED（另见 2026-09-29 AG-1 starter 解锁） |

**升 `approved` 门槛**（未达）：
- 15 项 U 表全清零或豁免登记（`:188-208`）
- P-07 / P-08 / P-09 / P-12 / P-13 全部 RESOLVED
- 62/62 验收口径 + W2 补证清单 + 回滚窗口关闭

**红线**：
- **未升 `approved` 前**，一切替换/包装动作仅限 **设计与 PoC**；**不得**称「切换许可证」
- **禁双轨**（§1.1）：任一时刻生产入口只有一个内核在跑
- **OI-019**：改判定范围 / 阈值 / 退出码 = 红线（升门禁另批 owner 二次签字）

**触发后 do**：
- P-01..P-06 各项按已裁决生效（**owner 落签字**在 §裁决登记）
- P-09 W1 订阅桥接退场（**已由 agent 落地**）；W2/W3 待续
- P-12 Phase 1 G3+G8 升判据（**已由 agent 落地**）；Phase 2 拆分待续
- 全 3 条红线 RESOLVED 后可申请升 `approved`（**另批 owner 二次签字**）

**不变**：
- 本节**不改判定范围**（依「订正过期事实 ≠ 改判定范围」）
- 原文 `proposed` 字面**已划线保留**（不抹痕）
- 升级是**状态字段变更**（`proposed` → `partially-approved`），不改任何裁决内容

**记录人**：AI（本会话）· **owner 授权**：2026-09-29「按照推荐完整执行」
**升级 commit**：见 ruoyi-ai 仓对应 commit SHA
