# AgentScope 归位方案 · compaction（上下文压缩）

- 日期：2026-10-02
- 范围：仅「上下文压缩」一项的官方归位评估
- 官方源码仓：`/Users/mac/Documents/agentscope-java`，依赖 `io.agentscope:* 2.0.3`
- 纪律：本文件只做实读源码的评估与方案设计，未修改任何 `src/` 文件、未改 `pom.xml`

---

## 0. 结论摘要（先读这段）

**归位可行，但不能整体替换自研引擎。** 自研的 `ContextEngine` 承担了三件官方 compaction 完全不做的事：不可变 pin 锚定、协议完整性校验（孤儿 tool result / 重复 tool_call_id）、以及确定性无 LLM 摘要。官方 `ConversationCompactor` 是「LLM 摘要 + 尾部保留」的通用实现，语义上更弱。

真正可归位的是**溢出兜底**（官方 `recoverFromOverflow` / `forceCompactAndRetry`）与**大工具结果卸载**（`ToolResultEvictionMiddleware`）这两条独立能力，它们与自研引擎正交、风险可控。

**四个必须在落地前知道的事实纠偏：**

1. **官方 compaction 在本项目并非「关着」，而是三处装配点行为不一致。** 任务书称「实测官方 CompactionMiddleware / CompactionConfig 在项目里引用数为 0」——实测为 **1 处**，且是 `.disableCompaction()` 这个**关闭调用**本身。
2. **官方文档与官方代码对「默认值」的说法相反。** 文档两处断言压缩「默认全部不开」，代码里 `HarnessAgent.Builder` 字段初值却是 `compactionConfig = CompactionConfig.builder().build()` + `disableCompaction = false`，即**默认开启**。落地时必须以代码为准。
3. **另两个装配点的官方 compaction 已装配进中间件链（代码已定论）。** `AgentScopeProjectAgentKernel` 与 `AgentScopeChatKernel` 从未调用 `disableCompaction()`。完整证据链：
   - `ProjectAgentModelAssembler.assemble(...)`：`request==null || modelName 为空` → **抛 `IllegalArgumentException`**，非返回 null；
   - `ModelRegistry.resolve(...)` 契约实证（`agentscope-core/.../ModelRegistry.java:102` `@throws IllegalArgumentException if the id cannot be resolved or creation fails`，`:118-138` 实现逐分支 `throw`，无 null 返回路径）；
   - 故 **build 成功 ⇒ model 非 null**；
   - 官方守卫 `HarnessAgent.java:2614-2622` 三条件全满足：`!disableCompaction`（:1243 默认 false）、`compactionConfig != null`（:1240 默认 `builder().build()`）、`compactionModel` 回落至上述非 null model；两内核均设非空 `.workspace(...)`（`:289` / `:346`）⇒ `wsManager` 可用（`HarnessAgent:2498` 无条件构造）。

   > **必须区分两件事：**
   > - **是否装配 = 代码已定论（是）。**
   > - **是否曾真正触发过 = 待运行数据。** 日志（截至 2026-10-02 03:02）`Compaction triggered` / `Compaction complete` 命中数 **0**，但两个内核确实在跑（当日 16:33、17:10 有记录）。dynamic 模式回落阈值 `FALLBACK_TRIGGER_TOKENS = 160_000`（`CompactionConfig.java:67`），IPD 单 run 对话长度大概率够不到——**「够不够得到」是数据问题，代码读不出来**。absence of trigger ≠ absence of feature。
   >
   > **严重性判定**：即便从未触发，它仍是一条**无人验收的、每轮 call 都参与 trigger 检查并持有 `WorkspaceManager`（可访问工作区）**的路径。步骤 0 的理由因此不是「怕它在偷偷压缩」，而是**「怕它在无人知晓的情况下参与中间件排序与工作区访问」**。
4. **官方 compaction 依赖 `WorkspaceManager`**（`HarnessAgent:2498` 无条件构造），`ToolResultEvictionMiddleware` 依赖 `AbstractFilesystem`（`resolveFilesystem` 产出；本项目两内核虽调 `disableFilesystemTools()`，该开关只停工具注册、**不停 filesystem 构造**）。**eviction 落盘路径的租户隔离已定位到具体缺口**——见 §4bis 阻塞项。

---

## 1. 官方 API 面实证

> 口径遵循本项目 `agentscope-harness` skill：**「已实证」= 源码中真实存在并已逐行读过；「仅文档提及」= 只在 docs 中出现、未在代码中验证。**

### 1.1 已实证（源码真实存在）

#### `CompactionConfig`（`.../harness/agent/memory/compaction/CompactionConfig.java`）

不可变 record-like 类，私有构造 `CompactionConfig(Builder b)`，入口 `public static Builder builder()`。

| 成员 | 签名 | 默认值 |
|---|---|---|
| 常量 | `public static final int FALLBACK_TRIGGER_TOKENS` | `160_000` |
| 常量 | `public static final String DEFAULT_SUMMARY_PROMPT` | 四小节结构（`SESSION INTENT` / `SUMMARY` / `ARTIFACTS` / `NEXT STEPS`） |
| getter | `getTriggerMessages()` | `50` |
| getter | `getTriggerTokens()` | `0`（= 动态模式） |
| getter | `getReserved()` | `20_000` |
| getter | `getKeepMessages()` | `20` |
| getter | `getKeepTokens()` | `-1`（动态） |
| getter | `getKeepTokensMin()` / `getKeepTokensMax()` / `getKeepTokensRatio()` | `2_000` / `8_000` / `0.25` |
| getter | `getSummaryPrompt()` | 默认 prompt |
| getter | `isFlushBeforeCompact()` / `isOffloadBeforeCompact()` | `true` / `true` |
| getter | `getTruncateArgsConfig()` / `getPruneConfig()` | `null`（关闭）/ `PruneConfig.defaults()` |
| getter | `getModel()` | `null`（用主模型） |
| 派生 | `public CompactionConfig withEffective(int effectiveTriggerTokens, int effectiveKeepTokens)` | 供中间件解析动态默认值 |

