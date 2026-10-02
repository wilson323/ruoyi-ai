# Wiki Log


## 2026-09-04

### batch-1: project-skeleton
- **ingest**: pom.xml, application*.yml (3), CLAUDE.md, README.md, README_ZH.md
- **compile**: wiki/modules/admin.md（基于 7 个 raw 文件）
- **raw files**: raw/project-skeleton/ (7 files, ~95 KB)

### batch-2: admin-source
- **ingest**: ruoyi-admin 全部 6 个 Java 文件 + logback-plus.xml
- **compile**: wiki/modules/admin-source.md（启动 / Controller / Config 详解）
- **raw files**: raw/admin-source/ (7 files, ~31 KB)

### batch-3: chat-source
- **ingest**: ruoyi-chat 8 个核心（ChitChatAgent / ChatController / MCP tool provider / ChatServiceFactory / RAG trace / VectorStoreProperties / McpSseConfig）
- **compile**: wiki/modules/chat.md（覆盖 21 agent、6 内置 MCP 工具、5 factory、Supervisor 模式、RAG 追踪）
- **raw files**: raw/chat-source/ (8 files, ~34 KB)

### batch-4: aiflow-source
- **ingest**: ruoyi-aiflow 8 个核心（WorkflowController / RuntimeController / 3 entity / WorkflowEngine / WfNodeFactory / GraphBuilder）
- **compile**: wiki/modules/aiflow.md（图驱动引擎、节点工厂、状态机）
- **raw files**: raw/aiflow-source/ (8 files, ~42 KB)

### batch-5: system-source
- **ingest**: system 8 文件（3 controller + listener + runner + entity）
- **compile**: wiki/modules/system.md（RBAC、23 controller、监听器、启动任务、CMS）

### batch-6: workflow + generator
- **ingest**: workflow 2 + generator 2 文件
- **compile**: wiki/modules/workflow.md + wiki/modules/generator.md

### batch-7: common 关键库
- **ingest**: common 6 文件（security / mybatis / web / satoken / chat）
- **compile**: wiki/modules/common.md（27 子模块清单）

### batch-8: extend + docker
- **ingest**: extend 2 + docker 3 文件
- **状态**: raw 已就位，wiki 待写（合并到 cross-cutting/deployment-guide.md）

### batch-9: cross-cutting + automation
- **compile**: 
  - wiki/cross-cutting/architecture-overview.md（系统架构总览 + 模块拓扑）
  - wiki/cross-cutting/multi-tenant-design.md（多租户隔离机制 + 边界）
  - wiki/cross-cutting/deployment-guide.md（3 种部署方式 + 配套服务）
  - wiki/automation/claude-code-setup.md（Claude Code 自动化栈使用手册）

### lint 验证
- **脚本**: wiki-lint.cjs（自写 78 行，简化版 check_evidence.py）
- **结果**: 79 通过 / 0 失败 / 0 孤立 raw
- **修复**: 5 篇 wiki 文章的 raw 引用缺 .md 后缀 + lint 脚本 listRaw bug

### batch-10: wiki 扩展
- **ingest**: 3 个聚合 raw（agents-catalog / mcp-tools-catalog / rbac-entities）
- **compile**: 4 篇扩展 wiki
  - wiki/modules/system-rbac-deep-dive.md（26 实体 / 4 权限注解 / 5 级数据权限）
  - wiki/modules/system-listener-runner.md（ApplicationRunner / 2 listener / 启动期异常处理）
  - wiki/modules/chat-agents-catalog.md（7 个核心 agent / Supervisor 模式 / 添加流程）
  - wiki/modules/chat-mcp-tools.md（6 个内置工具 / ExecuteCommand 安全风险 / BuiltinToolProvider 模式）
- **lint 结果**: 99 通过 / 0 失败 / 0 孤立 raw
- **git**: 77 文件暂存（未自动 commit）

### batch-11: 多模态 + common 详解 + CI
- **ingest**: 4 个多模态 abstract（视频/音频/图像/embedding）+ 3 个 common 关键（ExcelDictFormat/TenantHelper/SensitiveService）
- **compile**: 6 篇扩展 wiki
  - wiki/modules/chat-multimodal.md（18 个多模态文件全索引）
  - wiki/modules/common-core-utilities.md（4 模块：core + json + doc + excel）
  - wiki/modules/common-security-auth.md（4 模块：security + satoken + encrypt + sensitive）
  - wiki/modules/common-data.md（4 模块：mybatis + redis + tenant + trace）
  - wiki/modules/common-communication.md（7 模块：web + sse + websocket + oss + mail + sms + social）
  - wiki/modules/common-business.md（5 模块：log + job + ratelimiter + idempotent + translation）
- **CI**: .github/workflows/wiki-lint.yml（GitHub Actions 自动验证）
- **lint 结果**: 121 通过 / 0 失败 / 0 孤立 raw

