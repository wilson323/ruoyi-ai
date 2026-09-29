# D2b 后端与全局 AI 智能体能力深度反思 — AgentScope Java v2 全文档契约对照

> 任务来源：用户直令「系统性梳理全局项目前后端代码深度思考反思，输出本项目 AI 智能体能力是否完整实现、是否全局一致性；结合 java.agentscope.io/v2/zh/docs 所有内容深度反思本项目智能体是否完整实现」（2026-09-29）
> 审计对象：`/Users/mac/Documents/ruoyi-ai`（后端，HEAD `f79f580b`）+ `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd`（前端，HEAD `10ddf9c5` + A 路在途 dirty）+ `.worktrees/poc-agentscope-kernel`（`d8231596`）
> 契约参照系：AgentScope Java v2 官方文档全集（`java.agentscope.io/v2/zh/docs`，经 llms.txt 全 208 页索引 + architecture / going-to-production / permission-system 原文精读）+ 本仓 `agentscope-harness` skill 对照表 + ADR-0075 24 项三选一矩阵
> 写面纪律：D 路只读被测源码，本文只写 `docs/ipd-系统说明/验收/**`；本轮**不实施任何修复**（修复属 A/B/C 路写面与原卡）
> 证据时间戳：2026-09-29 03:00–04:30 PDT。证据分级：【本席位】= 本席位 grep/读源/实跑一手证据；【派单】= CodeReview 子席位产出、关键断言经本席位抽查复核；【子席位未复核】= 仅转述，已在 §8 显式标注

---

## 0. 三问裁决（结论速览）

**① AI 智能体能力是否完整实现？—— 否，PARTIAL。**
以 AgentScope Java v2 全文档能力面（Building Blocks 7 域 + Harness 10 域 + Going-to-Production 六组件）为参照系逐项对照：**已实现 2 / 部分 11 / 缺失 7**（§2 总表），且成熟度天花板是「测试通过（单模块）」——**「真链通过」「生产可用」两栏全空**（与 C 路差距矩阵口径一致）。以项目自身承诺（ADR-0075 W1–W8、C 路 P0 清单 6 项）衡量：**W1–W8 零波次 DONE**，P0 清单 6 项中 1 项已解（kernel 补提交）、5 项仍开。

**② 是否全局一致？—— 主干一致，但有 1 个 P0 双轨残留 + 4 处文档/代码漂移 + 3 处"半接线"。**
主干证据（好）：FE↔BE 抽核 20+ 端点路径/方法/参数全对齐、`expectedVersion` 双端同走 query、SSE 四帧 + code0 包络单一通道、单一聊天宿主（ai-assistant.vue）与单一卡片注册表、`ai_model_configs` 是 IPD 链唯一模型权威【本席位】、KnowledgeAccessGate 全部在检索前【派单+抽查】。
坏消息（§3）：PoC 实验面仍在 src/main 被注册（P0）；ADR/注释/基线数字与代码三处漂移；FE 卡片两条通道在生产不可达。

**③ 对照 AgentScope v2 文档，本项目智能体"完整"吗？—— 不完整，且缺口集中在 Harness 工程化四件套。**
AgentScope 2.0 的核心命题是「让智能体稳定长跑」：状态可恢复、上下文有界、能力可沉淀、危险动作受控。本项目受控执行（Plan 版本化 + 审批闭环 + PolicyDecision）**反超**官方形态（官方无 Plan 版本号/账本/审批回执，见 §6 复核），但**长跑四件套——长期记忆（MEMORY.md）、subagent、toolResultEviction、跨副本可恢复——全部缺失**，多维预算有裁决无计量。对一个产品承诺含「AI 代理全阶段执行闭环」的系统，这正是产品化最需要的一块。

---

## 1. 审计基线与方法

| 项 | 值 |
|---|---|
| 后端基线 | `f79f580b fix(ipd-c): validate AgentScope chat entry delegation`（6da7f4f6 / 8caf7097 / f79f580b 三连为 C 路收口，含 ADR-0075 与差距矩阵）；工作树 dirty 69 条【本席位】 |
| 前端基线 | `10ddf9c5` + A 路在途 dirty 28 文件（1687+/237-）【派单】 |
| 方法 | 蜂群并行：CodeReview 子席位 ×2（后端 AI 能力实审 / 前端能力与契约实审）+ 本席位独立复核全部关键断言（grep/读源/实跑）+ AgentScope 官方文档精读（architecture / going-to-production / permission-system 三页原文，llms.txt 全索引） |
| 复核覆盖 | §3 全部 P0/P1 断言 + §4 全部出入项为【本席位】一手证据；派单结论未一手复核的仅 §8 列出 |
| 门禁实跑 | `bash .claude/skills/agentscope-harness/scripts/verify.sh` → **EXIT=1**（C2 双树假红，见 #7）；`skill-lint=0 env-probe=0`【本席位】 |

