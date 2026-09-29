# AI 智能体能力完整性与全局一致性深度反思（对照 AgentScope Java v2）

> 回答两个问题：**①本项目 AI 智能体能力是否完整实现？②是否全局一致性？**
> 对照源：AgentScope Java v2 官方文档全集（`https://java.agentscope.io/v2/zh/docs`，索引 208 页，本轮实读 Harness 架构/Memory/Skill/Plan Mode/Going to Production 五篇原文 + 全索引能力清单）。
> 盘点方式：并行双路只读盘点（后端 `ruoyi-ai` 10 能力域 / 前端 `ruoyi-ipd-web` 8 能力域）+ 人工复核关键行 + 本地实证对照表（`.claude/skills/agentscope-harness/references/agentscope-java.md`、ADR-0075）。
> 证据口径：file:line 为盘点实读证据；成熟度五态（未实现/源码候选/测试通过/真链通过/生产可用），**本轮最高证据只到「测试通过（单模块）」，无一项达「真链通过」**。本文不回写看板状态（D 独占）。

## 0. 结论速览

**① 能力是否完整实现——否，完成度呈「管理面近满、执行面半截、智能体『智能』部分大面积未接」的断层。**
管理面（Agent CRUD / 知识库 / MCP 市场 / 双轨模型配置 / AI 文档助手）≈95% 且前后端契约逐端点对齐；智能体执行面 ≈40%：内核 `chat.kernel.agentscope.enabled` 默认关、W1 强制空工具，**即使打开开关，智能体也只能纯对话**——长期记忆、子 agent、技能、计划模式、压缩、沙箱、文件/Shell 工具被显式 disable 或未接（`AgentScopeChatKernel.java:265-284`）。对照 AgentScope v2 Harness 十二项核心能力，**接近完整的仅 2 项**（状态持久化、工作区分桶），其余 10 项为「自研面有但主链不用」或「未实现」。

**② 是否全局一致性——否，存在五处系统性错位。**
四套 SSE/WS 帧词汇并存、模型装配三轨并行（`ModelRegistry` / `ChatServiceFactory` 13 家自装配 / `AiGateway` ai_model_configs）、双 harness 并存且互不相通（AgentScope 内核被剥成裸对话 vs 自研 17 包能力齐全但游离主链）、错误语义三通道、**前端无任何智能体对话 UI（配置了 Agent 却无处对话，最后一公里断裂）**。

**一句话**：本项目拥有「企业级智能体平台的完整零件库」，但**尚未组装成一台能自主完成任务的智能体**——现状本质是「带管理后台的增强型聊天」，距 AgentScope v2 定义的 HarnessAgent（长程、可恢复、受控执行）还差整条执行链路的接通与统一。

## 1. 对照源与盘点方法

| 源 | 内容 | 用法 |
| --- | --- | --- |
| AgentScope v2 文档全集 | SDK Building Blocks 7 篇（Agent/Message&Event/Middleware/Model/Permission/Tool/Context&AgentState）+ Harness 10 篇（Architecture/Compaction/Workspace/Memory/Filesystem/Sandbox/Subagent/Skill/Plan Mode/Channel）+ Integration 全套（模型 11 家/Memory 3/State Store 3/RAG 5/Skill Repo 3/协议 3/基建/生态/Channel 6/分布式存储 4）+ Going to Production | 能力完整性判据 |
| 后端盘点（只读） | `ruoyi-ai` 10 能力域逐项实读 | 实现入口 file:line |
| 前端盘点（只读） | `ruoyi-ipd-web` 8 能力域 + 契约比对 | 消费端完整性 |
| 既有成果复用 | C 路交付包（C1–C5 矩阵）、ADR-0075、agentscope-harness 实证对照表 | 避免重复盘点，交叉验证 |

## 2. 能力完整性矩阵（AgentScope v2 × 本项目 × 前端消费）

成熟度按最高证据计；「前端消费」指该能力有无用户可见入口：

