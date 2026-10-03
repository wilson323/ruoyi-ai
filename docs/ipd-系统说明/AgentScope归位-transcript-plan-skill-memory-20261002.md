# AgentScope 归位方案：transcript / plan-mode / skill / memory

> 日期：2026-10-02
> 范围：把两个装配点显式 `disable*` 掉的四类官方能力，逐项评估「归位官方」的可行性与代价，给出可落地步骤。
> 纪律声明：**本文档只做「方案 + 实证」，未修改任何 `src/` 下文件、未改 `pom.xml`。** 所有官方 API 结论来自对 `/Users/mac/Documents/agentscope-java` 工作树的实读；读不到的一律标注「未实证」。

---

## §0 结论速览

| 项 | 官方现成度 | 归位判定 | 一句话理由 |
|---|---|---|---|
| **transcript** | 高（Store + Middleware + Builder 开关齐全） | **建议归位**，但必须先解决隔离键收口 | 官方 `TranscriptMiddleware` 完全走 `WorkspaceManager`，与项目 Redis `AgentStateStore` **不冲突**；唯一风险是 key 命名空间多一处手拼 |
| **plan-mode** | 高（三件套齐全） | **建议归位** | 项目当前**完全没有任何 plan-mode 等价物**（两个 kernel 都无 `enablePlanMode`），属纯增量，零替换成本 |
| **skill** | 高（含 `HarnessSkillMiddleware.frozen`） | **不能二选一，须「官方能力增量叠加」** | 官方 frozen 只冻结「集合快照」，**不做 sha256/version/body 密码学校验**；项目的多租户安全门禁必须保留为前置 gate |
| **memory** | 中（hook 侧完备，工具侧 4 个） | **分片归位** | 官方 memory 落**工作区文件**（`MEMORY.md` / `memory/*.md` / `sessions/*.jsonl`），项目自研 `ContextEngine` 落 **Redis AgentState**；两者是「长期记忆 vs 上下文压缩」两条轴，可并存但**不能互相替代** |

**核心风险（贯穿全文）**：官方这四类能力的持久化**全部收敛到 `WorkspaceManager`（文件系统）**，只有 `MemoryConsolidator` 的水位线走 `DistributedStore.baseStore()`。因此**不会与项目的 `AgentScopeRedisStateStores` 抢同一份数据**，真正的冲突面只有一个——**命名空间拼接**（详见 §5）。

---

## §1 官方 API 面实证表

> 「已实证」= 直接读到构造签名 / 方法签名 / Builder 开关的源码行。
> 「仅文档提及」= 只在 `docs/v2/zh/docs/harness/*.md` 中出现，源码未逐一核对。

### 1.1 Transcript

| 类 / 开关 | 实证内容 | 状态 |
|---|---|---|
| `TranscriptStore`（interface，`agent/transcript/TranscriptStore.java:35`） | `String appendSegment(TranscriptRef ref, long seqStart, long seqEnd, String writerId, byte[] jsonl)`（L48-50）；`List<SegmentInfo> listSegments(TranscriptRef)`（L52）；`InputStream readSegment(String segmentKey)`（L55）；`default void compact(TranscriptRef)`（L58）；`default TranscriptStore withRuntimeContext(RuntimeContext)`（L68）；`void delete(TranscriptRef)`（L73） | **已实证** |
| key 布局 | 源码 javadoc 明写：`{tenant}/{agentId}/{sessionId}/events/{seqStart}-{seqEnd}-{writerId}.jsonl`（L30-33） | **已实证** |
| `TranscriptRef`（record，`TranscriptRef.java:22`） | `record TranscriptRef(String tenant, String agentId, String sessionId)`；紧凑构造器：tenant 空 → 填 `"default"`，agentId 空 / sessionId 空 → `IllegalArgumentException`；`String prefix()` = `tenant + "/" + agentId + "/" + sessionId` | **已实证** |
| `FilesystemTranscriptStore`（`:42`） | `public FilesystemTranscriptStore(Path root)`；实现 `listSegments` / `readSegment` / `compact` / `delete`；落盘 `root.resolve(ref.tenant()).resolve(ref.agentId()).resolve(ref.sessionId()).resolve("events")`（L165-168） | **已实证** |
| `ObjectStoreTranscriptStore`（`:41`） | `public ObjectStoreTranscriptStore(AbstractFilesystem filesystem)`；`withRuntimeContext(RuntimeContext)`；`withFilesystem(AbstractFilesystem)` | **已实证** |
| `TranscriptMiddleware`（`middleware/TranscriptMiddleware.java:44`） | 构造 `(WorkspaceManager workspaceManager, TranscriptStore transcriptStore, String tenant)`（L57）；持有 `SessionTranscriptWriter`（L110）；sessionId 取自 `rc.getSessionId()`（L106-107），agentId 取自 `harnessAgent.getAgentId()`（L100） | **已实证** |
| Builder 开关 | `disableTranscript()`（`HarnessAgent.java:2265`）、`transcriptStore(TranscriptStore)`（L2271）、`transcriptTenant(String)`（L2277，默认 `"default"`） | **已实证** |
| 默认 Store 选择逻辑 | `HarnessAgent.java:2560-2569`：有 filesystem → `ObjectStoreTranscriptStore`，否则 `FilesystemTranscriptStore(workspace/.agentscope/transcripts)` | **已实证** |

### 1.2 Plan Mode

| 类 / 开关 | 实证内容 | 状态 |
|---|---|---|
| `PlanModeManager`（`workspace/plan/PlanModeManager.java:39`） | `public final class`；字段 `WorkspaceManager workspaceManager` + `String planDir`（L44-45）；方法 `String enter(AgentState)`、`String planFilePath(AgentState)`、`String writePlan(RuntimeContext, AgentState, String content)` | **已实证** |
| `PlanModeMiddleware`（`middleware/PlanModeMiddleware.java:59`） | 字段 `PlanModeManager manager` + `Predicate<String> readOnlyResolver` + `Set<String> additionalAllowed`（L112-114）；`Mono<String> onSystemPrompt(...)`（L73） | **已实证** |
| `PlanModeTools`（`tool/PlanModeTools.java`） | 内含 3 个 `Mono<ToolResultBlock> callAsync(ToolCallParam)` 实现 → `plan_enter` / `plan_write` / `plan_exit` | **已实证**（方法数实证，工具名来自文档） |
| Builder 开关 | `enablePlanMode()` / `enablePlanMode(boolean)`（L2178/2182）、`planFileDirectory(String)`（L2188）、`allowShellInPlanMode()` / `(boolean)`（L2196/2201） | **已实证** |
| 白名单 9 工具 + HITL 退出 | 文档 `plan-mode.md:14`、`:39-49`、`:113` | **仅文档提及** |
| 状态随 `AgentState` 持久化 | 文档 `plan-mode.md:131`；`PlanModeManager` 类 javadoc L28 佐证（`PlanModeContextState` 存于 AgentState） | **已实证（类 javadoc）** |
| 程序化进出 `agent.enterPlanMode/exitPlanMode/isPlanModeActive` | 文档 `plan-mode.md:135-147`（未在本轮 grep 中逐行核对方法签名） | **仅文档提及** |

### 1.3 Skill

