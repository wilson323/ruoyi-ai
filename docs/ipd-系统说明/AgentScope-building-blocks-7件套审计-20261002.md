# AgentScope 官方 building-blocks 7 件套充分应用审计

- 审计日期：2026-10-02
- 审计对象：`/Users/mac/Documents/ruoyi-ai`（AgentScope Java **2.0.3**，`pom.xml:16`）
- 官方事实源：`/Users/mac/Documents/agentscope-java`（git HEAD `e9721285`，`revision=2.0.4-SNAPSHOT`；2.0.3 参照 tag `v2.0.3`）
- 官方文档：`docs/v2/zh/docs/building-blocks/` 下 7 篇，**全部实读**（agent / context / message-and-event / middleware / model / permission-system / tool）
- 本次审计**未修改任何 `src/` 文件、未改 `pom.xml`、未改 `yml`**，只新增本文件

---

## §0 审计范围、方法与证据分级

### §0.1 三个（实为四个）来源层级的区分

任务书要求区分三层。实读后发现官方存在**第四个正交维度**，必须单列，否则后面所有结论都会归错账：

| 层级 | 包名前缀 | 归属 Maven 模块 | 说明 |
|---|---|---|---|
| L1 官方核心 | `io.agentscope.core.*` | `agentscope-core` | 7 件套本体 |
| L2 官方扩展 | `io.agentscope.extensions.*` | `agentscope-extensions-*` | 正常命名 |
| L3 harness | `io.agentscope.harness.*` | `agentscope-harness` | ReActAgent 的薄包装层 |
| **L4 命名泄漏** | **`io.agentscope.core.*` 但不在 core** | `agentscope-extensions-rag-simple` | **包名说 core，实际在 extensions** |

**L4 已实证**（`git ls-tree v2.0.3`）：

- `io.agentscope.core.embedding.*`（`EmbeddingModel` / `DashScopeTextEmbedding` / `OllamaTextEmbedding` / `OpenAITextEmbedding` / `DashScopeMultiModalEmbedding`）→ 物理位于 `agentscope-extensions/agentscope-extensions-rag-simple/`
- `io.agentscope.core.rag.store.InMemoryStore`、`io.agentscope.core.rag.knowledge.SimpleKnowledge` → 同上
- `agentscope-core` 在 v2.0.3 的顶层包清单里**没有** `embedding`、也没有 `rag/store`、`rag/knowledge`（已实证：`git ls-tree --name-only v2.0.3 agentscope-core/.../core/` 输出 21 项，无这三项）

该搬迁由官方 commit `07783d92 refactor: move embedding to rag-simple (#133)` 完成（已实证）。

**审计含义**：只看 import 前缀无法判断类来自哪个 jar。本仓 21 处 `io.agentscope.core.embedding.*` import 若按前缀归类会被误判为「用了 core」，实际依赖的是 `agentscope-extensions-rag-simple`（该依赖确实已声明，见 §2）。

### §0.2 证据分级定义

| 标记 | 含义 |
|---|---|
| **【已实证】** | 我本人执行了 Read / `find` / `grep` / `git ls-tree`，看到了字节或确切的引用计数 |
| **【仅文档提及】** | 该能力在官方 `building-blocks/*.md` 中被描述，但我在**项目源码中 grep 命中数为 0**，或我未能在官方源码中定位到对应实现 |
| **【未实证】** | 我没有读到足够证据，不下结论 |

**方法级保留（必须随本报告一起读）**：

- 官方 7 件套的**类名清单**来自对 `agentscope-core/src/main/java/io/agentscope/core/` 的 `find` 目录枚举（已实证类名与文件存在）。
- 官方**方法签名**多数转述自 7 篇官方文档，**未逐个 javap 反编译 2.0.3 jar 字节码**。凡本报告写出的官方方法名，均注明是「文档口径」还是「源码口径」。
- `HarnessAgent.java` 我实读了 §1225-1240、§1880-1935、§2235-2305、grep 全量 builder 方法（已实证）。
- `PermissionEngine.java` / `PermissionDecision.java` / `ToolBase.java` 等**未逐行读源码**，其语义转述自 `permission-system.md` / `tool.md`（已标注）。

### §0.3 装配点全景（本次审计最重要的结构性事实）

全仓 `HarnessAgent.builder()` 生产装配点共 **5 处**（已实证，grep `HarnessAgent.builder()` 于 `*/src`）：

| # | 文件 | 行 | 性质 |
|---|---|---|---|
| A1 | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeChatKernel.java` | 325 | 主聊天内核 |
| A2 | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java` | 265 | IPD 项目智能体 |
| A3 | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness/loop/DurableHarnessRunProcessor.java` | 417 | durable 编码泳道（**单文件 5,649 行 / 330 KB**） |
| A4 | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/impl/CodingServiceImpl.java` | 115 | 编程助手 |
| A5 | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/PocKernelSupport.java` | 54 | **PoC，却位于 main 源树** |

自研替代面体量（已实证，`find + wc -l`）：

| 目录 | 文件数 | 行数 |
|---|---|---|
| `ruoyi-chat/.../service/coding/harness` | 256 | 31,499 |
| `ruoyi-ipd/.../ipd/agent` | 60 | 8,817 |
| `ruoyi-chat/.../chat/kernel` | 17 | 1,604 |
| **合计** | **333** | **41,920** |

与任务书所述「约 42,000 行自研顶替」**吻合（已实证）**。

---

## §1 7 件套逐件审计

评级口径：

- **充分应用** —— 官方抽象被当作唯一实现面使用，自研只做「官方扩展点内的策略注入」
- **部分应用** —— 官方抽象被采用，但存在子能力未走官方、或官方抽象被旁路
- **未采用** —— 官方抽象在本项目中找不到实质使用

---

### 1.1 Agent（`io.agentscope.core.agent.*` / `io.agentscope.core.ReActAgent`）

**官方核心类清单**（已实证，`find` 枚举 `core/agent/` 27 个文件 + `core/ReActAgent.java`）：

`ReActAgent`（默认实现）、`agent/Agent`、`agent/AgentBase`、`agent/CallableAgent`、`agent/StreamableAgent`、`agent/ObservableAgent`、`agent/RuntimeContext`、`agent/AgentRun`、`agent/RunControl`、`agent/StreamingHook`、`agent/StreamOptions`、`agent/SubagentEventBus`、`agent/EventSource`、`agent/config/{ReactConfig,ModelConfig,FailoverListener}`、`agent/accumulator/{ContentAccumulator,TextAccumulator,ThinkingAccumulator,ToolCallsAccumulator,ReasoningContext,ServerToolResultAccumulator}`、`agent/user/{UserAgent,UserInputBase,UserInputData,StreamUserInput}`

**项目侧实际使用**（已实证，import 计数）：

| 官方类 | 引用数 | 项目文件 |
|---|---|---|
| `io.agentscope.harness.agent.HarnessAgent` | 14 | 5 个装配点（§0.3） |
| `io.agentscope.core.agent.Agent` | 2 | `AgentScopeProjectAgentKernel.java`（middleware 签名） |
| `io.agentscope.core.agent.RuntimeContext` | 7 | `KernelScopeKey.toRuntimeContext()` 等 |
| `io.agentscope.core.agent.accumulator.ReasoningContext` | 1 | 导入 |

**采用度评级：部分应用**

判定依据：走 `HarnessAgent` 这一官方统一入口（**不是**散用 `ReActAgent`），5 个装配点全部用 `builder()`，身份全部经 `RuntimeContext` —— 方向正确。但官方 Agent 面有一整块能力**完全未采用**，且**存在一个自研 ReAct 循环旁路**（见 §3 风险 R1）。

**缺口**（全部为 grep 命中 0，**【仅文档提及】**）：

| 官方能力 | 文档位置 | 项目引用 |
|---|---|---|
| `prepareRun` / `prepareCall` / `AgentRun` 句柄（细粒度取消） | agent.md §控制单次执行 | **0** |
| `observe()`（多 agent 观察） | agent.md §observe | **0**（`grep "\.observe("` 无命中） |
| `clearContext()` | context.md §清空会话对话上下文 | **0**（`P073BehaviorAcceptanceTest` 的 `clearContext` 是项目自定义接口，非官方） |
| `call(msgs, Schema.class)` 结构化输出 + `getStructuredData` | agent.md / model.md §结构化输出 | **0** |
| `maxRetries()` / `fallbackModel()` / `failoverListener()` | agent.md §模型容错 | **0**（`fallbackModel` 命中均为本地变量名） |
| `getAgentState(userId, sessionId)` | context.md §直接读写 AgentState | **1**（`DurableHarnessRunProcessor.java:434`） |
| `RuntimeContext.resolveAgentState(ctx, agent)` | context.md Tip | **0** |
| `agent.interrupt(...)` | agent.md §中断执行 | **1**（`AgentScopeProjectAgentKernel.java:200`，正确用法） |
| `getDelegate()`（拿到底层 ReActAgent） | — | **1**（`DurableHarnessRunProcessor.java:434`） |

补充说明：`getDelegate()` 的使用出现在 A3 装配点，即**官方明确鼓励「用 harness 就不要碰 delegate」的位置，项目反向依赖了 delegate**。这一点在官方文档中未列为推荐用法，故标 **【仅文档提及】**。

---

### 1.2 Context 与 AgentState（`io.agentscope.core.state.*`）

**官方核心类清单**（已实证，`core/state/` 17 文件）：
`AgentState`、`AgentStateStore`、`State`、`VersionedState`、`ReadCacheEntry`、`ConflictPolicy`、`ConcurrentSessionModificationException`、`InMemoryAgentStateStore`、`JsonFileAgentStateStore`、`TaskContextState`、`Task`、`ToolContextState`、`PlanModeContextState`、`SessionInfo`、`ListHashUtil`、`legacy/ToolkitState`、`legacy/LegacyStateLoader`

扩展实现：`extensions-redis` → `RedisAgentStateStore` / `RedisClientAdapter` / `redisson.RedissonClientAdapter`；`extensions-mysql` → `MysqlAgentStateStore`

**项目侧实际使用**（已实证）：

| 文件 | 官方类 | 性质 |
|---|---|---|
| `chat/kernel/AgentScopeRedisStateStores.java` | 实现 `RedisClientAdapter`（`Redisson351Adapter` 内部类） | 官方扩展点，**只替换 1 个枚举**（`RScript.ReturnType.INTEGER` 替代 4.2 才有的 `LONG`），其余 11 个方法全委托 SDK |
| `chat/kernel/FailClosedAgentStateStore.java` | 实现 `AgentStateStore` 全部 11 个方法 | 官方扩展点，**CAS 冲突抛错**替代 SDK 2.0.3 未暴露的默认覆盖恢复 |
| `ipd/agent/kernel/ProjectAgentTemporaryStateStore.java` | 实现 `AgentStateStore` 全部 11 个方法 | 官方扩展点，run 生命周期封存 + scope 校验 + ownership 事务 |
| `chat/kernel/AgentScopeChatKernel.java:94-110` | `MysqlAgentStateStore`（仅测试夹具构造器） | 官方扩展 |
| `chat/harness/loop/DurableHarnessRunProcessor.java:410` | `InMemoryAgentStateStore`（A3 用作**投影**） | 官方扩展 |
| `ipd/agent/kernel/AgentScopeProjectAgentKernel.java:104` | `InMemoryAgentStateStore`（默认值，可被 `setStateStore` 覆盖） | 官方扩展 |
| import 计数 | `AgentStateStore` 3 / `AgentState` 3 / `InMemoryAgentStateStore` 4 / `VersionedState` 2 / `State` 1 | — |

**采用度评级：充分应用**（3 个项目 store 全部实现官方 `AgentStateStore` 接口，无一处自建状态抽象）

**缺口**：

1. `ConflictPolicy` 官方枚举 —— **0 引用【仅文档提及】**。三个包装 store 各自用 `if (delegate.supportsVersioning() && version == UNVERSIONED) throw` 手写同一条判据，属**逻辑重复三遍**，官方已有该抽象。
2. `AgentState.getSummary()` / `setSummary()` / `getPlanModeContext()` / `getTasksContext()` / `getToolContext()` —— 项目侧 **0 直接调用【仅文档提及】**（A3 内部有自研 compaction state，见 §3 风险 R2）。
3. `JsonFileAgentStateStore` —— **0**。`context.md` 标注它是 **HarnessAgent 的默认值**（`~/.agentscope/state/<agentId>/`），意味着未显式配 `stateStore` 的装配点会**静默往用户主目录写文件**。A5 `PocKernelSupport` 显式配了 store，但若未来新增装配点漏配即触发（见 §3 风险 R3）。
4. **`context.md` 的分布式硬约束未触发**：官方 Warning 规定「用了 `filesystem(SandboxFilesystemSpec)` / `RemoteFilesystemSpec` 就必须配分布式 store，否则 `build()` 抛 `IllegalStateException`」。本项目全部走本地 `Path workspace`，未触发 —— **这不是缺陷，但意味着该安全网在本项目从未被验证过【未实证其是否真会抛】**。

---

### 1.3 Message 与 Event（`io.agentscope.core.message.*` / `io.agentscope.core.event.*`）

**官方核心类清单**（已实证）：

- `message/`（23 文件）：`Msg`、`MsgRole`、`ContentBlock`、`TextBlock`、`DataBlock`、`ImageBlock`、`AudioBlock`、`VideoBlock`、`ThinkingBlock`、`ToolUseBlock`、`ToolResultBlock`、`HintBlock`、`ToolCallState`、`ToolResultState`、`GenerateReason`、`UserMessage`、`AssistantMessage`、`SystemMessage`、`ToolResultMessage`、`Source`、`Base64Source`、`URLSource`、`MessageMetadataKeys`
- `event/`（35 文件）：`AgentEvent`、`AgentEventType`、`AgentStartEvent`、`AgentEndEvent`、`AgentResultEvent`、`ExceedMaxItersEvent`、`RequestStopEvent`、`CustomEvent`、`AgentEventEmitter`、`Text/Thinking/Data Block {Start,Delta,End}Event`、`ToolCall{Start,Delta,End}Event`、`ToolResult{Start,TextDelta,DataDelta,End}Event`、`ModelCall{Start,End}Event`、`RequireUserConfirmEvent`、`RequireExternalExecutionEvent`、`UserConfirmResultEvent`、`ExternalExecutionResultEvent`、`AllToolsDeniedEvent`、`SubagentExposedEvent`、`HintBlockEvent`、`ConfirmResult`

**项目侧实际使用**（已实证，import 计数）：

| 官方类 | 计数 | 项目文件 |
|---|---|---|
| `message.Msg` | **23** | 全线主干 |
| `message.TextBlock` | 19 | 事件拼装 / 工具结果 |
| `message.MsgRole` | 14 | — |
| `message.ContentBlock` | 9 | — |
| `message.ToolUseBlock` / `ToolResultBlock` | 8 / 8 | `KernelGovernedTool` / `ProductLineMcpTool` |
| `message.ToolResultState` | 2 | `KernelGovernedTool.java:81,87` |
| `message.ImageBlock` | 1 | — |
| `event.AgentEvent` | 6 | 两个 EventBridge |
| `event.TextBlockDeltaEvent` | 5 | — |
| `event.ToolCallStartEvent` | 3 | `AgentScopeProjectAgentKernel.EventBridge` |
| `event.AgentResultEvent` | 2 | `KernelFinalResponse` |
| `event.ThinkingBlockDeltaEvent` | 2 | — |
| `event.ToolResultEndEvent` / `ModelCallStartEvent` / `ModelCallEndEvent` / `ExceedMaxItersEvent` | 各 1 | `AgentScopeProjectAgentKernel` |
| `event.AgentEventType`（官方） | **0** | — |

**采用度评级：充分应用**（消息侧 23 处 `Msg` 是全仓主干；事件侧 A2 的 `EventBridge` 严格只映射合同事件，未外发推理原文）

**缺口与风险**：

1. **`Msg.getGenerateReason()` 全程未读 —— 0 引用【仅文档提及】**。官方用它区分 `INTERRUPTED` / `MAX_ITERATIONS` / `ALL_TOOLS_DENIED` / `TOOL_SUSPENDED` / `PERMISSION_ASKING`。项目 A2 用自研 `ERR_RUN_TIMEOUT` 错误码表达超时（`AgentScopeProjectAgentKernel.java:96,536`），**与官方 `GenerateReason` 是两套并行的终止原因体系**（见 §3 风险 R4）。
2. **35 个官方事件类型只消费了 8 个**。未消费的包括整个 HITL 家族（`RequireUserConfirmEvent` / `UserConfirmResultEvent` / `RequireExternalExecutionEvent` / `ExternalExecutionResultEvent` / `AllToolsDeniedEvent`）与 `SubagentExposedEvent`【仅文档提及】。
3. **⚠️ 类名撞车（已实证）**：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/model/AgentEventType.java` 定义了一个与官方 `io.agentscope.core.event.AgentEventType` **同名但语义完全不同**的枚举：
   - 官方：流式事件类型（`TEXT_BLOCK_DELTA` / `TOOL_CALL_START` / …）
   - 项目：`RUN_STARTED / STEP / TOOL_CALL / TOOL_RESULT / SOURCE / TEXT_DELTA / ARTIFACT / ERROR / RUN_FINISHED`，是**按 seq 持久化的运行事件合同枚举**
   两套枚举在同一个 IPD 模块内并存，任何跨层 import 或 IDE 自动补全都会产生歧义（见 §3 风险 R5）。

