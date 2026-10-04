# AgentScope 2.0.3 废弃 API 迁移影响清单

**归档日期**：2026-10-04
**状态**：仅建档，**未动任何代码**。本清单不构成本轮"代码异味清理"的执行项。
**结论一句话**：本仓在用的 **9 个** AgentScope 类型被官方标记 `@Deprecated(forRemoval=true)`，
跨 chat 内核 / IPD agent 内核 / RAG 检索 / 审计可观测 四条路径，共 9 个活代码文件。

> **计数修正（2026-10-04 交叉验证后）**：初稿写作时按 import 静态检索列为 7–8 个，
> 经第二路审计用 `javac -Xlint:all` 真实编译三个模块（这是"谁在调用待删除 API"的权威口径，
> 它抓到了纯 import 检索漏掉的全限定名写法）后更正为 **9 个**，补入
> `core.rag.model.DocumentMetadata`。以本节的 9 个为准。
这是一笔**框架迁移债**，需要单独立项论证，不应混进机械清理。

---

## 一、实证依据（非推测）

本仓锁定版本：根 `pom.xml` → `<agentscope.version>2.0.3</agentscope.version>`。
实际查询的制品：

```
/Users/mac/.m2/repository/io/agentscope/agentscope-core/2.0.3/agentscope-core-2.0.3.jar
（824469 字节 —— 确认为本仓锁定版本，非 2.0.4-SNAPSHOT 源码镜像）
```

`javap -v` 读出的类级注解原文：

```
io.agentscope.core.hook.Hook
  RuntimeVisibleAnnotations:
    java.lang.Deprecated(
      forRemoval=true
      since="2.0.0"

io.agentscope.core.memory.LongTermMemory
  RuntimeVisibleAnnotations:
    java.lang.Deprecated(
      forRemoval=true
      since="2.0.0"
```

**`forRemoval=true` 的含义**：官方已排期删除，当前版本仍可用。
本仓锁在 2.0.3，故**今天删不得**；但这是一条有期限的债。

> ⚠️ 本清单**不包含**替换 API 的名称。审计只确认了同 jar 内存在若干候选
> （`LegacyHookDispatcher`、`LongTermMemoryMode`、`StaticLongTermMemoryHook`），
> 未验证其语义等价性。**迁移前必须查 2.0.3 官方迁移文档，禁止按名字猜。**

---

## 二、受影响的活代码位置（9 文件 / 2 模块）

### 2.1 审计与可观测链路 —— 迁移时优先级最高

| 文件 | 涉及废弃类型 |
|---|---|
| `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/observability/AgentScopeAuditHook.java` | `hook.*`（通配导入，含 `Hook`/`HookEvent`/`ErrorEvent`） |
| `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/observability/AgentScopeObservabilityConfig.java` | `hook.Hook` |

**硬约束**：这两处承载审计链。迁移时**审计链不得中断，也不得静默降级**——
若替换过程中遗漏事件类型，审计会出现"看起来正常但少记"的静默失效。

### 2.2 Agent 内核

| 文件 | 涉及废弃类型 |
|---|---|
| `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeChatKernel.java` | `hook.Hook`（字段注入 + 构造注入） |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java` | `hook.Hook`（字段注入 + 构造注入） |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/config/ProjectAgentConfiguration.java` | `hook.Hook`（`@Qualifier("agentScopeAuditHook")` 注入点） |

### 2.3 长期记忆

| 文件 | 涉及废弃类型 |
|---|---|
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectScopedLongTermMemory.java` | `memory.LongTermMemory`（`implements`） |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentLongTermMemoryMiddleware.java` | `memory.LongTermMemoryTools` |

### 2.4 RAG 检索

| 文件 | 涉及废弃类型 |
|---|---|
| `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/knowledge/retriever/CustomVectorRetriever.java` | `rag.Knowledge`、`rag.model.Document`、`rag.model.DocumentMetadata`、`rag.model.RetrieveConfig` |
| `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/knowledge/retriever/MultiKnowledgeAugmentorFactory.java` | `rag.model.Document`、`rag.model.RetrieveConfig` |

### 2.5 测试侧（迁移时需同步）

- `ruoyi-modules/ruoyi-chat/src/test/java/org/ruoyi/chat/poc/kernel/AgentScopeRagPocIT.java`
- `ruoyi-modules/ruoyi-chat/src/test/java/org/ruoyi/observability/AgentScopeAuditHookTest.java`
- `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/config/ProjectAgentShutdownConfigurationTest.java`

> 说明：`ruoyi-modules/ruoyi-ipd/src/main/java/.../AgentScopeProjectAgentKernel.java`
> 另有一个 `auditHook` 字段；`Hook` 的全部出现共 10 处、分布 6 个文件（含 docs 下的归档副本）。

---

## 三、为什么必须单独立项