| 类 / 开关 | 实证内容 | 状态 |
|---|---|---|
| `HarnessSkillMiddleware`（`middleware/HarnessSkillMiddleware.java:75`） | 构造重载 ×4（L118/122/127/153）；**`public static HarnessSkillMiddleware frozen(List<AgentSkillRepository>, Toolkit, SkillFilter, SkillVisibilityFilter, MarketplaceStager, ShellPathPolicy)`（L174）**；`isolationScope(IsolationScope)`（L233）；`onSystemPrompt(Agent, RuntimeContext, String)`（L65）；`SkillRuntime runtime()`（L61） | **已实证** |
| ⚠ frozen 语义 | `frozen()` 只是把构造器的 `frozen=true` 传下去 → `this.frozenSkills = <构造期快照>`（L210）；`skillsForCall(ctx)` 直接 `return frozenSkills != null ? frozenSkills : mergeRepositories(ctx)`（L329-330）。**即：冻结「集合」，不校验内容哈希。** | **已实证** |
| `SkillRuntime`（`skill/runtime/SkillRuntime.java:36`） | `public SkillRuntime()` / `public SkillRuntime(SkillPromptBuilder)`（L46/L50）；`currentCatalog(RuntimeContext)`（L64）、`catalogFor(RuntimeContext)`（L70，包级 static）、`AgentTool loadTool()`、`String renderPrompt(SkillCatalog, SkillFilter)` | **已实证** |
| `SkillCatalog`（`skill/runtime/SkillCatalog.java`） | `static empty()` / `static of(List<HarnessSkillEntry> orderedEntries)` / `HarnessSkillEntry get(String)` / `Collection<HarnessSkillEntry> all()` / `List<String> ids()` | **已实证** |
| `HarnessSkillEntry` | `static HarnessSkillEntry of(AgentSkill skill, SkillResources lazyResources)` | **已实证** |
| `SkillPromptBuilder` | `String render(SkillCatalog, SkillFilter)` / `String render(SkillCatalog)` | **已实证** |
| `WorkspaceSkillRepository`（`agent/skill/WorkspaceSkillRepository.java:67`） | 字段 `AbstractFilesystem filesystem` + `String skillsRelativeDir` + `String source`（L76-79）；`getSkill` / `getAllSkillNames` / `getAllSkills()` / `getAllSkills(RuntimeContext)` / `getRepositoryInfo` / `resourcesFor` / `readSkillFile`×2 / `resolveSkillRoot` / `filesystem()` / `skillsRelativeDir()` / `toMarkdown(AgentSkill)` | **已实证** |
| `SkillLoadTool`（`skill/runtime/`） | `getName()` / `getDescription()` / `callAsync(...)` —— 工具名文档为 `load_skill_through_path`（`skill.md:295`） | **已实证（方法）/ 工具名仅文档** |
| `SkillResources` | `Optional<String> read(String rel)` / `readBinary` / `List<String> list()` / `static empty()` | **已实证** |
| `SkillSecurityScanner` | `static ScanResult scan(String name, String skillMd, Map<String,String> resources)`（L262）；`scanSingleFile(String,String)`（L280）；`static boolean shouldAllow(TrustLevel, Verdict)`（L293）；枚举 `Severity`/`Category`/`Verdict`/`TrustLevel` + record `Finding` / `ScanResult` | **已实证** |
| `SkillPromoter` | `Mono<PromotionResult> promote(String name, String reviewerId, RuntimeContext ctx)`（L87）；`record PromotionResult`（L288）含 `approved/deferred/rejected/invalid` 静态工厂 | **已实证** |
| `SkillVisibilityFilter` | `public interface`（L36） | **已实证（类型）**，方法签名未逐行读 → 方法级**未实证** |
| `SkillCurator` | `STATE_PATH = "skills/.curator_state.json"`（L63）、`REPORTS_DIR = "skills/.curator_reports"`（L64）、`applyAutomaticTransitions(Instant)`（L193）、`runOnce(Instant)`（L337）、`runUmbrellaDryRunReport(Instant)`（L258） | **已实证** |
| `SkillUsageStore` | `new SkillUsageStore(AbstractFilesystem)`（L51）、`(fs, String relativePath)`（L55）、`static baseStore(BaseStore)`（L60）、`load()` / `get(String)` / `agentCreatedReport()` | **已实证** |
| `SkillManageTool` / `ProposeSkillTool` | 均有 `getName/getDescription/callAsync`；`SkillManageTool` 另有 `validateName` / `validateContent` / `validateSubFilePath` | **已实证** |
| Builder 开关 | `skillRepository(AgentSkillRepository)`（L1759）、`skillRepositories(List)`（L1769）、`disableDynamicSkills()`（L2118）、`disableDefaultWorkspaceSkills()`（L2129）、`enableSkillManageTool(SkillManageConfig)` / `(boolean)`（L2136/2141）、`enableSkillPromotionGate(SkillPromotionGate, SkillVisibilityFilter)`（L2150）、`environment(String)`（L2157）、`enableSkillCurator(SkillCuratorConfig)`（L2161）、`skillFilter(SkillFilter)`（L2212）、`skillsEnabled(boolean)`（L2217）、`enableSkills(String...)`（L2225）、`disableSkills(String...)`（L2230） | **已实证** |
| 同名优先级 4 层（projectGlobal / 市场 / workspace 共用 / 用户隔离） | 文档 `skill.md:182-197` | **仅文档提及** |
| `<available_skills>` prompt 块格式 | 文档 `skill.md:277-289`；`SkillPromptBuilder.render` 佐证存在 | **已实证（方法）/ 格式仅文档** |

### 1.4 Memory