---

### 1.4 Middleware（`io.agentscope.core.middleware.*` / `io.agentscope.core.tracing.*`）

**官方核心类清单**（已实证，`core/middleware/` 8 文件 + `core/tracing/` 4 文件）：

`MiddlewareBase`、`MiddlewareChain`、`AgentInput`、`ReasoningInput`、`ActingInput`、`ModelCallInput`、`TaskReminderMiddleware`、`FinalAnswerFilterMiddleware`；`tracing/`：`OtelTracingMiddleware`、`Tracer`、`NoopTracer`、`TracerRegistry`

（`hook/` 另有 20 文件的 `Hook` API，属另一套事件机制，见下）

**项目侧实际使用**（已实证）：

| 官方类 | 计数 | 项目文件 |
|---|---|---|
| `io.agentscope.core.middleware.MiddlewareBase` | 2 | `AgentScopeProjectAgentKernel`（3 个实现类）、`FrozenProjectAgentSkills` |
| `middleware.AgentInput` / `ModelCallInput` / `ActingInput` | 各 1 | 同上 |
| `io.agentscope.core.hook.Hook` | 1 + 2 处全限定 | `observability/AgentScopeAuditHook.java`（实现 `Hook.onEvent`） |
| `tracing.OtelTracingMiddleware` | **0** | — |
| `tracing.Tracer` / `TracerRegistry` / `NoopTracer` | **0** | — |
| `TaskReminderMiddleware` / `FinalAnswerFilterMiddleware` | **0** | — |

项目自研 middleware 共 3 个（均已实读）：

| 类 | 覆写的官方 hook | 位置 |
|---|---|---|
| `FrozenProjectAgentSkills` | `onSystemPrompt` | `FrozenProjectAgentSkills.java:64` |
| `OwnershipMiddleware` | `onAgent` / `onModelCall` / `onActing`（`order() = Integer.MAX_VALUE`） | `AgentScopeProjectAgentKernel.java:299-315` |
| `DeadlineMiddleware` | `onAgent` / `onModelCall` / `onActing` | `AgentScopeProjectAgentKernel.java:344-390` |

**采用度评级：部分应用**

判定依据：3 个自研 middleware 全部落在官方 `MiddlewareBase` 扩展点内、`order()` 语义使用正确（`OwnershipMiddleware` 置 `MAX_VALUE` 拿到最外层，是官方文档明示的正确姿势）。但**官方内置 middleware 一个都没用**，且自研 `MyChatModelListener` 与官方 `onModelCall` 构成职责重叠（见 §3 风险 R6）。

**缺口**（全部 0 引用，**【仅文档提及】**）：

| 官方能力 | 文档依据 |
|---|---|
| `activePoints()` / `ExtensionPoint` 扩展点参与声明 | middleware.md §扩展点参与声明（官方**推荐实践**：为每个自研 middleware 准确定义） |
| `onAgentStateReady` 通知式 hook | middleware.md §状态就绪通知 |
| `OtelTracingMiddleware` | middleware.md §内置 Middleware（官方 agent / modelCall / acting 三级 span） |
| `TaskReminderMiddleware` + `enableTaskList()` | middleware.md / agent.md §内置工具 |
| `FinalAnswerFilterMiddleware` | middleware.md §内置 Middleware |
| `onReasoning` hook | 6 个位置中项目只用了 4 个（`onAgent`/`onModelCall`/`onActing`/`onSystemPrompt`），`onReasoning` 与 `onAgentStateReady` 未用 |

**特别说明 `activePoints()`**：官方文档明写「**推荐实践**：为你实现的每个 middleware 准确定义 `activePoints()`」。本项目 3 个自研 middleware 全部依赖「未覆写 = 全量激活」默认行为，因此**只要官方未来新增扩展点，这 3 个 middleware 会自动获得参与资格并被静默调用**。这是官方文档「不声明则保持完全兼容的默认行为」所描述的行为，但在本项目的安全语境下（READ_ONLY + ownership + deadline 三道闸）意味着**新增扩展点会自动进入这三道闸的作用域**。

---

### 1.5 Model（`io.agentscope.core.model.*` / `io.agentscope.extensions.model.*`）

**官方核心类清单**（已实证，`core/model/` 19 文件 + `core/formatter/` 6 + `core/credential/` 6）：

