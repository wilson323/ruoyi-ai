# CopilotKit 前端融合可行性分析（2026-09-27，交 owner 拍板版）

> 任务性质：只读调研分析。本任务未引入任何依赖、未修改任何前端/后端业务代码、未装包、未改 package.json / pnpm-workspace.yaml / vite 配置。唯一写入物：本文档 + log.md 登记。
> 分析对象：CopilotKit（https://github.com/CopilotKit/CopilotKit ，官方文档 https://docs.copilotkit.ai/ ）。硬边界：不是用 CopilotKit 替代整个前端，只评估「融合进现有前端」。
> 目标前端：/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd（Vben Admin 5 + Vue 3 + Ant Design Vue + TypeScript strict + pnpm 10.14.0 + turbo；本机 127.0.0.1:15666，后端代理 127.0.0.1:16039）。
> 检索日期：2026-09-27（外部事实）；本仓代码/配置事实为 2026-09-27 磁盘现查。行号仅作辅助，以符号名/键名为准。
> 前序文档关系：`CopilotKit全局调研分析-20260926.md`（2026-09-26 调研波）为同主题前序报告。本报告对其事实做了抽检复核并补充了四个前序未加权的约束（ZK-IPD 原型一致性红线、C08 suggest 红线、R214 反双轨纪律、R221 人审闭环已落地），**结论以本报告为准**（差异见 §6）。

---

## 0. 摘要（结论先行）

**结论：不适合现阶段把 CopilotKit 融合进 ruoyi-ipd-web。保持现有自研 AI 前端方案。置信度：中高（约 75%）。**

三条承重理由（详细证据见 §1–§4）：

1. **能力对账后主收益与现有自研重复，真增量拿不到手**。聊天壳、流式输出、结构化数据渲染、人审交互，本项目已有实现且被 1299 项前端测试锁定；CopilotKit 独有的增量（Generative UI、HITL 原语、Rich Threads、Automatic Learning）要么属于商业件 CopilotKit Intelligence，要么需要新增 Node 适配层才能消费——为「壳」付「核」的价钱，成本收益倒挂，且构成 owner 明令避免的「双轨」。
2. **与业务红线存在显式摩擦面，引入后防线要重建而不是复用**。CopilotKit 的 Frontend Tools 允许 agent 直接调用浏览器端函数（含提交类动作），开箱即用形态默认违反 C08 suggest 红线（AI 只建议、人目检后手动提交）；BR-AI-04 常驻风险提示、BR-AI-05 越权校验、审计三件套均无 CopilotKit 等价物；ZK-IPD 原型一致性红线又限制其自带样式 UI 铺进业务页面。
3. **后端对接没有零成本路径**。Copilot Runtime 是 Node 服务（官方无 Java/Spring 版），与本项目 /api/v1（code0/message 包络 + 字符串 ID + sa-token 会话）+ 自研四帧 SSE（meta/delta/done/error）分属两种协议模型，必须新增适配层（Node sidecar 或 Java 实现 AG-UI 端点），为一个前端 UI 框架新增部署单元与鉴权转发链不成比例。

结论翻转条件见 §5.4（满足其一即可重启评估）；双向差距清单见 §5.3；风险登记见 §5.5。

---

## 1. 维度一：技术栈兼容性

### 1.1 事实

**F1-1（官方 Vue 支持，一手证实）**：CopilotKit 不是 React-only，官方提供 Vue 包。
- 来源：官方文档留档 `docs/最佳实践/copilotkit/copilotkit-docs-full.md`（2026-09-27 抓自 docs.copilotkit.ai/llms-full.txt，1236 页），页 `## Source: https://docs.copilotkit.ai/frontends/vue`（留档 L45151 起）。
- 原文引用："`@copilotkit/vue` provides Vue 3 components and composables for CopilotKit."；前置条件 "Vue 3.3+"、"Node.js 20+"；安装命令官方直接给 pnpm 路径 `pnpm add @copilotkit/vue @copilotkit/runtime`；"The composables (`useAgent`, `useFrontendTool`, `useHumanInTheLoop`, and more) mirror the React SDK."。
- 另有 Vue 专章：`/frontends/vue/guides/generative-ui`、`/frontends/vue/guides/threads-and-drawer` 与参考页 `/reference/vue/*`（CopilotKitProvider / CopilotChat / CopilotSidebar / CopilotPopup / useAgent 等，留档 L45340、L166392 起）。

