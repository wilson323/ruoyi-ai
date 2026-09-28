# agentscope-java — 本仓实证的 AgentScope Java API 面与依赖雷

> 事实源分级（**引用前先看标记，禁止把 📄 当 ✅ 用**）：
> - ✅**已实证** = 本仓 `poc/agentscope-kernel` 分支代码里真跑过、真库回读过（commit `8019d9a5`→`129327db`，G1~G5，2026-09-28 现查）
> - 📄**仅文档** = 出自 `java.agentscope.io` 官方文档/博客，**本仓尚未验证**，用前必须自己跑通
>
> 版本：`io.agentscope:*:2.0.3`（✅ 见 `ruoyi-modules/ruoyi-chat/pom.xml`，PoC 分支）

## 是什么

AgentScope Java 2.0 的 `HarnessAgent` 是统一代码入口；同一套 Harness 可演进为本地工作区 / 嵌入式应用 / 长任务 Worker / 平台化交付四种形态（见 [harness-patterns.md](harness-patterns.md) §C）。

## 为什么踩坑（本仓真实吃过的三颗雷）

| 雷 | 表现 | 根因 / 解法 |
|---|---|---|
| **okhttp 重复类** | 引入 agentscope 后运行态类冲突 | G1 已把 okhttp **5.3.2 全家钉版** + `banDuplicateClasses` 门禁根除。升 agentscope 前先重跑该门禁 |
| **langchain4j 接线膨胀** | 内核替换期间新旧两套并存、接线点无控增长 | G2 立"**只减不增**棘轮门禁" + 基线 111 条（`scripts/baselines/langchain4j-count.json` + `scripts/check-langchain4j-ratchet.sh`，已接 `.claude/hooks/check-pre-commit.sh` 门禁 5）。新增 langchain4j 接线会被拦 |
| **原生只有二维寻址，业务要四维隔离** | project / agent 两个维度在 `AgentStateStore` 里**没有列**，串桶即数据泄漏 | G3 用 `KernelScopeKey` 把四维折入 `(userId, sessionId)` 二元组，fail-closed 拒注入。见下 §3 |

## 怎么识别（装配骨架，✅ 全部已实证）

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;

// 1) 装配：name + sysPrompt + model + workspace + stateStore（✅ PocKernelSupport.buildAgent）
HarnessAgent agent = HarnessAgent.builder()
        .name(agentId)                       // 也决定 workspace 下 agents/<agentId>/ 的分桶目录
        .sysPrompt("You are a helpful assistant. Answer concisely in the user's language.")
        .model(ModelRegistry.resolve("minimax:MiniMax-M3"))   // provider SPI，"provider:model" 形式
        .workspace(ws)                       // java.nio.Path；ws/AGENTS.md 是人格注入唯一必需文件
        .stateStore(stateStore)              // 外置状态；不传则只在进程内
        .build();

// 2) 状态存储：四参构造，第 4 参 = 是否自动建表（✅ 本仓传 false，表按源码 DDL 预建）
MysqlAgentStateStore store =
        new MysqlAgentStateStore(dataSource, "ipd_poc", "agentscope_sessions", false);

// 3) 身份：RuntimeContext 只有 (userId, sessionId) 二元
RuntimeContext ctx = RuntimeContext.builder().userId(uid).sessionId(sid).build();

// 4) 消息
Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
Msg asst = Msg.builder().role(MsgRole.ASSISTANT)
        .content(List.of(TextBlock.builder().text(reply).build())).build();

// 5) 调用：同步
String out = agent.call(msg, ctx).block();

// 6) 调用：流式（Reactive；✅ PocSseController 用 subscribe 三参）
agent.streamEvents(msg, ctx).subscribe(
        ev -> { /* io.agentscope.core.event.AgentEvent */ },
        err -> { /* 结构化错误，别吞 */ },
        () -> { /* complete */ });

