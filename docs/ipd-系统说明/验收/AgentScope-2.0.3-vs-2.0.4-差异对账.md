# AgentScope 2.0.3 vs 2.0.4 差异对账

> 审计编号：A02（AgentScope 版本对账）
> 对账日：2026-10-03
> 对账对象：本仓锁定的 `io.agentscope:*` **2.0.3**（已发布 jar） ↔ 官方仓 `/Users/mac/Documents/agentscope-java` 的 **2.0.4-SNAPSHOT**（未发布源码）
> 结论一句话：**本仓使用的 204 个 AgentScope 类中，27 个在 2.0.4 有变化；其中只有 1 处会导致本仓编译失败（`WorkspaceSkillRepository` 构造函数参数类型改变，涉及 4 个构造点），其余全部是向后兼容的新增或本仓未调用的删除。**

---

## ① 对账方法（仪器与口径）

### 1.1 两台仪器

| 侧 | 仪器 | 性质 | 说明 |
|---|---|---|---|
| 2.0.3 | `javap -p -classpath <13 个 jar>` | **字节码实测** | 直接从 `~/.m2/repository/io/agentscope/*/2.0.3/*.jar` 读，权威 |
| 2.0.4 | 源码解析（自建解析器） | **源码实测，非字节码** | 读 `/Users/mac/Documents/agentscope-java` 的 `.java` 源码 |

**为什么 2.0.4 只能用源码**：`~/.m2` 下不存在任何 2.0.4 版本的 jar（已 `find` 确认，结果为 0）；参考仓也没有构建产物（无 `target/` 目录）。因此 2.0.4 侧无法 `javap`。这是本次对账最重要的口径限制，第 ⑤ 节展开。

### 1.2 基线锚点（可复核）

```
2.0.3 侧源码基线：git tag v2.0.3 → commit 1b8e3dcd2338550ae5198bdb2a7bae56df5bf2e0
                 src/main/java 下 1799 个 .java
2.0.4 侧源码基线：HEAD e9721285c63a37c10b1d07aa540408e57ba56ab2
                 提交时间 2026-10-01 17:17:16 +0800
                 提交标题 feat(middleware): add onAgentStateReady extension point (#3370)
                 pom revision = 2.0.4-SNAPSHOT（正因如此它不是发布版）
                 src/main/java 下 1902 个 .java
2.0.3 jar：~/.m2/repository/io/agentscope/ 下 13 个 jar（另有一个 pom-only 的 agentscope-bom，无 jar）
```

### 1.3 扫描范围与排除

**口径**：只统计**现役源码树**。命令级排除 `.codex/`、`.harness/`、`target/`、`.git/`。
理由：本仓 `.codex/` 与 `.harness/` 是历史工作树副本，含归档全仓 35,166 个 `.java`，而真实源码只有约 2,281 个——归档是现役的 15 倍，不排除会把归档里的旧实现当现役代码。

实测：扫描到 **2,291 个 `.java`**（与 CLAUDE.md 记录的 2,281 同量级，差值来自近期新增文件）。

### 1.4 使用面的抽取方式

1. 收集全部 `import (static) io.agentscope.*` 语句 → 得到显式 import 的类；
2. 收集 18 个**通配符 import** 的包，再在这些包的类里做**简单名匹配**，得到「可能被使用」的类；
3. 合并去重 → **204 个类**（显式 import 187 个 + 通配符解析补充 17 个净新增，两者有重叠）。

**置信度分级**：
- 187 个显式 import 类 → 高置信（确定被引用）；
- 17 个通配符解析类 → 中置信（简单名匹配，理论上可能误收同名类，实测样本未发现误收）。

统计：
```
含 io.agentscope 字样的文件       266
含真实 import 语句的文件          233   （src/main 110、src/test 123）
按模块分布（含字样文件）          ruoyi-ipd 133、ruoyi-chat 100、其他 33
去重后使用的类                    204
```

### 1.5 差异判定规则

对每个类，比较两侧的**显式声明的 public/protected 成员**：

