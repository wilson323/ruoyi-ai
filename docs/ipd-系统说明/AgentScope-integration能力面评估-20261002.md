# AgentScope integration 能力面评估（2026-10-02）

> 范围：通读 `/Users/mac/Documents/agentscope-java/docs/v2/zh/integration/` 下**全部 62 个 md**（4921 行，10 个子目录 + 根 `overview.md`），逐条对照 `/Users/mac/Documents/ruoyi-ai` 现状。
> 证据分级：**📄 文档声称** = 官方 md 原文；**✅ 源码实证** = 官方 SDK 源码行号或本仓源码行号。
> SDK 版本：`agentscope.version = 2.0.3`（`pom.xml:16`）；官方仓本地 checkout `v2.0.2-217-ge9721285`（`git describe`），源码行号以该 checkout 为准。
> **未读到**：无。62/62 全部读完。

---

## 一、能力面全景表

| 子目录 | md 数 | 覆盖能力面 | 核心接口 / 入口 | 本仓依赖情况 |
|---|---|---|---|---|
| **根 `overview.md`** | 1 | 全部集成的总索引与选型导航 | — | — |
| `model/` | 11 | 10 家模型提供商接入（OpenAI 栈 6 家 + DashScope / Gemini / Anthropic / Ollama） | `ModelRegistry.resolve("<provider>:<model>")` | ✅ 已用（5 个 model 扩展） |
| `distributed/` | 5 | `DistributedStore` 全链路分布式：AgentStateStore + BaseStore + SandboxSnapshot + SandboxExecutionGuard | `RedisDistributedStore` / `JdbcDistributedStore` / `MongoDistributedStore` / `OssDistributedStore` | ⚠️ 引依赖，**零调用** |
| `session/` | 5 | `AgentStateStore` 单点持久化（Redis / MySQL / OSS；已标注迁移至 `distributed/`） | `AgentStateStore` | ✅ 已用（Redis 生产 / MySQL 夹具） |
| `memory/` | 4 | `LongTermMemory` 跨会话长期记忆（Mem0 / 百炼 / ReMe） | `.longTermMemory(...)` + `LongTermMemoryMode` | ❌ 零引用 |
| `rag/` | 6 | `Knowledge` 检索后端（Simple 自建 + 百炼 / Dify / HayStack / RAGFlow） | `.knowledge(...)` / `.ragMode(...)` / `KnowledgeRetrievalTools` | ⚠️ 引依赖，实现自研（`CustomVectorRetriever`） |
| `skill/` | 4 | `AgentSkillRepository` 技能仓库（Git / MySQL / PG，后两者已废弃转 JDBC） | `AgentSkillRepository` | ✅ 用 core 自带的 `Classpath` / `FileSystem` 实现 |
| `protocol/` | 4 | A2A（client/server）、AG-UI、Agent Protocol（`/tasks` REST + SSE + HITL） | `A2aAgent` / `AguiAgentAdapter` / `agentscope.agent-protocol.enabled` | ❌ 零引用 |
| `channel/` | 6 | 钉钉 / 飞书 / GitHub / GitLab / 企业微信 5 个消息平台适配器 | `GatewayBootstrap` + 各 `XxxChannel` | ❌ 零引用（本仓自建 SSE/WS） |
| `infrastructure/` | 5 | Higress（AI 网关 MCP）、Nacos（A2A 注册 + Prompt 配置中心 + Skill 仓库）、Scheduler（Quartz / XXL-Job） | `HigressToolkit` / `NacosA2aRegistry` / `AgentScheduler` | ❌ 零引用 |
| `ecosystem/` | 5 | Chat Completions Web（OpenAI 兼容面）、Studio（可视化调试 / HITL）、Training（Trinity 在线训练） | `ChatCompletionsStreamingAdapter` / `StudioManager` / `TrainingRunner` | ❌ 零引用 |

**能力面小结**：integration 章节共 10 组、62 篇；核心统一抽象是 4 个接口 + 1 个 façade：
- `DistributedStore`（distributed/，4 组件一条龙）
- `AgentStateStore`（session/）
- `LongTermMemory`（memory/）
- `Knowledge`（rag/）
- `AgentSkillRepository`（skill/）
其余（channel / protocol / infrastructure / ecosystem / model）是外围适配器，model 是唯一的「必选项」，其余 9 组全部按需。

