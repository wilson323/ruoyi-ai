# langgraph4j 编排「重写」为 AgentScope harness 方案（2026-10-02）

> ⚠️ **【已失效，2026-10-02】** owner 当日拍板：ruoyi-aiflow 整模块 + ruoyi-workflow（warm-flow）一并下线，langchain4j/langgraph4j 活区字样清零——本文前提（把 langgraph4j StateGraph 重写为 AgentScope 编排）随之消失，W1~W5 不再执行。下线证据：`docs/script/sql/update/2026-10-02-workflow-modules-offline.sql` 与当日收口记录。正文按历史档案保留，不再代表现状。

> 性质：**docs-only 方案**，零代码改动。结论均基于实读本仓源码与 `/Users/mac/Documents/agentscope-java` 源码；读不到的一律标「未实证」。
> 触发：owner 2026-10-02 路线拍板（`log.md:13342`）。纪律：**未获计划批准不改代码**（ADR-0075 铁律 6）。本文不改 `src/`、不改 `pom.xml`、不改 SQL。
> 证据等级：**【已实证】**= 读过 `.java` 字节并给 file:line；**【仅文档】**= 只出现在 `docs/v2/zh/docs/harness/*.md`；**【未实证】**= 没读到字节。

---

## §0 命题修正：这不是「迁移」，是「重写」

**owner 拍板的前提（把 StateGraph「迁到 harness 编排」）在语义上不成立。** 本方案按「**重写图拓扑**」执行，并把这一修正写进 §9 需 owner 裁决的冲突清单。

### 0.1 决定性证据（本文作者独立复核，非转述）

```
$ rg "StateGraph|addEdge|addNode|addConditionalEdges|CompiledGraph" \
     agentscope-harness/src/main/java agentscope-core/src/main/java
零命中
```
**【已实证】harness 与 core 均无任何图原语**——没有节点、没有边、没有静态拓扑、没有 `CompiledGraph`。

```
HarnessAgent.java:169   public class HarnessAgent implements Agent, AutoCloseable
HarnessAgent.java:492   public ReActAgent getDelegate()
```
**【已实证】`HarnessAgent` 是 `ReActAgent` 的一层包装**，其编排能力只有两个来源：
1. **middleware 能力栈** —— 19 个 middleware 类（18 个 `implements HarnessRuntimeMiddleware` + 空标记接口 `HarnessRuntimeMiddleware` 本身；grep 命中 20 个文件是因为 `HarnessAgent.java` 自身也匹配）
2. **子 agent 委派** —— 运行时 `agent_spawn` / `AgentGenerateTool`

### 0.2 由此推出的三条硬约束

| # | 约束 | 后果 |
|---|---|---|
| 1 | **没有静态拓扑可"迁"** | 原 `WorkflowGraphBuilder` 编译出的 `StateGraph`（节点 + 边 + 条件边 + 并行子图）**必须逐条重新表达**为「middleware 链 + 子 agent 委派 + 自研状态机」三者的组合。见 §3 拓扑重写映射 |
| 2 | **执行顺序是隐式的** | **【已实证】** 19 个 middleware 中**没有任何一个覆盖 `order()`**（`rg "int order\(\)" --glob "*Middleware*.java"` → 零命中），全部 `order=1`，同 order 按 builder 注册顺序执行。重写后图的控制流**不再由图定义决定，而由注册顺序决定**——这是本方案最大的隐式风险，已登记为 R-1 |
| 3 | **两个扩展点空置，正好可用** | **【已实证】** `onModelCall` 与 `onAgentStateReady` 在 harness 内**零实现**（`rg "onModelCall|onAgentStateReady"` → 零命中）。自研 middleware 应优先用这两个"无人占用"的扩展点，把 `order()` 显式钉死 |

### 0.3 一句话形态

> **AgentScope harness 承接「模型面 + 流式面 + 工具面」；图拓扑、条件边、Fan-in、步骤级检查点、节点级事件协议、run 围栏，全部留在本仓自研状态机里。**

---

## §1 langgraph4j 依赖面完整清单

### 1.1 声明面

| 位置 | 内容 |
|---|---|
| `ruoyi-modules/ruoyi-aiflow/pom.xml:132-136` | `org.bsc.langgraph4j:langgraph4j-core:${langgraph4j.version}` |
| `pom.xml:59` | `<langgraph4j.version>1.8.20</langgraph4j.version>` |
| `pom.xml:16` | `<agentscope.version>2.0.3</agentscope.version>`（对照） |

依赖**只在 `ruoyi-aiflow` 一个模块**；`ruoyi-chat` / `ruoyi-ipd` / `ruoyi-common` 全部 0 引用。
（`.codex/` 与 `.harness/.backup/` 下的 worktree 副本属备份目录，**不计入依赖面**。）

### 1.2 10 个 Java 文件逐个列出

**主源码 7 个：**

| # | 文件 | 行数 | langgraph4j API | 职责 |
|---|---|---|---|---|
| 1 | `workflow/WorkflowEngine.java` | 447 | `StateGraph` / `CompiledGraph` / `CompileConfig` / `RunnableConfig` / `GraphInput`(args/resume) / `AsyncGenerator` / `NodeOutput` / `StateSnapshot` / `StreamingOutput` / `AgentState` / `StateGraph.END` | **引擎门面**。`run()` 装配图并 `app.stream()`；`exe()` 消费流；`resume()` 走 `GraphInput.resume()`；`runNode()` 是唯一节点执行体；`rebuildCompletedNodes()` 续跑时从 `t_workflow_runtime_node` 重建上游 |
| 2 | `workflow/WorkflowGraphBuilder.java` | 261 | `StateGraph`(addNode/addEdge/addConditionalEdges) / `ObjectStreamStateSerializer` / `edge_async` / `node_async` / `START` / `END` / `GraphStateException` | **图编译器**。`buildCompileNode()` 把 DB 边表转成 `CompileNode` 树 → `buildStateGraph()` 物化成 `StateGraph`；并行分支合成 `parallel_<rootId>` 子图 |
| 3 | `workflow/WfState.java` | 129 | `AsyncGenerator.WithResult` / `StreamingOutput<AgentState>`（`nodeToStreamingGenerator` 字段） | **实例状态**，自研容器 |
| 4 | `workflow/WfNodeState.java` | 52 | **`extends org.bsc.langgraph4j.state.AgentState`** | **节点状态**，继承 langgraph 状态基类 |
| 5 | `workflow/checkpoint/JdbcCheckpointSaver.java` | 140 | **`extends AbstractCheckpointSaver`**（4 个 protected 抽象方法）/ `Checkpoint` / `CheckpointSerializer` / `RunnableConfig` / `AgentState` | **自研落库 saver**（R31 成果），见 §4 |
| 6 | `entity/WorkflowCheckpoint.java` | 40 | javadoc 引用 `Checkpoint` 语义 | **checkpoint 表实体**（`t_workflow_checkpoint`） |
| 7 | `workflow/WorkflowUtil.java` | 219 | `AsyncGenerator` / `AsyncGeneratorQueue` / `StreamingOutput` / `AgentState` | **LLM 流式桥**。`streamingInvokeLLM()` 把 AgentScope 模型 token 灌进 langgraph4j 的 `AsyncGenerator` |

