# ZK-IPD 规格 ↔ 实际代码 全量一致性 diff 报告（2026-09-29）

> **来源会话**：本文件由两次独立会话串成的最终清单落档，全程只读。上会话（transcript 已归档 b755c142）交付 **约 130 条**规格-代码不一致（49 页 + 横切规则）；本会话补做 **AI 工作界面原型审计（+22 条）** 与 **14 项未验证项核实（新增 4 条红色不一致）**。合计 **≈154 条**。
>
> **权威事实源**：`/Users/mac/Documents/ZK-IPD/开发说明/spec/batch-01..04`（49 页页级规格）+ `开发说明/开发说明书.md`（G-01..G-12 产品红线）+ `_公共规范.md` + `_导航地图.md` + `附录-D6-跨页状态机联动.md`；`_ARCHIVED_v1_*` 已冻结不纳入。
> **代码事实源**：后端 `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/**`（74 Controller + Service 层）；前端 `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/{views/ipd,api/ipd}/**`。
> **不涵盖**：`产品流程细化管理工具 2/`（仅口径分裂处引用）；上一轮已归档的 transcript（现态仅主题簇可复核，逐条 file:line 请以本轮补齐的 AI 原型与 14 项核实为准）。
>
> **方法**：Agency-harness 5 路并行子智能体（上轮 batch-01..04 + 横切）+ 主会话独立抽查复核（三证律）+ 本轮 AI 原型 1 路 + 14 项核实内联。所有条目均绑定规格出处 + 代码出处（路径:行号）双证据；无法核实者标 ⚠️ 未验证，禁止推测。

## 1) 六大主题分布（上轮 130 条）

| 主题 | 条目簇 | 关键命中（示例） |
|---|---|---|
| **A. 文档双源漂移** | A1..A6 | `_导航地图.md` / `batch-01` / `开发说明书.md` 在 `ZK-IPD/开发说明/` 与 `ruoyi-ai/docs/开发说明/` 双向漂移；G-12 只在镜像存在；`stage-advance` ↔ `advance-stage` 双向错向；`/api/v1` 前缀在两份文档相反 |
| **B. 涉钱口径 & 参数治理（G-05 / G-08）** | B1..B8 | 奖金池基数口径分裂（`BonusPoolService.java:36-56` 用「上市后连续 N 月实际回款净额×5%×S/A/B×6 档」，规格写「目标销售额×5%」）；`GuestDemandService.java` 硬编码 `OVERDUE_UNASSIGNED_WORKDAYS=5`、`TRACE_WITHDRAW_HOURS=24`（违反 G-05 参数零硬编码）；`HrSyncService.java:39` `DEFAULT_HANDOVER_DEADLINE_DAYS=15` 同类问题 |
| **C. 契约层错位（错误码 / 审计枚举 / 状态枚举）** | C1..C7 | `ApiV1ErrorCode.java`：`ROLE_LOCKED(40004)` / `DELETE_NOT_ALLOWED_DIRECT(40005)` / `HANDOVER_REQUIRED_BEFORE_DISABLE(40006)` / `STATE_CONFLICT(50002)`；规格 D.0.3 里 40004=评级快照、40006=产品项目 1:1、40910=BLOCKED_BY_OPEN_CHANGE——**码段号完全错位**；无 40007..40010；无 40910；审计枚举与状态枚举另有 5 处命名漂移 |
| **D. 状态机 & 守卫缺失** | D1..D6 | `RequirementStateMachine.transition()` 是死代码；`DemandController.java:98-121` triage 裸改状态未接 `StateMachineGuard`；共担 KPI 截止日 `KpiSharedCollectionService.java:280-296` 循环 off-by-one（`remaining>0` 时 `date` 已 plus 1 天，返回第 5 工作日的次日 18:00）；`BidInvitationService` 已装守卫（本轮核实）但 `ProjectService.java:387-395` 阻断抛 50002 时**不列被阻断动作编号**（规格要求列出） |
| **E. 红线失守** | E1..E10 | Gate 互不可见失守（`GateElementResultService.java:87-116` 单行共享判定）；`ProductGroupController.java:56-76` 组织写接口未按红线禁用；`NegativeFeedbackController.java:121-125` `@DeleteMapping("/{id}")` 提供删除（违反「负反馈不提供删除」红线）；权限可见性三连错位；登录安全语义偏差（首登强制改密与 FROZEN_PENDING_HANDOVER 只移交权限的实现细节） |
| **F. 整页整链路缺失** | F1..F12 | 页 37（招募转立项）、页 41（招标收口）、产品→认证联动、游客附件、和恒规则、页 33 KPI 归集第 5 工作日 18:00 截止调度、页 26 实施闭环端点命名、页 42 AI 文档 decision 三态、附录 D6.5 与 §8.3 系数表矛盾等 |

