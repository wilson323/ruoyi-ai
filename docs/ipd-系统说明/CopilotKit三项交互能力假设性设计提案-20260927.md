# CopilotKit 三项交互能力假设性设计提案（假设 owner 豁免对话界面形态红线）

- 日期：2026-09-27
- 状态：**APPROVED — owner 2026-09-27 拍板豁免**（原话：「可以重新设计但是要确保功能达成」），豁免边界与功能约束见 §5.4
- 前提假设（已发生，2026-09-27）：owner 拍板「允许改变对话界面形态」，ZK-IPD 原型逐页复刻红线**全界面显式豁免**（AI 对话交互层 + 业务页面均可重新设计；豁免边界与功能约束见 §5.4）
- 前序结论：《CopilotKit前端融合可行性分析-20260927.md》结论「不适合现阶段引入，置信度 ~75%」。本提案是该结论的**假设性反演探索**：若形态红线豁免成立，三项能力如何设计才能既拿到交互收益、又不击穿任何业务红线。
- 边界：纯分析设计。不写业务代码、不引入依赖、不改前后端。

---

## 0. 三项能力的「可借鉴度分层」（本提案的论证主线）

对官方留档（docs/最佳实践/copilotkit/copilotkit-docs-full.md）逐能力拆解后，三项能力各由三层构成，只有最里层是 CopilotKit 专属：

| 层 | Generative UI | 多 Agent 编排 | 图中断 HITL |
|---|---|---|---|
| **①交互形态层**（纯前端展示模式） | 结构化卡片替代 Markdown 文本流 | 多角色任务流水线的可视化 | 「暂停等人 → 人审 → 恢复」的状态呈现 |
| **②协议层**（事件/数据契约） | AG-UI tool call 事件 → 卡片渲染 | agent 生命周期事件流 | `on_interrupt` 自定义事件 + resume 命令 |
| **③引擎层**（CopilotKit 专属） | Runtime + LLM 自主选择卡片（含 A2UI Dynamic） | Runtime 注册的多 agent 实例路由 | LangGraph 服务端 `interrupt()` 图状态 |

**核心结论：①层 100% 可自研借鉴（零依赖）；②层可对标借鉴（扩展自研 SSE 四帧即可覆盖 80% 场景）；③层才是必须引入 CopilotKit 的部分，且③层与本项目红线/架构的冲突也最大**（C08 vs LLM 自主 UI、R221 状态机 vs LangGraph 图状态、ADR-1 vs 纯 LLM 编排）。

因此最小落地路径（§6）按「①→②→③」分层引入，每层独立验收、独立可回退，owner 可在任一层止损。

### 0.1 官方文档的四个关键事实（设计依据，均已现查留档原文）

1. **Fixed Schema ≠ 硬编码**（L46641-46658）：schema「design once, keep on the agent side」，交付方式三种——`.json` 文件启动加载 / 源码 inline / LLM 按请求生成（catalog 仍固定）。且组件树「lives backend-side」（L46701）。→ 我们可走「后端配置表生成 schema JSON 下发」，对齐本项目 `gate_review_element` 禁硬编码先例。
2. **「A well-formed component is not a correct one」**（L45431）：模型凭已知填 props，错误数据的卡片在浏览器/截图/视频里与正确卡片**外观完全一致**。官方解法 = 用 `useAgentContext` 把页面真实数据共享给 agent，再拿渲染字段与实际持有记录核对。→ 对应我们的 BR-AI-04 风险提示与「卡片数据必须来自后端真实记录」铁律（§2.2 R3）。
3. **OSS vs Intelligence 分界**（L25562-25572）：Rich Threads（saved UI / resumable runs / realtime sync）是**商业 CopilotKit Intelligence 功能**；OSS 只有 framework-native 或 application-owned 持久化。→ 团队协同（多 PM 同会话、跨设备续审）不得依赖 Rich Threads，必须走自建持久化（我们已有 ai_agent_tasks + audit_logs + notification_events）。
4. **useInterrupt 绑 LangGraph**（L171769 起）：前端 hook 监听 agent 的 `on_interrupt` 自定义事件，`resolveInterrupt` 通过 `forwardedProps.command.resume` 恢复——中断状态的持有方是**服务端 agent 框架**。→ 我们的服务端是 R221 自研状态机（ai_agent_tasks），不是 LangGraph；若照搬 useInterrupt 语义必须引入 LangGraph = 双轨（违反 R214）。借鉴方式改为「语义等价、实现自研」（§1.4）。

---

## 1. 六阶段 × 三项能力映射矩阵

依据：《IPD全阶段AI代理执行闭环设计-20260926.md》附录 A（69 动作档位唯一权威：HUMAN_GATE 5 / AI_DIRECT 40 / AI_GENERATE 24）+ 《六阶段标准动作清单 v3》+ 《五大Gate评审要素 v1》（33 要素 / 15 否决 / G1 与 G5 双签）。