// 7) 状态回读（✅ 隔离实证的判据来源）
Optional<AgentState> st = store.get(uid, sid, "agent_state", AgentState.class);
store.close();   // ✅ @AfterAll 必须关，否则连接泄漏
```

**流式事件形态**（✅ `PocSseController`）：`AgentEvent` 用 `event.getType().name()` 取类型名；文本增量是 `TextBlockDeltaEvent`，取 `delta.getDelta()`。映射到 SSE 时区分 `text_delta` 与泛 `event`，错误单独发 `error` 事件后 `complete()`。

**RAG 全链**（✅ `AgentScopeRagPocIT`，G5：ingest→retrieve + 正例 2 / 负例 6）：

```java
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.InMemoryStore;

InMemoryStore store = InMemoryStore.builder().dimensions(DIM).build();
SimpleKnowledge kb = SimpleKnowledge.builder()
        .embeddingModel(embeddingModel)     // io.agentscope.core.embedding.EmbeddingModel
        .embeddingStore(store)
        .build();

Map<String, Object> payload = new HashMap<>();
payload.put("projectId", projectId);        // 业务过滤维放 payload
DocumentMetadata md = new DocumentMetadata(
        TextBlock.builder().text(text).build(), docId, "chunk-0", payload);
Document doc = new Document(md);
doc.setVectorName("pP1");                   // ✅ 硬隔离维：不同 vectorName = 不同召回桶

kb.ingest(List.of(doc)).block();
List<Document> hits = kb.retrieve("query",
        RetrieveConfig.builder().limit(10).scoreThreshold(0.01d).vectorName("pP1").build()).block();