| # | AgentScope v2 能力 | 本项目实现（后端） | 前端消费 | 成熟度 | 判定 |
| --- | --- | --- | --- | --- | --- |
| 1 | Agent 定义与装配（HarnessAgent.builder） | `AgentScopeChatKernel.java:257-279` 装配；四维键 `KernelScopeKey.java:53-64`；Agent CRUD `AgentController` | Agent 管理 CRUD 完整（字段与 `AgentVo` 逐字段一致） | 测试通过 | **部分**：装配通、开关默认关、W1 空工具 |
| 2 | Model（provider SPI/多厂商） | `KernelModelSelector.java:71`（`ModelRegistry.resolve`）+ 13 家 langchain4j 自装配 + `AiGateway.java:75-80` 自建 OpenAiChatModel | 平台模型 + IPD ai_model_configs 双管理页均完整 | 测试通过（局部；Minimax 真链 24 errors 负证据） | **三轨并行未统一** |
| 3 | Tool（注册/白名单/MCP） | `LangChain4jMcpToolProviderService.java:152`（stdio/HTTP 双传输）、MCP 市场 CRUD、`KernelToolCallGovernance` 三件套 | MCP 工具/市场 UI 完整（write-only 凭据红线落实） | 测试通过（单测） | **未接执行面**：内核强制空工具（`:281-284`） |
| 4 | Permission System（ALLOW/ASK/DENY） | `KernelToolCallGovernance.java:16-29` 映射 + 自研 `ToolPolicyEngine`（fail-closed） | 无 | 源码候选 | **映射≠拦截**（`KernelEventFrames.java:20-22` 自述） |
| 5 | Context & AgentState（状态持久化/多用户隔离） | `MysqlAgentStateStore`（四维键收口）+ `LocalSessionTurnGate` 同键串行 + `ChatSessionOwnershipGuard` | 无（无需 UI） | 测试通过 | **接近完整**（多副本串行未验收） |
| 6 | Memory（双层长期记忆） | **无**。内核显式 `disableMemoryTools().disableMemoryHooks()`（`:267-268`）；`PersistentChatMemoryStore` 仅旧链会话历史 | 无 | 未实现 | **缺**（对照官方：`memory/*.md`→`MEMORY.md`→注入 prompt 三段管线全部无） |
| 7 | Compaction / 大结果卸载 | 自研 `ContextEngine`/`CompactionControl` 仅 `/coding/harness` 消费；内核对话路径无 | 无 | 源码候选 | **主链缺** |
| 8 | Subagent（委派/后台/反推） | 旧链 `SupervisorAgent` + 6 子 Agent（`ChatServiceFacade.java:355-361`）；内核 `disableSubagents`（`:272-273`） | 无 | 源码候选 | **未实现**（无 Team/Handoff 语义） |
| 9 | Skill（四层合成/市场/自学习） | 无 skill repository 接线；skill 仅存在于开发工具层（.claude skills） | 无 | 未实现 | **缺**（对照官方 Git/Nacos/MySQL/Classpath 四源全无） |
| 10 | Plan Mode（只读计划+HITL） | 自研 `CanonicalPlanHasher`/`AcceptanceCriterion` 仅 `/coding/harness`；内核无 Plan Mode | 无 | 源码候选 | **主链缺**；ASK 审批未接对话路径 |
| 11 | Workspace / Filesystem / Sandbox | workspace 三级分桶（`workspaceFor`:299-313，路径穿越 fail-closed，4 用例过）；自研 Docker 命令沙箱 | 无 | 测试通过（分桶） | **部分**：文件/Shell 工具全关；IsolationScope 未落地 |
| 12 | Channel（多 agent 路由/会话/流式） | SSE（`SseEmitterManager`/`SseErrorEmitter`）+ WS（`MpChatWebSocketHandler`）+ AG-UI 单轨桥（`AgUiCopilotRun`） | 副驾 SSE 四帧处理完整；**WS 聊天零前端消费者** | 测试通过 | **部分**：帧词汇四套并存（见 §3.1） |