**测试 3 个**（`src/test/.../workflow/checkpoint/`）：`CheckpointStateSerializationRoundTripTest.java`(127) / `JdbcCheckpointSaverContractTest.java`(172) / `WorkflowCheckpointResumeIntegrationTest.java`(143)，共 442 行；同目录 `FakeCheckpointDb.java`(142) 为夹具，不 import langgraph4j，不计入 10。

**耦合判定**：生产 7 文件，但**仅 3 处硬耦合**——`WfNodeState extends AgentState`、`JdbcCheckpointSaver extends AbstractCheckpointSaver`、`WorkflowEngine` 持有 `CompiledGraph`。这三处就是重写工作量的下界。

### 1.3 节点族完整枚举

`workflow/WfComponentNameEnum.java`（11 值）+ `workflow/WfNodeFactory.java`（9 映射 + 1 显式抛异常）：

| 枚举 | `name` | 实现类 | 状态 |
|---|---|---|---|
| `START` | Start | `node/start/StartNode.java` | 已实现 |
| `END` | End | `node/EndNode.java` | 已实现 |
| `LLM_ANSWER` | Answer | `node/answer/LLMAnswerNode.java` | 已实现（唯一真正用 LLM 的节点） |
| `DALLE3` | Dalle3 | `node/image/ImageNode.java` | 已实现 |
| `TONGYI_WANX` | Tongyiwanx | `node/image/ImageNode.java` | 复用上者 |
| `KNOWLEDGE_RETRIEVER` | KnowledgeRetrieval | `node/knowledgeRetrieval/KnowledgeRetrievalNode.java` | 已实现 |
| `SWITCHER` | Switcher | `node/switcher/SwitcherNode.java` | 已实现（**条件边路由源**） |
| `GOOGLE_SEARCH` | Google | `node/googleSearch/GoogleSearchNode.java` | 已实现 |
| `MAIL_SEND` | MailSend | `node/mailSend/MailSendNode.java` | 已实现 |
| `HTTP_REQUEST` | HttpRequest | `node/httpRequest/HttpRequestNode.java` | 已实现 |
| `FAQ_EXTRACTOR` | FaqExtractor | **无** | `WfNodeFactory:49-53` 显式 `throw UnsupportedOperationException` |

支撑件：`AbstractWfNode`（`initInput` 解析上游输出→当前输入引用）、`NodeFailurePolicy`、`node/switcher/{SwitcherCase, SwitcherCaseEvaluator, LogicOperatorEnum, OperatorEnum, SwitcherEvaluationException}`、`node/enmus/NodeMessageTemplateEnum`。

### 1.4 StateGraph 拓扑实况（重写的事实基线）

**装配**：`WorkflowEngine.run():104-117`（`WorkflowStarter.asyncRun():98`）与 `WorkflowEngine.resume():307-318`（`WorkflowStarter.resumeRuntime():140`）。两路径**装配对称**，唯一差别是 `GraphInput.args(Map.of())` vs `GraphInput.resume()`。

**三种边**（`WorkflowGraphBuilder`）：

1. **普通边** —— `pointToParallelBranch()==false` → `stateGraph.addEdge(src, tgt)`
2. **条件边** —— `nextNodes.size() > 1` 且**全不是** `GraphCompileNode` → `addConditionalEdges(src, edge_async(s -> resolveNextRoute(s.data(), src)), mappings)`，`mappings` 恒等 target→target；路由键取 `state.data().get("next")`，由 `WorkflowEngine.runNode():194-196` 从 `NodeProcessResult.nextNodeUuid` 写入。**next 缺失抛 `IllegalStateException`**（D2 修复，防 NPE 覆盖上游真错误）
3. **并行分支子图** —— `pointToParallelBranch()==true`（出边 >1 **且** `sourceHandle` 为空）→ `getOrCreateGraphCompileNode(rootId)` 生成 `"parallel_" + rootId`，`buildStateGraph():121-131` 编译成**独立 `StateGraph` 子图**（内含 `START→root`、末节点 `→END`），再 `parent.addNode("parallel_xxx", subgraph.compile())`

```
START ──addEdge──▶ <开始节点 uuid>                      // :61
                         │
        ┌────────────────┼──────────────────┐
        ▼普通边           ▼普通边            ▼并行(sourceHandle 空 + 多出边)
     <节点A>           <节点B>          parallel_<rootId> ← 编译后的子 StateGraph
        │                │                   ├─ root ─▶ … ─▶ tail  ─▶ END(子图内)
        │                │                   └─ root2 ─▶ … ─▶ tail2 ─▶ END(子图内)
        ▼条件边 next     ▼条件边 next
   mappings{t1:t1,…}  mappings{…}
无后继节点 ──addEdge──▶ END               // :169
```

**已知表达能力上限**：`WorkflowEngine.errorWhenExe():149-151` 硬编码「**并行节点中不能包含条件分支**」——条件边与并行子图**互斥**。

**流式**：LLM 节点持 `WfState.nodeToStreamingGenerator[nodeUuid]`；`runNode():203-207` 塞进 state map 的 `_streaming_messages` 键；`exe()` 用 `app.stream()` 拿 `AsyncGenerator<NodeOutput<WfNodeState>>`，`StreamingOutput` 按 `chunk` 推 `[NODE_CHUNK_<uuid>]`，非流式 `NodeOutput` 回写 `t_workflow_runtime_node.output`。

---

## §2 官方可用能力盘点（重写的材料清单）

**【已实证】** `agentscope-harness` 是单 Maven 模块（`agentscope-harness/pom.xml:32`），包根 `io.agentscope.harness.agent`，280+ 个 `.java`，**无 gradle 工程**。

