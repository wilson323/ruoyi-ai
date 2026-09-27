# CopilotKit 三项能力落地·全局执行计划

- 日期：2026-09-27
- 状态：**EXECUTING — owner 已拍板开工**（原话「第 3 步直接开工」「充分利用多个专业的智能体并行执行」）
- 前提（均已拍板）：① ZK-IPD 逐页复刻红线**全界面豁免**（界面可自由重新设计）；② 硬约束「**功能达成**」——原型/49 页规格全部功能保留、验收标准不降低；③ Phase 1/2 **零新依赖**（不引 CopilotKit SDK/LangGraph，Phase 3 另行决策）
- **单一事实源声明**：任务以看板（ruoyi-ai 项目，127.0.0.1:62250）为唯一事项源；本文档是执行计划的导出物与证据锚点，不是平行台账。
- 母文件：《CopilotKit三项交互能力假设性设计提案-20260927.md》（设计论证）；本文件是它的**可执行化**，冲突时以本文件的任务定义为准、以母文件的红线条款为纲。

---

## 0. 执行原则（owner 四要求 → 落地机制）

| owner 要求 | 落地机制（复用既有，不新造） |
|---|---|
| 全局一致性 | schema 双份对账哨兵（后端 JSON ↔ 前端 TS）；表名/端点/状态名一律以本文件 §1 现查结论为准 |
| 业务、代码逻辑闭环 | 三证律（HTTP + DB 回读 + 浏览器截图）逐节点验证；功能达成对照表（§2）为最终验收分母 |
| 避免双轨 | 零新表/零新门禁/零新台账；卡片注册复用 system_configs；审计复用三件套；文本降级路径永不删除 |
| 单一原则 | 看板=唯一事项源；OPS-09 单一写入者（主会话统一 commit，智能体只读+交付证据）；裁决源=后端 schema JSON（catalog lives backend-side） |

---

## 1. 现状盘点结论（2026-09-27 三路智能体并行实查，行号现查现写）

### 1.1 提案 ↔ 代码现状出入修正表（★=影响任务图定义，必须按修正后执行）

| # | 提案原文（位置） | 实查结论（证据） | 修正 |
|---|---|---|---|
| 1 ★ | ai_agent_tasks 落 GENERATED/PENDING_REVIEW 态（母文件 §1.4） | **两状态不存在**。ai_agent_tasks 实际状态机 = PENDING/RUNNING/SUCCEEDED/FAILED/DEAD（AiAgentTask.java L31-35）；GENERATED→REVIEWED/REJECTED/ARCHIVED 属 **ai_documents**（AiDocumentService.java L51-57） | 任务卡进度走 ai_agent_tasks 五态；「草稿审核中断」走 ai_documents GENERATED→REVIEWED。P2-04 按此定义 |
| 2 ★ | 服务端 interrupt() 暂停图执行（§1.4） | 全库无此机制。GatePrepExecutor 备料失败 = AiExecResult.fail → 任务 FAILED → 退避重试 30s/2m/10m，≥3 次 DEAD（AiExecutionEngine L41/L185-189），**不是稳定暂停态** | 「中断」语义映射改为：ai_documents GENERATED 态 + 通知待办（人审前的稳定等待），不依赖任务级暂停 |
| 3 ★ | fillContext 字段预填（§5.4 功能清单） | 比「前端未落地」更严重：streamCopilot 只传 message+projectId（ai-copilot.ts L153-154），**pageContext 前端 0 命中** → FILL_PAGE 意图 UI 完全不可达（hasPageContext 恒 false，AiCopilotService.java L264/L271-273）；服务端链路全通（Controller L93-94 → fillPagePath L312-407） | P2-03 修复点 = 前端 streamCopilot 补传 pageContext + fill 注册，断点明确 |
| 4 ★ | done 帧新增 card 字段（§2.2） | 现状 done 帧 = {status,tokenPrompt,tokenCompletion,latencyMs}+fillPayload 可选（AiCopilotController.java L130-139），无 card/meta 预告；meta 帧 data（CopilotDataItem[]）**前端未渲染被丢弃** | P2-01 按「可选超集」加 card，fillPayload 保留兼容；meta.data 渲染列入 P2-02 可选项 |
| 5 | 对齐 gate_review_element 表（§2.3） | 实际两表：gate_review_elements（定义，GateElement.java L18）+ gate_element_results（结果，GateElementResult.java L22） | 卡片数据源按两表设计 |
| 6 | 敏感字段确认卡 / auto 模式（§2.1） | mode 恒 suggest（AiCopilotService.java L376），auto 模式与确认卡机制均未落地；前端防御性忽略非 suggest（action-detail L345-346） | Phase 1 不做 auto 模式（C08 红线方向），确认卡=卡片化形态本身 |
| 7 ★ | ai-execute taskId 接入任务卡（§4） | taskId 字段在（stage-action.ts L196），但 **ai_agent_tasks 无任何 REST 查询端点**（仅服务层调用），任务卡无数据通道 | P2-04 需补查询端点——前后端同批齐落（防孤儿棘轮红），端点最小化（按 taskId 单查+按项目列表） |