**F1-2（GitHub 平台矩阵，一手证实）**：来源 https://github.com/CopilotKit/CopilotKit （README "Works With Your Stack"，2026-09-27 检索）。原文：平台表 "⚛️ React / Next.js ✅ GA"、"🅰️ Angular ✅ Supported"、"💚 Vue ✅ Supported"、"📱 React Native ✅ Supported"。注意 README 的 Vue 行写 "Source Code - Quickstart coming soon"，但文档站已有完整 Vue 指南（F1-1）——README 滞后于文档，以文档为准。

**F1-3（包形态，npm registry 一手证实，2026-09-27）**：`@copilotkit/vue@1.74.0`（https://registry.npmjs.org/@copilotkit/vue/latest ）：license=MIT，peerDependencies={vue:">=3.3.0"}，engines={node:">=20"}，unpackedSize=4,707,911 字节，导出含 `.`、`./v2`、`./styles.css`。`@copilotkit/runtime@1.74.0`：license=MIT，unpackedSize=4,719,052 字节，Node 服务端包。

**F1-4（本仓技术栈，磁盘现查）**：`ruoyi-ipd-web/pnpm-workspace.yaml` catalog：vue ^3.5.17、ant-design-vue ^4.2.6、vite ^7.1.2、typescript ^5.8.3、zod ^3.25.67；**catalog 中无 react**。→ Vue 3.5 ≥ 3.3 ✓、Node 22 ≥ 20 ✓；zod 有版本下限差（见 §4）。

### 1.2 三条集成路径评估

| 路径 | 做法 | 改造量 | 运行时风险 | 判定 |
|---|---|---|---|---|
| 甲：官方 Vue 适配 | 引 `@copilotkit/vue`（Provider + Chat/Sidebar/Popup + composables）挂进现有壳 | 中：AI 副驾壳层换/并存 + styles.css 双样式体系 + sidecar 仍需（见 §3） | 双样式体系（自带样式 vs AntDV 主题）、包体增量 | 技术可行，价值存疑（见 §2） |
| 乙：React 混合挂载 | micro-frontend / React→Vue 桥接，只为用 React 版组件 | 高：引入 react+react-dom 双框架共存、事件/路由/状态穿透、构建链改动 | 双框架体积与生命周期冲突（bundle 增量未实测，量级约 +150KB gz 起） | **排除**：官方 Vue 包已存在（F1-1），此路径零额外收益、纯增成本 |
| 丙：仅接协议层（AG-UI）自建 UI | 保留全部现有 Vue UI，只把传输层换成 AG-UI 事件流 | 中高：~16 种 AG-UI 事件与自研四帧 SSE 的映射 + 后端/sidecar 适配（见 §3） | 不消费 CopilotKit 的 UI 能力，价值只剩协议互通 | 与「融合 CopilotKit」的初衷不符，价值最薄 |

**等价替代方案**：保持自研（现状 `views/ipd/_shared/ai-assistant.vue` + `api/ipd/ai-copilot.ts` fetch+ReadableStream 自解析四帧）是事实上的基线替代品——已工作、被测试锁定、与业务红线咬合。若只想优化体验细节（markdown 渲染、代码高亮、消息骨架屏），纯 Vue 轻量库即可，不需要框架级引入。

### 1.3 小结

技术栈兼容性**不构成硬障碍**（官方 Vue 一等支持，无需 React 混合挂载），但它只回答了「能不能装」，不回答「值不值得装」——价值判断在 §2/§3/§4。

---

## 2. 维度二：现有能力对齐

### 2.1 本项目已有 AI 前端能力（磁盘现查，全部有测试锁定）