---

## 2. 能力完整性总表（AgentScope v2 全文档面 × 本项目三套实现）

「本项目」= AgentScope 内核（`org.ruoyi.chat.kernel` 13 文件）+ 自研 harness（`org.ruoyi.service.coding.harness` 17 子包 254 文件）+ 业务 AI 链（IPD AiGateway / aiflow / ruoyi-chat）。判定口径沿用五态：未实现 / 源码候选 / 测试通过 / 真链通过 / 生产可用——**本轮最高只到「测试通过（单模块）」**。

### 2.1 Building Blocks（官方 7 域）

| 域 | 本项目实现 | 证据 | 判定 |
|---|---|---|---|
| Agent（定义/装配） | AgentScopeChatKernel.builder（name/sysPrompt/model/workspace/stateStore）+ 自研 HarnessRunRequest/HarnessOwner + 业务数字员工（AgentController） | AgentScopeChatKernel.java【本席位】 | **部分**（装配通、多 agent 编排面缺） |
| Message & Event | KernelEventFrames 把 AgentEvent 映射四帧（text_delta/error/…）；自研 HarnessEventHub + HarnessDeltaEventPublisher | KernelEventFrames.java、AgentScopeChatKernel.java:358-360【本席位】 | **部分**（事件流出在、官方 ContentBlock 统一消息模型未采用） |
| Middleware 五阶段钩子 | 无；观测仍走 15 个 langchain4j listener（observability/） | 派单 grep `middleware` 主树零命中 | **缺失**（=U10/W6） |
| Model（provider SPI） | KernelModelSelector 唯一装配口 + ModelRegistry（MiniMax 实证）；13 家 langchain4j 直连保留（棘轮 113） | KernelModelSelector.java、langchain4j-count.json【本席位】 | **部分**（8 厂商未冒烟=U1；三轨现状见 #4） |
| Permission System（三态+HITL） | 自研 PolicyDecision(ALLOW/ASK/DENY) + ToolPolicyEngine + approval/ 18 文件闭环 + REST plan/approve；内核 KernelToolGovernance 做单源映射；**W1 工具面强制为空**（disable* 九连） | AgentScopeChatKernel.java:265-275【本席位】、KernelToolGovernanceTest.java | **部分**（裁决/审批自研面完整；执行前拦截未在真实工具路径验收=U2） |
| Tool | 自研 tool/ 48 文件（执行协议+账本+白名单）+ MCP 走 langchain4j provider + 内核 KernelGovernedTool/KernelToolEffectLedger | kernel/tool/ 7 文件【本席位】 | **部分**（W3/W5 未迁） |
| Context & AgentStateStore | MysqlAgentStateStore 已接（createIfNotExist=false，DDL 草案未 apply）；无 Redis/JSON 变体；四维键 KernelScopeKey 收口 | AgentScopeChatKernel.java:8,124、KernelScopeKey.java【本席位】 | **部分**（多副本恢复未验=U12） |

### 2.2 Harness 10 域（官方「长跑工程底座」）

| 域 | 本项目实现 | 判定 |
|---|---|---|
| Architecture（能力按需叠加） | 内核 builder 显式关闭 9 项（filesystem/shell/memory/memoryHooks/transcript/sessionPersistence/subagents/dynamicSubagents/dynamicSkills/defaultWorkspaceSkills）→ W1 纯对话面，形态与官方「按需叠加」一致【本席位 AgentScopeChatKernel.java:265-275】 | 一致（设计面） |
| Workspace（AGENTS.md/MEMORY.md/tools.json/skills/subagents/plans/agents） | workspaceFor 三级分桶（project/user/agent，fail-closed 拒注入）+ AGENTS.md 注入；MEMORY.md/sessions/subagents/plans 无；自研 CodingWorkspaceService 是另一形态 | **部分** |
| Memory（memory/流水账→MEMORY.md 双层） | 内核 `.disableMemoryTools().disableMemoryHooks()`；主树 grep `MEMORY.md`/`memory/` 零命中 | **缺失** |
| Compaction + toolResultEviction | 自研 ContextEngine（token 预算 + ContextPins + 压缩熔断）真实消费于 DurableHarnessRunProcessor；官方 .compaction/.toolResultEviction 未接，后者全树零命中 | **部分** / **缺失**（后者） |
| Subagent（声明/后台委派/反向推送） | 自研 17 子包无 subagent；内核 `.disableSubagents()` | **缺失** |
| Skill（四层合成+市场+自学习） | HarnessSkillCatalog(+Factory/Tools) 有消费点（HarnessToolRuntime/DurableHarnessRunProcessor）但**仅 1 个 harness 测试文件**；四层同名优先级/skillRepository 市场无 | **部分** |
| Plan Mode（只读规划+HITL） | 自研 PlanAggregate(revision 乐观锁) + CanonicalPlanHasher(SHA-256) + StalePlanRevisionException + approval 闭环 + REST plan/approve,revision——**比官方强**（官方 PLAN.md 无版本化）；官方 enablePlanMode 未接 | **已实现**（自研面） |
| Filesystem 三模式 / Sandbox | tool/command/DockerRuntime 容器命令沙箱；官方 RemoteFilesystemSpec/SandboxFilesystemSpec/快照恢复未接 | **部分** |
| Channel（会话管理/多 agent 路由/流式） | 自研 SSE 出口 + LocalSessionTurnGate（进程内）+ MpChatWebSocketHandler WS；官方 Channel/GatewayBootstrap 未接 | **部分** |