- **新增 / 删除**：比较 `(方法名, 参数类型列表)`；构造函数的参数类型列表同时给出，用于识别「同参数个数但参数类型变了」；
- **返回值 / 注解变化**：同名同参数时，比较返回类型与注解集合；
- **字段**：比较 public/protected 字段名与类型；
- **类头**：比较 `extends` / `implements` / 注解；
- **记录（record）组件**：通过类头文本比较（类头包含组件列表）。

### 1.6 仪器自证（本轮发现的仪器缺陷，已修复）

按本仓「先验仪器再读数」纪律，解析器在上线前先用 2.0.3 做了双向校验，过程中发现并修复两个真实缺陷：

| # | 缺陷 | 症状 | 影响 |
|---|---|---|---|
| 1 | javap 参数切分用了正则前瞻 `,(?![^<]*>)` | 双层泛型 `Function<A, Flux<B>>` 被切成 5 个参数（实际 4） | 会伪造出「方法签名变化」；**只影响校验指标，不影响最终差异**（差异用的源码头不是 javap 参数） |
| 2 | 源码解析把含括号的字段初始化语句当方法 | `= Set.of(...)` 被识别成名为 `of` 的方法 | 同上，两侧对称，不影响差异 |

另修复：嵌套类型声明被误当成外层类型的方法、接口方法缺省 `public` 未识别、枚举常量被误当方法。

**修复后的仪器校验结果（2.0.3 内部自洽性）**：

```
javap 可解析类            204 / 204   （全部命中，0 个找不到）
反向校验（源码有、javap 无）   1 个成员
正向召回（javap 有、源码也有） 88.7%（1,530 / 1,724）
```

- **反向 1 个成员 ≈ 0**：说明 `v2.0.3` tag 的源码与已发布的 2.0.3 jar **实质一致**，用 tag 源码代表 jar 是可靠的。
- **正向缺口 11.3%**：全部是**编译器/Lombok 生成**的成员——构造函数 32、`equals` 21、`toString` 21、`hashCode` 21，以及枚举的 `values`/`valueOf`、record 的访问器与规范构造函数。**这些在两侧都不可见，因此对「差异」判断是对称的，不产生假差异。**

已知解析器盲区（两侧同盲，不影响差异结论）：
1. Lombok 生成的成员（`@Data`/`@Builder`/`@AllArgsConstructor` 等产出的 getter/setter/builder/构造）；
2. record 的**规范构造函数**（源码中不显式写出）；
3. 枚举的 `values()` / `valueOf()`。

---

## ② 本仓用到的 AgentScope 类清单（204 个）

按包分组。`显式` = 有 import 语句；`通配` = 由通配符 import 的包解析得到。