| 能力 | 实现位置 | 关键契约 |
|---|---|---|
| 对话即填表 | `views/ipd/_shared/ai-assistant.vue`（done 帧携 fillPayload → `window.dispatchEvent(new CustomEvent('ipd:ai-fill-payload'))`）；消费端 `views/ipd/project/action-detail/index.vue` | R221：仅 suggest 模式回填 6 个可持久化字段（剔除 remark）、**mode=auto 防御性忽略、绝不自动 saveFields**（`action-detail/index.test.ts` 负向断言锁定） |
| AI 建议面板 | `views/ipd/_shared/ai-suggest.vue` + `api/ipd/ai-suggest.ts` | 7 场景白名单、markdown 纯文本、adoptable 门控默认关、降级应答、BR-AI-04 提示 |
| AI 执行按钮 | `views/ipd/project/action-detail/index.vue` + `api/ipd/stage-action.ts::aiExecuteStageAction`（POST /stage-actions/{id}/ai-execute） | R221 执行状态机：AI 草稿只到 IN_PROGRESS 待人审，绝不代签 DONE（后端 `service/aiexec/GenerateExecutor` 等） |
| SSE 流式 | `api/ipd/ai-copilot.ts`（fetch + ReadableStream 自解析；EventSource 带不了 Bearer 头） | 四帧契约：meta（结构化数据）/ delta（增量 token）/ done（status+token+fillPayload）/ error；history 最多 8 轮前端裁剪 |

后端配套（磁盘现查）：`service/AiCopilotService.java`（意图分类 TASKS/ADVANCE/FILL_PAGE/CHITCHAT、三档上下文注入、`assertProjectVisible` 越权拦截、审计三件套、`FILL_FIELD_WHITELIST`）、`service/AiSuggestionService.java`（7 场景白名单、**AI 输出只返回 markdown 绝不写业务表**）、`controller/AiCopilotController.java`（`@SaCheckPermission(OPERATION_AI_COPILOT, type=IpdAuthSession.LOGIN_TYPE)`、SseEmitter 60s 超时）。

### 2.2 逐项对照：增强 / 重复 / 替代

| 本项目能力 | CopilotKit 对应物 | 判定 |
|---|---|---|
| 副驾聊天壳（FAB+抽屉+流式渲染） | CopilotChat / CopilotSidebar / CopilotPopup + 内建 streaming | **重复**。壳层体验可平替，但现有壳已与 AntDV、BR-AI-04 提示、fill-payload 广播链融合，换壳是净支出 |
| SSE 流式四帧 | AG-UI 事件流（RUN_* / TEXT_MESSAGE_* 等） | **重复但契约不同**。两种事件模型并存需映射层，是新增复杂度不是增强 |
| 结构化数据渲染（meta 帧 data 项） | Generative UI（tool 结果渲染为组件） | **增强（呈现层）**。结构化卡片呈现是 CopilotKit 真增量中最实在的一块，但业务数据装配仍须留在后端 |
| AI 建议 + 人目检 + 手动提交 | HITL（`useHumanInTheLoop` / `useInterrupt`） | **部分增强、语义不同构**。HITL 是「会话内暂停-作答-续跑」，本项目是「跨会话持久状态机 + 状态流转 + 审计」（R221），后者更强；且现档 HITL 双模式表中 `useInterrupt` 明确绑定 LangGraph 后端（留档 L52256-52259），对本项目后端不适用 |
| 越权校验（project_members）/ 审计三件套 / 意图分类 | 无对应物 | **不可替代**。必须保留后端现状 |

### 2.3 红线冲突显式清单（不含糊）

1. **C08 suggest 红线（AI 只建议、人目检后手动提交）——存在默认形态冲突**。
   - 冲突机制：CopilotKit Frontend Tools 官方定义（留档 L3551）："Frontend tools enable you to define client-side functions that your agent can invoke, with execution happening entirely in the user's browser… direct access to the frontend environment."——即 agent 可直接调用浏览器端函数。若把「保存字段/提交」类动作注册为 tool（官方教程的自然用法），agent 可绕过人审直接提交，**开箱即用形态默认违反红线**。
   - 现有防线（必须重建才能兼容）：fill-payload 只回填不提交、mode=auto 忽略、`saveFields` 不被自动调用（`action-detail/index.test.ts` 负向断言）。引入 CopilotKit 后需等价防线：tool 白名单只允许「渲染/填充」类、禁止注册任何写操作 tool、同样用测试锁死。
   - HITL 语义差异：`useHumanInTheLoop` 的 respond 是「人答完 agent 继续跑」，人是流程的一个步骤；红线要求人是**唯一提交者**。可兼容，但必须限定 HITL 只用于「选择/确认建议」，不得用于「确认后代提交」。