### 2.3 Going-to-Production 六组件 + 多副本 checklist

| 组件（官方生产清单） | 现状 | 生产可用？ |
|---|---|---|
| Agent State Store | 仅 MySQL，DDL 未 apply、重启回读未验 | ❌ |
| Filesystem | 仅本地/容器命令；无 Remote 共享存储 | ❌ |
| Skill 集中管理 | 无 skillRepository，仅本地目录 | ❌ |
| Sandbox + **Snapshot** | 有命令沙箱、**Snapshot 完全缺失**（官方称"沙箱的分布式生命线"） | ❌ |
| Observability | 自研 journal 脱敏（JournalSecretRedactor）+ 15 langchain4j listener；Middleware 未迁 | ❌ |
| 多副本 checklist | 分布式会话锁缺失（LocalSessionTurnGate/HarnessSessionGate 均进程内）、崩溃恢复未验、InMemoryKernelToolEffectLedger 崩溃即丢【本席位】 | ❌ |

### 2.4 Integration 面全景矩阵（overview 十组 × 本项目，2026-09-29 owner 要求补全）

> 来源：`https://java.agentscope.io/v2/zh/integration/overview` 全景 + owner 逐页提供原文的四个重点扩展（Mem0 / HayStack / AG-UI / WeCom）。接入判定口径：替换 / 包装 / 保留 三选一，与 ADR-0075 矩阵同源。