### 1.2 前端现状（改造靶面）

- ai-assistant.vue 308 行：纯文本气泡（L172-177），done.fillPayload → CustomEvent `ipd:ai-fill-payload`（L92-96）；挂载 layouts/ipd.vue:19
- ai-suggest.vue 189 行：`<pre>` 纯文本（L130），degraded 走 Alert（L123-128）；props scene/entityId/projectId/needsPrompt/adoptable；7 场景挂载点：workbench/index.vue:344-345、project/detail:145、project/create:243、demand/index:275、review/gate-panel.vue:378/384
- ai-copilot.ts 194 行：SSE 四帧（meta/delta/done/error）消费；fillPayload L75-79
- action-detail/index.vue 654 行：onAiFill（L338-357）、FILLABLE_FIELDS 6 字段（L335）、mode!=='suggest' 防御（L345-346）
- 后端：AiSuggestionService 344 行（SCENES L57-61，只出 markdown 不写业务表 L36-38）；AiCopilotService 746 行（FILL_FIELD_WHITELIST L285-287 六字段、filterFillFields L293-305）；AiExecReviewHook 187 行（afterCommit 唤醒 L89-95）

### 1.3 卡片数据源可行性（4 卡）

| 卡片 | 数据源 | 可行性 |
|---|---|---|
| gate.precheck 预检卡 | gate_review_elements + gate_element_results + gate_reviews；renderContext gate 分支 L182-207 已有查询 | 可直接组装 |
| 草稿审核卡 | ai_documents 版本链 + ai_agent_tasks.aiDocId（L59）；AiDocumentController /review:83 /versions:109 /diff:158 | 可复用，「按项目待审」聚合需小补 |
| 测算卡 | 语义 A=C08 基准值→stage_actions 六字段；语义 B=奖金→bonus_pools（/compute /coefficient/preview） | **语义 A/B 待 owner 拍板**（§8） |
| 回执卡 | stage_actions + /fields /transit；receipt_ledgers | 端点在；任务进度依赖 #7 新增查询端点 |

---

## 2. 功能达成基线（P1-01 产物定义——owner 硬约束的唯一验收分母）

**表头**：`功能名 | 原型出处 | 改造前行为 | 改造后行为 | 验收证据（HTTP/DB/截图） | 通过判据 | 承接节点`

**首批行**（缺一项即视为「功能达成」未达成）：
1. ai-suggest 7 场景 ×7 行：gate.precheck-checklist / gate.conclusion-draft / project.create.suggest / demand.create.from-requirement（4 结构化→卡片）+ workbench.next-step / workbench.risk-warning / project.summary.refresh（3 轻场景→**保持文本**）
2. ai-assistant 对话（会话壳 + BR-AI-04 Alert + fillPayload 广播不回归）
3. fillContext 字段预填（改造前=**缺失/不可达**，如实标注；Phase 2 补齐，不因「原状没有」免验）
4. ai-execute 草稿→人审→确认闭环（R221 语义零改动，卡片直达入口）
5. 全站功能兜底行：69 动作 / 五大 Gate 双签仲裁终裁 / 双 PM 协同 / 菜单权限 / 审计三件套——「既有不动」，抽样回归留证

---

## 3. 任务图（17 节点 / 11 批次；batch 内无依赖可并行）

> 节点字段：objective / preconditions / action（含文件）/ validation / success_condition / failure_path。标 ✓=已现查存在，⚠=开工首动作现查。
> **多智能体并行**：batch 内节点派发给专业智能体只读+实施分工，主会话为唯一写入者（commit/看板）。

### Phase 1 — 自研卡片化（零依赖）