| 包 | 类 |
|---|---|
| `core.agent` | 显式：Agent、RuntimeContext |
| `core.agent.accumulator` | 显式：ReasoningContext |
| `core.agui.adapter` | 显式：AguiAdapterConfig、AguiAgentAdapter |
| `core.agui.adapter.strategy` | 显式：AgentEventConverterRegistry、AguiStreamContext |
| `core.agui.converter` | 显式：AguiMessageConverter |
| `core.agui.encoder` | 显式：AguiEventEncoder |
| `core.agui.event` | 显式：AguiEvent、AguiEvent.JsonPatchOperation |
| `core.agui.model` | 显式：AguiResume、AguiTool、RunAgentInput；通配：AguiContext、AguiFunctionCall、AguiMessage、AguiToolCall、ImageInputContent、InputContentUrlSource |
| `core.embedding` | 显式：EmbeddingModel |
| `core.embedding.dashscope` | 显式：DashScopeMultiModalEmbedding、DashScopeTextEmbedding |
| `core.embedding.ollama` | 显式：OllamaTextEmbedding |
| `core.embedding.openai` | 显式：OpenAITextEmbedding |
| `core.event` | 显式：AgentEvent、AgentResultEvent、ConfirmResult、ExceedMaxItersEvent、ModelCallEndEvent、ModelCallStartEvent、RequestStopEvent、RequireExternalExecutionEvent、RequireUserConfirmEvent、TextBlockDeltaEvent、ThinkingBlockDeltaEvent、ToolCallStartEvent、ToolResultEndEvent、ToolResultTextDeltaEvent；通配：AgentEndEvent、AgentStartEvent、ToolCallDeltaEvent、UserConfirmResultEvent |
| `core.hook` | 显式：Hook；通配：ErrorEvent、HookEvent |
| `core.memory` | 显式：LongTermMemory、LongTermMemoryTools |
| `core.message` | 显式：Base64Source、ContentBlock、GenerateReason、HintBlock、ImageBlock、Msg、MsgRole、TextBlock、ThinkingBlock、ToolCallState、ToolResultBlock、ToolResultState、ToolUseBlock、URLSource |
| `core.middleware` | 显式：ActingInput、AgentInput、MiddlewareBase、ModelCallInput、ReasoningInput |
| `core.model` | 显式：ChatResponse、ChatUsage、ExecutionConfig、GenerateOptions、Model、ModelCreationContext、ModelHttpException、ModelRegistry、ToolSchema |
| `core.model.transport` | 显式：HttpTransport、HttpTransportConfig、HttpTransportFactory、JdkHttpTransport |
| `core.permission` | 显式：PermissionBehavior、PermissionContextState、PermissionDecision、PermissionEngine、PermissionMode、PermissionRule |
| `core.rag` / `core.rag.knowledge` / `core.rag.model` / `core.rag.store` | 显式：Knowledge、SimpleKnowledge、Document、DocumentMetadata、RetrieveConfig、InMemoryStore |
| `core.shutdown` | 显式：GracefulShutdownConfig、GracefulShutdownManager |
| `core.skill` / `core.skill.repository` / `core.skill.util` | 显式：AgentSkill、SkillFilter、AgentSkillRepository、AgentSkillRepositoryInfo、ClasspathSkillRepository、FileSystemSkillRepository、SkillUtil |
| `core.state` | 显式：AgentState、AgentStateStore、InMemoryAgentStateStore、State、VersionedState |
| `core.tool` | 显式：AgentTool、Tool、ToolBase、ToolCallParam、ToolParam、Toolkit、ToolkitConfig；通配：SchemaOnlyTool |
| `core.tool.mcp` | 显式：McpAsyncClientWrapper、McpClientWrapper |
| `core.tracing` | 显式：OtelTracingMiddleware |
| `extensions.model.openai.exception` | 显式：OpenAIException |
| `extensions.mysql.state` | 显式：MysqlAgentStateStore |
| `extensions.redis.state(.redisson)` / `extensions.redis.store` | 显式：RedisAgentStateStore、RedisClientAdapter、RedissonClientAdapter、RedisStore |
| `harness.agent` | 显式：HarnessAgent、IsolationScope |
| `harness.agent.artifact` | 显式：ArtifactDeliveryRequest、ArtifactDeliveryResult、ArtifactDeliveryTarget |
| `harness.agent.bus` | 显式：AsyncToolRecord、AsyncToolRegistry、MessageBus、WorkspaceAsyncToolRegistry、WorkspaceMessageBus |
| `harness.agent.filesystem(.local/.sandbox/.spec/.remote.store)` | 显式：AbstractFilesystem、LocalFilesystem、PinnedSandboxFilesystem、SandboxBackedFilesystem、LocalFilesystemSpec、SandboxFilesystemSpec、BaseStore、InMemoryStore、StoreItem |
| `harness.agent.gateway` | 显式：LocalSessionTurnGate、SessionTurnGate、TurnLease |
| `harness.agent.memory(.compaction/.session)` | 显式：MemoryBackgroundTasks、MemoryConfig、CompactionConfig、SessionTranscriptWriter、SessionTree；通配：SessionEntry |
| `harness.agent.middleware` | 显式：AgentTraceMiddleware、AsyncToolMiddleware、HarnessSkillMiddleware、SubagentEntry |
| `harness.agent.sandbox(.impl.docker/.snapshot)` | 显式：Sandbox、SandboxAcquireResult、SandboxExecutionGuard、SandboxIsolationKey、SandboxLease、SandboxState、WorkspaceSpec、DockerFilesystemSpec、DockerSandbox、DockerSandboxClient、DockerSandboxClientOptions、DockerSandboxState、LocalSandboxSnapshot、LocalSnapshotSpec、NoopSnapshotSpec、SandboxSnapshot、SandboxSnapshotSpec |
| `harness.agent.skill(.curator/.runtime)` | 显式：WorkspaceSkillRepository、SkillCandidate、SkillCuratorConfig、SkillPromoter、SkillPromotionGate、SkillSecurityScanner、SkillVisibilityFilter、HarnessSkillEntry、SkillCatalog |
| `harness.agent.subagent(.task)` / `harness.agent.team` | 显式：SubagentFactory、LocalTeamClient、TeamClient、TeamContext、TeamCreateSpec、TeamTask；通配：TaskRunSpec |
| `harness.agent.tool` | 显式：ArtifactDeliveryTool、FilesystemTool、MemoryGetTool、MemorySaveTool、ShellExecuteTool、SkillManageConfig、WebTools；通配：MemorySearchTool、SessionSearchTool |
| `harness.agent.tools` | 显式：HarnessPlatformTools、ToolsConfig |
| `harness.agent.transcript` | 显式：FilesystemTranscriptStore、TranscriptRef、TranscriptStore |
| `harness.agent.workspace` | 显式：WorkspaceManager、WorkspacePathNormalizer |