| 类 / 开关 | 实证内容 | 状态 |
|---|---|---|
| `MemoryConfig`（`agent/memory/MemoryConfig.java`） | 字段：`flushPrompt` / `consolidationPrompt` / `consolidationMaxTokens` / `consolidationMinGap` / `dailyFileRetentionDays` / `sessionRetentionDays` / `flushTrigger`（L151-156）；常量 `DEFAULT_CONSOLIDATION_MAX_TOKENS=4000`（L55）、`DEFAULT_CONSOLIDATION_MIN_GAP=30min`（L58）、`DEFAULT_DAILY_FILE_RETENTION_DAYS=90`（L61）、`DEFAULT_SESSION_RETENTION_DAYS=180`（L64）；`static defaults()` / `static builder()`；Builder 全量 setter 见 §1 源码行 | **已实证** |
| `MemoryConfig.FlushTrigger` | `static always()` / `static never()` / `static throttled(Duration)`（L103/106/109）；`FlushMode mode()` / `Duration minGap()` | **已实证** |
| `MemoryFlushManager`（`:53`） | 字段 `WorkspaceManager` + `Model` + `String flushPrompt`（L91-93）；`Mono<Void> flushMemories(RuntimeContext, List<Msg>)`；`String resolveOffloadPath(RuntimeContext, String agentId, String sessionId)` | **已实证** |
| `MemoryConsolidator`（`:61`） | 构造 `(WorkspaceManager, Model, String consolidationPrompt, int maxMemoryTokens, BaseStore)`（L140）；`Mono<Void> consolidate(RuntimeContext)`；`Instant readWatermark(RuntimeContext)`；水位线优先读 `BaseStore`（L309/346/365/372），无 BaseStore 时降级 | **已实证** |
| `MemoryFlushMiddleware`（`middleware/:87`） | 构造入参：`WorkspaceManager, Model, String flushPrompt, MemoryConfig.FlushTrigger, IsolationScope, PeriodicGate`（`HarnessAgent.java:2584-2592` 实测调用） | **已实证** |
| `MemoryMaintenanceMiddleware`（`middleware/:70`） | 构造入参：`WorkspaceManager, MemoryConsolidator, int dailyFileRetentionDays, int sessionRetentionDays, Duration minGap, IsolationScope, PeriodicGate`（`HarnessAgent.java:2604-2611` 实测调用） | **已实证** |
| `CompactionMiddleware`（`middleware/:62`） | 构造 `(WorkspaceManager, Model, CompactionConfig)`（`HarnessAgent.java:2619`） | **已实证** |
| `CompactionConfig`（`memory/compaction/`） | Builder：`triggerMessages` / `triggerTokens` / `reserved` / `keepMessages` / `keepTokens` / `keepTokensMin` / `keepTokensMax` / `keepTokensRatio` / `summaryPrompt` / `flushBeforeCompact` / `offloadBeforeCompact` / `truncateArgs(TruncateArgsConfig)` / `prune(PruneConfig)` / `model(Model|String)`；`withEffective(int,int)` | **已实证** |
| `ConversationCompactor` | `formatMessagesForSummary` / `filterSummaryMessages` / `pruneToolResults` / `truncateArgs` | **已实证** |
| `ToolResultEvictionConfig` | `defaults()` / `maxResultChars` / `previewChars` / `evictionPath` / `excludedToolNames` | **已实证** |
| 工具 4 个 | `MemoryGetTool(WorkspaceManager)` / `MemorySaveTool(WorkspaceManager)` / `MemorySearchTool(WorkspaceManager, ...)`（`memorySearch(RuntimeContext, String)`）/ `SessionSearchTool(WorkspaceManager, ...)` | **已实证（字段）** |
| `SessionTree`（`memory/session/`） | `setRuntimeContext` / `setTranscriptStore(TranscriptStore, TranscriptRef)` / `append(SessionEntry)` / `buildContext()` / `getAllEntries()` / `getMessageEntries()` / `getContextFile()` / `getLogFile()` | **已实证** |
| `SessionTranscriptWriter` | `String resolveContextPath(RuntimeContext, String agentId, String sessionId)` / `List<SessionEntry> deriveEntries(Msg, String parentId)` | **已实证** |
| `MemoryBackgroundTasks` | `static begin()` / `static end()` / `static awaitQuiescence(long, TimeUnit)` —— 测试/优雅停机用计数器 | **已实证** |
| Builder 开关 | `memory(MemoryConfig)`（L1906）、`disableMemoryTools()`（L2245）、`disableMemoryHooks()`（L2256）、`disableCompaction()`（L1892）、`toolResultEviction(ToolResultEvictionConfig)`（L1916）、`disableToolResultEviction()`（L1921） | **已实证** |
| 三处 LLM 调用（Flush / Consolidation / Compaction summary） | 文档 `memory.md:20-30` | **已实证（源码类一一对应，表格文字来自文档）** |
| 两层记忆文件布局 `memory/YYYY-MM-DD.md` + `MEMORY.md` | 文档 `memory.md:32-50` | **仅文档提及**（本轮未逐一 grep 文件名常量） |

### 1.5 官方 0 引用实证

在 `ruoyi-modules` / `ruoyi-common` / `ruoyi-admin` 全量 `*.java` 中检索 `io.agentscope.harness.agent.*`，结果只有 4 个符号被引用：

```
3  io.agentscope.harness.agent.tools.ToolsConfig
1  io.agentscope.harness.agent.gateway.TurnLease
1  io.agentscope.harness.agent.gateway.SessionTurnGate
1  io.agentscope.harness.agent.gateway.LocalSessionTurnGate
```

`TranscriptStore` / `PlanModeMiddleware` / `SkillRuntime` / `MemoryConfig` / `MemorySearchTool` / `PlanModeManager` / `FilesystemTranscriptStore` / `HarnessSkillMiddleware` / `MemoryFlushMiddleware` / `CompactionMiddleware` / `SessionTree` / `WorkspaceSkillRepository` —— **引用数均为 0**。任务书给定的「实测 0 引用」结论**已复核通过**。

---

## §2 四项能力对账表

差异分类：**【缺】** 官方有、自研无；**【盈】** 自研有、官方无；**【重】** 两侧都有但语义不同。

### 2.1 Transcript

| 维度 | 官方 | 自研（项目现状） | 差异 |
|---|---|---|---|
| 事件落盘 | `TranscriptStore.appendSegment` 不可变 JSONL 分段，key `{tenant}/{agentId}/{sessionId}/events/{seq}-{seq}-{writerId}.jsonl` | **无**（`AgentScopeChatKernel.java:338` / `AgentScopeProjectAgentKernel.java:279` 显式 `disableTranscript()`） | **【缺】** |
| 会话树 | `SessionTree` / `SessionEntry` / `SessionTranscriptWriter` | 无 | **【缺】** |
| 审计读取 | `HarnessTranscriptReader`（143 行，**读的是 Harness 自己的事件流，不是 SDK transcript 通道**） | 已有，但**与官方 transcript 通道无关** | **【重】** |
| 压缩前 offload 原文 | `CompactionConfig.offloadBeforeCompact` → `sessions/<id>.log.jsonl`（永不压缩，供 `session_search`） | `ContextEngine.compact` 只做 token 安全前缀投影，**无 offload 落盘** | **【缺】** |
| 多租户 key | `TranscriptRef(tenant, agentId, sessionId)`，tenant 缺省 `"default"` | 项目四维隔离在 `KernelScopeKey`，**官方通道无 project 维** | **【重】** |
| 与状态库关系 | 走 `WorkspaceManager`（文件系统） | `AgentScopeRedisStateStores`（Redis/Redisson） | **不冲突**，见 §4 |

### 2.2 Plan Mode

| 维度 | 官方 | 自研 | 差异 |
|---|---|---|---|
| 只读阶段强制 | `PlanModeMiddleware` + `Predicate<String> readOnlyResolver` | **无**（两个 kernel 均无 `enablePlanMode`） | **【缺】** |
| 三工具 `plan_enter/write/exit` | `PlanModeTools` | 无 | **【缺】** |
| HITL 退出确认 | 复用权限系统 ASK | 无 | **【缺】** |
| 状态持久化 | `PlanModeContextState` 存 `AgentState` → 随 `AgentScopeRedisStateStores` 走 Redis | — | **【缺】** |
| 计划文件落盘 | `<planDir>/PLAN.md`，只经 `WorkspaceManager`（不直接用 `Files`） | 无 | **【缺】** |
| 权限模式运行时切换 | `setPermissionMode` / `PermissionMode.BYPASS` / `DONT_ASK` | 项目有 `KernelToolGovernance` + `ToolPolicyEngine` 做工具治理，但**不是官方权限模式** | **【重】**，语义相邻，需评估是否合并 |

**结论**：plan-mode 是四项里**唯一零冲突的纯增量**。项目此前完全没有这个概念，IPD 的「先设计后执行」诉求可以直接由官方承接。

### 2.3 Skill

| 维度 | 官方 | 自研 | 差异 |
|---|---|---|---|
| 目录发现 | `WorkspaceSkillRepository`（`AbstractFilesystem` 后端，可远端/沙箱）+ 4 层同名优先级 | `HarnessSkillCatalogFactory`（L37-47）扫 `.agents/skills` / `.claude/skills` / `.codex/skills`，`NOFOLLOW_LINKS` + 深度/体积上限 + 嵌套跳过 | **【重】**（自研更严的安全边界） |
| 加载/注入 | `SkillRuntime` + `SkillCatalog` + `SkillPromptBuilder` → `<available_skills>` 块 + `load_skill_through_path` 按需加载 | `FrozenProjectAgentSkills` **一次性全量拼进 system prompt**（L53-55）；`NativeChatSkills.selectedPrompt` 同为全量拼 | **【重】**：官方懒加载 vs 自研全量注入（token 成本差异显著） |
| 冻结 | `HarnessSkillMiddleware.frozen(...)` = **构造期集合快照** | `FrozenProjectAgentSkills(List<LoadedSkill>)` = 快照 **+ sha256/version/body 三重复核 + fail-closed + 只读 + 审计 marker** | **【盈】**（自研严格更强） |
| 工具 | `HarnessSkillTools`（自研 `activate_skill` / `read_skill_resource`） | 对应官方 `SkillLoadTool` | **【重】** |
| 安全扫描 | `SkillSecurityScanner.scan` + `TrustLevel`/`Verdict`/`Finding` | 自研只有 sha 校验 | **【缺】**（官方可增量叠加） |
| 自学习闭环 | `SkillManageTool` / `ProposeSkillTool` / `SkillCurator` / `SkillPromoter` / `SkillVisibilityFilter` / `SkillUsageStore` | **无** | **【缺】** |
| 多租户 skill 隔离 | `IsolationScope`（SESSION/USER/AGENT/GLOBAL）→ `NamespaceFactory`；USER 档拼 `<userId>/skills/` | 项目 `ProjectAgentSkillCatalog` 按 `resourceRoot + name` 取，隔离靠上层 `KernelScopeKey` | **【重】** |