**规格自身缺陷**（不属于代码-规格不一致，是规格内部问题）：G2 否决计数错（写 4 项但源文档列 5 项）、"拒绝不留痕"自相矛盾、D6.5 与 §8.3 系数表矛盾。

## 2) 对齐良好项（正向清单，不改）

- 审计 hash 链只追加（G-03）通过；
- `/api/v1` `code=0/message` 包络对齐（`ApiV1Response`）；
- ID 字符串透传（`IpdIdorGuard`）；
- 删除两级审批链（`DeletionRequestServiceImpl` + `DeleteAuditService` 同事务软删+审计）；
- 69 动作目录（`ActionCatalog`）逐项吻合；
- 33 要素 / 14 否决种子（`IpdGateElementSeedInitializer.java:38-77`）；
- `advance-stage` 勘误正确（`ProjectController.java:127`）；
- 页 42 AI 文档三态字段级（本轮 #3 已复核 ✅ 一致，见 §4）；
- 页 38 在研产品选择源 ACTIVE 过滤（本轮 #5 已复核 ✅ 一致，`GuestDemandService.java:216-218`）；
- 招标 deadline today+7 前端默认（本轮 #7 ✅，`bid/create/index.vue:282-286`）；
- `ProductService.batchImportOnSale` 幂等 upsert（本轮 #8 ✅，`ProductService.java:125-127`）；
- 招标状态机不可逆守卫（本轮 #10 ✅，`BidInvitationService.java:525-543`）；
- 在研项目 14 天倒计时+超期升级（本轮 #11 ✅，`ProjectService.java:105-107, 676-682`）；
- `allowance.L1..L5` 津贴档位映射（本轮 #12 ✅，`ProjectMemberServiceImpl.java:122-126`）。

## 3) AI 工作界面原型审计（本轮 +22 条）

**范围**：`UI原型-AI工作界面-20260928/ipd-ai-workbench-prototype.html`（952 行三变体）+ `ipd-product-space-prototype-v2.html`（1235 行）⇄ 前端 `views/ipd/{ai-docs, _shared/ai-agent, workbench, product-lines/workspace}` + `api/ipd/{ai-copilot, ai-document, ai-suggest, ai-model-config, project-agent, guide-script, bid-ai-*}.ts` ⇄ 后端 `AiCopilotController` / `AiDocumentController` / `AiSuggestionController` / `AiModelConfigController` / `ProjectAgentController` / `AiAgentTaskController` / `BidAiCompareController` / `BidAiDraftController`。