---

## ③ 逐类差异表（27 个类有变化，其余 177 个无差异）

汇总：**新增方法 55、删除方法 9、参数类型变更 4、返回值/注解变更 5、新增字段 4、删除字段 1**。

27 个变化类中，**19 个在本仓 main 代码里被显式 import，8 个仅出现在测试或不出现**。下表是显式 import 的文件数（口径见 3.3 说明），用于判断影响面：

| 类 | main | test | | 类 | main | test |
|---|---|---|---|---|---|---|
| RuntimeContext | 26 | 23 | | WorkspaceSkillRepository | 3 | 0 |
| AgentEvent | 15 | 11 | | ChatUsage | 3 | 4 |
| MiddlewareBase | 12 | 0 | | ToolBase | 2 | 6 |
| HarnessAgent | 11 | 22 | | OtelTracingMiddleware | 2 | 0 |
| Tool | 9 | 0 | | AguiStreamContext | 1 | 1 |
| ToolResultBlock | 8 | 6 | | WorkspacePathNormalizer | 1 | 1 |
| AgentState | 7 | 8 | | GracefulShutdownManager | 1 | 2 |
| Toolkit | 6 | 8 | | McpClientWrapper | 1 | 1 |
| AgentTool | 6 | 2 | | ToolsConfig | 1 | 0 |
| ToolUseBlock | 4 | 11 | | 其余 8 个类 | 0 | 见 3.4 |

其中 `ConfirmResult` main 0 / test 5、`LocalFilesystemSpec` main 0 / test 10、`DockerSandbox`、`AgentTraceMiddleware`、`AsyncToolMiddleware`、`HarnessSkillMiddleware` 均 main 0 / test 1、`MemorySearchTool` 与 `SessionSearchTool` main 0 / test 0（由框架内部装配，本仓不直接引用）。

### 3.1 会导致本仓编译失败（1 个类）

#### `harness.agent.skill.WorkspaceSkillRepository` ⚠️ **破坏性**

构造函数**参数类型整体更换**——不是加一个重载，而是把基于 `Supplier<RuntimeContext>` 的那组换成了基于 `String source` 的那组：

| 版本 | 构造函数 |
|---|---|
| 2.0.3 | `(AbstractFilesystem, String, Supplier<RuntimeContext>)` |
| | `(AbstractFilesystem, String, Supplier<RuntimeContext>, String)` |
| | `(AbstractFilesystem, String, Supplier<RuntimeContext>, String, boolean)` |
| 2.0.4 | `(AbstractFilesystem, String)` ← 新增 |
| | `(AbstractFilesystem, String, String source)` ← **替换**了上面的 3 参 |
| | `(AbstractFilesystem, String, String source, boolean writable)` ← **替换**了上面的 4 参 |
| | ~~5 参~~ ← 删除 |