**P1-01**（batch 1，先行）功能达成基线对照表｜action：按 §2 表头以 49 页交接清单（✓ docs/ipd-系统说明/前端对接/前端49页交接清单-20260905.md）+ 母文件 §5.4 功能清单逐项建表｜validation：每行可回溯文档行号或代码符号，对照 49 页清单无漏项｜success：功能项 100% 入表且挂到节点 id 或标「既有不动」｜fail：口径冲突→取严升级 owner

**P1-02**（batch 2）AiSuggestionService 增 structured 输出模式 + 4 场景卡片 schema Catalog（JSON 存 system_configs，禁硬编码）｜pre：P1-01｜action：改 ✓ service/AiSuggestionService.java（SCENES 白名单语义不动），响应体增 card 字段 {type,version,data,sourceRefs}｜validation：HTTP 4 场景 → 200+code0+card 对齐 schema；DB 回读 system_configs｜success：4 场景合法 card + 7 场景回归全绿｜fail：回归红→revert structured 分支无损回退

**P1-05**（batch 2，与 P1-02 并行）前端 CardRegistry + 4 类型 TS interface（R1 骨架作者控制）｜pre：P1-01｜action：新建 ✓ _shared/ai-cards/ 目录 + 注册表 + interface；先查 drift-guard 对 _shared 新文件放行规则｜validation：check:type 过 + drift-guard 不阻断｜success：interface 与后端 JSON 字段一一对应（对账在 P1-04）｜fail：drift-guard 阻断→按合法路径调整，禁加白名单绕过

**P1-03**（batch 3）cardPayload 组装器 + R2 白名单双向校验（schema 外字段丢弃+落审计）｜pre：P1-02｜action：组装器照抄 ✓ FILL_FIELD_WHITELIST（L285）校验模式；data 全部经 sourceRefs 回读业务表（R3）｜validation：注入越 schema 字段→丢弃+audit_logs 新行；DB 对账数值相等｜success：负向红→绿；sourceRefs 缺失→拒出卡降级文本｜fail：无法回读真实记录→不出卡回退文本

**P1-06**（batch 3）4 卡片组件（判定/清单/草稿/对比）+ 头部 BR-AI-04 Alert｜pre：P1-05｜action：_shared/ai-cards/<type>.vue ×4；Alert 复用 ✓ ai-assistant.vue 既有形态；确认按钮只调既有 api 层（C08 零直写）｜validation：vitest + 浏览器截图 4 卡+Alert 在位；grep 无 saveFields/直写｜success：4 卡截图齐、组件层零写入路径｜fail：不过的卡不进 P1-07 分发

**P1-07**（batch 4）ai-suggest.vue 分发层（有 card→卡片渲染，无 card/抛错→回退文本，文本路径永不删）｜pre：P1-02/05/06｜action：改 ✓ ai-suggest.vue（189 行）加分发层；3 轻场景保持纯文本｜validation：三态截图（带 card/不带/抛错）+ vitest 分发用例｜success：三态可见输出、4 结构化走卡、degraded 降级可见｜fail：688 行交互壳回归→按认领卡文件清单单文件回滚

**P1-04**（batch 4）schema 双份对账哨兵测试（并入既有契约测试体系，不建新门禁）｜pre：P1-03/05｜action：前端 vitest（✓ api/ipd/ai-suggest.test.ts 同体系）+ 后端哨兵对账 4 场景｜validation：故意改一侧字段名→必红（自证能红）｜success：自证留证 + 五门禁全绿｜fail：双方不一致→以后端 JSON 为裁决源改前端

**P1-08**（batch 5）C08 收口——每张带提交按钮的卡=一次既有 /api/v1 真人端点调用+审计三件套落痕｜pre：P1-06/07｜action：提交 handler 映射既有端点（Gate 签署走 ✓ GateReviewService.sign L207）；金额/评分/系数/删除/移交只进「建议值」区；mode!=='suggest' 防御保留｜validation：HTTP 真人提交链 + audit_logs（aiRole∈7 值、无 prompt 原文）+ 负向 SQL 无 AI 系统身份写入｜success：母文件 §5.1/§5.2 checklist 全勾｜fail：红线断言红=🔴 阻断，停 Phase 2 修复

### Phase 2 — SSE 协议扩展 + R221 任务卡 + fillContext 落地

**P2-01**（batch 6）done 帧解析 card 字段（可选超集，fillPayload 兼容保留）｜pre：P1-02、P1-09（P1 合格线）｜action：改 ✓ api/ipd/ai-copilot.ts（194 行）+ ✓ ai-copilot.test.ts（补 card 透传对称断言，仿既有 fillPayload 断言形态）｜validation：vitest 四帧用例（card 缺席兼容）+ 浏览器 SSE 实流截图｜success：既有四帧 0 破坏 + card 解析绿｜fail：破坏现有对话→回退解析分支