1. **跨 4 个子系统**，且其中一条是审计链，有"不得中断、不得静默降级"的硬约束。
2. **官方迁移路径未经验证**。按名字猜替换 API 是本仓明令禁止的做法。
3. **与"清理未用 import / 死变量"不是同类工作**。混在一起做，会把一次需要论证的框架换代
   伪装成顺手清理，风险与工作量都被低估。
4. **本仓此前没有任何机制能发现这类债**。根 `pom.xml` 原先未开 `-Xlint:deprecation`，
   本次发现依赖审计绕过 Maven 增量、直接用 javac 强编全部主源码。
   （该开关已于同日补上，见 `pom.xml` 的 `<compilerArgs>`。）

---

## 四、立项前需要先确定的事

以下问题本清单**无法回答**，属于立项时必须先解决的：

- 2.0.3 官方推荐的 `Hook` 替代是什么？语义是否等价？审计事件是否一一对应？
- `LongTermMemory` / `LongTermMemoryTools` 的官方替代路径，与
  `ProjectScopedLongTermMemory` 的 `implements` 关系如何改造？
- `rag.Knowledge` / `Document` / `RetrieveConfig` 是否有直接替代，还是 RAG 接入层整体重写？
- 升级 AgentScope 版本的代价（okhttp 全家钉版 + `banDuplicateClasses` 门禁）？
  是否"随版本升级一并迁移"比"原地替换废弃 API"更划算？

---

## 五、交叉验证与补充约束（第二路独立审计）

本节内容来自一次独立复核，**用 `javap` 独立读同一 jar，结论与第一节一致**
（`Hook` / `LongTermMemory` 均为类级 `forRemoval=true, since="2.0.0"`）；
但它用更硬的仪器补出了三条本文其余部分没有的约束，立项时必须一并遵守。

### 5.1 废弃是「整包」级的，但有两个包不能按包名一刀切

对 `agentscope-core-2.0.3.jar` 全量 1225 个 class 扫描后：

```
io.agentscope.core.hook            20/20   整包废弃
io.agentscope.core.hook.recorder    1/1    整包废弃
io.agentscope.core.memory           8/8    整包废弃
io.agentscope.core.rag              4/4    整包废弃
io.agentscope.core.rag.model        3/3    整包废弃
io.agentscope.core.agent.user       4/4    整包废弃
io.agentscope.core.agent            5/12   ← 部分废弃，不可按包名一刀切
io.agentscope.core.tool             4/38   ← 部分废弃，不可按包名一刀切
io.agentscope.core.tracing          3/4    ← 部分废弃
```

**含义**：`hook` / `memory` / `rag` / `rag.model` 是整包换血；
而 `agent` / `tool` 只有个别类型废弃（`agent`：`Event`/`EventType`/`EventSource`/`StreamableAgent`/`StreamingHook`；
`tool`：`ContextStore`/`DefaultContextStore`/`ToolExecutionContext`/`ToolExecutionContextProvider`）。
**如果按包名制定迁移范围，会误伤 `agent` / `tool` 里大量未废弃的类。**

### 5.2 官方 harness 自身仍在引用废弃的 hook 包

`HarnessAgent$Builder.hook(Hook)` 方法**本身未标废弃**，且 harness jar 的二进制里仍引用 `hook` 包。
**含义**：`Hook` 不是"用法错误"，而是官方尚未完成的自迁移。替换路径不能从本仓单方面推导。

### 5.3 权威口径是编译器，不是人工清单

第二路审计用 `javac -Xlint:all` 真实编译 ipd 主源码 / chat 主源码 / chat 测试三个模块，
`[removal]` 告警即"谁在调用待删除 API"。**该手段抓到了纯 import 静态检索漏掉的调用点**
——`ProjectAgentConfiguration.java:246` 用的是全限定名 `io.agentscope.core.hook.Hook`，
没有 import 语句，只做 import 扫描看不见。

**因此本清单的位置可能仍有遗漏，立项时须以 `javac -Xlint:all` 的输出为准复核一遍。**

---

## 六、本清单的证据边界

- 位置清单：初稿来自静态检索（`rg`，排除 `target/`、`.codex/`、`.harness/`），
  经 §5.3 的方法复核后确认存在遗漏，**以编译器 `[removal]` 输出为最终口径**。
- 废弃状态来自 `javap -v` 读真实 2.0.3 jar 的类级注解，**非文档转述、非记忆**，
  且经两路独立复核一致。
- 制品确认为远端解析的正式版（jar 内 `pom.properties` 写 `version=2.0.3`，
  `_remote.repositories` 标 `>public=`，m2 内无 2.0.4 制品）；
  **SNAPSHOT 源码镜像确实存在于 `/Users/mac/Documents/agentscope-java`（`<revision>2.0.4-SNAPSHOT`），
  是已知污染源，本清单全程未使用它。**
- 编译告警覆盖面：仅 3 个模块、约 1165/2276 个源文件（约 51%），**其余模块未编译，不可当作 0**。
- `this-escape` 类别在 javac 17 下无法检测（JDK 21 才有），**不是"干净"**。
- **未做**：替换 API 的存在性验证、语义等价性验证、运行时行为验证。
