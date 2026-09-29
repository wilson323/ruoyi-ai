# AgentScope 官方执行链最佳实践核查（2026-09-29）

范围：核对 `agentscope-ai` 官方 GitHub 的 AgentScope Java 2.0.3 文档与 Service README，分级检索 `/Users/mac/Documents/最佳实践` 的相关资料，并对照当前前后端源码与 `ruoyi-chat/pom.xml` 的 2.0.3 依赖。这里区分框架能力、项目现状和对 IPD 的设计推论，不把官方框架能力等同于本项目已接通的业务能力。官方 [v2.0.3 release](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.3)。

## 可直接采纳的能力与边界

1. **身份与状态**：Java 2.0.3 允许一个 `HarnessAgent` 实例服务多个用户和会话；每次调用通过 `RuntimeContext(userId, sessionId)` 选择 `(userId, sessionId)` 状态槽，同一槽串行，不需要“每个项目初始化一个 Java agent 对象”。`AgentState` 在调用结束时自动保存；单机默认 JSON 文件，多副本需共享状态存储（官方列 Redis / MySQL）。`RuntimeContext` 的其他临时属性不持久化。来源：[Context & AgentState（固定 v2.0.3）](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/building-blocks/context.md)。
2. **工作空间与任务是两种状态**：Harness 工作空间可保存 session log、task record、memory；恢复推理所需的 `AgentState` 则存于独立 `AgentStateStore`。工作空间 `IsolationScope` 与状态槽是两个维度，项目隔离不能只靠选择 `USER` 或 `AGENT` scope；IPD 仍应由服务端将真实 Person、产品线、项目绑定并核权。来源：[Workspace（固定 v2.0.3）](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/harness/workspace.md)、[Context & AgentState](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/building-blocks/context.md)。后半句为 IPD 设计推论。
3. **模型装配**：`ReActAgent` / `HarnessAgent` 可接受 `ModelRegistry` 字符串或显式模型对象；模型提供方在 2.0 中为独立扩展模块，`ModelConfig` 提供重试和后备模型。IPD 已有模型配置来源时，应由服务端按已授权、启用的配置解析和装配，失败明确报错；不能让一个 UI 初始化按钮绕过配置或回退至默认密钥。来源：[Agent（固定 v2.0.3）](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/building-blocks/agent.md)、[官方仓库 README](https://github.com/agentscope-ai/agentscope-java)。IPD 配置策略为设计推论。
4. **流式与人工介入**：`streamEvents` 输出文本、工具调用等 typed events；工具权限可以 `ALLOW / ASK / DENY`。`ASK` 或外部工具执行会让 agent 暂停，宿主收到事件后提交确认或结果再恢复。前端不能把 SSE 断流等同于“任务完成”。项目写操作还需在业务 API / tool 层重新验证真实人员和项目权限，不能仅依赖模型提示或框架工具许可。来源：[Agent（固定 v2.0.3）](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/building-blocks/agent.md)、[Permission System（固定 v2.0.3）](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/building-blocks/permission-system.md)。业务权限部分为设计推论。
5. **长期任务持久化**：`AgentStateStore` 保存的是 agent 的会话上下文，且文档说明通常每次 `call()` 结束才写入；它不能单独充当 IPD 业务任务台账、事件回放或幂等执行凭据。若需要刷新页面、重连、跨节点继续，服务端应先持久化业务 run/task 身份、状态、事件与审批，再驱动 agent 和流式输出。AgentScope Service 的 Managed Agent 流程明确区分“创建 Agent → 创建 Environment → 创建 Session → 发第一条消息”，并要求事件持久化、HITL 可暂停恢复；**创建 Session 本身不会启动 Agent**。来源：[Context & AgentState（固定 v2.0.3）](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/building-blocks/context.md)、[AgentScope Service README](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-service/README.md)。业务台账方案为设计推论；Service README 是主分支文档，随时间可变。

## 对“初始化按钮”的结论

初始化可用于管理员检查并补齐**确实可重建的前置配置**，但不能代替独立的服务端项目智能体执行合同。最小闭环应先具备：服务端定义/解析项目 agent 身份；按真实 Person、产品线和项目授权；从有效模型配置装配；创建并持久化业务 run；流式事件及重连回读；审批/取消/恢复；将工具写操作再做服务端权限与幂等检查。前端按钮只能调用这个合同并展示状态。此段是基于官方能力及当前 IPD 问题的设计推论，**不是 AgentScope 官方声称已经替 IPD 实现的能力**。

## 适用限制

- 本笔记未启动 AgentScope Service，也未验证 IPD 当前运行时联调。
- 官方 Service README 描述完整控制面；本项目不必为了单一项目智能体入口引入整套 Service，现有 Spring 后端可以实现最小业务合同。这个取舍需由本项目架构和验收确定。
- `agentscope-java` 主分支文档可能领先于 2.0.3；以上框架 API 引用固定 `v2.0.3`，Service README 的业务流程为主分支观察。

## 本地资料取舍与当前实现对账

`/Users/mac/Documents/最佳实践` 经文件枚举约 32 万项、13 GB，包含图片、HTML、二手文章、旧方案与原始抓取。本次按主题及来源分级检索，重点核对 `AgentScope-Java-知识库-20260928/00-INDEX.md`、`01-采集说明/采集说明与证据分级.md`、`03-官方文档/`、`06-主题汇编/`，以及根目录的单轨替换方案、PoC 验收报告、Harness 实施稿与 Runtime 评估。没有逐字读取全部 13 GB，不能把这次研究说成对所有文件内容的独立验证。该专题包明确：IMA 订阅库的 81 条正文均未取得（`220030`），标题线索不可当证据；社区转述也不得替代官方文档和当前源码。专题包给出的旧版本和旧工作树状态均以本次源码与官方标签复核为准。

当前源码的四段事实：

| 链段 | 已有能力 | 对项目智能体的缺口 |
| --- | --- | --- |
| 前端 `ai-assistant.vue` | 两种界面模式、项目上下文展示、阶段导航 | `workspaceMode === 'ai'` 时 `send()` 直接返回，输入和按钮禁用；没有独立执行请求 |
| `/copilotkit` | 固定 `ipd_copilot` 的 AG-UI/SSE 副驾桥 | 其他 agentId 返回 404；桥调用 `AiCopilotService.chatStream`，没有项目任务运行合同；`threadEndpoints` 全 false |
| `/api/v1/stage-actions/{id}/ai-execute` 与 `ai_agent_tasks` | 按已实例化阶段动作建任务、异步派发、只读查询 | 面向动作代码，不是任意项目智能体会话；`AiAgentTask` 无 agentId/sessionId/runId/产品线身份；不能直接作为会话运行记录 |
| `AgentScopeChatKernel` | 接收 project/user/agent/session 四维，创建隔离工作区并流出事件 | IPD 服务未调用该内核；当前内核默认关闭文件、Shell、记忆、会话附加持久化与子智能体能力，业务合同仍须另接 |

任务读取还需单独加固：当前 `AiAgentTaskController` 的 GET 端点仅要求内部身份和 AI 文档权限；`AiAgentTaskQueryService.getByTaskId/listByProject` 直接按 ID 查询，未在这两层显示项目可见性校验。项目智能体的运行记录入口不得照搬此读取边界，须做 Person 对该项目/任务的授权正反例。

源文件：前端 `apps/web-antd/src/views/ipd/_shared/ai-assistant.vue`；后端 `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/{CopilotKitRuntimeController,StageActionController,AiAgentTaskController}.java`、`.../copilotkit/AgUiCopilotRun.java`、`.../domain/AiAgentTask.java`、`.../service/AiExecutionTrigger.java`；内核 `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/{AgentScopeChatKernel,KernelScopeKey}.java`。这些是 2026-09-29 当前工作树源码观察，包含其他在途修改；本次没有改动这些文件。

根因是跨模块交付顺序断裂：界面先具备“项目智能体”入口，副驾协议桥与动作任务引擎各自可用，但 B 业务层尚未把可信项目身份、任务生命周期、AgentScope 执行、事件/审批/恢复封装成一个可调用合同，A 前端因此有意拒绝发送。已有 `ProjectBootstrapService.bootstrap()` 在 `ProjectService.create()` 创建项目时自动执行，职责是六阶段与 69 个动作实例化（另含 R221 首个 AI 档动作尾唤醒 `AiExecReviewHook.onBootstrapped`，仍属自研 `AiExecutionEngine` 链）；它不是 AgentScope 执行链的启动开关。把这一点称为“缺初始化”会误导修复方向。

## 推荐的最小纵向切片

1. **定义服务端合同**：选一个真实项目与一个只读引导场景，明确 `agentId=ipd_project_design`（示例名，最终以业务合同为准）、`productLineId`、`projectId`、`sessionId`、`runId`、幂等键、事件类型和错误码。副驾 `ipd_copilot` 保持咨询语义——指产品语义不变，其执行链仍按 ADR-0075 W8/ABCD B4 逐入口迁经唯一 AgentScope 适配口；两个模式分别有独立会话与运行标识。API 可用现有 `/api/v1` code0/message + 字符串 ID 体系，事件流另订可重连语义。
2. **在调用内核前核权**：从真实 Person 会话取操作者；验证租户、项目→产品→产品线归属、在职项目成员、动作权限。请求体 ID 仅作资源选择；角色、userId、权限不取浏览器传值。把核验后的 project/user/agent/session 交给 `KernelScopeKey.of(...)`；不同项目/Person 的正反例先过。
3. **对接唯一任务权威**：复用 `ai_agent_tasks` 的动作事实与幂等机制；如果自由会话运行无法自然表示为一个 `actionCode + stageActionId`，增加与现有任务强外键关联的窄 run 记录或扩展既有任务表列——run 记录不设独立状态权威，完成判定唯一归业务任务行，不得形成第二个任务台账；避免把推理快照、SSE 完成帧当作业务任务完成。先持久化 run/身份/状态，再派发；事件带序号以供刷新重连回读。取消、失败、审批等待都应可查询。
4. **接内核并保留业务闸门**：从 `ai_model_configs` 的已启用配置映射到 AgentScope 模型，缺失或不兼容时明确失败；知识检索先过项目/文档权限；工具真正执行时过现有 `PolicyDecision`、人工审批及幂等效果账本。首片仅开只读能力，写操作在独立验证后逐项开放。AgentScope 的 `AgentStateStore` 负责推理续行，IPD 业务表负责任务、产物、审批、审计。
5. **接前端与验证**：前端按项目调用独立入口，展示运行状态和事件；刷新、重连、取消与切换项目后从服务端回读。验证一条真 Person→真项目→真模型→AgentScope→任务行→事件→刷新恢复的正例，以及未选项目、跨空间、无权限、重复提交、断流和模型失败负例。只在证据闭合后解除当前禁用守卫。

**初始化按钮的合理位置**：上线后可提供管理员的“检查并修复前置配置”，显示模型可用性、项目实例化和存储/服务版本，只修复经核实可重建的数据；操作要幂等、限权并审计。普通用户看到的是“开始项目任务”与明确的准备状态。不存在通过单次初始化自动生成服务端 `agentId`、授权规则、任务状态机或业务验收的捷径。

## 全局落地计划（设计稿，事项状态仍以开发计划看板镜像为准）

**编号消歧（必读）**：本文 G0–G8 仅为该设计稿的私有节点命名，与仓库内三套既有编号**同号不同义**：①《生产就绪ABCD并行任务计划-20260929》的 G0–G5 门（D 轮已按其出具逐门裁决）；②ADR-0075 的 W1–W8 波次与 G1–G3（联合验收→切换/回滚→独立裁决）；③PoC 实验节点 G1–G5。禁止按本文 G 编号新建看板卡或回写状态。映射：G0≈ABCD G0 基线门；G1⊂ABCD G1 合同门＝B4「合同定义」子项（B4 七类消费者迁移对应本文 G7）；G2/G3 复用 ADR W1（身份）、W2（模型，C9/U9 挂账未闭）、W3（工具/权限，permission-system PoC 硬前置）门禁；G4 复用 W3 账本契约＋W6 事件唯一出口；G5＝ABCD A 路，宿主基准＝ADR §10.5 U0-A；G6＝W3/W5/W6 交付面；G7＝ABCD B4 消费者迁移＝ADR W8；G8＝ABCD G3 全域门＋ADR G1–G3 验收切换。

### 决策与范围

目标是让真实 Person 在获授权的 IPD 项目中选择职能体、运行任务、审批工具、取消/恢复、查看持久化产物；保留现有副驾咨询和阶段动作执行。项目智能体首片先做只读引导，再逐项开放业务写操作。覆盖前端 `ruoyi-ipd-web`、IPD 服务、`ruoyi-chat` 内核、模型/知识/工具、数据库和验收；其他 AI 消费者纳入接口盘点与迁移顺序，不能因该入口打通便宣称全局统一完成。

AgentScope Java 2.0.3 原生权限系统逐次拦截工具调用，输出 `ALLOW/ASK/DENY`，`ToolBase.checkPermissions` 可做动态不可绕过的检查；这是工具执行的合适控制点。IPD 的真实 Person、租户、产品线、项目成员关系、业务动作授权、审批人与写入幂等仍由后端提供事实与决策，再注入该控制点及业务服务。现有 `KernelGovernedTool` 已把 `ToolPolicyEngine` 决策接到原生权限接口，但整条治理链（`KernelGovernedTool`→`KernelToolGovernance`→`InMemoryKernelToolEffectLedger`）生产代码零调用、仅 3 个测试类接线；当前 `AgentScopeChatKernel` 还以结构断言强制空 `Toolkit`。**效果账本唯一归属（防双账本）**：对账与不确定态仲裁唯一归自研 `ToolEffectLedgerReconciler`＋`UncertainToolEffectGuard`（ADR-0075 矩阵 #14，任何波次不得删除、不得旁路）；内核 `KernelToolEffectLedger` 仅可作 W3 接线时的账本写入口（同一工具调用恰一次 append/settle），持久化必须落同一持久账本后端——不得出现第二套持久账本或双写。另注意 AgentScope 原生 `ToolsConfig.deny` 是与 `PolicyDecision` 并行的第二道准入点，W3 开放工具时两道门语义必须对齐，不得各自为政。来源：[官方权限系统 v2.0.3](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/building-blocks/permission-system.md)、当前源码 `ruoyi-chat/.../kernel/tool/`。

### 执行图与门禁

| 节点 | 交付物与依赖 | 独立验证及失败路径 |
| --- | --- | --- |
| G0 当前基线 | 固定后端/前端 HEAD、工作树归属（主树与 `.worktrees/poc-agentscope-kernel/` 内核文件已分叉，引用源码事实必须声明所依据工作树）、运行 jar 与 DB schema 版本；对齐现有 ABCD 计划和看板卡，不复制状态源。先处理当前看板记录的构建假绿、环境污染和运行 jar 落后问题。 | `git status`、构建日志、实际 API/DB 版本和 D 路证据逐项可重现；若基线漂移，重新冻结后再比较，禁止把旧结果当新通过。 |
| G1 合同裁决 | 定义 `agentId` 独立于副驾 `ipd_copilot`，同时定义 `productLineId/projectId/personId/sessionId/runId/taskId` 归属、状态机、API 包络、SSE 事件序号、错误码、幂等键及取消/审批语义。指定业务任务行是唯一完成权威，AgentScope `AgentState` 只保留推理状态。对应 ABCD G1 合同门与 B4 的合同定义子项，C/W1/W6 语义见上方映射表。 | 前后端合同测试及状态转换表评审；若业务任务与自由会话无法映射，先在既有任务表上建立窄关联 run，而非把会话或流结束算成功。 |
| G2 身份和权限 | 入口从可信 Person 会话取身份，核对租户、产品线归属、项目成员、动作权限；任务读取、重连、审批、取消和每次工具执行均重新核权。只读/写/外发工具经唯一裁决源自研 `PolicyDecision`（`ToolPolicyEngine`）裁决后，映射注入 AgentScope permission-system 作执行面拦截点（红线 D1，禁止在 AgentScope 侧另立裁决语义）；永久拒绝越权路径。 | 正反例覆盖跨租户、跨产品线、跨项目、离职/撤权、伪造 ID、审批人不匹配；若核权事实缺失则拒绝创建或执行任务。 |
| G3 内核注册与依赖 | 以固定版 2.0.3 接入项目 agent 工厂，注册最小白名单只读工具，使用 `KernelGovernedTool` 原生权限检查——前置门：permission-system 行为 PoC 实证（ADR-0075 U14/W3 硬前置，📄→✅）未完成前只作拦截实验、不计权限验收；模型装配以 W2 补证清单 C9/U9 闭环为前提（当前内核装配源仍是 `chat_model`），从 `ai_model_configs` 已启用配置装配，缺配置直接报可诊断错误；RAG 检索走项目/文档访问门。保持副驾旧链可回退。 | 真实模型首 token、只读工具真实调用、越权工具拒绝、知识跨项目零泄漏；失败时停留只读或回退副驾，不打开空白授权。 |
| G4 持久任务与流 | 任务先入库后派发；持久化 run 状态、递增事件、审批、产物关联、取消意图、失败原因；AgentStateStore 独立存储推理状态。多实例需共享状态与单执行者认领。效果账本按上文唯一归属声明持久化（复用自研账本后端与 Reconciler 对账，不建第二套持久账本）；新入口事件持久化与发布纳入 W6 唯一出口与统一事件 schema，不另起第二套事件管道（D4）；业务写入口按幂等键去重。 | 断流重连、浏览器刷新、进程重启、双实例并发、重复投递、取消竞态、审批超时；发现重复副作用先停写工具并补偿。 |
| G5 前端纵向片 | 在项目 AI 模式使用独立 agent/run API；显示待命、运行、待审批、成功、失败、取消及可回读事件；只有 G1–G4 只读链通过才解除当前禁用守卫。前端空白基准（2026-09-29 现查）：无 agent-run API 适配层、运行记录栏为静态占位、SSE 契约不收 history、workspace canvas/doc 插槽仍占位（steps 已接真实目录）。副驾模式与阶段动作入口保持原语义。 | 真 Person 浏览器端从项目选择到运行、刷新回读与取消；网络断开不误报成功；角色和产品线切换后不可回读他人任务。 |
| G6 写工具逐项开放 | 按业务动作建立工具清单、输入校验、作用范围、审批规则、持久效果账本与补偿；由现有业务服务执行写入并返回资源 ID。招标等高影响动作单列合同及回读。 | 每一项做允许/拒绝/ASK、重复确认、审批撤销、写后 DB 与页面回读；任一项失败仅回退该工具开关，不关闭已验收只读链。 |
| G7 全局消费者收编 | 逐一盘点 IPD 副驾、阶段动作、文档/检索、招标、工作流、平台 Chat/WebSocket、MCP 等的模型入口、权限、任务状态、事件和回退路径；按合同迁移，不把所有调用一次性改写。 | 消费者矩阵每行有现状入口、责任模块、迁移决策、旧/新行为对照测试与真实运行证据；未迁移项明确保留现状，不声称全局统一。 |
| G8 独立验收与发布 | D 路复核 HTTP/DB/浏览器、权限负例、任务恢复、模型故障、SSRF、测试发现率、回滚与运维指标；只有所有 P0 关闭才申请业务验收和逐步放量。 | 现有 `check:type`/Vitest/`build:antd`、Java 定向及模块测试、真实 Person E2E、DB 审计记录、双实例演练均留原始证据；任何关键红项保持 `PARTIAL` 并修复后重跑。 |

关键依赖：G0→G1→G2/G3→G4→G5→G6→G7→G8；W6 事件/审计与 G2–G6 同步设计，不能最后补。责任界面沿用现有 ABCD 计划原文分工：A 前端，B IPD 业务与数据，C AgentScope 内核与工具，D 独立验证；G0/G1/G8 由总控协调。每节点的测试红项由所属写面修复并重跑，不能以“兄弟在途”或跳过测试作为完成证据。

### 数据与接口建议（待 G1 合同裁决）

- `GET /api/v1/projects/{projectId}/agents` 返回此 Person 当前可运行的服务端 agent 列表及不可用原因；`POST .../agent-runs` 创建带幂等键的 run；`GET .../agent-runs/{runId}` 和 `GET .../events?after=` 回读；审批和取消为独立受权命令。所有 ID 为字符串，遵守 IPD `code=0/message`；SSE 事件协议明确例外。路由名是建议，不是当前已实现 API。
- 状态至少区分 `PENDING/RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/CANCEL_REQUESTED/CANCELLED`；服务端在业务副作用及产物确认后写 `SUCCEEDED`。每个 run 绑定不可变项目/Person/agent 快照与可重新求值的授权依据，事件按 run 单调序号回放。
- 管理员“初始化/检查”只做幂等的配置诊断或有明确授权的补建，展示模型、知识库、存储、AgentScope 工具注册和版本状态；不得从浏览器请求生成权限或绕过任务合同。先交付 G1–G5 的真实链，再决定是否有此运维按钮的必要。

### 放量顺序与停止条件

1. 开发环境只读项目引导：一名真实 Person、一条真实产品线和项目、真模型，验证 1 次成功与核心越权负例。
2. 扩到不同角色、项目、产品线与重启/多实例场景；任务与事件可恢复后，再开启逐项写工具的审批试点。
3. 逐消费者迁移并按功能开关放量；保存旧链回退开关，但禁止回退路径绕开业务权限。监测拒绝、审批等待、失败、重复副作用、事件延迟与成本。
4. 任一跨项目泄露、未审批写入、重复副作用、任务假成功或取消后继续写入即停止放量，关闭相应 agent/工具并按效果账本修复。

当前状态：`DRAFT_ONLY`。这是依据官方固定版本文档、本地资料证据分级与当前源码形成的实施合同候选；没有在本轮启动服务、改写业务代码或取得真链验收。既有看板和 ABCD 计划仍是执行状态权威。2026-09-29 三路只读审查（后端执行链/前端合同/全局一致性）结论：9 项源码事实断言全部属实；2 处 P0 已就地修复（G 编号撞车消歧、效果账本唯一归属），4 处 P1 已修复（G2 裁决唯一源、窄 run 边界、G3 硬前置、落档登记见 log.md）；另知 D2b 轮已登记 P0 残留（`PocSseController` 仍在 src/main 注册、userId 自报，违 ADR-0075 D9），归 A/C 路原卡处理，不在本文范围。
