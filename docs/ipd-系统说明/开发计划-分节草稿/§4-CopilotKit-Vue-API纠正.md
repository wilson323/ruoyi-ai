# §4 CopilotKit Vue 真实 API 纠正表 — 分节草稿

> **状态**：草稿（供主计划 §4 合入前评审）　**产出日期**：2026-09-28
> **范围**：仅 API 事实对账与偏差纠正，不含实现代码。本文件为新建分节草稿，不修改任何既有文件。
> **覆盖**：9 组件 + 14 composable 真实签名 **23/23 全实抓**（另附 CopilotThreadsDrawer 附加组件）；纠正表 8 条（3 条硬偏差 + 5 条补强/澄清）；渲染守则；注册名静默失败机制；Threads 自部署结论。

## 4.0 事实源与取证口径

| 代号 | 事实源 | 用途 | 取证方式 |
|---|---|---|---|
| **S1** | `最佳实践/CopilotKit-KB/Vue专题/CopilotKit-Vue-官方文档汇编.md`（6988 行 / 27 段） | **官方 API 签名权威** | 本地只读全文；下文行号列 = S1 行号 |
| **S2** | `ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-assistant.vue`（971 行） | 本仓 Provider 挂载现状 | 本地只读 |
| **S3** | `ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared/ai-cards/copilotkit-render.ts`（243 行） | 本仓渲染层现状 | 本地只读 |
| **S4** | `ruoyi-ipd-web/docs/copilotkit单轨融合契约-20260928.md`（301 行） | 5 条单轨红线 + 映射语义 SSOT | 本地只读 |
| **S5** | `@copilotkit/vue@1.74.0` 实装 dist（node_modules/.pnpm 实测） | 类型导出面 / 传递依赖实证 | 本地只读 rg |
| **S6** | `ruoyi-ai/ruoyi-modules/ruoyi-ipd/.../org/ruoyi/ipd/copilotkit/`（5 类） | Java AG-UI 桥现状 | 本地只读 |

**口径规则**：
1. props/签名逐条取自 S1，行号可复核；S5 仅用于验证「类型是否可导入 / 依赖是否在场」。
2. 推断性结论标「推断」，抓不到标「未取到」；与本仓代码注释冲突处**以 S1 为准**并在 §4.2 纠正表列出。
3. 单轨红线（S4 §1.1 五条）全程有效：本文档只做 API 对账，不引入第二聊天 UI、不建平行卡片体系、不删文本降级、不加新写入端点、不复制校验逻辑。

---

## 4.1 真实 API 面清单（9 组件 + 14 composable，全部实抓）

### 4.1.1 组件（9/9）

#### ① CopilotKitProvider（S1 L2788–3042）

`import { CopilotKitProvider } from "@copilotkit/vue/v2"`（S1 L2795；v2 子路径总口径 S1 L687–691）。前置约束：`runtimeUrl` / `publicApiKey` / `publicLicenseKey` / 本地 agents **至少给一**，否则 dev 警告、prod 抛错（S1 L2832）。

