---
topic: modules/ipd-node-agents
title: ruoyi-ipd — 生命周期节点智能体（69 码执行栈 / 6 执行器 / 信任边界）
updated: 2026-09-27
raw:
  - raw/ipd-source/ai-execution-engine.md
---

# ruoyi-ipd — 生命周期节点智能体

IPD 六阶段的 **69 个标准动作**（ActionCatalog）由嵌入式节点智能体完成——每个动作码绑定一个执行器，执行器在 outbox 引擎（`AiExecutionEngine`）的抢占-路由-退避链路中运行。核心设计哲学：**AI 是快车道不是唯一车道**——智能体不可用时降级为确定性行为 + WARN，绝不伪造产物；人工路径始终畅通。

契约文档（唯一事实源）：`docs/ipd-系统说明/R236-生命周期节点智能体接线设计-20260927.md`

## 执行栈唯一原则（裁决 A）

节点智能体的 LLM 调用**一律经 `AiGenerationService.generate(actor, req)`**，不走 ruoyi-chat 的 `ChatServiceFacade` / supervisor 路径（langchain4j `AiServices` 栈已于 2026-10-02 全量替换为 AgentScope）。

**为什么不能走 ruoyi-chat 路径**：

1. `ChatServiceFacade.chat(ChatRequest{agentId}, handler)` 该重载**完全不解析 agentId**，只用 `getModel()` 查模型
2. 调 `LoginHelper.getUserId()`（需登录态，outbox 无登录态）
3. 写 `chat_messages`（副作用）
4. 真正的 agent 路径 `handleAgentChat` 装配的是 supervisor 多子 Agent、**无条件挂 `ExecuteSqlQueryTool`**
5. 磁盘 skills 已被禁用——代码原文：`"Legacy shell-backed skills are disabled"`（`ChatServiceFacade.java` L309）

若走该路径，65 个业务节点的 LLM 调用**全部绕过** `AiGenerationService` 内建的 7 道治理（SSRF 前置 / 预算预检 / Semaphore(3) 限流 / RAG 注入 / 瞬时重试 / 文档落库 / 审计含 token 计量），形成 AGENTS.md 禁止的双轨。

**证据**：

| 事实 | 位置 |
|---|---|
| IPD 生成主链已是 AgentScope | `service/ai/AiGateway.java` import `io.agentscope.core.model.*` 与 `AgentScopeModelFactory`（2026-10-02 替换） |
| 旧 HttpClient 栈已废弃 | `service/ai/AiChatClient.java` L41 类级 `@Deprecated` + L44-48 注释「新代码禁止直接注入本类做生成调用」 |
| 新增代码零 AiServices/ChatServiceFactory/MCP ToolProvider | 契约 §1 裁决 A 明文约束 |

## 智能体绑定机制（裁决 B）

命名约定即映射，**不自建注册表、不新增映射表、不新增 system_configs 键**：

- 智能体身份 = `agent_info` 表一行，命名约定 **`IPD-<动作码>`**（如 `IPD-C07`、`IPD-LC09`）
- 解析入口 = 既有 `IAgentService.queryEnabledOptions()`（`ruoyi-chat` L52，已过滤 `status=0`）
- 智能体的 `system_prompt` 即该节点的工作指令（含输出格式/禁止事项/证据要求）
- `status` 字段是 **kill-switch**：置 1 即禁用该智能体，执行器降级
- 未绑定智能体（查无 `IPD-<code>` 行）时：执行器**降级为确定性行为 + `log.warn`**，绝不伪造产物

**为何不自建缓存**：`SystemConfigServiceImpl` 已有 Caffeine(500/5min)+写穿透失效；`agent_info` 解析发生在 outbox 任务执行时（频次 = 每任务一次，远低于 LLM 调用开销），直查即可。自建 `ConcurrentHashMap` 只会制造 stale bug，违反 PERF-02「配置变更立即生效，不允许 TTL 窗口」。

**命名约定的脆弱性与对冲**：约定是隐式契约，改名即静默解绑。对冲 = `ExecutorCoverageSentinelTest` 哨兵表驱动契约测试（R236 后 10 条断言，棘轮已到底）：

| 断言 | 锁什么 |
|---|---|
| `everyAiActionIsWiredOrExplicitlyExempt` | 非 HUMAN_GATE 动作既未接线也未豁免 = 静默漏 |
| `exemptionRatchetOnlyShrinks` | `EXEMPT` 必须为 0、`WIRED` 必须为 69（禁止回调） |
| `wiredCodesMatchExecutorsUnion` | 哨兵表 ≡ 执行器实际 `supportedActionCodes()` 并集 |
| `noTwoExecutorsClaimSameCode` | 防引擎 `executorByCode` map 静默覆盖 |
| `dynamicDepthCodesNotAssignedToLightDirect` | 数据驱动枚举全模板：LIGHT 可被提升为 DEEP 的码不得归零交付物执行器（B3） |
| `seedSqlRowsMatchLlmConsumingCodesExactly` | 种子建行码集 ≡ 消费 LLM 的 42 码（双向：缺行→永久降级，多行→装饰性假配置） |
| `seedSqlHasNoDuplicateRow` | INSERT 数 ≡ 去重建行数 |
| `generateCodesHaveSingleNotifiableOwnerRole` | AI_GENERATE 码的 ownerRole 必为可直接通知的单角色（B6） |
| `scheduleWiredCodesExcludeFillTableAndHumanGate` | 可调度集 = 38（Generate 24 + Light 14） |
| `frontEndExecModeMapMatchesActionCatalog` | 前端 `ACTION_EXEC_MODE` 69 行 ↔ 后端 `ActionCatalog.execMode` 逐码全等（跨仓） |