Object pid = hits.get(0).getPayloadValue("projectId");
```

> **两级隔离要分清**：`vectorName` 是**硬桶**（跨桶召不回，本仓用它做 project 维隔离）；`payload` 是**软过滤**（同桶内按元数据筛）。只用 payload 不用 vectorName，等于把租户数据放进同一个可召回空间——负例必须覆盖"故意用错 vectorName 应召不回"。

模型层还有 ✅ 已 import 但按需使用：`ChatResponse`、`GenerateOptions`、`ToolSchema`（`io.agentscope.core.model.*`）。

## 怎么修（三条落地纪律）

### 1. 四维隔离键唯一收口（✅ `KernelScopeKey`，禁止绕过）

`MysqlAgentStateStore` 落库 `slotId = userId + ":" + sessionId`（✅ 实测源码），只有二元寻址、**无 agent 列、无 project 列**。本仓键式：

```text
复合 userId    = p{projectId}:u{userId}          ← project 维是原生缺失维，用 userId 前缀补齐
复合 sessionId = a{agentId}:s{sessionId}          ← agent 维折入 session 段
最终 slotId    = p{projectId}:u{userId}:a{agentId}:s{sessionId}   ← 四维全收口于一列
```

硬规则：

- **业务代码禁止手工拼键，一律经 `KernelScopeKey.of(projectId, userId, agentId, sessionId)`**
- fail-closed：任一原始段含 `':'` 或 `..` **直接抛 `IllegalArgumentException`**（防复合 key 注入伪造别桶地址 / 路径穿越）
- `userId` 允许为空 → 降级 SESSION 语义，user 段坍缩为 `__anon__` 命名空间（对齐 `MysqlAgentStateStore.ANON_USER` 与 `IsolationScope.USER` 空 userId 降级 SESSION）；会话维仍硬隔离
- `projectId` / `agentId` / `sessionId` 必填
- HarnessAgent 侧的文件分桶对应物 = `.name(agentId)` 决定的 `agents/<agentId>/` 目录，**两处必须同源**，否则状态与文件分桶错位

### 2. Session ≠ Task（📄 文档纪律 + ✅ 本仓 PoC 已按此设计）

`RuntimeContext.sessionId` 承担**推理连续性**；业务 `taskId`、Agent 版本、输入、Artifact、审批记录、Outcome 必须由业务系统另存。别把 HTTP 连接 / WebSocket 当 Session，别把 Session 当 Task。

### 3. 凭据与数据源（✅ 本仓约定，与项目 AGENTS.md 一致）

- 本机真库不是 `application-dev.yml` 写的 `127.0.0.1:3306`，实走 `127.0.0.1:13306`；PoC 用独立库 `ipd_poc`（表 `agentscope_sessions`），**不碰业务库 `ipd_dev`**
- 凭证从 `.codex/ipd-dev/config/mysql-app.cnf` 读（gitignored），**不上命令行、不打印、不入版本库**
- 模型凭据缺失时各关标 `BLOCKED_ENVIRONMENT`，**不得伪造输出**（✅ `PocKernelSupport` 类注释即此纪律）
- 引擎无状态、per-`(userId, sessionId)` 内核串行化 → `HarnessAgent` 实例可按 agentId 缓存复用（✅ `ConcurrentHashMap` + `computeIfAbsent`）

## Builder 选项速查

✅ = 本仓已跑；📄 = 官方文档，本仓未验证。

| 方法 | 标记 | 说明 |
|---|---|---|
| `.name(String)` | ✅ | 行为主体标识 + workspace 下 `agents/<name>/` 分桶 |
| `.sysPrompt(String)` | ✅ | 系统提示 |
| `.model(Model)` | ✅ | 通常 `ModelRegistry.resolve("provider:model")` |
| `.workspace(Path)` | ✅ | 不传则走默认解析顺序 |
| `.stateStore(AgentStateStore)` | ✅ | 外置状态；分布式/可恢复必需 |
| `.call(Msg, RuntimeContext)` / `.streamEvents(Msg, RuntimeContext)` | ✅ | 同步 / 流式；均须传 RuntimeContext |
| `.additionalContextFile("SOUL.md")` | 📄 | workspace 相对路径，全文注入，可重复调 |
| `.maxContextTokens(int)` | 📄 | MEMORY 注入预算 |
| `.enablePlanMode()` / `.planFileDirectory("plans")` | 📄 | 只读探索→写计划→HITL 确认→执行；计划落 `plans/PLAN.md` |
| `.enableTaskList()` | 📄 | Todo，存 Agent 状态，跨调用恢复 |
| `.compaction(CompactionConfig.builder().triggerMessages(30).keepMessages(10).build())` | 📄 | 上下文压缩 |
| `.toolResultEviction(ToolResultEvictionConfig.defaults())` | 📄 | 超大工具结果落盘、窗口只留占位符 |
| `.filesystem(new DockerFilesystemSpec().image("ubuntu:24.04"))` | 📄 | Sandbox 执行环境；另有 `SandboxFilesystemSpec` / `RemoteFilesystemSpec`（Redis/JDBC/OSS） |
| `.skillRepository(repo)` / `.skillRepositories(list)` | 📄 | 追加 / 替换技能市场（`GitSkillRepository`、Nacos、MySQL、Classpath） |
| `.projectGlobalSkillsDir(Path)` | 📄 | 项目全局技能目录，不存在则跳过 |
| `.disableDynamicSkills()` | 📄 | 关掉"每轮推理前重新合并"，改 build 时合并一次（单次任务或市场后端慢时用） |

## Workspace 目录布局（📄 逻辑布局，非固定磁盘路径）

```text
.agentscope/workspace/
├── AGENTS.md              ← 静态：人格 + 行为约定（唯一你真正需要手写的文件）
├── MEMORY.md              ← 长期记忆：策划后的长期事实
├── tools.json             ← 静态：MCP server + 工具白名单（可选）
├── memory/YYYY-MM-DD.md   ← 长期记忆：每天追加的事实流水账
├── knowledge/KNOWLEDGE.md ← 静态：领域知识入口 + 任意参考文件
├── skills/<name>/SKILL.md ← 静态：技能目录，放好即生效，无需注册
├── subagents/<id>.md      ← 静态：子 agent 声明（文件名即 agent_id）
├── plans/PLAN.md          ← 运行时：Plan Mode 写下的计划
└── agents/<agentId>/      ← 运行时：每个 agent 自己的运行时根
    ├── sessions/sessions.json + <sessionId>.log.jsonl   ← 会话索引 + 永不压缩对话日志
    └── tasks/<sessionId>.json                           ← 子 agent 后台任务记录