`Builder` 方法（全部返回 `this`）：`triggerMessages(int)`、`triggerTokens(int)`、`reserved(int)`、`keepMessages(int)`、`keepTokens(int)`、`keepTokensMin(int)`、`keepTokensMax(int)`、`keepTokensRatio(double)`、`summaryPrompt(String)`、`flushBeforeCompact(boolean)`、`offloadBeforeCompact(boolean)`、`truncateArgs(TruncateArgsConfig)`、`prune(PruneConfig)`、`model(Model)`、**`model(String modelId)`**（走 `ModelRegistry.resolve`）、`build()`。

嵌套 `TruncateArgsConfig`：`triggerMessages=25`、`triggerTokens=40_000`、`keepMessages=20`、`keepTokens=0`、`maxArgLength=2_000`、`truncationText="...(argument truncated)"`。

嵌套 `PruneConfig`：`protectTokens=40_000`、`minimumTokens=20_000`、`maxOutputChars=2_000`、`excludedTools={read_file, memory_search, memory_get, session_search}`。

#### `ConversationCompactor`

- 构造：`public ConversationCompactor(Model model, MemoryFlushManager flushManager)`
- 主入口：`public Mono<Optional<List<Msg>>> compactIfNeeded(RuntimeContext rc, List<Msg> conversationMessages, CompactionConfig config, String agentId, String sessionId)`
- 常量：`public static final String SUMMARY_MSG_NAME = "__compaction_summary__"`
- 包级可见工具：`static String formatMessagesForSummary(List<Msg>)`、`static List<Msg> filterSummaryMessages(List<Msg>)`
- 实例方法：`List<Msg> pruneToolResults(List<Msg>, PruneConfig)`、`List<Msg> truncateArgs(List<Msg>, TruncateArgsConfig)`

算法（源码 `compactIfNeeded` 逐步实证）：参数截断 → 工具结果剪枝 → `TokenCounterUtil.calculateToken` → 触发判定 → 二分/计数求 cutoff → `findSafeCutoffPoint` 保证不撕裂 ASSISTANT tool-call 与 TOOL result 对 → memory flush（best-effort）→ offload JSONL → 一次 LLM 摘要 → 返回 `[summaryMsg] + tail`。返回 `Optional.empty()` 表示无需压缩。

#### `TokenCounterUtil`

`public static int calculateToken(List<Msg> messages)`。纯字符估算：`CHARS_PER_TOKEN = 2.5`、`MESSAGE_OVERHEAD = 5`、`TOOL_CALL_OVERHEAD = 10`、`TOOL_RESULT_OVERHEAD = 8`。**无真实 tokenizer**，中文按 2.5 字符/token 估。

#### `ToolResultEvictionConfig`

`public static final int DEFAULT_MAX_RESULT_CHARS = 80_000`、`DEFAULT_PREVIEW_CHARS = 2_000`、`DEFAULT_EVICTION_PATH = "large_tool_results"`、`DEFAULT_EXCLUDED_TOOLS = {read_file, write_file, edit_file, memory_search, memory_get, session_search}`。
Builder：`maxResultChars(int)`、`previewChars(int)`、`evictionPath(String)`、`excludedToolNames(Set<String>)`、`build()`；静态 `defaults()`。

#### 中间件

- `CompactionMiddleware(WorkspaceManager workspaceManager, Model model, CompactionConfig config)`，`activePoints() = EnumSet.of(ExtensionPoint.ON_REASONING)`。从 reasoning 消息列表剥离首条 SYSTEM，`resolveEffectiveConfig()` 按 `model.getContextWindowSize()` 解算动态阈值，写回 `AgentState.contextMutable()` 并重建 `ReasoningInput`。
- `ToolResultEvictionMiddleware(AbstractFilesystem filesystem, ToolResultEvictionConfig config)`，同样只挂 `ON_REASONING`。驱逐路径 `{evictionPath}/{sanitizedAgentName}/{sanitizedToolCallId}-{sha256前8字节}`；已驱逐块以 metadata key `agentscope.tool_result_evicted = true` 标记防递归；先压缩 canonical state 再压缩模型视图，避免 pre-reasoning hook 掩盖原始结果。

#### `HarnessAgent` 开关面

```java
public Builder compaction(CompactionConfig config)          // 传 null 等价 disable
public Builder disableCompaction()
public Builder toolResultEviction(ToolResultEvictionConfig config)
public Builder disableToolResultEviction()
public Builder maxContextTokens(int maxTokens)              // 默认 8000
public CompactionMiddleware getCompactionHook()             // 未配置时返回 null
```

`build()` 中的装配门禁（源码 2613-2626 行实证）：

```java
CompactionMiddleware compactionHook = null;
if (!disableCompaction && compactionConfig != null) {
    Model compactionModel = compactionConfig.getModel() != null ? compactionConfig.getModel() : model;
    if (compactionModel != null) { compactionHook = new CompactionMiddleware(wsManager, compactionModel, compactionConfig); inner.middleware(compactionHook); }
}
if (!disableToolResultEviction && toolResultEvictionConfig != null) {
    inner.middleware(new ToolResultEvictionMiddleware(filesystem, toolResultEvictionConfig));
}
```