| 组 | 官方扩展 | 本项目现状 | 接入判定与契约要点 |
|---|---|---|---|
| 模型提供商（10 家） | `model-openai` / `model-dashscope` / `model-gemini` / `model-anthropic` / `model-ollama`…（ModelRegistry id 如 `openai:<model>`） | 13 家 langchain4j 直连保留（棘轮 113=baseline）+ KernelModelSelector 唯一装配口（MiniMax 实证） | **保留**自研直连至 W2 回滚窗口关闭；新增模型只能走 KernelModelSelector + ModelRegistry，禁增 langchain4j 直连面（D8 棘轮） |
| 分布式存储 | Redis / MySQL-JDBC / OSS（AgentStateStore + BaseStore + SnapshotSpec + SandboxExecutionGuard） | MysqlAgentStateStore 已接（DDL 未 apply）；效果账本 InMemory | **包装**：MySQL-JDBC 首选（本仓 MySQL 8.0.46 底座）；多副本 P0 才引入 Redis；对应 U12 |
| 沙箱执行环境 | Docker（harness 内置）/ K8s / AgentRun / Daytona / E2B | DockerRuntime 命令沙箱；Snapshot 缺失 | **保留** Docker 内置；SandboxSnapshotSpec 按缺口补；云沙箱不属 IPD 需求 |
| 记忆 | **Mem0**（`agentscope-extensions-mem0`）/ 百炼记忆 / ReMe（均实现 LongTermMemory） | 官方内生记忆（MEMORY.md + memory/）被 disableMemoryHooks/Tools 关闭，主树零命中 | **暂缓**：先开内生 Memory（W 波次）；Mem0 仅在需跨 agent 长期事实记忆时评估。**接入契约要点**：Mem0 仅 agentName/userId/runName **三维** ID，而本项目隔离键是四维（project×user×agent×session）——project/agent 维须用 metadata 过滤表达（如 `project_id`/`category` tags），且 metadata **同时作用于写入与检索**；接入前必须补「四维键→Mem0 三维+metadata」映射契约与串桶负例（防记忆跨租户泄漏） |
| RAG 知识库 | Simple / 百炼 / Dify / **HayStack**（`agentscope-extensions-rag-haystack`）/ RAGFlow（均走 Knowledge 接口） | 自研 embed 5 + vector 3 + KnowledgeAccessGate（真库权威）；AgentScope Knowledge 仅 PoC 实证（G5 正负例） | **保留**自研 RAG 链为权威（权限门在自研侧）；HayStack/Dify/RAGFlow 仅作 Knowledge 接口可选后端适配。**HayStack 契约要点**：**管控分离**——`addDocuments()` 抛 UnsupportedOperationException，索引流水线归 HayStack 侧；若选型须确认本仓知识库写链（AiDocEmbeddingService）不双写，避免双侧索引状态不一致 |
| 技能仓库 | Git / MySQL / PostgreSQL / Nacos（AgentSkillRepository） | HarnessSkillCatalog 本地目录，无 skillRepository 市场 | **包装**（后置）：MySQL 技能仓库与本仓技能表最贴合；四层合成优先级须与 workspace 契约对齐 |
| Channel 适配器 | 钉钉 / 飞书 / GitHub / GitLab / **企业微信 WeCom**（`agentscope-extensions-channel-wecom`） | 自研 SSE + MpChatWebSocketHandler；IPD 有企微 Mock 绑定需求（P0-7.4） | **候选适配**：WeCom 扩展（WeComCrypto 加密回调 + MsgId 去重 + 防循环 + GatewayBootstrap）与 IPD 企微 Mock 契约对齐评估；钉钉/飞书/GitHub/GitLab 不属 IPD 需求，不计缺口 |
| 智能体协议 | A2A / **AG-UI**（`agentscope-extensions-agui` + `agentscope-agui-spring-boot-starter`）/ Agent Protocol | AG-UI **已有消费面**（AgUiCopilotRun / AgUiFrameTranslator 进 AiGateway） | **包装**：现有自研翻译层与官方适配器对照收口。**AG-UI 契约要点（接入必须对齐）**：① RUN_ERROR 与 RUN_FINISHED **互斥终态**（仅旧客户端开 `emitRunFinishedAfterError`）；② HITL interrupt 统一 `reason:"tool_call"`（权限确认用 `metadata.agentscope.interruptKind=permission_confirm`，禁写 `reason:"confirmation"`；`payload.editedArgs` 是全量替换非 merge）；③ **forwardedProps 非可信身份源**——服务端身份恒走认证链/resolver 注入（与 SEC-API-01/D9 同一红线）；④ sessionId 恒取 threadId，与 KernelScopeKey 四维键的映射须显式收口；⑤ 子 agent 事件默认落 `subagent.*` CUSTOM 命名空间，不污染父 run 流 |
| 基础设施/中间件 | Higress / Nacos / Scheduler（Quartz/XXL-Job） | RuoYi 自带 Quartz（OPS-04 可靠调度底座已交付） | **保留**自研调度底座；Higress/Nacos 不属当前部署形态 |
| 生态 | Chat Completions Web / Studio / Training | 无 | 不引入（非 IPD 交付面） |

### 2.5 十七项能力域 × 覆盖勾稽（owner 逐项确保清单，2026-09-29）