`Model`、`ChatModelBase`、`ChatResponse`、`ChatUsage`、`GenerateOptions`、`ExecutionConfig`、`ToolSchema`、`ToolChoice`、`ModelRegistry`、`CachePolicy`、`ModelCreationContext`、`ModelContextWindows`、`ModelUtils`、`ModelProviderSupport`、`StructuredOutputReminder`、`ModelException`、`ModelHttpException`、`spi/ModelProvider`、`transport/*`（16 文件 HTTP/WS transport）；`credential/`：`CredentialBase`、`ModelCard`、`DeepSeekCredential`、`KimiCredential`、`XAICredential`；`formatter/`：`Formatter`、`AbstractBaseFormatter`、`ResponseFormat`、`JsonSchema`、`MediaUtils`、`FormatterException`

扩展（已实证目录）：`model-openai`、`model-openai-official`、`model-dashscope`、`model-gemini`、`model-anthropic`、`model-ollama`

**项目侧实际使用**（已实证，import 计数）：

| 官方类 | 计数 | 关键文件 |
|---|---|---|
| `core.model.Model` | **31** | 全线 |
| `core.model.GenerateOptions` | 17 | `AgentScopeModelFactory` / `RuoYiHarnessChatModelFactory` |
| `core.model.ChatResponse` | 15 | `MyChatModelListener` / A3 `delegate.stream` |
| `core.model.ModelCreationContext` | 6 | `AgentScopeModelFactory.java:57-71` |
| `core.model.ModelRegistry` | 4 | `KernelModelSelector.java:53`、`AgentScopeModelFactory.java:16,20`、`ProjectAgentModelAssembler.java:44`、`PocKernelSupport.java:68` |
| `core.model.ToolSchema` | 8 | — |
| `core.model.ChatUsage` | 5 | `AgentScopeProjectAgentKernel.modelCallDetail` |
| `core.model.ExecutionConfig` | 3 | `AgentScopeModelFactory.java:65` |
| `core.model.ToolChoice` | 1 | A3 `ToolChoice.Required()` |
| `core.model.ModelHttpException` | 1 | — |
| `core.model.CachePolicy` | **0** | — |
| `core.credential.*`（4 类） | **0** | — |
| `core.formatter.*`（6 类） | **0** | — |
| `spi/ModelProvider` | **0**（SPI 隐式） | — |
| `extensions.model.openai.exception.OpenAIException` | 1 | — |
| `extensions.model.{anthropic,dashscope,ollama}.*` | **0** | SPI 隐式发现 |

**采用度评级：充分应用**（这是 7 件套里做得最好的一件）

判定依据：**模型创建只有一个解析源**。已实读并确认三条业务路径全部收敛：

```
KernelModelSelector(ModelRegistry::resolve)
  → AgentScopeModelFactory.context(...)  [ModelCreationContext 承载 apiKey/baseUrl/GenerateOptions]
  → AgentScopeModelFactory.registryKey() [provider:model 薄翻译]
  → ModelRegistry.resolve(key, context)

ProjectAgentModelAssembler(ModelRegistry::resolve)  → 同上（复用 AgentScopeModelFactory）
RuoYiHarnessChatModelFactory.create                  → AgentScopeModelFactory.create → 同上
```

无一处 `new XxxChatModel(...)` 硬编码。`ModelCreationContext` 正确用于多租户（apiKey / baseUrl 不落代码），`GenerateOptions.mergeOptions` 正确合并覆盖值。

**缺口**：

1. **⚠️ 项目自建 `providerAlias` 别名表（已实证，`AgentScopeModelFactory.java:38-47`）**：
   ```java
   case "zhipu","glm" -> "glm";          case "qianwen","dashscope" -> "dashscope";
   case "moonshot" -> "kimi";            case "custom_anthropic" -> "anthropic";
   case "custom_api","atlas","ppio","xiaomi","openai","qwen" -> "openai";
   default -> provider.trim().toLowerCase();
   ```
   官方 `ModelRegistry` 本身已有 SPI provider 词表与 `registerFactory(regex, factory)` 扩展点。项目这层别名把 11 个业务厂商码折叠到 5 个官方 provider，属于**业务词表 → 官方词表的第二套翻译**。当前 `KernelModelSelector` 与 `ProjectAgentModelAssembler` 共用同一个 `AgentScopeModelFactory.registryKey`，**单轨成立**；但 `ProjectAgentModelAssembler` 的 Javadoc（第 20-23 行）自称「区别于 ruoyi-chat chat_model 的厂商码，W2 收敛为单一公开翻译口」——**该「收敛」尚未发生，两处仍各自持有 provider 语义描述**（见 §3 风险 R7）。
2. `CachePolicy` —— 0 引用。`ModelCreationContext` 解析出的模型**默认不缓存**（官方 model.md 缓存策略表），意味着每请求新建 `Model` 实例。`AgentScopeChatKernel` 通过 `agents.computeIfAbsent(cacheKey, ...)` 在 **agent 层**做了缓存（`AgentConfiguration` record 含 `plan.registryKey()` + `plan.configurationIdentity()`），因此实践上无重复创建 —— 但**这是自建缓存，不是官方 `CachePolicy`**。
3. Credential / ModelCard 整条线 —— 0 引用。官方 model.md 的核心设计意图「先注册凭证，再从凭证下挑选模型，前端无需硬编码 provider 逻辑」在本项目**完全未落地**；模型选择改由 `ai_model_configs` 表 + `KernelModelRequest` 承担（业务侧等价物，但与官方 Credential 抽象无任何关系）。
4. 自定义 `ChatModelBase` / `CredentialBase` 子类 —— 0。`CachePolicy` 唯一一处官方建议是「`ENABLED` 搭配 `option()` 时用户必须提供 `cacheId`」，本项目未走该路径。
5. Spring Boot starter —— 0（见 §2）。

---

### 1.6 Permission System（`io.agentscope.core.permission.*`）

**官方核心类清单**（已实证，`core/permission/` 8 文件）：
`PermissionEngine`、`PermissionContextState`、`PermissionMode`（DEFAULT / ACCEPT_EDITS / EXPLORE / BYPASS / DONT_ASK）、`PermissionRule`（record）、`PermissionBehavior`（ALLOW / DENY / ASK / PASSTHROUGH）、`PermissionDecision`（`allow`/`deny`/`ask`/`passthrough` 四个静态构造）、`AdditionalWorkingDirectory`、`ToolDangerousPathConstants`（实为 `core/tool/` 下）

**项目侧实际使用**（已实证，全 `*/src` grep）：

| 官方类 | 引用数 | 位置 |
|---|---|---|
| `permission.PermissionDecision` | 3 处 import（main 2 / test 1），另 6 处全限定调用 | `KernelGovernedTool.java:63`、`AgentScopeProjectAgentKernel.java:334`、A3 `DurableHarnessRunProcessor.java:562,616,642,644,648` |
| `permission.PermissionContextState` | 2 处 import / 3 处全限定（**仅作 `checkPermissions` 方法签名**） | 同上 |
| `permission.PermissionBehavior` | 3 处 import，**全部在 test** | `KernelGovernedToolTest`、`KernelToolGovernanceTest`、`ProjectKnowledgeSearchToolTest` |
| **`permission.PermissionEngine`** | **0** | — |
| **`permission.PermissionMode`** | **0** | — |
| **`permission.PermissionRule`** | **0** | — |
| **`permission.AdditionalWorkingDirectory`** | **0** | — |
| **`tool.ToolDangerousPathConstants`** | **0** | — |
| `permissionContext(...)` builder 调用 | **0** | — |

**采用度评级：部分应用（官方引擎完全未采用，只用了扩展点）**

判定依据：项目**正确使用了官方 `ToolBase.checkPermissions` 拦截点**（`KernelGovernedTool.java:60-64`、`OwnershipGuardedTool.java:334-337`），并正确映射回官方 `PermissionDecision` 三态。`KernelGovernedTool` 的 Javadoc 也准确描述了这一点（「执行协议 = AgentScope 原生」「权限三态 = 包装」），设计认知是对的。

但**官方权限引擎（Rules + Mode + Engine）一行都没跑**。裁决语义唯一源是自研 `ToolPolicyEngine` + `KernelToolGovernance` + 自研枚举 `org.ruoyi.service.coding.harness.model.HarnessPermissionMode`（已实读存在）。

**缺口（这是 7 件套里最大的单轨缺口）**：

1. `PermissionMode` 五档全局策略 —— 未使用。官方 `DEFAULT` 是「最安全，推荐默认值」；项目实际语义等价于 `EXPLORE`（READ_ONLY），但这个语义写在**自研枚举**里，官方引擎看到的 `PermissionContextState` 是 builder 默认值。
2. `PermissionRule` 的 allow/ask/deny 三类规则表 —— 未使用。官方支持「在 ASK 提示中由用户接受建议规则，动态加入引擎」这一自学习闭环，项目无此能力。
3. `ToolDangerousPathConstants` 危险路径保护 —— 未使用。官方明确「Deny 规则与危险路径检查是**不可绕过**的，即使在 `BYPASS` 模式下也照常生效」。项目**没有任何等价的路径级硬保护**：所有隔离都靠 `KernelScopeKey`（键）+ workspace 段校验（路径），**不覆盖工具入参里的路径**。
4. `matchRule()` / `generateSuggestions()` 两个可选 hook —— 未使用。
5. `HarnessPermissionMode` 与官方 `PermissionMode` 语义撞车（见 §3 风险 R8）。

---

### 1.7 Tool（`io.agentscope.core.tool.*`）

**官方核心类清单**（已实证，`core/tool/` 44 文件）：

- 契约：`AgentTool`、`ToolBase`、`Tool`、`ToolParam`、`ToolCallParam`、`ToolResultConverter`
- 容器：`Toolkit`、`ToolkitConfig`、`ToolRegistry`、`ToolGroup`、`ToolGroupManager`、`ToolGroupScope`、`SkillToolGroup`、`ToolMergeMode`、`ToolRequestConfig`、`SchemaOnlyTool`
- 执行：`ToolExecutor`、`ToolMethodInvoker`、`ToolValidator`、`ToolSchemaGenerator`、`ToolSchemaProvider`、`ToolSchemaModule`、`ReflectiveFunctionTool`、`RegisteredToolFunction`
- 权限挂接：`ToolDangerousPathConstants`
- 上下文：`ToolEmitter`、`DefaultToolEmitter`、`NoOpToolEmitter`、`ToolExecutionContext`（@Deprecated）、`ToolExecutionContextProvider`、`ContextStore`、`DefaultContextStore`
- MCP：`mcp/McpClientBuilder`、`mcp/McpClientWrapper`、`mcp/McpAsyncClientWrapper`、`mcp/McpSyncClientWrapper`、`mcp/McpTool`、`mcp/McpMeta`、`mcp/McpContentConverter`、`McpClientManager`
- 内置：`builtin/TodoTools`
- 文件/编码：`file/{ReadFileTool,WriteFileTool,FileToolUtils}`、`coding/{ShellCommandTool,CommandValidator,UnixCommandValidator,WindowsCommandValidator}`
- 其他：`ToolSuspendException`、`ToolResultMessageBuilder`、`DefaultToolResultConverter`、`ExtendedModel`、`SimpleExtendedModel`