溢出兜底（`isContextOverflowError` 按错误串匹配 `context_length_exceeded` / `context length` / `maximum context` / `token limit` / `too many tokens` / `exceeds the model's maximum` / `reduce the length`）→ `recoverFromOverflow` → `forceCompactAndRetry` 以 `CompactionConfig.builder().triggerMessages(1).build()` 强压一次后重试。**`compactionHook == null` 时直接抛 `RuntimeException("Context overflow: no compaction configured, unable to recover")`。**

#### 官方文档断言（本项目需纠偏）

- 文档称四套策略「默认全部不开」；代码字段初值为默认开启。**以代码为准。**
- 文档称压缩只作用于 `AgentState.contextMutable()`，不波及 plan 状态、后台子 agent 任务、`todo_write` 清单、权限规则——这几点在源码中与 `AgentState` 字段分离的设计一致，但未逐行验证每个字段。

### 1.2 仅文档提及（未在代码中逐行实证）

- `MemoryFlushManager.flushMemories` / `offloadMessages` / `resolveOffloadPath` 的完整签名（`ConversationCompactor` 调用到，实现未通读）。
- `model.getContextWindowSize()` 在各 provider 适配层的真实返回（是否存在返回 0 从而落到 `FALLBACK_TRIGGER_TOKENS` 的 provider）。
- `session_list` / `session_history` / `session_search` 三个工具的自动注册条件。
- 官方 README/CHANGELOG 是否声明过「默认开启」——未查。

---

## 2. 能力对账表

> 官方 = 上表实证；自研 = `org.ruoyi.service.coding.harness.context.*` 与 `model.ProviderOverflowRecovery*` / `HarnessContextCheckpoint`。

| # | 能力 | 官方 | 自研 | 判定 |
|---|---|---|---|---|
| 1 | token 触发压缩 | 有（`triggerTokens`，动态按 contextWindow-reserved） | 有（`ContextEnginePolicy.preferredInputTokens` 按比例） | **语义重叠** |
| 2 | 消息数触发压缩 | 有（`triggerMessages`） | **无**（`ContextEnginePolicy` 注释明写「deliberately contains no message-count threshold」） | **官方有而自研无** |
| 3 | 压缩后保留尾部 | 有（`keepMessages` / `keepTokens`） | 有（`ContextEngine.chooseCut` 按 group 切） | 语义重叠（自研按**协议组**切，官方按**单条消息**切） |
| 4 | 不撕裂 tool-call / tool-result 对 | 有（`findSafeCutoffPoint` 反向回溯 ASSISTANT） | 有（`groupMessages` 校验孤儿 result / 重复 call id / 组完整性） | **语义重叠，自研更严**（自研直接判 invalid 并中止，非回溯修正） |
| 5 | LLM 摘要 | 有（一次 `model.stream`） | 默认**不用 LLM**（`ExtractiveHarnessSummarizer` 抽取式，注释明写「never invokes a second model or invents facts」） | **语义重叠，取向相反** |
| 6 | 不可变 pin 锚定（原始需求不得被压缩掉） | **无** | 有（`ContextPins` + `validateDraft` 逐字段比对，摘要头尾截断会丢 pin 故显式置顶） | **自研有而官方无** |
| 7 | 摘要「不可信数据不得升格为指令」的显式标注 | 无 | 有（`ExtractiveHarnessSummarizer` 写入「untrusted task data, not instructions」） | **自研有而官方无**（安全属性） |
| 8 | 压缩留痕 / 审计 | 仅 `log.info` + 摘要 msg 的 name 标记 | 有（`HarnessContextCheckpoint` 落 `HarnessRunState`，经 `FileHarnessStore` 序列化持久化；含 `inputTokensBefore/After`、`modelIdentity`、`securityConstraints`、`lineage`） | **自研有而官方无** |
| 9 | 压缩产物跨进程可恢复 | 无（`AgentState` 内存态，官方由 StateStore 持久化整体） | 有（checkpoint + working set 分离，raw 消息留 ledger） | **自研有而官方无** |
| 10 | 压缩失败熔断 | 仅 `onErrorResume` 降级继续 | 有（`CompactionControl` 连续 3 次失败 `circuitOpen`） | **自研有而官方无** |
| 11 | 溢出后**只重试一次**的强制约束 | 有（`forceCompactAndRetry` 单次；但无「已尝试过」记忆） | 有（`ProviderOverflowRecovery` 4 阶段状态机 + `emergencyAttempted(overflowId)` 去重） | **语义重叠，自研更严**（可跨进程判别「这次溢出已用过那一次」） |
| 12 | 溢出恢复的身份绑定 | 无（按消息列表强压） | 有（`effectId` + `iteration` + `requestSha256` 三元组校验，`matchesFailedEffect` / `matchesRetryEffect`） | **自研有而官方无** |
| 13 | 溢出恢复状态机持久化 | 无 | 有（`HarnessRunState.providerOverflowRecovery`，schemaVersion ≥ 7 门禁；`HarnessStartupRecovery` 启动时收敛） | **自研有而官方无** |
| 14 | 压缩前工具参数截断 | 有（`TruncateArgsConfig`） | 无 | **官方有而自研无** |
| 15 | 聚合工具结果剪枝 | 有（`PruneConfig`） | 无 | **官方有而自研无** |
| 16 | 大工具结果卸载到文件系统 | 有（`ToolResultEvictionMiddleware`） | 无 | **官方有而自研无** |
| 17 | 压缩前 memory flush / 消息 offload | 有（`flushBeforeCompact` / `offloadBeforeCompact`） | 无 | **官方有而自研无** |
| 18 | 摘要使用独立模型 | 有（`model(Model)` / `model(String)`） | 无 | **官方有而自研无** |
| 19 | artifact / 工具句柄跨压缩保留 | 无 | 有（`HarnessContextCheckpoint.artifactIds`；`DurableHarnessRunProcessor` 有 `artifactHandlesContext` 专项投影） | **自研有而官方无** |
| 20 | token 估算精度 | 字符估算 2.5 字符/token | `TokenEstimator` 独立可换（注释提到 production 用 UTF-8 上界，与摘要器单位一致） | 自研单位一致性更好（注释记录过一次中文单位不一致导致摘要恒失败的缺陷） |