| Prop | 类型 | 要点 | S1 行号 |
|---|---|---|---|
| `runtimeUrl` | `string` | 自托管 runtime 端点；缺省且有 public key 时回落 Copilot Cloud | L2834–2836 |
| `headers` | `Record<string,string> \| (() => Record<string,string>)` | 函数式用于 token 等动态值 | L2838–2847 |
| `credentials` | `RequestCredentials` | `"include"` 开 HTTP-only cookie 鉴权 | L2849–2851 |
| `messageFilter` | `(messages: Message[], ctx: {agentId: string}) => Message[]` | 改写每轮发给 agent 的消息列表（UI transcript 不受影响；断工具对先修复） | L2853–2881 |
| `defaultThrottleMs` | `number` | useAgent 未指定 throttleMs 时的默认节流 | L2883–2885 |
| `publicApiKey` / `publicLicenseKey` / `licenseToken` | `string` | Copilot Cloud key / Intelligence license（离线校验 token） | L2887–2903 |
| `properties` | `Record<string, unknown>`（默认 `{}`） | 每请求附带；可含 `threadMetadata`、LangGraph `authorization` | L2905–2919 |
| `useSingleEndpoint` | `boolean` | true=单路由 / false=REST 多路由 / **省略=auto 探测** | L2921–2923 |
| `agents__unsafe_dev_only` / `selfManagedAgents` | `Record<string, AbstractAgent>` | 本地/自管 agent（后者键冲突时优先） | L2925–2931 |
| `renderToolCalls` | `VueToolCallRenderer<any>[]` | 声明式工具渲染器（本仓走 useRenderTool composable 路线，不用此 prop） | L2933–2935 |
| `renderActivityMessages` | `VueActivityMessageRenderer[]`（默认 `[]`） | MCP Apps 渲染器**始终自动并入**；A2UI / Open Generative UI 渲染器在特性激活时并入 | L2937–2939 |
| `renderCustomMessages` | `VueCustomMessageRenderer[]`（默认 `[]`） | 自定义消息类型渲染 | L2941–2943 |
| `frontendTools` | `VueFrontendTool[]`（默认 `[]`） | 静态前端工具；动态增删用 useFrontendTool | L2945–2947 |
| `humanInTheLoop` | `VueHumanInTheLoop[]`（默认 `[]`） | 静态 HITL 工具；动态用 useHumanInTheLoop | L2949–2951 |
| `openGenerativeUI` | `{ sandboxFunctions?: SandboxFunction[]; designSkill?: string }` | Open GenUI 配置；**sandboxFunctions 必须稳定数组** | L2953–2955 |
| `enableInspector` | `boolean` | dev 构建默认开（prod/SSR 永不加载）；模板 `:enable-inspector="false"` 关闭 | L2957–2961 |
| `showDevConsole` | `boolean \| "auto"` | **已弃用**，改用 enableInspector | L2963–2966 |
| `onError` | `(event: {error, code, context}) => void \| Promise<void>` | 结构化错误钩子 | L2968–2989 |
| `a2ui` | `{ theme?, catalog?, loadingComponent?, includeSchema? }` | A2UI 渲染器**在 runtime 报告已配置时自动激活**，本 prop 仅用于覆盖 | L2993–3007 |
| `debug` | `boolean \| { events?, lifecycle?, verbose? }` | 客户端事件管线日志 | L3009–3011 |
| slot `default` | slot（必需） | 包裹子树（Vue 用 slot 而非 children） | L3013–3027 |

#### ② CopilotChat（S1 L764–1014）

继承 CopilotChatView props，但**扣除内部自管**的 `messages/isRunning/suggestions/suggestionLoadingIndexes/attachments/onRemoveAttachment/onAddFile/dragOver/onDragOver/onDragLeave/onDrop`（S1 L791）。另有 `CopilotChat.View` 静态成员 = CopilotChatView 裸布局（S1 L777、L976–999）。

| Prop | 类型 | S1 行号 |
|---|---|---|
| `agentId` | `string`（回落 ChatConfigurationProvider → default） | L795–797 |
| `threadId` | `string`（缺省本地铸 UUID，首次 run 建线程） | L799–801 |
| `throttleMs` | `number` | L803–805 |
| `labels` | `Partial<CopilotChatLabels>` | L807–809 |
| `attachments` | `AttachmentsConfig` | L811–813 |
| `onError` | 同 Provider onError（按 agentId 过滤） | L815–838 |
| 继承：`autoScroll`（`AutoScrollMode \| boolean`，默认 true）、`welcomeScreen`（bool，true）、`inputValue`（string）、`inputMode`（`"input"\|"transcribe"\|"processing"`）、`inputToolsMenu`（`(ToolsMenuItem\|"-")[]`）、`onFinishTranscribeWithAudio` | | L842–864 |

- **Events**（L870–879）：`submit-message(value)` / `stop` / `input-change(value)` / `select-suggestion(suggestion, index)` / `add-file` / `start-transcribe` / `cancel-transcribe` / `finish-transcribe`。
- **Slots**（L885–911）：`chat-view`、`message-view`、`input`、`suggestion-view`、`welcome-screen`、`welcome-message`、`interrupt`（HITL 中断 UI，`{event, result, resolve}`）；未列 slot 透传至 CopilotChatMessageView（L913–915）。

#### ③ CopilotChatView（S1 L2410–2787）

受控纯展示布局（不持消息/输入态，S1 L2430–2435）。