### batch-12: IPD 业务工作流（2026-09-27）
- **背景**: 工作流系统性梳理治理报告（`docs/ipd-系统说明/工作流系统性梳理-20260927.md`）发现 wiki 完全未覆盖 IPD 业务工作流（探针 D）
- **ingest**: 2 个 ipd raw（gate-review-service.md / stage-action-service.md）
- **compile**: wiki/modules/ipd-workflow.md（三套工作流边界 / 六阶段 69 动作 / 5 Gate 双签 / 状态机守卫）
- **勘误**: index.md 修正统计失真（raw 60→65、wiki 22→23）+ 补登 multimodal-source(4)/chat 8→10/system 8→9/common 6→9 实数
- **lint 结果**: 124 通过 / 0 失败 / 0 孤立 raw（EXIT=0）
- **git**: 未 commit，待 owner 授权

### batch-13: IPD 生命周期节点智能体（2026-09-27）
- **背景**: R236 接线设计契约（`docs/ipd-系统说明/R236-生命周期节点智能体接线设计-20260927.md`）——把 IPD 六阶段 69 个标准动作全部接到嵌入式节点智能体（非请假审批流的数字员工）；本轮新接线 61 码，哨兵棘轮 8→69、豁免清零
- **ingest**: 1 个 ipd raw（ai-execution-engine.md — AiExecutionEngine outbox 抢占-路由-退避链路 / 6 执行器 / AiGenerationService 7 道治理 / NodeAgentResolver）
- **compile**: wiki/modules/ipd-node-agents.md（69 码执行栈 / 6 执行器分档表 / 信任边界 6 红线 / 人审点清单 / 哨兵 10 断言 / 5 条已知限制）
- **勘误（本批自查）**: 首版文章写于 Java 落地前，含 4 处失效陈述（AgentEvidenceExecutor「尚未落地」与 17 码、「8 码已接线 / 61 码待分批扩展」、LightDirect 15 码），已按磁盘真值校正为 18 码 / 69 码全接线 / 14 码，并补 §4.7 NodeAgentResolver 与 V11 动态深度归属说明（契约 §7 B3）
- **index.md**: 统计 raw 65→66、wiki 23→24、raw ~450→~496 KB、wiki ~210→~224 KB；`raw/ipd-source/` 2→3；modules 表补 ipd-node-agents.md 行
- **跨仓对账补强（本批二次自查）**: 前端 `ipd-enums.ts` 的 `ACTION_EXEC_MODE`（69 行静态映射）原注释放称「哨兵测试保护」，但全仓 grep 实测**无任何测试引用该常量** = 失真陈述 + 静默漂移隐患（后端改档位、前端徽标显示错档且不报错）。处置：后端哨兵新增第 10 条断言 `frontEndExecModeMapMatchesActionCatalog` 做跨仓逐码对账，**复用** `StateMachineGuardRulesExportTest` 已有的 `IPD_FE_SHARED_DIR` 定位约定（不另造第二套探测逻辑）；三态自证：篡改副本→红并点名「C01 后端=AI_GENERATE 前端=AI_DIRECT」、真值→10 绿、前端仓缺失→Skipped 而非假绿（全程零触碰真实前端文件）。前端注释同步改为指名该断言。机械校验结果：后端 69 码 ↔ 前端 69 码逐行零差异（AI_DIRECT 40 / AI_GENERATE 24 / HUMAN_GATE 5）
- **lint 结果**: 126 通过 / 0 失败 / 0 孤立 raw（EXIT=0）
- **git**: 未 commit，待 owner 授权

### batch-14: 两模块下线 + LangChain4j→AgentScope 全量替换的 wiki 同步（2026-10-02）
- **背景**: ruoyi-aiflow / ruoyi-workflow 整模块下线（三重证据零消费查证后 owner 拍板）+ LangChain4j/Langgraph4j 全量替换为 AgentScope（ADR-0077），wiki 活文档同步清零
- **删除 4 篇 wiki**（描述对象已物理删除）: wiki/modules/aiflow.md、workflow.md（两模块下线）；chat-agents-catalog.md、chat-mcp-tools.md（21 个 @Agent 接口、LangChain4jMcpToolProviderService、内置 MCP 工具 6 件套已删）
- **删除 15 个 raw**: aiflow-source/（8）、workflow-source/（2）、project-skeleton 5 个漂移骨架快照（pom-xml / application-yml / claude-md / readme-md / readme-zh-md，与现盘不符）、chat-source 3 个独占 raw（chit-chat-agent / mcp-tools-catalog / mcp-tool-provider-service）
- **死链清理**: 18 页 56 处指向已删骨架快照的正文死链 + 第二轮 11 文件边角修复（“参见：。”等）
- **改写 12 页**: index（统计 raw 66→49、文章 24→20）、chat（-98 行失效段落 + AgentScope 声明）、architecture-overview（双 ASCII 图重排 + AI 编排节改写）、multi-tenant-design（@MemoryId 段改写为服务端可信注入：ProjectAgentRunSpec/ProjectKnowledgeSearchTool）、admin（模块聚合 5→3）、common、claude-code-setup、common-security-auth、common-communication、system-listener-runner（删 refresh/warmup 两行）、ipd-node-agents（证据表 AiGateway langchain4j→io.agentscope.core）、ipd-workflow（对比表加下线历史标注）
- **仓级同步**: CLAUDE.md / README-IPD-OVERRIDE.md 篇数统计（21→20 篇 / 60→49 raw）
- **lint 结果**: 91 通过 / 0 失败 / 0 孤立 raw（EXIT=0）
- **git**: 未 commit（并入本轮下线任务收口统一 stage）