### 2.4 Memory

| 维度 | 官方 | 自研 | 差异 |
|---|---|---|---|
| **长期记忆 Flush** | `MemoryFlushManager` → `memory/YYYY-MM-DD.md` 追加；触发点 3 个（每轮 / 压缩前 / 溢出兜底），可 `throttled(Duration)` 节流 | **无**（`disableMemoryHooks()`） | **【缺】** |
| **长期记忆 Consolidation** | `MemoryConsolidator` → `MEMORY.md` 整体重写；后台节流默认 30min | 无 | **【缺】** |
| **后台维护** | `MemoryMaintenanceMiddleware`：日流水账归档(90d)、合并、session 清理(180d) | 无 | **【缺】** |
| **对话压缩 Compaction** | `CompactionMiddleware` + `ConversationCompactor` + `CompactionConfig`（token 阈值 / 保留尾部 / 参数截断 / 工具结果裁剪 / offload） | `ContextEngine`(510) + `CompactionControl` + `ExtractiveHarnessSummarizer`(187) + `ContextPins` | **【盈】**：自研有 **pin 保护**、**抽取式摘要（不二次调模型、不臆造）**、**纯上下文域不碰持久化**；官方是 LLM 摘要 |
| **大工具结果卸载** | `ToolResultEvictionConfig`（默认 80k 字符触发） | 无 | **【缺】** |
| **记忆工具 4 个** | `memory_search` / `memory_get` / `memory_save` / `session_search` | 无（`disableMemoryTools()`） | **【缺】** |
| **可观测测试钩子** | `MemoryBackgroundTasks.begin/end/awaitQuiescence` | 无 | **【缺】** |
| 隔离 | `MemoryFlushMiddleware` / `MemoryMaintenanceMiddleware` 构造均吃 `IsolationScope` | `KernelScopeKey` 四维 | **【重】** |

**能力轴切分结论**：
- **长期记忆（Memory）** = 官方独有、自研全缺 → **归位官方**收益大、代价小。
- **上下文压缩（Compaction）** = 自研更严谨（pin + 抽取式 + 不越层持久化） → **保留自研**，但可把官方的 `CompactionConfig` 阈值/截断**参数形态**作为配置外化参考，或让官方 `offloadBeforeCompact` 承担审计留档。

---

## §3 `FrozenProjectAgentSkills` 归属判断（重点）

### 3.1 它到底做了什么（实读结论）

`ruoyi-modules/ruoyi-ipd/.../agent/kernel/FrozenProjectAgentSkills.java`（82 行，`final class`，**包级私有**，`implements AgentSkillRepository, MiddlewareBase`）在**构造函数**里完成全部安全校验：

| 步骤 | 行号 | 行为 | 失败 |
|---|---|---|---|
| 身份校验 | L30-34 | name 匹配 `[A-Za-z0-9_-]+`；sha256 匹配 `[a-fA-F0-9]{64}`；version 非空；快照内 name 不重复 | `IllegalArgumentException("Invalid frozen skill identity")` |
| **sha256 复核** | L36-40 | 从 classpath `ipd-skills/{name}/SKILL.md` 读**原始字节**求 SHA-256，与锁定值比对 | `IllegalStateException("Selected skill resource is missing")` / `"Selected skill hash changed"` |
| **version / body 复核** | L41-47 | `source.getSkill(name)` 后比 name / `getMetadataValue("version")` / 正文非空 / `getSkillContent()` 全等 | `IllegalStateException("Selected skill version or body changed")` |
| 脱敏重建 | L48-50 | `skill.toBuilder().resources(Map.of()).originDir(null).source("classpath:"+ROOT).build()` —— 清空 resources 与文件系统 origin | — |
| 审计 marker | L52/L61 | identity 累加 `name@version:sha256\n`；marker = `<!-- selected-skills:{sha256(identity)} -->` | — |
| 幂等注入 | L64-67 | `onSystemPrompt` 里 marker 已存在则原样返回，否则 `base + "\n" + prompt` | — |
| 只读 | L72/73/77 | `save`/`delete` 抛 `UnsupportedOperationException`；`isWriteable()` 恒 false | — |

上游 `ProjectAgentSkillCatalog`（161 行）提供 `LoadedSkill(name, version, sha256, content)` 快照，四步可用性判定：`skillExists` → 读正文 → **原始字节 sha 与清单一致** → 正文非空；不通过返回明确 reason（`"Skill 完整性校验失败（sha256 与清单不一致）"` 等），**不以空正文降级**。

### 3.2 官方能不能替代？——**不能**

逐条对比官方最接近的 `HarnessSkillMiddleware.frozen(...)`：

| 项目要求 | 官方 frozen 能力 | 差距 |
|---|---|---|
| sha256 原始字节复核 | ❌ 无。`frozen()` 只做 `this.frozenSkills = <构造期快照 Map>`（L210），`skillsForCall` 直接返回该 Map（L329-330） | **不可替代** |
| version 复核 | ❌ 无。官方 `AgentSkill` 元数据无锁定版本概念 | **不可替代** |
| body 逐字复核 | ❌ 无 | **不可替代** |
| fail-closed（资源缺失即抛） | ⚠ 部分。官方走 `mergeRepositories` 逐轮重读，缺资源只是「不出现」，**不抛异常**——是 fail-open | **语义相反** |
| 只读（禁写） | ✅ `isWriteable` 概念存在（官方 `WorkspaceSkillRepository` 有可写变体，但那是给 `skill_manage` 用的） | 可对齐 |
| classpath-only 来源 | ✅ 官方支持 Classpath 市场（文档 `skill.md:113`） | 可对齐 |
| 审计 marker 幂等 | ❌ 无。官方是 `<available_skills>` 块 + `load_skill_through_path` 懒加载，无「本次选定集合」指纹锚 | **不可替代** |
| 多租户（每 project/person 独立 skill 集） | ⚠ 官方按 `IsolationScope.USER` 拼 `<userId>/skills/` 目录，**粒度是 user 不是 project×person** | **粒度不足** |

**判定：不能替代。** 三条硬理由：
1. **密码学校验无等价物** —— 官方无 sha256/version/body 锁定概念。
2. **失败语义相反** —— 项目要 fail-closed（资源被动过 → 整轮拒绝），官方是 fail-open（读不到就少一个 skill）。
3. **隔离粒度不匹配** —— 官方最细到 `IsolationScope.USER`（单一 userId 段），项目要 `project × user × agent` 复合（`KernelScopeKey`）。

### 3.3 推荐组合方式（保留自研 + 官方增量叠加）

**定位改写**：`FrozenProjectAgentSkills` 从「skill 装配器」降级为「**skill 授权闸门（authorization gate）**」；把「装配/渲染/加载」交给官方。