2. **BR-AI-04（不做内容过滤 + UI 常驻风险提示**，定义见 `docs/ipd-系统说明/外部资源/IPD系统_AI开发主Prompt_v3.md`："系统不做内容过滤，直接透传模型返回结果，由 PM 人工把控内容风险。UI 上须有明确风险提示"**）**：CopilotKit 不做内容过滤（天然兼容），但其自带 UI 无常驻风险提示 → 需定制 slot/自绘提示条，属可解决的改造项，不构成否决，但必须显式保留。
3. **BR-AI-05 越权 + 审计三件套（aiAssisted/aiModel/aiRole）**：CopilotKit 无 sa-token、无 project_members 可见性、无本项目审计语义 → 必须继续在 Spring Boot 侧执行。任何「让 CopilotKit/agent 直连业务写接口」的形态都绕过审计与越权，属禁止形态。
4. **ZK-IPD 原型一致性红线**（`docs/ipd-系统说明/治理/ZK-IPD一致性红线-20260906.md`：UI/交互/样式/布局/字段须与原型一致、逐页复刻）：CopilotKit 自带样式（styles.css 自包含）不能铺进原型内业务页面；可复用面因此收缩到 headless 原语（状态、传输、tool 注册）——恰与现有自研重叠，进一步压缩引入价值。

### 2.4 小结

CopilotKit 与自研 AI 栈是「壳 vs 核」关系：壳层是重复，呈现层有增强，核层不可替代；且壳层能力（Frontend Tools）默认形态与 C08 红线冲突，要用就得先建防线。**对齐结果：重复为主、增强有限、冲突可控但必须重建防线。**

---

## 3. 维度三：后端对接成本

### 3.1 事实

**F3-1 Copilot Runtime 是 Node 服务，官方无 Java/Spring 版**。来源：留档 `/frontends/vue` 页（L45215-45246）：Node `createServer(createCopilotNodeListener({ runtime, basePath: "/api/copilotkit", cors: true }))`；"The runtime runs on your server, keeps model credentials out of the browser"。官方文档未见 Java Runtime（未发现≠不存在，按现有证据视同无）。

**F3-2 自研四帧 SSE ≠ AG-UI 事件流**。本项目四帧契约（meta/delta/done/error，`AiCopilotController` javadoc + `ai-copilot.test.ts` 夹具现查）与 AG-UI 的生命周期事件模型（RUN_* / TEXT_MESSAGE_* / TOOL_CALL_* / STATE_* 等）无一一对应，需要逐事件映射（映射表未验证，属实施期工作）。

**F3-3 官方明文禁止用 forwardedProps 承载凭证**。留档 L21300（现查）："Use `forwardedProps` only for non-secret, browser-controlled preferences. Validate…"。→ sa-token 身份必须走 header/网关透传，不得走 forwardedProps。

**F3-4 /api/v1 包络与序列化契约**（磁盘现查）：响应包络 `{code, message, data, timestamp, traceId}`（`ai-copilot.test.ts` 夹具）；字符串 ID + ISO 时间文本（防前端 BigInt 截断，`vo/KpiSharedConfirmView.java` javadoc）；错误码数字对齐 `ApiV1ErrorCode.java`（前端镜像 `views/ipd/_shared/ipd-error-text.ts`，见前端架构规约 §4）。

### 3.2 改造点清单（按模块粒度估算，本次不实施）