---

## 二、逐目录对照：已用 / 引了没调 / 没用 / 双轨

### 2.1 `model/` — ✅ 已用（覆盖度最高的组）

| 状态 | 事实 | 证据 |
|---|---|---|
| ✅ 已用 | `openai` / `anthropic` / `ollama` / `dashscope` 4 个扩展已声明 | `ruoyi-modules/ruoyi-chat/pom.xml:19,20,33,49` |
| ✅ 已用 | providerCode → `ModelRegistry` id 别名映射表（zhipu→glm、qianwen→dashscope、moonshot→kimi、custom_anthropic→anthropic、custom_api/atlas/ppio/xiaomi/openai/qwen→openai） | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeModelFactory.java:38-45` |
| ✅ 已用 | MiniMax 走 `minimax:` id（由 `agentscope-extensions-model-openai` 提供 formatter） | `AgentScopeModelFactory.java:33`（无 `:` 前缀时原样透传）+ `pom.xml:49` |
| ⚠️ 引了没调 | `agentscope-extensions-model-gemini` / `-openai-official` 未声明 → Gemini / OpenAI Responses API 不可达 | 三个 pom 全量 grep 无 `model-gemini` / `model-openai-official` |
| ❌ 没用 | 官方 Spring Boot starter（`agentscope-openai-spring-boot-starter` 等）一个都没引 | 同上 grep |
| ✅ 已用（旁证） | 本仓自建 `dify:` / `coze:` 两个 `ModelRegistry.registerFactory` 私有 provider，官方无对应扩展 | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/chat/impl/provider/DifyChatServiceImpl.java:30`、`CozeChatServiceImpl.java:30` |

**结论**：model 组是本仓唯一「单轨无缺口」的能力面。

### 2.2 `distributed/` — ⚠️ **引了依赖、零调用**（最大结构性缺口）

- 📄 文档声称：`DistributedStore` 一行配置让 Agent 状态 / 工作区 FS / 沙箱快照 / 并发锁全切分布式后端（`integration/distributed/index.md:12-21`）。
- ✅ 源码实证：本仓**没有任何一处** `DistributedStore` / `RedisDistributedStore` / `JdbcDistributedStore` / `SandboxSnapshotSpec` / `SandboxExecutionGuard` / `RemoteFilesystemSpec` 引用（`grep -rl` 于 `ruoyi-modules` / `ruoyi-common` / `ruoyi-admin` 全部返回 0）。
- ✅ 源码实证：`agentscope-extensions-redis` 在 `ruoyi-chat/pom.xml:21` 与 `ruoyi-ipd/pom.xml:20-23` 声明，但项目**只取用了其中的 `RedisAgentStateStore` 一个类**：
  - `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeRedisStateStores.java:18`
  - `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/config/ProjectAgentConfiguration.java:117`（`ipd:project-agent:state:` 前缀）
- 📄 文档声称的工作区 KV（`MEMORY.md` / `memory/` / `skills/` / `sessions/` 路由到共享 KV）在本仓**完全缺席** —— 4 个装配点的 `.filesystem(...)` 一个都没调，workspace 全部是本仓自建本地目录（见 §四）。
- 📄 文档特别强调的**多副本并发锁**（`RedisSandboxExecutionGuard`、`JdbcSandboxExecutionGuard`、`MongoSandboxExecutionGuard`）在本仓缺席；本仓用 SDK 自带的 **`LocalSessionTurnGate`**（进程内）做同 session 串行化：✅ 源码实证 `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeChatKernel.java:68`。
  - 📄 文档原文即点明该风险（`integration/distributed/index.md:26`）：`SandboxExecutionGuard` 的存在意义是「`AGENT` / `GLOBAL` 隔离范围在**多副本**下需要分布式锁防止并发冲突」。

**风险定级**：🔴 高。当前 `.agentscope` 若按 compose 单副本跑则无感，一旦多副本，`LocalSessionTurnGate` 不跨节点 → 同一 `(userId, sessionId)` 的并发 turn 会在两个节点上并行穿透。