### 1.1 主矩阵（按阶段 × 能力）

图例：●=适合并推荐；○=局部适合（限定条件见备注）；✗=不适合（显式排除，见 §1.3）。

| 阶段（动作数/深管/阻断） | 代表动作 | ①Generative UI 卡片 | ②多 Agent 视图 | ③中断式 HITL |
|---|---|---|---|---|
| 概念 C01-C12（12/11/10） | C01 调研(G)、C08 基准值(深D)、C11 Charter 评审会(H) | ● 字段预填卡（C08 四项基准值）＋ 调研文档草稿审核卡（C01） | ○ C01→C02 事件唤醒链的时间线视图 | ● C11=G1 双签备料暂停点 |
| 计划 P01-P13（13/4/5） | P08 排期核对(轻D)、P13 差异化确认(G2) | ● 排期差异**对比卡片**（计划 vs 实际）＋ 差异化系数 S/B 区间试算卡 | ○ | ● P13=G2 要素判定卡片流 |
| 开发 D01-D11（11/2/2） | D05 双周评审(G3)、D06 变更(双签否决) | ○ 变更前后对比卡（脱敏字段） | ○ 连续 2 次 P0 未升级的升级链提示 | ● D05=G3（注意 G3 无否决、不阻断，卡片只提示不拦截） |
| 验证 V01-V12（12/6/7） | V06 客户验收登记、Z05/Z01 测试 | ● 测试结论汇总卡（FAR/FRR 数值卡） | ● Z 系列→G4 汇总的归集视图 | ✗ V06 人页面登记结论是人签点，不设 AI 中断 |
| 发布 L01-L08（8/7/7） | L07 GTM 就绪(G4)、L08 上市日期 | ● GTM 8 要素逐项判定卡＋遗留项跟踪卡 | ● G4 通过自动生成 LC02/LC03/K01-K04 待办的派生关系图 | ● L07=G4（3 否决项提交前门禁预检卡） |
| 生命周期 LC01-LC09（9/8/7） | LC02 复盘(G5)、LC03 奖金终算 | ● 复盘 7 要素判定卡（双签隔离视图）＋ 奖金分配**审批卡**（金额域） | ○ K01-K04 归集复核视图 | ● LC02=G5 双签；✗ LC03 distribute 终审（§1.3） |
| KPI 归集 K01-K04（4/4/0） | K01 销量、K03 NPS | ● KPI 达成率数值卡＋月度截止日提醒卡 | ● 组长录入→复核的协作视图 | ○ 仅「逾期第 1 天提醒/第 3 天升级」的定时提醒可视 |

### 1.2 按档位（execMode）的通用映射（矩阵的行间规律）

| 档位 | 数量 | ①卡片形态 | ②多 Agent 形态 | ③HITL 形态 |
|---|---|---|---|---|
| HUMAN_GATE | 5（C11/P13/D05/L07/LC02） | Gate 要素**逐项判定卡**（三态 ✅/⚠️/❌，⚠️ 强制责任人与期限表单校验）＋否决项**门禁预检卡**（提交前红灯提示） | GatePrep 备料 → 双 PM 待办 → 签署 → settle 的**异步审批链视图** | ● 核心场景：备料完成即暂停（GENERATED 态），签署/仲裁/终裁全部真人 |
| AI_GENERATE | 24 | 文档**草稿审核卡**（ GENERATED → 人审 → 挂交付物） | GenerateExecutor 产物 + AiExecReviewHook 唤醒链 | ● 人审通过 = 图恢复点（现有实现，无需新机制） |
| AI_DIRECT-LIGHT | 27 轻管动作 | 三字段**回执卡**（完成状态/日期/备注）——价值低，保持现状 | — | — |
| AI_DIRECT-DEEP | 13 深管动作 | 测算单/归集结果卡＋**交付物挂接卡**（深管证据 ≥1） | ○ 多源归集（如 C08 四项基准值）的过程视图 | ○ 仅敏感字段（金额/评分/系数）转 suggest 时出现确认卡 |

### 1.3 显式排除清单（「哪些不适合」，与适用项同等重要）

