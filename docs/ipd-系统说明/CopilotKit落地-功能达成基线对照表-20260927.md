# CopilotKit 落地·功能达成基线对照表（P1-01 产物）

- 日期：2026-09-27｜节点：R232 全局执行计划 **P1-01**（batch 1）｜状态：DRAFT-待签核
- 依据：《CopilotKit三项能力落地-全局执行计划-20260927.md》§2（表头与首批行固定口径）+ §3 P1-01 节点定义；母文件《CopilotKit三项交互能力假设性设计提案-20260927.md》§1 映射矩阵 / §5 红线（尤其 §5.4 L254 功能达成口径）
- 地位：owner 硬约束「功能达成」的**唯一验收分母**（计划 §0 L16）；Phase 2 总验收（P2-06）按本表全表签核，缺一项即视为未达成
- 取证纪律：所有行号为 2026-09-27 现查现写（后端仓 ruoyi-ai / 前端仓 ruoyi-ipd-web）；无证据的格子一律写「未验证」，禁止编造；口径冲突取严并在文末「口径冲突登记」留痕
- 行号口径：前端文件行数以 `wc -l` 实测为准（与计划 §1.2 差 1 行，见口径冲突登记 #1）；行号锚点以本次 grep 现查为准

---

## 一、功能达成基线对照表（表头固定 7 列）

