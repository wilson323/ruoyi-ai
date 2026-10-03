# AgentScope 归位方案：subagent / team / task 编排

- 日期：2026-10-02
- 范围：subagent（子 agent）、team（多 agent 协同）、task（后台任务）、message bus、periodic gate
- 官方源码仓：`/Users/mac/Documents/agentscope-java`（`agentscope-harness` 模块，AgentScope 2.0.x）
- 项目侧装配点：
  - `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeChatKernel.java:341-342`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java:283-284`
  - 另有两处次要装配点：`DurableHarnessRunProcessor.java:421`、`CodingServiceImpl.java:120`、测试 `AgentScopeAuditHookTest.java:46`
- 纪律：本文档只做**只读实证 + 方案设计**，未修改任何 `src/` 下文件与 `pom.xml`。

---

## 0. 结论速览

| 维度 | 结论 |
|---|---|
| 官方 API 面 | 已实证 **28 个类**的构造签名/关键方法/Builder 开关（见 §1） |
| 官方能力可直接吃掉的自研 | 后台任务 + 完成后反向通知（TaskRepository）、并行有界分析 worker（delegate_task）、跨会话 inbox 投递、per-session 并发门 |
| 官方**不能**替代自研 | 业务级 durable 状态机（run 状态 / 工具副作用账本 / 审批 / 恢复投影）、OwnershipMiddleware 的 run-epoch 围栏、四维隔离键 |
| 归位后**必然丢失**的保证 | ① 工具副作用的 exactly-once 账本 ② run 状态机的显式重入保护 ③ 项目/员工维 fail-closed 键（官方只认 userId/sessionId 两维） |
| OwnershipMiddleware 归属 | **保留为自定义 `MiddlewareBase`**（推荐路线），官方无等价物 |
| 隔离键收口 | 官方 key 接口全部接受**不透明字符串** → 一律喂 `scope.slotId()`（四维）即成立，**无需改 `KernelScopeKey` 格式、无需迁移存量**（初稿「必须改格式」的结论已撤销，见 §5.4） |
| 归位是否被阻塞 | **否**。步骤 0 已由 C 类降为 A 类（AI 自主），归位可即刻启动 |
| 顺手发现 | `KernelScopeKey` javadoc 引用了一个**全仓不存在的类 `MysqlAgentStateStore`**，且把 Redis key 说成「落库 slotId」——已单列为步骤 0-bis |

---

## 1. 官方 API 面实证表

**实证方式**：直接 `Read` / `grep` 官方源码（路径前缀省略 `agentscope-harness/src/main/java/io/agentscope/harness/`，下同）。

### 1.1 子 agent 声明层

| 类 | 构造 / 工厂 | 关键方法 | 状态 |
|---|---|---|---|
| `agent/subagent/SubagentDeclaration` | `SubagentDeclaration.builder()` | `getName/getDescription/getWorkspaceMode/getWorkspacePath/getInlineAgentsBody/getModel/getMaxIters/getSteps/getTemperature/getTopP/getVariant/getMode/isHidden/isPersistSession/isInheritParentPermissions/getExposeToUser/getEnablePendingToolRecovery/getTools/getSkills/isRemote/getUrl/getHeaders/isRemoteStreaming/getRemoteStreamDetail/getRemoteAskPolicy/getRemoteContextAttributes/hasDefinitionWorkspace` | **已实证**（701 行，全量抄出） |
| `agent/subagent/SubagentDeclaration.Builder` | — | `name/description/workspaceMode/workspace/inlineAgentsBody/model/maxIters/steps/temperature/topP/variant/mode/hidden/persistSession/inheritParentPermissions/exposeToUser/enablePendingToolRecovery/tools/skills/url/headers/remoteStreaming/remoteStreamDetail/remoteAskPolicy/remoteContextAttributes/build` | **已实证**（26 个 builder 方法） |
| `agent/subagent/WorkspaceMode` | 枚举 | `ISOLATED`（默认）/ `SHARED` | **已实证**（70 行） |
| `agent/subagent/SubagentFactory` | 接口 | `Function<String, Agent>` 语义，由 `HarnessAgentBuilderSupport` 实现 | **已实证**（48 行） |
| `agent/subagent/DefaultAgentManager` | `DefaultAgentManager(List<SubagentEntry>, WorkspaceManager)` | `refreshEntries/replaceAgents/createAgentIfPresent/isPrimaryOnly/hasAgent/getAgentFactories/getDeclaration/createAgent/invokeAgent(×2)/invokeAgentStream(×2)/getWorkspaceManager` | **已实证**（260 行） |
| `agent/subagent/SubagentSpecGenerator` | `new SubagentSpecGenerator(Model)` | `generateMarkdown(String, Collection<String>)` / `generateAndValidate(...)` / `record GeneratedSpec(markdown, declaration)` | **已实证**（159 行） |
| `agent/subagent/RemoteSubagentStub` | `new RemoteSubagentStub(String name, String description)` | 继承 `AgentBase`，54 行 | **已实证** |
| `agent/subagent/RemoteAskPolicy` / `RemoteStreamDetail` | 枚举 | DENY/PROPAGATE、STATUS/FULL/VERBOSE | **仅文档提及**（本次未逐字读枚举体） |
| `agent/subagent/AgentSpecLoader` | 439 行 | 工作区 `subagents/*.md` 解析 | **已实证**（存在） |

### 1.2 后台任务层（`agent/subagent/task/`）

| 类 | 构造 | 关键方法 | 状态 |
|---|---|---|---|
| `TaskRepository`（接口） | — | `getTask(rc, sessionId, taskId)` / `putTask(rc, taskId, subAgentId, sessionId, TaskRunSpec)` / `listTasks(rc, sessionId, filter)` / `cancelTask(rc, sessionId, taskId)` / `findPendingDeliveries(rc, sessionId)` / `markDelivered` / `isDelivered` / `setCompletionCallback` / `shutdown`；常量 `SUPPRESS_COMPLETION_CALLBACK`；内嵌 `TaskCompletionCallback.onCompleted(rc, taskId, subAgentId, sessionId, result)` | **已实证**（161 行，全量接口） |
| `WorkspaceTaskRepository` | `new WorkspaceTaskRepository(WorkspaceManager, String parentAgentId)`（另有 2 个重载 + 3 个 `forTests`） | `setCompletionCallback` / `putTask`；常量 `HEARTBEAT_INTERVAL_SECONDS=30` / `ORPHAN_TIMEOUT_MINUTES=10` / `SWEEP_INTERVAL_SECONDS=5` | **已实证**；**存储布局已实证** = `agents/<parentAgentId>/tasks/<sessionId>.json` |
| `TaskStatus`（枚举） | — | `PENDING / RUNNING / COMPLETED / FAILED / CANCELLED` + `isTerminal()` | **已实证**（36 行） |
| `TaskRecord` | `new TaskRecord()` / `new TaskRecord(taskId, subAgentId, parentAgentId, parentSessionId, subSessionId)` | 全套 getter/setter（`status/result/errorMessage/cancelRequested/createdAt/...`） | **已实证**（278 行） |
| `TaskDelivery`（record） | — | `taskId, agentId, result, errorMessage, ...` | **已实证**（45 行） |
| `TaskRunSpec`（sealed interface） | — | `record LocalTaskRunSpec(Supplier<String>)` / `record RemoteTaskRunSpec(baseUrl, headers, agentId, input, ...)` / `record AdoptedTaskRunSpec(CompletableFuture<String>)` | **已实证**（61 行） |
| `BackgroundTask` | `new BackgroundTask(taskId, agentId, CompletableFuture<String>)` | `whenComplete/getTaskId/getAgentId/getCreatedAt/getLastCheckedAt/updateLastCheckedAt/isCompleted/getTaskStatus/getStatus/getResult/getError/waitForCompletion(long)/cancel(boolean)` | **已实证**（158 行） |
| `AgentProtocolTaskClient` / `RemoteSubagentTransport` / `RemoteSubmitContext` / `RemoteTarget` / `RemoteTaskStatus` | 远程子 agent 传输层，合计 ~764 行 | — | **已实证**（存在） |
| `agent/subagent/protocol/RemoteAgentEvent` / `RemoteEventCodec` / `RemoteEventType` / `RemotePendingConfirm` / `RemoteConfirmDecision` / `RemoteStreamDetail` | 远程事件编解码 | — | **已实证**（存在） |

### 1.3 Team 层（`agent/team/`）

| 类 | 构造 / 形态 | 关键方法 | 状态 |
|---|---|---|---|
| `TeamClient`（接口，20 个方法） | — | `listTasks/createTask/assignTask/claimTask/completeTask/failTask/unclaimTask/listClaimableTasks/sendMessage/broadcastMessage/listMessages/listMembers/spawnMember/shutdownMember/submitPlan/approvePlan/rejectPlan/createTeam/completeTeam`，**全部带 `String namespace` 首参** | **已实证**（114 行全量） |
| `LocalTeamClient` | 650 行 | 本地实现，含 `cas(namespace, teamName, task, expectedVersion)` 乐观锁与 `deliverNotification(...)` | **已实证** |
| `TeamContext`（record） | 8 元：`teamName, namespace, objective, myRole, isLead, members, availableActions, recoveryContext` | `resolvedNamespace()` → namespace 空/空白时返回 **`"default"`**；内嵌 `MemberSnapshot` / `RecoveryContext` / `CompletedTask` / `InterruptedTask` / `RecentMessage` | **已实证**（69 行） |
| `TeamCreateSpec`（record） | `name, namespace, objective, leadAgentRef, leadPrompt, members` | — | **已实证**（27 行） |
| `TeamTask`（record） | `taskId, teamName, namespace, subject, description, state, owner, blockedBy, result, version` | 常量 `PENDING/IN_PROGRESS/COMPLETED/FAILED` + `isTerminal(state)` / `claimableOf(tasks, forMember)`（含 `blockedBy` 依赖判定）/ `toMap()` | **已实证**（139 行） |
| `TeamInfo` / `TeamMemberInfo` / `TeamMemberSpec` / `TeamMessage` | record | `TeamMemberInfo` 含 `isLead` 判定 | **已实证** |
| `TeamWakeups` | `TeamWakeups.register(Hook)` / `wake(teamName, memberName, notice)` | 全局 ThreadLocal Hook | **已实证**（56 行） |
| `TeamConflictException` | 23 行 | — | **已实证** |

### 1.4 消息总线与异步工具（`agent/bus/`）

| 类 | 构造 | 关键方法 | 状态 |
|---|---|---|---|
| `MessageBus`（接口，extends `AutoCloseable`） | — | 原始：`queuePush/queueDrain/queueDelete/queuePeek/logAppend/logRead/logTrim/publish/subscribe`；域助手：`inboxPush(sessionId,msg)` / `inboxDrain(sessionId,max)` / `enqueueWakeup(userId,sessionId,agentId)` / `enqueueWakeup(sessionId,agentId)` / `subscribeWakeup()`；常量 `SESSION_EVENTS_KEY_PREFIX="agentscope:session:events:"`、`SESSION_REPLAY_MAX_LEN=1000` | **已实证**（267 行） |
| `WorkspaceMessageBus` | `new WorkspaceMessageBus(AbstractFilesystem, String busRoot)` | 同上（文件系统实现） | **已实证**（277 行） |
| `AsyncToolRegistry`（接口） | — | `register/complete/fail/findStale(sessionId, ttl)/markTimeout` | **已实证**（79 行） |
| `WorkspaceAsyncToolRegistry` | 205 行 | 文件系统实现 | **已实证** |
| `AsyncToolRecord`（record） | `id, sessionId, toolName, toolCallId, status, ...` | 常量 `RUNNING/COMPLETED/FAILED/TIMEOUT` | **已实证**（42 行） |
| `BusEntry`（record） | `entryId, payload` | — | **已实证**（26 行） |

### 1.5 协调门（`agent/coordination/`）

| 类 | 构造 | 方法 | 状态 |
|---|---|---|---|
| `PeriodicGate`（接口） | — | `boolean tryClaim(String name, Duration minGap)` | **已实证**（37 行） |
| `LocalPeriodicGate` | 无参 | `tryClaim` / `cleanupStaleEntries(Duration)` / `clearForTests` / `seedLastClaimAtForTests` / `hasClaimForTests` | **已实证**（82 行） |
| `StoreBackedPeriodicGate` | `new StoreBackedPeriodicGate(BaseStore)` | `tryClaim` | **已实证**（90 行） |

### 1.6 Gateway（`agent/gateway/`）

| 类 | 构造 | 关键方法 | 状态 |
|---|---|---|---|
| `SubagentRegistry`（接口） | — | `register(SubagentRecord)` / `find(subagentId)` / `revoke` / `default revokeByParentSession(parentSessionId)` | **已实证**（71 行） |
| `StoreBackedSubagentRegistry` | `new StoreBackedSubagentRegistry(BaseStore)` | 同上；**NAMESPACE 实测 = `List.of("subagents","exposed")`（固定，全局）**，`SCAN_PAGE_SIZE=1000` | **已实证**（131 行） |
| `InMemorySubagentRegistry` | 无参 | 同上 | **已实证**（68 行） |
| `SubagentRecord`（record） | `subagentId, agentId, sessionId, userId, parentSessionId, ...` | `isExpired(Instant)` / `toMap()` / `fromMap()` | **已实证**（108 行） |
| `SessionTurnGate` / `LocalSessionTurnGate` | 无参 | `acquire(key) → TurnLease` / `isRunning(key)` | **已实证** |
| `TurnLease` | — | `extends AutoCloseable`，`close()` | **已实证**（34 行） |
| `WakeupDispatcher` | `new WakeupDispatcher(MessageBus, WakeupTarget)` | `start()` / `close()`；内嵌 `WakeupTarget{isSessionRunning, runWakeup}` | **已实证**（186 行） |
| `ChannelManager` | 无参 | `register/unregister/getChannel/channelIds/getAllChannels/initAll/startAll/stopAll/isStarted/deliver(OutboundAddress, List<Msg>)` | **已实证**（186 行） |
| `HarnessGateway` / `Gateway` / `GatewayBootstrap` | `GatewayBootstrap.builder()` | `agent(id, agent)` / `mainAgent(id)` / `channel(...)` / `configureAllAgents` / `distributedStore` / `runtimeContextResolver` / `build()` / `start()` / `stop()` / `gateway()` / `channelManager()` / `agents()` / `gatewayBridge()` / `chatUiChannel(...)` | **已实证**（331 行） |
| `SubagentGatewayBridge` / `SubagentMaterializer` / `SessionIdUtils` / `MsgContext` / `TurnBusyException` | — | 50 / 43 / 52 / 104 / 46 行 | **已实证**（存在） |

### 1.7 Middleware（`agent/middleware/`）

| 类 | 构造 | 钩子 / 方法 | 状态 |
|---|---|---|---|
| `SubagentsMiddleware` | 3 个重载：①`(entries, taskRepository, workspaceManager, filesystem, mainWorkspace, factoryBuilder)` ②`(entries, taskRepository, workspaceManager)` ③`(entries, externalSubagentTool, taskRepository)`（session 模式） | `activePoints()={ON_AGENT, ON_REASONING}` / `enableAgentGenerateTool(SubagentSpecGenerator)` / `getTaskRepository()` / `wireMessageBus(MessageBus, agentId)` / `setGatewayBridge(SubagentGatewayBridge)` / `getAgentManager()` / `getTools()` / `onAgent` / `onReasoning` / static `renderSubagentSection` / static `buildTaskSummary` | **已实证**（736 行） |
| `DynamicSubagentsMiddleware` | `(staticEntries, filesystem, mainWorkspace, factoryBuilder, agentManager, subagentTool, taskRepository)` | `activePoints()={ON_REASONING}` / `setGatewayBridge` / `getAgentManager` / `getTools` / `getTaskRepository` / `onReasoning` | **已实证**（279 行） |
| `TeamsMiddleware` | `new TeamsMiddleware(TeamClient, TeamContext)` | `activePoints()` / `bindSession(sessionId)` / static `registerSession/unregisterSession/wakeupSession(×2)/wakeupTeamMember(×2)` / `getTools()` / `teamContext()` / `teamClient()` / `wireMessageBus(MessageBus, agentId)` / `notifyWakeup(×2)` / `onAgent` / `onReasoning` / `hasOutstandingTeamWork()` / static `renderTeamSection` | **已实证**（708 行） |
| `InboxMiddleware` | `(MessageBus)` / `(MessageBus, int maxDrainCount)` / 3 参重载 | `activePoints()={ON_REASONING}` / `onReasoning`（每轮前 drain inbox + 清理 stale async tool） | **已实证**（231 行） |
| `AsyncToolMiddleware` | `(MessageBus, Duration offloadTimeout)` / 3 参重载 | `activePoints()={ON_ACTING}` / `onActing`（超时工具调用卸载到后台，结果经 inbox 回投） | **已实证**（303 行） |
| `AtPathExpansionMiddleware` | `(WorkspaceManager)` | `activePoints()={ON_AGENT}` / `onAgent`（把 `@path` 展开为文件内容） | **已实证**（204 行） |
| `SubagentEntry`（record） | `(name, description, factory)` | — | **已实证** |

### 1.8 工具（`agent/tool/`）

| 类 | 构造 | 工具名 / 方法 | 状态 |
|---|---|---|---|
| `AgentSpawnTool` | `(DefaultAgentManager, TaskRepository, int)` ×2 | 工具 `agent_spawn`（参数 `agent_id/task/label/timeout_seconds/expose_to_user`）、`agent_send`、`agent_list`；常量 `CTX_EXPOSE_TO_USER="agentscope.subagent.expose_to_user"` / `CTX_AGENT_MANAGER` / `CTX_REMOTE_CONTEXT_ATTRIBUTES` / `CTX_FORCE_SYNC="agentscope.subagent.force_sync"` / `CTX_FORCE_SYNC_TIMEOUT_SECONDS`；`setGatewayBridge(SubagentGatewayBridge)` | **已实证**（1795 行） |
| `TaskTool` | `new TaskTool(TaskRepository)` | 工具 `task_output` / `task_cancel` / `task_list` | **已实证**（252 行） |
| `WaitAsyncResultsTool` | `(MessageBus)` / `(MessageBus, TaskRepository)` | 工具 `wait_async_results`（`task_ids` / `wait_all` / `setExternalWorkProbe`） | **已实证**（489 行） |
| `TeamTool` | `new TeamTool(TeamClient, TeamContext)` | 工具 `team`（统一入口）+ 17 个细分方法（`listTasks/listClaimableTasks/createTask/assignTask/claimTask/unclaimTask/failTask/completeTask/sendMessage/broadcastMessage/listMessages/listMembers/spawnMember/shutdownMember/submitPlan/approvePlan/rejectPlan/completeTeam`） | **已实证**（709 行） |
| `AgentGenerateTool` | `(Model, ...)` | 工具 `agent_generate`（**默认关闭**） | **已实证**（155 行） |

### 1.9 Builder 开关（`agent/HarnessAgent.java`）

**已实证**（行号取自官方源文件）：

| 开关 | 行号 | 语义 |
|---|---|---|
| `disableSubagents()` | 2298 | 置 `disableSubagents=true`，跳过 `SubagentsMiddleware` 注册 |
| `disableDynamicSubagents()` | 2235 | 置 `disableDynamicSubagents=true`，跳过 `DynamicSubagentsMiddleware` |
| `subagent(SubagentDeclaration)` | 1947 | 追加一条声明 |
| `subagents(List<SubagentDeclaration>)` | 1952 | 批量追加 |
| `subagentFactory(String name, Function<String,Agent>)` | 1958 | 自定义工厂 |
| `subagentFactory(String name, String description, Function<String,Agent>)` | 1966 | 带描述的自定义工厂 |
| `taskRepository(TaskRepository)` | 1975 | 自定义后台任务仓储（**官方已提供扩展点，不必自研**） |
| `messageBus(MessageBus)` | 1985 | 设置后**自动注册 `InboxMiddleware`** |
| `asyncToolTimeout(Duration)` | 1995 | 启用 `AsyncToolMiddleware`（**依赖 messageBus**） |
| `asyncToolRegistry(AsyncToolRegistry)` | 2005 | 异步工具生命周期追踪 |
| `externalSubagentTool(Object)` | 2012 | 注入外部 subagent 工具（典型 `SessionsTool`） |
| `modelResolver(Function<String,Model>)` | 2018 | 子 agent 模型名 → `Model` 解析器 |
| `teamsMode(TeamClient, TeamContext)` | 1323 | 挂 `TeamsMiddleware` + 注册 `team` 工具 |
| `teamsMode(TeamClient, TeamContext, String sessionId)` | 1334 | 同上，额外绑定 sessionId 收 TeamEvents |
| `distributedStore(DistributedStore)` | 1662 | 跨副本恢复（state / 暴露子 agent） |
| `middleware(MiddlewareBase)` / `middlewares(List)` | 1613 / 1621 | 自定义中间件（文档：跑在所有 Harness 内置**之前**） |
| `stateStore(AgentStateStore)` | 1644 | 状态持久化 |
| `enablePendingToolRecovery(boolean)` | 1690 | 悬空工具调用恢复 |

### 1.10 隔离机制（`agent/IsolationScope.java` + `filesystem/remote/store/NamespaceFactory.java`）

| 项 | 实测结论 |
|---|---|
| `NamespaceFactory` | `@FunctionalInterface List<String> getNamespace(RuntimeContext rc)`，**每次 store 操作动态求值**；rc 为 null 时也允许（调用方传 `RuntimeContext.empty()`） |
| `IsolationScope` | 枚举 `SESSION / USER / AGENT / GLOBAL`；`toNamespaceFactory()` 实测语义：`USER` → `List.of(uid)`，uid 空则退 `List.of(sid)`，都空则 `List.of()`；`SESSION` → `List.of(sid)`，sid 空则 **`List.of()`**；`AGENT/GLOBAL` → `List.of()` |
| `StoreBackedSubagentRegistry.NAMESPACE` | **实测固定为 `List.of("subagents","exposed")`**，不含任何 userId/sessionId 段 |
| `MessageBus.inboxPush` | 实测 key = **`"agentscope:inbox:" + sessionId`**（全局固定前缀，只按 sessionId 分） |
| `MessageBus.enqueueWakeup` | 实测 key = **`"agentscope:wakeups"`**（全局单队列），userId 只作为 payload 字段 |
| `MessageBus` session events | key = `"agentscope:session:events:" + sessionId` |
| `WorkspaceTaskRepository` | 任务文件 = `agents/<parentAgentId>/tasks/<sessionId>.json`（`parentAgentId` 来自 `HarnessAgent.name`） |

> **对账关键**：官方**全部三类持久化寻址（store 命名空间、inbox key、任务文件路径）都只认 `sessionId` / `HarnessAgent.name` 两维，没有 project / tenant 维度，也没有 fail-closed 校验**。
>
> **补充（2026-10-02 二次实证）**：上述所有 key 参数**均为不透明字符串**——`inboxPush` 实现为 `"agentscope:inbox:" + sessionId` 纯拼接，**不解析、不校验格式**。因此这不是「官方不支持多维」，而是「**维度由调用方编进字符串**」。
>
> **项目侧既存正面先例（已实证）**：`AgentScopeChatKernel.java:66` 已实例化官方 `LocalSessionTurnGate`，`:203`/`:262` 用 `TURN_GATE.acquire(scope.slotId())` 喂入含 project 的四维串。同一模式直接套用到 `inboxPush` 即可，无需改 `KernelScopeKey` 格式。见 §5.4。

### 1.11 项目侧实测「官方类引用数」

对全仓 `*.java`（含测试）逐类 `rg -c`：

```
SubagentsMiddleware: 0   TeamsMiddleware: 0        AgentSpawnTool: 0
TaskTool: 0             TeamTool: 0               AsyncToolMiddleware: 0
MessageBus: 0           WorkspaceMessageBus: 0    WakeupDispatcher: 0
PeriodicGate: 0         SubagentDeclaration: 0     DynamicSubagentsMiddleware: 0
InboxMiddleware: 0      AgentGenerateTool: 0       WaitAsyncResultsTool: 0
StoreBackedSubagentRegistry: 0   LocalTeamClient: 0
```

即：**官方 subagent/team/bus/gate 能力在本项目 100% 未启用**，全部由自研顶替。

---

## 2. 能力对账表

自研对应物（`ruoyi-modules/ruoyi-chat/.../service/coding/harness/`，共 **41,920 行**含两个 kernel）：

| 子包 | 行数 |
|---|---|
| `loop/` | 8,423 |
| `tool/` | 4,948 |
| `plan/` | 3,552 |
| `model/` | 2,387 |
| `event/` | 1,658 |
| `app/` | 1,509 |
| `recovery/` | 1,458 |
| `context/` | 1,134 |
| `journal/` | 1,151 |
| `store/` | 1,065 |
| `approval/` | 920 |
| `runtime/` | 927 |
| `artifact/` | 953 |
| `modelruntime/` | 486 |
| `prompt/` | 481 |
| `skill/` | 308 |
| `config/` | 139 |

### 2.1 逐条对账

| # | 官方能力 | 官方 API（已实证） | 自研对应物 | 差异类型 | 归位判断 |
|---|---|---|---|---|---|
| 1 | 子 agent 声明 | `SubagentDeclaration` + `workspace/subagents/*.md` + 内置 `general-purpose`（`HarnessAgentBuilderSupport.java:119/213/256/308/362`） | `FrozenProjectAgentSkills`（skills 冻结）、无 subagent 声明 | **C. 自研更弱** | 可归位，零风险 |
| 2 | 同步 spawn + 超时 promote | `agent_spawn(timeout_seconds>0)`；实测 `timeout_promoted` 出现在 `SubagentsMiddleware.java:142` 与 `AgentSpawnTool.java:1127` | `HarnessDelegateTools.delegate_task` → `HarnessAnalysisDelegate.execute`（同步 String 返回，**无 promote**） | **B. 语义重叠但更窄** | 归位后失去「超时转后台」保障，需评估 |
| 3 | 后台任务 + 完成后自动反向通知 | `TaskRepository` + `WorkspaceTaskRepository` + `TaskStatus/TaskRecord/TaskDelivery/BackgroundTask` + `TaskTool` + `buildTaskSummary` | 自研 `harness/` run 状态机（`DurableHarnessRunProcessor` 5,649 行 + `HarnessStore`） | **A. 官方更弱** | **不可归位**（见 §3） |
| 4 | 任务取消 / 列举 | `task_cancel` / `task_list` | 自研 run 取消（`HarnessScheduler` / `HarnessActiveTurnRegistry`） | **A. 官方更弱** | 不可归位 |
| 5 | 有界并行分析 worker | `agent_spawn`（子 agent 完整 HarnessAgent，带工具） | `delegate_task`：**无工具、无工作区、单次模型调用**（实测 `HarnessAnalysisDelegate` 构造 `NativeModelRequest(..., tools=List.of(), maxTokens=1600)`，超时 90s） | **B. 官方更强但风险更高** | 可归位为可选路径，见 §6 步骤 4 |
| 6 | 消息总线 inbox 投递 | `MessageBus.inboxPush/inboxDrain` + `InboxMiddleware` | `HarnessEventHub` / `HarnessEventOutboxService`（`event/` 1,658 行，outbox 落库） | **A. 自研更强**（outbox 事务保证 vs 队列消费） | **不可直接归位**，可混用 |
| 7 | 唤醒派发 | `WakeupDispatcher` + `MessageBus.enqueueWakeup/subscribeWakeup` | 自研 `HarnessStartupRecovery` + `HarnessScheduler` 轮询 | **A. 自研更强** | 不可归位 |
| 8 | 周期节流门 | `PeriodicGate.tryClaim(name, minGap)`（Local / StoreBacked 两实现） | 自研 `timeoutScheduler` + `Clock` 门控 | **C. 官方更完整**（跨副本） | **可归位**：多副本部署时防重复执行 |
| 9 | 多 agent 团队协同 | `TeamClient`（20 方法）+ `LocalTeamClient`（CAS 乐观锁）+ `TeamTool`（18 方法）+ `TeamsMiddleware` + `TeamWakeups` | 无 | **C. 自研完全缺失** | 本项目 IPD 无多 agent 团队需求 → **不归位**（避免无谓复杂度） |
| 10 | 暴露子 agent / Channel 路由 | `HarnessGateway` + `ChannelManager` + `StoreBackedSubagentRegistry` + `GatewayBootstrap` + `SubagentGatewayBridge` | 无（前端走 SSE 直连内核） | **C. 自研无需求** | **不归位** |
| 11 | 异步工具卸载 | `AsyncToolMiddleware`（`ON_ACTING`，超时卸载）+ `AsyncToolRegistry` + `WaitAsyncResultsTool` | 自研 `HarnessToolBatchExecutor`（349 行）并发批执行 + `HarnessToolBatchExecution` | **B. 部分重叠** | 需评估，见 §6 步骤 5 |
| 12 | per-session 并发门 | `SessionTurnGate.acquire(key) → TurnLease`（`AutoCloseable`） | 自研 `HarnessSessionGate`（`runtime/`） | **B. 语义重叠** | 自研版已绑定 DB/乐观锁，**保留** |
| 13 | 子 agent 权限继承 | `SubagentDeclaration.inheritParentPermissions`（默认继承 DENY 规则）+ Plan Mode 自动继承只读 | 自研在 `AgentScopeProjectAgentKernel` 侧按 `spec.toolIds()` 白名单裁剪 toolkit（实测 `built.getToolkit().getToolNames()` 必须 ⊆ `spec.toolIds()`，否则 `throw new IllegalStateException("project agent exposes unselected tools")`） | **B. 自研更严**（白名单 vs 黑名单继承） | **保留自研白名单裁剪**，不改为继承制 |
| 14 | 递归保护 | 子 agent 强制标为 leaf + 硬上限 3 层 | `HarnessAnalysisDelegate` SYSTEM_PROMPT 显式禁止「create another plan, delegate work」 | **A. 等效** | 归位后由框架保证，更强 |
| 15 | `userId` 透传 | 文档明示父 `RuntimeContext.userId` 自动透到子 | 自研 `KernelScopeKey` 手工收口 | **A. 官方更强** | 归位后需仍经 `KernelScopeKey` |
| 16 | 工具副作用 exactly-once 账本 | **官方无** | 自研 `recovery/UncertainToolEffectGuard` / `ToolEffectLedgerReconciler` / `ToolEffectLedgerFailureReason` | **A. 自研独有** | **绝对不可归位** |
| 17 | run 状态机 + 显式重入 | **官方无**（官方只到 `AgentStateStore` 会话快照级） | 自研 `DurableHarnessRunProcessor` + `HarnessStore` + `HarnessRunState` / `HarnessSessionState` | **A. 自研独有** | **不可归位** |
| 18 | 审批（HITL）状态机 | 官方有 `RequireUserConfirmEvent` + `RemoteConfirmDecision`（**远程子 agent 场景**） | 自研 `approval/` 20 个类（`ClaimApprovalCommand` / `ApprovalClaimReceipt` / `StaleApprovalException` / `ApprovalOwnershipException` / 24h TTL 等，920 行） | **A. 自研远超** | 不可归位 |
| 19 | run-epoch 所有权围栏 | **官方无** | `OwnershipMiddleware` + `OwnershipGuardedTool` + `ProjectAgentEventSink.requireActiveOwnership()` | **A. 自研独有** | **不可归位**（见 §4） |
| 20 | 事件按 seq 落库 + 终态 CAS 收口 | 官方 `bus` 是队列/日志，无 seq 语义 | `ProjectAgentEventSink`（`onStep/onToolCall/onToolResult/onArtifact/onError/onComplete`，实现方 CAS 保证终态至多一次） | **A. 自研更强** | 不可归位 |
| 21 | 上下文压缩 / 摘要 | 官方 `.compaction(...)` / `.disableCompaction()` / `CompactionMiddleware` | 自研 `context/ContextEngine`（1,134 行）+ `DurableHarnessRunProcessor` 内 8 个 token 上限常量 | **A. 自研业务定制更强** | 保留 |
| 22 | 计划门控 | 官方 `.enablePlanMode()` | 自研 `plan/`（3,552 行）+ `PLAN_GATED_MUTATION_TOOLS` | **A. 自研更强** | 保留 |

差异类型图例：**A. 官方弱于自研（不可归位）** / **B. 部分重叠（可择机归位）** / **C. 官方补齐自研缺口（可归位）**

### 2.2 对账统计

- 归位后能删的自研：**≈ 0.5–1.0 k 行**（主要是 `modelruntime/HarnessAnalysisDelegate` 107 行 + `loop/tool/HarnessDelegateTools` 34 行 + 部分 `bus` 等价物）
- 归位后**新增**代码量：`SubagentDeclaration` 声明 + `messageBus` 装配 + `PeriodicGate` 装配，**约 150–300 行**
- 保留的自研主体：`loop/` 8,423 + `store/` 1,065 + `recovery/` 1,458 + `approval/` 920 + `event/` 1,658 + `plan/` 3,552 ≈ **17 k 行不动**

---

## 3. 编排语义差异

### 3.1 两套模型的根本区别

| 维度 | 官方 AgentScope harness | 自研 Durable Harness |
|---|---|---|
| **核心抽象** | 多 agent 协同：主 agent 通过 `agent_spawn` 委派给子 agent；子 agent 是完整 `HarnessAgent`，有独立会话/工作区/工具/技能 | 单进程 durable 状态机：一次「运行」是一个业务实体（`HarnessRunState`），由 `DurableHarnessRunProcessor` 推进 |
| **任务模型** | 进程内/跨副本的后台任务（`BackgroundTask` + `CompletableFuture`），执行粘在创建节点，结果经 inbox 回投 | 业务运行记录（`HarnessStore`），有 seq 事件日志、终态 CAS、工具副作用账本 |
| **消息机制** | `MessageBus` 队列 + `WakeupDispatcher` 订阅唤醒 | `HarnessEventHub` + `HarnessEventOutboxService`（outbox 模式，事件先落库再投递） |
| **失败恢复** | 靠 `WorkspaceTaskRepository` 的心跳（30s）+ 孤儿超时（10min）+ 5min sweep；会话状态靠 `AgentStateStore` 按 `(userId, sessionId)` 恢复 | `HarnessStartupRecovery` 启动扫描 + `FileRunJournalProjector` 投影 + `UncertainToolEffectGuard` 兜底「工具是否已生效」不确定性 |
| **重入语义** | `SessionTurnGate` 拿 `TurnLease`（同 key 互斥），**会话级** | run 级：`HarnessActiveTurnRegistry` + DB 乐观锁 + epoch 校验，**运行级** |
| **租户隔离** | 隐式——只认 `userId` / `sessionId` / `HarnessAgent.name`，**无 project 维度、无 fail-closed** | 显式——`KernelScopeKey` 四维（project × user × agent × session）fail-closed，`: 与 ..` 拒绝 |

### 3.2 逐项行为差异与归位后丢失的保证

| 保证 | 现状（自研） | 纯归位到官方后 | 结论 |
|---|---|---|---|
| **进程崩溃后工具不重复执行** | `UncertainToolEffectGuard` + `ToolEffectLedgerReconciler`：工具执行前登记「即将写」，崩溃后按账本判定是否已落库 | 官方**无此机制**。`BackgroundTask` 崩溃后按孤儿超时标记，不区分「工具是否已执行」 | ❌ **丢失**——必须保留自研账本 |
| **运行终态至多提交一次** | `ProjectAgentEventSink.onError/onComplete` 由执行器 CAS 保证 | 官方 `TaskStatus` 单向流转，但**无业务级 CAS** | ❌ **丢失** |
| **被抢占的 run 立即停手** | `OwnershipMiddleware` 在 `onAgent/onModelCall/onActing` 三个钩子 + 每个工具订阅处调 `requireActiveOwnership()` | 官方无 ownership 概念 | ❌ **丢失**——必须保留 |
| **重入同一 run 不会并发写** | `HarnessActiveTurnRegistry` + DB 乐观锁 | `SessionTurnGate` 是进程内 `LocalSessionTurnGate`，**不跨副本** | ⚠️ 需补 `StoreBackedPeriodicGate` 或保留自研 |
| **跨副本 / 跨节点恢复** | 单机假设为主（`FileHarnessStore`） | 官方 `distributedStore` + `StoreBackedSubagentRegistry` 天然支持多副本 | ✅ **官方更强**——归位收益点 |
| **项目/租户隔离** | `KernelScopeKey` fail-closed 四维 | 官方 key 只含 `sessionId`（inbox / 任务文件）或固定 `["subagents","exposed"]` | ❌ **必须自己保证**（见 §5） |
| **子 agent 工具白名单** | `spec.toolIds()` 白名单，超出即抛 | 官方默认**继承父的 DENY 规则**（黑名单），可 `inheritParentPermissions(false)` 关闭 | ⚠️ 语义不同，不可直接换 |
| **审批 HITL** | 20 类状态机 + 24h TTL + 乐观 claim | 官方只覆盖远程子 agent 的 confirm resume | ❌ 不可归位 |
| **Plan Mode 只读继承** | 自研 `plan/` 独立 | 官方自动继承（文档 + `SubagentDeclaration` 语义） | ✅ 归位收益点 |

### 3.3 结论

> **归位不是「整体切换」，而是「在自研 durable 骨架上挂载官方的委派/任务/总线能力」。**
> 官方 subagent/team 是一套**协作语义**，自研 harness 是一套**持久化语义**，两者正交。
> 若把 `disableSubagents()` 单纯删掉就完事，会同时丢掉 §3.2 表中标 ❌ 的 5 项保证。

---

## 4. OwnershipMiddleware 的归属

### 4.1 它的真实职责（实读结论）

`AgentScopeProjectAgentKernel.OwnershipMiddleware`（`AgentScopeProjectAgentKernel.java:299-315`）：

```java
static final class OwnershipMiddleware implements MiddlewareBase {
    private final ProjectAgentEventSink sink;
    OwnershipMiddleware(ProjectAgentEventSink sink) { this.sink = sink; }
    public int order() { return Integer.MAX_VALUE; }
    public Flux<AgentEvent> onAgent(...)  { return Flux.defer(() -> { sink.requireActiveOwnership(); return next.apply(input); }); }
    public Flux<AgentEvent> onModelCall(...) { /* 同 */ }
    public Flux<AgentEvent> onActing(...)   { /* 同 */ }
}
```

配套的 `OwnershipGuardedTool`（`:318-345`）在 `checkPermissions` 与 `callAsync` 两处再查一次（注释明示「同一 acting 批次的每个工具订阅也检查，不能只在批次开始检查一次」）。

**实读修正**：任务书描述为「事件投递/归属判定」。实读后应更正为——**OwnershipMiddleware 不投递任何事件**，它是一个 **run-epoch 围栏（fencing guard）**：在 SDK 每次模型调用/工具执行前，校验当前执行者仍是该 run 的合法持有者；已被抢占的 run 立即抛错停手。事件投递由 `ProjectAgentEventSink` 的 `onStep/onToolCall/onToolResult/onArtifact` 独立承担，**与 middleware 无关**。

### 4.2 明确结论

> **保留为自定义 `MiddlewareBase`。官方无等价物，不可被取代。**

理由（四条，均可实证）：

1. **官方无「所有权/epoch」概念**。已实证的官方能力里最接近的是 `SessionTurnGate`（`acquire(key) → TurnLease`），但那是**进程内互斥**（`LocalSessionTurnGate`），不是**跨副本的业务 epoch 校验**。项目需要的是「同一 run 被新执行者接管后，旧执行者必须立刻停手」，语义层级完全不同。

2. **`order() = Integer.MAX_VALUE` 是刻意的最后闸门**。官方 `architecture.md:28` 明示「内置 middleware 注册顺序固定，你自己加的跑在最前面」。`MAX_VALUE` 让本 middleware 处在整条链路最内层——SDK 内部任何路径（包括超时、工具取消、pending tool recovery）最终都要穿过它。这个位置是归位后**必须保留**的。

3. **配套的 `OwnershipGuardedTool` 逐工具包裹是项目独有的工具治理模型**。它把 `AgentTool` 降级包装为 `io.agentscope.core.tool.ToolBase`，并要求「必须是 native governed tool 否则抛 `IllegalArgumentException`」。官方工具白名单是声明式的（`SubagentDeclaration.tools(List<String>)` 或 `ToolsConfig`），**没有运行时逐调用围栏**。

4. **归位不改变这个 middleware 的必要性**。即使把 subagent 全归位，run-epoch 围栏仍然是项目对「同一 run 被抢占」这一 IPD 业务语义的强制要求（IPD 阶段动作的 run 会被重新调度）。

### 4.3 唯一需要调整的点

归位官方 subagent 后，`OwnershipMiddleware` 的 `onActing` 只会覆盖**父 agent** 的 acting。子 agent 的 acting 由子 agent 自己的 middleware 链负责。**子 agent 必须继承同一个 `ProjectAgentEventSink` 围栏**，否则会出现「父已失权、子还在跑」的空窗。

落地做法：在 `SubagentDeclaration` 上用 `subagentFactory(name, description, factory)` 自定义工厂（`:1958`），在工厂内为每个子 agent 挂同一个 `OwnershipMiddleware(sink)` 实例。这样围栏随工厂复制，天然覆盖全部子 agent。

---

## 5. 隔离键约束

### 5.1 现状：已经存在**两处**手拼隔离键

| 位置 | 拼法 | 维度 | fail-closed |
|---|---|---|---|
| `chat/kernel/KernelScopeKey.java:61-62` | `"p" + projectId + ":u" + userId` / `"a" + agentId + ":s" + sessionId` | project × user × agent × session | ✅ 拒绝 `:` 与 `..` |
| `chat/kernel/AgentScopeChatKernel.java:361-364`（`workspaceFor`） | `root.resolve(projectId).resolve(userId).resolve(agentId)` | project × user × agent（**无 session**） | ✅ 拒绝 `:` `/` `\` `..` `.`，并逐级 `isSymbolicLink` 拒绝符号链接 |

即：**文件面和状态面各有一套独立的键拼装逻辑**，只是语义同源。这是「第二处手拼隔离键」的**既存事实**，本次归位必须顺带收口。

### 5.2 官方机制会引出第三、第四处手拼

若启用官方 subagent，隔离键会分裂成**四个互不相干的寻址面**：

| 面 | 官方 key 构造（已实证） | 隔离维度 |
|---|---|---|
| 状态 | `AgentStateStore` 按 `(RuntimeContext.userId, RuntimeContext.sessionId)` | 复合 userId + 复合 sessionId ✅ |
| inbox 队列 | `"agentscope:inbox:" + ctx.getSessionId()` | **仅复合 sessionId**（`a{agentId}:s{sessionId}`，**丢 project 维**） |
| 任务文件 | `agents/<parentAgentId>/tasks/<sessionId>.json` | `HarnessAgent.name` + sessionId |
| 暴露子 agent | `["subagents","exposed"]` + `subagentId` | **零隔离**（仅当业务不开 `expose_to_user` 才安全） |
| 团队任务 | `TeamClient.*(namespace, teamName, ...)` | `TeamContext.namespace`，**空则 `"default"`**（`resolvedNamespace()` 实测） |
| 子 agent 工作区 | `ISOLATED` 时按「父 sessionId × 用户」分桶（文档 §ISOLATED vs SHARED） | 继承 `RuntimeContext`，**但工作区根路径另算** |

**最危险的两条**：

- `inboxPush` 的 key 里**只有 sessionId**。若某处传入裸 `sessionId` 而非复合 sessionId，两个项目的同号会话会**互相消费对方的后台任务完成通知**。
- `TeamContext.resolvedNamespace()` 在 namespace 为空时返回 `"default"`——这是 **fail-open**。所有 team 任务会落进同一个 `"default"` 命名空间。

### 5.3 收口做法（具体、可执行）

**目标**：`KernelScopeKey` 是唯一允许拼隔离键的文件；其余一切面**只能消费 `KernelScopeKey.Scope` 的产物，禁止自己拼**。

#### 做法 1 —— 新增 `KernelScopeKey.Scope` 的面投影方法（新增，不改现有语义）

在 `KernelScopeKey.java` 内新增投影方法，**所有官方集成点一律调它们**：

```java
/** 官方 MessageBus.inboxPush/inboxDrain / SessionTurnGate.acquire 的 key —— 完整四维。 */
public String busKey() { return userId + ":" + sessionId; }   // = 既有 slotId()