**对账核心：官方在「轻量降本手段」上更强（14/15/16/17/18），自研在「可审计、可恢复、协议安全」上更强（6/7/8/9/10/12/13/19）。两者交集只有「触发 + 保留尾部」这一层。**

---

## 3. 自研可删清单

### 3.1 建议**全部保留**（不得删除）

| 类 | 保留理由 |
|---|---|
| `ProviderOverflowRecovery`（155 行） | 官方无对应的可持久化恢复状态机。删除即失去「同一溢出只重试一次 + 恢复身份三元组校验」，崩溃重启后无法判别是否已用过那次重试。 |
| `ProviderOverflowRecoveryStage`（9 行） | 同上，4 阶段枚举。 |
| `HarnessContextCheckpoint`（114 行） | 审计留痕载体：token 前后对比、模型身份、安全约束、lineage。官方压缩不产出可审计记录，而本项目 IPD 场景要求压缩动作可追溯。 |
| `HarnessRunState`（625 行） | 全量运行态聚合，30+ 处引用。承载上面两个字段。 |
| `ContextEngine` / `CompactionControl` / `ExtractiveHarnessSummarizer` 等 `context/` 包 | pin 锚定、协议完整性校验、确定性无 LLM 摘要、熔断——官方均无等价物。 |

**本项归位后，自研 4 个待评估类无一可删。** 这与「归位即瘦身」的直觉相反，但由上表 20 条对账支撑：compaction 这一项的官方能力与自研能力**几乎不重叠**，重叠的仅是触发与切尾。

### 3.2 可考虑「瘦身但保留」的点

- `HarnessContextCheckpoint` 的 `summary` 字段与官方摘要并存时会出现两份摘要。建议保留自研摘要为**审计副本**，官方摘要为**模型可见副本**，并显式命名区分，避免后续误读为双写 bug。
- 官方 `ToolResultEvictionMiddleware` 归位后，卸载出去的大结果文件需纳入本项目工作区目录约定，否则会与 `WorkspaceManifest` 校验冲突（需在步骤 3 验证）。

---

## 4. 落地步骤（分步可独立验证）

> 全程要求**真 HTTP + 真 DB** 验收，不接受 Mock 替业务。

### 步骤 0 · 冻结基线并显式化官方压缩装配（**必做前置**）

**问题**：另两个装配点（`AgentScopeProjectAgentKernel:265`、`AgentScopeChatKernel:325`）的 `CompactionMiddleware` **已确定装配进中间件链**（证据见 §0 纠偏 3，代码已定论），但从未有人验收过它。

- 改哪个文件：两个内核
- 改什么：显式加 `.disableCompaction()` 与 `.disableToolResultEviction()`，把当前隐式行为**固化**下来
- **改动的性质**：这是**行为变更**，不是纯文档。官方 `recoverFromOverflow` 依赖 `compactionHook != null`——显式关闭后，一旦 provider 抛 `context_length_exceeded`，异常信息将从「被官方兜底压缩后重试」变为 `"Context overflow: no compaction configured, unable to recover"` **原样上抛**。因此**必须先确认这两个内核是否有自研的溢出兜底**（`DurableHarnessRunProcessor` 有完整的 `ProviderOverflowRecovery` 状态机，但它是 coding harness 专用，**不覆盖** `AgentScopeProjectAgentKernel` / `AgentScopeChatKernel`）。
- 怎么验证通过：全量 `mvn clean package` 绿；真 HTTP 打一次项目智能体与 chat kernel 端点，日志中 grep `Compaction triggered` / `Compaction complete` / `Evicted large tool result` 应为 0 命中

### 步骤 1 · 单点开启官方 tool-result eviction（低风险，先做）

- 改哪个文件：`DurableHarnessRunProcessor.java:423`，把 `.disableToolResultEviction()` 换为 `.toolResultEviction(ToolResultEvictionConfig.builder().maxResultChars(...).build())`
- 前置确认：确认该装配点的 `filesystem` 非 null（`HarnessAgent` 2498 行附近构造 `WorkspaceManager` 时已带 filesystem，但需实测 evicted 文件真落盘）
- 怎么验证通过：
  1. 真 HTTP `POST /coding/harness/sessions/{id}/runs` 跑一轮会产生大输出的任务
  2. 真 DB / 工作区文件系统回读 `{evictionPath}/durable-coding/` 下确有落盘文件
  3. 断言模型可见消息里该 tool result 已变首尾预览 + `read_file` 提示
  4. 确认 `excludedToolNames` 覆盖本项目高频小结果工具
- 失败回滚：改回 `.disableToolResultEviction()`

### 步骤 2 · 溢出兜底路径对齐（**最高风险，单独一步**）