| 排除项 | 依据红线 | 说明 |
|---|---|---|
| G1/G5 **盲签的 sign 操作本身** | 盲签遮蔽红线（GateReviewService L263/269 `rowView(r, revealed)`：my 全揭示、other 按 revealed） | 卡片只能渲染**己方**判定草稿；对方结论在 reveal 前不得进入任何卡片或会话上下文。共享会话默认破坏盲签 → 必须 per-thread 隔离（§3.1） |
| `arbitrate` / `finalRuling`（仲裁/终裁） | 最小人审集：超管仅仲裁终裁介入 | 仲裁视图可以做成卡片，但**决定动作**永远真人按钮，无 AI 参与渲染建议倾向 |
| 删除域 leaderDecision / adminDecision | 最小人审集删除域 | 实体快照/影响清单可做对比卡，终审按钮真人 |
| 移动交域 accept / initiateOnBehalf / batchHandover / transferSuperAdmin | 最小人审集移交域（确认短语级） | 移交清单/资格预检/批量干跑可卡片化，确认短语输入必须原生表单 |
| 绩效金额三组件 submit / confirm / freeze / distribute | ADR-3 + R213 奖金门禁（requireConfirmedContribution） | 分配方案**试算卡**可以（§1.1 LC03 ○），终审按钮真人且走既有 409/50002 门禁 |
| C08/C09 锁定后的字段修改 | G1 规则：一经通过即冻结，修改需双签+审计 | 锁定字段在卡片上只读展示，不进预填槽位 |
| 三态判定的**打勾动作** | C08 | AI 只生成「判定草稿与依据」（现有 gate.conclusion-draft 场景），✅/⚠️/❌ 的选择是真人操作 |
| A2UI **Dynamic Schema** / MCP Apps / Open Generative UI | C08 + 后端 schema 白名单强校验 | LLM 生成 schema = AI 自己决定「有哪些字段可写」，直接击穿 FILL_FIELD_WHITELIST 与「schema 外字段一律丢弃」防线。属「不适用」而非「暂不做」 |
| Frontend Tools（agent 在浏览器跑逻辑直接写状态） | C08 方向相反 | 官方 L45366「agent 已拥有工具你只画调用」的 useFrontendTool+handler 形态不用；只用无 handler 的 useRenderTool/useComponent 渲染形态 |
| 多 PM **共享同一 thread 的会话**（Rich Threads 式） | 商业 Intelligence + 盲签 | OSS 无此能力且与盲签冲突，改用「同一业务实体、按人隔离 thread」（§3.1） |
| Agent 间自主协商/任务转移 | ADR-1（否决纯 LLM 编排）+ spec 附录 B「明确不做：Agent 多步编排扩展」 | ②能力的边界：只做**执行流水线的可视化投影**，不做 Agent 自主协作引擎 |

### 1.4 ③能力的语义映射：useInterrupt → R221 状态机

官方 useInterrupt 的三要素与我们的等价物：

| useInterrupt 机制 | 本项目等价物（已存在，自研） |
|---|---|
| 服务端 `interrupt()` 暂停图执行 | ai_agent_tasks 落 **GENERATED/PENDING_REVIEW** 态 + NotificationService 发人审待办（GatePrepExecutor / GenerateExecutor 已实现） |
| 前端 `on_interrupt` 事件 + `#interrupt` slot 渲染审批 UI | done 帧携带 fillPayload / 任务待办通知 → 宿主页面渲染审核卡片（待建，§6 Phase 2） |
| `resolveInterrupt(response)` → `command.resume` 恢复 | 人点「通过」→ 既有 `POST /{id}/ai-execute` 或领域端点（如 review）→ AiExecReviewHook afterCommit 唤醒后继（R221 Task 10 已实现） |
| 中断状态持久化（跨设备恢复） | ai_agent_tasks 行即持久化（天然 application-owned，优于 LangGraph checkpoint 在我们场景的运维成本） |

**结论：③能力我们已有 90% 语义等价物，缺口只在「前端审批 UI 的卡片形态与直达入口」**。照搬 useInterrupt 需引入 LangGraph + Node Runtime = 双轨，违反 R214，不做。

---

## 2. 人机协同交互流设计（用户视角）

### 2.1 标准流（以「C11 Charter 评审会（G1）备料」为例）

```text
[发起] 市场 PM 在项目详情页打开 AI 副驾，说「准备 G1 评审材料」
   ↓
[识别] AiCopilotService 意图分类 → 命中 gate 域 → AiExecutionTrigger 落 ai_agent_tasks
       （trigger_type=CHAT, action_code=C11, exec_mode=HUMAN_GATE 快照）
   ↓
[执行] GatePrepExecutor：judge 要素判定草稿 + 评审材料/纪要草稿 + submit 开启签署期
   ↓                                                    ┌─ meta 帧：任务标识+场景+cardType 预告
[呈现] SSE 四帧流式返回 ──────────────────────────────┼─ delta 帧：进度文本（备料中…要素 x/7）
   ↓                                                    └─ done 帧：cardPayload（见 2.2）
[目检] 副驾渲染「Gate 备料卡片组」：
       ① 要素判定草稿卡（7 要素 × AI 建议判定 + 依据摘要，来源=gate_review_element 真实数据）
       ② 否决项预检卡（5 否决项逐项红绿灯，命中项红灯 + 「不得签署通过」提示）
       ③ 遗留项登记卡（⚠️ 态强制责任人 + 关闭期限，缺一无法提交——表单校验）
   ↓
[修正] PM 在卡片上改判定倾向（此时只是本地草稿，未落库）；
       AI 建议与 PM 修改的 diff 高亮显示（对比卡片形态）
   ↓
[提交] PM 点卡片「提交签署请求」→ 前端调既有 /api/v1 端点（真人 sa-token 身份）
       → 后端 Service 写库 + 状态机守卫 preCheck
   ↓
[留痕] audit_logs 落 AI_FILL / GATE_SUBMIT（aiAssisted=true, aiModel=…, aiRole=agent_exec 或 suggestion）
       + ai_agent_tasks 翻 SUCCEEDED（result_summary，不存 prompt 原文）
   ↓
[转异步] 双 PM 收到签署待办通知 → 各自在自己的隔离视图完成盲签（§3.2）
```