/** 官方 TeamClient 的 namespace 面 —— fail-closed，绝不回退 "default"。 */
public String teamNamespace() { return userId; }             // 复合 userId，已含 p{projectId}:u{userId}

/** 官方 HarnessAgent.name —— 任务文件 agents/<name>/tasks/ 的目录维，须含 project。 */
public String projectAgentName() { return agentId; }          // 见下方修正说明
```

> **`busKey()` 必须用完整 `slotId()`，不能用 `sessionId()`。** 官方 `inboxPush` 的 sessionId 实为不透明串（见 §5.4 修正），喂四维 `slotId()` 即得四维隔离。
>
> `teamNamespace()` **不复用** `TeamContext.resolvedNamespace()`，避免 `""` → `"default"` 的 fail-open。
>
> `projectAgentName()` 若项目间 `agentId` 可能重复，需进一步改为 `p{projectId}:a{agentId}`；当前项目 `ProjectAgentConstants.AGENT_ID` 为常量，暂不重复，但**须在门禁中登记为待观察项**。

#### 做法 2 —— `SessionId` / `userId` 一律从 `Scope` 取，禁止裸传

**门禁（CI 可执行）**：新增一条静态检查，禁止在 `chat/kernel`、`ipd/agent`、`service/coding/harness` 三个包内出现 `RuntimeContext.builder().sessionId(<非 Scope 变量>)`。

落点：`scripts/check-kernel-scope-key.sh`（新增脚本，不改 `pom.xml`）：

```bash
#!/usr/bin/env bash
# 隔离键收口门禁：任何绕过 KernelScopeKey 的 RuntimeContext 构建 / 官方 key 调用即 FAIL
set -euo pipefail
cd /Users/mac/Documents/ruoyi-ai
PKGS="ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness"