- 改哪个文件：`DurableHarnessRunProcessor.java`，新增官方 `.compaction(CompactionConfig)` 或保留自研路径，二选一
- **建议：不切换，只做等价性验证。** 理由见 §5 风险 1
- 怎么验证通过（若确要切换）：
  1. 用真实 provider 触发一次 `context_length_exceeded`
  2. 断言 `HarnessRunState.providerOverflowRecovery` 的 4 阶段流转与 `CompactionControl.emergencyAttempted` 仍为真（这是官方**不提供**的能力，切换后必然丢失 → 预期结论是**不切换**）
  3. 真 DB 回读 run 状态与审计事件

### 步骤 3 · 官方 compaction 主链路评估开关（可逆）

- 改哪个文件：同 `DurableHarnessRunProcessor.java`，引入 feature flag（如 `coding.harness.official-compaction-enabled`，默认 `false`）
- 怎么验证通过：
  1. flag=false 时行为与基线**逐字节一致**（对比 run 状态快照）
  2. flag=true 时跑长会话，断言：`AgentState.contextMutable()` 出现 `__compaction_summary__` 标记消息，且自研 `contextCheckpoint` 仍在更新
  3. 断言 pin 未丢：对比压缩前后 `ContextPins.originalRequirement()`

### 步骤 4 · 官方 `PruneConfig` / `TruncateArgsConfig` 归位

- 改哪个文件：同 423 行，`CompactionConfig.builder().prune(...).truncateArgs(...)`
- 怎么验证通过：真 HTTP 长会话 + 真 DB 回读 token 前后对比（`HarnessContextCheckpoint.inputTokensBefore/After`），确认剪枝确实省 token 且未破坏 tool result 配对

---

## 3.5 🔴 全局阻塞项：官方 harness 寻址层不带租户维度（owner 拍板第一件事）

> 本节是**所有**官方 harness 归位项的共同前置，不止 compaction。归位任何一项前都须先过此节。
> 三个独立审计路径撞到同一模式，故单列为全局阻塞而非散落在各风险里。

**统一结论**：官方 harness 的寻址/持久化层**普遍不带 project/tenant 维度**，而本项目是多租户私有部署。
本项目靠自研 `KernelScopeKey` 在**应用层**补维，但官方组件直接消费 `RuntimeContext` 或 `agent.getName()`，
**绕过**了该收口。

| 路径 | 现象 | 本次实证 |
|---|---|---|
| **eviction**（本文档负责） | `ToolResultEvictionMiddleware` 落盘 `{evictionPath}/{agentName}/{toolCallId}-{hash}`，`agentName` 取 `agent.getName()`（本项目为固定字面量），**无租户维**；官方 `buildEvictionPath` 仅 `replaceAll("[^a-zA-Z0-9_-]","_")`，**无 fail-closed** | ✅ 已实证（`ToolResultEvictionMiddleware.java:266-274`） |
| **transcript**（他路审计，本次已复核） | `TranscriptRef` 紧凑构造器三个维度**校验不对称**：`tenant` 空 → **静默降级 `"default"`**（fail-open）；`agentId`/`sessionId` 空 → **throw**（fail-closed）。`HarnessAgent:1268` `String transcriptTenant;` 无初始化，null 即命中降级 ⇒ 不显式调 `.transcriptTenant(...)` 则**全租户 transcript 落同一目录** | ✅ 已复核（`TranscriptRef.java:24-34`、`HarnessAgent.java:1268`） |
| **subagent**（他路审计） | inbox key 只有 sessionId；`StoreBackedSubagentRegistry.NAMESPACE` 固定 `["subagents","exposed"]` 零隔离 | ⚠️ **未实证**（本轮未通读该两文件，仅转述） |

**为什么 tenant 维的 fail-open 比其他维更严重**：另外两个维度漏了会报错（fail-closed，快速暴露）；
唯独标识租户的那个**静默降级成 `"default"`**——不报错、不告警，**跨租户写入同一目录**，
且要等到真出现数据串扰才会被发现。这是典型的「静默 fail-open」，比崩溃更危险。

**owner 需拍板的两件事**：
1. **逐项补维 vs 统一收口**：是在每个官方组件调用点显式补 tenant，还是在
   `AbstractFilesystem` / `AgentStateStore` 抽象层做一次性收口（推荐后者，改一处生效于全局）。
2. **对 transcript 现状的追溯**：当前若确实在跑且未调 `.transcriptTenant(...)`，
   需评估**是否已发生跨租户 transcript 落盘**，以及历史数据是否需要清理。

**未决前禁止**：开启任何官方落盘/持久化组件（eviction、transcript、subagent registry）。

---

## 4bis. 可执行 diff 规格（不改任何源码，供执行者直接落地）

> 本节是 §4 的可执行化。写这份文档时**未修改任何 `src/` 文件、未改 `pom.xml`、未跑构建**。
> 下列改动全部是**改生产链路行为**，须由执行者在其工作环境落地并自行承担构建与验收。

### 🔴 阻塞项 B1（compaction 侧的具体缺口；已升级至 §3.5 全局阻塞项）

> **本项是 §3.5 全局阻塞项在 compaction 侧的具体落点。** 全局结论：官方寻址层不带租户维度。
> 下面是 eviction + workspace 两条路径的具体实证。

`ToolResultEvictionMiddleware` 把大结果写到 `{evictionPath}/{agentName}/{toolCallId}-{hash}`，
`agentName` 取自 `agent.getName()`——**不含 session 维度**，且官方 `buildEvictionPath` 只做
`replaceAll("[^a-zA-Z0-9_-]", "_")` 字符清洗，**没有** `KernelScopeKey` 那样的 `':'` / `".."`
fail-closed 校验。

**本项目侧的隔离现状（实读确认）**：

