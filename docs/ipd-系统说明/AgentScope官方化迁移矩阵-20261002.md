> 2026-10-02 版本1.1.0校准：本矩阵提供能力决策线索，不裁决执行顺序。统一计划和编号见《AgentScope官方化-六计划总览-20261002》v1.1.0。下文254文件/旧Controller/内存存储/已加载等是历史时点，开工现查。与当前业务合同不符的绝对判断不得驱动删除/权限变更。状态持久化不证明故障接续；slotId不证明Redis四维隔离；middleware/工具治理等业务适配不要求每个类实现SDK SPI。

# AgentScope 官方化迁移矩阵（2026-10-02，owner 拍板 harness 按官方）

> 依据：owner 2026-10-02 指令「核心组件充分应用、单一原则禁止双轨、harness 按官方来、本项目特性基于官方之上优化」。
> 事实源：agentscope-harness 2.0.3 sources jar（/tmp/as-src、/tmp/as-core 解包）+ 官方文档 java.agentscope.io/v2/zh/docs/{building-blocks,harness} + 本仓 grep 实测。

> v1.1.0 当前修订：生产适用 API 以本项目锁定 AgentScope 2.0.3 的源码包及编译、测试证据为准。官方站点的 v2 路径不是版本锁；2.1 或后续版本的文档/API 仅作研究参考，未经差异核实不得直接套用或升级。本轮七个专业工程分区沿同一总计划协作，不开启产品 subagents，不引入 Service/Vault/Sandbox/Temporal。
>
> aiflow 全图与旧检查点兼容读取正在迁移，统一打包、加载与真实恢复验收未完成。确定性业务 DAG 保留既有业务调度语义，智能体推理、模型、技能、MCP 与 Harness 仍统一在 AgentScope 2.0.3；DAG 不等于第二智能体执行轨，也不得保留 LangGraph runtime 作为失败回落。旧复杂并行/条件断点不能猜测重放，兼容能力以真实验证为准。

## 一、诚实结论：尚未充分应用

LangChain4j 主链及根 POM 旧 BOM 已清除；aiflow 全图正在迁移，尚未完成运行验收。下表的关闭能力数量、自研包/文件数量及旧 Controller 接线是先前审查记录，不代表当前源码或已加载包；本轮以同一总计划和当次证据核实，不能据历史数量宣称迁移完成。

## 二、官方 12 项能力 × 本项目状态 × 裁决

| # | 官方能力 | Builder 入口 | 本项目现状 | 裁决 |
|---|---|---|---|---|
| 1 | 工作区人格 | `.workspace(path)` | ✅ 两装配点已用（ProjectAgentKernel/ChatKernel） | 已应用 |
| 2 | 状态持久化 | `.stateStore(...)` | ✅ temporaryState + KernelScopeKey 四维键 | 已应用 |
| 3 | 双层长期记忆 | `.memory(...)`（默认开） | ❌ 双 disable（disableMemoryTools+Hooks） | **保持关**：MEMORY.md 不得当业务事实源（AGENTS.md 红线，业务记忆在 ipd 库表） |
| 4 | 对话压缩 | `.compaction(...)` | ✅ **官方默认即开启**（Builder 字段默认值 = 全默认配置，主模型+动态阈值）；2026-10-02 双装配点已显式固化 | 已应用 |
| 5 | 大结果卸载 | `.toolResultEviction(...)` | ✅ **官方默认即开启**（defaults()：80k 字符阈值+2k 预览+落盘；勘误见 log.md） | 已应用（默认） |
| 6 | 子 agent 编排 | `.subagent(...)` | ❌ disableSubagents+disableDynamicSubagents | **保持关闭**：本轮七分区是工程协作，不授权产品 subagents；IPD 单智能体单轨仍是业务语义（ProjectAgentController 单轨） |
| 7 | 可插拔文件系统 | `.filesystem(...)` | ❌ disableFilesystemTools+disableShellTool | **保持关**：项目智能体是只读检索+文档生成，无沙箱执行需求（场景不适用） |
| 8 | 沙箱 | `.filesystem(new DockerFilesystemSpec...)` | ❌ 同上 | 同上 |
| 9 | 计划模式 | `.enablePlanMode()` | ✅ 已启用（2026-10-02 晚，项目智能体内核；契约测试 ProjectAgentPlanModeHitlTest 3/3 绿） | **已启用**（owner 2026-10-02「官方能力全量启用、禁止禁用」指令推翻本行原「保持关」裁决）：PLAN.md 仅模型侧视图，业务计划权威仍是 INTENT.steps/PlanAggregate；plan_exit 经 `ProjectAgentOfficialPermissions` 显式 askRule 恒 HITL（官方 allowRule 会压制工具自检 ASK，实证见归位文档 K10），复用 WAITING_APPROVAL 单轨 |
| 10 | 技能装配 | `.skillRepository(...)` | ⚠️ 半应用：FrozenProjectAgentSkills（MiddlewareBase 扩展）注入，skillsEnabled(false) 关官方动态装配 | **保持现状**：技能晋升须 owner 拍板写 ipd_action_skill_map（业务闸门），官方动态装配会绕过该闸门 |
| 11 | MCP 白名单 | `workspace/tools.json` | ✅ AgentScopeMcpToolProviderService + toolsConfig deny 3 项 | 已应用（自有实现） |
| 12 | Channel 路由 | `agent.channel(...)`/`GatewayBootstrap` | ⚠️ 半应用：官方 gateway 的 SessionTurnGate/LocalSessionTurnGate/TurnLease 已 import；Channel 未用 | **待评估**：现有 WebSocket 单连接踢旧（1007）是业务语义；Channel 与其重叠度需专项对照 |