# 1) 禁止裸 RuntimeContext.builder().sessionId/.userId
hits=$(grep -rnE 'RuntimeContext\.builder\(\)\s*\.?\s*\.?(sessionId|userId)\(' $PKGS \
       | grep -v 'toRuntimeContext' || true)
[ -z "$hits" ] || { echo "FAIL: 裸 RuntimeContext 构建，必须经 KernelScopeKey.Scope"; echo "$hits"; exit 1; }

# 2) 禁止官方 MessageBus/TeamClient 使用字面量 sessionId / namespace
for pat in 'inboxPush("' 'inboxDrain("' 'enqueueWakeup("' ; do
  h=$(grep -rnF "$pat" $PKGS || true)
  [ -z "$h" ] || { echo "FAIL: 官方 bus 调用带字面量 key"; echo "$h"; exit 1; }
done

# 2b) 禁止向官方 key 接口喂裸 sessionId（必须用 scope.slotId() / scope.busKey()）
h=$(grep -rnE 'inboxPush\(|inboxDrain\(|acquire\(' $PKGS | grep -vE 'slotId\(\)|busKey\(\)|TURN_GATE\.acquire' || true)
[ -z "$h" ] || { echo "FAIL: 官方 key 接口必须喂 scope.slotId()（含 project 维）"; echo "$h"; exit 1; }