- **Props**（L2465–2573）：`messages: Message[]`（默认 `[]`）、`autoScroll: AutoScrollMode|boolean`（true；`"pin-to-bottom"/"pin-to-send"/"none"`，L2471–2480）、`isRunning: boolean`（false）、`suggestions: Suggestion[]`、`suggestionLoadingIndexes: ReadonlyArray<number>`、`welcomeScreen: boolean`（true）、`attachments: Attachment[]`、`dragOver: boolean`、`inputValue: string`、`inputMode: CopilotChatInputMode`（`"input"`）、`inputToolsMenu`、`isConnecting: boolean`（false）、`hasExplicitThreadId: boolean`（false）、`onRemoveAttachment(id)`、`onAddFile()`、`onDragOver(e)`、`onDragLeave(e)`、`onDrop(e)`、`onFinishTranscribeWithAudio(audioBlob)`。
- **Events**（L2579–2612）：`submit-message` / `stop` / `input-change` / `select-suggestion` / `add-file` / `start-transcribe` / `cancel-transcribe` / `finish-transcribe`。
- **Slots**（L2620–2677）：`message-view`、`scroll-view`、`feather`、`scroll-to-bottom-button`、`interrupt`、`input`、`suggestion-view`、`welcome-screen`、`welcome-message`。

#### ④ CopilotChatInput（S1 L1322–1826）

- **Props**（L1379–1457）：`modelValue: string`（受控/非受控，L1379–1383）、`mode: "input"|"transcribe"|"processing"`（`"input"`）、`disabled: boolean`（false）、`placeholder: string`、`autoFocus: boolean`（true）、`clearOnSubmit: boolean`（true）、`isRunning: boolean`（false）、`toolsMenu: (ToolsMenuItem|"-")[]`（`[]`）、`maxRows: number`（5）、`positioning: "static"|"absolute"`（`"static"`）、`keyboardHeight: number`（0）、`showDisclaimer: boolean`、`bottomAnchored: boolean`（false）。
- **Events**（L1465–1515）：`update:modelValue` / `submit-message` / `stop` / `add-file` / `start-transcribe` / `cancel-transcribe` / `finish-transcribe` / `finish-transcribe-with-audio(audioBlob)`。**多个按钮仅在绑定对应事件监听时才渲染**（L1336–1345）。
- **Slots**（L1524–1560+）：`text-area` / `send-button` / `add-menu-button` / `start-transcribe-button` / `cancel-transcribe-button` / `finish-transcribe-button` / `audio-recorder` 等。

#### ⑤ CopilotChatMessageView（S1 L1827–2090）

- **Props**（L1868–1879）：`messages: Message[]`（`[]`；重复 id 去重、assistant 同 id 分片合并）、`isRunning: boolean`（false；末条后出打字光标）。非 prop 属性经 `$attrs` 落根 div（L1881–1884）。
- **Slots**（L1890–1960+）：`assistant-message`（默认 CopilotChatAssistantMessage）、`user-message`（默认 CopilotChatUserMessage）、`reasoning-message`、`cursor`、`interrupt`、`message-before` / `message-after`（带 run/位置元数据）等。

#### ⑥ CopilotChatAssistantMessage（S1 L1015–1321）

Markdown 由 `streamdown-vue` 渲染（GFM 表格 / KaTeX / Shiki 高亮，L1023）。

- **Props**（L1066–1086）：`message: AssistantMessage`（必需）、`messages: Message[]`（`[]`）、`isRunning: boolean`（false）、`toolbarVisible: boolean`（true；流式中末条自动隐藏，L1027–1031）。
- **Events**（L1094–1118）：`thumbs-up(message)` / `thumbs-down(message)` / `read-aloud(message)` / `regenerate(message)` —— **绑定监听才渲染对应按钮**；复制按钮内部处理、无 `copy` 事件。
- **Slots**（L1126–1140+）：`layout` / `message-renderer` / `tool-calls-view` / `toolbar` / 各按钮 slot。

#### ⑦ CopilotChatUserMessage（S1 L2091–2409）

- **Props**（L2129–2146）：`message: UserMessage`（必需）、`branchIndex: number`（0）、`numberOfBranches: number`（1）。
- **Events**（L2152–2182）：`edit-message({message})`（无监听不渲染编辑钮）、`switch-to-branch({message, branchIndex, numberOfBranches})`（需监听且 branches>1）。
- **Slots**（L2188–2230+）：`message-renderer` / `toolbar` / `toolbar-items` / `copy-button` / `edit-button` 等。

#### ⑧ CopilotPopup（S1 L3043–3273）— **"popup" 特性需要 license**（S1 L3055，未授权渲染内联警告 + console 警告）

- **Own props**（L3076–3090）：`defaultOpen: boolean`（true）、`width: number|string`、`height: number|string`、`clickOutsideToClose: boolean`（false）。
- **继承 CopilotChat props**（L3092–3130）：`agentId` / `threadId` / `labels` / `autoScroll` / `welcomeScreen` / `inputValue` / `inputMode` / `inputToolsMenu` / `onError`。
- **Slots**（L3136–3166）：`header` / `toggle-button` / `chat-view` / `message-view` / `input` / `suggestion-view` / `welcome-screen` / `welcome-message`。**Events**（L3172–3181）同 CopilotChat。