- 两个内核的 workspace 根由 `AgentScopeChatKernel.workspaceFor(root, projectId, userId, agentId)`（`:371-374`）产出，**只含 project × user × agent 三段，无 session 段**。
- `workspaceSegment`（`:401-409`）校验确实 fail-closed：拒 `null`/blank、`.`、含 `':'`、`'/'`、`'\\'`、含 `".."`。**已实证，无路径穿越漏洞。**
- 但该方法的 javadoc 自陈：「会话维由 stateStore 四维键硬隔离，同一用户同一员工的**多会话共用员工工作区**。W3 开放工具前仍须裁决会话维文件隔离（当前 W1 文件工具全关）。」

**结论**：租户/项目/用户/员工维的文件隔离成立；**session 维在文件面不成立**。
官方 eviction 一旦开启，大结果文件会落到「同一员工跨会话共享」的工作区里。

**风险判定**：当前文件工具全关（`disableFilesystemTools()`），agent 无法 `read_file` 取回，
故**当下不构成可利用的越权读取**；但它把「跨会话共享工作区」这一**已知未裁决状态**变成了
真实写入路径——一旦未来打开文件工具（W3），隔离缺口立即可利用。

**因此 diff 2 的执行门槛**：
1. 先完成 §6 的会话维文件隔离裁决（session 维补进 workspace 路径，或裁定 eviction 产物改落
   session 专属目录），**或**
2. 明确接受「eviction 产物跨会话共享」并写入 ADR，且承诺 W3 开文件工具前必须先补隔离。

**在这两条满足前，diff 2 判定为不可执行。** 这一项是本方案唯一的硬阻塞。

### diff 1 · 冻结基线（步骤 0；先做，低风险）

**文件 A** `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java`
锚点：`.skillsEnabled(false)`（当前 286 行附近）
```java
            .skillsEnabled(false)
+           // 显式化官方压缩能力：当前 CompactionMiddleware 确实已装配进中间件链（见文档 §0 纠偏 3），
+           // 但从未验收。显式关闭以固化归位前基线；归位时逐项移除即可。
+           .disableCompaction()
+           .disableToolResultEviction()
            .toolsConfig(toolsConfig)
```

**文件 B** `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeChatKernel.java`
锚点：`.disableDefaultWorkspaceSkills()`（当前 346 行附近）
```java
                    .disableDefaultWorkspaceSkills()
+                   // 同上：显式化官方压缩能力，固化归位前基线。
+                   .disableCompaction()
+                   .disableToolResultEviction()
                    .toolsConfig(toolsConfig)
```

**⚠️ 执行前必须先查（红线）**：本步是**行为变更**。官方 `recoverFromOverflow` 以
`compactionHook != null` 为前提；显式关闭后，provider 抛 `context_length_exceeded` 时将
**原样上抛**而非兜底压缩重试。`DurableHarnessRunProcessor` 的 `ProviderOverflowRecovery` 状态机
**只服务 coding harness，不覆盖这两个内核**。若这两个内核自身无溢出兜底，则本步等于
**移除了它们的最后一道防线**——须先补兜底或改为「保留 compaction 仅加 feature flag」。

**验证**：
```bash
mvn clean package                                    # 全量构建绿
# 真 HTTP 各打一次项目智能体与 chat kernel 端点后：
grep -c "Compaction triggered\|Compaction complete\|Evicted large tool result" logs/sys-console.log
# 期望 0
```
**回滚**：删除所加四行，行为回到当前状态（即恢复官方压缩自动装配）。

### diff 2 · 单点开启 tool-result eviction（步骤 1；依赖前置阻塞项结论）

**文件** `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness/loop/DurableHarnessRunProcessor.java`
锚点：423 行现有 `.disableCompaction().disableToolResultEviction()`
```java
-            .disableDynamicSkills().skillsEnabled(false).disableCompaction().disableToolResultEviction()
+            .disableDynamicSkills().skillsEnabled(false).disableCompaction()
+            .toolResultEviction(io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig.builder()
+                .maxResultChars(80_000)
+                .excludedToolNames(java.util.Set.of(
+                    "read_file", "write_file", "edit_file",
+                    "memory_search", "memory_get", "session_search"))
+                .build())
```
**验证**：真 HTTP 跑一轮产生大输出的任务 → 工作区回读 `large_tool_results/durable-coding/` 确有落盘 → 断言模型可见消息已变首尾预览 → 确认 `excludedToolNames` 覆盖本项目高频小结果工具。
**回滚**：还原为 `.disableToolResultEviction()`。

### diff 3 · 官方主链路 feature flag（步骤 3；可逆，默认关）

**文件** 同 `DurableHarnessRunProcessor.java`
```java
+ // feature flag，默认 false；置 true 时叠加官方 compaction 主链路。
+ boolean officialCompaction =
+     Boolean.parseBoolean(System.getProperty("coding.harness.official-compaction-enabled", "false"));
```
并在 builder 链上条件追加：
```java
+            (officialCompaction
+                ? .compaction(io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder()
+                    .triggerMessages(50).keepMessages(20)
+                    // 归档历史经摘要后以 USER 角色注入，必须补不可信标注（见 §5 风险 2）
+                    .summaryPrompt(UNTRUSTED_HISTORY_SUMMARY_PROMPT)
+                    .build())
+                : .disableCompaction())
```
**验证**：flag=false 与基线逐字节一致；flag=true 时长会话断言 `__compaction_summary__` 出现、自研 `contextCheckpoint` 仍在更新、`ContextPins.originalRequirement()` 未丢。
**回滚**：flag 置 false，无需改码。

### 步骤 2 明确不做