| # | 能力域（官方文档页） | 本项目落点 | 判定 | 缺口 |
|---|---|---|---|---|
| 1 | 消息与事件（building-blocks/message-and-event） | §2.1 Message & Event 行 | 部分 | ContentBlock 统一消息模型未采用 |
| 2 | Middleware（building-blocks/middleware） | §2.1 Middleware 行 | **缺失** | U10/W6（观测仍走 15 langchain4j listener） |
| 3 | Model（building-blocks/model） | §2.1 Model 行 | 部分 | U1/C9（8 厂商未冒烟；三轨并存） |
| 4 | Permission System（building-blocks/permission-system） | §2.1 Permission 行 | 部分 | U2（执行前拦截未真路径验收） |
| 5 | Tool（building-blocks/tool） | §2.1 Tool 行 | 部分 | W3/W5 未迁 |
| 6 | 上下文与 AgentState（building-blocks/context） | §2.1 Context & AgentStateStore 行 | 部分 | U12（多副本恢复未验） |
| 7 | Harness（docs/harness/* 总纲） | §2.2 全表 | 部分 | 见 8–17 分域 |
| 8 | Harness 架构（docs/harness/architecture） | §2.2 Architecture 行 | 一致（设计面） | — |
| 9 | 上下文压缩（docs/harness/compaction） | §2.2 Compaction 行 | 部分 | toolResultEviction（落盘+head/tail 预览）缺失 |
| 10 | 工作区 Workspace（docs/harness/workspace） | §2.2 Workspace 行 | 部分 | 用户级覆盖目录/两层读/IsolationScope 未接；契约要点见本节附 |
| 11 | 记忆 Memory（docs/harness/memory） | §2.2 Memory 行 | **缺失** | 内生 MEMORY.md/memory/ 未开；Mem0 判定见 §2.4 |
| 12 | 文件系统 Filesystem（docs/harness/filesystem） | §2.2 Filesystem 行 | 部分 | RemoteFilesystemSpec/IsolationScope 未接 |
| 13 | 沙箱 Sandbox（docs/harness/sandbox） | §2.2 Sandbox 行 | 部分 | Snapshot（分布式生命线）缺失 |
| 14 | 子 Agent（docs/harness/subagent） | §2.2 Subagent 行 | **缺失** | W 波次 |
| 15 | 技能 Skill（docs/harness/skill） | §2.2 Skill 行 | 部分 | 四层同名优先级/skillRepository/自学习闭环无 |
| 16 | 计划模式 Plan Mode（docs/harness/plan-mode） | §2.2 Plan Mode 行 | **已实现**（自研面，版本化强于官方） | 官方 enablePlanMode 未接（保留自研） |
| 17 | Channel（docs/harness/channel） | §2.2 Channel 行 | 部分 | GatewayBootstrap/官方 Channel 未接；WeCom 见 §2.4 |

**Workspace 契约要点（官方 workspace 页原文精读，owner 2026-09-29 提供）**：① AgentState 不入工作区——存独立 AgentStateStore（默认 `~/.agentscope/state/<agentId>/`），与本项目 MysqlAgentStateStore + KernelScopeKey 方向一致；② 多租户两层——定义按用户覆盖目录（`<userId>/skills/` 等）、进化按 IsolationScope（USER 默认/SESSION/AGENT/GLOBAL）命名空间，本项目 workspaceFor 三级分桶是同构子集，接 Remote/Sandbox 模式须补 IsolationScope 语义（四维键→命名空间映射）；③ 写工作区必须走 `getWorkspaceManager()` 而非 `java.nio.Files`（沙箱/KV 模式下后者写错地方）；④ 生产多副本若配 RemoteFilesystemSpec/SandboxFilesystemSpec 而未换分布式状态存储，build() 直接抛 IllegalStateException——官方强制护栏，对应 U12；⑤ agent 进化五通道（长期记忆/自学习技能/计划文件/工具结果落盘/会话日志）全部落在工作区树并享受租户隔离——本项目接对应能力时以此为验收口径。

---

## 3. 发现总表

| # | 维度 | 位置与证据 | 分级 | 新/旧 |
|---|---|---|---|---|
| 1 | **PoC 实验面未清场（D9 红线违反）** | `PocSseController.java:28-40`【本席位】：src/main 内 `@RestController @RequestMapping("/poc/kernel")`、无 @Profile/@ConditionalOnProperty，`@RequestParam userId/projectId` 默认 U1/P1 **自报身份**直传 KernelScopeKey；`PocKernelSupport.java:75-94`【本席位】硬编码 `jdbc:mysql://127.0.0.1:13306/ipd_poc` + 读 `.codex` 凭据文件；该文件 grep 无任何 disable* → **默认工具面**（ADR §8 实录默认注册 write_file/edit_file/execute/web_fetch）。ADR-0075 §2/W1 明文「该模式不得进正式面、cutover 时删除」，现实仍在且被组件扫描注册 | **P0** | 旧（承诺未兑现） |
| 2 | 测试面失衡 + 证据不可复跑 | 自研 harness **254 个 main 文件 ↔ 全树仅 1 个测试文件**（ExecuteProcessToolSecurityTest）【本席位 find=1】；ADR §8-§10 引述的 48/48、38/38、26/26、54/54 均出自 `/tmp/agentscope-isolated-*` 隔离快照，**测试文件未入库、不可复跑** | **P1** | 旧 |
| 3 | AI 能力"半接线"被 UI 完整假象掩盖 | ① 四帧 SSE `done.card` 通道前端整套渲染、后端永不发送（AiCopilotController.java:132-137 done 仅五键 status/tokenPrompt/tokenCompletion/latencyMs/fillPayload【本席位】）；② CopilotKit `run` + `sub-stage.guide` 工具卡后端已交付、前端零 run 调用、卡片注册表无该 type（【派单】）；③ 多轮 history 后端 AiCopilotReq 支持 8 轮、GET 流契约三参、FE historyTurns 整段注释（【派单】）；④ docType/knowledgeIds RAG 限定有后端无 UI | **P1** | 新（对照 D2a 增量） |
| 4 | 模型链三轨并存 | IPD 链 `ai_model_configs.currentEnabled()` 唯一权威（GatePrecheckService:282 / BidResponseCheckService:99 / AiGenerationService:108 等【本席位】）✅；ruoyi-chat SSE/WS 走 `chat_model`(ChatModelVo)；aiflow `WorkflowUtil.java:182 selectModelByName` 走 chat_model【本席位】；`ai_model_configs` **未接** AgentScope 装配（KernelModelRequest.from(ChatModelVo)），即 U9/C9 挂账属实 | **P1** | 旧 |
| 5 | 注释/ADR 与代码矛盾 | ① AgentScopeChatKernel.java:62-64 注释称「开关关**或 Bean 缺席**→回旧链」，实际 ChatServiceFacade.java:209-211 / MpChatWebSocketHandler:211-213 对「开关开 + Bean 缺席」**抛 IllegalStateException fail-closed**（行为正确、注释误导回滚判断）【本席位】；② ADR-0075 两处写棘轮基线 111，实为 113；③ FE ipd.ts 注释「悬浮钮已移除」vs layouts/ipd.vue 仍挂 ipd-ai-fab（【派单】） | **P2** | 旧 |
| 6 | 棘轮基线被手工上调 | `scripts/baselines/langchain4j-count.json` growth_log：2026-09-29T06:48:37Z **111→113「manual --update-baseline」**，reason 为空泛手工更新——「只减不增」被放宽且未登记理由/审批（对照 ratchet-data-guard 精神）【本席位】 | **P2** | 新 |
| 7 | 契约门禁双树假红 | `verify.sh` EXIT=1，C2「复合键拼接散落到 2 个文件」实为**同一份** KernelScopeKey.java 的主树 + worktree 副本被重复计数（日志两行路径互为副本）【本席位 /tmp/d2b-verify-20260929.log:71-77】；单树扫描即 PASS（C 路 §6 已证）。工具侧修复（排除 .worktrees/）归属待登记 | **P2** | 旧 |
| 8 | 跨副本与效果账本（C 路 P0 ②③持续开） | LocalSessionTurnGate + HarnessSessionGate 均进程内锁；`InMemoryKernelToolEffectLedger`（kernel/tool/）未持久化，崩溃即丢【本席位】 | **P1** | 旧 |
| 9 | D2a 存留未修 | flow.vue:131 P1（自造 Error 被 ipdErrorText 吞）**仍在**；`ipd:guide-sub-stage` 自产自销仍在；api→views 反向依赖、5 页本地 STATUS_TEXT 并存（D2a #3/#4/#8）未动（【派单】对照复核） | P1×1+P2 | 旧 |
| 10 | 死代码/误用雷 | `api/chat/chatconfig` 指向 `/system/config` 与 SysConfigController 撞路径、零消费者；`chatCopilot` 同步端点 FE 死导出（【派单】） | 提示 | 旧 |

**未发现**：第二套聊天宿主 / 第二套卡片体系 / 双请求通道 / 双码表（card-registry 4 型仍是单一事实源）——用户红线「禁双轨」在前端面守住【派单+本席位抽查】。

---

## 4. 全局一致性深度反思（多源对账）

### 4.1 ADR-0075 声称 ↔ 代码现状（逐条核过）

| ADR 声称 | 现状 | 判定 |
|---|---|---|
| KernelScopeKey 已迁正式包 chat.kernel | ✅ 现查存在，正式桥/PoC/测试同引用 | 一致 |
| fallback(ModelPlan) 死方法已删 | ✅ kernel 包 grep 零命中 | 一致 |
| 13 家 langchain4j 直连保留（矩阵 #8 M1） | ✅ 保留，棘轮=基线 113 | 一致 |
| 开关默认 false、回滚双保险 | ✅ `@ConditionalOnProperty(matchIfMissing=false)` + `@Value(...:false)`，application*.yml 无 chat.kernel 段 | 一致 |
| KnowledgeAccessGate 在检索前 | ✅ ChatServiceFacade:698,704 先 collectKnowledgeIds 再 augmentAgentInput；aiflow KnowledgeRetrievalNode:227→:248 同序 | 一致 |
| 「Bean 缺席即回旧链」（内核注释/回滚点表述） | ❌ 实为 fail-closed 抛错 | **出入**（#5①） |
| 棘轮基线 111 | ❌ 113（且 09-29 手工上调） | **出入**（#5②/#6） |
| 「PocSseController 不得进正式面」 | ❌ 仍在 src/main 被注册 | **出入**（#1，最大） |
| U1–U15 挂账 | 抽查 U2/U9/U12/U13 全部属实（工具路径未验/ai_model_configs 未接/跨副本锁缺/usage 计量缺） | 一致（挂账诚实） |

### 4.2 双轨红线 D1–D10 现状（ADR §5.1 逐项）

| 风险 | 现状【本席位/派单】 |
|---|---|
| D9 PoC 样例流入正式面 | **真实存在**（#1）——十项中唯一已兑现为现实的风险 |
| D1/D2 双裁决/双工具目录 | 未触发（内核 W1 工具面强制空 + KernelToolGovernance 单源映射） |
| D3 双压缩 | 未触发（官方 compaction 未接，仅自研 ContextEngine） |
| D4 双轨观测 | **潜在**：HarnessEventHub 与内核 streamEvents 双发布路径并存，W6 未做出口唯一化 |
| D5 双门并发 | 无同轮双门，但**两把进程内锁并存 + 跨副本全缺**（#8） |
| D8 langchain4j 反弹 | 棘轮 113=基线未增长，但基线被手工上调 2 条（#6） |
| D10 门禁改宽 | 未发生；反而门禁自身假红待修（#7） |

### 4.3 深层病根（映射 R25「5 类病根」）

| 病根 | 本轮证据 | 机制化处方（不补自觉） |
|---|---|---|
| ① 改主代码测试没跟上 | #2（254:1）、#3（卡片通道死路径零测试拦截） | harness 测试面**下限哨兵**（main:test 比例阈值会红）；SSE 契约测试升级为**双向**（前端解析的每个可选字段必须有后端生产者断言，反之亦然） |
| ③ 规则靠人肉对账 | #1（「cutover 时删除」只写在 ADR）、#5（注释与代码矛盾） | 「PoC 面禁入 src/main」进 pre-commit 门禁（`org/ruoyi/chat/poc/**` 在 main 源集即红，或强制 @Profile）；注释性回滚语义以测试钉死（Bean 缺席分支补负向用例） |
| ④ 契约门禁 | #3 双通道、#6 棘轮放宽 | 棘轮 `--update-baseline` 强制 reason + 双人审（挂 ratchet-data-guard 同级保护）；FE↔BE 契约基线把 SSE done 帧键纳入棘轮 |
| ⑤ 多事实源无对账 | ADR/注释/基线/FE 注释四处漂移 | ADR-0075 每波收口跑「声称↔代码」自动对账（现有 §10.4 对账门是人肉清单，缺脚本化）；**/tmp 隔离快照数字不得写入 ADR**（证据必须入库可复跑） |