#### ⑨ CopilotSidebar（S1 L3274–3520）— **"sidebar" 特性需要 license**（S1 L3287，未授权渲染内联警告 + console 警告）

- **Own props**（L3307–3313）：`defaultOpen: boolean`（true）、`width: number|string`。
- **继承**（L3315–3365）：同 CopilotChat/View 全套（`agentId`/`threadId`/`throttleMs`/`labels`/`attachments`/`onError`/`autoScroll`/`welcomeScreen`/`inputValue`/`inputMode`/`inputToolsMenu`/`onFinishTranscribeWithAudio`）。
- **Events**（L3371–3380）同 CopilotChat；**Slots**（L3386–3416）：`header` / `toggle-button` / `chat-view` / `message-view` / `input` / `suggestion-view` / `welcome-screen` / `welcome-message`。

### 4.1.2 Composable（14/14）

> 签名均为 S1 官方汇编实抓；行号可复核。`MaybeRefOrGetter<T>` 表示可传普通值 / ref / getter（S1 L729 Vue 惯例）。

| # | Composable | 真实签名 / 参数 | 返回 | 证据（S1 行号） |
|---|---|---|---|---|
| ① | `useCopilotKit` | `useCopilotKit()`（无参） | `copilotkit: ShallowRef<CopilotKitCoreVue>`、`executingToolCallIds`、`a2uiTheme`、`a2uiCatalog`、`a2uiLoadingComponent`、`a2uiIncludeSchema`；`copilotkit.runTool({name, args}, opts)` | L5024；返回 L5035–5064；runTool L5161–5203 |
| ② | `useAgent` | `useAgent(props)`；props：`agentId: MaybeRefOrGetter<string>`、`threadId?: MaybeRefOrGetter<string\|undefined>`、`updates?: UseAgentUpdate[]`（状态订阅增量）、`throttleMs?: number` | `agent`（`running/loading/state/nodeName` 等属性 + `run/interrupt/stop` 方法） | L3544–3546；props L3553–3580；agent 属性方法 L3584–3683 |
| ③ | `useFrontendTool` | `useFrontendTool({name, description?, parameters: ZodSchema, handler?(args, {toolCall, agent, signal}), render?, available?, agentId?, followUp?, webmcp?})` | 渲染 props：`{name, toolCallId, args, status: ToolCallStatus 枚举(@copilotkit/core), result?}` | L5502–5506；tool 字段 L5510–5585；render props L5548–5559 |
| ④ | `useRenderTool` | named：`useRenderTool<S>({name, parameters: S, render, agentId?}, deps?: WatchSource<unknown>[])`；wildcard：`useRenderTool({name:"*", render})`（无 parameters） | 渲染 props 判别联合 `RenderToolProps<S>`：`{name, toolCallId, status: "inProgress"\|"executing"\|"complete", parameters: Partial<T>\|T, result?: string\|undefined}` | L6384–6410（签名）；L6440–6466（Render Props）；deps L6434–6436 |
| ⑤ | `useComponent` | `useComponent({name, description?, parameters: ZodSchema, render: VueComponent})`——组件即工具，schema 到达组件即 props，无 handler | built on `useFrontendTool`（默认指令转发） | L4261–4273；L4305–4310 |
| ⑥ | `useHumanInTheLoop` | `useHumanInTheLoop({name, description?, parameters: ZodSchema<T>, render})`——**无 handler，render 必需**（内部包装 useFrontendTool） | 渲染 props 按 status 分态：`{name, description, toolCallId, status}` 恒在；inProgress：`args: Partial<T>, respond: undefined`；executing：`args: T, respond: (result)=>Promise<void>`；complete：`args: T, result: string` | L5780–5783；分态 props L5809–5833 |
| ⑦ | `useInterrupt` | `useInterrupt({handler, enabled?, agentId?, renderInChat? 默认 true})` | `{interrupt, result, hasInterrupt, resolveInterrupt, slotProps}`；`on_interrupt` AG-UI 事件驱动 | L6179–6182；返回 L6212–6234；事件 L6315 |
| ⑧ | `useSuggestions` | `useSuggestions(options?: {agentId?: MaybeRefOrGetter<string\|undefined>})` | `{suggestions, isLoading, reloadSuggestions, clearSuggestions}` | L6635；L6655–6681 |
| ⑨ | `useConfigureSuggestions` | 动态源：`useConfigureSuggestions({instructions, minSuggestions?, maxSuggestions?, available?, providerAgentId?, consumerAgentId?})`；静态源：`useConfigureSuggestions({suggestions, available?, consumerAgentId?})` | void（配置型，向聊天注入建议源） | L4448–4452；动态/静态 L4456–4520 |
| ⑩ | `useCapabilities` | `useCapabilities({agentId?: string})`——**agentId 为普通 string，setup 时捕获一次**（非 MaybeRefOrGetter） | `capabilities`（按类别分组的 agent 能力面） | L4098；L4103–4105；类别 L4109–4157 |
| ⑪ | `useThreads` | `useThreads({agentId: string 必填, includeArchived?, limit?})` | `{threads, isLoading, error, hasMoreThreads, isFetchingMoreThreads, fetchMoreThreads, renameThread, archiveThread, deleteThread}`；Thread 形状 `{id, name?, ...}` | L6822；input L6827–6841；返回 L6845–6893；Thread L6850–6858 |
| ⑫ | `useAgentContext` | `useAgentContext({description, value: MaybeRefOrGetter<JsonSerializable>})` | void；**wire 上 value 恒为 JSON 字符串**（接收端注意 parse） | L3860；L3865–3883；wire 警告 L3995–4052 |
| ⑬ | `useCopilotChatConfiguration` | 无参 composable；读取祖先 `CopilotChatConfigurationProvider` 上下文 | `{labels, agentId, threadId, hasExplicitThreadId, isModalDefaultOpen}` 等；**CopilotKitProvider 不挂此 provider**，聊天组件自动挂 | L4777；provider props L4736–4764；注意 L4698；labels 表 L4823–4844 |
| ⑭ | `useDefaultRenderTool` | `useDefaultRenderTool({render?})`——`useRenderTool({name:"*"})` 薄包装，无参调用装内置折叠卡 | DefaultRenderProps：`{name, toolCallId, parameters: unknown, status: 字符串联合, result: string\|undefined}`；枚举→字符串映射，未知枚举回落 "inProgress" + 一次 console.warn | L5282–5290；props L5274–5280/L5313–5341；映射 L5460–5461 |