**C08 铁律在卡片上的体现**：卡片的「提交」按钮 = 一次真实的 `POST /api/v1/...` 调用（真人会话身份），后端白名单校验/状态机守卫/审计门禁全链路照走。AI 在整个链路里只到「生成卡片数据」为止。

### 2.2 cardPayload 契约（done 帧泛化，替代 fillPayload 单一形态）

```jsonc
// done 帧新增 card 字段（fillPayload 保留兼容，是其超集）
{
  "card": {
    "type": "gate.precheck",        // 卡片类型 = 场景白名单子集（见 2.3）
    "version": 1,                   // schema 版本，前端注册表按 (type, version) 匹配
    "data": { /* 后端校验后的结构化数据，字段与该 type 的 schema 逐项对齐 */ },
    "sourceRefs": {                 // R3 铁律：卡片数据的后端事实源引用
      "gateId": "2099…", "elementResults": "…", "taskId": "2104…"
    }
  }
}
```

### 2.3 卡片注册表（Fixed Schema 思想的三层落地）

| 层 | 内容 | 载体 | 变更成本 |
|---|---|---|---|
| Catalog（卡片类型 + props schema） | 「Gate 判定卡有哪些槽位」 | **后端配置表下发 JSON**（对齐 gate_review_element / system_configs 禁硬编码先例）；前端 TS 侧仅声明类型骨架 | 新增卡片类型 = 前端发版 |
| 数据（具体要素/阈值/文案） | 「G1 有哪 7 个要素」 | gate_review_element 等既有配置表 | 超管后台改，零发版 |
| 渲染（Vue 组件） | 卡片外观与交互 | `views/ipd/_shared/ai-cards/<type>.vue`（drift-guard 一域一文件规则内） | — |

**三条渲染铁律（对应官方警告与本项目红线）**：
- **R1 类型骨架作者控制**：卡片槽位 schema 由我们定义（等价 zod parameters 的自研 TS interface + 后端 JSON 双份对账），LLM/AI 只能填数据不能改槽位。
- **R2 白名单双向校验**：后端生成 cardPayload 时按 type 的 schema 过滤（schema 外字段一律丢弃并记审计，对齐 FILL_FIELD_WHITELIST 既有实现）；前端渲染前再做防御性过滤。
- **R3 数据来自后端真实记录**：卡片显示的要素结果/金额/日期一律从 sourceRefs 指向的业务表读取，**不得**用 LLM 复述值兜底渲染——「well-formed ≠ correct」的工程对策。

### 2.4 审计与降级

- 每张卡片的「提交」落审计三件套（aiAssisted/aiModel/aiRole），aiRole 取值沿用 7 值白名单（草稿审核卡=draft 或 suggestion、Gate 备料=suggestion、字段预填确认=AI_FILL 路径既有值）；卡片**纯浏览不提交不落新审计**（避免噪音）。
- BR-AI-04 常驻风险提示：卡片头部固定 Alert「AI 生成内容仅供参考，请目检后手动提交」（沿用 ai-assistant.vue 既有 Alert 组件）。
- 降级路径：模型不可用/degraded=true 时，卡片退化为纯文本建议（现状 ai-suggest.vue 形态），**卡片层是增强不是依赖**。

---

## 3. 团队协同场景设计

### 3.1 前提：会话隔离模型（盲签第一）

多 PM 协同的默认形态**不是**「多人在同一个聊天室」，而是：

```text
业务实体（Gate 2104…）──┬── 市场 PM 的 thread（IpdAuthSession A）
                        ├── 研发 PM 的 thread（IpdAuthSession B）      ← 三者互不可见对方草稿
                        └── 组长/仲裁视图 thread（reveal 后的合并视图）
```

- 每个 thread 的 AI 上下文注入走 `rowView(personId)` 同源遮蔽——**AI 辅助材料复用既有遮蔽视图是 spec §5.1 红线，卡片层不得另造遮蔽逻辑**。
- 「团队可见」的部分是**结果**而非过程：判定表、纪要、遗留项清单（Gate 强制输出物 3 件套）在 reveal/settle 后对所有角色可见。
- 官方 per-thread clone（useAgent threadId → 独立 messages/state）证明该模型在 Vue SDK 可行；但我们 Phase 1/2 用自研实现（每会话独立 history，现状 8 轮上限不变）。

### 3.2 场景 A：Gate 双签异步审批