| 功能名 | 原型出处 | 改造前行为 | 改造后行为 | 验收证据（HTTP/DB/截图） | 通过判据 | 承接节点 |
|---|---|---|---|---|---|---|
| A1 ai-suggest·gate.precheck-checklist（评审前检查清单，4 结构化之一） | 母文件 §1.1 主矩阵 L48（G4 否决项门禁预检卡）+ §1.2 L56（否决项门禁预检卡）+ §3.3 L198（Gate 检查→预检清单卡）+ §5.4 L254；49 页清单 P0-10.23/24（L45-46 Gate 评审两页） | 纯文本 markdown：ai-suggest.vue 结果区 `<pre class="suggest-md">` L130，degraded 走 Alert L123-128；场景白名单 AiSuggestionService.java SCENES L57-61；挂载 review/gate-panel.vue:377-378（宿主 project/detail/gates.vue:147 页23 / review/index.vue:265 页24）；只出 markdown 绝不写业务表（AiSuggestionService.java L38） | 响应体增 card 字段｛type,version,data,sourceRefs｝（计划 P1-02），渲染清单卡（4 结构化→卡片，计划 §2 L60）；数据源 gate_review_elements + gate_element_results（计划 §1.3 L48，两表 GateElement.java L18 / GateElementResult.java L22）；无 card/抛错回退文本（P1-07），文本路径永不删 | 未验证（取证时点 P1-02/P1-07）：HTTP POST /api/v1/ai/suggest scene=gate.precheck-checklist→200+code0+card 对齐 Catalog schema；DB system_configs 卡片 Catalog 回读；截图清单卡渲染+带 card/不带/抛错三态 | 4 场景合法 card + 7 场景回归全绿（P1-02 success）；三态可见输出、degraded 降级可见（P1-07 success）；文本降级路径未删（计划 §4 R214） | P1-02→P1-06→P1-07（提交链 P1-08） |
| A2 ai-suggest·gate.conclusion-draft（评审结论草稿，4 结构化之一） | 母文件 §1.1 L44（要素判定草稿卡）+ §1.3 L71（三态判定打勾排除，AI 只生成判定草稿与依据=本场景）+ §3.3 L198（结论草稿卡）+ §5.4 L254；49 页清单 L45-46（P0-10.23/24） | 纯文本 markdown（同 A1 形态）；SCENES L57-61 白名单内；挂载 review/gate-panel.vue:383-384；prompt 模板 AiSuggestionService.java L278（结论草稿含建议倾向与理由）；✅/⚠️/❌ 打勾永远真人（§1.3 L71） | 结论草稿卡（判定卡形态，4 结构化→卡片）；AI 建议倾向只进「建议值」区，打勾/签署真人（C08，母文件 §5.1 L231）；回退文本同 A1 | 未验证（取证时点 P1-02/P1-06/P1-07）：HTTP 同 A1 接口 scene=gate.conclusion-draft→card；DB sourceRefs 回读 gate_element_results；截图草稿卡+BR-AI-04 头部 Alert 在位 | 同 A1；另：卡片打勾动作不存在（真人操作），grep ai-cards/ 无 saveFields/直写（P1-08 C08 五查） | P1-02→P1-06→P1-07（提交链 P1-08） |
| A3 ai-suggest·project.create.suggest（立项要点建议，4 结构化之一） | 母文件 §3.3 L197（需求分析→立项要点卡）+ §5.4 L254；49 页清单 L30（P0-10.8 新建项目） | 纯文本 markdown（ai-suggest.vue L130）；needsPrompt 场景需先输入原始素材（ai-suggest.vue L10/L36，挂载 project/create/index.vue:242-243）；prompt 模板 L272（Charter 要点 markdown 分节） | 立项要点卡（对比/要点卡形态，4 结构化→卡片）；采纳=人目检后手动填表（C08）；回退文本同 A1 | 未验证（取证时点 P1-02/P1-07）：HTTP scene=project.create.suggest→card；截图卡片渲染+needsPrompt 提示保留+三态 | 同 A1；另：needsPrompt 行为不回归（无素材不出卡） | P1-02→P1-06→P1-07 |
| A4 ai-suggest·demand.create.from-requirement（需求单草稿，4 结构化之一） | 母文件 §3.3 L197（需求单草稿卡）+ §5.4 L254；49 页清单 L62（P0-10.40 需求池；demand/index.vue 头注 L2 自证「页40 需求管理」） | 纯文本 markdown（ai-suggest.vue L130）；needsPrompt 场景（L10）；挂载 demand/index.vue:274-275；prompt 模板 L274（需求单草稿：标题/描述/分类/优先级/验收标准） | 需求单草稿卡（草稿卡形态，4 结构化→卡片）；优先级 P0-P3 只进建议值区，创建提交真人（C08）；回退文本同 A1 | 未验证（取证时点 P1-02/P1-07）：HTTP scene=demand.create.from-requirement→card；截图草稿卡+三态 | 同 A1；另：需求单创建仍走既有 /api/v1 真人端点（无新增写入端点） | P1-02→P1-06→P1-07（提交链 P1-08） |
| A5 ai-suggest·workbench.next-step（下一步建议，3 轻场景之一） | 母文件 §4 L215（3 轻场景保持文本，明确点名本场景）+ §5.4 L254；49 页清单 L25（P0-10.3 工作台） | 纯文本 markdown（ai-suggest.vue L130）；挂载 workbench/index.vue:344（label「AI 建议下一步」）；prompt 模板 AiSuggestionService.java L270 附近（150 字状态总结/下一步） | **保持纯文本**，不卡片化（计划 §2 L60 明确「3 轻场景→保持文本」）；组件形态与降级行为不变 | 未验证（取证时点 P1-07）：截图文本输出与改造前同形态；vitest 分发用例含本场景走文本分支 | 本场景输出形态前后一致（文本）；7 场景回归全绿（P1-02 validation 含 7 场景） | P1-07（分发层显式保持文本） |
| A6 ai-suggest·workbench.risk-warning（风险预警，3 轻场景之一） | 母文件 §4 L215（点名 risk-warning 保持文本）+ §5.4 L254；49 页清单 L25（P0-10.3 工作台） | 纯文本 markdown（ai-suggest.vue L130）；挂载 workbench/index.vue:345（label「AI 风险预警」） | **保持纯文本**，不卡片化（计划 §2 L60）；BR-AI-04 风险提示常驻不回归 | 未验证（取证时点 P1-07）：截图文本输出+风险提示在位 | 同 A5 | P1-07 |
| A7 ai-suggest·project.summary.refresh（项目状态总结，3 轻场景之一） | 母文件 §4 L215（点名 project.summary.refresh 保持文本）+ §5.4 L254；49 页清单 L32（P0-10.10 项目详情-项目概览） | 纯文本 markdown（ai-suggest.vue L130）；挂载 project/detail/index.vue:145（label「AI 总结项目状态」）；prompt 模板 L270（150 字以内状态总结） | **保持纯文本**，不卡片化（计划 §2 L60） | 未验证（取证时点 P1-07）：截图文本输出与改造前同形态 | 同 A5 | P1-07 |
| B1 ai-assistant 对话（会话壳 + BR-AI-04 Alert + fillPayload 广播不回归） | 母文件 §4 L216（副驾会话壳共存演进四条）+ §2.4 L157（BR-AI-04 Alert）+ §5.4 L254；49 页清单 L16（AI 助手定位：上游 ruoyi-web 二期备用，IPD 副驾全局挂载）；挂载 layouts/ipd.vue:19/144（后台全站） | 会话壳 Drawer+消息列表（ai-assistant.vue 307 行，wc 实测）；SSE 四帧 meta/delta/done/error（ai-copilot.ts L7-8/L186-188）；done.fillPayload→CustomEvent `ipd:ai-fill-payload`（ai-assistant.vue L91-96）；BR-AI-04 Alert 固定文案 L154-159；mode 恒 suggest、auto 未落地（计划 §1.1 #6 L33，AiCopilotService.java mode=suggest） | 会话壳+Alert+fillPayload 广播**零回归**（计划 §2 L61）；Phase 2 done 处理泛化为 fillPayload ｜ cardPayload，新增 `ipd:ai-card` 事件，`ipd:ai-fill-payload` 兼容保留（P2-02，母文件 §4 L216）；done 帧 card=可选超集（计划 §1.1 #4 L31） | 未验证（取证时点 P2-01/P2-02）：vitest 四帧用例（card 缺席兼容）+双事件用例；浏览器 SSE 实流截图；宿主监听点 grep 回归（action-detail L328/L361 现有监听）；截图 BR-AI-04 Alert 在位 | 既有四帧 0 破坏 + fillPayload 宿主零回归（P2-01/02 success）；BR-AI-04 每卡/每屏无一豁免（计划 §4）；mode!=='suggest' 防御保留（action-detail L345-346） | P2-01→P2-02（Alert 复用随 P1-06） |
| C1 fillContext 字段预填 | 母文件 §5.4 L254（功能清单点名）+ §6 Phase 2 L283（fillContext 前端注册补齐）+ §8 L334（grep 0 命中证据）；计划 §1.1 #3 L30（断点定位）+ §2 L62（不因原状没有免验）；49 页清单 L34-35（P0-10.12/13 动作详情=FILLABLE_FIELDS 宿主） | **缺失/不可达**（如实标注）：2026-09-27 复验 grep fillContext ｜ pageContext /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src → **0 命中**；streamCopilot 只传 message+projectId（ai-copilot.ts L153-154）→ hasPageContext 恒 false（AiCopilotService.java L271）→ FILL_PAGE 意图 UI 不可达；后端链路全通（classifyIntent L254/L264、fillPagePath L312、FILL_FIELD_WHITELIST L285 六字段、filterFillFields L294）；消费端已就绪无源头：onAiFill（action-detail/index.vue L338-357）、FILLABLE_FIELDS 6 字段 L335、事件监听 L361/L364 | Phase 2 补齐（P2-03）：前端 streamCopilot 补传 pageContext + fill 注册（断点=ai-copilot.ts L153-154 与 action-detail L330 锚点）；fill→表单预填→**人目检手动提交**（C08，绝不自动 saveFields——action-detail L342-343 既有约定）；字段白名单同源 FILL_FIELD_WHITELIST 禁扩 | 未验证（取证时点 P2-03）：浏览器走查 fill→表单预填→手动提交截图（六字段逐一）；DB 审计 AI_FILL 落痕（三件套齐）；grep 复验 pageContext 已传 | 功能对照表 fillContext 行签核（P2-03 success）；白名单不齐→以 FILL_FIELD_WHITELIST 为源改前端、禁扩白名单（P2-03 fail 路径）；改造前「缺失/不可达」如实保留不追认 | P2-03 |
| D1 ai-execute 草稿→人审→确认闭环（R221 语义零改动，卡片直达入口） | 母文件 §5.4 L254（功能清单点名）+ §1.4 L83-86（useInterrupt→R221 语义映射四行）+ §4 L218（演进路径）+ §5.4 功能口径；49 页清单 L34-35（P0-10.12/13 发起点）+ L64（P0-10.42 草稿人审宿主） | POST /stage-actions/{id}/ai-execute（stage-action.ts L204-205，返回 taskId L196）→ ai_agent_tasks 五态 PENDING/RUNNING/SUCCEEDED/FAILED/DEAD（AiAgentTask.java L31-35）；失败退避 30s/2m/10m、≥3 次 DEAD 转人工（AiExecutionEngine.java L30/L171-186）；草稿 GENERATED→REVIEWED/REJECTED 属 ai_documents（AiDocumentService.java L36/L51-55，与任务五态分离=计划 §1.1 #1）；人审 afterCommit 唤醒后继（AiExecReviewHook.java L80-101）；**ai_agent_tasks 无 REST 查询端点→任务卡无数据通道、发起后失联**（计划 §1.1 #7 L34，母文件 §4 L218） | R221 语义**零改动**（计划 §2 L63，母文件 §4 L219 执行器/状态机/守卫/审计全不动）；补任务查询端点（按 taskId 单查+按项目列表，前后端同批齐落防孤儿，P2-04）；卡片直达入口=发起→进度→ai_documents GENERATED 待审→人审通过四态（P2-04）；通知直达复用 NotificationService 载荷；状态呈现到 result_summary 粒度、不展示 prompt 原文 | 未验证（取证时点 P2-04）：HTTP 发起 ai-execute→200+taskId；DB 回读 ai_agent_tasks（PENDING→SUCCEEDED）+ ai_documents（GENERATED→REVIEWED）；浏览器四态截图；待办直达跨设备可用（不靠会话回放） | 任一时刻从待办可直达未完成审批卡（P2-04 success）；R221 状态机/守卫/审计零改动（git diff 证）；「中断」语义=ai_documents GENERATED 稳定等待（计划 §1.1 #2 L29，不依赖任务级暂停） | P2-04 |
| E1 全站兜底·六阶段 69 动作 | 母文件 §1 L36（附录 A 为档位唯一权威）+ §8 L317（H5/D40/G24）；《IPD全阶段AI代理执行闭环设计-20260926.md》L9（seed/ActionCatalog.java 69 动作目录）+ L264-266（附录 A 定案：HUMAN_GATE 5 / AI_DIRECT 40 / AI_GENERATE 24）；49 页清单 L19-71（全 49 页功能分母） | ActionCatalog.java（seed/ActionCatalog.java，现查存在）69 动作目录；档位分布 HUMAN_GATE 5 / AI_DIRECT 40 / AI_GENERATE 24（附录 A L266）；六阶段动作分布 12/13/11/12/8/9/4（母文件 §8 L326） | **既有不动**：CopilotKit 改造不得改变任何动作的功能语义/档位/守卫；卡片层只做呈现叠加（母文件 §4 L219） | 未验证（取证时点 P2-06）：抽样回归留证——每阶段抽 1 动作 HTTP 走通 + DB 业务表回读；git diff 证 ActionCatalog 69 行零改动（R214 三查） | 抽样动作全绿；69 动作功能语义前后一致；零新表/零新门禁（计划 §4 R214） | 既有不动（P2-06 抽样回归） |
| E2 全站兜底·五大 Gate 双签/仲裁/终裁 | 母文件 §1.3 L65-66（盲签 sign 排除、arbitrate/finalRuling 决定动作永远真人）+ §3.2 L178-189（双签异步审批链）+ §8 L325（33 要素/15 否决/2 双签）；《IPD系统_五大Gate评审要素_v1.md》L5（33 项要素+15 否决项）；49 页清单 L45-46（P0-10.23/24） | GateReviewService.java：sign L207、preCheckGuard L124/L299、settle L296-292、双 PM 分歧→组长仲裁→超管终裁（L70-72/L309，gate_arbitrations 表）；盲签 rowView（my 全揭示 L263、other 按 revealed L269、定义 L339）；要素两表 gate_review_elements（GateElement.java L18）+ gate_element_results（GateElementResult.java L22） | **既有不动**：sign/arbitrate/finalRuling 全走既有 Service 方法，无新增写入路径（母文件 §3.2 L189）；卡片只做状态呈现与直达入口；reveal 前对方判定不进任何卡片 data/sourceRefs（P2-05） | 未验证（取证时点 P2-05/P2-06）：双账号浏览器签署链截图（sign→分歧→仲裁→终裁）+ DB gate_review/gate_arbitrations 回读；盲签隔离双账号走查截图；历史 E2E-验收-*.md 存在但未逐项核对 | 双签/仲裁/终裁链路行为不变；盲签红线断言绿（reveal 前对方判定零泄漏）；卡片无 AI 倾向渲染于 arbitrate/finalRuling | 既有不动（卡片相关挂 P1-08 提交映射 / P2-05 盲签契约） |
| E3 全站兜底·双 PM 协同 | 母文件 §3.1 L166-176（会话隔离模型：同一业务实体按人隔离 thread）+ §1.3 L74（共享会话排除）+ §8 L324（双 PM 自评→知会组长终裁）；49 页清单 L10（G-09 角色体系：超管/产品组长/普通PM/游客） | ContributionService.java：双 PM 自评 SUBMITTED→知会组长终裁（L73 R221 通知缺口注释 / L376 补线）；双 PM 并发首填唯一键防撞（L360）；G1/G5 双签两行隔离（GateReviewService L263/L269）；会话按人隔离=每会话独立 history（母文件 §3.1 L176） | **既有不动**：「同一实体、按人隔离」模型不变（Phase 1/2 自研实现，不引 Rich Threads——母文件 §1.3 L74）；卡片不得引入共享会话 | 未验证（取证时点 P2-05/P2-06）：双 PM 账号各自可见性 HTTP 抽样 + 截图；自评→组长终裁链 DB 回读 | 双 PM 隔离语义不变；对方草稿 reveal 前不可见（与 E2 盲签同判据） | 既有不动（P2-05 盲签隔离契约扩展） |
| E4 全站兜底·菜单权限 | 49 页清单 L10（G-09：页 43-49 系统管理区仅超管；用户/角色/组织/权限复用若依 RBAC，IPD 角色映射其上）+ L12（二开复用铁律）；母文件 §5.2 L241（BR-AI-05 越权→50001） | 权限码单一权威源 _shared/ipd-permission-codes.ts L1-12（75 key 镜像后端 org.ruoyi.ipd.security.IpdPermissionCode）；路由表与按钮 v-access:code 引用常量禁字面量（L10-11）；AI 面板同走可见性校验：项目不可见→IpdBusinessException NOT_FOUND（AiSuggestionService.java L311、AiCopilotService.java L680） | **既有不动**：卡片/面板不新增越权通道；卡片请求沿用 project_members 可见性校验（BR-AI-05）；菜单/按钮权限行为不变 | 未验证（取证时点 P1-08/P2-06）：越权 projectId→50001/NOT_FOUND 负例 HTTP；按角色登录菜单渲染截图抽样（超管 vs 普通PM） | 49 页菜单/按钮权限行为不变；AI 面板请求越权负例全红→绿；无绕过 v-access 的新入口 | 既有不动（BR-AI-05 负例随 P1-08/P2-05 回归） |
| E5 全站兜底·审计三件套 | 母文件 §2.4 L156（卡片提交落三件套）+ §5.2 L237-241（审计红线五查）+ §4 L221（不发明新 aiRole）+ §8 L336；《AI-审计三件套规约-20260923.md》L18-35（aiRole 7 值白名单、越界门禁拦截） | AuditEventData.java：AI_ROLES 7 值 Set.of（draft ｜ precheck ｜ summarize ｜ copilot_answer ｜ streaming ｜ agent_exec ｜ suggestion）L121，缺一即抛 L101-104；AI_SUGGEST 审计行每次调用必落（AiSuggestionService.java L42-44），只记 promptLen 不记原文；aiModel 降级值 intent_match（L43） | **既有不动**：卡片提交复用既有 action 与 aiRole 值（结构化建议卡=suggestion、草稿卡=draft、对话答案卡=copilot_answer、意图=copilot_intent 映射、流式=streaming——计划 §4 L119，**7 值够用禁自造**）；纯浏览不落审计行；prompt 原文不入库 | 未验证（取证时点 P1-08/P2-06）：DB audit_logs 抽样回读（aiAssisted=true、aiModel 非空、aiRole∈7 值、无 prompt 原文）；负向 SQL 无 AI 系统身份写入行 | 审计三件套齐、无新 aiRole 值、无原文入库、被拒不落「假采纳」（母文件 §5.2 L239） | 既有不动（卡片提交落痕挂 P1-08 / 抽样 P2-06） |
| F1 测算卡·语义 A（C08 四项基准值→stage_actions 六字段）【计划 §8.1 要求 P1-01 并列两行】 | 母文件 §1.1 L44（概念阶段：字段预填卡（C08 四项基准值））+ §1.3 L70（C08/C09 锁定后字段修改排除，锁定字段只读展示不进预填槽位）；计划 §1.3 L50（语义 A=C08 基准值→stage_actions 六字段）+ §8.1 L152 | 无测算卡形态；C08 四项基准值走 stage_actions 六字段常规录入；AI 输出只出 markdown（AiSuggestionService.java L38）；C08 锁定规则在（母文件 §1.3 L70：G1 通过即冻结，修改需双签+审计） | **语义 A/B 待 owner 拍板（计划 §8.1，P2 前拍板即可）**：若采纳语义 A=卡片化呈现四项基准值建议值；金额/评分/系数只进「建议值」区（母文件 §5.1 L231）；锁定字段只读不进预填 | 未验证（拍板后定义取证）：暂无取证口径——待 §8.1 拍板定取舍后补 | 拍板取舍书面明确；若实施则建议值区语义不降低 C08 锁定/双签修改规则（母文件 §1.3 L70） | P2-04（任务卡族并列占位；取舍拍板=计划 §8.1） |
| F2 测算卡·语义 B（奖金分配试算→bonus_pools）【计划 §8.1 要求 P1-01 并列两行】 | 母文件 §1.1 L49（生命周期：奖金分配审批卡（金额域））+ §1.3 L69（试算卡可以，终审按钮真人走既有 409/50002 门禁）；计划 §1.3 L50（语义 B=奖金→bonus_pools（/compute /coefficient/preview））+ §8.1 L152；49 页清单 L56（P0-10.34 激励管理-奖金池核算） | 无卡片形态；BonusPoolService.compute 试算与 /coefficient/preview 端点在（计划 §1.3 L50）；奖金状态机 DRAFT→CONFIRMED→DISTRIBUTED + R213 门禁 requireConfirmedContribution（母文件 §8 L323）；页34 奖金池核算现状无 AI 面板挂载（2026-09-27 挂载点全扫无 bonus 页） | 同 F1 待拍板：若采纳语义 B=分配试算卡（金额域只进「建议值」区）；终审按钮真人且走既有 409/50002 门禁，卡片零写入 | 未验证（拍板后定义取证）：暂无取证口径 | 同 F1；另：R213 门禁行为不变（终审真人） | P2-04（同 F1 并列占位） |
| G1 页42 AI 文档助手 8 端点闭环（generate/revise/review/archive/reject/versions/history/diff）【49 页核对补充行·宁多勿少】 | 49 页清单 L64（P0-10.42 AI 文档助手，依赖 P4-2.3）+ L36（P0-10.14 文档与交付物=人工改版入口，ai-docs 头注 L12 指认）；母文件 §5.4 L254（ai-execute 草稿→人审→确认闭环） | 已实现独立闭环页：ai-docs/index.vue（845 行）头注 L1-13 自证 8 端点契约（generate/revise/review/archive/reject/versions/history/diff，按 AiDocumentController P1-10.1+P4-2.2+P4-2.3）；BR-AI-03 未审核不生效、BR-AI-04 风险提示（头注 L8-9）；状态机 GENERATED→REVIEWED/REJECTED/ARCHIVED（AiDocumentService.java L32-36/L51-55） | **既有不动**（本波改造不改其功能语义）；卡片直达入口与 D1 行 P2-04 任务卡衔接（GENERATED 待审卡直达本页人审） | 未验证（取证时点 P2-06）：抽样回归 generate→review→versions/diff HTTP + 截图；DB ai_documents 版本链回读 | 8 端点行为不变；BR-AI-03 未审核不生效不变；页14 人工改版入口不断 | 既有不动（人审链衔接 P2-04） |
| G2 页48 AI 模型配置启用行（degraded 判定源）【49 页核对补充行·宁多勿少】 | 49 页清单 L70（P0-10.48 AI 模型配置，依赖 P4-2.1）；母文件 §2.4 L158（degraded→卡片退化纯文本）+ §4 L215（degraded 走现状形态） | 模型启用行=AI 能力降级判据源：AiCopilotService.java L169 modelConfigService.currentEnabled；AiSuggestionService degraded 时 aiModel 记 intent_match（L43）、ai-suggest.vue degraded 走 Alert 引导（L123-128、头注 L11） | **既有不动**；卡片降级判据沿用 degraded 信号（母文件 §2.4 L158 卡片层是增强不是依赖） | 未验证（取证时点 P1-07/P2-06）：停用模型→ai-suggest/ai-assistant degraded 截图；DB system_configs/ai_model_configs 启用行回读 | degraded 降级可见（P1-07 三态之一）；模型配置功能行为不变 | 既有不动（degraded 三态随 P1-07） |