**附加（非 composable，但 §4.5 用到）**：`CopilotThreadsDrawer` props 表（S1 L635–645）：`agentId: string`（默认 default agent）/ `limit: number` / `label: string` / `recentLabel: string`（默认 `"Recent Conversations"`）/ `collapsible: boolean`（true）/ `onThreadSelect: (threadId: string) => void` / `onNewThread: () => void` / `licenseUrl: string`（locked 态跳转）/ `onLicensed: () => void`（locked 态动作回调）。与 `CopilotChatConfigurationProvider` 同挂（L610–612）；SSR 懒加载 custom element（L647–651）。

**计数结论**：9 组件 + 14 composable = **23/23 全部实抓到真实签名**，另有 CopilotThreadsDrawer props 一并实抓。无「未取到」签名项（个别内部实现细节标「推断」，见 §4.2）。

---

## 4.2 纠正表（本仓现状 vs 官方 API）

> 本仓现状取证：S2 `ai-assistant.vue`、S3 `copilotkit-render.ts`、S4 单轨契约、S5 node_modules 实测。R 编号供主计划引用。

| # | 类别 | 本仓现状（证据） | 官方 API 口径（证据） | 判定与处置 |
|---|---|---|---|---|
| **R1** | **两套渲染 props 不通用（硬偏差防线）** | `CardToolRenderProps{name, parameters: Record<string,unknown>, result?, status, toolCallId}` + `CardToolStatus = 'complete'\|'executing'\|'inProgress'` 字符串（S3 L41/L50–61）；渲染器只注册在 `useRenderTool` 系（S3 L194–206） | `useRenderTool` 收 `parameters` + status 字符串联合；`useFrontendTool` 收 `args` + `ToolCallStatus` 枚举；**"写给一个的渲染器在另一个里静默不画任何东西"**（S1 L425–437） | **本仓现状正确**（只用 useRenderTool 系）。硬防线：未来若引入 `useFrontendTool`（如画布定位工具），其 render **不得复用** CardToolRenderProps 渲染器，须包一层转换（S1 L434–436 官方建议 wrap 而非 reuse） |
| **R2** | **executing 期参数语义（注释级偏差）** | S3 L48 注释称「inProgress\|executing 时 parameters 为流中部分参数；complete 时全量」 | 官方：`parameters` 仅在 **inProgress** 时为 `Partial<T>`；**executing 时已是全量 `T`**；`result` 仅 complete 有（S1 L6464–6466；L365–368） | **注释与官方不符**，但本仓 `resolveCardToolRender` 在 `status !== 'complete'` 一律只出 pending 占位（S3 L118–147），比官方最低要求更严——因卡片校验依赖 complete 期 `result` 里的 `{version, sourceRefs}`，过检前不出卡是正确的。**处置：只修注释口径，不动逻辑** |
| **R3** | **RenderToolProps 类型不可从 /v2 导入** | 本仓自定义本地 `CardToolRenderProps` 等价承接（S3 L50–61），契约 S4 称「本地等价类型」 | node_modules 实测：`RenderToolProps<S>` 定义于 `dist/v2/hooks/use-render-tool.d.ts:24`，但 `hooks/index.d.ts` 桶**只导出值 `useRenderTool`、不导出类型**（S5）；exports map 仅 `.`/`./v2`/`./styles.css`/`./package.json`，无深层导入通配 | **本仓做法正确且是唯一可行路径**（官方类型面未导出该 type）。可在本地类型注释补 S5 实测依据，防止后人误从 `/v2` 深挖导入 |
| **R4** | **/v2 子路径导入** | `import { CopilotKitProvider } from '@copilotkit/vue/v2'`（S2 L46） | v2 API 全部在 `/v2` 子路径（S1 L243、L687–691）；注意 Threads 指南示例用根路径 `@copilotkit/vue`（S1 L598/L620/L660）属**汇编内部不一致** | **本仓正确**。继续统一 `/v2`；勿被 Threads 段根路径示例带偏（该段示例疑为旧版残留，「推断」） |
| **R5** | **Provider 未配 `:a2ui` / `:open-generative-ui`** | Provider 仅 `:enable-inspector="false" :headers="copilotKitAuthHeaders" runtime-url="/api/copilotkit"`（S2 L635–640） | `:a2ui` 仅**覆盖**用途——A2UI 由 runtime 报告配置后内置渲染器**自动激活**，前端无需传（S1 L460–466）；`catalog` 传入可从客户端侧开启（L474–479）；Open GenUI **必须**传 `:open-generative-ui`（含稳定 `sandboxFunctions` 数组）才激活（S1 L502–540） | **非偏差，是澄清**：①不配 `:a2ui` ≠ 禁用 A2UI——真开关在 Java 桥 runtime 侧；本仓 Java 桥 GET /info 无 a2ui 字段（S4 L56–71），故 A2UI 实际未激活，符合单轨契约锁定。②`:open-generative-ui` 未传 = OGUI 不激活，现状正确。**启用任一路径须 ADR（见 §5）** |
| **R6** | **useRenderTool 未传 deps** | 循环注册 `useRenderTool({name: entry.type, parameters, render})` 闭包捕获 `onConfirm` 等，无第二参（S3 L194–206） | deps 为 Vue `WatchSource[]`（ref/getter，**不是 React 依赖数组**）；闭包捕获响应式状态须列入，否则注册渲染器持旧值（S1 L370–378、L6434–6436） | **当前可接受（推断）**：`onConfirm` 经 props 传入后在 setup 期固定，卡片组件生命周期内不变。若未来 onConfirm 或注册表变响应式，须补 deps。列入遗留观察项 |
| **R7** | **Popup/Sidebar license** | 本仓未用 CopilotPopup/CopilotSidebar（只用 Provider + renderless host，S2） | `CopilotPopup` "popup" 特性需 license（S1 L3055）、`CopilotSidebar` "sidebar" 需 license（S1 L3287），未授权渲染内联警告 | **现状无影响**。若产品要内置浮动助手/侧栏壳，须先评估 license（本仓自部署，见 §4.5 结论同样适用于此） |
| **R8** | **headers 函数求值时机** | `copilotKitAuthHeaders` 为函数，无 active pinia 时返回 `{}`（S3 L212–219） | 官方 Provider props 表仅声明 `headers: Record<string,string> \| (() => Record<string,string>)`（S1 L2830–3011 区间），**求值时机（setup 一次 vs 每请求）官方未承诺** | 标「**推断**」：token 过期刷新场景下若为 setup 一次求值会持旧 token。验证方式：Inspector 观察请求头（§6 探针）。列为未证明项 U4 |