**总反思**：本项目的「能力面广度」并不差——比官方 AgentScope 还多了 Plan 版本化、效果账本、审批回执三件治理资产。真正的缺口是三类**闭环**：①**执行闭环**（W1–W8 零波次收口，PoC 清场、入口迁移、ai_model_configs 接线全部停在"接线在、开关关"）；②**证据闭环**（真链证据为零，关键测试数字在 /tmp 不可复跑，254:1 的测试面无法为治理资产作证）；③**一致性闭环**（同一事实写在 ADR/注释/基线/FE 四处而无对账门）。这与 D2a 前端结论同构：**"写得干净"已内化，"约定没有对应的红灯"就会漂移**。

---

## 5. 「是否完整实现」分层裁决

| 层 | 完整性 | 依据 |
|---|---|---|
| IPD 产品 AI 功能面（副驾/建议/生成/预审/比对/文档/知识/员工/MCP UI） | **形态完整、接线部分**：8 项端到端能（选模型/流式对话/引用/确认卡/知识管理/MCP 目录/员工管理/子任务展示）、3 项不能（多轮 history、RAG 限定、CopilotKit run）【派单】；卡片两通道生产不可达 | 面 ⭕ / 线 ⚠ |
| Agent 平台能力（对照 AgentScope v2 全景） | **2/18 已实现、11/18 部分、7/18 缺失**（memory 长期记忆、subagent、toolResultEviction、middleware、分布式锁、usage 计量、Snapshot） | ❌ |
| 受控执行治理（Permission/Plan/账本/预算/脱敏） | **本项目强项**：三态裁决 + 审批闭环 + Plan 乐观锁/hash + 效果账本 + journal 脱敏，其中 5 项 AgentScope 官方无对应物（§6） | ✅（但账本 InMemory、预算未贯穿=裸奔） |
| 生产就绪（Going-to-Production 六组件 + 多副本） | **0/6 生产可用**；开关默认关本身即"尚未生产"的诚实表达 | ❌ |