---

## 二、49 页清单逐页核对（「AI 面板相关功能」无漏项检查）

核对方法：① 49 页清单（前端49页交接清单-20260905.md L23-71 逐行）取页面全集；② 前端仓 2026-09-27 挂载点全扫（grep ai-suggest.vue ｜ ai-assistant.vue ｜ aiExecuteStageAction ｜ streamCopilot ｜ ipd:ai-fill-payload → views/ layouts/ 全命中清单）反查每页 AI 面板挂载；③ 对照母文件 §5.4 L254 功能清单四件套逐项落行。无挂载页标「无专属面板（全局副驾 B1）」；免登录/认证区不套后台布局（49 页清单 L8）连 B1 也无。

| 卡号 | 页面 | AI 面板相关功能（2026-09-27 现查挂载） | 覆盖行 |
|---|---|---|---|
| P0-10.1 | 登录页 | 无（免登录/认证区不套 ipd 布局，49 页清单 L8） | — |
| P0-10.2 | 首次登录强制改密 | 无专属面板；全局副驾随布局（布局归属未验证，取严按有 B1 计） | B1 |
| P0-10.3 | 工作台 | ai-suggest×2（workbench/index.vue:344-345）+ 全局副驾 | A5 / A6 / B1 |
| P0-10.4 | 删除审核-我的申请 | 无专属面板（全局副驾） | B1 |
| P0-10.5 | 删除审核-待我审核 | 无专属面板（全局副驾） | B1 |
| P0-10.6 | 审计日志 | 无专属面板（全局副驾） | B1（E5 审计是数据源） |
| P0-10.7 | 我的项目-列表 | 无专属面板（全局副驾） | B1 |
| P0-10.8 | 新建项目 | ai-suggest project.create.suggest（project/create/index.vue:242-243） | A3 / B1 |
| P0-10.9 | 存量项目导入 | 无专属面板（全局副驾） | B1 |
| P0-10.10 | 项目详情-项目概览 | ai-suggest project.summary.refresh（project/detail/index.vue:145） | A7 / B1 |
| P0-10.11 | 项目详情-IPD 流程 | 无专属挂载（flow.vue 不在挂载全扫命中清单） | B1 |
| P0-10.12 | 深管动作详情 | fillContext 消费端（action-detail/index.vue L328-364）+ ai-execute 发起（L311-316） | C1 / D1 / B1 |
| P0-10.13 | 轻管动作详情 | 同页12（同一 action-detail/index.vue 653 行双模式） | C1 / D1 / B1 |
| P0-10.14 | 项目详情-文档与交付物 | AI 文档人工改版入口（ai-docs/index.vue 头注 L12 指认页14） | G1 / B1 |
| P0-10.15 | 项目详情-项目日志 | 无专属面板（全局副驾） | B1 |
| P0-10.16 | 产品管理-产品目录 | 无专属面板（全局副驾） | B1 |
| P0-10.17 | 产品新增/编辑 | 无专属面板（全局副驾） | B1 |
| P0-10.18 | 国别认证清单模板库 | 无专属面板（全局副驾） | B1 |
| P0-10.19 | 招标组队-招标单列表 | 无专属面板（全局副驾） | B1 |
| P0-10.20 | 发起招标 | 无专属面板（全局副驾） | B1 |
| P0-10.21 | 应标 | 无专属面板（全局副驾） | B1 |
| P0-10.22 | 遴选 | 无专属面板（全局副驾） | B1 |
| P0-10.23 | 项目详情-Gate 评审 | ai-suggest×2 经 GatePanel（gate-panel.vue:377-378/383-384，宿主 project/detail/gates.vue:147） | A1 / A2 / B1 |
| P0-10.24 | Gate 评审详情 | 同 GatePanel（review/index.vue:50/265） | A1 / A2 / B1 |
| P0-10.25 | 项目详情-需求与变更 | 无专属挂载（changes.vue 不在挂载全扫命中清单） | B1 |
| P0-10.26 | 需求变更单详情 | 无专属面板（全局副驾） | B1 |
| P0-10.27 | 项目移交 | 无专属面板；移交确认短语=原生表单（母文件 §1.3 L68 显式排除 AI） | B1 |
| P0-10.28 | 组织架构 | 无专属面板（全局副驾） | B1 |
| P0-10.29 | KPI 考核-功能 KPI | 无专属面板（KPI 数值卡属母文件 §1.1 L50 规划形态，非现有功能不入分母） | B1 |
| P0-10.30 | KPI 考核-共担 KPI 归集 | 同上（规划卡非现有功能） | B1 |
| P0-10.31 | 项目绩效评定 | 无专属面板（全局副驾） | B1 |
| P0-10.32 | 项目详情-KPI 考核 | 无专属面板（全局副驾） | B1 |
| P0-10.33 | 激励管理-津贴台账 | 无专属面板（全局副驾） | B1 |
| P0-10.34 | 激励管理-奖金池核算 | 无现有卡片；测算卡·语义 B 为其规划形态（待拍板） | F2 / B1 |
| P0-10.35 | 贡献度评定 | 无专属面板；双 PM 自评链（E3） | B1 / E3 |
| P0-10.36 | 负反馈执行 | 无专属面板（全局副驾） | B1 |
| P0-10.37 | 项目详情-激励台账 | 无专属面板（全局副驾） | B1 |
| P0-10.38 | 需求门户-游客提交 | 无（免登录区不套后台布局，49 页清单 L8） | — |
| P0-10.39 | 需求门户-查询进度 | 无（免登录区） | — |
| P0-10.40 | 需求池 | ai-suggest demand.create.from-requirement（demand/index.vue:274-275，头注 L2 自证页40） | A4 / B1 |
| P0-10.41 | 需求详情-处理 | 无专属面板（全局副驾） | B1 |
| P0-10.42 | AI 文档助手 | 8 端点 AI 文档闭环页（ai-docs/index.vue L1-13）+ 人审链 | G1 / D1 / B1 |
| P0-10.43 | 删除审核-归档区 | 无专属面板（全局副驾） | B1 |
| P0-10.44 | 人员管理 | 无专属面板（全局副驾） | B1 |
| P0-10.45 | 参数配置 | 无专属面板（全局副驾） | B1 |
| P0-10.46 | SOP 模板 | 无专属面板（全局副驾） | B1 |
| P0-10.47 | Gate 评审要素 | 无面板挂载（admin/gate-detail/index.vue 仅注释引用页24 gate-panel，L18）；要素配置=E1/E2 数据源 | B1 |
| P0-10.48 | AI 模型配置 | degraded 判定源（AiCopilotService.java L169） | G2 / B1 |
| P0-10.49 | 超级管理员移交 | 无专属面板；移交域确认短语原生表单（母文件 §1.3 L68） | B1 |