| # | 原型区块/能力 | 类型 | 原型出处 | 实际实现出处 | 差异说明与影响 | 风险 |
|---|---|---|---|---|---|---|
| 1 | 模式切换「传统/AI」语义反转 | 语义不同 | v2:514-515, 11-14, 138, 614 | `workspace-mode.ts:2-3`；`ai-assistant.vue:916-937, 685-687, 967-973` | 原型 AI 模式=CopilotKit 副驾；实现 ai 模式=项目智能体且发送直接 `return`（无独立 agentId 不能用副驾 SSE 冒充）。**用户按原型预期切到 AI 模式即失去对话能力** | 高 |
| 2 | 项目智能体独立链路 UI 挂载 | 缺失 | v2:616-666 引导 Runs 时间线；工作台:413-421 Runs | 后端 `ProjectAgentController` 已建；前端 `project-agent-panel.vue` 组件已建但 **grep 全仓仅测试文件引用**，无生产页面挂载 | `/agent-capabilities\|agent-runs` 独立链路对用户完全不可见；`ai-assistant.vue:958-965` runs 列是占位文案 | 高 |
| 3 | done 帧 `card` 超集契约 | 语义不同 | 工作台:523-547 生成式卡片；v2:703-716 | 前端 `api/ipd/ai-copilot.ts:69-79` 注释承诺 card；后端 `AiCopilotController.java:153-158` done 帧无 card 键；仅 `SubStageGuideTool.java:57-58` 经 AG-UI 翻译链下发 | SSE 直连链路永远收不到 card；卡片能力双轨且 type 游离注册表外 | 高 |
| 4 | HITL「批准后执行写入」 | 缺失+语义不同 | 工作台:548-564 renderAndWaitForResponse；v2:790-807 | grep `useCopilotAction`/`renderAndWaitForResponse` 0 命中；`ai-assistant.vue:853-855` 只暂存 `cardConfirmPayload`（C08 零直写） | 原型「批准→AI 执行 POST」；实现「零直写→真人去既有页面提交」，语义相反（后者符合 C08 红线） | 高 |
| 5 | 结构化落库卡（双表原子写入） | 缺失 | v2:738-773 `useRenderTool("structured_db_write")` INSERT ai_documents + UPDATE gate_element_results 单事务 | `ai-document.ts` 与 `AiDocumentController` 均无「文档+要素结果」双表事务端点；grep `structured_db_write` 0 命中 | AI 产物一次事务落两表并出回执的核心能力缺失，Gate 要素结果无法由 AI 侧结构化写入 | 高 |
| 6 | CopilotTextarea 辅助写作 | 缺失 | 工作台:855-866；v2:365, 811-825 | grep `CopilotTextarea` 0 命中；实际是 `ai-composer.vue` | 续写/润色/压缩能力无实现 | 高 |
| 7 | useAgentState 双向共享状态 | 缺失 | 工作台:20, 587, 699-732；v2:775-779 | grep `useAgentState` 0 命中；`AgUiFrameTranslator.java:18, 98-102` 只单向投影 fillPayload | Agent↔前端共享状态面板缺失 | 高 |
| 8 | structuredInput（Agent 主动请求输入） | 缺失 | 工作台:587, 659-675 | grep `structuredInput` 0 命中 | 整链路缺失 | 高 |
| 9 | 多 Agent 团队切换 / Agent Team | 缺失 | 工作台:604-608, 745, 794-833 | `CopilotKitRuntimeController.java:57` `AGENT_ID="ipd_copilot"` 固定单 agent；`:123` 未知 agentId→404 | 后端仅单 agent，原型变体 B/C 的团队能力全部落空 | 高 |
| 10 | thinking / tool_use 内容块 | 缺失 | 工作台:587, 621-635 | `ai-assistant.vue:1034-1088` 只渲染文本+sources | 推理过程与工具调用不可见 | 高 |
| 11 | AI 画布四 Tab（Vue Flow/TinyMCE/iframe/ECharts） | 缺失 | v2:828-1004 | `ai-workspace.vue:62-92` 全为占位空态 | 四载体全空态 | 高 |
| 12 | 卡片注册表口径（type 集合） | 语义不同 | v2:844, 703-716 声明 4 类（`ipd_plan`/`structured_db_write`/`ipd_artifact`/文档·图表） | `ai-cards/card-registry.ts`+`types.ts:122-126` 注册 `demand.draft`/`gate.conclusion`/`gate.precheck`/`project.charter`；另有 `SubStageGuideTool` 的 `sub-stage.guide`（**第 5 种 type 游离注册表外**） | 两边都叫 4 卡但集合完全不同；schema 治理双轨缝隙 | 高 |
| 13 | 产品创建向导（4 步+落库预览） | 缺失 | v2:1009-1147（产品定义/市场与竞品/初始 KPI/铺开六阶段；单事务批量提交） | `product/edit/index.vue:259` 普通表单，无向导、无 KPI/竞品、无六阶段铺开、无落库预览 | 差距最大的单点；六阶段自动铺开（含 34 项 Gate 要素）无实现 | 高 |
| 14 | 产品空间页面形态 | 语义不同 | v2:491-516, 519-607 六阶段节点轨道+Gate-2 要素表 E-01~E-08 含「AI 起草」按钮 | `product/workspace/index.vue`（537 行）实为「需求主题/IPD 项目迭代/原始客户反馈」三区块；无阶段轨道、无模式切换、无 Gate 要素表 | 同名页面 IA 完全不同；「AI 起草」逐要素入口缺失 | 中高 |
| 15 | Run 反馈（👍/👎 归因） | 反向缺口 | v2:616-666 无显式反馈控件 | `project-agent.ts:148-149` `AiFeedbackTargetType='ARTIFACT_VERSION'|'RUN_MESSAGE'`；`AiFeedbackService.java:106`；`ProjectAgentController.java:123-129` | 实现的反馈链路在白名单前后端对齐（✅）但随 #2 未挂载 = 端点建好无人调 | 高 |
| 16 | Run 观测塔 + 项目级 AI 统计 | 语义不同 | 工作台:745, 869-909 AG-UI 事件流可视化 + 项目级统计 | `AiAgentTaskController.java` 只读两端点；`AiAgentTaskView.java:14`「prompt/fillPayload 一律不出 VO」；两套事件词汇表并存无映射 | 无观测塔 UI；「写入均经授权」等合规统计无聚合端点 | 中高 |
| 17 | 本轮消耗（token/延迟）面板 | 缺失 | 工作台:700-732 | `AiCopilotController.java:154-156` done 帧含 tokenPrompt/tokenCompletion/latencyMs；`ai-assistant.vue:1034-1088` 未消费展示 | 后端已具备的数据未在 UI 呈现 | 中 |
| 18 | CopilotChat suggestions 建议条 | 语义不同 | v2:12, 691-698 | `CopilotKitRuntimeController.java:110` `suggestions: false` 显式关闭；替代品为 `GuideSuggestionBar` | 同叫"建议条"但机制不同：CopilotKit 内建 vs 小阶段引导脚本派生 | 中高 |
| 19 | 招标 AI 对比 / AI 草稿 / 完整性检查 | 超范围 | 两原型无招标 AI 区块 | `api/ipd/bid-ai-compare.ts`、`bid-ai-suite.ts`、`BidAiCompareController`、`BidAiDraftController`、`BidResponseCheckService.java:77, 200` | 实现已超出原型范围的整块 AI 能力，验收无 UI 基准可依 | 中高 |
| 20 | AiSuggest 智能建议（21 场景） | 超范围 | 原型无独立「智能建议」面板 | `api/ipd/ai-suggest.ts:28-49` 21 场景白名单；`workbench/index.vue:495-496` `workbench.next-step` / `workbench.risk-warning` | 与 CopilotKit suggestions、guide-suggestion-bar 构成第三套"建议"概念，术语需收口 | 中高 |
| 21 | AI 模型配置入口 | 超范围 | 两原型无模型配置 UI（说明书 L630 / BR-AI-01 有红线） | `api/ipd/ai-model-config.ts`（/ai-models CRUD+enable+test）+ `AiModelConfigController` | 代码覆盖了说明书红线但原型未画入口（超原型范围，方向正确但基线缺失） | 中高 |
| 22 | CopilotKit runtime 接入参数 | 语义不同 | v2:673 徽章 `runtimeUrl=/copilotkit` | `ai-assistant.vue:861-865` `runtime-url="/api/copilotkit"`；`vite.config.mts:100-103` 代理剥离前缀；后端 `agentId=ipd_copilot` ✅ | 路径经代理归一后实际等价，文档照抄会联调 404；`:112 openGenerativeUIEnabled:false` 与原型 Generative UI 依赖声明相悖 | 中（路径差）/低 |