种子对账按幂等守卫 `WHERE NOT EXISTS (... agent_name = 'IPD-X')` 解析建行，不全文 `contains`——否则文末回验查询列出的全 69 码字面量会同时造成正向假绿与反向假红。

跨仓对账复用 `StateMachineGuardRulesExportTest` 的 `IPD_FE_SHARED_DIR` 定位约定（系统属性 > 环境变量 > 默认路径），前端仓不在检出内时 `Assumptions` 跳过而非假绿。前端为何要存这份副本：`/stage-actions` 的 VO 不带 execMode（只有已产生 AI 任务时 `AiAgentTaskView` 才带），而节点徽标须在「尚无 AI 任务」时也能显示执行档位；代价是同一真值两仓各存一份，故必须机制化对账而非靠注释声称「有哨兵保护」。

## 6 执行器分档表（裁决 C）

| 执行器 | 认领码数 | 调 LLM | supportsSchedule | 终态 | 行为摘要 |
|---|---|---|---|---|---|
| `GenerateExecutor` | 24（全 AI_GENERATE） | **是** | true | **IN_PROGRESS** | 智能体 prompt → generate() 出草稿 → 按 ownerRole 通知责任人审 |
| `AgentEvidenceExecutor` | 18（AI_DIRECT ∧ DEEP ∧ 无 valueFields） | **是** | **false** | DONE | 智能体出证据文档 → generate() → OSS 挂交付物 → transit(DONE) |
| `LightDirectExecutor` | 14（AI_DIRECT ∧ LIGHT ∧ 无 valueFields） | 否 | true | DONE | recordFields(now) → transit(DONE)，零 LLM 纯确定性 |
| `DeepDirectExecutor` | 4（C08/D11/V02/L08） | 否 | **false** | DONE | 人确认载荷 → 按 `valueFields` 数据驱动归集 md → OSS → addDeliverable → transit(DONE) |
| `GatePrepExecutor` | 5（HUMAN_GATE） | 否 | true | ok/fail | 备料 + 非否决要素预判 PASS + 尝试 submit；否决项绝不代判 |
| `KpiSharedReconcileExecutor` | 4（K01-K04） | 否 | **false** | 不 transit | 只读对账 + 台账 md → OSS → 交付物留痕 → 知会组长 |
| **合计** | **69**（哨兵 WIRED=69、EXEMPT=0） | 42 码调 LLM | 38 码可调度 | | |

**`supportsSchedule=false` 的理由**（共 27 码）：

- `DeepDirectExecutor` 4 码 + `KpiSharedReconcileExecutor` 4 码：无人确认载荷时 SCHEDULE 自动派发必 fail，只会累积必死行；
- `AgentEvidenceExecutor` 18 码（**安全红线**，契约 §7 B4）：本档位把 LLM 产物直接作为 DONE 的门禁交付物，**没有独立人审环节**——若放开调度，每日自动派发会让「未人审的 LLM 草稿」成为动作完成的唯一证据 = 实质 AI 代签完成（违反红线 2）。故只接受自然人在动作详情页点「AI 执行」的 PASSIVE 触发。

扫描器据 `scheduleWiredActionCodes()` 二次过滤，不建每日累积的无效任务；可调度集 = GenerateExecutor 24 + LightDirectExecutor 14 = 38 码，由哨兵锁定。

**GenerateExecutor 终态为 IN_PROGRESS 而非 DONE**：AI 草稿只是起点，人审通过后由 review hook 挂交付物收尾。绝不代签 DONE 是信任边界第 4 条的体现。

## 信任边界 6 条红线

以下 6 条为不可协商的硬约束（契约 §2 原文口径）：