```
                    ┌──────────────────────────────────────────┐
   ProjectAgentSkillCatalog.load(name)
        │  Optional<LoadedSkill>  (name,version,sha256,content)
        ▼
   ┌──────────────────────────────────────────┐
   │  FrozenSkillGate  ← 保留自研（只做校验）   │  sha/version/body 三重复核
   │  （改造自 FrozenProjectAgentSkills）      │  fail-closed
   └────────────────┬─────────────────────────┘
                    │ Optional.empty() → 整轮拒绝（抛）
                    ▼
   ┌──────────────────────────────────────────┐
   │  HarnessSkillMiddleware.frozen(...) 官方  │  集合快照 / 幂等
   │  .skillRepository(gateRepository)         │  <available_skills> 渲染
   │  .disableDynamicSkills()                  │  load_skill_through_path
   └────────────────┬─────────────────────────┘
                    ▼
        SkillRuntime → SkillCatalog → SkillPromptBuilder
```

**具体动作（分 3 步，每步可独立回滚）**：

1. **拆分职责**（改造 `FrozenProjectAgentSkills`，不删）
   - 保留构造函数的**全部校验逻辑**（L30-47）原样不动。
   - 把 `onSystemPrompt`（L64-67）**删除**，不再自己拼 prompt。
   - 保留 `AgentSkillRepository` 的 9 个 `@Override`（`getSkill` / `getAllSkillNames` / `getAllSkills` / `skillExists` / `getRepositoryInfo` / `getSource` / `setWriteable` / `isWriteable` / `save` / `delete`）——**它已经是合法的官方 `AgentSkillRepository`**，可直接喂给 `HarnessSkillMiddleware.frozen(...)`。
   - 审计 marker 改为在 `getRepositoryInfo()` 的 name / source 字段里携带 `<!-- selected-skills:{sha} -->`，随官方 `<available_skills>` 块一起进 prompt，幂等性由「每轮重算」天然保证。

2. **接官方渲染**（`AgentScopeProjectAgentKernel.java:268`）
   - 把 `.middleware(selectedSkills)` 改为 `.middleware(HarnessSkillMiddleware.frozen(List.of(selectedSkills), toolkit, SkillFilter.only(names), null, null, ShellPathPolicy.noShell()))`。
   - **同时**保留 `.skillsEnabled(false)` → 改为 `.enableSkills(selectedNames...)`（L2225），用 `SkillFilter.only(...)` 替代 `SkillFilter.none()`。
   - **保留** `.disableDynamicSkills()`（L285）——它正是官方触发 `frozen` 模式的开关（`HarnessAgent.java:2929-2934`：`disableDynamicSkills ? frozen(...) : new ...`）。

3. **叠加官方安全能力**（增量，零风险）
   - 装配前对每个 `LoadedSkill.content` 调 `SkillSecurityScanner.scan(name, md, resources)`（L262），`Verdict` 非 allow 时 fail-closed。
   - 装 `.environment("prod")`（L2157）+ `.enableSkillPromotionGate(...)`（L2150），为后续自学习闭环留闸。
   - `ProposeSkillTool` / `SkillManageTool` **暂不启用**（`enableSkillManageTool` 会把官方只读 repo 升级为可写变体，与项目的「只读冻结」语义冲突）——列入 §6 后续阶段。

**保留不可删的部分**：`ProjectAgentSkillCatalog`（sha 校验源头）、`FrozenProjectAgentSkills` 的校验段。**可删**：L53-55 的全量 prompt 拼装、L64-67 的 `onSystemPrompt`。

---

## §4 状态存储层冲突风险

### 4.1 三套存储的真实边界

| 层 | 载体 | 存什么 | 隔离维度 |
|---|---|---|---|
| **A. 项目状态库** | `AgentScopeRedisStateStores` → `RedisAgentStateStore`（Redisson + `Redisson351Adapter` 归一 `ReturnType.INTEGER`→`long`）；外面套 `FailClosedAgentStateStore` | `AgentState`：对话历史、plan-mode 状态、Harness 游标 | keyPrefix 常量 `"ruoyi:agentscope:chat:"`（`AgentScopeChatKernel.java:90`）/ `"ipd:project-agent:state:"`（`ProjectAgentConfiguration.java:117`）；slot = `userId + ":" + sessionId`，两段均来自 `KernelScopeKey` 复合串 |
| **B. 官方 transcript** | `TranscriptStore`（`FilesystemTranscriptStore` / `ObjectStoreTranscriptStore`） | 不可变 JSONL 事件分段 | key `{tenant}/{agentId}/{sessionId}/events/…`，落 **workspace 目录** |
| **C. 官方 memory** | `WorkspaceManager`（文件系统）；**仅** consolidation 水位线走 `DistributedStore.baseStore()`（`MemoryConsolidator.java:365/372`，`WATERMARK_NAMESPACE` / `WATERMARK_KEY`） | `MEMORY.md`、`memory/YYYY-MM-DD.md`、`sessions/*.log.jsonl` | workspace 路径（项目已按 `workspaceSegment` fail-closed）+ `IsolationScope` |

### 4.2 会不会打架？——**结论：不会抢数据，但会抢「同一份事实的两个真相源」**

1. **物理层无冲突**：B/C 落文件系统，A 落 Redis。`MemoryConsolidator` 的 `BaseStore` 是**可选注入**（`HarnessAgent.java:2597-2600`：`distributedStore != null ? baseStore() : null`），项目当前没给 `HarnessAgent` 配 `DistributedStore`，所以连这一处也不会碰 Redis。
2. **逻辑层有冲突风险（真问题）**：
   - `FailClosedAgentStateStore`（L21-27）只拦 `saveIfVersion` 的 `UNVERSIONED` 冲突，**对 transcript 分段写入完全无感知**。官方 `appendSegment` 是「调用方自选 seq、writerId 防撞」的追加写，**没有 CAS**。若同一 `slotId` 上并发两个节点写 transcript，会出现 seq 重叠 / 分段交错，而 A 层会认为状态没冲突。
   - 官方 memory 的 `MEMORY.md` 是**每轮整体重写**（consolidation）。若两个副本同时跑后台维护，**后写覆盖先写，无版本门禁**——这正是项目当初建 `FailClosedAgentStateStore` 想解决的那类问题，但官方侧**没有对应机制**。
   - 官方 transcript 写入路径 `ObjectStoreTranscriptStore` 走 `AbstractFilesystem` + `NamespaceFactory`（`IsolationScope`），**不经过 `AgentStateStore` 的任何锁**。

### 4.3 统一收口建议（三层，不引入第四套）

**建议 1（必做）：`FailClosedAgentStateStore` 的 fail-closed 语义扩到官方写入前置**
在两个 kernel 的 `scope.toRuntimeContext()` 之后、agent 调用之前，加一道 `TurnGate` 级别的**单写者校验**：已存在 `TURN_GATE.acquire(scope.slotId())`（`AgentScopeChatKernel.java:203/264`）——确认它覆盖的是整个 call 生命周期；只要成立，transcript/memory 的并发写就已经被串行化，**无需额外加锁**。若不成立，则必须扩 gate 覆盖面。

**建议 2（必做）：官方持久化一律关掉 BaseStore 注入**
不要给 `HarnessAgent` 配 `DistributedStore`，让 `MemoryConsolidator` 的 `baseStore == null` 降级路径生效（`MemoryConsolidator.java:346` 的 null 分支），水位线落工作区文件。**理由**：避免官方内存水位线混进 Redis 命名空间，形成第 4 套 key 拼接。

**建议 3（强烈建议）：把「谁拥有哪类事实」写成显式契约**