方法变化（均为新增重载，旧签名保留）：
- 新增：`skillExists(String, RuntimeContext)`、`save(List, boolean, RuntimeContext)`、`delete(String, RuntimeContext)`、`readSkillFile(String, String, RuntimeContext)`、`writeSkillFile(String, String, String, RuntimeContext)`、`deleteSkillFile(String, String, RuntimeContext)`
- 删除：`resolveContext()`

**本仓受影响点（4 处构造，全部传 `() -> context` 形式的 lambda）**：

```
ruoyi-modules/ruoyi-chat/.../chat/kernel/ChatOfficialCapabilities.java:123
ruoyi-modules/ruoyi-ipd/.../ipd/agent/kernel/ProjectAgentSkillGovernance.java:53
ruoyi-modules/ruoyi-ipd/.../ipd/agent/kernel/ProjectAgentSkillPublisher.java:56
ruoyi-modules/ruoyi-ipd/.../ipd/agent/kernel/ProjectAgentSkillPublisher.java:57
```

四处写法均为 `new WorkspaceSkillRepository(filesystem, "skills/_drafts", () -> context)` 形态（3 参）。在 2.0.4 下第三个参数是 `String`，lambda 无法赋给 `String` → **编译期报错**。

> 注：本仓未调用 `resolveContext()`（实测 0 处），该删除不影响本仓。

### 3.2 删除了成员，但本仓未调用（4 个类，**当前安全，属潜在风险**）

| 类 | 删除的成员 | 本仓调用点数 |
|---|---|---|
| `harness.agent.HarnessAgent` | `promoteSkill(String, String)`、`getRuntimeContext()` | 0（`getRuntimeContext` 全仓 13 处命中均为中间件入参对象的方法，非 `HarnessAgent` 的） |
| `core.state.AgentState` | `interruptControl()` | 0 |
| `core.tool.Toolkit` | `setInternalChunkCallback(BiConsumer)` | 0 |
| `core.shutdown.GracefulShutdownManager` | `registerRequest(Agent)` → 改为 `registerRequest(Agent, RunControl)` | 0 |

同组另有：`core.agui.adapter.strategy.AguiStreamContext` 删除字段 `REASONING_MESSAGE_ID_SUFFIX`（本仓 0 处引用）。

### 3.3 新增成员全部向后兼容（含接口默认方法）

**关键判断：以下新增的接口方法全部是 `default` 实现，本仓的实现类不会被破坏。**

| 类 | 新增 | 是否 `default` |
|---|---|---|
| `core.middleware.MiddlewareBase` | `activePoints()`、`onAgentStateReady(Agent, RuntimeContext, AgentState, List)` | ✅ 均为 `default` |
| `core.tool.AgentTool` | `isReturnDirect()` | ✅ `default`，返回 `false` |
| `core.tool.Tool` | `returnDirect()` | ✅ 注解成员，`default false` |
| `core.tool.ToolBase` | `isReturnDirect()` | 具体 `final` 方法，非抽象 |

**`activePoints()` 的行为语义（重要）**：其 `default` 实现返回 `EnumSet.allOf(ExtensionPoint.class)`，即**全集**——语义等价于「本中间件在所有扩展点都生效」，与升级前行为一致。`ReActAgent` 消费处（`ReActAgent.java:825`）用 `requireNonNullElseGet(..., 全集)`，把 `null` 也视为全集。因此本仓三个自定义中间件（`ChatOfficialCapabilities`、`OfficialToolGovernanceMiddleware`、`ChatSafeTranscriptStore`，均 `implements MiddlewareBase` 且未覆写该方法）**在 2.0.4 下行为不变**，不会出现「静默失效」。

按**显式 import 的文件数**统计：`core.tool.Tool` 出现在 9 个 main 文件、`AgentTool` 6 个、`ToolBase` 2 个；因新增的是 `default`，均不受影响。