**裁决：本项目 AI 智能体能力 = 「未完整实现（PARTIAL），成熟度=测试通过（单模块），真链证据 0」。**
量化锚点：AgentScope v2 参照系缺口 7 项缺失 + 11 项部分；项目自身承诺 W1–W8 完成 0/8、C 路 P0 清单 6 开 5；前端 3 项核心交互不可用。**不构成对产品方向的否定**——开关默认关 + fail-closed + 挂账诚实，说明团队在按波次纪律推进；但任何「AI 能力已完整」的表述在证据面前不成立。

---

## 6. 对 ADR-0075「替换/包装/保留」三选一矩阵的独立复核

矩阵 24 项（替换 6 / 包装 11 / 保留 7）方向**整体同意**，「成熟方案优先、仅缺失或语义不符才保留」的基调正确。独立复核意见 4 条：

1. **「保留」7 项理由经官方文档复核后更充分**：permission-system 原文（精读）确无「审批回执/一次性 claim/效果账本」概念，HITL 只到「暂停等待批准→恢复」；Plan Mode 无版本号/乐观锁；going-to-production 无副作用幂等对应物。**保留 7 项（副作用账本、多维预算、HITL 回执、KnowledgeAccessGate、脱敏、Plan 版本、run-state 命名空间）判定维持**，且这些恰是本项目相对官方的差异化资产。
2. **矩阵 #2（同键串行「替换」）降级为「包装（过渡）」更稳**：跨副本锁与整轮互斥均未验（#8），且 ADR §8 已自我修正「删除旧 SessionGate 暂缓」——矩阵正文应同步降级，避免读者只看矩阵误判。
3. **矩阵 #23（Skill「包装」）验收前置缺失**：内核当前 `.disableDynamicSkills().disableDefaultWorkspaceSkills()` 全关，HarnessSkillCatalog 无测试护航——W7 收编前必须先补 skill 同名优先级四层正反例，否则「包装」只是纸面。
4. **矩阵状态标注建议**：24 项目前全部是「拟议目标」，建议每项加一列「当前波次状态」（未启/挂账/局部绿/已替换）并入 §10.4 对账门——本次审计发现的全部出入（#5/#6）都源于"矩阵/ADR 静态文本"与"演进中的代码"无对账。