---

## 4.3 渲染器守则：guard-on-status 的正确写法

> 官方机制（S1 L365–368）：渲染器随调用进度运行**三次**，`status` 走 `"inProgress"` → `"executing"` → `"complete"`；**`parameters` 在参数流式传输期间是 `Partial<T>`**，读取任何必填字段前必须先守 `status`。

### 守则 G1 — status !== 'complete' 只出占位（官方示例口径）

官方示例（S1 L329）：

```vue
<template>
  <p v-if="status !== 'complete'">Triaging {{ parameters.incidentId }}…</p>
  <article v-else class="triage-card">…</article>
</template>
```

要点：
1. **占位分支里可以读可选字段做文案**（如 `parameters.incidentId` 显示"正在处理 INC-…"），但必须容忍 `undefined`；
2. **完整卡片（含必填字段渲染、图表、表单）只允许出现在 `status === 'complete'` 分支**；
3. **不得**在 `status !== 'complete'` 分支读流式 Partial 参数的必填字段——此刻它们可能尚未到达，渲染出 `undefined`/空白即为「半成品卡」。

### 守则 G2 — 本仓卡片守卫应比官方更严（现状已正确）

本仓 `resolveCardToolRender`（S3 L118–147）采用 **`status !== 'complete'` 一律 pending 占位、不出卡**的策略：