**P2-02**（batch 7）ai-assistant.vue 双事件分发（ipd:ai-fill-payload 兼容 + 新 ipd:ai-card）｜pre：P1-09、P2-01｜action：改 ✓ ai-assistant.vue（308 行）done 处理泛化 fillPayload|cardPayload｜validation：vitest + 双事件截图；宿主监听点 grep 现查逐页回归｜success：fillPayload 宿主零回归 + card 事件到达卡片容器｜fail：宿主回归→摘除该页 card 订阅降级

**P2-03**（batch 7）fillContext 落地（§1.1 #3 断点修复）｜pre：P1-09、P2-02｜action：前端 streamCopilot 补传 pageContext（✓ ai-copilot.ts L153-154）+ action-detail fill 注册（✓ L330 锚点）；字段白名单同源 FILL_FIELD_WHITELIST｜validation：浏览器走查 fill→表单预填→人目检手动提交（C08）；审计 AI_FILL 落痕｜success：功能对照表「fillContext」行签核｜fail：白名单不齐→以 FILL_FIELD_WHITELIST 为源改前端禁扩白名单

**P2-04**（batch 7）R221 任务卡（按 §1.1 #1/#2/#7 修正定义）｜pre：P1-09、P2-01｜action：①补 ai_agent_tasks 查询端点（最小面：按 taskId 单查+按项目列表，前后端同批齐落防孤儿）；②任务卡复用 ai-cards 注册表；③通知直达复用 NotificationService 载荷；状态呈现到 result_summary 粒度不展示 prompt 原文｜validation：HTTP 发起→DB 回读 ai_agent_tasks（PENDING→SUCCEEDED）+ 浏览器四态截图（发起→进度→ai_documents GENERATED 待审→人审通过）+ 待办直达可用｜success：任一时刻从待办可直达未完成审批卡（跨设备不靠会话回放）｜fail：通知载荷不兼容→回退「待办列表手动找」并存路径

**P2-05**（batch 8）盲签隔离契约测试扩展（卡片层不得绕过 rowView 遮蔽）｜pre：P2-02/04｜action：扩展既有契约测试：卡片 data/sourceRefs 查询走 ✓ GateReviewService.rowView（L263/L269）；arbitrate/finalRuling 卡无 AI 倾向渲染｜validation：双账号浏览器走查截图 + 断言自证能红｜success：reveal 前对方判定不进任何 card data/sourceRefs｜fail：绕过遮蔽=🔴 盲签红线级停线升级

**P2-06**（batch 9）Phase 2 总验收｜pre：P2-02~05、P1-08｜action：R221 四链（主动/被动/对话/人审）卡片截图 + 审计三件套抽样 + §2 对照表全表签核 + 五门禁全过｜success：对照表无空项（owner 硬约束）｜fail：缺证据→不得报完成，缺口回挂看板下波

### Phase 3 — 决策门（不实施，只核验）

**P3-C1/C2**（batch 10）前置核验：①卡片层稳定+采纳率可观测为正（复用既有审计字段计数，不加列）；②Node sidecar 运维方案拍板 + Vue A2UI POC（风险#1，React 包 Vue 等价物未验证）｜fail：POC 不可行→Phase 3 记「永久止损」归档，禁找替代框架二次引入
**P3-C3**（batch 11）owner go/no-go 决策门：凭 C1/C2 数据决策，无论结果登记留痕；no-go 时 Phase 1/2 收益已落袋（零依赖止损设计）

**批次汇总**：1=P1-01｜2=P1-02+P1-05｜3=P1-03+P1-06｜4=P1-07+P1-04｜5=P1-08｜6=P2-01｜7=P2-02+P2-03+P2-04｜8=P2-05｜9=P2-06｜10=P3-C1+C2｜11=P3-C3

---

## 4. 红线回归检查表（执行期 DoD，全绿才可标 COMPLETE）