**项目侧实际使用**（已实证，import 计数）：

| 官方类 | 计数 | 关键文件 |
|---|---|---|
| `tool.Tool`（注解） | **17** | MCP 市场工具 |
| `tool.ToolParam` | **16** | 同上 |
| `tool.Toolkit` | 7 | 5 个装配点 |
| `tool.ToolCallParam` | 7 | `KernelGovernedTool` / `ProductLineMcpTool` |
| `tool.AgentTool` | 6 | `InlineKnowledgeSearchTool` / `OwnershipGuardedTool` |
| `tool.ToolBase` | 2 import + 5 处全限定 | `KernelGovernedTool` / `OwnershipGuardedTool` |
| `tool.ToolkitConfig` | 2 | `parallel(false)` |
| `tool.mcp.McpClientWrapper` | 3 | `AgentScopeMcpToolProviderService` / `ProductLineMcpTool` |
| `tool.mcp.McpAsyncClientWrapper` | 1 | `ManagedMcpAsyncClient` |
| **`tool.mcp.McpClientBuilder`** | **0** | — |
| **`tool.ToolGroup` / `ToolGroupScope` / `registerToolGroup`** | **0** | — |
| **`tool.ToolRequestConfig` / `ToolMergeMode` / `SchemaOnlyTool`** | **0** | — |
| **`tool.builtin.TodoTools` / `enableTaskList`** | **0** | — |
| **`tool.SkillToolGroup` / `createSkillToolGroup` / `enableMetaTool`** | **0** | — |
| `tool.Tool` / `ToolParam` 反射注册（`registerTool(Object)`） | — | `HarnessToolRegistry.java`、`CodingServiceImpl.java:113`、`AgentScopeMcpToolProviderService.java:95` |

**采用度评级：部分应用**

判定依据：Tool 的**执行面**用得很到位 —— 注解式 + `ToolBase` + `AgentTool` 三种官方 tool 形态都在用，`registerTool` / `registerAgentTool` / `registerMcpClient` 三个官方注册入口都被真实调用，`ToolkitConfig.parallel(false)` 正确规避了 A2 注释里记录的调度器死锁。治理层 `KernelGovernedTool` 是干净的官方扩展点包装。

但 Tool 件套的**「自我管理」半边（Tool Group / Meta Tool / Skill 绑定 / 请求级工具视图）整体未采用**，且 MCP 有一条旁路（见 §3 风险 R9）。

**缺口**：

| 官方能力 | 项目引用 | 说明 |
|---|---|---|
| `ToolGroup` + `enableMetaTool(true)` + `reset_tools` | 0 | 无法按需披露工具集。IPD 侧靠**运行前静态校验**（`AgentScopeProjectAgentKernel.java:291-295`：`exposed ⊆ spec.toolIds()` 否则拒绝运行）替代 —— 这是**更强的约束但不同的时间维度**（装配期 vs 运行期） |
| `SkillToolGroup` / `createSkillToolGroup` | 0 | Skill 无法自动激活绑定的 tool group |
| `ToolRequestConfig` / `ToolMergeMode` / `SchemaOnlyTool` | 0 | 无请求级工具视图。文档明示「调用期间**不要**通过修改共享 Toolkit 注入外部工具或切换会话的工具组」—— A1 的 `stream(..., Toolkit toolkit, ...)` 形参正是**每请求新建 Toolkit**，规避了该禁令（正确） |
| `TodoTools` / `enableTaskList` / `TaskReminderMiddleware` | 0 | 无任务清单能力 |
| `McpClientBuilder` | 0 | **MCP 客户端走自建路径**（见 §3 风险 R9） |
| `ToolDangerousPathConstants` | 0 | 与 §1.6 同一缺口 |
| `readOnlyHint` 自动放行 | 0 | 官方 MCP 能力，项目无对应 |
| `@Tool(dangerousFiles/dangerousDirectories)` | 0 | grep 无命中 |

---

## §2 extensions 采用度矩阵

官方 `agentscope-extensions/` 下 **22 个顶层目录**（已实证 `ls -1d */`），展开后 31 个叶子模块。声明版本统一 `2.0.3`（`pom.xml:16`）。

**分级说明**：
- **引依赖 + 真调用** —— 声明了 jar 且代码 import 了其类
- **引依赖 + 仅 SPI** —— 声明了 jar，代码不 import，靠 Java SPI 被 `ModelRegistry` 发现（**这是官方 model.md 明示的正确用法**）
- **未引入**

| # | extensions 目录 | 状态 | 证据 | 本项目是否需要 |
|---|---|---|---|---|
| 1 | `model-openai` | **引依赖 + 真调用** | `ruoyi-chat/pom.xml:48-50`；`import io.agentscope.extensions.model.openai.exception.OpenAIException` | 需要（OpenAI 兼容端点主力） |
| 2 | `model-anthropic` | **引依赖 + 仅 SPI** | `ruoyi-chat/pom.xml:19`；类 import **0** | 需要（`registryKey` 可产出 `anthropic:` 前缀，见 `AgentScopeModelFactory.java:29`） |
| 3 | `model-dashscope` | **引依赖 + 仅 SPI** | `ruoyi-chat/pom.xml:32-34`；类 import **0** | 需要（同上，`qianwen→dashscope`） |
| 4 | `model-ollama` | **引依赖 + 仅 SPI** | `ruoyi-chat/pom.xml:20`；类 import **0**（注：`io.agentscope.core.embedding.ollama.OllamaTextEmbedding` 属 **L4 rag-simple**，不是本模块） | 需要（本地模型） |
| 5 | `model-gemini` | 未引入 | — | 不需要（无 Gemini 业务 provider；走 OpenAI 兼容即可） |
| 6 | `model-openai-official` | 未引入 | — | 不需要（Responses API 与 Chat Completions 语义差异未触及） |
| 7 | `model-e2e-tests` | 未引入 | — | 不需要（测试模块） |
| 8 | `rag-simple` | **引依赖 + 真调用** | `ruoyi-chat/pom.xml:42-44`；21 处 `io.agentscope.core.embedding.*` + `core.rag.*`（**L4 命名泄漏**） | 需要（embedding 是 RAG 底座） |
| 9 | `rag-bailian` | 未引入 | — | **暂不需要**。项目有自研 `AliBaiLian*EmbedProvider`（已实读存在），走 OpenAI 兼容；若后续要接百炼知识库 API 再评估 |
| 10 | `rag-dify` | 未引入 | — | 不需要（`providerAlias` 的 `default` 分支注释明确提到 dify 由原生裁定，但无业务配置） |
| 11 | `rag-haystack` | 未引入 | — | 不需要 |
| 12 | `rag-ragflow` | 未引入 | — | 不需要 |
| 13 | `redis` | **引依赖 + 真调用** | `ruoyi-chat/pom.xml:21`、`ruoyi-ipd/pom.xml:20-22`；`RedisAgentStateStore` / `RedisClientAdapter` / `RedissonClientAdapter` | 需要（生产首选状态库） |
| 14 | `mysql` | **引依赖 + 真调用** | `ruoyi-chat/pom.xml:37-39`；`MysqlAgentStateStore` import ×10 | 需要（审计/报表场景 + 测试夹具） |
| 15 | `spring-boot-starters` | 未引入 | — | **暂不需要**。官方 model.md 建议 Spring Boot 优先用 starter 创建 `Model` bean；本项目走 `AgentScopeModelFactory` 静态门面 + `ModelRegistry`，与 starter 的 `@ConfigurationProperties` 路线是二选一关系。**但这是 extensions 中唯一与「双轨」直接相关的一项**（见 §3 风险 R10） |
| 16 | `skills` | 未引入 | — | 不需要。IPD 侧走自研 `FrozenProjectAgentSkills`（classpath + sha256 冻结），与官方 `AgentSkillRepository` 接口对齐但不走官方 `skillRepository(...)` 装配（见 §3 风险 R11） |
| 17 | `sandbox` | 未引入 | — | **不需要**。官方 context.md 的分布式硬约束（用 sandbox 必须配分布式 store）因此不触发 |
| 18 | `mem` | 未引入 | — | 不需要。`disableMemoryTools()` + `disableMemoryHooks()` 已全量关闭 |
| 19 | `oss` | 未引入 | — | 不需要（文件面走本地 `Path workspace` + 自有 `ruoyi-common-oss` MinIO/S3） |
| 20 | `jdbc` | 未引入 | — | 不需要（SQL 走 MyBatis-Plus） |
| 21 | `postgresql` | 未引入 | — | 不需要（MySQL 单一栈） |
| 22 | `mongodb` | 未引入 | — | 不需要 |
| 23 | `protocol` | 未引入 | — | 不需要（无跨 agent 协议/远程子 agent 需求；`disableSubagents()`） |
| 24 | `nacos` | 未引入 | — | 不需要（服务注册走 Spring Cloud 既有栈） |
| 25 | `scheduler` | 未引入 | — | 不需要（定时任务走 SnailJob） |
| 26 | `studio` | 未引入 | — | **不需要**。官方 model.md 提示 `StudioManager` 在 `initialize()` 时仍会独立安装已弃用的 `TracerRegistry` —— 不引入反而避免一条隐式追踪路径 |
| 27 | `training` | 未引入 | — | 不需要 |
| 28 | `cos` | 未引入 | — | 不需要 |
| 29 | `judge` | 未引入 | — | 不需要（无 LLM-as-judge 场景） |
| 30 | `aistio` | 未引入 | — | 不需要 |
| 31 | `higress` | 未引入 | — | 不需要 |
| 32 | `channel` | 未引入 | — | 暂不需要（`ChatUiChannel` 等已被 SSE 自有通道替代） |
| — | `agentscope-core` | **引依赖 + 真调用** | `ruoyi-common-chat/pom.xml:58-60` | 需要（L1） |
| — | `agentscope-harness` | **引依赖 + 真调用** | `ruoyi-chat/pom.xml:27-29` | 需要（L3） |

**统计：**
- 引入 8 个 artifacts（core / harness / model×4 / rag-simple / redis / mysql）
- 其中 **真调用类**：core、harness、model-openai、rag-simple、redis、mysql = **6 个**
- **仅 SPI**：model-anthropic、model-dashscope、model-ollama = **3 个**（SPI 装配是官方推荐姿势，**不构成缺口**）
- 未引入 23 个叶子模块，逐条已给出「是否需要」判定

---

## §3 统一性 / 单轨风险清单

按严重度排序。**每条均已实读源码并给出文件路径与行号。**

---

### 🔴 R1 —— 自研 ReAct 循环旁路官方 Agent（最严重）

**证据**（已实读 `DurableHarnessRunProcessor.java`）：