| 事实 | 唯一真相源 | 官方通道的角色 |
|---|---|---|
| 对话历史 / 游标 / plan-mode 状态 | **A（Redis）** | 只读消费（官方 compaction 从 `messages` 读，写进 A） |
| 事件审计流 | **B（transcript）** | 唯一写入者 |
| 跨会话长期记忆 | **C（工作区 MEMORY.md）** | 唯一写入者 |
| 上下文压缩后的投影 | **A（Redis）+ 自研 ContextEngine** | 官方 `CompactionMiddleware` **不启用**（见 §2.4） |

**建议 4（可选）：`MemoryBackgroundTasks` 用作测试闸门**
官方提供 `begin()/end()/awaitQuiescence(timeout, unit)`（L43/50/69）静态计数器，验收脚本可用它判定后台 flush/consolidation 已静默，再读 DB/文件做真实验收——避免「断言时后台还在写」的 flaky。

---

## §5 隔离键约束

### 5.1 现状（实读）

`ruoyi-modules/ruoyi-chat/.../chat/kernel/KernelScopeKey.java`（82 行）**已是唯一收口点**：

- `public static Scope of(projectId, userId, agentId, sessionId)`（L53-64）
  - 复合 userId = `"p" + projectId + ":u" + userSegment`（L61）
  - 复合 sessionId = `"a" + agentId + ":s" + sessionId`（L62）
  - `slotId()` = `userId + ":" + sessionId`（L38-40）→ 最终 `p{projectId}:u{userId}:a{agentId}:s{sessionId}`
- `validateSegment`（L66-81）**fail-closed**：含 `':'` → `"(composite key injection)"`；含 `".."` → `"(path traversal injection)"`；required 段空白 → `"(must not be blank)"`。projectId / agentId / sessionId 全部 required；userId 可空 → 降级 `ANONYMOUS_USER_SEGMENT = "__anon__"`（L25）并跳过校验。

### 5.2 官方会拼哪些命名空间（风险面）

| 官方机制 | 拼接代码位置 | 风险 |
|---|---|---|
| `TranscriptRef.prefix()` | `TranscriptRef.java:39` → `tenant + "/" + agentId + "/" + sessionId`；`FilesystemTranscriptStore.java:165-168` 直接 `root.resolve(tenant).resolve(agentId).resolve(sessionId).resolve("events")` | **高**：`tenant` 由 Builder 的 `transcriptTenant(String)` 传入，**当前默认 `"default"`**。若不显式设置，**所有 project / user 的 transcript 会写进同一目录** |
| `HarnessAgent` 内部 agentId | `TranscriptMiddleware.java:100` 取 `harnessAgent.getAgentId()`（**常量字符串**，非复合键） | **中**：agentId 维在 transcript 通道被压成常量 |
| `IsolationScope.toNamespaceFactory()` | `IsolationScope.java`：USER → `rc.getUserId()`（**只取单段**，不解析复合串）；SESSION → `rc.getSessionId()` | **中**：项目塞进 userId 的是 `p{project}:u{user}` 复合串，官方会原样当目录名用（含 `:`），**跨平台路径不安全**（Windows 非法） |
| `WorkspaceSkillRepository` | 字段 `AbstractFilesystem filesystem` + `String skillsRelativeDir` | **低**：`skillsRelativeDir` 是 workspace 相对路径（如 `"skills"`），**不拼隔离键**；隔离由 workspace 根决定 |
| `SkillCurator` | `STATE_PATH = "skills/.curator_state.json"`、`REPORTS_DIR = "skills/.curator_reports"` | **低**：固定相对路径 |
| `SkillUsageStore` | `DEFAULT_RELATIVE_PATH = "skills/.usage.json"` | **低** |

### 5.3 如何保证「不产生第二处手拼隔离键」——四条硬约束

**约束 1：复合隔离键的构造逻辑一律不加在 `KernelScopeKey` 之外。**
官方 `transcriptTenant(...)` / `IsolationScope` 需要的是「已经拼好的字符串」，它由**调用方**（即我们）提供。所以正确做法不是禁止官方拼，而是**让官方拿到 `KernelScopeKey` 亲手产出的值**。

**约束 2（落地做法）：在 `KernelScopeKey` 内新增两个方法，作为官方通道的唯一适配器。**

```java
// 加在 ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/KernelScopeKey.java
// 这是本次归位唯一允许新增手拼逻辑的位置

/** transcriptTenant(...) 的唯一取值来源：project × user 二维，路径安全编码。 */
public String transcriptTenant() {
    return encode("p" + projectId) + "/" + encode("u" + userSegment);
}

/** 供 IsolationScope / workspace 使用的路径安全 user 段（把 ':' 换成 '_'）。 */
private static String encode(String raw) {
    // fail-closed：残留 ':' / '/' / '\' / ".." 一律拒绝
    if (raw.indexOf(':') >= 0 || raw.indexOf('/') >= 0
            || raw.indexOf('\\') >= 0 || raw.contains("..")) {
        throw new IllegalArgumentException("transcript segment must be path-safe: " + raw);
    }
    return raw;
}
```

> 设计要点：把 `':'` 换成路径安全字符**必须**发生在 `KernelScopeKey` 内部。**若在 kernel 里写 `"p" + projectId + ":u" + user` 就地替换，就产生了第二处手拼点，约束失效。**

**约束 3：`IsolationScope` 不用 USER 档，用 SESSION 档或整体不启用。**
- 官方 `IsolationScope.USER` 会把 `rc.getUserId()`（复合串，含 `:`）原样当目录名 → Windows 非法字符、跨平台不一致。
- 推荐：memory middleware 走 `IsolationScope.SESSION`（`sessionId` 也是复合串，同样含 `:`）——**所以必须配合约束 2**：让 `Scope.toRuntimeContext()` 暴露一个**路径安全版** sessionId 给官方专用，而给 Redis `AgentStateStore` 的仍是原复合串。即 `Scope` 需提供两个视图：
  - `scope.toRuntimeContext()` —— **现有**，带复合 `:`，供 `AgentStateStore` / `TurnGate`（保持 key 布局不变，避免存量数据迁移）
  - `scope.toFilesystemRuntimeContext()` —— **新增**，`:` → `_`，供 transcript / memory / skill 的文件系统通道
- 若不打算加这个视图，**最简做法是不启用官方 memory 的文件系统持久化**（`disableMemoryHooks()` 保持），只归位 transcript（transcript 有专用的 `transcriptTenant` 开关，不需要动 RuntimeContext）。

**约束 4：静态检查门禁（新增 grep 脚本，防回归）。**
禁止在 `src/main/java` 中出现下列模式（`KernelScopeKey.java` 自身除外）：

| 禁止模式 | 理由 |
|---|---|
| `"p" + projectId` / `"a" + agentId` / `":s" + sessionId` | 手拼四维隔离键 |
| `transcriptTenant(` 的字面量实参 | 必须来自 `KernelScopeKey` |
| `new TranscriptRef(` 的字面量 tenant/agentId | 同上 |
| `IsolationScope.USER` | 复合 userId 含 `:`，路径不安全 |
| `resolve("skills")` 之类硬编码 skill 相对路径（除官方常量镜像） | skill 目录应由 workspace 根决定 |

建议落成 `scripts/check-isolation-key-scope.sh`（可复用现有 `scripts/check-*.sh` 门禁范式 + `*_FAIL_SEED` 自证能红），与 §6 的 `harness-contract-check.sh` 一起跑。

---

## §6 自研可删清单 + 落地步骤

### 6.1 可删清单（按风险从低到高）