```text
GatePrepExecutor 备料完成（GENERATED，暂停点）
  → 通知双 PM（既有 NotificationService，R221 Task 12 补线后含此缺口）
  → 市场 PM 在自己的卡片视图提交签署（sign: L207，preCheckGuard L299）
  → 研发 PM 异步抵达：视图显示「对方已签（结论遮蔽中）」+ 己方判定卡
  → 双签齐 → settle；若意见相左 → 提示组长仲裁入口（arbitrate 真人）
  → 超时 → GateSignScanScheduler 催签（既有），卡片视图显示催签状态
```

卡片只做**状态呈现与直达入口**；sign/arbitrate/finalRuling 按钮全部映射到既有 Service 方法，无新增写入路径。

### 3.3 场景 B：Agent 任务分派与结果汇总（②能力的正确打开方式）

「需求分析 Agent / Gate 检查 Agent / KPI 核算 Agent」在本项目的映射 = **执行器族 + 场景白名单子集的展示层投影**，不是三个真 LLM Agent：

| 对话中的「Agent」名 | 实际实现（已有） | 卡片化后用户看到 |
|---|---|---|
| 需求分析 | AiSuggestionService 场景 project.create.suggest / demand.create.from-requirement | 立项要点卡 / 需求单草稿卡 |
| Gate 检查 | gate.precheck-checklist / gate.conclusion-draft + GatePrepExecutor | 预检清单卡 / 结论草稿卡 |
| KPI 核算 | DeepDirectExecutor（K01-K04 归集）+ BonusPoolService.compute（试算） | 达成率数值卡 / 分配试算卡 |

「协作过程可见」= ai_agent_tasks 时间线（PENDING→RUNNING→SUCCEEDED + result_summary）的流水线卡片组。**不展示 prompt/响应原文**（审计规约：只存 input_digest + result_summary）——用户看到的是「谁（哪个执行器）在做哪步、结果摘要是什么、轮到我审批什么」，逐一点开审批。

若 Phase 3 引入 CopilotKit：多 agent 实例（useAgent 多 agentId）只作为**路由壳**（把上述三类卡片归到三个 tab/头像下），决策逻辑仍在 Java 侧执行器族——满足 ADR-1「LLM 编排仅作 AI_GENERATE 档内部受限实现位」。

### 3.4 场景 C：跨设备的审批接力

Rich Threads（商业）不做。等价物：任何 PM 换设备/重登录后，从「待办列表」（notification_events）直达未完成的审批卡片——卡片数据按 sourceRefs 现拉后端，**不依赖会话历史回放**（这是 R3 铁律的副产品收益：卡片天然可重建）。

---

## 4. 与现有自研组件的关系（逐一演进路径）

| 组件/契约 | 现状（实测） | 关系 | 演进路径 |
|---|---|---|---|
| `ai-suggest.vue`（188 行，7 场景，pre-wrap 纯文本） | Markdown 文本面板 | **增强**（Phase 1 主战场） | ① 新增 CardRegistry 分发层：done 响应含 card 字段 → 渲染对应卡片组件；② 无 card 字段或渲染失败 → 回退现有文本形态（永不删除文本路径）；③ 4 个结构化场景先行（gate.precheck-checklist / gate.conclusion-draft / project.create.suggest / demand.create.from-requirement），3 个轻场景（workbench.next-step / risk-warning / project.summary.refresh）保持文本 |
| `ai-assistant.vue`（307 行，fillPayload 广播 + BR-AI-04 Alert） | 副驾会话壳 | **共存**（Phase 2 接线） | ① done 帧处理从「fillPayload 专用」泛化为「fillPayload 或 cardPayload」；② 保留 `ipd:ai-fill-payload` CustomEvent 兼容（宿主页面已监听）；③ 新增 `ipd:ai-card` 事件供卡片容器监听；④ BR-AI-04 Alert 移入卡片头部共用 |
| `api/ipd/ai-copilot.ts`（193 行，四帧 SSE） | meta/delta/done/error | **增强**（Phase 2） | 四帧结构不变；done 帧新增可选 card 字段（§2.2）；meta 帧新增 cardType 预告（前端可预挂载骨架卡）。**不引 AG-UI 协议**（Phase 3 决策点，见 §6） |
| `ai-execute`（stage-action.ts L204，POST /stage-actions/{id}/ai-execute） | 触发 R221 任务 | **共存**（Phase 2） | 返回的 taskId 接入「任务卡片」：详情页的 AI 执行按钮从「发起后失联」变为「发起 → 卡片显示进度（轮询或既有通知）→ 人审卡片直达」。后端端点零改动 |
| R221 引擎（AiExecutionEngine + 四执行器族） | 已实现（含 AiExecReviewHook afterCommit 唤醒） | **不动** | 本提案所有前端形态叠加在 R221 语义之上；执行器/状态机/守卫/审计零改动（唯一后端新增 = AiSuggestionService 结构化输出模式 + cardPayload 组装，Phase 1） |
| 7 场景白名单（AiSuggestionService SCENES L57-63） | Set.of 7 场景 | **不动语义，扩输出形态** | 白名单机制原样保留；新增「输出模式」参数（markdown | structured），structured 模式按场景配卡片 schema（后端 JSON）。新场景仍走「白名单+登记」既有纪律 |
| 审计三件套 | AI_ROLES 7 值（含 suggestion） | **不动** | 卡片提交复用既有 action（AI_FILL/AI_SUGGEST/GATE_*），不发明新 aiRole |