1. **写库数值一律来自人确认载荷**：`recordFields` 的 far/frr/certNo/certPassedAt/launchDate 只接受 `triggerType=PASSIVE ∧ triggeredBy≠null` 的自然人确认链路上的 `fill_payload`；SCHEDULE/EVENT 无人确认环节**连查都不查**
2. **LLM 不得直接产数落库**：智能体产物只进 `ai_documents`（草稿态待人审）或 OSS 交付物，**绝不**解析 LLM 文本回填 `stage_actions` 数值字段
3. **Gate 否决项绝不代判**：`GatePrepExecutor.isVeto` 命中即 `continue`
4. **终态守卫**：所有执行器首行 `AiActionExecutor.terminalNoOp(...)`，人判完成后重复触发不得再备料/上传/通知
5. **失败即 fail，不伪造完成**：智能体不可用 / 载荷缺失 / OSS 未返回 ossId → `AiExecResult.fail(...)` 走引擎退避重试，≥3 次 DEAD 转人工
6. **不挂 SQL 执行工具**：`ExecuteSqlQueryTool` 等 supervisor 工具**不进入**任何业务节点（按裁决 A 根本不走 supervisor 路径，天然满足）

## 人审点清单

### AI_GENERATE 24 码：产物是草稿待人审

GenerateExecutor 产出只到 `IN_PROGRESS`，AI 文档状态为 `GENERATED`（待审核）。人审通过后由 review hook 挂交付物、transit(DONE)。24 码分布：

- CONCEPT：C01-C06, C12（7 码）
- PLAN：P01, P03-P07, P11（7 码）
- DEV：D01, D04, D06（3 码）
- VALID：V06-V08（3 码）
- LAUNCH：L01, L03, L04（3 码）
- LIFECYCLE：LC08（1 码）

### HUMAN_GATE 5 码：否决项绝不代判

| 动作码 | Gate | 动作名 |
|---|---|---|
| C11 | G1 | Charter立项评审会 |
| P13 | G2 | 差异化确认评审会 |
| D05 | G3 | 双周开发评审 |
| L07 | G4 | GTM就绪评审 |
| LC02 | G5 | 上市后90天复盘 |

GatePrepExecutor 只做备料（材料 md + 纪要模板 + 非否决要素预判 PASS），否决要素留给人判。submit 被人判门槛拒绝属预期路径（fail → 退避 → 通知双 PM）。

## 已知限制

1. **`agent_info.model_id` 当前不参与实际模型选择**：`AiGenerationService.generate()` 取全局唯一 `AiModelConfig`（`modelConfigService.currentEnabled()`），智能体行的 `model_id` 字段预留但未接入路由逻辑。切换模型需改全局配置，不能按智能体粒度指定。

2. **MCP 工具与磁盘技能在 outbox 链上不可达**：
   - MCP 工具（`BuiltinToolProvider` / `ExecuteSqlQueryTool` 等）只在 `ChatServiceFacade.handleAgentChat` 的 supervisor 路径装配，outbox 引擎不走该路径
   - 磁盘 skills 已在代码中禁用——`ChatServiceFacade.java` L309 原文：`"Legacy shell-backed skills are disabled; use the coding Harness skill runtime"`
   - 节点智能体的能力边界 = `agent_info.system_prompt` 指令 + `AiGenerationService.generate()` 的 RAG 注入，无工具调用能力

3. **V11 动态深度陷阱已对冲**（契约 §7 B3）：`ActionCatalog.expectedDepth` 下 V11 在 SOLUTION 模板为 DEEP，而 `StageActionService` 按**实例** depth 判定、DEEP 强制 ≥1 交付物（BR-IPD-03）。若归零 LLM 的 `LightDirectExecutor` 则 SOLUTION 项目该动作必死于校验 → 退避 → DEAD。故 V11 归 `AgentEvidenceExecutor`（其 LIGHT 实例同样产证据文档：过度产出但合法），并由哨兵 `dynamicDepthCodesNotAssignedToLightDirect` 数据驱动枚举全模板锁定。

4. **种子 SQL 只为 42 个消费 LLM 的码建行**：`docs/script/sql/update/20260927-ipd-node-agents.sql`（42 行，`INSERT...SELECT...FROM DUAL WHERE NOT EXISTS` 幂等）。其余 27 码执行器为确定性逻辑，建行即装饰性假配置——由哨兵 `seedSqlRowsMatchLlmConsumingCodesExactly` 双向锁定（缺行 → 永久降级；多行 → 假配置）。本仓无 Flyway，需 DBA 人工 apply；apply 前 42 码均走「显式声明未使用 AI」的确定性降级路径。

5. **`queryEnabledOptions()` 的 N+1 成本**：每行 toVo 触发模型/MCP/知识库关联查询且无 LIMIT。解析频次 = 每 outbox 任务一次，远低于同任务内 LLM 调用开销；按 PERF-02「配置变更立即生效、不允许 TTL 窗口」**不自建缓存**。

## 相关文档

- [ipd-workflow.md](ipd-workflow.md) — IPD 业务工作流（六阶段 / Gate 评审 / 阶段动作状态机）
- 契约文档：`docs/ipd-系统说明/R236-生命周期节点智能体接线设计-20260927.md`
- 治理报告：`docs/ipd-系统说明/工作流系统性梳理-20260927.md`
- 源码级摘要：[ai-execution-engine.md](../../raw/ipd-source/ai-execution-engine.md)