| # | 文件 / 代码段 | 行数 | 可删条件 | 替代者 | 风险 |
|---|---|---|---|---|---|
| D1 | `FrozenProjectAgentSkills.onSystemPrompt`（L64-67） | 4 | §3.3 步骤 2 完成 | `HarnessSkillMiddleware.frozen` + `SkillPromptBuilder` | **低** |
| D2 | `FrozenProjectAgentSkills` 的 `prompt` 字段与 L53-55/L62 拼装 | ~5 | 同上 | 同上 | **低** |
| D3 | `AgentScopeProjectAgentKernel.java:287` 的 `.skillsEnabled(false)` | 1 | 换成 `.enableSkills(names...)` | `SkillFilter.only(...)` | **低** |
| D4 | `NativeChatSkills.selectedPrompt`（L23-46）全量拼装（**仅当** chat 侧也接入官方 skill 通道） | ~24 | 官方 skill 通道在 chat kernel 装配完成 | `SkillPromptBuilder` | **中**（chat 侧当前 `disableDynamicSkills` + `disableDefaultWorkspaceSkills`，无等价物） |
| D5 | `HarnessSkillTools` 的 `activate_skill`（L14-18） | 5 | 官方 `SkillLoadTool` 接入 coding-harness | `load_skill_through_path` | **中**（需确认 coding-harness 的 Toolkit 与官方 toolkit 兼容性） |
| D6 | `HarnessSkillCatalogFactory` 的 frontmatter 解析（`parse` L143-165 / `unquote` L167） | ~25 | 官方 `WorkspaceSkillRepository` 接管发现 | 官方 parser | **中高**（自研的 `NOFOLLOW_LINKS` + 深度/体积上限比官方严，**删前必须逐条确认官方等价**） |
| D7 | 官方 compaction 与 `ContextEngine` 二选一后的冗余 | 待定 | §2.4 判定保留自研 compaction → **不删** | — | — |

**不可删（红线）**：
- `ProjectAgentSkillCatalog` 全部（sha 校验源头）
- `FrozenProjectAgentSkills` 的构造校验段 L30-47
- `KernelScopeKey` 全部
- `AgentScopeRedisStateStores` / `FailClosedAgentStateStore` 全部
- `ContextEngine` / `ExtractiveHarnessSummarizer` / `ContextPins`（自研压缩带 pin 保护 + 抽取式摘要，官方无等价）

### 6.2 落地步骤（每步给文件 + 验证方式）

> **验收纪律：真 HTTP + 真 DB，不用 Mock 替业务。** 单测只用于契约锁定，不作为验收证据。

#### 步骤 0：静态门禁先行（不写业务代码）

- **文件**：新增 `scripts/check-isolation-key-scope.sh`（§5.3 约束 4）
- **验证**：
  1. 正常态 `bash scripts/check-isolation-key-scope.sh; echo $?` → `0`
  2. `ISOKEY_FAIL_SEED=1 bash …` → `1`（**自证能红**）
  3. 与现有 5 个 `scripts/check-*.sh` 一并纳入 `pre-commit` 清单

#### 步骤 1：transcript 归位（最小风险，先做）

- **改**：
  1. `KernelScopeKey.java` —— 新增 `transcriptTenant()`（§5.3 约束 2）
  2. `AgentScopeChatKernel.java:338` —— 删 `.disableTranscript()`，加 `.transcriptTenant(scope.transcriptTenant())`
  3. `AgentScopeProjectAgentKernel.java:279` —— 同上
- **验证**：
  1. 单测锁定 `KernelScopeKey.transcriptTenant()` 的 fail-closed（传含 `/`、`:`、`..` 的 projectId → 抛）
  2. **真启动**（`mvn spring-boot:run -pl ruoyi-admin`，dev profile）：发一轮 chat（**同一 project 两次 + 不同 project 一次**）
  3. `find` workspace 目录，确认出现**三组互不相同**的 `{tenant}/{agentId}/{sessionId}/events/*.jsonl`，且不同 project 的 tenant 段不同
  4. 关服务重启，再发一轮，确认 transcript 可续读（`listSegments` 有历史段）
  5. **真 DB**：`docker exec` 查 Redis `KEYS 'ruoyi:agentscope:chat:*'`，确认 slot 布局**未变**（无存量迁移）

#### 步骤 2：plan-mode 归位（纯增量）

- **改**：
  1. `AgentScopeProjectAgentKernel.java` —— 加 `.enablePlanMode()` + `.planFileDirectory("plans")`
  2. 若需 plan 阶段读代码：`.allowShellInPlanMode(true)`（配合已有 `.disableShellTool()` 会冲突，需一并评估）
- **验证**：
  1. **真 HTTP**：调 chat 接口，触发 `plan_enter` → 尝试调用一个写操作工具 → 断言返回「plan 阶段拒绝」；`plan_write` → 文件落 `workspace/plans/PLAN.md`；`plan_exit` → 恢复正常工具集
  2. **真 DB**：plan 状态存 `AgentState` → 查 Redis 确认 `agent_state` 里出现 `PlanModeContextState` 键
  3. 重启服务后 `GET plan` 状态仍为 active（验证持久化）

#### 步骤 3：skill 归位（§3.3 三步）

- **改**：
  1. `FrozenProjectAgentSkills.java` —— 删 `onSystemPrompt` + prompt 拼装（**D1/D2**），校验段一字不动
  2. `AgentScopeProjectAgentKernel.java:268/287` —— `.middleware(selectedSkills)` → `HarnessSkillMiddleware.frozen(...)`；`.skillsEnabled(false)` → `.enableSkills(names...)`
  3. 装配前接 `SkillSecurityScanner.scan(...)`（fail-closed）
- **验证**：
  1. **真 HTTP**：跑一轮项目智能体，抓 system prompt，断言含 `<available_skills>` 且**每个条目都在 `CapabilityManifest` 内**
  2. **篡改测试**：把 `ipd-skills/<name>/SKILL.md` 改 1 个字节 → 重启 → 真 HTTP 请求应 **fail-closed 抛错**，且 `project_agent_skill operation=VERIFY status=MISMATCH` 日志出现
  3. **越权测试**：构造一个不在 `CapabilityManifest` 的 skill 名 → 断言 `reason="Skill 未在内置清单登记"`
  4. 写操作断言：`FrozenProjectAgentSkills.save/delete` 仍抛 `UnsupportedOperationException`
  5. 幂等断言：同一 marker 连续两轮注入，prompt 中 `<!-- selected-skills:` 出现且仅出现 1 次

#### 步骤 4：memory 归位（只归位 hook + 工具，**不归位 compaction**）

- **改**：
  1. 两个 kernel 删 `.disableMemoryHooks()`（`AgentScopeChatKernel.java:337` / `AgentScopeProjectAgentKernel.java:278`）与 `.disableMemoryTools()`（L336/L277）
  2. **保留** `.disableCompaction()`（防与自研 `ContextEngine` 打架）
  3. `.memory(MemoryConfig.builder().flushTrigger(FlushTrigger.throttled(Duration.ofMinutes(5))).build())` —— 节流起步，**避免每轮 flush 烧 token**
  4. **不配** `DistributedStore`（§4.3 建议 2）
  5. `.isolationScope` 相关：chat 侧**先不启用** USER 档（§5.3 约束 3 简版）
- **验证**：
  1. **真 HTTP** 对话 3 轮 → `find workspace/memory/` 出现 `YYYY-MM-DD.md`
  2. 调 `memory_search(query=...)` 工具 → 断言返回非空；调 `memory_save` → 断言 `MEMORY.md` 更新
  3. **真 DB**：确认 Redis 键集合**无新增**官方 memory 命名空间（验证没混进 BaseStore）
  4. 节流断言：3 轮对话后 `memory/YYYY-MM-DD.md` 行数 ≤ 2（throttled 生效）
  5. `MemoryBackgroundTasks.awaitQuiescence(30, SECONDS)` 静默后再读文件，避免 flaky
  6. **回归**：上下文超长时仍走自研 `ContextEngine`（断言 `sessions/*.log.jsonl` **未**生成）