- 官方允许 executing 期（参数已全量 `T`，S1 L6464–6466）就画出卡片骨架；
- 但本仓卡片的合法性判定依赖 complete 期 `result` 中的 `{version, sourceRefs}`（单轨契约 S4 §5），过检前出卡会造成「未对账先出卡」；
- **结论：维持现状的更严格守卫是正确设计，不得为"体验更好"提前在 executing 期出卡**。

```ts
// 本仓守卫骨架（现状等价，勿放松）：
if (status !== 'complete') return pendingVNode;      // G1/G2：只出占位
const parsed = parseCardToolResult(result);           // complete 才解析 {version, sourceRefs}
if (!getCardType(type, parsed.version)) return defaultFallback; // (type,version) 过检
return cardVNode(parameters);                         // 此时 parameters === T 全量
```

### 守则 G3 — result 仅 complete 有

`result?: string\|undefined` **仅在 complete 态存在**（S1 L6440–6466 判别联合）。任何对 `result` 的读取（含 `parseCardToolResult`）必须放在 complete 守卫之后——现状已满足（G2 骨架第 2 行）。

### 守则 G4 — deps 口径（呼应 R6）

渲染器闭包捕获响应式值（ref/getter）时列入第二参 `deps: WatchSource[]`；**不是** React 式依赖数组（S1 L370–378）。当前 onConfirm 固定捕获可不传（R6 推断），动态化时必须补。

---

## 4.4 注册名与 tool 名精确匹配：静默失败机制

> 官方"渲染验证两检查"（S1 L549–558）：**"A surface that silently falls back to text is the common failure."**——表面静默降级为文本是最常见故障。

### 两检查原文口径

1. **检查一（状态迁移）**：开 Inspector 确认 tool call 出现且 `status` 完成 `inProgress → executing → complete` 迁移。**未达 `complete` = 服务端工具失败，不是渲染器写错**（S1 L553–555）。
2. **检查二（名字精确匹配）**：确认**注册名与 tool 名逐字精确匹配**。**注册在错误名字下的渲染器不是报错**——调用直接落到默认渲染器，或在未注册默认渲染器时落到纯文本（S1 L556–558）。

### 静默失败机制（本仓风险点）

| 环节 | 机制 | 本仓映射 |
|---|---|---|
| 注册名来源 | `useRenderTool({name: entry.type, ...})` 循环 `card-registry` 的 `entry.type`（S3 L194–206） | `demand.draft` / `gate.conclusion` / `gate.precheck` / `project.charter`（card-registry.ts 4 键） |
| tool 名来源 | AG-UI `TOOL_CALL_START.toolCallName`，由 Java 桥从 `done+card` 帧映射（S4 §4）：`toolCallName = card.type` | 单轨契约锁定 `card.type = useRenderTool 注册 name = 注册表键`，**三处同名是硬约束** |
| 失败表现 | 名字不匹配时**零报错**、零 console 警告，卡片渲染为默认卡或文本 | 传统 UI 的文本降级会"正常工作"，掩盖 AI UI 卡片丢失（红线 R3 文本降级不删，故文本永远在场，更易掩盖） |
| 追加注意 | 渲染器去重键为 `agentId:name`，同名**后者胜**；cleanup **故意不移除**渲染器（保历史消息可渲染）（S1 L6472–6473） | 多会话/多次 setup 同名注册时以最后一次为准；历史 run 卡片依赖"不移除"特性 |