**AI 链路重点复核（任务重点项）**：项目智能体（`/api/v1/projects/{id}/agent-capabilities|agent-runs`）与 AI 副驾（`streamCopilot`）两条链路在代码中区分清晰（`project-agent.ts:17` 明文「与 ai-copilot.ts 是两条独立链路」，`ai-assistant.vue:685-687` 明确拒绝"用副驾 SSE 冒充智能体"），但**用户侧无入口触达智能体链路**（#2），且**「AI 模式」语义反转**（#1）。双开关 `ipd.project-agent.enabled`（`ProjectAgentConstants.java:16`）与 `chat.kernel.agentscope.enabled`（`AgentScopeChatKernel.java:72`, `matchIfMissing=false`）独立。

**对齐良好项**（正向）：「让 AI 预检 G2 就绪度」（工作台:483）→ `gate.precheck` 卡 + `GatePrecheckService` 存在；AI 文档助手「生成→登记 v1 待审核→人工审核/拒绝→归档→版本对比/字段 diff」全链与 BR-AI-02/03 完全对齐（`ai-docs/index.vue:6-12, 296-362`；`ai-document.ts:198-341`）。

## 4) 14 项未验证项核实结果（本轮内联）

| # | 待核实项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 删除申请撤回后被删项目恢复 ACTIVE | ❌ **代码无此逻辑** | `DeletionRequestServiceImpl.java:185-217` `withdraw()` 仅置 `request.setStatus(ST_WITHDRAWN)`，从不触碰目标实体的 status 字段；grep 全库 `restoreStatus.*ACTIVE` 0 命中 |
| 2 | DRAFT 撤回保留 30 天到期清理 | ❌ **无调度实现** | grep `purgeArchive\|cleanupDraf\|DRAFT.*30.*天` 全库 0 命中 |
| 3 | 页 42 AI 文档 `decision` 三态字段级比对 | ✅ **一致** | `AiDocumentController.java:94-99` review / `:165-174` reject；`RejectReq(@NotBlank @Size(max=1000) comment)`；BR-AI-02/03 完全对齐 |
| 4 | `is_veto` 归一化 SQL 是否已 apply 真库 | ⚠️ **SQL 已 commit 未 apply** | 脚本 `docs/script/sql/update/2026-09-22-ipd-gate-element-value-domain-normalize.sql`（P47 续轮 commit `425393b8`）；`GateElementService.java:44-51` 读取侧已兜底；DBA 窗口待 owner 拍板 |
| 5 | 页 38「在研产品」选择源与 status 字段 | ✅ **一致** | `GuestDemandService.java:216-218` `publicProducts()` 仅 `eq(status,"ACTIVE")`；`PublicPortalController.java:48-50` 调用 |
| 6 | Gate 发布时 `vetoDualRequired` 双签必填强校验 | ❌ **仅反向一致性校验** | `GateElementService.java:529-531` 只校验「`vetoDualRequired='1'` ⇒ `isVeto='1'`」；正向「`isVeto='1'` ⇒ 必填 `vetoDualRequired`」无守卫 |
| 7 | 发起招标 deadline 默认 today+7 | ✅ **已对齐** | `apps/web-antd/src/views/ipd/bid/create/index.vue:282-286` `defaultExpireAt()`；`:352` onMounted 默认 |
| 8 | `ProductService.batchImportOnSale` 幂等 upsert | ✅ **一致** | `ProductService.java:125-127` javadoc「按 modelCode 幂等（已存在则更新名称）」+ R8-P0-7 预取优化 |
| 9 | BR-DEL-02 附件软删（页 14 之外） | ⚠️ **附件走 oss 硬删** | `DeliverableService.java:202-204` `ossService.deleteWithValidByIds`；grep `attachment.*soft.*delete` 全库 0 命中；`SoftDeleteExecutor` 覆盖 Requirement/Product/Project/CertTemplate/Person/Gate 六类，**不含 Attachment** |
| 10 | 招标状态机不可逆约束服务端守卫 | ✅ **已装** | `BidInvitationService.java:26-28` 状态机注释；`:525-543` `StateMachineGuard` 装配（null=装配缺失→fail-closed）；`:466` 仅 OPEN 可改 |
| 11 | 在研项目 14 天场景复核倒计时+超期升级 | ✅ **已实现** | `ProjectService.java:105, 107, 517-526, 676-682`；`LEGACY_SCENARIO_DAYS=14` + `LEGACY_SCENARIO_CRITICAL_DAYS=3` + 通知 MARKET_PM/GROUP_LEADER |
| 12 | `allowance.L1..L5` 津贴档位映射落库位置 | ✅ **落 system_configs** | `ProjectMemberServiceImpl.java:122-126` `systemConfigService.getIntValue("allowance." + level, -1)`；`AllowanceLedger.java:51-54` baseAmount 字段；测试锚 `P241AcceptanceTest.java:105` allowance.L2=1500 |
| 13 | 60 天无产出停发的活动数据源腿 | ⚠️ **仍 PARTIAL** | `AllowanceMonthlyLedgerScheduler.java:18` javadoc 自述「NO_OUTPUT_60_DAYS 腿缺活动数据源本波未接，登记 PARTIAL」 |
| 14 | 共担 KPI「第 5 天自动关闭」是否存在 | ❌ **仍不存在** | `KpiSharedDeadlineScheduler.java:13-19` javadoc 只覆盖「逾期第 1 天提醒 / 第 3 天升级」；grep close/自动关闭/fifthWorkday 全库 0 命中——与规格「次月第 5 工作日 18:00 截止」尚存 2 天缺口 |