```

按需自动出现：`.compaction(...)` → `memory/` + `MEMORY.md`；放 subagent spec → `subagents/`；装技能 → `skills/`；`.enablePlanMode()` → `plans/`；任何 `call()` 跑过一次 → `agents/<agentId>/`。

**同一份布局可物理落在本机磁盘 / 远端分布式存储（`RemoteFilesystemSpec`：Redis / JDBC / OSS）/ 映射进沙箱容器（`SandboxFilesystemSpec`）**，相对路径三种模式完全一致，agent 代码不用动。

> **Agent 状态不在工作区**——它走 `stateStore`（内存 / JSON 文件 / MySQL / Redis）独立存储。会话日志才是工作区文件。别把两者混在一处做备份或迁移。

### Skill 同名冲突优先级（📄 四层，从低到高）

| 优先级 | 来源 | 配置 |
|---|---|---|
| 1（最低） | 项目全局目录 | `projectGlobalSkillsDir(Path)`，如 `~/.agentscope/skills/` |
| 2 | 市场 | `skillRepository(...)`，后注册覆盖先注册 |
| 3 | 工作区共用 | `workspace/skills/` |
| 4（最高） | 用户隔离 | `workspace/<userId>/skills/`（需 `RuntimeContext.userId` 匹配） |

下层独有的 skill 仍保留，只在重名时被上层覆盖。子 agent 自动继承父的市场列表和项目全局目录，不用重复配。运行时按需 `load_skill_through_path` 加载详情；**读 `SKILL.md` 不需要沙箱**，只有跑 skill 脚本才进沙箱（宿主物化 → workspace projection 注入容器 `/workspace` → 容器内执行）。

## 本仓自研 harness ↔ AgentScope 契约对照（单轨决策用）

本仓已有一套**与 AgentScope 无关**的自研 harness：`org.ruoyi.service.coding.harness`，17 子包 / main 树 254 个 `.java` + test 树 1 个（2026-09-28 `find` 现查），**零** `io.agentscope` 引用。它已经把 blog 01~04 的多条契约自己实现了一遍，所以引入 AgentScope 时最大的风险不是「不会用」，而是**静默长出第二套 harness**。

子包规模（大到小）：`tool` 48 / `model` 38 / `loop` 35 / `plan` 28 / `approval` 18 / `artifact` 15 / `context` 15 / `app` 8 / `journal` 7 / `recovery` 7 / `runtime` 7 / `modelruntime` 6 / `prompt` 6 / `store` 6 / `event` 5 / `skill` 4 / `config` 1。

下表左列是契约，中列是**已现查确认存在**的自研实现，右列是单轨处置建议：

| AgentScope / blog 契约 | 本仓自研实现（路径相对 harness 包根） | 单轨处置 |
|---|---|---|
| Permission 三态 ALLOW/DENY/ASK | `tool/PolicyDecision.java`、`tool/ToolPolicyContract.java` | 保留，接入时做适配层；禁再写一套 |
| 副作用幂等「上次到底发生没有」 | `recovery/ToolEffectLedgerReconciler.java`、`recovery/UncertainToolEffectGuard.java` | 保留（已是本仓最难重建的资产） |
| Plan 版本与失效 | `plan/CanonicalPlanHasher.java`、`plan/StalePlanRevisionException.java` | 保留；对齐 Plan Mode 的 `plans/PLAN.md` + HITL |
| 多维预算（步数/时长/token/费用/工具次数/并发/高风险次数） | `model/HarnessBudget.java` | 对照 blog02 七项逐项查缺口 |
| Context 管线与压缩 | `context/ContextEngine.java`、`context/CompactionRequest.java`、`context/TokenEstimator.java` | 对照 Context Manifest 9 字段 + 分层活动上下文查缺口 |
| Event Log 与脱敏 | `journal/JournalSecretRedactor.java`（`journal/` 7 + `event/` 5） | 保留；区分 Event Log / Snapshot / Checkpoint 三表示 |
| 乐观并发 | `store/HarnessOptimisticLockException.java` | 保留 |
| Skill 渐进式披露（发现/选择/执行三层） | `skill/HarnessSkillCatalog.java`（`skill/` 仅 4 文件） | **最可能的缺口**：对照四层同名优先级 + 沙箱物化路径 |
| HITL 人工审批 | `approval/`（18 文件） | 对照 Action 生命周期与 Preview→Approve→Commit→Verify |
| Agent Loop 五步 | `loop/`（35 文件） | 对照 Prepare→Model→Act→Observe→Verify |
| Artifact 生命周期 | `artifact/`（15 文件） | 对照 Draft→Validating→Ready→Published/Rejected→Archived |
| Workspace 六区 | （未见对应包） | 缺口：inputs/scratch/state/artifacts/evidence/manifest |

三条硬纪律：

1. **先审计再引入**：把本 skill 当透镜逐项盘点上表，产出「已有 / 缺口 / 重复」三态清单，再决定 AgentScope 的引入面。
2. **单轨决策显式化**：内核替换必须做「替换 / 包装 / 保留」三选一并记 ADR；禁止自研与 AgentScope 两套并行演进（两套各自演进 = 行为不可复现）。
3. **门禁不得误伤自研 harness**：`harness-contract-check.sh` 的扫描范围因此收窄为「引用 `io.agentscope` 或 `RuntimeContext` 的 `.java`」——自研 harness 内部大量 `+ ":" +` 属于自身 run-state 命名空间，不是四维隔离键违规。

## 验证

```bash
# 契约门禁（四维键收口 / builder 必备项 / 凭据纪律 / 依赖钉版）
bash .claude/skills/agentscope-harness/scripts/verify.sh