## 三、模型容错三件套核实（jar 级）

| API | 2.0.3 真实性 | 本项目动作 |
|---|---|---|
| `HarnessAgent$Builder.maxRetries(int)` | ✅ 存在（透传 flatMaxRetries） | **不加**：已挂 `modelExecutionConfig(MODEL_DEFAULTS)`（maxAttempts=3），ReActAgent:4843 modelExecutionConfig 优先；两处同时配 = 同一事实两源（双轨） |
| `fallbackModel(Model\|String)` | ✅ 存在 | **不上**：ai_model_configs 唯一启用对话模型 MiniMax-M3，无第二模型事实源；owner 指定回退模型后一行即接 |
| `failoverListener` | core 有 flat 字段 | 同上，随 fallbackModel 一起 |

## 四、自研 17 子包 × 官方对应 × 归位路线

自研 `org.ruoyi.service.coding.harness`（254 文件）消费者：① `CodingHarnessController`（HTTP 入口）② `chat.kernel.tool.*`（工具治理，被 AgentScope 轨复用）③ 测试。

| 自研子包(文件数) | 官方对应 | 归位动作 |
|---|---|---|
| context(15) | CompactionMiddleware + CompactionConfig（已挂） | **首批归位候选**：被 DurableHarnessRunProcessor 用（CodingHarness 链）；随该链迁移后删除 |
| loop(37) | core ReAct 循环 | CodingHarness 链迁移后删除（与官方循环重复最大户） |
| model(38)+modelruntime(6) | extensions-model-*（openai/dashscope/ollama/anthropic） | AgentScopeModelFactory 已存在；CodingHarness 链的 RuoYiHarnessChatModelFactory 迁移后归并 |
| plan(28) | core plan mode（项目智能体内核已启用，plan_exit 走官方 ASK HITL）+ IPD WAITING_APPROVAL（业务层） | HarnessPlanCommandService 是 CodingHarness 链组件；随链裁决 |
| approval(18) | core permission 三态管线 | AgentScope 轨已有四层防线；CodingHarness 链的 approval 随链裁决 |
| tool(48) | harness tool(19)+tools(9) | `chat.kernel.tool.*` 治理层是本项目特性（基于官方扩展），保留；harness.tool 内部包随链裁决 |
| artifact(15) | harness artifact(3) | 随链裁决 |
| journal(7) | harness transcript(4) | 随链裁决 |
| recovery(7) | harness 内置恢复 | 随链裁决 |
| runtime(7) | harness gateway/scheduler | HarnessSessionGate 与官方 SessionTurnGate 部分重叠，专项对照 |
| skill(4) | harness skill(33) | FrozenProjectAgentSkills 已是官方扩展实现；自研 catalog 随链裁决 |
| store(6)/event(5)/prompt(6)/app(8)/config(1) | — | 随链裁决 |

## 五、执行序列（分片）

- **P1（已完成 2026-10-02）**：ExecutionConfig 双挂载（真增强：OpenAIClient:710 证实 HTTP 层无重试）+ compaction 显式固化（与官方默认等价，勘误见 log.md）+ 防退化断言 + verify/棘轮/单测全绿。
- ~~P2 toolResultEviction~~（勘误撤销：官方默认即开启，非缺口）。
- **P2'（新，零业务侵入）**：官方 Channel 能力面评估（见 P5 合并）。
- **P3（需 owner 拍板）**：CodingHarnessController 入口去留——保留则整链迁 AgentScope 编排（自研 17 包随之归位删除）；废弃则直接摘链删包。**这是"禁止双轨"的最大单点**。
- **P4（需 owner 拍板）**：langgraph4j（aiflow `org.bsc.langgraph4j:1.8.20`，10 文件）迁 AgentScope 编排（owner 已口头拍板迁，待排期）。
- **P5（专项）**：官方 Channel vs 自有 WebSocket 单连接语义对照；HarnessSessionGate vs SessionTurnGate 重叠收敛。
- **保持关闭并登记理由**（业务语义冲突，非技术缺口）：memory 双层、subagent、plan mode、动态技能、filesystem/sandbox。

## 六、判定标准（防"为开而开"）

充分应用 = ①官方组件为默认底座 ②本项目特性一律走官方扩展点（MiddlewareBase/PermissionCallback/Hook/workspace）③不重复实现官方已有底层 ④与产品语义冲突的官方能力保持关闭且理由落档。开官方能力若引入第二事实源（plans/PLAN.md、MEMORY.md、动态技能绕过 owner 拍板）即违反单一原则。

- marker agentscope-official-migration-matrix-20261002