**Integration 生态对照**（摘要）：模型 11 家——本项目 langchain4j 13 家自装配「覆盖但未统一」；Memory（Mem0/Bailian/ReMe）——无；State Store（MySQL/Redis/OSS）——用 MySQL ✅；RAG（Simple/Bailian/Dify/HayStack/RAGFlow）——自研 Weaviate/Milvus/Qdrant 三策略「另一套等价物」；Skill Repo（Git/MySQL/PG）——无；协议（A2A/AG-UI/Agent Protocol）——AG-UI ✅（单轨翻译桥），**A2A 无**；Channel 适配器（钉钉/飞书/GitHub/GitLab/企微）——无（IPD 场景不必须，属产品裁决）。

## 3. 全局一致性发现

### 3.1 四套帧词汇并存（最大契约债）

| 通道 | 帧词汇 | 消费方 |
| --- | --- | --- |
| 副驾 SSE | `meta/delta/done/error`（`ai-copilot.ts:282-290`） | AI 副驾 ✅ |
| aiflow SSE | `[START]/[THINKING]/[DONE]/[ERROR]/[STATE_CHANGED]`（`runtime.ts:119-125`） | 工作流运行 ✅ |
| 内核/通知 SSE | `content/reasoning/done/error/mcp_tool`（`KernelEventFrames.java:49-58`、`SseEventDto`） | **推理帧/mcp_tool 帧前端零消费者** |
| MpChat WS | `{content}` / `[DONE]` / `{data:"错误:xxx"}`（`MpChatWebSocketHandler.java:55-65`） | **前端无此通道**（WS 层还主动丢弃 reasoning/mcp_tool，`:323,328`） |

后果：深度思考与工具调用过程对用户完全不可见；后端补发也没有 UI 接。且 `SseMessageUtils.sendError` 恒发脱敏通用文案（`SseErrorEmitter` 契约），前端无法按 code 分支——错误语义三通道（SSE {code,message} / [ERROR] 字符串 / WS {data}）不统一。

### 3.2 模型装配三轨

`KernelModelSelector`→`ModelRegistry`（provider SPI）/ `ChatServiceFactory`→13 家自装配 / `AiGateway`→ai_model_configs 自建 `OpenAiChatModel`。`KernelModelRequest.java:13-14` 明文「ai_model_configs 尚未接入」。凭据、用量、预算口径三套不一（`HarnessBudget` 仅自研面消费，对话主链无 usage 落库）。

### 3.3 双 harness 并存（与 agentscope-harness skill 铁律直接冲突）

AgentScope 内核承载真实聊天路径但被剥成裸对话；自研 `org.ruoyi.service.coding.harness`（17 子包：计划哈希/审批聚合/效果账本/压缩/恢复）能力齐全但游离主链（独立 `/coding/harness` REST 面，核心 run loop 零测试执行证据）。唯一交点是内核借用自研 `ToolPolicyEngine` 做事件帧裁决——**裁决源在自研、执行拦截在外核，语义漂移无对账**。这正是 ADR-0075「替换/包装/保留三选一」尚未落地执行的直接后果。

### 3.4 前后端「最后一公里」断裂

Agent 配置面（modelId/systemPrompt/skills/MCP/知识库）已完整落库，但**不存在任何智能体对话 UI**（`agentEnabledOptions` 死代码、agentId 不透传、模型选择无对话透传）；`chat.kernel.agentscope.enabled` 开关无管理 UI；多轮 history 前端禁用待后端、`done.card` 前端先行后端缺位——两处「前端等后端/后端等前端」悬置。

### 3.5 结构性小错位（治理中）

`agentModelOptions` TS 签名与真实 VO 错位（运行期靠 labelField 吸收）；ID number/string 双态未全局收口；更新动词 PUT root vs `POST /{id}/update` 双轨；product-group 兼容 shim 已收敛（drift-guard 守护）✅。

## 4. 深度反思

**为什么零件齐全却没组装成智能体？三个结构性原因：**