---

## 5. 红线合规验证清单

### 5.1 C08（AI 只建议、人目检后手动提交）

- [ ] 每张带提交按钮的卡片，提交动作 = 一次对既有 /api/v1 端点的真人身份调用（sa-token IpdAuthSession）
- [ ] 卡片层无任何直接写业务表的路径（前端无 saveFields 直调、后端卡片组装器无写权限）
- [ ] 金额/评分/系数/删除/移交类槽位永远只进「建议值」区域，确认走原生表单或既有终审端点
- [ ] 负向测试：卡片提交后业务表无 AI 系统身份写入行（除既有 AI_DIRECT 档允许的 executor 路径外）
- [ ] mode=suggest 默认值语义不变；`mode!=='suggest'` 防御性忽略逻辑保留

### 5.2 BR-AI-04 / 审计三件套

- [ ] 所有卡片头部含 AI 生成风险提示（复用既有 Alert）
- [ ] 每次卡片提交落 audit_logs 且 aiRole ∈ 7 值白名单；aiModel 非空（intent 路径 intent_match）
- [ ] 卡片纯浏览不产生新审计行（防噪音）；AI 建议被拒绝/放弃不落「假采纳」
- [ ] prompt/响应原文不入库（input_digest + result_summary 口径不变）
- [ ] BR-AI-05：越权 projectId 的卡片请求 → 50001「项目不可见」（既有负例路径回归）

### 5.3 BR-AI-05 与盲签（团队协同专项）

- [ ] G1/G5 会话中，对方判定在 reveal 前不进入任何卡片 data / sourceRefs / AI 上下文
- [ ] 卡片数据查询复用 rowView(personId) 同源 SQL（契约测试扩展：新增「卡片层不得绕过遮蔽」断言，沿用 spec §5.1「执行器直查 mapper 视为违规」同款测试形态）
- [ ] arbitrate/finalRuling 卡片无 AI 建议倾向渲染

### 5.4 ZK-IPD 一致性豁免边界（需 owner 书面拍板的豁免令）

- 豁免范围（2026-09-27 二次拍板扩大）：**全界面**——AI 副驾/建议面板内部呈现形态 + 业务页面布局/导航/表单/列表结构，ZK-IPD 逐页复刻红线整体豁免，界面可自由重新设计
- 初版边界（已被二次拍板取代，留档备查）：原仅豁免 AI 副驾/建议面板内部呈现形态，业务页面照旧与原型一致
- 登记方式：本提案拍板后，在 log.md 登记豁免决定 + 提案文件状态改 APPROVED；ZK-IPD 一致性相关文档如引用「AI 面板形态」需勘误级更新（走既有勘误登记流程）
- **拍板记录（2026-09-27）**：owner 原话「可以重新设计但是要确保功能达成」——豁免成立，并追加硬约束：**形态可自由重新设计，功能不得缺失**。功能达成口径（随二次拍板扩大到全站）：原型/49 页规格定义的全部功能保留、验收标准不降低——含 AI 面板功能（ai-suggest 7 场景建议、ai-assistant 对话、fillContext 字段预填、ai-execute 草稿→人审→确认闭环）与全站业务功能（六阶段 69 动作、五大 Gate 双签/仲裁/终裁、双 PM 协同、菜单权限、审计三件套）；重构后按功能清单逐项验收，缺一项即视为未达成。
- 范围登记说明（语义已澄清）：owner 原话「2豁免3」经二次确认解读为「第 2 条（业务页面不豁免）豁免，第 3 条（登记方式）照旧」——豁免范围据此扩大到全界面（2026-09-27 补登）。看板认领流程**不在**豁免范围（Phase 1 派单仍需认领卡+allowedPaths）。

### 5.5 R214 反双轨

- [ ] 不新建台账：卡片注册表不建新表，复用 system_configs / gate_review_element 既有配置机制
- [ ] 不新建门禁：卡片 schema 对账断言并入既有契约测试体系（vitest + 后端哨兵测试），不另立脚本
- [ ] 不引入 LangGraph/工作流引擎（③用 R221 等价物）
- [ ] 不引入 Agent 编排引擎（②用执行器族投影）
- [ ] 文本降级路径永不删除（卡片层是增强非依赖）

---

## 6. 最小落地路径（Phase 1/2/3）

### Phase 1：自研卡片化（零新依赖，可独立验收）