| 能力 | 签名/位置 | 对重写的用途 |
|---|---|---|
| 推理主循环 | `HarnessAgent.call(List<Msg>, RuntimeContext) :709` / `streamEvents(Msg, RuntimeContext) :894` | 模型面 |
| Builder 持久化面 | `.stateStore(AgentStateStore) :1644` / `.distributedStore(DistributedStore) :1662` / `.taskRepository(TaskRepository) :1975` / `.messageBus(MessageBus) :1985` / `.transcriptStore(TranscriptStore) :2259` | 落盘面 |
| 工具挂载 | `.toolkit(Toolkit) :1556` / `.toolsConfig(ToolsConfig) :1930` | B 类落地 |
| 子 agent | `.subagent(SubagentDeclaration) :1947` / `.subagentFactory(String, BiFunction<RuntimeContext,String,Agent>) :1966` / `.teamsMode(...) :1323` | B 类落地 |
| 预算 | `.maxIters(int) :1572` | A 类落地 |
| 门禁 | `.permissionContext(PermissionContextState) :1737` / `.stopOnReject() :1732` | A 类落地 |
| 中间件基类 | `io.agentscope.core.middleware.MiddlewareBase`（在 **core** 不在 harness）`ExtensionPoint{ON_AGENT, ON_REASONING, ON_ACTING, ON_MODEL_CALL, ON_SYSTEM_PROMPT, ON_AGENT_STATE_READY}` `:77-84`；`order() :121` 默认 1 | A 类落地 |
| MySQL stateStore | `MysqlAgentStateStore`（**已在本项目两处装配**：`AgentScopeChatKernel:109`） | 现成可复用 |
| JDBC 分布式 store | `JdbcDistributedStore.create(DataSource)` `jdbc/JdbcDistributedStore.java:84-87`，支持 MySQL/PG/H2/SQLite；表 `agentscope_store`（`AbstractJdbcDialect.java:77` 前缀 `agentscope_`） | 可选，**注意 `MysqlDistributedStore`/`PostgresDistributedStore` 已 `@Deprecated(since="2.1", forRemoval=true)`** |
| Plan Mode | `PlanModeMiddleware`（onSystemPrompt + onActing），9 个 `ALWAYS_ALLOWED` 白名单 | HITL（aiflow 现无，属新增） |

**【已实证】官方**不提供**（重写必须自建，见 §3 C 类）：
图原语 / 条件边 / Fan-in join / **步骤级 checkpoint** / 节点级流式事件协议 / 分布式 `SessionTurnGate` / 版本化 DDL 迁移（DDL 全是硬编码 `CREATE TABLE IF NOT EXISTS`）。

---

## §3 拓扑重写映射（A / B / C 三类归一）

对 §1.4 的真实拓扑逐项归类。**A = 表达为 middleware 钩子；B = 表达为 tool / subagent（模型自主决策）；C = 保留自研（官方无等价物）。**

### 3.1 节点映射

| 节点 | 类 | 归类 | 落地形态 |
|---|---|---|---|
| `StartNode` | 开始节点 | **A** | 入参闸 = 自研 middleware 的 `onAgent`（首个钩子）；`getAndCheckUserInput()` 的必填/类型校验逻辑搬进该 middleware，`A_WF_INPUT_MISSING` / `A_WF_INPUT_INVALID` 语义原样保留 |
| `LLMAnswerNode` | LLM 流式 | **A** | **不是 tool**，是 harness 主 agent 的推理轮本身。`WorkflowUtil.streamingInvokeLLM` 的 `AsyncGenerator` 桥删除，改 `HarnessAgent.streamEvents()`；prompt 由 `onSystemPrompt` 钩子从上游 IO 渲染（复用现有 `WorkflowUtil.renderTemplate`） |
| `GoogleSearchNode` | 联网搜索 | **B** | `ToolBase` 子类（现 `ZhipuWebSearchClient` 直接包一层）；注册进 `Toolkit` |
| `HttpRequestNode` | HTTP 调用 | **B** | `ToolBase` 子类。**必须带幂等键**（`side-effect-log-and-idempotency` 铁律：有副作用的工具调用要附幂等键 + 5 字段 side-effect 日志） |
| `MailSendNode` | 发邮件 | **B** | 同上，**有外部副作用，幂等键必需** |
| `KnowledgeRetrievalNode` | 知识检索 | **B** | `ToolBase` 子类 |
| `ImageNode`（Dalle3 / Tongyiwanx） | 出图 | **B** | `ToolBase` 子类（`buildTextToImage` 迁移） |
| `SwitcherNode` | 条件分支 | **C（推荐）** | ★ 见 §3.3 |
| `EndNode` | 结束节点 | **C** | 终态推进 + run 收口，官方无 |
| `FAQ_EXTRACTOR` | — | 无 | 后台未实现，**保持抛异常**，重写不涉及 |

### 3.2 边与控制流映射

| 图元素 | 归类 | 落地形态 |
|---|---|---|
| `START → 开始节点` | **A** | `onAgent` 钩子（每轮开头，harness 唯一「进入一轮」时机） |
| 普通边（线性推进） | **C** | **无对应物**。harness 无"下一步去哪"，只能由自研状态机推进 → C 类 `RunStateMachine` |
| **条件边** `addConditionalEdges` | **C（推荐）** | 见 §3.3 |
| **并行分支子图** `parallel_<rootId>` | **B（近似）** | `SubagentDeclaration` + `agent_spawn` 并行。但 ★ **语义不等价**：harness 是"多 agent 同时跑"，langgraph 是"一条图分叉成两条路径后各自走到底" |
| **Fan-in join**（等所有分支完成再继续） | **C** | ★ **官方完全无 join 原语**。必须自研 |
| 并行分支与条件边互斥约束 | **C** | 保留 `errorWhenExe()` 的原有报错 |
| 末节点 `→ END` | **C** | 终态 CAS |

### 3.3 ★ Switcher 的两种归类（必须显式裁决）

现状：`SwitcherNode` + `SwitcherCaseEvaluator` + `OperatorEnum` / `LogicOperatorEnum` = **确定性表达式求值**（字段 vs 常量比较 + 逻辑运算），产出 `NodeProcessResult.nextNodeUuid` → `state.data().put("next", …)` → `resolveNextRoute()` 消费。

| 方案 | 归类 | 优点 | 缺点 |
|---|---|---|---|
| **方案一（推荐）**：分支表编译进自研状态机 | **C** | 保持**确定性**——同一份 IO 必走同一分支，可测、可回归；前端画布的分支配置语义 1:1 保留 | 状态机要自己实现条件求值（≈ 复用现有 `SwitcherCaseEvaluator`，**几乎零新增**） |
| 方案二：让模型在工具集里自主选 | **B** | "更 agentic" | **破坏确定性**——同一输入可能走不同分支，画布配置形同虚设，且**无法做图语义等价回归**（W3 验证 1 会失效） |

**结论：取方案一（C 类）。** `SwitcherCaseEvaluator` 整段复用，只把"求值结果 → 下一跳"的 glue 换成自研状态机的转移函数。**不为了"更像 agent"而把确定性分支交给模型**——这正是 ADR-0075 成熟方案优先原则的反向误用。

### 3.4 C 类清单（官方无等价物，全部自研）

