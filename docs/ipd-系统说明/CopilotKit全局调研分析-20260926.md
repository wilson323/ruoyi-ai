# CopilotKit 全局调研分析（2026-09-26）

> ⚠️ 后续更新（2026-09-27）：同主题终版评估见 `CopilotKit前端融合可行性分析-20260927.md`，其结论（不适合现阶段引入）取代本文「试点 POC」建议——差异在于补充了 ZK-IPD 原型红线 / C08 suggest 红线 / R214 反双轨 / R221 人审闭环四项权重；本文事实层仍有效，引证状态修正见终版 §6。

> 调研性质：只读研究，未改动任何代码/依赖/看板/pom/package.json。
> 调研对象：CopilotKit（docs.copilotkit.ai、github.com/CopilotKit/CopilotKit、npm @copilotkit/*、AG-UI 协议）。
> 本项目：ruoyi-ai（Spring Boot 3.5.8 + Java 17 + Langchain4j 多模块 Maven）+ 正式前端 ruoyi-ipd-web（vue-vben-admin monorepo：Vue 3.5 + Ant Design Vue 4.2 + Vite 7 + pnpm 10.14）。
> 证据纪律：每条事实标来源 URL + 原文引用/章节名；无法一手证实的写「未验证」；第三方来源仅作旁证并标注。

---

## 0. 结论摘要（含维度 5 建议）

**建议：试点 POC（有条件引入）。不建议直接全面引入，也不建议一票否决。**

四条支撑判断：

1. **可行性已证实**：CopilotKit 不是 React-only，官方提供 `@copilotkit/vue`（"Vue 3 components and composables"，前置 Vue 3.3+），官方安装命令直接给 pnpm 路径，与本项目 Vue 3.5 + Vite 7 + pnpm 10.14 技术栈兼容。来源：https://docs.copilotkit.ai/vue
2. **能力是「壳 vs 核」关系**：Chat UI / streaming / Generative UI / HITL 可升级自研前端交互壳；但本项目的意图分类、越权校验（project_members）、审计三件套（BR-AI-04/05）、R221 执行状态机与人审闭环无 CopilotKit 等价物，必须保留。来源：https://github.com/CopilotKit/CopilotKit（能力清单）+ 本仓 AiCopilotService/AiSuggestionService/AiExecutionEngine/AiExecReviewHook
3. **后端必须新增适配层**：Copilot Runtime 是 Node 服务（官方无 Java Runtime）；对接 Spring Boot 的 /api/v1 AI 端点需 Node sidecar（custom factory 转发）或 Java 实现 AG-UI 端点（Java SDK 为 Community 维护，成熟度未验证）。来源：https://docs.copilotkit.ai/quickstart、https://docs.copilotkit.ai/custom-agent、https://github.com/ag-ui-protocol/ag-ui
4. **依赖治理可控但演进极快**：全系 MIT 许可；@copilotkit/vue@1.74.0 解包 ≈4.7MB；但 1.71.0（09-09）→ 1.74.0（09-25）16 天 3 个 minor，且文档处于 v2 重构期（旧 CoAgents 文档页实测 404）。来源：npm registry（npm view）、https://docs.copilotkit.ai/concepts/coagents/quickstart（404 实测）

POC 最小验证路径见 §5.2（做什么 / 验什么 / 退出条件）。

---

## 1. 维度一：技术栈

### 1.1 事实

**F1-1 CopilotKit 前端运行时不是 React-only，官方一等支持 Vue 3。**
- 来源：https://docs.copilotkit.ai/vue（章节 "Vue quickstart" / "Prerequisites"）
- 原文引用："@copilotkit/vue provides Vue 3 components and composables for CopilotKit."；前置条件 "Vue 3.3+"、"Node.js 20+"。
- 安装命令官方直接给 pnpm 路径："pnpm add @copilotkit/vue @copilotkit/runtime"。

**F1-2 Vue 包的组件与 composables 与 React SDK 对齐。**
- 来源：https://docs.copilotkit.ai/vue（章节 "Connect to Copilot Runtime" / "Where to go next"）
- 原文引用："import { CopilotKitProvider, CopilotChat } from '@copilotkit/vue/v2'"；"CopilotSidebar (a collapsible side panel) or CopilotPopup (a floating widget)… They take the same props."；"The composables (useAgent, useFrontendTool, useHumanInTheLoop, and more) mirror the React SDK."

**F1-3 React 是主线，Vue/Angular/React Native 均为官方支持面。**
- 来源：https://github.com/CopilotKit/CopilotKit（README "Works With Your Stack"）
- 原文引用："What started as a React library is now the horizontal layer between your agents and your users"；平台表："⚛️ React / Next.js ✅ GA"、"🅰️ Angular ✅ Supported"、"💚 Vue ✅ Supported"、"📱 React Native ✅ Supported"。
- 注意：README 的 Vue 行写 "Source Code - Quickstart coming soon"，但 docs.copilotkit.ai/vue 已有完整 quickstart —— README 滞后于文档，以文档为准。

**F1-4 核心包与版本现状（npm registry 元数据，2026-09-26 经 npm view 查询；包页 https://www.npmjs.com/package/@copilotkit/vue 等）。**
- @copilotkit/vue：1.74.0，license=MIT，peerDependencies = { vue: '>=3.3.0' }，engines = { node: '>=20' }，unpacked ≈4.7MB。
- @copilotkit/runtime：1.74.0，MIT，unpacked ≈4.7MB。
- @copilotkit/react-core：1.74.0（unpacked ≈7.6MB）；@copilotkit/react-ui：1.74.0（≈1.5MB）—— Vue 路线不需要这两个。
- @copilotkit/vue 直接依赖 12 个：@copilotkit/core、@copilotkit/shared、@copilotkit/web-inspector、@copilotkit/web-components（均 1.74.0）、@ag-ui/core 0.0.59、@ag-ui/client 0.0.59、@a2ui/web_core 0.10.4、zod ^3.25.75、zod-to-json-schema ^3.24.5、katex ^0.16.27、streamdown-vue ^1.0.29、lucide-vue-next ^0.525.0、@jetbrains/websandbox ^1.1.3。

**F1-5 与本项目前端（ruoyi-ipd-web）技术栈的兼容性对照。**
- 本仓事实（本地读取）：vue-vben-admin monorepo（pnpm workspace + turbo + catalog: 机制），catalog 中 vue ^3.5.17、ant-design-vue ^4.2.6、vite ^7.1.2、typescript ^5.8.3、zod ^3.25.67；apps/web-antd 依赖 ant-design-vue、@ant-design/icons-vue 等；本机 pnpm 10.14.0、Node v22.22.3。
- 对照结论：Vue 3.5 ≥ 3.3 ✓；Node 22 ≥ 20 ✓；zod 需求 ^3.25.75 vs catalog ^3.25.67（须确认解析版本）；官方 Vue quickstart 的开发服即 Vite（"Open the dev server URL (Vite prints it, usually http://localhost:5173)"）→ Vite 兼容无官方反证，Vite 7 具体版本未标注（未验证实测）。

### 1.2 对本项目的含义
- Vue 3 + Ant Design Vue 工程可以使用 CopilotKit，无需迁 React；但 CopilotKit 自带独立样式（"@copilotkit/vue/styles.css… It's self-contained"），与 AntDV 主题体系是两套样式，样式隔离/主题统一是 POC 必验项。
- 真实接入面只有两个包：@copilotkit/vue（前端）+ @copilotkit/runtime（Node 服务端）；react 系包不必引入。

---

## 2. 维度二：能力对齐

### 2.1 CopilotKit 能力清单（事实）

**F2-1 官方能力列表。**
- 来源：https://github.com/CopilotKit/CopilotKit（README "What you can build"）
- 原文引用（逐条）："Chat UI – A fully customizable chat interface that supports message streaming, tool calls, and agent responses."；"Backend Tool Rendering – Enables agents to call backend tools that return UI components rendered directly in the client."；"Generative UI – Allows agents to generate and update UI components dynamically at runtime based on user intent and agent state."；"Shared State – A synchronized state layer that both agents and UI components can read from and write to in real time."；"Human-in-the-Loop – Lets agents pause execution to request user input, confirmation, or edits before continuing."；另有 Rich Threads、Automatic Learning。

**F2-2 Generative UI（useComponent，展示型）。**
- 来源：https://docs.copilotkit.ai/generative-ui（章节 "Display-only"）
- 原文引用："useComponent lets you register a React component as a tool your agent can invoke. When the agent calls the tool, CopilotKit renders your component directly in the chat with the tool's arguments as props."
- GitHub README 补充三形态："Static (AG-UI Protocol) / Declarative (A2UI) / Open-Ended (MCP Apps & Open JSON)"。

**F2-3 Frontend Tools（前端动作/前端工具）。**
- 来源：https://docs.copilotkit.ai/frontend-tools（章节 "What is this?"）
- 原文引用："Frontend tools enable you to define client-side functions that your agent can invoke, with execution happening entirely in the user's browser… to let your agent control the UI, for generative UI, or for Human-in-the-loop interactions."（useFrontendTool + zod parameters + handler）

**F2-4 Human-in-the-loop 双模式。**
- 来源：https://docs.copilotkit.ai/human-in-the-loop（章节 "Two patterns for HITL in CopilotKit"）
- 原文引用："useHumanInTheLoop — The LLM, by calling a registered client-side tool… A frontend-only tool description (Zod schema + render)"；"useInterrupt — The graph, by calling interrupt(...) during a node… A server-side interrupt() call in your LangGraph agent"。
- 关键限制原文："Not supported on CopilotKit's Built-in Agent: Human in the Loop: Interrupts."（interrupt 模式需 LangGraph 类图框架后端）

**F2-5 Streaming 为内建默认行为。**
- 来源：https://docs.copilotkit.ai/vue（"you'll see it stream back through Copilot Runtime"）+ https://docs.copilotkit.ai/custom-agent（"CopilotKit handles everything else: RUN_STARTED and RUN_FINISHED lifecycle events, stream-to-AG-UI conversion, error handling, and abort/cancellation."）

### 2.2 与本项目已有能力逐项对照

本仓证据（本地只读）：AiCopilotService.java（CopilotStreamSink 契约 meta/delta/done/error；classifyIntent：TASKS/ADVANCE/FILL_PAGE/CHITCHAT）、AiSuggestionService.java（SCENES 七场景白名单：workbench.next-step、workbench.risk-warning、project.summary.refresh、project.create.suggest、demand.create.from-requirement、gate.precheck-checklist、gate.conclusion-draft）、AiExecutionEngine.java（"填表需用户确认"的人审红线）、aiexec/GenerateExecutor.java（"AI 草稿 → 动作只到 IN_PROGRESS 待人审，绝不代签 DONE"）、AiExecReviewHook.java（"AI 草稿人审通过 → 交付物自动挂载 + 动作 DONE + 后继唤醒"）、StageActionController.java（POST /{id}/ai-execute）。

| 本项目能力 | CopilotKit 对应物 | 结论 |
|---|---|---|
| 副驾问答（POST /api/v1/ai-copilot/chat + GET /chat/stream，meta/delta/done/error 四类 SSE 事件） | Chat UI + streaming + AG-UI 事件流 | **能替代 UI 壳，不能替代业务内核**。聊天渲染/流式体验可交给 CopilotKit；意图分类、越权（project_members）、审计三件套（aiAssisted/aiModel/aiRole）是后端规约，须保留 |
| 场景建议（POST /api/v1/ai/suggest，7 场景白名单 + prompt 注入防护 + 降级应答） | 无直接等价物；可用 Generative UI（useComponent）做呈现 | **能增强（呈现层）**。场景白名单/上下文装配/审计留后端；CopilotKit 只是把 markdown 建议升级为结构化卡片的可选呈现器 |
| AI 执行闭环（POST /api/v1/stage-actions/{id}/ai-execute，AiExecutionEngine 派发执行器族，GenerateExecutor：AI 草稿→IN_PROGRESS 待人审） | HITL（useHumanInTheLoop/useInterrupt）思想同构 | **能增强、不能替代**。HITL 是「会话内暂停-确认-续跑」；本项目是「跨会话持久状态机 + 状态流转 + 审计」。HITL 可作为人审确认的交互入口，状态机必须留后端 |
| 人审链 review hook（AiExecReviewHook：人审通过→OSS 挂交付物→transit DONE→后继唤醒） | 无对应物 | **不适用**。持久化业务闭环（交付物、状态流转、通知），CopilotKit HITL 不涉及任何持久化业务动作 |

### 2.3 对本项目的含义
- CopilotKit 与自研 AI 栈是「壳 vs 核」关系：它擅长聊天壳、组件化呈现、会话内 HITL；本项目核心资产（意图路由、越权、审计三件套、执行状态机、人审闭环）无一被替代。
- 若引入，定位应是「前端 AI 交互层框架」而非「AI 能力层」；后端 AI 服务（AiCopilotService/AiSuggestionService/AiExecutionEngine）继续作为唯一业务真源。

---

## 3. 维度三：后端对接

### 3.1 事实

**F3-1 Copilot Runtime 是 Node 服务端组件（官方无 Java/Spring Runtime）。**
- 来源：https://docs.copilotkit.ai/quickstart（章节 "Setup Copilot Runtime"）与 https://docs.copilotkit.ai/vue（章节 "Create the Copilot Runtime"）
- 原文引用：quickstart 用 Next.js route handler（app/api/copilotkit/[[...slug]]/route.ts，导出 GET/POST/PATCH/DELETE）；Vue 指南用 node:http："createServer(createCopilotNodeListener({ runtime, basePath: '/api/copilotkit', cors: true }))"，并称 "The runtime runs on your server, keeps model credentials out of the browser"。
- 官方文档未见任何 Java/Spring 版 Runtime（检索 docs.copilotkit.ai 未发现，标注：未发现≠不存在，按现有证据视为无官方 Java Runtime）。

**F3-2 Bring-your-own LLM：任何 OpenAI 兼容端点可直连。**
- 来源：https://docs.copilotkit.ai/model-selection（章节 "OpenRouter, proxies, and bring-your-own LLM" / "Custom Models (AI SDK)"）
- 原文引用："Anything that exposes an OpenAI-compatible API, including OpenRouter, a self-hosted gateway, an internal LLM proxy, Ollama, Together, Groq, Novita, or your own fine-tuned endpoint, works through the same createOpenAI({ baseURL }) pattern"；"There is no CopilotKit-specific provider to install; if the AI SDK can talk to it, the Built-in Agent can use it."
- 但注意：这解决的是「LLM 模型层」直连，不是「业务 AI 端点」对接——本项目的 /api/v1/ai-copilot、/api/v1/ai/suggest 是业务 API，不是裸 LLM 端点，仍需 F3-3/F3-4 的适配。

**F3-3 Custom Agent「factory 模式」可调任意 HTTP 后端（关键适配点）。**
- 来源：https://docs.copilotkit.ai/custom-agent（章节 "Quick Start – Custom"，页面标题 "Use any model router"）
- 原文引用："BuiltInAgent's factory mode gives you full control over the LLM call. You provide a factory function that talks to any backend, and CopilotKit handles converting the stream to AG-UI events, managing lifecycle, and wiring it into the runtime."；Custom 示例即 "factory: async function* ({ input, abortSignal }) { const response = await fetch('https://your-llm-api.com/chat', …)" 后逐块 yield AG-UI 事件（TEXT_MESSAGE_CHUNK / TOOL_CALL_START / TOOL_CALL_ARGS / TOOL_CALL_RESULT 等）。
- 同页安全告诫原文："Use forwardedProps only for non-secret, browser-controlled preferences… Never use these properties for credentials, tenant identity, authorization, or unrestricted model and provider selection."（→ 本项目 sa-token 身份绝不能走 forwardedProps）

**F3-4 远程 agent over AG-UI（LangGraph 为官方样板）。**
- 来源：https://docs.copilotkit.ai/langgraph（章节 "Connect your agent"）
- 原文引用："Your graph stays where it runs today: LangGraph Platform, LangSmith, or your own FastAPI service. CopilotKit reaches it over AG-UI, so nothing inside the graph changes."（示例：new LangGraphAgent({ deploymentUrl, graphId, langsmithApiKey })）
- 含义：「agent 逻辑留在原后端（可为非 Node 服务）、CopilotKit 经 AG-UI 协议远接」是官方支持架构。

**F3-5 AG-UI 协议与 Java SDK。**
- 来源：https://github.com/ag-ui-protocol/ag-ui（README "What is AG-UI?" / "SDKs"）
- 原文引用："AG-UI is an open, lightweight, event-based protocol… agent backends emit events compatible with one of AG-UI's ~16 standard event types"；"Works with any event transport (SSE, WebSockets, webhooks, etc.)"；SDKs 表："Java | ✅ Supported | Getting Started | Community"（社区维护；另列 Kotlin/Go/Dart/Rust/Ruby/.NET 等）。
- 协议许可原文："AG-UI is open source software licensed as MIT."
- 客户端侧：https://www.npmjs.com/package/@ag-ui/client —— "HttpAgent for direct server connections with SSE/protobuf support"（npm 包页）。

**F3-6 AG-UI 协议线上形态（旁证，第三方）。**
- 来源（旁证）：https://docs.cloudbase.net/ai/agent-development/protocol —— 展示 "POST /agent + Accept: text/event-stream"、请求体含 threadId/runId/messages/tools/context/forwardedProps，响应为 "data: {\"type\":\"TEXT_MESSAGE_START\",\"messageId\":…}" 形态的 SSE 事件帧。
- 用途：说明 AG-UI 与本项目现有 SseEmitter（event=meta/delta/done/error）是不同事件模型，需要逐事件映射（映射表未验证，见 §6）。

### 3.2 适配层结论：能否对接 Spring Boot + Langchain4j 的 /api/v1 AI 端点？

**结论：能对接，但官方没有现成「Spring Boot 直连」方案，必须新增适配层。两条可行路径：**

**路径 A（POC 推荐）：Node sidecar + custom factory 转发**
- 形态：独立 Node 小服务（@copilotkit/runtime 1.74.x），BuiltInAgent type:"custom" 的 factory 内 fetch 本项目 `/api/v1/ai-copilot/chat`（或 /chat/stream、/api/v1/ai/suggest、/api/v1/stage-actions/{id}/ai-execute），把返回翻译为 AG-UI 事件（TEXT_MESSAGE_CHUNK / TOOL_CALL_* / STATE_*）。
- 需新增：① Node sidecar 工程与部署单元；② 身份透传链路（Vue 前端 → sidecar → Spring Boot，走 header/网关承载 sa-token，禁走 forwardedProps，依 F3-3）；③ 自研 SSE 契约（meta/delta/done/error）→ AG-UI 事件映射；④ CORS/网关路由。
- Spring Boot 侧：可零改动（sidecar 适配），符合「最小变更」取向。

**路径 B（中期可选）：Spring Boot 直接实现 AG-UI 端点**
- 形态：用 AG-UI Java SDK（Community 维护，成熟度未验证）或手写 SSE 事件序列，在 Spring Boot 暴露 AG-UI 兼容端点（POST + text/event-stream），前端经 @ag-ui/client HttpAgent 或经 runtime 的自定义 agent 转接。
- 优点：消灭 Node sidecar、鉴权审计天然留在 sa-token 体系内；缺点：~16 种事件的生命周期管理、断线/abort 语义要自研，Java SDK 成熟度未验证。

**鉴权结论（两路径通用）**：CopilotKit 不提供 sa-token/内部角色等价物；OPERATION_AI_COPILOT 权限注解、project_members 越权校验、审计三件套（BR-AI-04/05）必须继续在 Spring Boot 侧执行，CopilotKit 只承担传输与 UI。

### 3.3 对本项目的含义
- 「非 Node 后端」不是障碍（AG-UI 就是为跨语言设计，Java 有社区 SDK），但「零适配层直连」不存在；评估引入成本时必须把 sidecar/适配层的开发与运维计入。
- Langchain4j 与 CopilotKit 无直接集成（integrations 列表无 Langchain4j：CopilotKit/DeepAgents/LangChain/Google ADK/AWS Strands/Microsoft Agent Framework/Mastra/Claude Agent SDK/PydanticAI/…，来源 https://docs.copilotkit.ai/integrations），因此本项目只能走「custom factory / AG-UI 协议」的通用路径，而非框架级一键集成。

---

## 4. 维度四：依赖治理

### 4.1 事实

**F4-1 npm 包清单与体量（npm registry 元数据，2026-09-26 经 npm view 查询；包页 https://www.npmjs.com/package/@copilotkit/vue、/@copilotkit/runtime、/@copilotkit/react-core、/@copilotkit/react-ui、/@copilotkit/shared）。**
- Vue 路线引入面：@copilotkit/vue@1.74.0（unpacked ≈4.7MB，进前端）+ @copilotkit/runtime@1.74.0（≈4.7MB，Node 服务端，不进前端 bundle）。
- @copilotkit/vue 直接依赖 12 个（明细见 F1-4）：UI 类（katex、streamdown-vue、lucide-vue-next、@jetbrains/websandbox、@copilotkit/web-components、@copilotkit/web-inspector）、协议类（@ag-ui/core、@ag-ui/client 0.0.59、@a2ui/web_core 0.10.4）、schema 类（zod ^3.25.75、zod-to-json-schema）。
- @copilotkit/runtime 依赖较重（npm view dependencies）：ai ^6.0.104、openai、@ai-sdk/openai|anthropic|google|mcp、hono、graphql + graphql-yoga + type-graphql 2.0.0-rc.1、ws、pino、rxjs 7.8.1、phoenix、@ag-ui/langgraph、class-validator 等——全部隔离在 sidecar 服务端，不影响前端包体。
- 注意：type-graphql 为 2.0.0-rc.1（RC 版），属 runtime 传递风险点（仅影响 sidecar，不影响前端）。

**F4-2 许可证：MIT（全链路）。**
- 来源：https://github.com/CopilotKit/CopilotKit（README "License"）原文："This repository's source code is available under the MIT License."；npm 各包 license=MIT（F1-4）；AG-UI 协议亦 MIT（https://github.com/ag-ui-protocol/ag-ui："AG-UI is open source software licensed as MIT."）。

**F4-3 维护活跃度：极高（双刃剑）。**
- 来源：npm registry 发布时间戳（npm view @copilotkit/vue time，2026-09-26 查询）
- 数据：1.71.0 → 2026-09-09；1.71.1 → 09-11；1.71.2 → 09-14；1.72.0 → 09-15；1.73.0 → 09-19；1.73.1/1.73.2/1.73.3 → 09-22；1.74.0 → 2026-09-25（另有多枚 canary）。即 **16 天内 4 个 minor + 4 个 patch**，npm time.modified = 2026-09-25（调研当日仍在发版）。
- 文档重构信号（实测）：https://docs.copilotkit.ai/concepts/coagents/quickstart 返回 404——用户调研问题中的「CoAgents」文档路径已失效，概念并入 Integrations/agents 体系（https://docs.copilotkit.ai/integrations 的 "Your agent backend" 矩阵）；quickstart 代码使用 v2 子路径导出（@copilotkit/react-core/v2、@copilotkit/runtime/v2），且提示 "every released <CopilotKit> still pins it to true internally"（transport 兼容性坑，见 custom-agent 页 "About the explicit useSingleEndpoint"）。
- GitHub 活跃度旁证：README 展示 Intelligence 平台、Channels SDK（Slack/Teams）、AG-UI 生态合作（"adopted by Google, LangChain, AWS, Microsoft, Mastra, PydanticAI"）。

**F4-4 与 pnpm 10.14 / Vite 7 的兼容与改动面（本仓实况对照）。**
- 官方 Vue quickstart 直接给 pnpm 命令（F1-1）；dev server 即 Vite（F1-5）→ 无官方兼容性反证；Vite 7 具体版本兼容未标注（未验证实测）。
- 对现有 package.json 的改动面（若 POC）：
  1. apps/web-antd/package.json：+1 条依赖 "@copilotkit/vue"（pin 1.74.x）；
  2. pnpm-workspace.yaml catalog：+1 行（vben 走 catalog: 统一版本）；zod 下限需 ≥3.25.75（现 catalog zod ^3.25.67，@copilotkit/vue 要求 ^3.25.75，pnpm catalog 固定语义下需上调或实测解析）；
  3. 入口 main.ts：+1 行 import "@copilotkit/vue/styles.css"；
  4. 业务组件/路由若干（演示页）。
  5. @copilotkit/runtime 不进 vben workspace，放独立 Node 部署单元（避免重依赖污染 monorepo 门禁）。
- vben 工程门禁风险：monorepo 带 check:dep / check:circular / cspell / eslint / typecheck 门禁（package.json scripts 实读），新包名（copilotkit、ag-ui、a2ui、katex 等）可能触发 cspell/dep 检查告警，POC 须跑全套 check 验证。

### 4.2 对本项目的含义
- 无许可证合规问题（MIT）、无前端包体硬伤（核心包 ≈4.7MB 解包，且样式自包含）；真正的治理风险是「16 天 3+ minor 的演进速度 + v2 API/文档重构期」——**pin 死 1.74.x + 一个迭代周期内不追新版**是必要纪律，升级当作独立变更评审。
- 依赖隔离原则：前端只拿 @copilotkit/vue；Node 重依赖（graphql/type-graphql RC/ws/pino 等）全部锁在 sidecar；zod 与 catalog 的版本对齐是唯一的已知直接冲突面。

---

## 5. 维度五：结论路径

### 5.1 建议：试点 POC（有条件引入）

**为什么不「直接引入」：**
- 后端对接必须新增适配层（Node sidecar 或 Java AG-UI 端点），官方无 Spring Boot 现成方案（F3-1/F3-3/F3-4）；
- CopilotKit 不覆盖本项目 AI 核心规约：意图分类、越权、审计三件套、R221 执行状态机、人审闭环均无等价物（§2.2）；
- 文档/API 处于 v2 重构期（CoAgents 旧文档 404、v2 子路径导出、16 天 4 minor），全面押注有版本漂移风险（F4-3）。

**为什么不「不引入」：**
- 官方一等支持 Vue 3.3+（@copilotkit/vue），与本项目 Vue 3.5 + pnpm + Vite 栈兼容（F1-1/F1-5）；
- 全系 MIT、前端引入面小（1 个包 + 1 行样式）（F4-1/F4-2）；
- 能力面（Generative UI、HITL、Frontend Tools、流式聊天壳）恰是当前自研副驾 UI 的升级空间（F2-1~F2-5）；
- AG-UI 已是被 Google/LangChain/AWS/Microsoft 等采纳的行业协议（GitHub README），押注其协议层（而非某个框架版本）有中期复用价值。

### 5.2 最小验证路径（POC，建议 ≤5 人日）

**做什么：**
1. 独立起一个 Node sidecar（@copilotkit/runtime pin 1.74.x），BuiltInAgent type:"custom" factory 转发 POST /api/v1/ai-copilot/chat（先同步、后 /chat/stream），返回映射为 AG-UI 事件（路径 A，见 §3.2）。
2. ruoyi-ipd-web 的 apps/web-antd 新增一个演示路由，挂 CopilotSidebar（@copilotkit/vue/v2）+ styles.css 引入。
3. 加一个 useHumanInTheLoop 演示卡片（AntDV 组件做 render），模拟 R221「AI 草稿 → 人审确认」交互（仅前端交互，不动后端状态机）。

**验什么（5 项验收）：**
1. **流式无损**：自研 meta/delta/done/error SSE 契约 → AG-UI 事件映射可行，首帧 meta 结构化数据可先渲染、delta 逐字、done/error 可判。
2. **鉴权透传**：sa-token 在「Vue 前端 → sidecar → Spring Boot」链路打通（走 header/网关，不走 forwardedProps），OPERATION_AI_COPILOT 权限与 project_members 越权拦截仍生效，审计三件套仍按 BR-AI-04/05 落库。
3. **样式共存**：CopilotKit styles.css 与 Ant Design Vue 4.x / vben 主题（含暗色）无严重冲突（弹层 z-index、字体、滚动条）。
4. **工程门禁**：pnpm catalog 引入 + zod 版本对齐后，check:dep / check:circular / cspell / typecheck 全绿、pnpm build 通过、前端产物增量可接受。
5. **HITL 闭环**：useHumanInTheLoop 渲染自定义 AntDV 卡片并 respond 回传、agent 续跑的交互闭环可用。

**退出条件（任一命中即停，回退「自研 UI 渐进增强」路线）：**
1. 鉴权/审计链路无法在不削弱 BR-AI-04/05（审计三件套、越权拦截）前提下打通；
2. Node sidecar 的部署/运维不被接受（无 Node 生产环境或不接受新增部署单元），且路径 B 工作量评估大于收益；
3. 样式隔离成本高到需要 fork CopilotKit 样式才能与 AntDV 共存；
4. POC 期间 CopilotKit 发生破坏性变更，pin 1.74.x 无法维持（安全补丁强制升级等）。

**POC 红线（不做什么）：**
- 不改 /api/v1 既有契约；不动 AiExecutionEngine / AiExecReviewHook / GenerateExecutor 状态机；
- 不把 7 场景建议（AiSuggestionService）迁到 CopilotKit；不引入 @copilotkit/react-*；不在 POC 阶段引入 CopilotKit Intelligence（商业件）。

---

## 6. 风险与未决问题清单

| # | 风险/未决问题 | 级别 | 证据/说明 |
|---|---|---|---|
| R1 | 文档/API 处于 v2 重构期 | 高 | concepts/coagents/quickstart 实测 404（https://docs.copilotkit.ai/concepts/coagents/quickstart）；quickstart 用 /v2 子路径导出；16 天 4 minor（npm time） |
| R2 | Node sidecar 运维面 | 中 | Copilot Runtime 仅 Node（https://docs.copilotkit.ai/quickstart /vue）；官方无 Java Runtime 文档 |
| R3 | AG-UI Java SDK 成熟度 | 中 | 官方标 "Community"（https://github.com/ag-ui-protocol/ag-ui SDKs 表）；maven 坐标/版本/活跃度未验证 |
| R4 | 鉴权模型错位 | 中 | 官方明文禁止 forwardedProps 承载凭证/租户身份（https://docs.copilotkit.ai/custom-agent）；sa-token 透传方案须自建 |
| R5 | HITL interrupt 模式受限 | 中 | "Not supported on CopilotKit's Built-in Agent: Human in the Loop: Interrupts"（https://docs.copilotkit.ai/human-in-the-loop） |
| R6 | 双样式体系 | 中 | @copilotkit/vue/styles.css 自包含（https://docs.copilotkit.ai/vue）vs AntDV 主题；无官方 AntDV 适配 |
| R7 | zod/catalog 版本对齐 | 低 | catalog zod ^3.25.67（本仓 pnpm-workspace.yaml）vs @copilotkit/vue 需 ^3.25.75（npm） |
| R8 | runtime 依赖含 RC 版 | 低 | @copilotkit/runtime 依赖 type-graphql 2.0.0-rc.1（npm view）；仅影响 sidecar |
| R9 | CopilotKit Intelligence 商业化 | 低-中 | Rich Threads/Memories/Automatic Learning 为 Intelligence 产品（GitHub README），托管/自托管条款未验证 |
| R10 | transport 兼容坑 | 低 | custom-agent 页 "About the explicit useSingleEndpoint"："every released <CopilotKit> still pins it to true internally… Keep the {false}" —— 多路由/单路由配置易踩坑 |

**未能验证的点（明确列出，不得当事实引用）：**
1. @copilotkit/vue 在 ant-design-vue 4.x + vue-vben-admin monorepo 的实际渲染/构建兼容性（本次为只读调研，未做安装实测）。
2. AG-UI Java SDK 的 maven 坐标、版本号、维护活跃度、Spring Boot 3.5 集成示例（仅从 AG-UI README 得知 "Java ✅ Supported (Community)"）。
3. 前端绕过 Copilot Runtime 直连 AG-UI 端点（@ag-ui/client HttpAgent 与 @copilotkit/vue Provider 组合）是否被官方支持——文档仅展示 runtimeUrl 模式；GitHub README 称 useAgent "sits directly on AG-UI"，其边界未验证。
4. CopilotKit Intelligence（Rich Threads/Automatic Learning）的商业条款、定价与自托管资源要求。
5. pnpm 10.14 + Vite 7 对 @copilotkit/vue 的实际构建产物兼容（官方示例为 Vite dev server，未标注 Vite 大版本）。
6. @copilotkit/runtime 1.74 生产环境的资源占用、稳定性与安全审计情况。
7. 「CoAgents」术语在当前官方文档中的正式地位（旧 URL 404；是否保留该品牌未确认）。
8. 自研 SSE 契约（meta/delta/done/error）与 AG-UI ~16 事件的逐事件映射表（本报告仅给方向，未逐事件核对）。
9. 官方是否已存在 Java/Spring 版 Copilot Runtime（检索未发现；「未发现」不等于「不存在」）。

---

## 7. 来源清单

**一手（官方）：**
- [S1] https://docs.copilotkit.ai/quickstart — Quickstart（Runtime 部署形态、包安装、Inspector）
- [S2] https://docs.copilotkit.ai/vue — Vue quickstart（@copilotkit/vue、Vue 3.3+、pnpm 命令、组件/composables 清单）
- [S3] https://www.npmjs.com/package/@copilotkit/vue — npm 包页（另有 /@copilotkit/runtime、/@copilotkit/react-core、/@copilotkit/react-ui、/@copilotkit/shared；本次版本/许可/依赖/发布节奏经 npm view registry 查询）
- [S4] https://docs.copilotkit.ai/model-selection — Model Selection（OpenAI 兼容端点、bring-your-own LLM、createOpenAI({baseURL})、forwardedProps 语义）
- [S5] https://github.com/CopilotKit/CopilotKit — 官方仓库 README（能力清单、平台支持表、MIT License、Intelligence/Channels、AG-UI 生态）
- [S6] https://docs.copilotkit.ai/human-in-the-loop — HITL Overview（useHumanInTheLoop / useInterrupt 双模式与 Built-in Agent 限制）
- [S7] https://docs.copilotkit.ai/custom-agent — Custom Agent / factory 模式（fetch 任意后端→AG-UI 事件、forwardedProps 安全告诫、useSingleEndpoint 坑）
- [S8] https://github.com/ag-ui-protocol/ag-ui — AG-UI 协议仓库（协议定位、~16 事件、SDKs 含 Java Community、MIT）
- [S9] https://docs.copilotkit.ai/integrations — Integrations 总览（前端/agent 后端矩阵）；https://docs.copilotkit.ai/langgraph — LangGraph over AG-UI
- [S10] https://docs.copilotkit.ai/generative-ui — Generative UI（useComponent）；https://docs.copilotkit.ai/frontend-tools — Frontend Tools（useFrontendTool）
- [S11] https://docs.copilotkit.ai/concepts/coagents/quickstart — （实测 404，作为文档重构证据）
- [S12] https://www.npmjs.com/package/@ag-ui/client — AG-UI 客户端 SDK（HttpAgent 直连、SSE/protobuf）

**本仓一手（本地只读）：**
- /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiCopilotService.java（CopilotStreamSink、classifyIntent）
- 同目录 service/AiSuggestionService.java（SCENES 七场景白名单）
- 同目录 service/AiExecutionEngine.java、service/aiexec/GenerateExecutor.java（人审红线）
- 同目录 service/AiExecReviewHook.java（人审闭环）
- 同目录 controller/AiCopilotController.java、controller/StageActionController.java（POST /{id}/ai-execute）
- /Users/mac/Documents/ruoyi-ipd-web/package.json、pnpm-workspace.yaml、apps/web-antd/package.json（技术栈与门禁）

**旁证（第三方，仅参考、结论未依赖）：**
- https://docs.cloudbase.net/ai/agent-development/protocol — AG-UI 的 POST + SSE 事件帧格式示例（旁证）
- CSDN/51CTO 若干 AG-UI 协议解读文（旁证协议形态）

---

*报告生成：2026-09-26。调研方式：官方文档/官方 GitHub/npm registry 一手取证 + 本仓源码只读比对；未安装、未运行、未修改任何代码与依赖。*