# 3) KernelScopeKey 是唯一允许出现键前缀字面量的文件
stray=$(grep -rlE '"p" *\+ *projectId|"a" *\+ *agentId' $PKGS | grep -v 'KernelScopeKey.java' || true)
[ -z "$stray" ] || { echo "FAIL: 第二处手拼隔离键: $stray"; exit 1; }
echo "PASS"
```

#### 做法 3 —— `workspaceFor` 收进 `KernelScopeKey`

把 `AgentScopeChatKernel.workspaceFor`（`:361-364`）的三段拼装改成调 `KernelScopeKey` 新增的 `workspaceSegments()`。**推荐在 `Scope` record 里直接缓存原四段**，而不是从复合串二次解析——二次解析在 key 格式变更时会静默错位：

```java
public record Scope(String userId, String sessionId) {
    // 需在 of() 中一并填充：
    private final String projectSegment;   // "p{projectId}"
    private final String userSegment;      // "u{userId}" 或 "u__anon__"
    private final String agentSegment;     // "a{agentId}"

    /** 工作区路径段，供 AgentScopeChatKernel.workspaceFor 使用（与 Scope 同源）。 */
    public String[] workspaceSegments() {
        return new String[] { projectSegment, userSegment, agentSegment };
    }
}
```

#### 做法 4 —— `SubagentDeclaration` 侧固定

```java
.subagentFactory("ipd-worker", "IPD 有界分析 worker", (agentId) -> {
    HarnessAgent.Builder b = HarnessAgent.builder()
        .name(agentId)                    // 落在 agents/<agentId>/tasks/ 下，agentId 已被复合键约束
        .workspace(sharedWorkspace)       // **必须 SHARED，不开 ISOLATED**
        .inheritParentPermissions(true)   // 保留父的 DENY 规则
        .middleware(new OwnershipMiddleware(sink));   // §4.3：围栏随工厂复制
    return b.build();
})
```

**为什么强制 `WorkspaceMode.SHARED`**：`ISOLATED` 会让框架按「父 sessionId × 用户」自动开子目录并**自行拼路径**——那正是我们要消灭的「第二处手拼隔离键」。`SHARED` 下子 agent 直接用主工作区，路径由 `AgentScopeChatKernel.createWorkspace`（已 fail-closed）产出，唯一真相源成立。

#### 做法 5 —— 显式禁止 `expose_to_user`

项目已有前端 SSE 直连内核，不需要暴露子 agent。且 `StoreBackedSubagentRegistry.NAMESPACE` 实测是**固定的 `["subagents","exposed"]`，零隔离**。因此：

- 不装配 `GatewayBootstrap` / `HarnessGateway` / `ChannelManager`
- ~~在 `ToolsConfig` 里显式 `deny` 掉一切能触发 `expose_to_user` 的路径~~ **【2026-10-03 实测更正：此项保护已不存在】** 该 deny 原写作 `toolsConfig.setDeny(List.of("web_fetch","web_search","wait_async_results"))`，由 commit `8310853e` 引入，随后被 commit `b757fa7a`（commit message 仅为 "test"，无任何说明）删除。当前 `AgentScopeProjectAgentKernel.java:435` 是 `ToolsConfig toolsConfig = new ToolsConfig();` 空对象，从未调用 `setDeny`；本文原先引用的 260-261 行号亦已漂移。**依据本条做过的安全判断全部失效，需重做。**
- 若将来必须开启，需先给 `SubagentRecord.subagentId` 加 project 维前缀并在 `find()` 侧校验 `record.userId` 与当前 `RuntimeContext.userId` 一致——**这是新工作项，不在本次归位范围**

### 5.4 收口后的隔离保证

| 面 | 隔离强度 | 依据 |
|---|---|---|
| 状态（`AgentStateStore`） | 四维，fail-closed | `FailClosedAgentStateStore.save(user, session, key, value)` 两参均为不透明串；项目传 `scope.toRuntimeContext()` |
| turn gate | 四维 | **已实证**：`AgentScopeChatKernel.java:66` `TURN_GATE = new LocalSessionTurnGate()`，`:203/:262` 用 `scope.slotId()` 喂 key —— 官方 key 已在用且含 project |
| inbox 队列 | 四维（条件） | 官方 `inboxPush` 是纯字符串拼接 `"agentscope:inbox:" + sessionId`，**无任何格式假设与校验**；传 `scope.slotId()` 即四维 |
| 任务文件 | 三维 | `agents/<parentAgentId>/tasks/<sessionId>.json`；`parentAgentId` = `HarnessAgent.name`，需令其含 project |
| 团队命名空间 | 四维（条件） | `teamNamespace()` = 复合 userId；**不用** `resolvedNamespace()` 的 `"default"` 回退 |
| 暴露子 agent | 零隔离 | `["subagents","exposed"]` 固定 → 不启用 `expose_to_user` |

#### ⚠️ 初稿结论修正（2026-10-02 二次实证）

初稿曾判定「官方 inbox key 只有 sessionId 一个变量，无法注入 project → **必须改 `compositeSessionId` 格式 + 迁移存量 slotId**」。**该结论错误，现撤销。**

实测依据：

1. `MessageBus.inboxPush(String sessionId, Map<String,Object> msg)` 实现为 `queuePush("agentscope:inbox:" + sessionId, msg)`（`bus/MessageBus.java:156-159`）——**纯拼接，不校验、不解析、不假设格式**。`sessionId` 实为不透明字符串。
2. 同一模式**已在项目里成功用过**：`AgentScopeChatKernel.java:66` 已实例化官方 `LocalSessionTurnGate`，`:203`/`:262` 用 `TURN_GATE.acquire(scope.slotId())` 喂入四维 `slotId`。这是「官方 key 接口接复合串」的**既存正面先例**。
3. `AgentStateStore` 同样如此：`FailClosedAgentStateStore` 的 `save(user, session, key, value)` 两参皆不透明串，生产实现为 `RedisAgentStateStore`（`AgentScopeRedisStateStores.java:17`）。

**修正后的结论**：

- **不需要**变更 `compositeSessionId` 格式；
- **不需要**存量 `slotId` 迁移（无破坏性变更）；
- **不需要** owner 拍板（从 C 类降为 A 类「AI 自主」）；
- 做法统一为：**所有官方 key 接口一律喂 `scope.slotId()`（= `p{projectId}:u{userId}:a{agentId}:s{sessionId}`，含全部四维）**，禁止喂 `scope.sessionId()` 或裸 `sessionId`。
- 原 §6.2 步骤 0 的「阻塞项」降级为「R2 缓解措施」，归位可即刻启动。

**修正后仍需注意的两点**：

- `HarnessAgent.name` 会落进任务文件路径 `agents/<name>/tasks/`，若 name 不含 project，两个项目复用同一 `agentId` 时任务文件会碰撞。当前 `.name(agentId)` 需改为 `.name(scope.projectAgentName())`（新增投影方法）。
- `LocalSessionTurnGate` 是**进程内**的（`ConcurrentHashMap`），不跨副本。步骤 2 若接多副本，需换 `StoreBackedPeriodicGate` 或自研跨副本门。

---

## 6. 自研可删清单 + 落地步骤

### 6.1 可删清单（按条件触发，全部为**条件可删**）

| # | 文件 | 行数 | 删除条件 | 风险 |
|---|---|---|---|---|
| 1 | `service/coding/harness/loop/tool/HarnessDelegateTools.java` | 34 | 官方 `agent_spawn` 路径验收通过**且** `delegate_task` 无任何业务调用方 | 低——但需先 grep 确认无外部引用 |
| 2 | `service/coding/harness/modelruntime/HarnessAnalysisDelegate.java` | 107 | 同上 | 低 |
| 3 | `modelruntime/HarnessModelPolicy` 中的 `FLASH` 委派分支 | 少量 | 同上 | 低 |
| 4 | `loop/tool/HarnessToolBatchExecutor.java` 中「超时未返回」的旁路 | 部分 | 启用 `AsyncToolMiddleware` 后确认不再需要 | **中**——并发批执行还承担 fan-out 语义，不等于异步卸载 |
| 5 | `event/HarnessEventOutboxService` 的「后台任务完成通知」分支 | 部分 | 确认官方 `wireMessageBus` 的 inbox 投递可替代 | **高**——outbox 有事务保证，官方 inbox 是队列消费，**默认不删** |

**明确不可删清单**（官方无等价物，删了就是业务事故）：

- `recovery/UncertainToolEffectGuard` / `ToolEffectLedgerReconciler` / `ToolEffectLedgerFailureReason`
- `recovery/HarnessStartupRecovery` / `HarnessRecoveryReport`
- `store/HarnessStore` / `HarnessOptimisticLockException` / `HarnessMessageLedgerConflictException`
- `approval/` 全部 20 个类
- `runtime/HarnessSessionGate` / `HarnessActiveTurnRegistry`（`LocalSessionTurnGate` 不跨副本）
- `loop/DurableHarnessRunProcessor` 主体（5,649 行）
- `journal/` 全部
- `plan/` 全部

### 6.2 落地步骤

> **红线**：每一步**独立提交**；任一步验收不过就停在该步，不进下一步。全程不改 `pom.xml`（官方类已在 `io.agentscope:agentscope-harness` 传递依赖内，本次归位**不需要新增依赖**）。

---

#### 步骤 0 —— 前置：隔离键投影方法落地（**已由二次实证降级为 A 类，不再阻塞**）

- **初稿判定已撤销**：曾判「必须改 `compositeSessionId` 格式 + 迁移存量 slotId，阻塞整个归位」。二次实证证明官方 key 接口全部接受不透明字符串，且 `AgentScopeChatKernel:203/262` 已有既存正面先例（`TURN_GATE.acquire(scope.slotId())`）。详见 §5.4 修正。
- **文件**：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/KernelScopeKey.java`
  - `Scope` record 增加缓存字段 `projectSegment` / `userSegment` / `agentSegment`
  - 新增 `busKey()` / `teamNamespace()` / `projectAgentName()` / `workspaceSegments()`
  - **不改动** `of()` 的既有输出格式（无破坏性变更，无存量迁移）