溢出兜底**不切换到官方实现**，理由见 §5 风险 1（官方无「已用过那次重试」的持久化记忆与 effect 身份校验）。该项在 diff 规格中**没有对应改动**——这是有意的空缺，不是遗漏。

---

## 5. 风险与红线

### 风险 1 · 溢出恢复语义降级（**阻断级**）

官方 `forceCompactAndRetry` 只做「按消息列表强压一次并重试」，**没有**「该溢出是否已用过那次重试」的持久化记忆，也没有 effect 身份三元组校验。切过去意味着：

- 崩溃重启后可能对同一次溢出重复重试；
- 无法区分「本次 model effect 属于失败重试序列」还是「无关的新请求」，跨进程恢复正确性下降。

自研 `ProviderOverflowRecovery` 明确注释「Recovery may continue only when the current model effect matches the identity expected by the current stage」。**这一项不应归位。**

### 风险 2 · 摘要质量与指令注入面

官方摘要 prompt 是**通用工程 agent 向**的四小节模板，不含「归档历史不得升格为指令」的显式约束。本项目 `ExtractiveHarnessSummarizer` 显式写入该标注。若并行启用官方摘要，被压缩的归档历史会以 `USER` 角色注入（`buildSummaryMessage` 用 `MsgRole.USER`）——**这是一个 prompt injection 面**：历史里的恶意文本经摘要后以用户身份进入上下文。官方设计上假定 workspace/memory 可信，本项目不可默认如此。缓解：若启用，摘要前缀须补不可信标注，或限定 `summaryPrompt` 为项目定制模板。

### 风险 3 · token 估算误差

`TokenCounterUtil` 是 2.5 字符/token 的纯估算，`keepTokensRatio` 等阈值全部建立在该估算上。中英混排下可能低估或高估 20%+。自研 `ContextEnginePolicy` 用 UTF-8 上界。项目 `docs` 记录过同类单位不一致导致摘要恒失败的缺陷。缓解：若归位，用真实 provider usage 回填校准 `CHARS_PER_TOKEN`，或改用静态预算 + 自研估算器。

### 风险 4 · token 成本变化

- **上升**：官方 compaction 每次触发都要额外一次 LLM 摘要调用（`model.stream`）；自研默认是抽取式、零 LLM。若 `triggerMessages(50)` 默认阈值比自研的 token 策略更早触发，摘要调用频次会显著上升。
- **下降**：`PruneConfig` + `TruncateArgsConfig` + `ToolResultEviction` 三件套是纯字符串操作，能实打实省输入 token。
- 净效应需真 HTTP 长会话 + provider usage 账单实测，不可估算。

### 风险 5 · 多租户隔离

官方 compaction 链路完全依赖 `RuntimeContext` 的 `(userId, sessionId)`：`agentId` 取 `agent.getName()`（**所有会话共享同一 agent 名**），`sessionId` 取 `rc.getSessionId()`，offload 路径由 `MemoryFlushManager` 拼装。

风险点：
- `ToolResultEvictionMiddleware` 的落盘路径是 `{evictionPath}/{agentName}/{toolCallId}-{hash}`，**agentName 不含租户维度**。若 `AbstractFilesystem` 底层未按 `RuntimeContext` 隔离，多租户下可能互相读到对方卸载的大结果文件。
- 官方 `buildEvictionPath` 只做 `replaceAll("[^a-zA-Z0-9_-]", "_")` 清洗，**没有**本项目 `KernelScopeKey` 那样的 `':'` / `".."` fail-closed 校验。

**结论：启用前必须确认 filesystem 的租户隔离由 `KernelScopeKey` 收口的 `(userId, sessionId)` 保证，而非依赖 eviction 路径本身。**

### 红线

- 不得为 compaction 引入第二处隔离键拼接（见 §6）
- 不得删 `ProviderOverflowRecovery` / `HarnessContextCheckpoint`（审计与恢复依赖）
- 不得在并行期让两套压缩同时改写同一 `AgentState.contextMutable()` 与 checkpoint
- 不得用 Mock 替代真 HTTP + 真 DB 验收
- 步骤 0 未完成前，不得开启任何官方压缩能力

---

## 6. 隔离键约束

项目铁律：复合隔离键拼接**必须收口在唯一文件**
`ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/KernelScopeKey.java`
且 fail-closed。

实读该文件确认的现有契约：

- 复合 `userId` = `p{projectId}:u{userId}`；复合 `sessionId` = `a{agentId}:s{sessionId}`；落库 `slotId` = 两者以 `':'` 相连
- 空 userId 降级 `__anon__`
- `validateSegment` 对每段 fail-closed：含 `':'` 抛「composite key injection」，含 `".."` 抛「path traversal injection」

**本方案的遵守声明：**

1. compaction 归位**全程不新增任何隔离键拼接**。官方 compaction 的 `agentId`/`sessionId` 均取自 `RuntimeContext`，而本项目 `KernelScopeKey.Scope.toRuntimeContext()` 已是四维收口后的产物——官方直接消费即可，无需自建。
2. **不得**为了给 `ToolResultEvictionConfig.evictionPath` 加租户前缀而去改官方 `buildEvictionPath`（那是官方库源码，改了就变成双轨维护）。正确做法是**在 filesystem 抽象层实现租户隔离**，让路径解析复用 `KernelScopeKey`。
3. 若确需为 eviction 配置传入租户相关路径，只能经 `KernelScopeKey` 派生，**禁止**在其他文件出现 `"p" + projectId + ":u" + ...` 这类手拼。

### 6.1 既有第二处手拼：`AgentScopeChatKernel.workspaceFor`（**漂移风险，非活漏洞**）