---

## 7. 处方（按优先级，全部指向会红的门禁而非文档补丁）

| 优先级 | 动作 | 归属 | 验收判据 |
|---|---|---|---|
| P0 | PoC 面清场：`org/ruoyi/chat/poc/**` 移出 src-main 或加 @Profile("poc") 隔离 + pre-commit 哥卫（main 源集检出 poc 包即红） | C 路写面 | grep src/main 无 poc 包；登录态无法自报 userId 驱动带默认工具的 Agent |
| P1 | SSE 契约双向棘轮：done 帧字段集纳入基线（后端生产键 ↔ 前端消费键自动对账），通道二选一（后端补发 card 或前端删通道） | A+C | 契约测试双向断言全绿，死通道=0 |
| P1 | harness 测试面下限哨兵 + ADR 证据入库纪律（/tmp 数字不得引用） | C 路 | main:test 阈值门禁会红；ADR 引证均可 `mvn -pl` 复跑 |
| P1 | 多副本会话锁 + 效果账本持久化（C 路 P0 ②③） | C 路/B4 | 双副本同键负例 + 崩溃恢复对账负例 |
| P2 | 注释/ADR 对账：Bean 缺席语义注释改 fail-closed、基线 111→113 订正、FE 悬浮钮注释统一 | 各写面 | 「声称↔代码」对账脚本绿 |
| P2 | 棘轮基线变更治理：`--update-baseline` 强制 reason + 人审登记 | 工具维护 | growth_log 每条含审批痕迹 |
| P2 | verify.sh 双树假红修复（harness-contract-check 排除 .worktrees/） | 工具维护 | 默认扫描 EXIT=0 且 --self-red=0 |

---

## 8. 无法验证项（诚实边界）

1. **运行态证据为零**：本轮全程静态审计，未启动应用/未连库/未跑浏览器——#1 的运行时可达性为静态判定（组件扫描 + 拦截器路径），动态未证。
2. **派单席位结论抽查覆盖**：#3②③④、#9、#10 及 FE 能力面清单为【派单】+ 本席位抽查（done 帧、history 注释、D2a 三处存留已一手复核），其余 FE 细节（48 页表单形态、动态菜单路由下发、生产 Nginx 分流）未一手复核。
3. **ADR 引述的 /tmp 测试数字真伪未证**（文件可能已清理），只能判「不可复跑」，不判「造假」。
4. **AgentEvent 是否携带 usage（U13）、2.0.3 JAR 的 conflictPolicy 可配性**：未查证 JAR 行为。
5. **库内状态**：`agentscope_sessions` 表 DDL 是否 apply、`ai_model_configs` 实际数据行未查（沿用 ADR §9「本地未见此表」的旧结论）。

---

## 9. 边界与交接

- 本轮为只读审计：**未修改任何被测源码**，未提交/推送。唯一写入 = 本文 + log.md 登记。
- 看板：本任务为用户直令发起、无对应卡，按「不另建台账」纪律未新建卡；§7 处方若需挂卡跟踪，请指定卡号（P0 清场建议挂总控卡 61217664 的 W1 出条件）。
- 与 D2a 关系：D2a 管前端代码质量（1 P1 + 6 P2），本文管 AI 能力完整性 + 全局一致性 + AgentScope 契约对照，D2a 的 P1 与两条 P2 在本文 #9 续账。
- 对 C 路交接：§6 复核意见 4 条 + §7 P0/P1 三项属 C 写面（ruoyi-chat/内核/门禁），请回原卡排期；对 A 路交接：#3②③ FE 侧二选一决策与 D2a 三条守卫同批落地更经济。