- **C08 五查**：提交=真人 sa-token 对既有端点（无新增写入端点）；grep ai-cards/ 无 saveFields/insert/直写；金额/评分/系数/删除/移交只进 suggest* 建议区；业务表无 AI 系统身份写入行；mode='suggest' 默认 + 防御性忽略保留
- **BR-AI-04**：每张卡片头部 Alert「AI 生成内容仅供参考」，无一豁免
- **BR-AI-05/盲签**：越权 projectId→50001；卡片 sourceRefs 走 rowView 同源；reveal 前对方判定不进 data/sourceRefs/上下文
- **审计三件套**：提交落 audit_logs 三件套齐、aiRole∈7 值（Phase 1 映射：结构化建议卡=suggestion、草稿卡=draft、对话答案卡=copilot_answer、意图=copilot_intent、流式=streaming——**7 值够用，禁止自造值**；计数需求复用 accepted 字段）；纯浏览/被拒不落审计行；prompt 原文不入库
- **R214 三查**：git diff 无新表/新门禁脚本；pom/package.json 0 新依赖；文本降级路径未删
- **三渲染铁律**：R1 schema 对账哨兵绿；R2 schema 外字段丢弃+留痕；R3 卡片数值与 sourceRefs 源表逐项对账、无 LLM 复述值兜底

## 5. 门禁衔接（预期与处置）

| 门禁 | 预期 | 红了处置 |
|---|---|---|
| pre-commit 五门禁 | 绿 | 修代码不改门禁 |
| drift-guard | 绿（卡片落 _shared/ai-cards/ 合规） | API 函数并入既有 api/ipd 文件，禁新开域文件 |
| 孤儿棘轮 check-api-contract-fe-be.mjs | Phase 1 绿（不加路由）；P2-04 新端点**前后端同批齐落** | 孤儿+1=同批补齐或回退；改 SSE 行注意 R215 采集 raw fetch 字面量 |
| SSE 形态契约 | Phase 1 不触发；Phase 2 done 帧 card=可选超集，fillPayload 断言不红 | 断言红=恢复超集语义 |
| check:type / vitest / build:antd | 绿（新增测试全绿才算） | 修代码；静态 import 注册表禁动态拼路径 |

## 6. 风险闸（开工前置锁 3 项，均已满足/随批满足）

- **#9 并行回归（P0）**→ 本次已走「智能体只读+主会话单写者」；实施批开工前看板卡声明文件清单 ✅（本计划 batch 定义即文件清单）
- **#4 schema 双份漂移（P0）**→ P1-04 对账哨兵=batch 4 与实现同批，契约先行（P1-02/05 同 batch 先定契约）
- **#8 豁免书面化（P0）**→ 已满足：log.md marker copilotkit-hypo-approve-20260927 + copilotkit-hypo-approve2-20260927 已登记（commit d482205c/44b84198）
- P1 随批：#7 采纳率埋点（复用 accepted）随 P1-08；#3 过程可见口径文档化与开发并行
- P2 不阻塞：#1 A2UI / #2 zod / #5 sidecar / #6 DynString 均 Phase 3 专属，前置门再处置

## 7. 多智能体执行纪律（本计划全程适用）

1. 智能体分工：ioedream-pm（任务图维护/批次调度）｜agency-harness（实施+现状核查）｜ioedream-qa-gatekeeper（每批 DoD 验收）｜CodeReview（P1-08/P2-05 红线批独立审查）——只读与实施分离，看板/commit 由主会话单一写入者收口
2. 认领广播：每批开工前 git log -5 + 看板 in-progress + log.md 末段三查（R177 对策 1）
3. commit 对账：commit 后立即 git show --stat HEAD 核对 message↔diff（R177 对策 2）
4. 路径预检：任务卡断言的路径/端点/表名开工时现查（§1.1 七处出入正是预检价值）
5. 三证律：每节点 validation 至少含 HTTP+DB+截图中的两类，红线批三类齐
6. log.md 登记：每 batch 完成一段（精简），状态以看板卡为准

## 8. 待拍板项（不阻塞 Phase 1）

1. 测算卡语义 A（C08 基准值→stage_actions）vs B（奖金→bonus_pools）二选一或都要（P1-01 建表时并列两行，P2 前拍板即可）
2. 采纳率统计若需独立 aiRole 值（card_render/card_accept）→ 需扩白名单+DDL 同步；默认复用 accepted 字段绕开（推荐）

## 9. 验收定义（本计划「完成」的判据）

Phase 1 完成 = P1-01~P1-08 全节点 success_condition 达成 + §4 检查表全绿 + 功能对照表 Phase 1 范围行签核；Phase 2 同理；整体「功能达成」= §2 对照表 100% 证据列非空且 owner 目标签核。任何节点只完成未验证 = PARTIAL，不得标 done。