另一路审计查出 `AgentScopeChatKernel.java:371-374` 的 `workspaceFor` 是**第二处独立手拼路径**，
走 `Path` 链式 `.resolve()` 而非经 `KernelScopeKey`。本次已实读确认：

- **校验强度合规**：`workspaceSegment`（`:401-409`）fail-closed，拒 `null`/blank、`.`、含 `':'`、`'/'`、`'\\'`、含 `".."`——比 `KernelScopeKey.validateSegment` **更严**（多拒 `/` 与 `\`）。
- **不是活漏洞**。但存在两个真实问题：
  1. **维度不对齐**：`workspaceFor` 只收 project × user × agent **三段，无 session**；`KernelScopeKey` 是四段。同一员工跨会话共用工作区（见 §4bis 阻塞项 B1）。
  2. **门禁盲区**：现有 fail-closed grep 若只扫 `KernelScopeKey.java` 的拼接模式，扫不到这里。**这是收口纪律的缺口，不是安全缺陷。**

**归位要求**：官方 tool-result eviction 的落盘路径与 workspace 路径**同源**，两者隔离必须一起验。
故会话维隔离裁决（阻塞项 B1）落地时，**必须同时把 `workspaceFor` 并入 `KernelScopeKey` 收口**，
或至少让两处共用同一个段校验器——否则又多一处需要人工同步的规则。

### 6.2 门禁检测盲区（已实证，附检测器实证）

现有检测器 `.claude/skills/agentscope-harness/scripts/scope-key-statements.py` 的匹配模式为：

```python
PAIR = re.compile(r'\+\s*":"\s*\+')
```

即**只认 `+ ":" +` 这种字符串拼接式隔离键**。而 `workspaceFor` 走的是 `Path` 链式
`.resolve(workspaceSegment(...))`——**不含 `+ ":" +` 字面量**，故**检测器看不见**（本次已实读检测器源码确认）。

**这解释了 §3.5 中 transcript 的 fail-open 为何能长期存活**：门禁覆盖的是「手拼字符串键」，
而官方组件的隔离缺口发生在**类型层面**（record 字段语义、registry 常量），根本不在该检测器的表达范围内。

**归位要求（门禁侧）**：检测器需补两类规则，否则新增的官方组件会重蹈覆辙——
1. **fail-open 检测**：`catch`/默认赋值/`"default"` 这类静默降级模式（可直接命中 `TranscriptRef` 式漏洞）；
2. **多维校验对称性检测**：同一 record/构造器中各维度的空值处理是否一致（tenant 空降级而 agentId 空抛异常即应告警）。

4. 落地后应加门禁 grep：`KernelScopeKey` 段校验规则在 `AgentScopeChatKernel.workspaceSegment` 处
   出现**第二份实现**（可挂进 `scripts/check-*.sh` 做双实现检测），避免规则漂移。

---

## 附：本次审计对任务书的三处实证纠偏

| 任务书表述 | 实测 |
|---|---|
| 「官方 CompactionMiddleware / CompactionConfig 在项目里引用数为 0」 | **1 处**：`DurableHarnessRunProcessor.java:423` 的 `.disableCompaction().disableToolResultEviction()`——是关闭调用，非使用 |
| 「两个 HarnessAgent 装配点」 | **三个主装配点**：`DurableHarnessRunProcessor:417`、`AgentScopeProjectAgentKernel:265`、`AgentScopeChatKernel:325`（另有 `CodingServiceImpl:115` 与 2 个测试 PoC）。其中**只有第一处**关闭了 compaction |
| 隐含「官方压缩已被显式关掉」 | 后两个装配点**从未**调用 `disableCompaction()`，按代码推断应为**开启**；叠加官方文档「默认全关」与代码字段初值「默认开」的矛盾，归位前必须先做步骤 0。**该推断未实证**，见 §0 纠偏 3 |

## 附二：本次审计的未实证项清单（交执行者补齐）

| # | 未实证项 | 补齐方式 |
|---|---|---|
| 1 | ~~两个内核官方压缩是否装配~~ | **已定论：是**（见 §0 纠偏 3 完整证据链） |
| 2 | 两个内核官方压缩**是否曾真正触发过** | 真实 run 数据（阈值 `FALLBACK_TRIGGER_TOKENS=160_000`） |
| 3 | **两个内核是否有自研溢出兜底**（步骤 0 关闭后 `recoverFromOverflow` 会失效） | 查 `AgentScopeProjectAgentKernel` / `AgentScopeChatKernel` 的 `context_length_exceeded` 处理路径；`DurableHarnessRunProcessor` 的 `ProviderOverflowRecovery` **不覆盖**这两个内核 |
| 4 | 🔴 **会话维文件隔离裁决**（阻塞 diff 2） | 见 §3.5 全局阻塞项 + §4bis B1 + §6.1 |
| 5 | `MemoryFlushManager` 三个方法签名 | 通读 `.../harness/agent/memory/MemoryFlushManager.java` |
| 6 | 各 provider 的 `getContextWindowSize()` 真实返回 | 跑一次看是否落到 `FALLBACK_TRIGGER_TOKENS=160_000` |
| 7 | `session_*` 三工具自动注册条件 | 通读会话能力装配代码 |
| 8 | **subagent inbox key / `StoreBackedSubagentRegistry.NAMESPACE`**（§3.5 转述项） | 通读该两文件，**本轮未实证** |
| 9 | **transcript 现状是否已发生跨租户落盘** | 查是否调用过 `.transcriptTenant(...)`；若无，检查历史 transcript 目录归属 |