| C 类项 | 说明 | 归属文件（建议） |
|---|---|---|
| 图定义 → 可序列化执行计划的编译层 | 复刻 `buildCompileNode` + `buildStateGraph` 语义，**不依赖任何图框架** | `WorkflowGraphBuilder`（重写） |
| 运行状态机（普通边推进 + 条件跳转 + 并行分组） | harness 无"下一跳"概念 | `WorkflowRunStateMachine`（新建） |
| **Fan-in join** | 等并行分支全部完成 | 同上 |
| **步骤级 checkpoint / 断点续跑** | 见 §4 | `WorkflowCheckpointStore`（新建） |
| 节点级 SSE 4 类事件（`[NODE_RUN_/INPUT_/OUTPUT_/CHUNK_]`） | harness 的 `Msg`/`MsgContext` 抽象**表达不了节点级** | `WorkflowEngine` 流消费循环 |
| run 生命周期状态机（`READY/DOING/SUCCESS/FAIL`）+ **终态 CAS** | `t_workflow_runtime.status` 推进 | `WorkflowRuntimeService` |
| **run-epoch 围栏** | 防同一 run 被并发跑两次 | 新建 |
| **事件 seq + outbox** | 事件单调序号 + 事务性发件箱 | 新建 |
| 节点级留痕（`t_workflow_runtime_node`） | 业务语义检查点载体 | 已有，见 §4 |
| 审批 20 类 / `GateReviewService` 签署语义 | **C-5 红线：禁止重写第二套签署语义** | 不动 |

### 3.5 ★ 执行顺序隐式化（R-1，本方案最大风险）

**【已实证】** 19 个 middleware **无一覆盖 `order()`**（`rg "int order\(\)" --glob "*Middleware*.java"` → 零命中）→ 全默认 `order=1` → 同 order 按 **builder 注册顺序**执行。

**重写后这意味着什么**：
- 原 langgraph4j 图里，**节点顺序由 `WorkflowEdge` 表决定**（外部数据，可视、可改、可回归）
- 重写后，中间件的介入顺序**由 `HarnessAgent.builder()` 里的注册先后决定**（代码顺序，隐式）
- 两者一混，图的行为就**取决于 builder 调用里那几行的排列**——改一行无关代码可能悄悄改变执行顺序

**具体做法（强制）**：
1. **自研的每一个 middleware 都显式覆盖 `order()`**，并把取值写成**命名常量**（如 `ORDER_PRE_ROUTE = 10` / `ORDER_NODE_EXEC = 20` / `ORDER_POST_STATE = 90`），**禁止沿用默认 1**
2. 在 `HarnessAgent.Builder` 调用点加**静态断言式注释**声明顺序契约；并在装配面单测里断言 `middlewareClasses()` 的实际顺序
3. 优先占用**空置扩展点**：`onModelCall` 与 `onAgentStateReady` 在 harness 内**零实现**（`rg "onModelCall|onAgentStateReady"` → 零命中）。→ **路由决策放 `onModelCall`（每次模型调用前，天然在正确的位置）、状态落盘放 `onAgentStateReady`（每轮结束）**。这样既避开与现有 18 个 middleware 争抢 `order`，又语义贴切
4. `SandboxLifecycleMiddleware` 的 `activePoints()` 返回 `EnumSet.noneOf(ExtensionPoint.class)`（`:77-78`）——**它不参与任何扩展点**。⇒ 注册顺序对它是**完全无效**的，任何"靠顺序插队"的写法对它不成立

---

## §4 自研 `JdbcCheckpointSaver` 的归属（单列结论）

### 4.1 官方侧恢复能力的完整边界

**【已实证】官方无执行检查点 API。** 全仓 `checkpoint` 命中仅 `core/shutdown/*`（`ShutdownStateSaver` / `AgentScopeJvmShutdownHook` / `GracefulShutdownMiddleware`）——**那是 JVM 优雅停机钩子，不是可回放的执行检查点**。

官方全部恢复能力只有**三个**场景：

| # | 场景 | 机制 | 粒度 | 能否承接 aiflow 断点续跑 |
|---|---|---|---|---|
| ① | session transcript 分段 | `TranscriptStore`：`FilesystemTranscriptStore`（`CREATE_NEW`）/ `ObjectStoreTranscriptStore`（每次新 key），key = `{tenant}/{agentId}/{sessionId}/events/{seqStart}-{seqEnd}-{writerId}.jsonl` | **会话级**（对话事件流） | **否**。不可变分段解决的是"并发写覆盖"，**无 `nextNodeId` 概念**，无控制流位置 |
| ② | `TaskRecord` 后台任务状态 | `TaskRepository` → `agents/<parentAgentId>/tasks/<sessionId>.json` 一个 `taskId → TaskRecord` map；javadoc 自述 "authoritative truth source, survives JVM restarts" | **子 agent 任务级** | **否**。生命周期是"一个后台 task"，不是"图上第 N 步"。且 `WorkspaceTaskRepository` 孤儿判定（心跳 30s / 孤儿 10min / sweep 5min）**只把任务标 FAILED，不重跑** |
| ③ | 远程 SSE 续传游标 | `TaskRecord.lastEventSeq`（`:78`） | **传输级** | **否**。是对端流式事件游标。对端实现 **【未实证】** |

### 4.2 逐候选裁决

| 候选 | 官方实证 | 承接? | 裁决 |
|---|---|---|---|
| `DistributedStore` | 【已实证】聚合门面，`Builder.build()` 强制 `requireNonNull(agentStateStore)` + `requireNonNull(baseStore)`；`JdbcDistributedStore` 提供真 CAS | **否** | **键值门面**，无"栈 + nextNodeId + 序列化 state"语义 |
| `TaskRepository` / `TaskRecord` | 见上 ② | **否** | 粒度错配；孤儿只标 FAILED 不重跑 |
| `ObjectStoreTranscriptStore` | 见上 ① | **部分** | 只解决并发写覆盖，无控制流位置 |
| `AgentStateStore` | 【已实证】`save(userId, sessionId, key, State)`，寻址二元组；javadoc `:37-42` 逐字 "**Callers MUST NOT concatenate them manually**" | **是（仅承载）** | 可存"执行位置 + 已完成节点"的业务 State，但**它只是 KV，不是执行检查点机制** |
| **本仓 `t_workflow_runtime_node`** | 项目自研，**已在 resume 路径实证有效**（`WorkflowEngine.rebuildCompletedNodes():366-396`） | **是（推荐真相源）** | ★ 见 4.3 |

### 4.3 ★ 明确结论

> **结论 1：`JdbcCheckpointSaver` 在官方侧没有任何可归位物。** 它不是"暂时没找到对应类"，而是**官方根本不存在这个抽象**（无执行检查点 API）。因此它**不是"迁移"的产物，而是"重写"必须自建的机制的起点**。

> **结论 2：采用「自建 checkpoint 机制」，但复用已有业务载体，不新建序列化体系。**
> 把 `t_workflow_runtime_node` 升为**断点续跑的唯一真相源**（它已在 `rebuildCompletedNodes()` 路径实证有效、带租户上下文、是业务语义而非二进制），自研轻量 `WorkflowCheckpointStore` 取代 `AbstractCheckpointSaver` 继承：
> - 存什么：`run_id` / `node_id` / **`next_node_id`**（续跑起点，语义与现表一致）/ `epoch`（围栏）
> - 不存什么：**不再序列化整个 state**（`ObjectStream` 载荷是 R31 已登记的已知缺陷：`log.md:12678` ⑥「类演进/依赖升级后旧 checkpoint 反序列化会败」）
> - 上游上下文：`rebuildCompletedNodes()` 已经证明可从 `t_workflow_runtime_node` 重建，无需 checkpoint 携带