- **同步修正**：`AgentScopeChatKernel.workspaceFor`（`:361-364`）改为调 `workspaceSegments()`，消除第二处手拼
- **验证**：
  1. `KernelScopeKeyTest` 补 4 例：`busKey()` 含 4 段、`workspaceSegments()` 三段与 `of()` 输入一致、非法段仍 fail-closed、`__anon__` 降级路径下 `busKey()` 仍含 project
  2. `mvn test -pl ruoyi-modules/ruoyi-chat -Dtest=*KernelScopeKey* -Pdev` 全绿
  3. **真 DB**：`AgentScopeRedisStateStores` 写入后 `redis-cli keys` 断言 key 前缀下无裸 sessionId 形态的 key
- **拍板位**：**A 类（AI 自主）** —— 无破坏性变更，步骤 1-3 完成后自动 sign-off

---

#### 步骤 0-bis —— 登记 `KernelScopeKey` javadoc 失实（**独立小项**）

- **问题**：`KernelScopeKey.java:8` 写「以实测 **MysqlAgentStateStore**.slotId = `userId + ":" + sessionId` 为准」、`:13` 写「**落库 slotId**」、`:37` 写「MysqlAgentStateStore 落库 slotId」。
- **实证**：全仓 `rg MysqlAgentStateStore` **无任何 Java 定义**——该类不存在。生产状态库实为 `AgentScopeRedisStateStores.create(RedissonClient, keyPrefix)` → 官方 `RedisAgentStateStore`，slotId 是 **Redis key 片段**而非 MySQL 列。
- **动作**：修正 javadoc 三处措辞（去掉 MySQL 指涉，改为「Redis 状态库寻址」）。**纯注释，不改行为**。
- **验证**：`rg -c MysqlAgentStateStore` 归零；`mvn -pl ruoyi-modules/ruoyi-chat -DskipTests package` 编译通过
- **为何单独列**：项目 CLAUDE.md 明确「业务规则高于文档惯例，文档惯例高于系统实现」；javadoc 失实会让下一个维护者去找一个不存在的类并做出错误迁移决策