**工程守则**：
- 任何新增卡片类型 = `card-registry.ts` 加键 + Java 桥 `card.type` 透传 + 前端注册名，**三处字符串逐字一致**，建议以注册表键为单一事实源做常量引用（推断：Java 桥侧为字符串透传，无法共享 TS 常量，须契约文档锁定）；
- 排障顺序固定为"两检查"：先 Inspector 看状态迁移，再核对名字——**不要先怀疑渲染器组件代码**；
- 测试探针（S4 §6 三探针）应含一条"故意错名注册"的反向探针，验证降级确实走到文本/默认卡（推断，建议）。

---

## 4.5 Threads 能力与自部署后端可用性结论

### 能力面（实抓）

| 项 | 内容 | 证据（S1 行号） |
|---|---|---|
| `CopilotThreadsDrawer` props | `agentId` / `limit` / `label` / `recentLabel`（默认 `"Recent Conversations"`）/ `collapsible`（true）/ `onThreadSelect` / `onNewThread` / `licenseUrl` / `onLicensed` | L635–645 |
| 挂载要求 | drawer 与 chat **同挂 `CopilotChatConfigurationProvider`** 下，选择/新建才会联动聊天 | L610–612 |
| `useThreads` | headless 同源数据：`useThreads({agentId 必填, includeArchived?, limit?})` → `{threads, isLoading, error, hasMoreThreads, isFetchingMoreThreads, fetchMoreThreads, renameThread, archiveThread, deleteThread}` | L6822、L6827–6893 |
| 实现形态 | drawer 包 custom element（`@copilotkit/web-components/threads-drawer`），SSR 懒加载、客户端渲染 | L647–651；S5 node_modules 传递依赖 `@copilotkit/web-components@1.74.0` 实证 |
| **持久化来源** | **Threads 由 CopilotKit Intelligence（云托管平台）持久化** | L587–589 |
| **license 限制** | drawer 读平台 `threads` license 特性，**不可用时渲染 locked 态**（而非线程列表）；`useThreads` "needs a CopilotKit Intelligence project" | L587–589、L6799–6805 |
| 云端依赖 | `CPK_INTELLIGENCE_API_KEY` 环境变量指向 Intelligence 项目 | L6979；S5 node_modules 含 `@copilotkit/license-verifier@0.5.0` |

### 结论（一句话）

**本仓自部署 Java AG-UI 桥后端不可用 Threads 完整功能——线程持久化与实时同步由 CopilotKit Intelligence 云托管独家提供，无 license/无 Intelligence 后端时 `CopilotThreadsDrawer` 只会渲染 locked 态、`useThreads` 无数据源，本期不引入，多会话需求继续走本仓既有会话管理（传统 UI 侧）。**

### 依据

1. **数据面缺失**：Threads 持久化在 CopilotKit Intelligence 云上（L587–589、L6799–6805）；本仓 runtimeUrl 指向自部署 Java 桥（S2 L635–640），GET /info 无 `licenseStatus`/Intelligence 字段（S4 L56–71）；
2. **license 面缺失**：drawer 读平台 `threads` license 特性，取不到即 locked 态（L587–589）；`license-verifier@0.5.0` 需要可验证的 license token（S5 传递依赖实证），自部署场景无签发方；
3. **产品面不符**：locked 态的 `licenseUrl`/`onLicensed` 面向"引导开发者购买/开通 Intelligence"（L644–645），与内网私有化交付冲突。

### 可用性分级（工程口径）

| 层 | 自部署可用？ | 说明 |
|---|---|---|
| `threadId` 参数透传（会话路由） | ✅ 可用 | `CopilotChat :threadId` 只是把线程 id 发给 runtime（L591–606），Java 桥可自行持久化——但这属于本仓自有会话管理，**不是** CopilotKit Threads API |
| `useThreads` / `CopilotThreadsDrawer` 列表/切换/归档/删除 | ❌ 不可用 | 数据源与 license 均在 Intelligence 云端（上文依据 1/2） |
| 若未来采购 Intelligence 或自研等价持久化 | 🔶 观察 | 自研等价需要在 Java 桥实现 threads CRUD 端点并接 `useThreads` 数据面——但 `useThreads` 官方实现直连 Intelligence（推断，源码级验证未做），大概率不可对接自研端点。列为未证明项 U5 |