> **结论 3：`t_workflow_checkpoint` 表——保留但冻结写入，观察期后废弃。**
> - **不立即删**：W3 上线后需并行验证 `rebuildCompletedNodes` 在**含条件边 + 并行分支**的图上保真（这正是 R31 遗留第 3 项「复杂图 resume 节点 IO 重建保真度**待生产验证**」）
> - **废弃条件**（三条全绿）：①W3 复杂图续跑真 DB 验收通过 ②W3 上线满一个迭代 ③`grep -rn "AbstractCheckpointSaver\|JdbcCheckpointSaver" src/main` 零命中
> - **执行顺序**：`RENAME TO t_workflow_checkpoint_bak_2026xxxx` → 观察一个迭代 → `DROP`（DDL 原文件已给幂等 DROP+CREATE 与回滚语句，**需 DBA 窗口**）
> - **同步清理**：`application.yml:316-319` 的 `tenant.excludes` 注释与条目

> **结论 4：禁止新建第二个 checkpoint 表。** 自建机制应复用 `t_workflow_runtime` 加列 + `t_workflow_runtime_node`。新建 `t_aiflow_*_checkpoint` 即 ADR-0075 D6「状态双写混淆」。

> **结论 5：与官方三场景的边界（写清楚，避免日后误接）**
> - `TranscriptStore`（①）归 **W4 流式面**——用来落"对话/事件"的可审计流，**不是**断点续跑
> - `TaskRepository`（②）若日后接子 agent（**本轮明确不接**），其 `TaskRecord` 归子 agent 生命周期，**与 run 断点是两套东西，不得互相引用**
> - `lastEventSeq`（③）属远程传输，aiflow 单体部署**不涉及**

---

## §5 能否下线 langgraph4j

### 5.1 完全下线条件清单

| # | 条件 | 判据 | 阻塞? |
|---|---|---|---|
| 1 | W2 完成 | `WorkflowUtil.streamingInvokeLLM` 删除，`org.bsc.async.*` 零引用 | 否 |
| 2 | **W3 完成** | `WorkflowEngine` 不再持有 `CompiledGraph`/`StateGraph`；`WorkflowGraphBuilder` 自研；自研状态机含 Fan-in | **是（最大）** |
| 3 | 节点状态解耦 | `WfNodeState` **不再 `extends AgentState`** | **是** |
| 4 | saver 退役 | `JdbcCheckpointSaver.java` 删除；`AbstractCheckpointSaver`/`Checkpoint`/`CheckpointSerializer` 零引用 | **是** |
| 5 | 实体退役 | `entity/WorkflowCheckpoint.java` 删除 | 是 |
| 6 | 测试重写 | `src/test/.../checkpoint/` 3 测试（442 行）按新机制重写 | 是 |
| 7 | **pom** | 删 `ruoyi-aiflow/pom.xml:132-136` 依赖块；删 `pom.xml:59` property | 是 |
| 8 | SQL | `t_workflow_checkpoint` 按 §4.3 结论 3 退役；`application.yml:316-319` 清理 | 是 |
| 9 | 历史 run 兼容 | 切换瞬间在跑的 `status=2` 实例用旧引擎续完或人工置 4（`failZombieDoingRuntimes`）；**需切换窗口** | 是 |
| 10 | 依赖补齐 | 若接 `DistributedStore` 需 `agentscope-extensions-jdbc`（**勿用已 Deprecated 的 `-mysql`/`-postgresql`**） | 视选型 |

### 5.2 判定

**能完全下线，但 W3 自研状态机是唯一必经之路，且工作量远大于"迁到 harness"这个说法暗示的规模。**

**最小残留（不做 W3）**：`WorkflowEngine` 仍需 `CompiledGraph`+`StateGraph`+`RunnableConfig`+`GraphInput`+`AsyncGenerator`+`NodeOutput`+`StateSnapshot`（约 40 行）；`WorkflowGraphBuilder` 整个 261 行都围绕 `StateGraph` API；`WfNodeState` 仍 `extends AgentState`；`JdbcCheckpointSaver` 仍 `extends AbstractCheckpointSaver`。→ **不做 W3，则 1 个 pom + 7 文件全留，重写等于没发生。**

### 5.3 最终形态

```
ruoyi-aiflow
├── workflow/                     ← 自研状态机（重写）
│   ├── WorkflowGraphBuilder      编译层（不依赖任何图框架）
│   ├── WorkflowRunStateMachine   推进 + 条件跳转 + Fan-in + 并行分组  【新建】
│   ├── WorkflowCheckpointStore   自建断点（复用 t_workflow_runtime_node）  【新建】
│   ├── WorkflowEngine            重写 run/exe/resume
│   ├── WfState / WfNodeState     解耦 AgentState
│   ├── middleware/               自研 A 类，order() 显式钉死           【新建】
│   │   ├── RouteDecisionMiddleware      onModelCall    （空置扩展点）
│   │   ├── RunStatePersistMiddleware    onAgentStateReady（空置扩展点）
│   │   └── NodeLifecycleMiddleware      onAgent
│   └── WfNodeFactory + 9 类节点（C 类节点不动；B 类节点改 ToolBase）
└── tools/                        ← B 类落地（Google/Http/Mail/Knowledge/Image）  【新建】
```

依赖净变化：`langgraph4j-core` **删除**（净 -1 外部编排框架），`agentscope-harness` 覆盖面 +1。

---

## §6 隔离键与租户约束

### 6.1 既有收口规则（不新造）