# PoC 分支实证复跑（错峰 + 单模块 + 不带 -am 不带 clean，见项目 AGENTS.md 假红陷阱）
export PATH="$HOME/tools/maven/bin:$PATH"; export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"
cd /Users/mac/Documents/ruoyi-ai/.worktrees/poc-agentscope-kernel
mvn -o -pl ruoyi-modules/ruoyi-chat -Dtest=AgentScopeKernelPocIT test
```

隔离类实证的最小判据（✅ G3 口径：正例 2 + 负例 6）：两个不同四维组合必须落**不同** `slotId` 行；A 桶内容**不得**出现在 B 桶（`rawAgentState` 全文比对 + `stateStore.get(...)` 回读双路）；注入段（含 `':'` / `..`）必须被拒。**只测"能写能读"不叫隔离实证。**

## 来源

- ✅ 本仓 `poc/agentscope-kernel`：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/{KernelScopeKey,PocKernelSupport,PocSseController}.java`、`src/test/.../{AgentScopeKernelPocIT,AgentScopeRagPocIT,AgentScopeStreamingPocIT,PocSseApplication}.java`、`ruoyi-modules/ruoyi-chat/pom.xml`、`scripts/check-langchain4j-ratchet.sh`、`scripts/baselines/langchain4j-count.json`（2026-09-28 现查；C7 实测：okhttp 5.3.2 钉在该树根 pom、`banDuplicateClasses` 在 `ruoyi-modules/ruoyi-chat/pom.xml:287`、棘轮基线 `count: 111`）
- ✅ 本仓自研 harness（对照表事实源）：`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness/`——17 子包，main 254 + test 1 个 `.java`；表中引用的 13 个类名已逐一 `find` 现查存在（2026-09-28）。早期文档写的「261 文件」是把 `service/coding` 下非 harness 文件算进去了，已订正。
- 📄 `/v2/zh/docs/harness/{workspace,skill,filesystem,sandbox,compaction,plan-mode,memory,subagent,architecture,channel}`、`/v2/zh/docs/building-blocks/{context,permission-system,middleware,tool,message-and-event,model,agent}`、`/v2/zh/docs/others/going-to-production`、blog 01~04
- 项目雷区：根 `AGENTS.md`（假红/假绿陷阱、Maven 与 JDK 路径、真库 13306、凭据纪律）