| # | 改造点 | 路径甲（Node sidecar） | 路径 B（Spring Boot 实现 AG-UI 端点） |
|---|---|---|---|
| 1 | 新工程/部署单元 | 新增 Node sidecar（@copilotkit/runtime pin 1.74.x）：服务、进程守护、健康检查、CORS/网关路由 | 无 sidecar，但新增后端端点模块 |
| 2 | 鉴权透传 | Vue→sidecar→Spring Boot 链路 header 承载 sa-token Bearer；禁止 forwardedProps（F3-3） | 天然留在 sa-token 体系内 |
| 3 | 包络转换 | sidecar 解 `{code,message,data,timestamp,traceId}` → AG-UI 事件；ApiV1ErrorCode → ERROR 事件映射表 | 同左，但发生在 Java 内 |
| 4 | SSE 帧映射 | meta/delta/done/error → AG-UI 生命周期事件；**done 帧 fillPayload（对话即填表载荷）在 AG-UI 无现成语义**，需自定义 tool call 或状态快照承载 | 同左 |
| 5 | 会话/多轮 | 现状 history 8 轮前端裁剪 → AG-UI threadId/runId/messages 模型的映射与裁剪语义搬移 | 同左 |
| 6 | 字符串 ID | sidecar/JS 层严禁把 ID Number 化（需代码规约 + 测试锁定） | Java 侧风险小（Jackson 已按字符串出） |
| 7 | 前端壳层 | CopilotKitProvider 挂载、styles.css 引入、Chat 组件替换/并存、fill-payload 广播链改由 AG-UI 事件驱动 | 同左 |
| 8 | 后端改动 | **Spring Boot 零改动**（适配全在 sidecar） | 新增 AG-UI 端点（~16 事件生命周期、断线/abort 语义自研；AG-UI Java SDK 标注 Community 维护、成熟度未验证——引自 2026-09-26 报告，本轮未复核） |

### 3.3 小结

对接成本**中高**：无论哪条路径都要新增「协议适配层 + 鉴权转发 + 帧映射」三件套。路径甲保 Spring Boot 零改动但新增 Node 部署单元；路径 B 无 sidecar 但把 ~16 种事件的生命周期管理搬进 Java 自研。没有任何一条是「装个包就通」的。

---

## 4. 维度四：依赖与治理风险

### 4.1 依赖体积与形态（npm registry 一手，2026-09-27）

- **前端引入面**：`@copilotkit/vue@1.74.0`（unpacked ≈4.7MB）。直接依赖 12 个：`@copilotkit/core/shared/web-inspector/web-components@1.74.0`、`@ag-ui/core`/`@ag-ui/client@0.0.59`、`@a2ui/web_core@0.10.4`、zod ^3.25.75、zod-to-json-schema、**katex**（数学渲染，含字体）、streamdown-vue、lucide-vue-next、@jetbrains/websandbox。gzip 后实际 bundle 增量未实测（未验证项）。
- **图标库重复**：lucide-vue-next 与本仓现用 @phosphor-icons/vue 并存（双图标库）。
- **服务端重依赖必须隔离**：`@copilotkit/runtime@1.74.0` 依赖 ai/openai/hono/graphql/**type-graphql 2.0.0-rc.1（RC 版）**/ws/pino/rxjs 等，另含 **@scarf/scarf（遥测）**、**@copilotkit/license-verifier**、**@copilotkit/channels-intelligence（商业组件）**。这些不得进 vben workspace，只能锁在 sidecar；内网部署需评估遥测外发与 license 校验组件的行为（两者行为均未验证）。

### 4.2 许可证

- **MIT 全链路**：GitHub README License 段原文 "This repository's source code is available under the MIT License."（2026-09-27 检索）；npm 两包 license=MIT（registry 同日复核）。商用无障碍。
- **边界**：CopilotKit Intelligence（Rich Threads / User Memories / Automatic Learning / Product Analytics）是商业产品（托管或自托管），不在 MIT 范围内；本分析不引入、不评估其条款（未验证）。AG-UI 协议 MIT（引自 2026-09-26 报告，本轮未复核）。

### 4.3 构建链兼容与门禁冲突面

| 门禁/构建项 | 冲突面 | 评估 |
|---|---|---|
| pnpm 10.14 workspace catalog | zod：@copilotkit/vue 需 ^3.25.75 vs catalog 现值 ^3.25.67（`pnpm-workspace.yaml` 现查） | 需上调 catalog（小改动，但按规矩过审） |
| turbo / Vite 7 | 官方 Vue 指南以 Vite 为 dev server，无官方兼容反证 | 大版本兼容未实测（未验证） |
| `check:type`（vue-tsc strict） | @copilotkit/vue 类型在 strict 下兼容性 | 未实测（未验证） |
| `vitest`（现 1299 tests） | fill-payload 回填例、mode=auto 忽略例、saveFields 不调用例等负向断言锁死现状行为 | 新壳必须保持全绿或改测试须逐条说明——护栏有效 |
| `build:antd` | 包体增量、katex 字体资源 | 增量未实测 |
| ipd-frontend-drift-guard（前端架构规约 §5.1） | 新增 `api/ipd/*.ts` 导出与既有冲突会被 exit 2 拦截；`<domain>-error.ts` 实函数被拦 | CopilotKit 接入若新增 API 文件须遵守「一域一文件」规约落位 |
| check-api-contract-fe-be.mjs（R212 棘轮） | 路径甲：前端调 sidecar 不进扫描面，**零影响**；路径 B：后端 AG-UI 新端点=孤儿端点 → 退出码 bit4 拦截，须走白名单/baseline 人审流程 | 路径甲友好，路径 B 有新增门禁面 |