- 第 417-427 行构建了 `HarnessAgent`（try-with-resources）
- 第 715 行：`reactor.core.publisher.Flux<io.agentscope.core.model.ChatResponse> source = delegate.stream(` —— **直接调 `Model.stream(...)`**
- 第 1970-1971 行自行组装 `NativeModelRequest`（`providerMessages` + `tools.specifications()` + `GenerateOptions`）

**性质**：A3 泳道**同时**持有官方 Agent 句柄**和**一条自建的 LLM 调用 + 工具编排循环。官方 `ReActAgent` 的推理-行动主循环、权限门、`AgentState` 生命周期、事件流在该泳道**被整段绕过**。

**与 7 件套的关系**：直接冲击 **Agent（§1.1）** 与 **Message/Event（§1.3）** 两件 —— 官方事件流契约在这条泳道上不成立。

**为什么门禁抓不到**：C1~C8 无一条检查「是否存在绕过 `Agent`/`AgentRun` 的直接 `Model.stream` 调用」。

---

### 🔴 R2 —— 自研 compaction 状态与官方 `AgentState.getSummary()` 并存

**证据**：
- A3 第 1971 行 `compaction.state().checkpoint().summary()` —— **自研 compaction 状态对象**
- 官方 `io.agentscope.core.skill` / `harness.agent.memory.compaction.{CompactionConfig,ConversationCompactor,ToolResultEvictionConfig}` —— 项目引用 **0**

**性质**：官方 harness 默认启用 `CompactionMiddleware` + `ToolResultEvictionMiddleware`（已实证 `HarnessAgent.java:1243-1244` `disableCompaction = false` / `disableToolResultEviction = false`）。A1 / A2 **未调用** `disableCompaction()`，因此**官方压缩管线在 A1/A2 运行时是活的**；A3 则显式 `disableCompaction()` 并自建了一套。

**结果**：同一仓内**三条泳道跑三套压缩语义**，且 A1/A2 的压缩行为在代码里完全不可见（只由官方默认值决定）。

---

### 🟠 R3 —— 5 个装配点的 disable profile 五种形态

**证据**（逐点实读，已实证）：

| 装配点 | disable 调用 | **未调用但默认开启的高危能力** |
|---|---|---|
| A1 `AgentScopeChatKernel:334-341` | `disableFilesystemTools` `disableShellTool` `disableMemoryTools` `disableMemoryHooks` `disableTranscript` `disableSessionPersistence` `enableAgentTracingLog(false)` `disableSubagents` `disableDynamicSubagents` `disableDynamicSkills` `disableDefaultWorkspaceSkills` | **`disableCompaction`（压缩 ON）**、**`disableToolResultEviction`（工具结果淘汰 ON，80k 字符触发）**、**`disableWebTools`（Web 工具 ON，靠 `ToolsConfig.deny` 软拦）**、**`disableWorkspaceContext`（ON）**、**`disableAtPathExpansion`（ON）** |
| A2 `AgentScopeProjectAgentKernel:275-288` | 同 A1 全部 + `skillsEnabled(false)` | 同 A1 全部 |
| A3 `DurableHarnessRunProcessor:420-425` | A1 全部 **+ `disableToolsConfig` `disableWorkspaceContext` `disableAtPathExpansion` `disableCompaction` `disableToolResultEviction`**，`maxIters(Integer.MAX_VALUE)` | 无（关得最全） |
| A4 `CodingServiceImpl:119-120` | `disableFilesystemTools` `disableShellTool` `disableMemoryTools` `disableMemoryHooks` `disableTranscript` `disableSubagents` `disableDynamicSubagents` `disableDynamicSkills` `disableDefaultWorkspaceSkills` `skillsEnabled(false)` | 同 A1 全部 |
| **A5 `PocKernelSupport:54-60`** | **零个 disable** | **全部官方能力全开：文件系统工具 / shell / memory / 子 agent / 技能 / 压缩 / Web 工具 / 追踪日志** |

**A5 细节**（已实读 `PocKernelSupport.java:50-76`）：
- 位于 **main 源树**（非 test）
- `Files.createTempDirectory("poc-kernel-" + agentId + "-")` —— 临时目录工作区
- 模型来自 **系统属性** `System.getProperty("poc.model.id", "minimax:MiniMax-M3")`

**性质**：同仓 5 个官方 Agent 装配点，官方能力开关组合**无一相同**。A5 是唯一一个「全开」的，且它不在测试目录。

---

### 🟠 R4 —— 终止原因双轨：官方 `GenerateReason` vs 自研 `ERR_*`

**证据**：
- 官方 `message.GenerateReason`（8 个值：MODEL_STOP / TOOL_SUSPENDED / REASONING_STOP_REQUESTED / ACTING_STOP_REQUESTED / ALL_TOOLS_DENIED / INTERRUPTED / MAX_ITERATIONS / PERMISSION_ASKING）—— **全仓 0 引用**
- 项目自研错误码（已实读 `AgentScopeProjectAgentKernel.java:92-96`）：
  ```java
  static final String ERR_SCOPE_REJECTED = "SCOPE_REJECTED";
  static final String ERR_MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
  static final String ERR_KERNEL_ERROR = "KERNEL_ERROR";
  static final String ERR_STREAM_ERROR = "STREAM_ERROR";
  static final String ERR_RUN_TIMEOUT = "RUN_TIMEOUT";
  ```
- A1 同构：`ERR_SCOPE_REJECTED` / `ERR_KERNEL_ERROR` / `ERR_STREAM_ERROR`（`AgentScopeChatKernel.java:48-52`）

**性质**：官方用**同一 Msg 的 `getGenerateReason()`** 表达全部终止原因（可与内容一起持久化）；项目另起一套字符串码。官方 `ExceedMaxItersEvent` 已被 A2 消费（第 527-528 行 `sink.onStep("EXCEED_MAX_ITERS", ...)`）—— **同一个事件被翻译成两套体系**。

---

### 🟠 R5 —— 官方类名撞车：`AgentEventType`