> 计数口径说明：本节的「引用数」一律指**显式 `import <FQCN>;` 的文件数**，不是简单名出现次数。用简单名匹配会把 `Tool` 误计到 `Toolkit`/`ToolCallParam`/`AgentTool` 等类上（实测同一批类会从 9 虚增到 20），故不采用。

### 3.4 其余变化（新增能力 / 语义微调）

| 类 | 变化 | 对本仓 |
|---|---|---|
| `core.agent.RuntimeContext` | 新增 `getRunId()`、`generateRunId()`、`getToolRequestConfig()`、`setToolRequestConfig(ToolRequestConfig)` | 纯新增，安全（26 个 main 文件显式 import） |
| `core.tool.Toolkit` | 新增 `getTool(String, ToolRequestConfig)`、`isExternalTool(String, …)`、`getToolSchemas(Collection, …)`、`callTools(…, ToolRequestConfig, …)`、`closeMcpClients()` | 纯新增（另有 3.2 的删除） |
| `harness.agent.HarnessAgent` | 新增 `prepareRun(List, RuntimeContext)`、`prepareCall(List, RuntimeContext)` | 纯新增（另有 3.2 的删除） |
| `core.event.ConfirmResult` | 新增 `ConfirmResult(boolean, ToolUseBlock, List, String)`、`getReason()`；3 参构造失去 `@JsonCreator` | 本仓只用 2 参构造，安全；但**反序列化路径需复测** |
| `core.model.ChatUsage` | 新增 7 参构造、`getCacheCreationTokens()`、`getReasoningTokens()`、`getToolUsePromptTokens()`；4 参构造去掉 `@JsonCreator` 保留 `@JsonProperty` | 本仓只用 3 参构造，安全；**反序列化路径需复测** |
| `core.message.ToolResultBlock` / `ToolUseBlock` | 各新增 `isServerTool()` 与常量 `METADATA_SERVER_TOOL` | 纯新增 |
| `core.event.AgentEvent` | 新增常量 `METADATA_GENERATE_REASON` | 纯新增 |
| `harness.agent.sandbox.impl.docker.DockerSandbox` | 类头新增 `implements SandboxFileTransfer`；新增 `supportsFileTransfer(String)`、`uploadFile(String, byte[])`、`downloadFile(String)` | 2.0.4 自身已实现该接口，本仓仅使用，不受影响 |
| `harness.agent.tools.ToolsConfig` | 新增 `isStrictAllow()`/`setStrictAllow(boolean)`、`isDefaultToolsEnabled()`/`setDefaultToolsEnabled(boolean)` | 纯新增（本仓 main 引用 1 处） |
| `core.tool.mcp.McpClientWrapper` | 新增 `isPropagateMeta()`/`setPropagateMeta(boolean)` 与字段 `propagateMeta` | 纯新增 |
| `harness.agent.workspace.WorkspacePathNormalizer` | 新增 `of(String, NamespaceFactory)`、`normalize(String, RuntimeContext)`；`normalize(String)` 标记 `@Deprecated` | 仅弃用告警，编译通过 |
| `core.tracing.OtelTracingMiddleware` | 新增 `OtelTracingMiddleware(OpenTelemetry)`、`activePoints()` | 纯新增 |
| `harness.agent.middleware.AgentTraceMiddleware` / `AsyncToolMiddleware` / `HarnessSkillMiddleware` | 各新增 `activePoints()`（覆写为受限集合） | 纯新增 |
| `harness.agent.filesystem.spec.LocalFilesystemSpec` | 新增 `sharedLocalWorkspace(boolean)` | 纯新增（本仓 main 引用 0 处，测试 10 处） |
| `core.agui.adapter.strategy.AguiStreamContext` | 新增 `adoptToolCall(String)`；删除字段 `REASONING_MESSAGE_ID_SUFFIX` | 前者新增；后者本仓 0 引用 |
| `harness.agent.tool.MemorySearchTool` | `@Tool` 注解从 2 参 `memorySearch(RuntimeContext, String)` 移到**新增的 3 参** `memorySearch(RuntimeContext, String, String)`；2 参变成无注解的便捷重载 | 工具仍注册，模型可见 schema 多一个可选参数 |
| `harness.agent.tool.SessionSearchTool` | 同上形态：`@Tool` 移到 5 参重载，4 参变为无注解重载 | 同上 |