- **改动面**：
  - 后端：AiSuggestionService 增加 structured 输出模式（4 场景各配卡片 schema JSON + 组装器；schema 外字段丢弃+审计）；响应体新增 card 字段
  - 前端：`views/ipd/_shared/ai-cards/` 目录 + CardRegistry + 4 个卡片组件（判定卡/清单卡/草稿卡/对比卡）+ ai-suggest.vue 分发层改造
- **明确不做**：不改 SSE 帧结构（同步接口先行）、不动 R221、不动 7 场景白名单语义
- **验证**：① 负向测试——schema 外字段被丢弃且记审计；② 契约测试——cardPayload 类型骨架与前端 TS interface 对账哨兵；③ 三证律——HTTP(200+code0+card 字段) + DB(audit 行) + 浏览器截图（卡片渲染与提交链路）；④ 门禁——check:type / vitest / build:antd / drift-guard 全过
- **止损点**：若卡片采纳率低（后续可观测），回退纯文本零成本（文本路径未动）

### Phase 2：SSE 帧扩展 + R221 任务卡片 + CHAT 填表落地

- **改动面**：
  - 前端：ai-copilot.ts done 帧解析 card 字段；ai-assistant.vue 双事件分发（fillPayload/cardPayload）；R221 任务卡（GENERATED 草稿审核卡/Gate 备料卡直达入口，从待办通知跳转）
  - 后端：GatePrep/Generate 完成通知携带卡片直达参数（复用既有 NotificationService 载荷，不建新机制）
  - 落地 spec §3.5 CHAT 填表链路的 fillContext 前端注册（实测 grep 0 命中，尚未实现——本阶段一并补齐，卡片预填即其卡片化形态）
- **验证**：R221 验收剧本四链（主动/被动/对话/人审）各加卡片截图；盲签隔离视图双账号浏览器走查；审计三件套断言
- **止损点**：任务卡与通知入口并存，任一路径可用

### Phase 3：引入 @copilotkit/vue + AG-UI 端点（真正的「引入」决策点）

- **前置条件（全部满足才启动）**：① Phase 1/2 卡片层稳定运行且采纳率可观测为正；② owner 确认豁免令书面化（§5.4，**已满足 2026-09-27**）；③ Node sidecar 运维方案拍板（官方无 Java Runtime，前序报告已列 8 项适配改造）；④ Vue 侧 A2UI/多 agent 能力 POC 验证（React 包 @copilotkit/a2ui-renderer 的 Vue 等价物官方文档未见，**未验证**）
- **改动面**：CopilotKitProvider + useRenderTool 迁移既有卡片（卡片组件本身可复用，只换注册方式）+ 多 agentId 视图路由 + AG-UI 端点 sidecar（鉴权透传/包络转换/四帧映射，前序报告 §4 已列清单）
- **验证**：盲签 per-thread 契约测试（隔离视图互不可见）；sa-token 透传安全审查（禁 forwardedProps 传凭据）；C08 红线回归（agent 不得获任何 handler 型工具）
- **明确不依赖**：Rich Threads / User Memories / Automatic Learning / Channels（商业 Intelligence 功能，全部用自建等价物）

---

## 7. 风险与未决项

| # | 风险/未决 | 等级 | 处置 |
|---|---|---|---|
| 1 | **Vue 侧 A2UI renderer 未验证**：官方 Fixed Schema 示例用 React 包 @copilotkit/a2ui-renderer；Vue 侧文档只见 :a2ui 入口未见 catalog 工具链 | 高（Phase 3） | Phase 3 前置 POC 项；Phase 1/2 不受影响（自研卡片不走 A2UI） |
| 2 | zod 依赖：CopilotKit parameters 用 zod，前端未引入；且前序报告发现 zod ^3.25.75 与 catalog ^3.25.67 漂移 | 中（Phase 3） | Phase 3 依赖评估项；自研路径用 TS interface + 后端 JSON 双份对账替代 |
| 3 | 「协作过程可见」与「不存原文」的张力：多 Agent 过程展示只能到 result_summary 粒度，用户若期待对话级过程回放会落空 | 中 | 产品预期管理：卡片组呈现状态机时间线；文档明示口径 |
| 4 | 卡片 schema 双份（后端 JSON + 前端 TS）漂移风险 | 中 | 对账哨兵测试（Phase 1 验证项②），复用既有契约测试形态不建新门禁 |
| 5 | Node sidecar 运维成本（部署/监控/升级），前序报告 8 项适配改造工作量 | 高（Phase 3） | 已列 Phase 3 前置条件③；止损设计 = Phase 1/2 全部收益不依赖 sidecar |
| 6 | DynString union 陷阱（Vue 等价形态未知）：React 侧绑定型 prop 未声明 union 会运行时崩溃且报错不指向 schema | 低（仅 Phase 3 A2UI 路径） | 自研路径无此问题（TS 静态检查覆盖） |
| 7 | 卡片采纳率无基线：立项后无法量化「卡片 vs 文本」的体验收益 | 中 | Phase 1 上线即在审计中登记 card 渲染/提交计数（复用既有字段不加列），Phase 3 决策用数据 |
| 8 | ~~豁免令边界解释权~~ **已消解（2026-09-27 二次拍板）**：豁免扩至全界面后「对话交互层」范围争议不复存在；新边界变为「功能达成」验收争议 | 低 | §5.4 书面豁免令 + log.md 登记；功能争议按 49 页规格功能清单逐项对照 |
| 9 | 688 行交互壳的回归风险：ai-suggest/ai-assistant 改造期间兄弟会话并行修改同文件 | 中 | 遵循 OPS-09 单一写入者；改造卡认领时声明文件清单 |
| 10 | ~~本提案整体前提未发生~~ **已消解（2026-09-27）**：owner 已拍板豁免（原话「可以重新设计但是要确保功能达成」）；前序「不引入 SDK」结论仍现行（Phase 1/2 零依赖路线不变） | — | 豁免范围已扩大到全界面（2026-09-27 二次拍板澄清「2豁免3」= 第 2 条豁免）；看板认领流程不豁免 |