- 铁律（`.claude/skills/agentscope-harness/SKILL.md:53`）：**「四维隔离键唯一收口：project × user × agent × session 一律经 `KernelScopeKey.of(...)`，业务代码禁止手拼；段内含 `':'` 或 `..` 必须 fail-closed 拒绝」**
- 收口文件（唯一）：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/KernelScopeKey.java`
  - 复合 userId = `p{projectId}:u{userId}`（空 → `__anon__`）
  - 复合 sessionId = `a{agentId}:s{sessionId}`
  - slotId = `p:P:u:U:a:A:s:S`
  - `validateSegment()` 已 fail-closed 拒 `':'` 与 `..`
- 门禁：`.claude/skills/agentscope-harness/scripts/harness-contract-check.sh:135-171` C2/C3，按**所属工作树**分组判"每工作树内 ≤1 处收口"

### 6.2 ★ 发现的既有第二处手拼隔离键

**`AgentScopeChatKernel.workspaceFor()`（`:367-371`）+ `workspaceSegment()`（`:398+`）是隔离键的第二处构造点。**

```java
static Path workspaceFor(Path root, String projectId, String userId, String agentId) {
    return root.resolve(workspaceSegment("projectId", projectId))
            .resolve(workspaceSegment("userId", userId))
            .resolve(workspaceSegment("agentId", agentId));
}
```

javadoc 自称「与 `KernelScopeKey` 同源」，但它**确实是独立手拼**（独立路径分段、独立 fail-closed 规则、比 `KernelScopeKey` 多拒 `/` `\` `.`）。关联记录：`生产就绪C路-盘点候选清单与差距矩阵-20260929.md:54,58`「`KernelScopeKey` 四维收口只覆盖 stateStore 键，**文件面漏 project/user 维**」。

**对重写的影响**：`workspaceFor` 是 **package-private static**，aiflow（不同包）**根本调不到**。重写一旦落地，aiflow 必然要新写一份路径拼接——**那就是 ADR-0075 D1「再造第二套」+ C2 门禁要拦的形态**。

**具体做法（三条，缺一不可）**：
1. 把 `workspaceFor`/`workspaceSegment` **上提进 `KernelScopeKey`**（如 `Scope.workspacePath(Path root)`），`AgentScopeChatKernel.workspaceFor` 保留为**薄转发**以免破坏既有测试 `AgentScopeChatKernelWorkspaceIsolationTest`。**物理文件仍是唯一那一个**，C2 按文件计数不受影响
2. aiflow 侧**禁止**出现任何 `root.resolve(...)` 分桶。门禁 C2 扫描范围（"只看引用 `io.agentscope` 或 `RuntimeContext` 的 `.java`"）**保持原样**——`WorkflowUtil` 已 import `io.agentscope.core.message.Msg`，aiflow **天然落在范围内**，无需改宽（改宽是 OI-019 红线）
3. **官方会拼命名空间的三处，一律关掉，不许参与隔离**：
   - `HarnessAgentBuilderSupport.deriveChildSessionId`【已实证 `:678-690`】→ 必须 `.disableSubagents()`
   - `IsolationScope.toNamespaceFactory()`【已实证 `:102-126`】→ 只用于 remote filesystem 命名空间，不接即可不触发
   - `WorkspaceManager` 路径 `agents/{agentId}/sessions/{sessionId}`【已实证 `:337/411/620`】→ ★ **最危险**：它**绕过** `RuntimeContext` 的复合 userId，只用 agentId + sessionId。**对策**：重写方案**显式 `.disableFilesystemTools()` + 不配 `workspace(...)`**（工作区不承载隔离），或接受"工作区纯 scratch"并在 javadoc 写明

### 6.3 ★ 正面回答：`application.yml:320` 既存缺陷的解决

**现状缺陷**（`application.yml:316-319` 原文）：checkpoint 落库表租户共享，「**断点恢复场景无租户上下文，且 langgraph4j AbstractCheckpointSaver 扩展点 API 只有 RunnableConfig 拿不到租户**；不登记则租户拦截器追加 `WHERE tenant_id=?` 表现为「查不到 checkpoint」断点续跑全断」。

**根因（实读确认）**：`JdbcCheckpointSaver` 的回调签名是 `loadCheckpoints(RunnableConfig)` / `insertedCheckpoint(RunnableConfig, …)`——**扩展点契约里就没有租户参数**。这不是本仓写错，是框架决定。

**重写后的解法（三层）**：

**层 1（框架层，根治）**：`AgentStateStore` 寻址是 `(userId, sessionId)` 二元，**userId 非空、可带项目维**。只要强制经 `KernelScopeKey.of(projectId, userId, agentId, sessionId)`，**租户/项目维就进了 userId 段**，落库键天然分桶。官方 javadoc【已实证 `AgentStateStore.java:37-42`】还明确 "**Callers MUST NOT concatenate them manually**"——正是 `KernelScopeKey` 的角色。**这一层让 `t_workflow_checkpoint` 继续 tenant.excludes 的必要性消失。**

**层 2（本仓层，兜底）**：`KernelScopeKey.validateSegment()` 已 fail-closed 拒 `':'` 与 `..`，但**目前不拒 `/` `\`**。若复合段可能参与路径拼接，需**扩展 `validateSegment` 拒掉 `/` 与 `\`**（与 `AgentScopeChatKernel.workspaceSegment` 现有规则对齐）。属修改收口文件本身，允许，但需在 W1 评估（会动既有测试通过域）。

**层 3（切换层，务实）**：`t_workflow_runtime` 实体**不映射 tenant_id**（实读：`uuid/userId/workflowId/input/output/status/statusRemark`；`BaseEntity` 只有 `id/createTime/updateTime/isDeleted`，**无 tenantId 字段**）。但 `WorkflowStarter.asyncRun():82` 签名**已收 `tenantId`** 并在 `@Async` 线程 `TenantHelper.setDynamic(tenantId)`——**租户在引擎边界可得，只是没进 checkpoint 的键**。
→ **具体动作**：把续跑键从「裸 `runtime.uuid`」改成 `KernelScopeKey.of(projectId/tenantId, userId, agentId, runtimeUuid)` 的复合 sessionId，**在 `WorkflowEngine.run()` 入口构造一次**——与框架扩展点无关，**根因随依赖摘除而消失**。
→ **前置未实证**：`t_workflow_runtime` 实体无 project/tenant 维，aiflow 侧**目前没有持久化来源**（`WorkflowStarter` 只在内存传）。W3 需**新增一列**（如 `scope_key varchar(128)`）。**需 DDL + DBA 窗口 + owner 拍板。**

**残留风险（显式承认）**：`WorkflowEngine.resume()` 的调用方是**运维/定时线程**，**天然无请求上下文**。层 1 解决"键里有没有租户维"，解决不了"运维线程怎么知道租户是谁"——需 §4.3 结论 2 的**新列持久化**。**在新列落地前，`tenant.excludes` 的 `t_workflow_checkpoint` 登记不得删除。**

---

## §7 落地步骤

每波独立可验证可回退。验收一律**真 HTTP + 真 DB**，Mock 不得顶替业务链路。

### W1 · 门禁与文档（0.5 天）
- **文件**：`docs/ipd-系统说明/ADR/ADR-0077-langgraph4j编排重写为AgentScope-20261002.md`（新建）、`scripts/check-langgraph4j-ratchet.sh`（新建）、`scripts/baselines/langgraph4j-count.json`（新建，基线 10）
- **验证**：`bash scripts/check-langgraph4j-ratchet.sh` EXIT=0；`LANGGRAPH_FAIL_SEED=1 …` EXIT=1（**自证能红**）；`bash .claude/skills/agentscope-harness/scripts/verify.sh` EXIT=0 且 `--self-red` EXIT=0

### W2 · LLMAnswerNode 切 harness（3 天）
- **文件**：`node/answer/LLMAnswerNode.java`、`WorkflowUtil.java`（留 `renderTemplate`/`buildTextToImage`，删 `streamingInvokeLLM`）、`WfState.java`（`nodeToStreamingGenerator` 降级为 harness 事件缓冲）
- **验证**：①`mvn -pl ruoyi-modules/ruoyi-aiflow test`（注意 CLAUDE.md 的 surefire `@Tag("dev")` 过滤坑）②**真 HTTP**：真服务 + token，触发含 Answer 节点的图，`curl -N` 收 SSE，断言 `[NODE_CHUNK_x]` ≥2 条且拼接等于答案 ③**真 DB**：`t_workflow_runtime_node.output` 回读非空且等于最终答案 ④隔离回归：同图两个 `projectId` 并发，互不串
- **门禁**：`mvn -q -pl ruoyi-modules/ruoyi-aiflow clean package` BUILD SUCCESS

### W3 · 自研状态机（8–12 天，最大波）
- **文件**：`WorkflowGraphBuilder.java`（重写）、`WorkflowEngine.java`（重写 `run`/`exe`/`resume`）、`WfNodeState.java`（解耦 `AgentState`）、**新建** `WorkflowRunStateMachine` / `WorkflowCheckpointStore` / `middleware/{RouteDecisionMiddleware, RunStatePersistMiddleware, NodeLifecycleMiddleware}`、`entity/WorkflowRuntime.java`（加 scope 维列）、`docs/script/sql/update/2026-10-xx-*.sql`（**需 DBA 窗口**）
- **验证**：
  1. **图语义等价**（"图没走样"的唯一硬判据）：同一份 DB 边表数据，旧 `WorkflowGraphBuilder`（保留为测试夹具）与新编译器产出的执行计划，断言**节点集合 + 分支映射 + 并行分组完全一致**
  2. **middleware 顺序契约**：装配面单测断言自研 middleware 的 `order()` 序列 == 命名常量序列（防 R-1 隐式化）
  3. **真 HTTP + 真 DB 断点续跑**（**最关键**）：造含「条件边 + 并行分支 + LLM」图 → `kill -9` → 走自研 `resumeRuntime()` → 断言 ①从 `next_node_id` 续跑 ②`t_workflow_runtime_node` 无重复无缺失 ③最终 output 与不中断跑一致
  4. Fan-in 正确性：并行分支两路都完成后才继续
  5. 原有「并行节点中不能包含条件分支」报错仍生效
  6. 真 DB 切租户：两个 `scope_key` 并发，`t_workflow_runtime_node` 互不可见
- **门禁**：`scripts/check-*.sh` 5 项全绿 + 各自 FAIL_SEED EXIT=1

### W4 · SSE 事件契约对齐（1 天）
- **文件**：`WorkflowEngine.java` 流消费循环
- **验证**：真 HTTP 回归 `[NODE_RUN_/INPUT_/OUTPUT_/CHUNK_]` **4 类事件名与顺序逐条不变**（前端在独立仓 `ruoyi-web`，破坏契约无法热修）

### W5 · 依赖下线与 checkpoint 退役（2 天 + 观察期）
- **文件**：删 `JdbcCheckpointSaver.java`、`entity/WorkflowCheckpoint.java`、`src/test/.../checkpoint/` 3 测试；改 `ruoyi-aiflow/pom.xml:132-136`（**删依赖块**）、`pom.xml:59`（**删 property**）
- **验证**：①`grep -rn "org.bsc.langgraph4j" --include="*.java" ruoyi-modules ruoyi-common ruoyi-admin` → **零命中** ②`grep -rn "langgraph4j" pom.xml ruoyi-aiflow/pom.xml` → **零命中** ③`mvn clean package -DskipTests` BUILD SUCCESS ④`check-langgraph4j-ratchet.sh` 基线 = 0
- **表退役（不在本波执行）**：按 §4.3 结论 3，三条件全绿后 `RENAME` → 观察 → `DROP`

---

## §8 风险与红线

### 8.1 已实证的四个坑（本文作者独立复核）

| # | 坑 | 实证 | 重写方案的对策 |
|---|---|---|---|
| **K-1** | **`BaseStore.putIfVersion` 默认实现 `return false`** | 【已实证】`filesystem/remote/store/BaseStore.java:74-77`。已确认**只有** `InMemoryStore` / `JdbcStore` / `RedisStore` 三个后端覆盖 | 任何自定义后端**必须显式覆盖** `putIfVersion`，否则 `StoreBackedPeriodicGate` / `BaseStoreSkillUsageBackend` / `LocalTeamClient` 的 CAS **静默失效——无异常、无日志**。对策：自研 store 的单测里**强制断言** `putIfVersion` 在版本不匹配时返回 `false`、匹配时返回 `true`（证明覆盖生效，而非落进默认实现） |
| **K-2** | **namespace 分隔符两后端不一致** | 【已实证】`InMemoryStore.java:110-120` 用 ASCII **NUL（`\0`）**；`JdbcStore.java:215-230` 用 **U+001F**（且显式拒含 0x1F 的段） | 混用后端 key **不可移植**。对策：aiflow 若跨内存/DB 后端（如测试用 InMemory、生产用 Jdbc），**测试通过不代表生产可用**。验收必须**固定在 Jdbc 后端上跑真 DB 用例** |
| **K-3** | **子 agent sessionId 两套并存** | 【已实证】`HarnessAgentBuilderSupport.deriveChildSessionId`（`:678-690`）产出 `{name}[@{parentSessionId}][#{userId}]`；`AgentSpawnTool.java:369-373` 产出 `sub-{hash(parentSessionId,agentId,label)}`。**【仅文档】** `subagent.md:214` **只描述了后者** | 重写图若接子 agent（**本轮明确不接**），**必须显式指定用哪一套**，否则同一子 agent 会有两个不相通的 session 桶。**且官方文档只覆盖了其中一套**——依赖文档会踩坑。§3.2 的并行分支若采纳 B 类近似，**必须先裁决这一项** |
| **K-4** | **`SessionTurnGate` 只有 `LocalSessionTurnGate` 一个实现** | 【已实证】`ConcurrentHashMap<String,Semaphore>` 公平锁，**进程内**；接口 javadoc 提到的分布式实现在 harness 内不存在 | ★ **本仓已存在该竞态**（实读复核）：`AgentScopeChatKernel.java:68` `private static final SessionTurnGate TURN_GATE = new LocalSessionTurnGate();`，用于 `:205` 与 `:264`——**JVM 级 static，双副本不互斥**。重写方案**必须正面处理**：①要么部署形态确认为单副本并写入部署文档 ②要么自研 store-backed `SessionTurnGate` 注入 `.distributedStore(...)`（但 `DistributedStore.sessionTurnGate()` **默认返回 `null`** `:165`，须显式提供）③run-epoch 围栏（§3.4 C 类）作为**幂等兜底**，不依赖 gate |

### 8.2 风险登记

| 级别 | 风险 | 缓解 |
|---|---|---|
| **P0** | **执行顺序隐式化**（R-1）。**【已实证】** 19 个 middleware 无一覆盖 `order()`，全默认 1 按注册序执行；改一行 builder 可能悄悄改变图行为 | §3.5 四条：自研 middleware **全部显式覆盖 `order()`** + 命名常量 + 装配面顺序断言 + 优先用空置扩展点 `onModelCall`/`onAgentStateReady` |
| **P0** | **误把 harness 当图引擎**。无 StateGraph / 无条件边 / 无 checkpoint（§0 已实证） | §0 前置结论写进 ADR-0077；W3 明确**自研而非 harness** |
| **P0** | **隔离键第二处**（§6.2）。aiflow 必然要 workspace，撞上 `workspaceFor` 且它是 package-private | §6.2 三条：上提进 `KernelScopeKey` + 禁 `root.resolve` 分桶 + 关官方三处命名空间 |
| **P0** | **租户上下文**。新机制的租户维无持久化来源（`t_workflow_runtime` 无该列） | §6.3 层 3；需 DDL + owner 拍板；**在此之前不得删 `tenant.excludes` 的 `t_workflow_checkpoint`** |
| **P1** | **图走样**。重写 261 行图语义，条件边/并行子图可能不等价 | W3 验证 1「同边表 → 两套编译器 → 断言等价」 |
| **P1** | **复杂图续跑保真度**。R31 遗留「复杂图 resume 节点 IO 重建保真度**待生产验证**」 | W3 验证 3 专造复杂图；**不过则 W5 不得开工** |
| **P1** | **Fan-in 无官方原语**（§3.4）。官方完全没有 join 语义 | 自研状态机实现；W3 验证 4 专测 |
| **P2** | **SSE 契约破坏**打到独立仓前端 | W4 逐条不变断言 |
| **P2** | **官方扩展已 Deprecated**。`MysqlDistributedStore`/`PostgresDistributedStore` 均 `@Deprecated(forRemoval=true)` | 若接 store 须用 `agentscope-extensions-jdbc` |
| **P2** | **官方能力误信**。K-1/K-2/K-4 均为「看起来能用、实际静默降级」 | §8.1 逐条对策 + 选型时逐个确认覆盖实现，不看接口默认实现 |

### 8.3 红线（承接 ADR-0075）

1. **禁双轨** —— 任一时刻生产入口只有一个内核在跑。W2/W3 切换必须**同批**落地，不留"两边都能跑"的中间态
2. **禁范围外自研** —— W3 自研**只允许**实现「图编译 + 状态机 + Fan-in + 断点」四件事，**禁止**顺手长出第二套工具白名单 / Context 压缩 / 观测事件（D1/D3/D4）
3. **门禁不得改宽** —— C2 扫描范围保持原样（aiflow 已在范围内）。改判定范围/阈值/退出码 = **OI-019 红线**，需 owner 二次签字
4. **棘轮只减不增** —— langgraph4j 棘轮基线 10，每波只减不增
5. **单波单变更面** —— gate 失败即 `git revert` 该波 commit
6. **签署语义不重写**（C-5）—— `GateReviewService` 保留为节点执行层，**禁止重写第二套签署语义**。本方案不触碰 `ruoyi-ipd`，天然不涉及
7. **不引入第四套引擎** —— 禁 Temporal / Flowable（`三套工作流引擎定位裁定与Guard链路归一:14`）
8. **成熟方案优先** —— W3 自研属"官方确实缺失"才可（ADR-0075 红线 4 唯一例外），**必须**在 ADR-0077 逐条举证"harness 无此能力"（本文 §0/§2/§3 已给源码实证）
9. **未获计划批准不改代码** —— 本轮仅 docs；W2 起每波开工前需 owner 签字

### 8.4 明确不做（防范围蔓延）

`HarnessGateway` / `ChannelRouter` / `TeamClient` / `MessageBus` / **subagent** / skill / sandbox / compaction / plan-mode —— 与"重写编排图"无直接关系，**本轮一条都不接**。接了即为在 langgraph4j 旁边长第二套编排面（铁律 1 反面）。`AgentScopeChatKernel` 现有装配已显式 `disableSubagents() / disableDynamicSubagents() / disableDynamicSkills() / disableDefaultWorkspaceSkills()`，本方案**沿用同一姿态**。

---

## §9 需 owner 裁决的冲突

| 冲突 | A 方 | B 方 | 建议 |
|---|---|---|---|
| **langgraph4j 去留** | `ADR-0075:164`「**LangGraph4j 工作流编排本身不属 agent 内核，保留。**」/ `:318`「LangGraph4j 编排本身保留」/ `开发计划-看板镜像.md:5792`「**不把LangGraph4j编排本身误当废代码**」 | owner 2026-10-02「迁到 harness 编排」（`log.md:13342`） | **正面冲突**。ADR-0075 状态 `partially-approved` 未升 `approved`。本方案默认 owner 新拍板优先，但**必须**由 ADR-0077 显式改写三处"保留"，否则仓库里两句话打架 |
| **「迁移」还是「重写」** | owner 措辞为"迁到 harness 编排" | §0 已实证 harness **无任何图原语**，语义上不能"迁" | 建议 ADR-0077 标题与结论改用「**重写**」，并在 §0 附本文的 `rg` 零命中证据 |
| **ruoyi-aiflow 定位** | `三套工作流引擎定位裁定与Guard链路归一-20260927.md:11`「限定**短周期 AI 管道**、不扩容承载 90 日级生命周期」 | 本方案让它自研状态机承载编排 | **不冲突但需重述**：定位不变，只是编排底座换了 |
| **t_workflow_runtime 新增 scope 列** | 需 DDL + DBA 窗口 | §6.3 层 3 依赖它 | **需 owner 拍板**；未拍板前 W3 不得开工 |

---

## 附：本文「未实证」清单

| # | 未实证项 | 需谁去查 |
|---|---|---|
| 1 | `t_workflow_runtime` 真库实际列（是否有 tenant_id / project_id）——实体未映射 ≠ 表里没有 | W3 实施时 `SHOW CREATE TABLE` |
| 2 | `t_workflow_checkpoint` 实际存量行数与最老 `create_time`（决定观察期长度） | DBA |
| 3 | `agentscope-extensions-jdbc` 与本仓多数据源（`stateDatabase` 独立库）的兼容性——`AgentScopeChatKernel:109` 用的是 **stateStore 而非 DistributedStore** | W5 条件 10 |
| 4 | 远程 SSE 续传（`lastEventSeq`）的**对端实现** | 官方未开仓/未读 |
| 5 | `AbstractJdbcDialect.from(DataSource)` 方言探测实现 | 实施时 |
| 6 | `JsonFileAgentStateStore` / `InMemoryAgentStateStore` 实际存储路径 | 实施时 |
| 7 | `Sandbox` / `SkillRuntime` / `artifact` / `filesystem` 子树（不在本轮范围） | 实施时 |