---

## ④ 对本仓升级风险的判断

### 4.1 必须改代码才能升级（阻塞项）

**只有 1 项：`WorkspaceSkillRepository` 的 4 个构造点。**

- 影响模块：`ruoyi-chat` 1 处、`ruoyi-ipd` 3 处（见 3.1 的路径与行号）。
- 性质：编译期失败，不会静默——升级后会立刻在构建阶段暴露。
- 修复方向（仅供参考，不含实施）：2.0.4 用 `String source` 取代 `Supplier<RuntimeContext>`，即上下文改为在**每次调用**时按新重载显式传入，而不是在构造时用 Supplier 捕获。构造函数与调用点的对应关系需要逐处重写，不是机械替换。
- 撤销方式：本对账只产出文档，未改动任何代码，无需撤销。

### 4.2 升级后需要复测（不阻塞编译，但可能行为变化）

1. **`ChatUsage` 与 `ConfirmResult` 的 JSON 反序列化**：两者都有构造函数失去 `@JsonCreator`。本仓若通过 Jackson 反序列化这两个类型（例如从 trace 表、审计 JSON、或持久化的 agent state 还原），需专门复测。本仓对 `ChatUsage` 的用法以 3 参构造为主（`MyChatModelListener`、`DifyChatServiceImpl`、`CozeChatServiceImpl`、`DoubaoStreamingChatModel` 等），构造方向安全；**反序列化方向未验证**。
2. **`@Tool` 注解位置迁移（`MemorySearchTool` / `SessionSearchTool`）**：工具仍会注册，但模型看到的参数 schema 变了（多一个可选参数）。若本仓有针对这两个工具的参数断言测试，需同步。
3. **`AgentState.interruptControl()` 删除**：本仓当前 0 引用，但中断控制是本仓 HITL/暂停恢复链路关注的能力；若后续要接入官方中断语义，需要改用 2.0.4 的新入口（本轮未定位）。

### 4.3 风险分级小结

| 级别 | 项 | 数量 | 本仓影响 |
|---|---|---|---|
| **P0 阻塞** | `WorkspaceSkillRepository` 构造参数类型更换 | 1 | 4 个构造点编译失败，涉及 ruoyi-chat + ruoyi-ipd 的 skill 治理装配 |
| **P1 需复测** | `ChatUsage` / `ConfirmResult` 的 `@JsonCreator` 调整 | 2 | 依赖是否走 Jackson 反序列化 |
| **P1 需复测** | `@Tool` 注解迁移导致的工具 schema 变化 | 2 | 参数断言测试可能失败 |
| **P2 潜在** | 删除但本仓未调用的成员（`HarnessAgent.promoteSkill`/`getRuntimeContext`、`AgentState.interruptControl`、`Toolkit.setInternalChunkCallback`、`GracefulShutdownManager.registerRequest(Agent)`） | 5 | 当前安全；若未来要使用需按新签名 |
| **P3 信息** | 其余新增重载与默认方法 | 50+ | 向后兼容 |

**升级可行性结论**：本仓 204 个使用类中，177 个（86.8%）在 2.0.4 完全无变化；变化的 27 个里，只有 `WorkspaceSkillRepository` 构成真实阻塞。整体升级面**收敛、可控**，且阻塞点会在编译期立刻暴露而非静默。

---

## ⑤ 未能确证的部分（如实标注）

### 5.1 方法本身的限制

1. **2.0.4 侧无字节码，只有源码**。因此 2.0.4 的 **Lombok 生成成员**与 **record 规范构造函数**在本对账中**不可见**。若 2.0.4 通过 Lombok 注解的变化（例如新增 `@Builder`）改变了生成 API，本对账**不会发现**。本轮实测中，2.0.3 侧被解析器漏掉的生成成员占 11.3%（1,724 个中的 194 个），可作为该盲区规模的量级参考。
   - 缓解：类头与成员**注解**本身是可见的，本轮已比较（`@Tool`/`@ToolParam`/`@JsonCreator`/`@JsonProperty`/`@Deprecated`），27 个变化类中注解变化命中 5 处。