**证据**：
- 官方：`io.agentscope.core.event.AgentEventType`（35 个 event 类的类型枚举）—— 项目 **0 引用**
- 项目：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/model/AgentEventType.java`（9 值：`RUN_STARTED / STEP / TOOL_CALL / TOOL_RESULT / SOURCE / TEXT_DELTA / ARTIFACT / ERROR / RUN_FINISHED`）

**性质**：同名、同模块内并存、语义正交。官方同名类当前 0 引用，所以尚未爆炸；但官方 7 件套里 `event.AgentEventType` 是 `message-and-event.md` 明确要求「按 `event.getType()` 分发」的入口键，一旦项目开始消费官方事件类型就会撞名。

---

### 🟠 R6 —— 模型可观测性双轨：自研 `Model` 装饰器 vs 官方 `onModelCall` / `OtelTracingMiddleware`

**证据**（已实读 `MyChatModelListener.java` 全文）：
- 自研 `ObservedModel implements Model`，在 `stream(...)` 上包 `doOnNext` / `doOnError` / `doOnComplete`
- 官方 `tracing.OtelTracingMiddleware`（middleware.md 明确它在 `onAgent` / `onModelCall` / `onActing` 三处打 span）、`tracing.Tracer` / `TracerRegistry` / `NoopTracer` —— **全部 0 引用**
- 项目另有 `AgentScopeAuditHook implements io.agentscope.core.hook.Hook`（走**第三套**官方扩展点 `Hook.onEvent`）

**性质**：模型调用可观测性有 **3 条并行通道**：`MyChatModelListener`（Model 装饰器）、`AgentScopeAuditHook`（Hook 事件）、官方 `OtelTracingMiddleware`（未用）。前两条是项目自选的官方扩展点（**不算违规**），但第三条的缺席意味着**没有统一的 span/trace 层**，且 `MyChatModelListener` 装饰 `Model` 的做法**改写了 Model 契约**（`AgentScopeModelFactory.create` 的返回对象不是 SDK 原生 `Model`）。

---

### 🟡 R7 —— 模型注册键翻译的「W2 收敛」尚未发生

**证据**（已实读两个文件）：
- `KernelModelSelector.java:98-106` Javadoc：「薄归一（翻译，不是路由）……厂商别名（确定性翻译表，OpenAI 兼容端点归一）：zhipu→glm、qianwen→dashscope、OpenAI 兼容自建端点（custom_api/atlas/ppio/xiaomi）→openai」
- `ProjectAgentModelAssembler.java:20-23` Javadoc：「注册键翻译按 **IPD 自身 provider 词表**（与 `OpenAiCompatibleTester` 的别名一致：openai/deepseek/qwen/moonshot/MiniMax 为 OpenAI 兼容端点）……**区别于 ruoyi-chat chat_model 的厂商码**；`KernelModelSelector` 为包级私有且不在本切片写入面，**W2 收敛为单一公开翻译口（见运行合同 §7）**」

**现状（已实证）**：`registryKey` 实现**确实已共享**（两者都调 `AgentScopeModelFactory.registryKey`），实现层单轨成立。**但两份 Javadoc 各自描述了一套不同的 provider 语义**——IPD 那份列举的 `deepseek / qwen / moonshot / MiniMax` 与 chat 那份列举的 `zhipu / qianwen / custom_api / atlas / ppio / xiaomi` **不是同一个集合**，且 `deepseek` / `minimax` 在 `providerAlias` 的 `switch` 里**没有 case**（走 `default` 小写直通）。

**风险**：文档与实现已不同步；`deepseek` / `minimax` 的可解析性完全依赖官方 `ModelRegistry` 的 SPI 词表，项目侧无任何断言覆盖。

---

### 🟡 R8 —— 权限模式枚举撞车：`HarnessPermissionMode` vs `PermissionMode`

**证据**：
- 项目：`org.ruoyi.service.coding.harness.model.HarnessPermissionMode`（已实读文件存在），全部 5 个装配点传入 `HarnessPermissionMode.READ_ONLY`
- 官方：`io.agentscope.core.permission.PermissionMode`（DEFAULT / ACCEPT_EDITS / EXPLORE / BYPASS / DONT_ASK）—— **0 引用**

**性质**：官方 `EXPLORE` 的语义（只读：放行读、拒绝所有写与命令）与项目的 `READ_ONLY` **语义等价但命名不同、且走的不是同一套判定**。官方 `PermissionEngine` 从未被调用，所以 `PermissionContextState` 里始终是 builder 默认值 —— **A1/A2/A3 实际运行在官方 `DEFAULT` 模式语境下，只是所有工具的 `checkPermissions` 都无条件返回自研裁决结果，官方引擎的判定从未有机会执行**。

---

### 🟡 R9 —— MCP 双轨：自建客户端构造 + 手写 MCP 工具包装

**证据（a）—— 客户端构造绕开官方 `McpClientBuilder`**（已实读 `ManagedMcpAsyncClient.java:33-37`）：
```java
public static ManagedMcpAsyncClient create(String name, McpClientTransport transport, Duration requestTimeout) {
    return new ManagedMcpAsyncClient(name, io.modelcontextprotocol.client.McpClient.async(transport)...
```
官方 `McpClientBuilder`（tool.md 的标准入口，支持 `stdioTransport` / `sseTransport` / `streamableHttpTransport` / `.header(...)`）—— **0 引用**。

**证据（b）—— IPD 侧手写 MCP 工具**（已实读 `ProductLineMcpTool.java`）：
- 第 30 行 `implements AgentTool`（**不是**官方 `registerMcpClient` 路径）
- 第 172 行 `McpClientWrapper client = ManagedMcpAsyncClient.streamableHttp(...)` —— **在 `callAsync` 内部现场建 MCP 连接**
- 第 77 行 `toolkit.registerAgentTool(KernelGovernedTool.wrap(...))` —— 注册的是**自包装的单个 AgentTool**，不是 MCP 服务端的工具集

**对照**：chat 侧 `AgentScopeMcpToolProviderService.java:112` 走的是**正确**官方路径 `session.toolkit.registerMcpClient(client).block(TIMEOUT)`。

**性质**：MCP 有两条轨道 —— chat 侧官方、IPD 侧自研。且 IPD 侧每次 `callAsync` 现场建连（无连接池、无 `registerMcpClient` 的工具发现缓存）。

---

### 🟡 R10 —— Spring Boot starter 未引入 + 自建 `Model` bean 装配

**证据**：`agentscope-spring-boot-starters` 未声明；官方 model.md「Spring Boot 应用优先使用特定模型提供商的 starter……创建 Spring 管理的 `Model` bean」未采用。本项目走 `AgentScopeModelFactory` 静态门面。

**性质**：这本身**不是违规**（官方文档明确「高级用户始终可以自定义 `Model` bean」）。但需登记：项目在 Model 装配上选择了**静态门面路线**，与官方 starter 路线是二选一，未来若有人加 starter 会形成真双轨。当前无冲突。

---

### 🟡 R11 —— `FrozenProjectAgentSkills` 的 `AgentSkillRepository` 半边是死代码

**证据**（已实读 `AgentScopeProjectAgentKernel.java:265-273`）：
```java
HarnessAgent built = HarnessAgent.builder()
    ...
    .middleware(selectedSkills)     // ← 只作为 middleware 注册
    .model(model)
```
**没有** `.skillRepository(selectedSkills)`。

**官方机制（已实证）**：`HarnessAgent.java:1232` `final List<AgentSkillRepository> skillRepositories = new ArrayList<>();`，由 `Builder.skillRepository(...)`（第 1759 行）/`skillRepositories(...)`（第 1769 行）填充，**与 `middlewares` 列表是两个独立字段**（第 1227 行），**不存在从 middleware 自动提取 `AgentSkillRepository` 的逻辑**。

**后果**：`FrozenProjectAgentSkills` 实现的 9 个 `AgentSkillRepository` 方法（`getSkill` / `getAllSkillNames` / `getAllSkills` / `skillExists` / `save` / `delete` / `getRepositoryInfo` / `getSource` / `setWriteable` / `isWriteable`）**在运行时永远不会被 SDK 调用**，只有 `onSystemPrompt` 是活的。

**评价**：sha256 冻结 + 内容比对 + `resources(Map.of())` 剥离这套**安全设计本身是对的**（实读第 30-56 行，质量很高）。问题只是它挂载到了错误的扩展点 —— 官方 `skillRepository(...)` 本可以提供同一份冻结能力 + 让 `DynamicSkillMiddleware` 参与，两处只需一处。

---

### 🟢 R12 —— `ToolBase` 包装丢字段

**证据**（已实读 `KernelGovernedTool.wrap`，第 46-53 行；`OwnershipGuardedTool` 构造器，第 322-325 行）：
```java
ToolBase.builder()
    .name(delegate.getName())
    .description(delegate.getDescription())
    .inputSchema(delegate.getParameters())
    .readOnly(delegate.isReadOnly())
    .concurrencySafe(delegate instanceof ToolBase nativeTool && nativeTool.isConcurrencySafe());
```

**丢失项**：`externalTool`（`isExternalTool()`）、`stateInjected`（`isStateInjected()`）、`strict`、`outputSchema`、以及 `ToolBase.builder()` 支持但未复制的任何其他字段。

**缓解**：`OwnershipGuardedTool`（第 332-333 行）显式补回了 `getStrict()` 与 `getOutputSchema()`，但 **`KernelGovernedTool` 没有**。

**未实证**：以上字段在 2.0.3 `ToolBase.builder()` 中是否全部存在（`ToolBase.java` 未逐行读源码，字段清单转述自 tool.md 表格 + A2 已编译代码的调用证据）。建议以 `javap` 复核。

---

### 🟢 R13 —— CAS 冲突判据手写三遍

**证据**（三个文件均已实读，判据字面相同）：
- `FailClosedAgentStateStore.java`：`if (delegate.supportsVersioning() && version == UNVERSIONED) throw ...`
- `ProjectAgentTemporaryStateStore.java`：同一判据（另有一处在 `save` 中主动拒绝无条件覆盖）
- `AgentScopeChatKernel.java:412`：`if (store.supportsVersioning() && version == AgentStateStore.UNVERSIONED) throw ...`

官方 `ConflictPolicy` 枚举存在（已实证 `core/state/ConflictPolicy.java`）但 **0 引用**。属逻辑重复，非安全缺陷。

---

### 🟢 R14 —— `disableSessionPersistence()` 是官方明示的 no-op

**证据**：`AgentScopeChatKernel.java:339` 调用 `.disableSessionPersistence()`。
官方 `HarnessAgent.java:2283-2286` Javadoc：**「No-op since 2.0; session persistence is owned by ReActAgent itself.」**

**性质**：死代码 + 误导性注释面。读者会以为该调用在关闭持久化，实际不关闭。

---

## §4 门禁覆盖缺口

现有门禁：`.claude/skills/agentscope-harness/scripts/harness-contract-check.sh`（287 行），本次实跑 **PASS，EXIT=0，接线文件 138 个**（已实证）。

### §4.1 现有 C1~C8 覆盖范围（已实读脚本）

| 门禁 | 判定内容 |
|---|---|
| C1 | `HarnessAgent.builder()` 必须有 `.name(` 与 `.workspace(` |
| C2 | 复合隔离键手拼必须收敛到单一收口文件（按工作树分组） |
| C3 | 收口文件必须 fail-closed（拒 `:` / 拒 `..` / 显式抛错） |
| C4 | `RuntimeContext.builder()` 必须带 `.userId(` 与 `.sessionId(` |
| C5 | 含 `HarnessAgent` 且含 `.streamEvents(`/`.call(` 的文件**必须引用** `RuntimeContext` 或 `toRuntimeContext()` |
| C6 | 禁止硬编码 `setUser(`/`setPassword(` 字面量 |
| C7 | 引入 io.agentscope 的 pom 必须钉 okhttp + `banDuplicateClasses` |
| C8 | langchain4j 棘轮脚本 + 基线文件必须在场 |

### §4.2 未覆盖的 7 件套层面风险（13 项）

| # | 缺口 | 对应风险 | 现有门禁为何漏 |
|---|---|---|---|
| G1 | 装配点数量与 disable profile 一致性 | R3 | C1 只查「有没有 name+workspace」，5 个点全部合法通过 |
| G2 | 绕过 Agent 的直接 `Model.stream` 调用 | R1 | 无任何一条针对 `Model` 层的调用面检查 |
| G3 | 官方 `PermissionMode` / `PermissionRule` / `PermissionEngine` 采用度 | R8 | C1~C8 不涉及 permission 包 |
| G4 | `Msg.getGenerateReason()` 终止原因单一体系 | R4 | 无 |
| G5 | 项目类名与官方类名撞车 | R5 / R8 | 无 |
| G6 | MCP 客户端必须经 `McpClientBuilder` | R9 | 无 |
| G7 | `io.agentscope.core.*` import 的**模块归属**（L4 命名泄漏） | §0.1 | C7 只看 pom 有没有 io.agentscope，不校验 import 前缀 ↔ jar 的对应 |
| G8 | 自研 middleware 必须声明 `activePoints()` | §1.4 | C5 只看文件级 RuntimeContext 引用 |
| G9 | 模型创建必须经 `ModelRegistry`（禁 `new XxxChatModel`） | §1.5 | 无 |
| G10 | 压缩/记忆/工具淘汰等**默认开启**能力必须显式声明意图 | R2 / R3 | C1 不管 disable 白名单 |
| G11 | main 源树禁止存在 PoC 装配点 | R3（A5） | C1 对 A5 判定 PASS（它确实有 name + workspace） |
| G12 | `ToolBase` 包装必须保字段（`externalTool` / `stateInjected` / `outputSchema`） | R12 | 无 |
| G13 | 官方 HITL 事件族采用度（`RequireUserConfirmEvent` 等） | §1.3 缺口 2 | 无 |

### §4.3 建议新增检查项（写清检查什么 / 怎么判 / 失败怎么报）

> 以下为**建议**，本次未实施（硬性纪律：只写 docs）。建议落在 `harness-contract-check.sh` 之后作为 C9~C16，或独立为 `building-blocks-check.sh`。

**C9 —— 装配点 disable profile 一致性**
- 检查什么：所有 `HarnessAgent.builder()` 装配点的 `disable*` 调用集合
- 怎么判：抽取每个装配点 builder 块内的 `disable\w+\(` / `enable\w+\(` 调用名，规范化后与基线清单（放 `scripts/baselines/harness-disable-profile.json`）比对；**任一装配点出现基线外的调用即 FAIL**
- 额外：`stateStore(...)` 必须存在（防止落到 `JsonFileAgentStateStore` 默认值往 `$HOME` 写文件）
- 失败怎么报：`[ C9 ] <文件>:<行> disable 集合漂移：多出 {X} 缺失 {Y} —— 官方能力面与基线不一致`

**C10 —— 禁止绕过 Agent 直接调 Model**
- 检查什么：`Model` 类型变量上的 `.stream(` 调用
- 怎么判：在含 `io.agentscope` import 的文件中，若出现 `\.stream\(` 且同一行/上下文出现 `Model` 变量或 `ChatResponse` 泛型，且该文件**不含** `AgentScopeAuditHook`/`MyChatModelListener` 这类已知装饰器白名单，则 FAIL
- 白名单机制：允许项目显式登记例外（`// bb-exempt: <理由>` 行内注解）
- 失败怎么报：`[ C10 ] <文件>:<行> 疑似绕过 Agent 直接调 Model.stream()`

**C11 —— 官方权限系统采用度棘轮**
- 检查什么：`io.agentscope.core.permission.{PermissionMode,PermissionRule,PermissionEngine}` 的引用数
- 怎么判：基线为 **0**（只允许 `checkPermissions` + `PermissionDecision`）；任何新增引用**不自动 FAIL**，但必须强制走评审 —— 建议改为「引用数变化即 WARN + 打印 diff」，并要求同步更新 `docs/ipd-系统说明/AgentScope-building-blocks-7件套审计-*.md`
- 失败怎么报：`[ C11 ] 官方权限引擎引用数由 0 变为 N —— 若为有意采用，请同步更新采用度评级与本文件`

**C12 —— main 源树禁止 PoC 装配点**
- 检查什么：`src/main/java/**` 中路径含 `/poc/` 且出现 `HarnessAgent.builder()` 或 `ReActAgent.builder()`
- 怎么判：命中即 FAIL
- 失败怎么报：`[ C12 ] <文件> PoC 装配点位于 main 源树 —— 请移入 src/test 或加显式白名单`

**C13 —— MCP 客户端构造收口**
- 检查什么：`io.modelcontextprotocol.client.McpClient.` 静态调用（非 `McpClientBuilder` 产出）
- 怎么判：命中即 FAIL，除非同文件含 `McpClientBuilder`
- 失败怎么报：`[ C13 ] <文件>:<行> 绕过官方 McpClientBuilder 直接建 MCP 客户端`

**C14 —— 官方类名撞车检测**
- 检查什么：项目自有类型 simpleName 与官方 7 件套公开类型同名
- 怎么判：生成官方类名集合（从 `agentscope-core` + `agentscope-harness` 源码 `find` 得出 simpleName），与项目 `src/main/java` 下所有 `class|enum|interface|record` simpleName 求交；命中即 WARN 并打印双方 FQN
- 已知豁免（当前）：`AgentEventType`（IPD 运行事件合同）、`HarnessPermissionMode`（官方无同名，风险低）
- 失败怎么报：`[ C14 ] 撞名：org.ruoyi.ipd.agent.model.AgentEventType ↔ io.agentscope.core.event.AgentEventType`

**C15 —— import 前缀 ↔ Maven 模块一致性**
- 检查什么：`io.agentscope.core.embedding.*` / `io.agentscope.core.rag.store.*` / `io.agentscope.core.rag.knowledge.*` / `io.agentscope.core.rag.reader.*` 等 **L4 泄漏包**的 import，其提供方 jar 是否已声明
- 怎么判：泄漏包清单硬编码在脚本内；每个泄漏包必须能在某 pom 中找到 `agentscope-extensions-rag-simple`
- 失败怎么报：`[ C15 ] <文件> 引用 L4 泄漏包 <pkg>，但未声明 agentscope-extensions-rag-simple`

**C16 —— 默认开启能力显式化**
- 检查什么：装配点是否显式调用了 `disableCompaction` / `disableToolResultEviction` / `disableWebTools` / `disableWorkspaceContext` / `disableAtPathExpansion` 五者中的全部或全部不调
- 怎么判：**允许全开或全关，禁止部分声明**（部分声明 = 意图不明）。基线 A1/A2/A4 当前是「部分声明」（全开），需 owner 拍板后写入基线
- 失败怎么报：`[ C16 ] <文件> 默认开启能力声明不完整：{已声明} —— 官方默认 {未声明} 为 ON`

**C17（建议，优先级最低）—— `ToolBase` 包装字段保真**
- 检查什么：项目中所有 `ToolBase.builder()...build()` 包装他人 `AgentTool` 的位置
- 怎么判：若 `.name()/.description()/.inputSchema()/.readOnly()/.concurrencySafe()` 之外的字段（`externalTool`/`stateInjected`/`outputSchema`）在 delegate 上非默认值而未被复制，FAIL
- 需先 `javap io.agentscope.core.tool.ToolBase$Builder` 确认 2.0.3 实际字段集
- 失败怎么报：`[ C17 ] <文件>:<行> ToolBase 包装丢失字段 {X}`

---

## §5 收敛建议清单

按「先做哪个 / 收益风险比」排序。**每条均标注涉及文件与验证方式。**

> 项目要求**真 HTTP + 真 DB 验收，不接受 Mock 替业务**（任务书约束）。下表「验证方式」列已按此口径书写。

---

### T1 ——【最高收益 / 零风险】处置 A5 `PocKernelSupport`：移出 main 或加显式白名单

**涉及文件**：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/PocKernelSupport.java`、`PocSseController.java`

**问题**：main 源树里存在一个**零 disable**（官方全开：文件工具 / shell / memory / 子 agent / Web 工具 / 压缩全 ON）、工作区在系统临时目录、模型来自系统属性的 Agent 装配点。

**动作**：
1. 先做**零行为变更**的一步：给 A5 加上与 A1 一致的 disable profile + 显式 `stateStore`，使其与其他 4 个点对齐。
2. 再评估是否整体移入 `src/test`（`PocSseController` 有真实 PoC 用途，需 owner 拍板）。

**验证方式**：
- 静态：`bash .claude/skills/agentscope-harness/scripts/harness-contract-check.sh`（应仍 PASS）+ 新增 C9/C12 后应 FAIL → 修完转 PASS
- 运行时：真 HTTP 起 `ipd_dev` 后端，`curl` 打 PoC SSE 端点，抓 `GET /resource/sse`（或 PoC 自身端点）事件流，断言**事件里不出现任何 shell / 文件写入 / web_fetch 工具调用**（当前配置下这些工具是暴露的 —— 这正是要验的点）

---

### T2 ——【最高收益 / 中风险】统一 5 个装配点的 disable profile 为一份基线

**涉及文件**：A1 `AgentScopeChatKernel.java:332-348`、A2 `AgentScopeProjectAgentKernel.java:265-290`、A3 `DurableHarnessRunProcessor.java:417-427`、A4 `CodingServiceImpl.java:115-121`、A5 `PocKernelSupport.java:54-60`；新增 `scripts/baselines/harness-disable-profile.json`

**问题**：R3 —— 五种形态。特别注意 A1/A2/A4 **未显式声明** `disableCompaction` / `disableToolResultEviction` / `disableWorkspaceContext` / `disableAtPathExpansion`，这四项当前是**官方默认 ON**，行为完全由 SDK 决定、代码不可见。

**动作**：
1. **先只做「显式化」**：给 A1/A2/A4 补上显式 `disableCompaction()` / `disableToolResultEviction()` / `disableWorkspaceContext()` / `disableAtPathExpansion()`，取值与当前运行时实际行为一致（**先查清当前是否真的在压缩**）。
2. 若 owner 认为 A1/A2 需要压缩，则改为显式 `.compaction(CompactionConfig.builder()....build())` 而非依赖默认。
3. 落 `scripts/baselines/harness-disable-profile.json`，新增 C9 棘轮。

**验证方式**：
- **关键前置**：必须先回答「A1/A2 现在到底有没有在跑官方压缩」。方法：真 HTTP 发起一轮超长对话（>模型 context window 的 token 量），观察 `IpdAgentRun`/chat 消息表里是否出现官方压缩产出的摘要。**不接受单测断言**。
- 收敛后：同一长对话重放，断言消息历史结构**逐字节一致**（压缩开或关必须是显式决定的结果，不是默认值漂移的结果）。

---

### T3 ——【高收益 / 高风险，需 owner 拍板】R1：收敛 A3 的自研 ReAct 循环

**涉及文件**：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness/loop/DurableHarnessRunProcessor.java`（5,649 行）

**问题**：A3 同时持有 `HarnessAgent`（第 417 行）与自建的 `Model.stream` + 工具编排（第 715、1970 行）。这是全仓最大的单轨缺口，也是 31,499 行 `service/coding/harness` 的核心。

**动作（分三阶段，每阶段独立可交付）**：
- **阶段 1（只读诊断，不改码）**：产出 A3 自研循环与官方 `ReActAgent` 主循环的**逐能力对照表**（推理轮次管理 / 工具批处理 / 权限门 / `AgentState` 生命周期 / 事件流 / 中断 / 结构化输出），标出「自研独有、官方无对应」的能力清单。只有官方确实缺失的能力才保留自研。
- **阶段 2**：把「官方有、自研也做了」的部分切回官方，保留自研的部分以**独立 middleware / tool** 形态挂在 `HarnessAgent` 上（这才是官方 `MiddlewareBase` 扩展点的正确用法）。
- **阶段 3**：删除已被官方覆盖的自研代码。**这一步必须走 R141/BCP 流程与棘轮，不在本任务范围**。

**验证方式**：
- 真 HTTP 发起一次编码任务，断言 `ipd_agent_run_event` 表中事件序列**与官方 `AgentEvent` 序列一一对应**（`ToolCallStartEvent` ↔ `TOOL_CALL`、`ToolResultEndEvent` ↔ `TOOL_RESULT`、`ModelCallEndEvent` ↔ `STEP`）
- 同一任务在收敛前后各跑一次，断言**最终代码产物字节一致** + 工具调用次数一致
- **不接受 Mock 模型替业务**：需真模型调用、真 DB 落库

---

### T4 ——【高收益 / 低风险】把 `FrozenProjectAgentSkills` 从 middleware 改挂 `skillRepository(...)`

**涉及文件**：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java:268`、`FrozenProjectAgentSkills.java`

**问题**：R11 —— 9 个 `AgentSkillRepository` 方法是死代码，只有 `onSystemPrompt` 活着。官方 `HarnessAgent` 明确不自动从 middleware 提取 repository（已实证 `HarnessAgent.java:1232` 与 `1227` 是两个独立 list）。

**动作**：在 A2 的 builder 上加 `.skillRepository(selectedSkills)`。**sha256 冻结校验逻辑一个字都不动**（它已经是对的），只换挂载点。

**验证方式**：
- 真 HTTP 发起一次带 skill 的项目智能体运行，断言：
  1. `IpdAgentRun` 的冻结 skill 记录（name@version:sha256）与请求一致
  2. SDK 侧 `agent.getSkillRepositories()` 返回非空且 `getAllSkillNames()` 命中选集
  3. 篡改 `src/main/resources/ipd-skills/<name>/SKILL.md` 一个字节后重跑，**必须在建 agent 阶段失败**（`Selected skill hash changed`），不进入 LLM 调用

---

### T5 ——【中收益 / 低风险】MCP 双轨收口：IPD 侧改走 `registerMcpClient`

**涉及文件**：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProductLineMcpTool.java`、`ProductLineMcpQuery.java:172`

**问题**：R9 —— IPD 侧在 `callAsync` 里现场建 MCP 连接并手写单个 `AgentTool`；chat 侧（`AgentScopeMcpToolProviderService.java:112`）走的是正确官方路径。

**动作**：把 IPD 侧改为 `ManagedMcpAsyncClient` 建连 → `toolkit.registerMcpClient(client)` → 工具集由 SDK 发现。`ProductLineMcpTool` 中 `getName()`/`getDescription()`/`getParameters()` 三处手写 schema 与官方 `McpTool` 发现的 schema 会并存，需裁决保留哪一份。

**验证方式**：
- 真 HTTP 发起产品线查询，确认 `McpToolVo` 的工具在 `IpdAgentRunEvent` 的 `TOOL_CALL` 中以 `mcp__{server}__{tool}` 命名出现（官方命名约定）
- 真 DB 断言 `ipd_product_line` 命中条数与 MCP 响应一致
- 新增 C13 后应 FAIL → 修完转 PASS

---

### T6 ——【中收益 / 极低风险】清理死代码与文档漂移（可与 T1~T5 同批做）

**涉及文件**：
- `AgentScopeChatKernel.java:339` —— 删 `disableSessionPersistence()`（官方明示 no-op，R14）
- `AgentScopeProjectAgentKernel.java:20-23` —— 修正 Javadoc 中已过期的「W2 收敛」表述（实现已共享，文档仍写「不在本切片写入面」，R7）
- `FailClosedAgentStateStore.java` / `ProjectAgentTemporaryStateStore.java` / `AgentScopeChatKernel.java:412` —— CAS 判据收敛为单一静态工具方法（R13）
- `KernelModelSelector.java:98-106` —— Javadoc 的厂商清单与 `AgentScopeModelFactory.providerAlias` 实际 switch 对齐（R7）

**验证方式**：
- `mvn -q clean package -DskipTests` 通过
- `mvn test -pl ruoyi-modules/ruoyi-chat -Dtest=KernelGovernedToolTest+KernelToolGovernanceTest+AgentScopeChatKernelIdentityTest` 全绿（surefire 需 `@Tag("dev")`，见 CLAUDE.md Testing 节）
- 真 HTTP 冒烟：一次 chat + 一次 IPD 项目智能体运行，断言行为无变化

---

### T7 ——【中收益 / 中风险】补 `PermissionRule` 或显式登记「以自研引擎替代」为已裁决

**涉及文件**：`KernelGovernedTool.java`、`AgentScopeProjectAgentKernel.OwnershipGuardedTool`、`HarnessPermissionMode.java`、新增 `docs/ipd-系统说明/` 裁决记录

**问题**：R8 —— `PermissionMode` / `PermissionRule` / `PermissionEngine` 全部 0 引用，`ToolDangerousPathConstants` 无等价物（**工具入参里的危险路径无硬保护**）。

**动作（先裁决，后编码）**：
- 路径 A（推荐）：owner 拍板「以自研 `ToolPolicyEngine` 为权限裁决唯一源，官方 `PermissionEngine` 不采用」，并把它写进 `CLAUDE.md` 的红线 + 本文件采用度评级。**那么当前代码已经是正确的**，缺的只是登记与棘轮（新增 C11）。
- 路径 B：把 `ToolDangerousPathConstants` 的保护面接入 —— 通过 `@Tool(dangerousFiles/dangerousDirectories)` 或在 `KernelGovernedTool.checkPermissions` 内调官方危险路径判定。**这条能补上真实安全缺口**（当前无入参路径级保护）。

**验证方式**：
- 路径 A：新增 C11 棘轮跑通（引用数 0 → 0，PASS）
- 路径 B：真 HTTP 发起一次工具调用，传 `.ssh/authorized_keys` 类路径入参，断言**被拒绝且拒绝原因可审计**（落 `IpdAgentRunEvent` / audit log），不触达文件系统

---

### T8 ——【低收益 / 低风险】把 §4.3 的 C9~C17 门禁落地

**涉及文件**：新增 `.claude/skills/agentscope-harness/scripts/building-blocks-check.sh`、`.claude/skills/agentscope-harness/scripts/baselines/*.json`、`.claude/skills/agentscope-harness/scripts/verify.sh`（挂载新脚本 + `--self-red` 双向验证）

**动作**：按 §4.3 逐条实施，每条必须满足 CLAUDE.md SOP-3 的「自证能红」：`BB_FAIL_SEED=1 bash ...` 必须 EXIT=1。优先顺序 **C12 → C10 → C9 → C13 → C15 → C14 → C16 → C11 → C17**（C12/C10 是纯新增红线，无兼容性风险；C9/C16 需要 T2 先落基线）。

**验证方式**：
- `bash .claude/skills/agentscope-harness/scripts/verify.sh` EXIT=0
- `bash .claude/skills/agentscope-harness/scripts/verify.sh --self-red` EXIT≠0
- 每条 C 项单独注入违规 fixture 验证会红

---

## §6 附录：已实证「0 引用」官方组件全清单

grep 范围：`ruoyi-modules/ruoyi-chat/src`、`ruoyi-modules/ruoyi-ipd/src`、`ruoyi-common/ruoyi-common-chat/src`（已排除 `target/`、`.harness/`、`.codex/` 备份树）。

**A. 官方 core（7 件套本体）**

`PermissionEngine` / `PermissionMode` / `PermissionRule` / `AdditionalWorkingDirectory` / `ToolDangerousPathConstants` / `ToolGroup` / `ToolGroupScope` / `ToolGroupManager` / `SkillToolGroup` / `ToolRequestConfig` / `ToolMergeMode` / `SchemaOnlyTool` / `ToolExecutor` / `ToolValidator` / `ToolSuspendException` / `builtin.TodoTools` / `tool.mcp.McpClientBuilder` / `mcp.McpTool` / `mcp.McpMeta` / `mcp.McpContentConverter` / `McpClientManager` / `ToolEmitter`（`DefaultToolEmitter`/`NoOpToolEmitter`）/ `ToolExecutionContext` / `ContextStore` / `file.ReadFileTool` / `file.WriteFileTool` / `coding.ShellCommandTool` / `coding.CommandValidator` / `ReflectiveFunctionTool` / `ToolSchemaGenerator` / `ToolResultConverter` / `DefaultToolResultConverter` / `ToolResultMessageBuilder` / `ExtendedModel` / `SimpleExtendedModel`

`model.CachePolicy` / `ModelContextWindows` / `ModelUtils` / `ModelProviderSupport` / `StructuredOutputReminder` / `spi.ModelProvider` / `transport.*`（16 类）/ `credential.*`（4 类）/ `formatter.*`（6 类）/ `GenerateOptions` 之外的 `ExecutionConfig` 高级用法

`message.GenerateReason` / `message.MessageMetadataKeys` / `message.HintBlock` / `message.SystemMessage` / `message.AssistantMessage` / `message.ToolResultMessage` / `message.DataBlock` / `message.AudioBlock` / `message.VideoBlock` / `message.ThinkingBlock` / `message.Source` / `message.Base64Source` / `message.URLSource` / `message.ToolCallState`

`event.AgentEventType` / `AgentStartEvent` / `AgentEndEvent` / `RequestStopEvent` / `CustomEvent` / `AgentEventEmitter` / `TextBlockStartEvent` / `TextBlockEndEvent` / `ThinkingBlockStartEvent` / `ThinkingBlockEndEvent` / `DataBlock*`（3）/ `ToolCallDeltaEvent` / `ToolCallEndEvent` / `ToolResultStartEvent` / `ToolResultTextDeltaEvent` / `ToolResultDataDeltaEvent` / `RequireUserConfirmEvent` / `RequireExternalExecutionEvent` / `UserConfirmResultEvent` / `ExternalExecutionResultEvent` / `AllToolsDeniedEvent` / `SubagentExposedEvent` / `HintBlockEvent` / `ConfirmResult`

`middleware.MiddlewareChain` / `TaskReminderMiddleware` / `FinalAnswerFilterMiddleware`；`tracing.OtelTracingMiddleware` / `Tracer` / `TracerRegistry` / `NoopTracer`

`state.ConflictPolicy` / `JsonFileAgentStateStore` / `ReadCacheEntry` / `SessionInfo` / `ListHashUtil` / `ConcurrentSessionModificationException` / `TaskContextState` / `Task` / `ToolContextState` / `PlanModeContextState` / `legacy.*`

`agent.AgentRun` / `RunControl` / `StreamingHook` / `StreamOptions` / `SubagentEventBus` / `EventSource` / `CallableAgent` / `StreamableAgent` / `ObservableAgent` / `AgentBase` / `config.ReactConfig` / `config.ModelConfig` / `config.FailoverListener` / `accumulator.*`（6）/ `user.*`（4）

`skill.SkillRegistry` / `SkillBox` / `SkillToolFactory` / `DynamicSkillMiddleware` / `SkillHook` / `SkillFilter` / `SkillFileFilter` / `AgentSkillPromptProvider` / `util.*`（3）/ `repository.FileSystemSkillRepository`（`ClasspathSkillRepository` / `RuntimeContextSkillRepository` / `AgentSkillRepository` 有引用）

`memory.*`（8 类全部）/ `rag.GenericRAGHook` / `rag.KnowledgeRetrievalTools` / `rag.RAGMode` / `shutdown.*`（10 类）/ `interruption.*`（3 类）/ `hook.recorder.JsonlTraceExporter` / `hook.LegacyHookDispatcher` / `hook.HookEventType` / `hook.*Event`（除 `ErrorEvent` 外）

**B. 官方 harness（对应任务书给出的 0 引用清单，全部交叉验证成立）**

`CompactionMiddleware` / `CompactionConfig` ✅ 0 · `WorkspaceManager` ✅ 0 · `SandboxManager` ✅ 0 · `SubagentsMiddleware` ✅ 0 · `TeamsMiddleware` ✅ 0 · `TranscriptStore` ✅ 0 · `PlanModeMiddleware` ✅ 0 · `MemoryConfig` ✅ 0 · `SkillRuntime` ✅ 0（该类名在 harness 源码中亦未找到，官方对应物是 `skill/SkillRegistry` + `SkillBox`，**任务书此项为【未实证】**）· `FilesystemTool` ✅ 0（`grep FilesystemTool` 的 5 处命中全部是 `disableFilesystemTools()` 方法名，非类引用）· `ShellExecuteTool` ✅ 0 · `MemorySearchTool` ✅ 0 · `AsyncToolMiddleware` ✅ 0 · `AgentTraceMiddleware` ✅ 0 · `AgentSpawnTool` ✅ 0 · `TaskTool` ✅ 0 · `TeamTool` ✅ 0

**已引用的 harness 类（对照）**：`HarnessAgent`（14）/ `agent.tools.ToolsConfig`（2）/ `agent.gateway.{TurnLease,SessionTurnGate,LocalSessionTurnGate}`（各 1）

**C. 任务书清单的两处修正**

1. **`CompactionMiddleware` 虽 0 引用，但在 A1/A2/A4 运行时是活的** —— 官方 `HarnessAgent.java:1243` `boolean disableCompaction = false` 为默认值，A1/A2/A4 均未调用 `disableCompaction()`。**「0 引用」≠「未启用」**，这是本报告最容易被误读的一处。
2. **`SkillRuntime` 在官方 2.0.4-SNAPSHOT 源码中未找到同名类** —— 官方 skill 子包 11 个文件已全部枚举（`SkillHook`/`SkillFileFilter`/`repository/*`×4/`util/*`×3/`DynamicSkillMiddleware`/`SkillToolFactory`/`AgentSkill`/`SkillRegistry`/`SkillBox`/`AgentSkillPromptProvider`），无 `SkillRuntime`。该项标 **【未实证】**。

---

*报告结束。所有「已实证」结论均附可复跑的 grep / find / Read 依据；所有「仅文档提及」「未实证」项已显式标注，未作推测。*