---

#### 步骤 1 —— 打开官方 subagent（只声明，不接流量）

- **文件**：
  - `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeChatKernel.java:341-342` — 删 `.disableSubagents().disableDynamicSubagents()`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java:283-284` — 同上
  - `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness/loop/DurableHarnessRunProcessor.java:421` — 同上
  - `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/impl/CodingServiceImpl.java:120` — 同上
- **同时新增**：1 条 `SubagentDeclaration`（`name="general-purpose"` 默认已内置，若要自定义则显式声明）
- **验证**：
  1. 启动日志出现 `agent_spawn` / `agent_list` 工具注册（`SubagentsMiddleware.getTools()`）
  2. **真 HTTP**：`POST /chat/send` 带 sessionId → `GET /tool/list` 断言含 `agent_spawn`、`task_output`、`task_list`、`task_cancel`
  3. **真 DB**：断言未产生跨项目 inbox 记录（查 `agents/<agentId>/tasks/<sessionId>.json`）
  4. 单测：`mvn test -pl ruoyi-modules/ruoyi-chat -Dtest=*Kernel* -Pdev`

---

#### 步骤 2 —— 挂 MessageBus + PeriodicGate（跨副本正确性）

- **文件**：`AgentScopeChatKernel.java` / `AgentScopeProjectAgentKernel.java` 的 builder
- **新增**：
  - `.messageBus(new WorkspaceMessageBus(filesystem, busRoot))` — 触发自动注册 `InboxMiddleware`
  - `.asyncToolRegistry(new WorkspaceAsyncToolRegistry(filesystem, busRoot))`
  - 周期门：Spring `@Scheduled` 任务里用 `StoreBackedPeriodicGate.tryClaim("sweep:" + scope, Duration.ofMinutes(5))` 取代裸 `@Scheduled`
- **前置**：步骤 0 已完成（`KernelScopeKey.busKey()` 已就绪）；`messageBus` 装配处一律喂 `scope.busKey()`
- **验证**：
  1. **真 HTTP**：A 会话启动后台任务 → 同一 `agent_spawn` 完成后，B 会话（不同 project）**收不到**提醒（用 `curl` 轮询 SSE 断言）
  2. **真 DB**：查 inbox 目录，确认 key 段含 project 维
  3. **双实例**：`StoreBackedPeriodicGate` 在 2 个 Spring context 下 `tryClaim` 同一 name，断言**恰好 1 个 true**

---

#### 步骤 3 —— 装配 `OwnershipMiddleware` 到子 agent 工厂

- **文件**：`AgentScopeProjectAgentKernel.java` — 用 `subagentFactory(name, description, factory)` 替代纯 `subagent(SubagentDeclaration)`
- **动作**：工厂内给子 agent 挂**同一个** `OwnershipMiddleware(sink)` 实例 + `WorkspaceMode.SHARED`
- **验证**：
  1. **真实恢复链路**：在隔离验收环境启动 run，制造旧执行者失去租约，再由既有 `ProjectAgentRunRecovery` 领取执行版本，断言旧父/子执行者下一次工具调用被拒绝，且不产生工具副作用。必须使用当前恢复服务的事务、租约和围栏流程，不能以手工改列代替完整抢占。
     - 2026-10-02 源码勘误：`MybatisAgentRunStore.claimEpoch` 已在原 `ipd_agent_run` 行上通过 `version` 做 CAS 领取，`lockEpoch` 在事务内锁行并检查该版本。此前 `UPDATE ... SET epoch=epoch+1` 引用了不存在的列，现撤回；当前方案复用 `version`，不以本文授权新增 `epoch` 列或直接写库。此为源码事实，数据库及已加载运行包仍须独立回读。
  2. **真 DB**：断言抢占后 `ipd_agent_run_event` 无新增 `TOOL_RESULT` 行
  3. 单测：`ProjectAgentOwnershipMiddlewareTest` 补一个「子 agent 也被围栏覆盖」用例

---

#### 步骤 4 —— 替换 `delegate_task`（**可延后，先观测**）

- **文件**：删除 `HarnessDelegateTools.java` + `HarnessAnalysisDelegate.java`；`DefaultHarnessPromptAssembler` 中 `delegate_task` 提示词改为 `agent_spawn`
- **前置**：步骤 1-3 全绿 + 至少 1 周生产观测
- **验证**：
  1. **真 HTTP**：发一个需要并行分析的请求，断言父 agent 一轮内发起 ≥2 个 `agent_spawn`（`ToolCallStartEvent` 计数）
  2. **真 DB**：断言子 agent 产生独立 `subSessionId` 的会话记录，且**父的 run 状态机未被污染**
  3. 断言 `UncertainToolEffectGuard` 仍拦截（子 agent 工具写操作必须被账本捕获）

---

#### 步骤 5 —— AsyncToolMiddleware 评估（**默认不做**）

- **动作**：仅在步骤 2 观测到「长工具调用阻塞主循环」确有痛感时才评估
- **验证**：`asyncToolTimeout(Duration)` 下的工具超时卸载 → 结果经 inbox 回投；`WaitAsyncResultsTool(wait_all=true)` 的 barrier 语义不与自研审批 TTL 冲突
- **默认结论**：**不做**。自研 `HarnessToolBatchExecutor` 的并发 fan-out 语义与 AsyncToolMiddleware 的「超时卸载」语义不等价

---

#### 步骤 6 —— 隔离键门禁固化

- **新增**：`scripts/check-kernel-scope-key.sh`（§5.3 做法 2）
- **接入**：`.claude/settings.json` 的 `PreToolUse` Bash 钩子（本方案**只出脚本**，是否接入 hook 属 BP-013 范畴，需 owner 拍板）
- **验证**：
  - 正态：`bash scripts/check-kernel-scope-key.sh` → `PASS`，EXIT=0
  - **自证能红**：`SCOPEKEY_FAIL_SEED=1 bash scripts/check-kernel-scope-key.sh` → EXIT=1

---

### 6.3 风险登记

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | 删 `disableSubagents()` 后官方工具面意外展开，暴露 `web_fetch`/`web_search` | **中（实测已发生）** | 高（安全） | **~~现有 `ToolsConfig.setDeny` 已覆盖~~ —— 【2026-10-03 实测更正】该缓解措施已于 commit `b757fa7a` 被删除，**本项当前无 `ToolsConfig` 层缓解**。实际状态：`web_fetch` 仍可用（已真实调用成功），由 `ProjectAgentOfficialToolGovernance` 的 SSRF 出站防护部分兜底；`web_search` **既无 `ToolsConfig` deny 也无任何出站管控**，当前仅因缺 `TAVILY_API_KEY` 而处于不可用状态。一旦补上该密钥即等于开启一个由用户可控查询词驱动的无防护出站通道，**启用前必须先补同等出站管控**。 |
| R2 | 官方 `inboxPush` 只按 sessionId 分桶，两个项目同号会话串扰 | 中（原判高，已降级） | **高** | 官方 key 为不透明串，**喂 `scope.slotId()`（四维）即解决**；门禁 `check-kernel-scope-key.sh` 第 2b 条强制 |
| R2b | `HarnessAgent.name` 落进 `agents/<name>/tasks/` 路径，跨项目同 `agentId` 会碰撞 | 低 | 中 | `.name(scope.projectAgentName())`；当前 `ProjectAgentConstants.AGENT_ID` 为常量不重复，登记为待观察项 |
| R3 | 归位后 run 状态机与官方 `AgentStateStore` 双写状态，导致恢复逻辑分叉 | 中 | 高 | 步骤 1 只开 subagent，**不换 stateStore**；stateStore 保持 `FailClosedAgentStateStore` / `ProjectAgentTemporaryStateStore` |
| R4 | 子 agent 绕过 `OwnershipMiddleware` 围栏（父失权子仍跑） | 中 | 高 | 步骤 3 的 `subagentFactory` 强制 |
| R5 | 官方 `ISOLATED` 工作区自动拼路径，产生第二处隔离键 | 中 | 高 | 强制 `WorkspaceMode.SHARED` |
| R6 | `TeamContext.resolvedNamespace()` fail-open 到 `"default"` | 低 | 中 | **不启用 team**；若启用须传 `teamNamespace()` 并加门禁 |
| R7 | 步骤 4 删 `delegate_task` 后丢失「无工具只读 worker」这一强约束，LLM 开始用子 agent 做写操作 | 中 | 中 | 子 agent `tools` 白名单设空 + 保留 `UncertainToolEffectGuard` |
| R8 | 兄弟会话/子 agent 并发改同一 kernel 文件 | 高 | 中 | 每步一个 commit，`git add` 精确到文件；步骤 1 单独提交便于回滚 |

### 6.4 红线

1. **不改 `pom.xml`** — 官方类已在传递依赖内，本次归位零新增依赖
2. **不改 `recovery/` `approval/` `store/` `journal/` `plan/` 任何文件** — 官方无等价物
3. **不装配 `GatewayBootstrap` / `HarnessGateway` / `ChannelManager`** — `expose_to_user` 路径的 `StoreBackedSubagentRegistry` 是零隔离固定命名空间
4. **不启用 team** — `TeamClient` 的 namespace fail-open，且本项目无多 agent 团队业务
5. **不删 `disableFilesystemTools()` / `disableShellTool()`** — 与本任务无关
6. **步骤 0 未完成前，步骤 1 只允许在 dev profile 开启**（原为「未拍板」，因降级为 A 类自主，无需 owner 拍板）
7. **每步验收必须真 HTTP + 真 DB**，不接受 Mock 替代业务路径

---

## 附：实证方法与未实证项

**已实证**（逐字读过源码）：本文档 §1 全部「已实证」行、`IsolationScope` / `NamespaceFactory` / `MessageBus` / `StoreBackedSubagentRegistry` / `WorkspaceTaskRepository` 的 key 构造、`KernelScopeKey` / `AgentScopeChatKernel.workspaceFor` / `OwnershipMiddleware` / `HarnessDelegateTools` / `HarnessAnalysisDelegate` 全文、自研 18 个子包的行数统计、官方 17 个类的项目侧引用数（均为 0）。

**二次实证已闭合（原「未实证」项）**：

| 原未实证项 | 闭合结论 |
|---|---|
| `RemoteAskPolicy` 枚举体 | `DENY, PROPAGATE`（`subagent/RemoteAskPolicy.java` 全文 35 行）；`SubagentDeclaration.getRemoteAskPolicy()` 默认 `DENY` |
| `RemoteStreamDetail` 枚举体 | `STATUS, FULL, VERBOSE` + `wireValue()`（小写）/ `includes(required)`（按 ordinal 比较）/ `parse(value)`；**`SubagentDeclaration.getRemoteStreamDetail()` 默认 `FULL`**（显式 `!= null ? : FULL`），而 `parse(null)` 返回 `STATUS` —— **声明默认值与解析默认值不一致，是官方自身的双默认，归位时须显式指定** |
| `ToolkitConfig.parallel` 实际默认 | **已实证 = `true`**（`agentscope-core/.../tool/ToolkitConfig.java:127` `private boolean parallel = true;` + `defaultConfig()`）。与 `subagent.md` 声称的「Toolkit 默认启用工具并行」一致 |
| `agent_spawn` 超时边界 | **已实证**：`AgentSpawnTool.java:109-110` `DEFAULT_TIMEOUT_SECONDS=30` / `MAX_TIMEOUT_SECONDS=600`；`:1499` `null` → 默认、`:1502` `<=0` → 后台（除非 `CTX_FORCE_SYNC`）、`:1540` 走 `resolveTimeoutMs` |
| `timeout_promoted` | **已实证**：`SubagentsMiddleware.java:142`（中间件系统提示词内嵌）+ `AgentSpawnTool.java:1127`（工具返回值） |
| 远程子 agent 端到端 | 仍**未实证**（`AgentProtocolTaskClient` 等 ~764 行仅确认存在）。**归位不涉及**——项目不启用 `SubagentDeclaration.url()`，故不构成风险 |

**读不到 / 未深入的部分**：
- `SubagentsMiddleware.onReasoning`（736 行中约 250 行）内部逻辑未逐行分析
- `TeamsMiddleware.onAgent/onReasoning`（708 行）内部逻辑未逐行分析（本方案已判定不启用 team）
- `channel.md` 全文未读（Channel 侧判定「不归位」）
- `HarnessGateway`（847 行）内部逻辑未深入（判定不启用）

**本文档自身的两处已知缺陷**（自我登记）：
1. §5.3 做法 3 给出的 `Scope` 缓存字段代码片段与 record 的 `private final` 语法不自洽（record 需在 canonical constructor 中赋值），落地时以步骤 0 的实际改动为准。
2. §6.1 可删清单第 4、5 项（`HarnessToolBatchExecutor` 旁路、`HarnessEventOutboxService` 分支）**未逐行确认删除边界**，仅给出方向性判断。落地前需单独做一次调用图核对。

**全程无任何结论依赖文档推断**；所有「实测」项均为本地源码直读。