### 4.4 维护成本

- **演进极快（双刃剑）**：2026-09-26 报告实测 1.71.0→1.74.0 为 16 天 4 个 minor + 4 个 patch（npm time）；registry 导出含 `./v2` 子路径（今日复核）证实 API 仍在 v2 重构期。引入即需「pin 死 1.74.x + 升级当独立变更评审」纪律，长期跟随成本真实存在。
- **团队技能面**：Vue 侧技能栈吻合；新增 Node sidecar 运维 + AG-UI 协议知识是团队新负担。

### 4.5 小结

许可证无障碍、包体无硬伤；真正的治理风险是「快节奏演进 + v2 重构期」的版本漂移，和 runtime 传递依赖里的 RC 版/遥测/许可校验组件（内网部署需专项评估）。总体**可控但不轻**。

---

## 5. 维度五：结论与路径

### 5.1 唯一结论

**不适合现阶段引入（保持现有自研方案）。置信度：中高（约 75%）。**

判定逻辑（把四维证据合起来看）：
1. 收益侧：能拿到的（聊天壳、流式、结构化呈现）与已有自研**重复**；想要的独有增量（Generative UI 呈现、HITL 原语、Rich Threads、自动学习）要么是商业件、要么要先付适配层成本；
2. 成本侧：新增适配层三件套（协议映射 + 鉴权转发 + 包络转换）+ 潜在 Node 部署单元 + 双样式体系 + 版本跟踪纪律；
3. 约束侧：C08 suggest 红线默认被 Frontend Tools 范式违反（防线要重建）、ZK-IPD 原型红线压缩 UI 换壳价值、R214「避免过度设计与双轨」纪律直接指向「不要为已有能力引入第二套框架」。

「有条件适合」的门槛本报告认为现阶段不满足：唯一现实的受益场景（复杂 agent 工作流的 Generative UI/HITL 交互）尚不在当前产品路线图的必经路径上；R221 人审闭环已用「跨会话状态机 + 审计」把 HITL 语义以更强形态落地。

### 5.2 保持自研方案的理由

1. 现有方案完整覆盖当前产品需求（对话、流式、建议、填表、执行、人审），且每条都有测试锁定与红线防线；
2. 与后端契约（包络/字符串 ID/sa-token/审计三件套）零适配成本、零新增部署单元；
3. UI 与 AntDV/原型一致性天然满足，无双样式体系；
4. 依赖面零新增，无版本漂移跟踪负担。

### 5.3 双向差距清单

**CopilotKit 有而我们没有：**
- Generative UI 三形态（组件渲染 / A2UI 声明式 / MCP Apps）与 tool 结果卡片化呈现；
- 标准化 HITL 原语（`useHumanInTheLoop` 会话内暂停-作答-续跑，含自定义渲染卡）；
- Shared State 双向状态同步（agent 与 UI 互写）；
- AG-UI 标准协议互通（与 LangChain/CrewAI/Mastra/PydanticAI 等 agent 生态直接对接）；
- Rich Threads / User Memories / Automatic Learning / Product Analytics（注：属商业件 Intelligence）；
- 多端复用（同一 agent 接 React/Angular/Vue/RN/Slack/Teams）。