**统计**：✅ 6（#3 #5 #7 #8 #10 #11 #12 共 7 项一致，含 #5 交叉验证） / ❌ 4（#1 #2 #6 #14）/ ⚠️ 3（#4 #9 #13）。

## 5) 建议登记看板 P1 卡（4 张，本轮新发现）

> **登记结果追补（2026-09-29 22:1x）**：4 卡已登看板镜像 marker `zk-diff-p1-register-20260929`；P1-02 已代码实施 commit `3290b166`（GateElement*Test 58/58 绿，真库 HTTP 验收待补，卡面 ◐）；P1-04 卡面已按宿主选型调研回填建议（首选 ai-assistant AI 模式 IpdAiWorkspace 区）。

> **登记方式**：按 AGENTS.md §「并发写单一写入者（OPS-09）」+ 「写操作用 `manage.py set` 同步源和看板」——本报告不直接编辑 `开发计划-看板镜像.md`，仅提供卡片草案供 owner 或主协调会话走 `manage.py set` 或 Vibe Kanban UI 正式登记。

| # | 建议编号 | 卡片标题 | 优先级 | 验收要点 | allowedPaths | 验证命令 | 依据 |
|---|---|---|---|---|---|---|---|
| 1 | ZK-DIFF-P1-01 | 删除撤回后目标实体复活 ACTIVE + DRAFT 30 天清理调度 | U1 | `withdraw()` 事务内：`deletion_request` 置 WITHDRAWN 后**同步 UPDATE 目标实体 status 从 DELETION_PENDING 回 ACTIVE**（需先确认规格具体恢复态字段名）；`DeletionRequestDraftCleanupScheduler` 每日扫 DRAFT 且 create_time<now-30d 的记录并清理 | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/DeletionRequestServiceImpl.java`；新增 scheduler | 新增 ZkDiffP101AcceptanceTest（@Tag dev）；`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=ZkDiffP101AcceptanceTest test` | 报告 §4 条目 #1 #2；上轮 E 组红线 |
| 2 | ZK-DIFF-P1-02 | Gate 要素发布正向 vetoDualRequired 强校验 | U1 | `GateElementService.publish` 前若 `isVeto='1'` 必须 `vetoDualRequired='1'`（或至少显式确认否），否则抛 `PARAM_INVALID 10001`；单测正反例锁定 | `GateElementService.java:529-531` 附近；测试 | 扩展 `GateElementServiceTest`；`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=GateElementServiceTest test` | 报告 §4 条目 #6；上轮 D 组守卫缺失 |
| 3 | ZK-DIFF-P1-03 | 共担 KPI 第 5 工作日 18:00 自动关闭调度 | U1 | 规格「次月第 5 工作日 18:00 截止」→ `KpiSharedDeadlineScheduler` 新增 `fifthWorkdayAutoClose()` cron；修 `KpiSharedCollectionService.java:280-296` off-by-one（循环把 `remaining--` 与 `date.plusDays(1)` 顺序颠倒，多走 1 天） | `KpiSharedCollectionService.java` / `KpiSharedDeadlineScheduler.java` | 扩展 `KpiSharedDeadlineSchedulerTest`；`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=KpiSharedDeadlineSchedulerTest test` | 报告 §4 条目 #14；上轮 D 组 off-by-one |
| 4 | ZK-DIFF-P1-04 | AI 模式语义 & 项目智能体面板挂载收口 | U1 | 产品拍板：（a）AI 模式=副驾 or 项目智能体？（b）`project-agent-panel.vue` 在哪个业务页挂载？（c）卡片注册表 4 类 type 集合与 `SubStageGuideTool` 的 `sub-stage.guide` 收口为单一注册表 | `views/ipd/_shared/ai-agent/ai-assistant.vue`；`workspace-mode.ts`；`ai-cards/card-registry.ts`；`SubStageGuideTool.java` | 前端 `pnpm run check:type`；`pnpm exec vitest run --config vitest.ipd.config.mts src/views/ipd/_shared/ai-agent`；后端相关单测 | 报告 §3 条目 #1 #2 #12；本轮 AI 原型审计 |

## 6) 待 owner 裁决清单（不阻塞其他修复）

| # | 事项 | 建议 | 影响 |
|---|---|---|---|
| O1 | `is_veto` 归一化 SQL 是否 apply 真库（P47 续轮 commit `425393b8` 遗留） | DBA 窗口批量 UPDATE `'Y'→'1'` / `'N'→'0'`；apply 前保留 `gate_review_elements_backup_20260922` 备份表 | 中：读取侧已兜底双套编码，不 apply 不阻塞功能，但影响后续所有基于 `is_veto` 的 SQL 分析 |
| O2 | 共担 KPI 归集第 5 工作日 18:00 截止的 close 语义（是「不允许再提交」还是「强制 FINALIZED 结算」） | 建议「不允许再提交」——避免自动结算引发争议 | 高：直接影响 ZK-DIFF-P1-03 的实现范围 |
| O3 | 奖金池基数口径最终裁决（`BonusPoolService.java:36-56` 注释 2026-09-06 owner 已拍板「上市后连续 N 月实际回款净额×5%×系数」，规格未同步回写） | 规格 batch-03 + 开发说明书 §8.3 勘误级更新（错字/失效引用/数字对齐）——按 ruoyi-ai AGENTS.md 属允许的勘误；登记 log.md | 高：AC-INC-16 用例复审 |
| O4 | AI 模式语义产品拍板（副驾 vs 项目智能体，或双轨并存） | 建议按现有代码语义「AI 模式=项目智能体」——已符合 C08 零直写红线；原型侧勘误 | 高：ZK-DIFF-P1-04 |
| O5 | HITL「批准即执行」vs「零直写」语义冲突裁决 | 建议保留实现侧「零直写」（符合 C08 红线）；原型侧勘误 | 中 |
| O6 | 60 天无产出停发的活动数据源 | 需外部 HR 或工作日志接入——本波可先保留 PARTIAL 不实现 | 低：登记为 Q4 事项 |
| O7 | 附件（Deliverable oss）删除是否纳入 G-02 红线（禁物理删除） | 需 owner 明确：若纳入则改造 `DeliverableService` 走软删；若不纳入则规格 §页 14 补注 | 中：影响 ZK-DIFF-P2 排期 |
| O8 | 文档双源漂移的方向裁定 | `_导航地图.md` / `batch-01` / `开发说明书.md` 三份 DIFF 需逐条判定 ZK-IPD 版为准还是 ruoyi-ai 镜像版为准，然后向另一侧回写 | 高：影响所有下游会话读到的规格 |
| O9 | `ApiV1ErrorCode` 码段与规格 D.0.3 全量错位 | 需 owner 拍：改代码对齐规格 vs 改规格对齐代码（当前代码已上线运行，倾向改规格+勘误登记） | 高：影响 C 组契约层修复方向 |
| O10 | 页 37 / 页 41 / 招募转立项 / 招标收口链路是否本季度实施 | F 组整页缺失，需产品排期 | 高：影响 M1-M3 收敛节奏 |

## 7) 修复优先级建议（按杠杆排序，非本报告权威）

1. **B 组涉钱口径**：Owner 拍板 O3 / O9 后回写规格（勘误级）——零代码风险，一次收口
2. **D 组状态机守卫**（含本轮 ZK-DIFF-P1-01/02/03）：功能缺陷，~9h
3. **E 组红线失守**（Gate 互不可见、组织写接口、负反馈删除）：安全/合规风险，需产品+安全双拍板
4. **C 组错误码契约**：依赖 O9 方向裁定
5. **F 组断链补端点**（含 AI 原型 #13 产品创建向导、#11 AI 画布四 Tab、ZK-DIFF-P1-04）：新功能，需产品排期

## 8) 元信息与限制披露

- **审计范围**：49 页页级规格 + `_公共规范.md` + `_导航地图.md` + `附录-D6` + `开发说明书.md`（G-01..G-12）+ UI 原型 2 个 HTML；后端 `ruoyi-modules/ruoyi-ipd/**`（74 Controller + Service 层）；前端 `views/ipd/**` + `api/ipd/*.ts`
- **未覆盖**：`_ARCHIVED_v1_*`（已冻结）；`产品流程细化管理工具 2/`（仅口径分裂处引用）；AI 智能体运行时内核（AgentScope / 自研 harness 内部实现，仅到 Controller/前端契约层）
- **方法限制**：本轮 4 张红色 P1 卡为「代码 grep 无匹配」→「未实现」推断（不是「实现但错」）；如未来发现有兄弟会话已实现，本报告需回退条目
- **对齐良好项不代表完备**：§2 与 §3 中「✅ 一致」项仅证明「当前实现符合规格文本」，不证明「业务真活闭环」——真库 HTTP/浏览器三证律验收仍需在具体修复卡中执行
- **本轮未做**：未修改任何文件、未 git commit/push、未编辑看板镜像、未起后端/前端进程；仅只读 grep/read + 落一份新档
- **证据引用规则**：本报告中所有路径:行号引用均可在**报告创建时刻**的 HEAD 复核；因 ruoyi-ai AGENTS.md「假红陷阱」提示多会话共工，行号可能随兄弟会话编辑漂移，标识符（类名/方法名/config_key）为权威

---

**报告版本**：v1 · 2026-09-29
**编制**：Qoder 主会话（agency-harness 6 路子智能体 + 主会话 14 项内联核实）
**审阅**：待 owner 与 ioedream-qa-gatekeeper 独立复核