**核对结论**：✅ **无漏项**。① 前端仓挂载点全扫命中 9 个宿主文件（layouts/ipd.vue、workbench、project/create、project/detail/index、demand/index、review/gate-panel、project/action-detail、_shared 两件）逐一对账 49 页，7 场景挂载点 + 副驾全局挂载 + fill/ai-execute 消费点全部落入 A1-A7 / B1 / C1 / D1 行；② 母文件 §5.4 L254 功能清单四件套（ai-suggest 7 场景、ai-assistant 对话、fillContext 字段预填、ai-execute 草稿→人审→确认闭环）+ 全站五件（69 动作、五大 Gate、双 PM、菜单权限、审计三件套）全部入表（A/B/C/D/E 组）；③ 宁多勿少补 3 行：F1/F2（计划 §8.1 强制并列）+ G1（页42 八端点闭环）+ G2（页48 降级判据源）——其中 G1/G2 为超出首批行口径的补充行；④ 母文件 §1.1 映射矩阵中的规划卡片（C01 调研草稿卡、排期对比卡、KPI 数值卡、GTM 判定卡、复盘卡等）属**改造目标形态而非现有功能**，不入「改造前」分母，其落点由 P1-02（4 场景）/ P2-04（任务卡族）承接；⑤ AI_DIRECT 轻动作回执呈现（母文件 §1.2 L58「价值低，保持现状」+ §1.3 L51 回执卡）按「既有不动」并入 E1 行语义，不单列（见口径冲突登记 #4）。