### 2.3 `session/` — ✅ 已用（但有一处文档/源码矛盾待裁）

- ✅ 生产：`RedisAgentStateStore` + 自建 keyPrefix `ruoyi:agentscope:chat:`（`AgentScopeChatKernel.java:92`）、`ipd:project-agent:state:`（`ProjectAgentConfiguration.java:117`）。
- ⚠️ MySQL 路径：`agentscope-extensions-mysql` 在 `ruoyi-chat/pom.xml:36-40` 声明，但 **main 源树仍有一处生产可达的引用** —— `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/PocKernelSupport.java:6,27,33,38` 直接 `new MysqlAgentStateStore(pocDataSource(), POC_DATABASE, STATE_TABLE, false)`，且 `PocKernelSupport` 在 main 源树、非测试。
  - 官方源码：✅ `MysqlDistributedStore` 已标 `@Deprecated(since="2.1", forRemoval=true)`（`agentscope-extensions/agentscope-extensions-mysql/.../MysqlDistributedStore.java:60`），但 **`MysqlAgentStateStore` 类本身没有类级 `@Deprecated`**（同目录 `MysqlAgentStateStore.java:74` 类声明无注解；文件内唯一 `@Deprecated` 在 `:949` 的 `clearAllSessions()` 方法上）。
  - ⚠️ **本仓注释与官方源码不一致**：`KernelScopeKey.java:20-21` 写「MysqlAgentStateStore 该类在官方仓存在但被 `@Deprecated(since="2.1", forRemoval=true)` 标注」——该注解实际在 `MysqlDistributedStore` 上，不在 `MysqlAgentStateStore` 上。属**事实性注释错误**，建议修正（不在本次改动范围）。