1. **安全优先的保守裁决累积成「功能冻结」**。W1 空工具、开关默认关、文件/Shell/记忆/子 agent 全 disable——每一项都是合理的单点安全决策，但叠加后使 HarnessAgent 失去「Harness」的全部意义。对照官方架构：Harness 的价值恰在工具/记忆/计划/子 agent 的受控接通；只留状态持久化等于买了发动机只装了轮子。**缺的不是能力，是 W2/W3 的开放序列与验收**。
2. **双轨并行无收敛日程**。自研 harness 与 AgentScope 内核、三套模型装配、四套帧词汇，每一处双轨都因「旧链有消费者不能动」而保留——但没有消费者迁移动作在推进（C5 矩阵：**零入口已迁**）。对照 Going to Production 的多副本 checklist：`IsolationScope` 上线前定死、状态存储分布式化、Skill 治理集中——这些都要求先结束双轨，双轨期越长迁移成本越高。
3. **测试投入与风险面倒挂**。内核桥 10 套件全过（安全边界测试质量高），但承载计划/审批/恢复/压缩的自研 run loop（3000+ 行）近乎零测试执行证据；前端契约测试覆盖管理面，对话面无 UI 也无测。**「测了的没接线，接线的没测」**。

**映射 R25「五类病根」**：①改主代码测试没跟上→自研 harness 测试债；②提交不完整→kernel 曾未提交（已本轮修复）；③规则表与接线点人肉对账→工具治理映射≠拦截；④前后端契约无门禁→四套帧词汇/两处悬置；⑤多事实源无对账→双 harness 语义漂移。

**对照官方「常见坑位」的直接命中**：`IsolationScope` 改了旧数据不迁移（本项目 workspace 分桶刚改布局、W3 前无存量——窗口期正好）；本地 `AgentStateStore` 多副本 build 即抛（本项目用 MysqlAgentStateStore ✅ 选型正确，但 `LocalSessionTurnGate` 进程内锁在多副本下失效——与官方 `SandboxExecutionGuard`/粘性路由的差距）；`tools.json` allow 过滤内置工具（W3 开放时必踩，须保留 `read_file`/`memory_search`/`agent_spawn`）。

## 5. 缺口优先级（供 D/B 裁决，本文不排期）

| 级 | 缺口 | 依据 |
| --- | --- | --- |
| P0 | 智能体对话 UI 打通（消费 Agent 配置；帧词汇统一映射层） | 能力闭环最后一公里；否则管理面全部投入无出口 |
| P0 | 内核 W2/W3 开放序列（工具→审批拦截→账本持久化）+ 预算贯穿 | 「智能体」名实相符的前提 |
| P1 | ai_model_configs 统一接入 `KernelModelSelector`（结束三轨） | KernelModelRequest 已明文待办 |
| P1 | 长期记忆（官方双层 Memory）与 Plan Mode（HITL）接入内核 | AgentScope 核心价值能力 |
| P1 | 自研 harness 测试债清偿或收敛为内核插件（ADR 级裁决） | 双轨铁律 |
| P2 | Subagent/Team、Skill repository、A2A、多副本（分布式锁/粘性路由） | 视 IPD 产品范围裁决（避免过度设计：原型没有的不加） |

## 6. 边界与限制（诚实披露）

1. **无真链证据**：全部结论基于源码 + 单测记录，无 HTTP/DB/真实模型端到端验收；「真链通过/生产可用」栏全部为空。
2. `io.agentscope.*` 为外部依赖（本地无源码），其行为按调用方注释与官方文档转述；`MinimaxServiceImplTest` 16 errors / `MinimaxIntegrationTest` 8 errors 等红例属兄弟在途/真链负证据，未在本轮处置（见 C 路交付包 §1.1 同类裁决）。
3. 盘点为双子智能体实读 + 人工复核关键行（`AgentScopeChatKernel`/`MpChatWebSocketHandler`/`ChatServiceFacade`/`KernelEventFrames` 引用已人工复核），其余 file:line 以盘点报告为准；`api/graph/**` 前端页面的后端 Controller 未检索到（标「未核实/疑似缺」）。
4. 文档对照实读 5 篇原文（Architecture/Memory/Skill/Plan Mode/Going to Production）+ 全索引能力清单；Building Blocks 其余篇目以索引摘要与本地实证对照表覆盖，未逐篇全文精读。
5. 本文为独立反思产物，未回写看板/SSOT（D 独占）；如需收编进 D 轮验收系列由 D 裁决。

---
*产出：全局梳理（并行双路专业智能体盘点 + 人工复核）。结论成熟度=「源码候选/测试通过（单模块）」口径，不代表看板完成态。*