**我们有而 CopilotKit 不覆盖：**
- 意图分类（TASKS/ADVANCE/FILL_PAGE/CHITCHAT）与业务兜底路径；
- sa-token（loginType=ipd）会话、OPERATION_AI_COPILOT 权限注解、project_members 越权校验（BR-AI-05）；
- 审计三件套（aiAssisted/aiModel/aiRole，AI_COPILOT_CHAT/STREAM、AI_SUGGEST 落库规约）；
- C08 suggest 红线防线（只回填不提交、mode=auto 忽略、负向测试锁定）；
- R221 执行状态机与人审闭环（跨会话持久化、状态流转、交付物挂载、通知唤醒）；
- 7 场景建议白名单 + prompt 注入防护 + 降级应答；
- /api/v1 包络、字符串 ID、错误码中文映射等本地契约。

### 5.4 结论翻转条件（满足其一即重启评估）

1. 产品路线图明确需要复杂 agent 工作流 UI（多步工具链可视化、会话内表单交互卡）且自研成本被实测高于引入；
2. 出现 AG-UI 生态互通的硬需求（对接第三方 agent 框架/多 agent 编排）；
3. 基础设施侧接受新增 Node sidecar 部署单元（或 AG-UI Java SDK 成熟度经实测达标）；
4. CopilotKit Vue API 连续两个 minor 无破坏性变更、v2 重构收尾，且无样式（headless）模式经实测可与 AntDV 干净共存。

届时按前序报告 §5.2 的 POC 方案（Node sidecar + custom factory + 演示路由 + HITL 演示卡，≤5 人日）做隔离试点，POC 红线照旧（不改 /api/v1 契约、不动 R221 状态机、不迁 7 场景建议、不引 react 系包与 Intelligence）。

### 5.5 风险登记（引入派视角，留档备查）

| # | 风险 | 级别 | 证据 |
|---|---|---|---|
| R1 | C08 红线被 Frontend Tools 默认形态违反 | 高 | 留档 L3551 tool 定义 + 本仓负向测试现状 |
| R2 | 后端适配层三件套成本（协议映射/鉴权转发/包络转换） | 高 | §3.2 清单 |
| R3 | 版本漂移（16 天 4 minor + v2 重构期） | 高 | 2026-09-26 报告 npm time；registry `./v2` 导出（今日复核） |
| R4 | 双样式体系与 ZK-IPD 原型红线摩擦 | 中 | styles.css 自包含（留档 L45251）+ 治理/ZK-IPD一致性红线-20260906.md |
| R5 | runtime 传递依赖（type-graphql RC / @scarf 遥测 / license-verifier）内网合规 | 中 | registry 依赖清单（今日复核），组件行为未验证 |
| R6 | HITL `useInterrupt` 绑 LangGraph，本项目用不上 | 中 | 留档 L52256-52259 双模式表 |
| R7 | zod catalog 版本下限差 | 低 | catalog ^3.25.67 vs 需求 ^3.25.75（均现查） |
| R8 | fillPayload 载荷在 AG-UI 无标准承载 | 中 | 四帧 done 帧契约 vs AG-UI 事件模型（映射未验证） |

---

## 6. 与前序报告（2026-09-26）的差异

前序结论为「试点 POC（有条件引入）」，本报告结论为「不适合现阶段引入」。差异不在于事实（承重事实一致且经本轮抽检复核：@copilotkit/vue 存在与版本、Runtime 为 Node 服务、MIT 许可均今日复核无误），而在于**权重**——本轮补充了四个前序未加权的约束：
1. ZK-IPD 原型一致性红线：限制 CopilotKit 自带 UI 的铺开面，可复用面收缩到 headless 原语后与自研高度重叠；
2. C08 suggest 红线与 Frontend Tools 范式的默认冲突（防线重建成本）；
3. R214「避免过度设计与双轨」纪律：为已有能力引入第二套框架即双轨；
4. R221 人审闭环已落地：HITL 语义已被更强的跨会话形态覆盖，CopilotKit HITL 的增量进一步缩小。

同时修正前序两处引证状态：「Built-in Agent 不支持 HITL Interrupts」原句在 2026-09-27 留档中未复现（文档 v2 重构改写），现档等价证据为 HITL 双模式表（useInterrupt 的后端面是 "a server-side `interrupt()` call in your LangGraph agent"）；`forwardedProps` 安全告诫在现档措辞为 "Use `forwardedProps` only for non-secret, browser-controlled preferences."（语义一致）。