---

## 三、未验证格子清单（禁止编造，逐格登记）

全部「未验证」格子均集中在**验收证据列**（P1-01 为改造前建表时点，取证随承接节点产出）与 F1/F2 待拍板格：

| 行 | 格子 | 状态 | 取证时点 |
|---|---|---|---|
| A1-A4 | 验收证据（HTTP/DB/截图） | 未验证 | P1-02 / P1-06 / P1-07 |
| A5-A7 | 验收证据 | 未验证 | P1-07 |
| B1 | 验收证据 | 未验证 | P2-01 / P2-02 |
| C1 | 验收证据（改造前 0 命中已有复验证据，验收证据待产出） | 未验证 | P2-03 |
| D1 | 验收证据 | 未验证 | P2-04 |
| E1-E5 | 验收证据（抽样回归留证） | 未验证（E2 注：历史 E2E-验收-*.md 存在但未逐项核对，不作数） | P2-05 / P2-06 |
| F1 | 改造后行为（语义 A/B 未拍板）+ 验收证据 | 未验证 | 计划 §8.1 拍板后 |
| F2 | 改造后行为（语义 A/B 未拍板）+ 验收证据 | 未验证 | 计划 §8.1 拍板后 |
| G1 / G2 | 验收证据 | 未验证 | P2-06 / P1-07 |
| 49 页核对表 | P0-10.2 全局副驾布局归属 | 未验证（取严按有 B1 计） | P2-02 宿主回归时顺带确认 |