---

## 8. 证据索引（关键断言 → 一手来源）

| 断言 | 来源（均已现查） |
|---|---|
| 69 动作档位 H5/D40/G24 | IPD全阶段AI代理执行闭环设计-20260926.md 附录 A |
| 四执行器族语义 / 最小人审集 8 类 / 盲签遮蔽红线 | 同上 §2/§5.1 |
| ADR-1 否决纯 LLM 编排；附录 B 明确不做 Agent 多步编排 | 同上 ADR-1/附录 B |
| 7 场景白名单原文 | AiSuggestionService.java L57-63（Set.of 七场景）+ L262-279（指令模板） |
| 盲签 rowView(my=true, other=revealed) | GateReviewService.java L263/L269/L339 |
| sign/arbitrate/finalRuling + preCheckGuard | GateReviewService.java L207/L587/L628/L299 |
| 奖金状态机 DRAFT→CONFIRMED→DISTRIBUTED + 门禁 | BonusPoolService.java L57 注释 + R213（requireConfirmedContribution） |
| 双 PM 自评 SUBMITTED→知会组长终裁 | ContributionService.java R221 通知缺口注释（实测 L70 附近） |
| Gate 33 要素/15 否决/2 双签/三态判定/⚠️必填/禁硬编码 | 五大Gate评审要素_v1.md + 开发实现要求 6 条 |
| 六阶段分布 12/13/11/12/8/9/4，深 42 轻 27 阻断 38 | 六阶段标准动作清单_v3.md 阶段统计表 |
| Fixed Schema 三种交付 + lives backend-side + DynString 陷阱 | copilotkit-docs-full.md L46641-46658/L46701/L46751 |
| 「A well-formed component is not a correct one」 | 同上 L45431 |
| Rich Threads 属商业 Intelligence | 同上 L25562-25572 |
| useInterrupt 绑 LangGraph / on_interrupt / command.resume | 同上 L171769-171957 |
| useAgent 多 agent + per-thread clone | 同上 L169415-169450 |
| OSS 无 Java Runtime / 8 项适配改造 | CopilotKit前端融合可行性分析-20260927.md（前序报告） |
| 自研三件 688 行（193+307+188） | wc -l 实测（前序轮） |
| fillContext 前端未落地 | 本轮 grep ai-assistant.vue fillContext|FILL_FIELD_WHITELIST|schema → 0 命中 |
| 一域一文件 / drift-guard 规则 | 前端架构规约-20260906.md §2/§5 |
| 审计三件套 + aiRole 7 值 + 不存原文 | AI-审计三件套规约-20260923.md（全文）+ log.md 09-27 AI_ROLES 补 suggestion 段 |

---

## 9. 结论

1. **三项能力的收益主体（交互形态层）可以在不引入 CopilotKit 的前提下落地**：Phase 1/2 自研卡片化覆盖 Generative UI 的 Controlled 档与 HITL 的呈现层，改动面收敛在前端 _shared + AiSuggestionService 输出模式，零新依赖、零后端契约变更、零红线冲突。
2. **真正需要 CopilotKit 的只剩 Phase 3**（LLM 自主选卡/多 agent 路由/AG-UI 协议），而该层恰是与 C08/ADR-1/R214 冲突最集中、成本最高的部分——分层设计让 owner 可以拿走 80% 体验收益后在 Phase 3 门前永久止损。
3. **团队协同的正确模型是「同一实体、按人隔离」而非「共享会话」**：盲签红线决定这一点；Rich Threads 商业依赖的排除恰好与该模型自洽。
4. owner 已于 2026-09-27 拍板豁免（约束：形态可重新设计、功能必须达成）；Phase 1 派单需先认领看板卡并确认 allowedPaths 后实施。