2. **2.0.4-SNAPSHOT 是移动靶**。对账锚定在 HEAD `e9721285`（2026-10-01）。该 commit 之后到正式发布 2.0.4 之间若有新提交，本对账的 2.0.4 侧结论即失效。**`2.0.4-SNAPSHOT` 不等于已发布的 2.0.4。**

3. **只看签名，不看行为**。签名不变而语义变化的情况本对账无法发现。已知本轮新增的 `returnDirect` / `onAgentStateReady` / `activePoints` 都带有明确的行为语义（工具短路 ReAct 循环、agent state 就绪通知、中间件扩展点激活集合），其**运行时效果未经验证**。

4. **未编译验证**。本轮未执行 `mvn`（遵守并发构建约束，且任务明令禁止）。因此「4 个构造点会编译失败」是**基于签名的推断**，不是编译器给出的结论。同理，反向的「其余都兼容」也是签名层面的判断。

5. **通配符解析存在理论误收**。17 个由简单名匹配得到的类中置信度低于显式 import；本轮抽查未发现误收，但未做全量人工复核。
   - 已实测的误收案例（已排除，供参考）：本仓自有类 `org.ruoyi.ipd.copilotkit.RunAgentInput`、`org.ruoyi.domain.entity.mcp.McpTool`、`org.ruoyi.mcp.tools.ReadFileTool`、`CodingWorkspaceService.FileEntry` 均与 AgentScope 同名；在「构造函数调用点核对」环节按**导入语句**而非简单名重新判定后，7 个候选全部排除。

### 5.2 未覆盖的范围

1. **未读 `agentscope-service` / `agentscope-examples` / `agentscope-distribution` 模块**：本仓未引用这些模块的类，故未纳入。
2. **未对 204 个类做「行为级」验证**：每个类的运行时行为差异需要真实运行才能确认，超出本轮范围。
3. **未核对本仓的** `pom.xml` 版本声明位置与依赖收敛逻辑：本轮只对账 API 面，未分析升级时需要改哪些 POM 条目。`agentscope-bom` 是 pom-only 构件，本仓如何引用它未在本轮核实。

### 5.3 可以复核本对账的最小命令集

```bash
# 1) 2.0.3 jar 与参考源确实存在（仪器存在性）
ls ~/.m2/repository/io/agentscope/*/2.0.3/*.jar | wc -l          # 期望 13
git -C /Users/mac/Documents/agentscope-java rev-parse HEAD        # 期望 e9721285...
git -C /Users/mac/Documents/agentscope-java rev-parse v2.0.3      # 期望 1b8e3dcd...

# 2) 任一类的 2.0.3 签名（字节码实测）
CP=$(find ~/.m2/repository/io/agentscope -name '*.jar' -not -name '*sources*' | tr '\n' ':')
javap -p -classpath "$CP" io.agentscope.harness.agent.skill.WorkspaceSkillRepository

# 3) 2.0.4 侧构造函数（源码实测）
grep -n 'public WorkspaceSkillRepository' \
  /Users/mac/Documents/agentscope-java/agentscope-harness/src/main/java/io/agentscope/harness/agent/skill/WorkspaceSkillRepository.java

# 4) 本仓受影响的 4 个构造点
grep -rn 'new WorkspaceSkillRepository' ruoyi-modules/ --include='*.java'

# 5) activePoints 默认实现（判断是否静默失效）
sed -n '107,109p' /Users/mac/Documents/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/middleware/MiddlewareBase.java
```

---

## 附：本对账的产出物清单

- **本文档**：`docs/ipd-系统说明/验收/AgentScope-2.0.3-vs-2.0.4-差异对账.md`
- 未改动任何 Java / yml / scripts / CLAUDE.md / 看板镜像 / 其他 docs。
- 未执行 `commit` / `push`；未运行 `mvn`；未重启或终止任何进程。
- 中间产物（解析器、javap 输出、差异 JSON）全部落在 `/tmp`，不进入仓库。