---

## 四、口径冲突登记（取严处置，留痕）

| # | 冲突 | 两造口径 | 取严处置 |
|---|---|---|---|
| 1 | 前端文件行数 | 计划 §1.2 L38-41：ai-suggest.vue 189 / ai-assistant.vue 308 / ai-copilot.ts 194 / action-detail 654 行；2026-09-27 wc -l 实测：188 / 307 / 193 / 653（母文件 §8 L333 亦记 193+307+188=688） | 以实测 wc -l 为准（相差 1 行为末行换行计数差）；行号锚点以本次 grep 现查为准（与计划 §1.2 行号一致，无实质冲突） |
| 2 | 测算卡是否入 P1-01 分母 | 计划 §2 首批行（L59-64）未列测算卡；计划 §8.1 L152 要求「P1-01 建表时并列两行」 | 取严=入表（F1/F2 并列占位）；语义 A/B 取舍 P2 前拍板，拍板前两行均不得删 |
| 3 | 卡片命名两套并存 | 母文件 §2.3/§3.3 卡片名（预检卡/草稿审核卡/测算卡/回执卡）vs 计划 §2 首批行 4 结构化场景（清单/结论草稿/立项要点/需求单草稿） | 两套名并存互映：A1-A4 以场景白名单名为准（SCENES L57-61），卡片 type 定名以 P1-02 Catalog（system_configs JSON）为准，禁硬编码（母文件 §2.3 L145） |
| 4 | 回执卡是否单列 | 母文件 §1.3 L51 回执卡（stage_actions + /fields /transit）与 §1.2 L58「AI_DIRECT-LIGHT 回执卡价值低，保持现状」；计划 §2 首批行未列 | 取严处置=不删功能：按「既有不动」并入 E1 行（69 动作行）语义；任务进度呈现并入 D1/P2-04 任务卡族（回执/进度同卡组）；如 owner 要求单列可后加行（加行不减行） |
| 5 | 「改造前缺失」类功能的验收地位 | 朴素理解「原状没有=不用验」；计划 §2 L62 明示「不因原状没有免验」 | 取严=C1 fillContext 照常入表、Phase 2 补齐后签核，改造前格如实标「缺失/不可达」不追认 |

---

## 五、签核栏

- [ ] P1-01 每行可回溯（文档行号或代码符号）——本次已逐行挂锚
- [ ] 49 页清单对照无漏项——本次核对结论 ✅（见第二节）
- [ ] 功能项 100% 入表且挂节点 id 或「既有不动」——A/B/C/D/E/F/G 共 19 行已挂
- [ ] owner 签核（Phase 2 总验收 P2-06 按本表全表签核，缺一项即视为未达成）