- 📄 文档已把 `session/*` 三篇全部标注为「内容已迁移至 distributed/*，保留作为参考」（`integration/session/redis.md:6`、`mysql.md:6`、`oss.md:6`）→ session 组已非推荐入口，本仓按 `distributed` 视角看才是完整口径。

### 2.4 `memory/` — ❌ 零引用

- ✅ 源码实证：`LongTermMemory` / `Mem0LongTermMemory` / `BailianLongTermMemory` / `ReMeLongTermMemory` 在本仓 0 引用。
- ✅ 源码实证：4 个装配点全部显式 `.disableMemoryTools()` + `.disableMemoryHooks()`：
  - `AgentScopeChatKernel.java:344-345`
  - `AgentScopeProjectAgentKernel.java:283-284`
- 影响：Agent 无跨会话长期事实记忆（用户偏好、历史决策）。IPD 业务侧若要「记住这个人上次评审提过什么」需自建或接 Mem0。

### 2.5 `rag/` — ⚠️ 引依赖 + **双轨**（自研 `Knowledge` 实现）

- ✅ 依赖：`agentscope-extensions-rag-simple` 已声明（`ruoyi-chat/pom.xml:41-45`），但 `SimpleKnowledge` 在 **main 源树零引用**，只在 `ruoyi-chat/src/test/java/org/ruoyi/chat/poc/kernel/AgentScopeRagPocIT.java:6,93` 出现。
- ✅ **双轨证据**：本仓自研 `org.ruoyi.service.knowledge.retriever.CustomVectorRetriever implements io.agentscope.core.rag.Knowledge`（`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/knowledge/retriever/CustomVectorRetriever.java:30`），内部桥接自研 `KnowledgeRetrievalService` + Weaviate + `QueryVectorBo`，`addDocuments` 直接抛 `UnsupportedOperationException`（:36-38）。
- ✅ 编排层也自研：`MultiKnowledgeAugmentorFactory`（`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/knowledge/retriever/MultiKnowledgeAugmentorFactory.java`），由 `ChatServiceFacade.java:82` 与 `MpChatWebSocketHandler.java:64` 直接依赖，**不走** `.knowledge(...)` / `.ragMode(...)`。
- 判定：**接口单轨（都用 `io.agentscope.core.rag.Knowledge`）、实现在项目内** —— 严格说不是「官方 vs 自研」的双轨，而是「官方 SimpleKnowledge vs 本仓 CustomVectorRetriever」在同一 `Knowledge` 接口下的两条实现。官方 `SimpleKnowledge` 事实上只被 PoC 测试使用，**生产 RAG 100% 走自研 Weaviate 链路**。
- 📄 文档事实核对：`SimpleKnowledge` 内置 5 个向量库适配器含 `MilvusStore` / `QdrantStore` / `PgVectorStore` / `ElasticsearchStore`，**不含 Weaviate**（`integration/rag/simple.md:144-153`）→ 本仓用 Weaviate 是**官方未覆盖的合理选择**，双轨不可简单归并。

### 2.6 `skill/` — ✅ 已用（用的是 core 内置实现，非 integration 组）

- ✅ `ruoyi-modules/ruoyi-ipd/.../agent/catalog/ProjectAgentSkillCatalog.java:4,94` → `ClasspathSkillRepository`（core 自带）。
- ✅ `ruoyi-modules/ruoyi-ipd/.../agent/kernel/FrozenProjectAgentSkills.java:7,9,28,74` → 自研 `FrozenProjectAgentSkills implements AgentSkillRepository, MiddlewareBase`（按 run 冻结 skill sha/version/body）。
- ✅ `ruoyi-modules/ruoyi-chat/.../agent/NativeChatSkills.java:4,20,30` → `FileSystemSkillRepository`（core 自带）。
- ❌ integration 组三个扩展（`skill-git-repository` / `skill-mysql-repository` / `skill-postgresql-repository`）**一个都没引**。且 📄 官方已把后两者标「**已废弃**，请迁移到 `JdbcAgentSkillRepository`」（`integration/skill/mysql-repository.md:672`、`postgresql-repository.md:784`）→ 不引是对的。
- ⚠️ 连带结论：**「后台在线编辑 skill、立即生效」这条能力目前无官方实现落点**（IPD 有 69 动作、Skill 是能力包的一部分）。可用落点只有 `JdbcAgentSkillRepository`（需引 `agentscope-extensions-jdbc`）或 Nacos Skill 仓库（`integration/infrastructure/nacos.md:186-207`）。

### 2.7 `protocol/` — ❌ 零引用

- ✅ 源码实证：`A2aAgent` / `AgentScopeA2aServer` / `AguiAgentAdapter` / `agentscope.agent-protocol.*` 全部 0 引用。
- ✅ 源码实证：本仓 `application.yml` 里**没有任何 `agentscope:` 顶层配置块**（grep `^agentscope:` 无命中），故 📄 文档 `integration/protocol/agent-protocol.md:1102-1107` 的 5 个配置键全为默认态（`enabled=false`）。
- 缺口对照本仓现状：
  - 📄 AG-UI → 本仓自建 SSE（`KernelChatSink` / `KernelEventFrames`，`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/`）→ **对 AG-UI 协议是双轨**（自家事件帧 vs 官方 `RUN_*` / `TEXT_MESSAGE_*` 语义）。
  - 📄 Agent Protocol `/tasks` REST → 本仓 IPD 有自建 `ProjectAgentRun*` 一族（`ProjectAgentRunHandle` / `ProjectAgentRunOwnership` / `ProjectAgentRunRecovery`）→ **对远程任务面是双轨**。
  - 📄 A2A → 本仓无需求，可不落。

### 2.8 `channel/` — ❌ 零引用

- ✅ 源码实证：`GatewayBootstrap` / `FeishuChannel` / `WeComChannel` / `DingTalkChannel` / `GitHubChannel` / `GitLabChannel` 全部 0 引用；`agentscope-extensions-channel-*` 未声明。
- 📄 值得注意的架构事实（`integration/channel/index.md:38-48`）：`IdempotencyStore` / `BotLoopGuard` / `AccessTokenStore` **默认只存 JVM 内**，多副本必须换成共享实现。本仓若将来接飞书/企微，**直接上 channel 会踩这个坑** —— 而本仓的 `ruoyi-common-redis` 已有 `KeyPrefixHandler`（`ruoyi-common/ruoyi-common-redis/src/main/java/org/ruoyi/common/redis/handler/KeyPrefixHandler.java`）可复用为共享 store。
- 本仓已有自建实时通道：`ruoyi-common-sse` + `ruoyi-common-websocket`（`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/websocket/chat/MpChatWebSocketHandler.java`）→ 与 channel 组无重叠，无双轨。

### 2.9 `infrastructure/` — ❌ 零引用

- ✅ 源码实证：`HigressToolkit` / `NacosA2aRegistry` / `AgentScheduler` 0 引用。
- 📄 唯一与本仓现实呼应的一条：`integration/infrastructure/scheduler.md:215-227` 的 Quartz / XXL-Job Agent 调度。**本仓已有 `ruoyi-modules/ruoyi-workflow`（Warm-Flow BPMN）+ `ruoyi-extend/ruoyi-snailjob-server`** → 调度面已自建覆盖，不引官方 scheduler 是对的。
- 📄 值得注意的隐性耦合：`NacosSkillRepository` 构造签名带 `"default-namespace"` 字符串参数（`integration/infrastructure/nacos.md:205`），与本仓 `tenant.enable=true` 的多租户诉求直接冲突（详见 §三）。

### 2.10 `ecosystem/` — ❌ 零引用

- ✅ 源码实证：`ChatCompletionsStreamingAdapter` / `StudioManager` / `TrainingRunner` 0 引用。
- 📄 Chat Completions Web 缺口：本仓 `ruoyi-common-chat` 有一整套自建 `ChatService` 体系（`ChatServiceFacade` + `DifyChatServiceImpl` / `CozeChatServiceImpl` 等 provider），**但那是「调外部模型服务」，不是「把 Agent 暴露成 OpenAI 兼容面」**。若外部系统要远程调 IPD 数字员工，目前只能走 A2A / Agent Protocol / 自建 REST。
- 📄 Studio 缺口：📄 `ecosystem/studio.md:515` 明确「生产部署一般不挂这个 Hook」→ 不引不构成风险。
- 📄 Training：与 IPD 业务无关，不落。

---

## 三、多租户 × 官方集成面的冲突点

本仓 `tenant.enable=true`（`ruoyi-admin/src/main/resources/application.yml:267-269`）。逐条核查官方默认假设：

| # | 冲突点 | 📄 官方默认 | ✅ 官方源码行号 | 本仓现状 | 风险 |
|---|---|---|---|---|---|
| **T-1** | Redis 状态 key 前缀**不含租户** | `agentscope:session:` | `agentscope-extensions-redis/.../RedisAgentStateStore.java:180` `DEFAULT_KEY_PREFIX = "agentscope:session:"` | 本仓两个 keyPrefix 均为**固定常量**、无租户段：`AgentScopeChatKernel.java:92` `"ruoyi:agentscope:chat:"`、`ProjectAgentConfiguration.java:117` `"ipd:project-agent:state:"` | 🔴 租户隔离**完全依赖复合 sessionId 字符串**，keyPrefix 层无租户维度 |
| **T-2** | 租户维度**只能折进 userId/sessionId** | 📄 `(userId, sessionId)` 二元寻址，`userId` 可空表示「匿名/单租户调用方（CLI、测试等）」 | `RedisAgentStateStore.java:446-451` `slotId()` = `normalizeUser(userId) + "/" + sessionId` | 本仓用 `KernelScopeKey.of(projectId, userId, agentId, sessionId)` 把四维压成 `p{project}:u{user}` + `a{agent}:s{session}`（`KernelScopeKey.java:78-80`） | 🟡 键空间无租户段，跨租户 `projectId` 相同即串（依赖业务保证 projectId 全局唯一） |
| **T-3** | 匿名段常量官方已内置 | `__anon__` | `RedisAgentStateStore.java:439` `ANON_USER = "__anon__"`；`:442` `normalizeUser()` 空 userId → `__anon__` | 本仓**自定**了同名常量 `KernelScopeKey.ANONYMOUS_USER_SEGMENT = "__anon__"`，注释写「官方 RedisAgentStateStore 无对应 ANON_USER 常量」 | 🟡 **注释失实**：官方 `:439` 就有该常量。行为一致（都叫 `__anon__`），但注释否认了它 |
| **T-4** | MySQL / JDBC **默认库名、表前缀无租户段** | `agentscope` / `agentscope_` | `agentscope-extensions-mysql/.../MysqlAgentStateStore.java:76-77`；`agentscope-extensions-jdbc/.../AbstractJdbcDialect.java:77` `tablePrefix = "agentscope_"` | PoC 走 `PocKernelSupport.java:38` 固定 `POC_DATABASE`；生产已切 Redis | 🟡 PoC 路径在多租户下是单库单表 |
| **T-5** | `IsolationScope` 只有 USER/SESSION/AGENT/GLOBAL，**无 TENANT** | 📄 `IsolationScope` 四值枚举 | `agentscope-harness/.../IsolationScope.java:62,71,79,87`；`:104-124` 路径前缀规则 | 本仓不用 `IsolationScope`，改用自建 `ProjectAgentWorkspace.prepare(root, projectId, userId, agentId)`（`ruoyi-modules/ruoyi-ipd/.../ProjectAgentWorkspace.java:34-35`） | 🟡 workspace 路径段是 projectId/userId/agentId，**无 tenantId 段** |
| **T-6** | Harness 默认 workspace 落在**进程本地相对目录** | `${user.dir}/.agentscope/workspace` | `agentscope-harness/.../HarnessAgent.java:1188` `resolveDefaultWorkspace()`；`:1152` 状态默认 `~/.agentscope/state` | 4 个装配点全部显式 `.workspace(...)`，未走默认 | 🟢 已规避（前提：所有新增装配点都记得显式设） |
| **T-7** | Nacos Skill 仓库构造签名带**硬编码默认命名空间** | `new NacosSkillRepository(aiService, "default-namespace", props)` | 📄 `integration/infrastructure/nacos.md:205` | 未引用 | 🟡 若将来接 Nacos，「租户 = Nacos namespace」是单值假设，**与本仓 tenantId 体系对不上** |
| **T-8** | Mem0 记忆**三层 ID 需手工映射租户** | `agentName` / `userId` / `runName` 三选一 | 📄 `integration/memory/mem0.md:109-120`；ReMe 侧更极端：「建议在 `userId` 里编码命名空间（例如 `tenant-a:project-1`）」（`memory/reme.md:316`） | 未引用 | 🟡 ReMe 明确把「命名空间编码」责任推给调用方 → 与本仓 `KernelScopeKey` 的 `:` 分隔编码法同源，**可复用同一收口点** |
| **T-9** | 📄 官方自己承认控制面**不适用于互不信任的多租户** | 「当前鉴权为集群内共享 internal token；租户（`agentName` / `namespace`）取自请求体——**不适用于**同一控制面上互不信任的多租户」 | 📄 `integration/distributed/index.md`（aistio 托管 Store 段） | 未使用 aistio | 🟢 官方已自曝边界，本仓不踩 |
| **T-10** | Channel 通用组件**默认 JVM 内单副本可见** | `IdempotencyStore` / `InMemoryAccessTokenStore` 默认 | 📄 `integration/channel/index.md:40-48` | 未引用 channel | 🟡 未来接通道时必查 |

**多租户冲突点合计：10 条**（🔴 1 / 🟡 8 / 🟢 1）。**其中 T-1 是唯一的结构性红灯**：官方 key 前缀无租户维度，本仓四维隔离全压在调用方传的复合 sessionId 上，绕过 `KernelScopeKey.of` 即静默失隔离——`KernelScopeKey.java:26-30` 的 fail-closed 校验正是为此存在，但**它是本仓自建防线，不是官方保证**。

---

## 四、双轨风险清单

| # | 双轨 | 一方（官方/仓内基座） | 另一方（自研） | 证据 | 严重度 |
|---|---|---|---|---|---|
| **D-1** | **MysqlAgentStateStore 声明 vs 实际** | `agentscope-extensions-mysql` 官方扩展 | 本仓生产走 Redis，PoC 走 MySQL，两条并存 | 依赖 `ruoyi-chat/pom.xml:36-40`；MySQL 引用 `PocKernelSupport.java:6,33,38`（**main 源树**）；Redis 引用 `AgentScopeRedisStateStores.java:18` | 🟠 中：官方已把 mysql 模块整体导向 `agentscope-extensions-jdbc`（`integration/distributed/jdbc.md:11-15` 迁移表），本仓两条都得迁 |
| **D-2** | **Redisson 客户端适配** | 官方 `RedissonClientAdapter`（假设 Redisson ≥4.2） | 本仓自研 `Redisson351Adapter`（降级 `ReturnType.LONG`→`INTEGER`） | `AgentScopeRedisStateStores.java:22-49`；注释自述「SDK 2.0.3 adapter 使用 4.2 才有的 ReturnType.LONG；3.51 的 INTEGER 同样返回 Number」 | 🟢 低：**注释清晰、只改一个枚举、委托官方其余逻辑**，是合规的版本适配不是平行实现 |
| **D-3** | **RAG 检索** | 官方 `SimpleKnowledge` + 5 个向量库适配器（`integration/rag/simple.md:144-153`） | 本仓 `CustomVectorRetriever` + Weaviate 链路 | 官方侧仅测试引用 `AgentScopeRagPocIT.java:6,93`；自研侧 `CustomVectorRetriever.java:30` + `MultiKnowledgeAugmentorFactory.java` | 🟢 低：统一在 `Knowledge` 接口下，且**官方 SimpleKnowledge 不支持 Weaviate**，本仓是补位不是重复 |
| **D-4** | **Agent 事件流协议** | 官方 AG-UI（`integration/protocol/agui.md`，`RUN_*` / `TEXT_MESSAGE_*` / HITL interrupt） | 本仓自建 `KernelChatSink` / `KernelEventFrames` 帧协议 | 自研侧 `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/KernelEventFrames.java`；官方侧 0 引用 | 🟠 中：前端若要接标准 AG-UI 生态需二次适配 |
| **D-5** | **远程任务面** | 官方 Agent Protocol `/tasks` + SSE + `/resume` HITL（`integration/protocol/agent-protocol.md:996-1097`） | 本仓 IPD `ProjectAgentRun*` 一族 | 自研侧 `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentRun{Handle,Ownership,Recovery}.java` | 🟠 中：两套 HITL / 状态语义并存，跨系统调用无标准出口 |
| **D-6** | **Agent 状态持久化入口** | 官方推荐 `DistributedStore` 一条龙（`integration/distributed/index.md`） | 本仓手工 `.stateStore(...)` 逐点装配 + 自建 `FailClosedAgentStateStore` | 4 个装配点均无 `.distributedStore(...)`；自研侧 `AgentScopeChatKernel.java:92` `FailClosedAgentStateStore` 包装 | 🟠 中：官方能力（BaseStore / 快照 / 分布式锁）拿不到，workspace KV 走本地 |
| **D-7** | **注释与源码失实** | 官方 `MysqlAgentStateStore` 无类级 `@Deprecated` | 本仓注释称其被 `@Deprecated(since="2.1",forRemoval=true)` 标注 | 官方 `MysqlAgentStateStore.java:74`（无注解）vs 注解实际在 `MysqlDistributedStore.java:60`；失实注释 `KernelScopeKey.java:19-21` | 🟡 低（文档级），但会让后续判断走错 |

**双轨合计 7 条**（🟠 3 / 🟡 2 / 🟢 2）。

---

## 五、落地清单（按优先级，只列清单不做方案）

### P0 — 消除结构性红灯（多副本正确性）
1. **用 `DistributedStore` 收敛状态存储**：`ruoyi-chat` / `ruoyi-ipd` 两处 `AgentScopeRedisStateStores.create(...)` 改为 `RedisDistributedStore.fromJedis(client, "<含租户语义的prefix>")` 一步到位，同时拿到 `BaseStore` / `SandboxSnapshotSpec` / `SandboxExecutionGuard`（`integration/distributed/redis.md:20-35`、`:36-140`）。
2. **keyPrefix 补租户段**（对应 T-1）：现 `"ruoyi:agentscope:chat:"` / `"ipd:project-agent:state:"` 需设计为可含 tenantId 的形态，或明确书面裁定「租户隔离只由 `KernelScopeKey` 复合键承担，prefix 固定」并加门禁。
3. **Turn gate 换分布式实现**（`AgentScopeChatKernel.java:68` `LocalSessionTurnGate` → 官方托管 `SessionTurnGate`，见 `integration/distributed/index.md` aistio 段或自建 Redis 租约）。

### P1 — 收敛双轨 + 补齐关键能力面
4. **MySQL 路径迁 `agentscope-extensions-jdbc`**（D-1）：`PocKernelSupport.java:33-41` 的 `MysqlAgentStateStore` 与 `AgentScopeChatKernel.java:102-113` 的测试夹具统一迁到 `JdbcDistributedStore.create(ds)`，同时按 `integration/distributed/jdbc.md:60-70` 建 `agentscope_store` / `agentscope_sessions` / `agentscope_snapshots` / `agentscope_distributed_locks`。
5. **远程任务面接标准协议**（D-5）：评估引 `agentscope-extensions-agent-protocol`（`/tasks` + SSE + HITL），与 IPD `ProjectAgentRun*` 划清「标准面 vs 业务面」边界。
6. **workspace 走 `RemoteFilesystemSpec`**（D-6 / §2.2）：让 `MEMORY.md` / `memory/` / `skills/` / `sessions/` 路由到共享 KV，而不是 4 个点各自 `Files.createTempDirectory` / 本地目录。
7. **Skill 在线编辑能力缺口**（§2.6）：IPD 需要「后台改 Skill 立即生效」，官方落点只有 `JdbcAgentSkillRepository`（需 `enableSkillTables(true)`，见 `integration/distributed/jdbc.md:105-127`）或 Nacos Skill 仓库（受 T-7 命名空间限制）。

### P2 — 增量补齐（按业务优先级排后）
8. **AG-UI 事件面**（D-4）：前端若要接标准 AG-UI 生态，引 `agentscope-agui-spring-boot-starter` 做协议映射而非重写。
9. **长期记忆**（memory 组全空）：评估 Mem0（本地 `docker run` 门槛低、`userId` 三层 ID 可直接映射本仓 `KernelScopeKey`，见 `integration/memory/mem0.md:109-120`）。
10. **Channel 接入**（若需飞书/企微入口）：引扩展时**必须**同时换共享 `IdempotencyStore` / `AccessTokenStore`（T-10），否则多副本下 webhook 重试会重复处理。
11. **模型补齐**：`agentscope-extensions-model-gemini`（若需 Gemini）/`-openai-official`（若需 Responses API 推理链）。
12. **Chat Completions Web**：若外部系统需以 OpenAI 协议调 IPD 数字员工，引 `agentscope-extensions-chat-completions-web`（`integration/ecosystem/chat-completions-web.md:394-431`）。

### 明确**不落**（避免无谓引入）
- ❌ `ecosystem/studio`（生产不建议挂 Hook，📄 `ecosystem/studio.md:515`）
- ❌ `ecosystem/training`（Trinity，与 IPD 业务无关）
- ❌ `skill/mysql-repository` + `skill/postgresql-repository`（📄 官方已标废弃）
- ❌ `infrastructure/scheduler`（本仓已有 Warm-Flow + SnailJob 覆盖）
- ❌ `distributed/mongodb`、`distributed/oss`（当前无 MongoDB / 阿里云 OSS 部署）
- ❌ `protocol/a2a`（无外部 Agent 互操作需求）
- ❌ `infrastructure/higress`（无 Higress 网关）

---

## 六、读档完备性声明

- integration 章节 **62/62 篇 md 全部读完**，无「未读到」项。
- 逐篇清点：`overview.md` 1 + `channel/` 6 + `distributed/` 5 + `ecosystem/` 5 + `infrastructure/` 5 + `memory/` 4 + `model/` 11 + `protocol/` 4 + `rag/` 6 + `session/` 5 + `skill/` 4 = 62。
- 本仓现状核查范围：`pom.xml` × 5、`ruoyi-modules` / `ruoyi-common` / `ruoyi-admin` 全量 `io.agentscope.*` import 聚合、4 个 `HarnessAgent.builder()` 装配点及其全量 disable 开关、隔离键收口点、官方 SDK 源码（redis / mysql / jdbc / harness / extensions 目录）。