---

## 7. 证据索引

**外部一手（检索日期 2026-09-27）：**
- [E1] 官方文档留档 `docs/最佳实践/copilotkit/copilotkit-docs-full.md`（2026-09-27 抓自 https://docs.copilotkit.ai/llms-full.txt ，1236 页 / 201,390 行）：`/frontends/vue`（Vue 支持、pnpm 安装、Runtime 形态、composables 清单）、`/human-in-the-loop`（双模式表）、frontend-tools 页（tool 定义）、custom-agent 页（forwardedProps 告诫）；具体引用行号见正文 F 条目。
- [E2] https://github.com/CopilotKit/CopilotKit （README，2026-09-27 检索）：平台支持表、能力清单、MIT License、Intelligence 商业分层。
- [E3] npm registry（2026-09-27 检索）：https://registry.npmjs.org/@copilotkit/vue/latest 、https://registry.npmjs.org/@copilotkit/runtime/latest —— 版本 1.74.0、license、peer/engines、依赖树、unpackedSize、`./v2` 导出。

**本仓一手（2026-09-27 磁盘现查）：**
- 前端：`ruoyi-ipd-web/apps/web-antd/src/api/ipd/ai-copilot.ts`（四帧契约注释）、`api/ipd/ai-suggest.ts`、`api/ipd/stage-action.ts::aiExecuteStageAction`、`views/ipd/_shared/ai-assistant.vue`（fill-payload 广播 + BR-AI-04 提示）、`views/ipd/_shared/ai-suggest.vue`、`views/ipd/project/action-detail/index.vue` + `index.test.ts`（suggest 红线负向断言）、`api/ipd/ai-copilot.test.ts`（包络夹具）。
- 后端：`ruoyi-modules/ruoyi-ipd/.../service/AiCopilotService.java`、`service/AiSuggestionService.java`、`controller/AiCopilotController.java`、`controller/IpdSseController.java`、`vo/KpiSharedConfirmView.java`（字符串 ID 契约注释）。
- 治理：`docs/ipd-系统说明/前端架构规约-20260906.md`、`docs/ipd-系统说明/治理/ZK-IPD一致性红线-20260906.md`、`docs/ipd-系统说明/外部资源/IPD系统_AI开发主Prompt_v3.md`（BR-AI-04 定义）、`ruoyi-ai/scripts/check-api-contract-fe-be.mjs`（R212 棘轮）、`ruoyi-ipd-web/pnpm-workspace.yaml`（catalog）。

**引自前序报告（2026-09-26 检索，本轮未逐条复核，标注继承）：**
- npm 发布节奏（1.71.0→1.74.0 时间戳）、AG-UI 协议仓库（~16 事件、Java SDK Community、MIT）、@copilotkit/runtime 完整依赖树细节、@copilotkit/vue 解包体积 4.7MB（今日 registry 复核一致）。

---

## 8. 未决项（不得当事实引用）

1. @copilotkit/vue 在 pnpm 10.14 + Vite 7 + vue-tsc strict 下的实际构建/类型兼容（未安装实测）；
2. zod catalog ^3.25.67 与 @copilotkit/vue 需求 ^3.25.75 的 pnpm 实际解析结果（未实测）；
3. gzip 后前端 bundle 实际增量、katex 字体对产物的影响（未实测）；
4. 自研四帧 → AG-UI 事件的逐事件映射表（含 fillPayload 承载方案，未设计）；
5. AG-UI Java SDK 的 maven 坐标/版本/活跃度（未验证）；
6. @scarf/scarf 遥测外发行为与 @copilotkit/license-verifier 行为（未验证，内网部署前必须查清）；
7. CopilotKit Intelligence 商业条款与自托管资源要求（未验证，本次不引入）；
8. CopilotKit 多实例/长连接在生产网关（如 nginx）下的代理配置要求（未验证）。

---

*报告生成：2026-09-27。调研方式：官方文档留档 + GitHub README + npm registry 一手取证，本仓源码/配置磁盘现查比对；未安装、未运行、未修改任何代码与依赖。结论交 owner 拍板，拍板前不引入任何新依赖、不改前端业务代码、不改后端代码；拍板后如需实施另行派单并先确认 allowedPaths。*