### 6.3 风险登记

| ID | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | `transcriptTenant` 忘记设置 → 全租户 transcript 落同一目录 | **P0** | 步骤 1 门禁脚本拦截字面量实参；`transcriptTenant()` 缺省即抛而非回落 `"default"` |
| R2 | `IsolationScope.USER` + 复合 userId（含 `:`）→ Windows 部署路径非法 | **P0** | §5.3 约束 3：不用 USER 档；或加 `toFilesystemRuntimeContext()` 视图 |
| R3 | 官方 memory 后台 consolidation 无版本门禁 → 多副本互相覆盖 `MEMORY.md` | **P1** | 步骤 4 先只开 chat 单副本验证；开多副本前须在 `MemoryMaintenanceMiddleware` 覆盖层加分布式闸 |
| R4 | 官方 transcript `appendSegment` 无 CAS，与 `FailClosedAgentStateStore` 的 fail-closed 语义不同层 | **P1** | §4.3 建议 1：确认 `TURN_GATE.acquire(slotId)` 覆盖整个 call 生命周期，否则扩 gate |
| R5 | `disableDynamicSkills` 被误删 → 官方退回 per-call `mergeRepositories`，classpath 每轮重读 | **P1** | 步骤 3 保留该开关；`.disableDynamicSkills()` 缺失即官方不进入 frozen 模式（`HarnessAgent.java:2929`） |
| R6 | 启用 `enableSkillManageTool` 会把只读 repo 升级为**可写**（`HarnessAgent.java:2806-2814`），打破项目「只读冻结」 | **P1** | §3.3 明确**不启用**；后续若要开，必须先加 `SkillPromotionGate` |
| R7 | 官方 compaction 与自研 `ContextEngine` 双跑 → 上下文被压两次 | **P1** | 步骤 4 保留 `.disableCompaction()` |
| R8 | 删 `HarnessSkillCatalogFactory.parse` 后丢失 `NOFOLLOW_LINKS` / 深度 6 / 256KB 上限 | **P2** | D6 标注「中高」，删前逐条比对官方 parser 等价性，缺一不可 |
| R9 | 官方 memory 每轮 LLM 调用烧 token | **P2** | 起步即用 `FlushTrigger.throttled(5min)` |
| R10 | 新增官方能力引入第 4 套 Redis 命名空间 | **P2** | §4.3 建议 2：不配 `DistributedStore` |

### 6.4 红线（本次归位期间不可越）

1. **不改** `pom.xml` —— AgentScope 锁死 `2.0.3`，本文所有 API 以该版本为准；升级需先跑 `.harness/evals/` 回归。
2. **不删** `KernelScopeKey` / `AgentScopeRedisStateStores` / `FailClosedAgentStateStore` / `ProjectAgentSkillCatalog` / `FrozenProjectAgentSkills` 校验段 / `ContextEngine` 全家。
3. **不新增** `KernelScopeKey.java` 之外的隔离键拼装点（门禁脚本强制）。
4. **不配置** `HarnessAgent.distributedStore(...)`。
5. **不启用** `enableSkillManageTool` / `enableSkillCurator`（自学习闭环会让 agent 写自己的 skill，与多租户安全边界冲突）。
6. **不删** `.disableDynamicSkills()`（步骤 3 必须保留，否则冻结模式失效）。
7. **每步必须真 HTTP + 真 DB 验收**，Mock 单测只作契约锁定。
8. **本方案文档落地须在独立分支/worktree**，避免与兄弟会话在途工作树冲突。

---

## 附：本文「未实证」清单（诚实边界）

| 项 | 为什么未实证 |
|---|---|
| `PlanModeTools` 三个实现类的**类名** | 只实证到「3 个 `callAsync` 实现」；工具名 `plan_enter`/`plan_write`/`plan_exit` 来自 `plan-mode.md:39-49` 文档 |
| `SkillVisibilityFilter` 的方法签名 | 只实证到 `public interface`（L36），未读方法体 |
| `agent.enterPlanMode/exitPlanMode/isPlanModeActive` 方法签名 | 仅 `plan-mode.md:135-147` 文档记载，未 grep 到 `HarnessAgent` 对应行 |
| `MEMORY.md` / `memory/YYYY-MM-DD.md` / `sessions/*.log.jsonl` 的字面量常量 | 仅 `memory.md:32-50` 文档描述，本轮未 grep 到对应 Java 常量 |
| 4 层 skill 同名优先级 | 仅 `skill.md:182-197` 文档表格 |
| `ProjectAgentWorkspace`（IPD 侧）与 `AgentScopeChatKernel.workspaceSegment` 是否会形成第二处路径隔离实现 | 子代理报告指出二者**是独立实现但同构**，本轮未逐行读 `ProjectAgentWorkspace.java` |

---

## 【owner 拍板 2026-10-03】两套记忆的明确分工

此前官方文件记忆（SDK sandbox `memory/*.md`）与自研 `ipd_agent_memory` 表并存、都在真写、
语义重叠且无任何同步代码。本节确立分工边界，**两者都保留，不合并**。

### 分工

| | 官方文件记忆（`memory/*.md`） | 自研 `ipd_agent_memory` 表 |
|---|---|---|
| **生命周期** | 单次运行 / 单个沙箱，随快照归档 | **跨运行、跨会话持久** |
| **作用域** | 本次任务内的工作上下文 | 按 `project_id` + `person_id` 隔离 |
| **回答的问题** | 「我这次已经读过什么、推过什么」 | 「**下次**我还记得你是谁、你怎么做事」 |
| **内容特征** | 临时推理痕迹、阶段性结论 | PREFERENCE / FACT / OBSERVATION 三类结构化条目 |
| **权威性** | 不承载业务事实 | 强制带「非权威个人工作笔记」标注；IPD 权限/审批/Gate 一律不查此表 |
| **写入方** | SDK `memory_save`（实测 3 次调用） | `ProjectScopedLongTermMemory.record()`（每轮运行收尾，带 `MEMORY_RECEIPT` 回执） |

### 判定依据（实测证据，非推理）

- 自研表 16 行跨 5 次运行，三类分布 PREFERENCE 7 / FACT 6 / OBSERVATION 3，**全部 status=0（CANDIDATE）**；
  实际内容如「用户偏好极简确认式回复」「验收类交互中用户偏好极简回复」——**跨会话的个人偏好**，
  正是本表职责。
- 官方记忆实测 `memory_search` 15 次、`memory_save` 3 次，全部发生在**单次运行内**——会话工作上下文，正是官方职责。

### 明确不做的事

- **不建同步代码**。两者语义不同，强行同步会制造「哪份是权威」的新问题。
- **不把官方记忆当业务事实来源**。`memory/*.md` 随沙箱归档，其中出现的任何陈述都不得用于权限、审批或 Gate。
- **不合并存储**。合并等于放弃官方能力的原生归档路径，且要自研一套跨运行记忆（本次明确不做）。

### 已知质量观察（非分工问题，单独跟踪）

自研表出现「本次指定的备用模型为 GLM-5.3-Flash」这类**单次会话内的事实**被归入 FACT。
抽取 prompt 已禁止抽取审批/权限/金额类内容，但未禁止「一次性配置」进入长期记忆。
建议后续在抽取判据中区分「可复用的稳定事实」与「本次会话的一次性设定」，避免长期记忆被一次性内容稀释。
