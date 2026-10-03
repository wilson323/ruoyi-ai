> 本轮研究校准版本1.1.0（2026-10-02）。当前官方中文索引四组137篇文字正文已核读：SDK23/Service50/集成60/Harness博客4，共997,764字节。逐页URL/SHA/来源与边界见 [读取清单](AgentScope官方资料读取清单-20261002.json)。先分批读本地官方镜像，再逐页对当前线上文字完整比较并补读差异；非仅下载/哈希证明阅读。图片、视频和所有站外链接未逐项分析。线上文档与本地源码镜像API/默认值存在漂移，应用以项目2.0.3实际JAR和真实运行核验；下文旧默认值比较不能直接作为当前运行结论。实施编号、启动门和互斥交接只认 [六计划总览v1.1.0](AgentScope官方化-六计划总览-20261002.md)。

# AgentScope 官方最佳实践提炼与本项目适用性

> 日期：2026-10-02
> 范围：AgentScope Java 官方 `docs/v2/zh/blogs/`（重点 `how-to-build-agent-harness/` 4 篇）+ `docs/harness/` 10 篇，回源码验证
> 读者：本项目技术负责人 + 后续规范撰写者
> 纪律声明：本文只做**提炼与判定**，不含完整规范（完整规范由后续计划产出）。所有关键论断分「📄 文档声称」与「✅ 源码实证」两栏；读不到的明确写「未读到」。

---

## 零、本次阅读覆盖度（诚实交代）

**已全部读完（12 篇 + 目录索引）**

| 文件 | 状态 |
|---|---|
| `zh/blogs/how-to-build-agent-harness/01-patterns.md` | ✅ 全文读完 |
| `zh/blogs/how-to-build-agent-harness/02-task-orchestration.md` | ✅ 全文读完 |
| `zh/blogs/how-to-build-agent-harness/03-context-and-state.md` | ✅ 全文读完 |
| `zh/blogs/how-to-build-agent-harness/04-controlled-execution.md` | ✅ 全文读完 |
| `zh/blogs/agentscope-v2-explained.md` | ✅ 全文读完 |
| `zh/blogs/agentscope-v2-release.md` | ✅ 全文读完 |
| `zh/blogs/agentscope-v2-coding-agent.md` | ✅ 全文读完 |
| `zh/blogs/agentscope-v1-harness.md` | ✅ 全文读完 |
| `zh/blogs/agentscope-v1-builder.md` | ✅ 全文读完 |
| `zh/blogs/agentscope-service-release.md` | ✅ 全文读完 |
| `zh/blogs/agentscope-service-release-tech.md` | ✅ 全文读完 |
| `zh/blogs/managed-agents-agentscope-rumtime.md` | ✅ 全文读完 |
| `zh/blogs/session-execution-control.md` | ✅ 全文读完 |
| `zh/blogs/jev-structured-decision-for-agents.md` | ✅ 全文读完 |
| `zh/blogs/usecases/finxscope.md` | ✅ 全文读完 |
| `zh/blogs/usecases/logistics.md` | ✅ 全文读完 |
| `zh/blogs/usecases/aidc-logistics.md` | ⚠️ **与 logistics.md 实测仅 58 行差异**（`diff logistics.md aidc-logistics.md \| wc -l` = 58），差异全部是 front matter 标题与代码块缩进。**未逐行重读，按等价处理**，这是本次唯一未逐字读完的指定篇目 |
| `zh/blogs/usecases/index.md` | ✅ 读完（14 行目录） |
| `zh/docs/harness/` 10 篇（architecture / channel / compaction / filesystem / memory / plan-mode / sandbox / skill / subagent / workspace） | ✅ 全部全文读完 |
| `en/docs/harness/` 对应 10 篇 | ✅ **已全文读完**（补读于第二轮）。逐行对照，结论见 §五 |

**未读**

- `en/blogs/` 9 篇 —— **未读**。`en/blogs/` 目录**不存在** `how-to-build-agent-harness/`（已 `ls` 确认），即中英不对称，该系列目前只有中文。
- `en/blogs/usecases/` —— 未读。
- `docs/v2/zh/docs/building-blocks/`（context / agent / message-and-event / tool / permission）等非 harness 目录、`integration/` 目录 —— 不在本次指定范围，**未读**。
- 各 blog 中的图片/SVG 架构图 —— 未读（纯图片）。
- blog 中引用的 `agentscope-examples/`、`agentscope-service/` 源码 —— 未读，本文所有源码实证只针对 `agentscope-harness` 与 `agentscope-core` 的主仓代码。

### 0.1 两轮补读说明

第一轮交付时 §五「中英一致性」仅基于 2 处定点比对，样本过窄。第二轮补读 `en/docs/harness/` 全部 10 篇逐行对照后，§五 已重写，并因此**新增 2 条矛盾（C7 / C8）**——均由源码复核确认。

---

## 一、`how-to-build-agent-harness` 讲的方法论（大白话版）

### 1.1 一句话

**给模型套一个"工程外壳"，让它的每一次判断都被翻译成确定性的、可审计、可恢复、可验收的动作——而不是把整套流程写进 prompt 里祈祷模型听话。**

### 1.2 核心论点拆解

系列 4 篇的骨架是「三类工程契约」：

| 篇目 | 契约类 | 一句话 |
|---|---|---|
| 01 架构范式 | — | 划清 Model / Harness / Runtime / Sandbox / Platform 五层责任边界；给 `HarnessAgent` 四种运行形态（本地工作区 → 嵌入式 → 长任务 Worker → 平台化） |
| 02 任务编排 | **任务契约** | Prepare→Model→Act→Observe→Verify 五阶段稳定 Loop；显式状态机 + 多维预算；Plan/Todo 外部化 + 阶段门禁；Subagent 受控委派；异步 Continuation；**完成由 Verifier 依据环境事实判定，不接受"我已完成"** |
| 03 上下文状态 | **信息契约** | Context Builder 每次调用前动态编译 System/Task Context，并产出 **Context Manifest**（模型究竟看见了什么、为什么、为什么没看见别的）；Commit/Compact/Rebuild/Validate 四步生命周期；Call / Session / Task 三边界分离；Event Log / Snapshot / Checkpoint 三种状态表示不可互替；四类 Memory + Knowledge 与 Memory 的治理分权；Skill 渐进式披露 + 版本化发布 |
| 04 受控执行 | **行动契约** | 统一 Action Plane（Schema→身份→Policy(ALLOW/DENY/ASK)→HITL→执行→Observation→State Patch）；**「模型看得见 / 系统注册得了 / 当前用户获授权」是三件不同的事**；Environment Contract 声明式兑现；Preview—Approve—Commit—Verify 模式；事件语义 8 类；Trace 因果链 + Evaluation Harness 三层评测 + Trace→Harness Patch 闭环 |

**方法论真正的落点**（01 §1.1.2 + 03 §3.6.3 + 04 §4.6.2 三处反复强调）：

1. **先定 Agent Contract，再选框架、才开工具。** 契约回答：为谁工作 / 输入输出 / 允许影响什么 / 什么动作必须拒绝或审批 / **什么证据能证明完成**。
2. **把模型判断与确定性逻辑分离。** 稳定可编码的 → 脚本/工具/策略/中间件；需要权衡取舍的 → 留在 Loop。
3. **状态、权限、验证三件事必须落在代码里，不落在 prompt 里。** 01 §1.2.2 的原话最直白：「Prompt 可以要求模型『先规划再修改』，但只有权限模式、工具白名单、持久状态与 HITL 共同生效，系统才真正具备『计划获批前不可写』的约束。」
4. **模型、Prompt、Skill、权限策略、Sandbox 镜像、评估基线同属一个发布单元。** 否则「代码已回退但 Skill 还是新版」。
5. **Context 要可解释。** 存 Context Manifest 而不是只存拼接后的 prompt 文本。

### 1.3 📄 vs ✅ 方法论主张的源码交叉验证

| # | 📄 文档声称 | ✅ 源码实证 | 判定 |
|---|---|---|---|
| M1 | 「Harness 是 `ReActAgent` 的一层薄包装」（architecture.md:7） | `HarnessAgent.java:169` `public class HarnessAgent implements Agent, AutoCloseable` + `:173` `private final ReActAgent delegate;` —— **组合/委托式，不是继承** | ✅ 一致（"薄包装"指形态，源码是委托） |
| M2 | 「能力是叠加在循环关键时机上，不是改写循环」（architecture.md:17） | `io.agentscope.harness.agent.middleware/` 实际 19 个 middleware 类：AgentTrace / AsyncTool / AtPathExpansion / Compaction / DynamicSubagents / HarnessRuntime / HarnessSkill / Inbox / MemoryFlush / MemoryMaintenance / PlanMode / SandboxLifecycle / SkillCurator / SkillUsage / SubagentEntry / Subagents / Teams / ToolResultEviction / Transcript / WorkspaceContext | ✅ 一致 |
| M3 | 「用户 `.middleware(...)` 跑在所有 Harness 内置之前」（architecture.md:28） | `HarnessAgent.java:1613-1619` 用户 middleware 在 Builder 配置期就入列；内置的在 `build()` L2533-2930 追加 → 用户先 | ✅ 一致 |
| M4 | 「Task 与 Session 必须分离；Agent Session 负责推理连续性，业务 Task 负责目标与验收」（01 §1.3.1） | 源码把 `AgentState` 寻址固定为 `(userId, sessionId)`（`HarnessAgent.java:1147-1154`），**无 Task 概念** —— 官方运行时**不提供**业务 Task 状态机 | ⚠️ blog 侧是**方法论要求**，不是 SDK 能力。**Task 状态机必须由应用侧自建** |
| M5 | 「Verifier 由 Harness 承担」（04 §2.6.4） | `middleware/` 19 类中**无 Verifier / CompletionGate** | ⚠️ 同上，**Verifier 必须自建** |
| M6 | 「Context Manifest 由 Context Builder 产出」（03 §3.1.4） | 源码有 `WorkspaceContextMiddleware` 负责 system prompt 拼装，但**无 Manifest 落盘结构** | ⚠️ **无官方 Context Manifest** |
| M7 | 「Evaluation Harness」（04 §4.6.2） | 主仓**无** evaluation 模块 | ⚠️ **需自建** |

> **小结（决定本项目工作量估算的关键）**：blog 描述的是**目标架构**，`HarnessAgent` 提供的是**其中 7 块积木**（Loop / Context 拼装 / State 持久化 / Workspace / Tool 治理 / Plan+Todo / Subagent+Skill+Sandbox）。**Task 状态机、Verifier、Context Manifest、Evaluation、Trace 因果链——这 5 块官方主仓没有，必须本项目自建或外接。**

---

## 二、逐条对照：官方建议 vs 本项目现状

### 2.1 本项目装配面事实（已核代码）

| 装配点 | 文件 | 行数 | 定位 |
|---|---|---|---|
| A. 聊天内核 | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/AgentScopeChatKernel.java` | 483 | 通用对话，`streamEvents` |
| B. IPD 项目智能体 | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java` | 568 | IPD 业务域，**治理最重** |
| C. 编程助手 | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/impl/CodingServiceImpl.java` | 211 | 6 工具受控工作区 |
| D. PoC | `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/poc/kernel/PocKernelSupport.java` | 107 | ⚠️ **在 `src/main` 而非 `src/test`** |

SDK 版本 `pom.xml:16` → `agentscope.version = 2.0.3`。
治理契约：`org.ruoyi.service.coding.harness.{model,tool}` = **13 文件 609 行**（已 `wc -l` 核过，与任务书数字一致）。

### 2.2 逐条差异（13 条）

| # | 维度 | 📄 官方建议 | ✅ 本项目现状（已核代码） | 差异判定 |
|---|---|---|---|---|
| D1 | **装配基线统一** | 01 §1.5.3「多 Agent 差异应体现在任务契约、工作区资产、工具权限、Subagent 规格，**而不是复制整套运行框架**」；应「在相同 Builder 基线之上选择」 | 4 个装配点**各自手写 builder**，无共享基线。**第三轮已量化（`grep -c` 逐项计数）**：`.maxIters` A=0 / B=10 / C=1；`.skillsEnabled(false)` A=0 / B=1 / C=1；`.disableSessionPersistence` A=1 / B=0 / C=0（**A 面反而独有**）；`.modelExecutionConfig`/`.toolExecutionConfig` 三面各 1（唯一真正共有的项）；`.toolResultEviction` **A=B=C=0**（三面全无，见 P2） | ❌ **实质违反**。三处 builder 配置已实际漂移，且漂移是**双向的**（A 缺两项、B 缺一项），不是简单的"A 比 B 弱" |
| D2 | **Agent Contract** | 01 §1.1.2 第一项构建对象就是 Agent Contract（角色 / 输入输出 / 成功标准 / 允许与禁止） | 无 agent 级契约文件。G-01~G-11 硬约束散在 `CLAUDE.md` / `docs/ipd-系统说明/外部资源/`，**不与 agent 版本绑定** | ❌ **缺失** |
| D3 | **Task 状态机** | 02 §2.1.2 十态显式机（CREATED/RUNNING/WAITING_INPUT/WAITING_APPROVAL/WAITING_EVENT/PAUSED/VERIFYING/COMPLETED/FAILED/CANCELLED） | 源码无 Task 概念（M4）。本项目用 `ProjectAgentEventSink` + `runId` + `HarnessRunStatus` 近似，**WAITING_* / VERIFYING 两族缺失** | ⚠️ **部分覆盖，业务状态在 IPD 表里而非 harness 层** |
| D4 | **Verifier / 完成门禁** | 04 §2.6.2 五级验证（结构/环境/确定性/独立模型/人工）；「模型只能提出完成申请，Harness 才能提交完成状态」 | **无 Verifier**。G-05 五 Gate 双签在业务 Service 层，不在 harness 层 | ❌ **缺失**（但已有业务侧替代物） |
| D5 | **多维预算** | 02 §2.1.2「步骤数、总时长、Token 与费用、工具调用次数、子任务并发数、高风险动作次数都应进入预算」；耗尽要产生明确终态 + 未完成清单，**不能悄然截断** | B、C 有 `.maxIters`（步数）+ `DeadlineMiddleware`（时长）。**无 Token/费用预算、无高风险动作次数、无预算耗尽终态** | ⚠️ **2/6 维** |
| D6 | **Plan Mode** | 02 §2.2.3 / 04 §4.4.2：大改前固化「只读探索→写计划→人确认→执行」 | **未开**。IPD 69 动作是确定性业务流，阶段门禁由业务表承担 | 🚫 **判定不适用**（理由充分，见 §三 P4） |
| D7 | **Sandbox / Environment Contract** | 04 §4.3：Environment Contract 9 项（image/挂载/网络/Secret/工具/配额/超时/快照/审计） | `disableShellTool()` + `disableFilesystemTools()` 显式关闭执行面；C 走「受控工作目录 + WorkspaceGuard」而非沙箱 | 🚫 **判定不需要**（无代码执行面，见 §三 P5） |
| D8 | **Memory** | 03 §3.4 四类 Memory（Working/Episodic/Semantic/Procedural）+ Knowledge 分权 | `disableMemoryHooks()` + `disableMemoryTools()` 全关。知识走 RAG（`ProjectKnowledgeRetriever` / 向量 + 全文） | 🚫 **判定不适用但须登记理由**（见 §三 P8） |
| D9 | **Skill 版本治理** | 03 §3.5.4：Skill Registry 记录 owner/作用域/版本/依赖/权限/评估/发布日期；「线上 Trace 必须能定位到具体 Skill 版本」 | `FrozenProjectAgentSkills` 用 `ClasspathSkillRepository("ipd-skills")` + **SHA-256 逐文件 pin + version + content 全量校验**，不符即 `IllegalStateException` | ✅ **本项目强于官方**。这是现有资产，**不要在改造中丢掉** |
| D10 | **Skill 装配方式** | 官方 4 层：projectGlobal < skillRepository < workspace < userId | B 走 `ClasspathSkillRepository` 编程式 + `disableDynamicSkills()` + `disableDefaultWorkspaceSkills()` + `skillsEnabled(false)`，**每次 run 冻结快照** | ✅ **比官方更严**（动态合成都关掉了）。判定：保留 |
| D11 | **Context Manifest** | 03 §3.1.4：每次模型调用产出 Manifest（source/scope/version/trust_level/permission_basis/selected_reason/token_count/transform/content_hash） | **无**。system prompt 由 `sysPrompt(...)` 字符串 + `AGENTS.md` 拼 | ❌ **缺失**（可观测性债） |
| D12 | **Trace → Outcome → Evaluation** | 04 §4.6：三层评测（单步/轨迹/最终结果）+ Trace→Harness Patch 闭环；「不应从单条失败直接改 Prompt」 | 有 `AgentTraceMiddleware`（B 已 `enableAgentTracingLog(false)` 关闭）+ `auditHook` + `KernelToolCallTrace` + `HarnessToolEffectLedger`。**无跨版本 Evaluation Harness** | ⚠️ **有轨迹无评测** |
| D13 | **Permission ALLOW/DENY/ASK** | 04 §4.4.1 三态 + 「ASK 不是默认兜底」 | 治理契约有 `ToolPolicyEngine` / `PolicyDecision` / `ToolPolicyEvaluation` / `HarnessPermissionMode.READ_ONLY`（4 处硬编码 READ_ONLY）。`OwnershipGuardedTool` 逐次 `checkPermissions` 收口 | ✅ **形态正确，但当前只有 READ_ONLY 二值**，未见 DENY/ASK 分支实际启用 |

### 2.3 差异汇总

- ❌ **实质违反 / 缺失 4 条**：D1 装配基线漂移、D2 Agent Contract、D4 Verifier、D11 Context Manifest
- ⚠️ **部分覆盖 4 条**：D3 Task 状态机、D5 多维预算、D12 Evaluation、D13 权限三态
- ✅ **本项目强于官方 2 条**：D9 Skill SHA pin、D10 冻结装配
- 🚫 **判定不适用/不需要 3 条**：D6 Plan Mode、D7 Sandbox、D8 Memory

---

## 三、可应用最佳实践清单（逐条适用性判定）

图例：🟢 直接适用 ｜ 🟡 需改造 ｜ 🔴 不适用 ｜ ⚪ 需 owner 拍板

| ID | 最佳实践（出处） | 判定 | 理由 | 落到哪个文件 / 门禁 |
|---|---|---|---|---|
| **P1** | **统一 Builder 基线**：抽出单一 `HarnessAgentBaseline`（一个 `HarnessAgent.Builder` 工厂方法），4 个装配点在其上叠加各自差异（01 §1.5.3） | 🟢 | 官方明确要求；本项目 4 处已实际漂移；改动面小、收益立竿见影 | 新建 `org.ruoyi.service.coding.harness.HarnessAgentBaseline`（或 `ipd/agent/kernel` 内）；A/B/C/D 四处改为调用它。**门禁**：新增静态检查「4 处 builder 必须经由基线」 |
| **P2** | **显式声明 `toolResultEviction`**（03 §3.2.2「让大结果退出窗口而不退出任务」） | 🟢 | 源码证实**默认已开**（§四 C1）。**第三轮已量化确认**：`.toolResultEviction` 在 A/B/C 三个 builder 中出现次数 **均为 0**，即完全依赖隐式默认。官方博客也把「默认启用」当既有事实 | A/B/C/D 四处 builder 补 `.toolResultEviction(ToolResultEvictionConfig.defaults())`，并在注释里钉住「默认已开，显式声明防默认漂移」——与既有 `.compaction(...)` 注释同一范式 |
| **P3** | **Agent Contract 文档化**（01 §1.1.2） | 🟢 | 本项目 G-01~G-11 散落各处，agent 装配处无法反查行为边界 | 每个装配点一份契约：`docs/ipd-系统说明/harness-contracts/<agentId>.md`（角色/输入/输出/完成证据/禁止动作）。**门禁**：`scripts/check-harness-contract.sh` |
| **P4** | **Plan Mode**（02 §2.2.3） | 🔴 | IPD 69 动作路径已在业务表里确定性建模，阶段门禁由 G-05 五 Gate 双签承担；再叠一层 Plan Mode 会造成**双轨状态机**——正是 owner 明令禁止的 | 不开。理由写入 P3 契约的「不适用项」节，避免后续 agent 反复提议 |
| **P5** | **Sandbox / Environment Contract**（04 §4.3） | 🔴 | 已 `disableShellTool` + `disableFilesystemTools`，**执行面为零**；C 面的「受控工作目录」有 `WorkspaceGuard` 且工具是自研受控 6 件套，非通用 shell | 不开。**但**：C 面若未来放开任何命令执行，**必须**先补 Environment Contract（这是本项目最接近红线的一处） |
| **P6** | **Verifier / 完成门禁**（04 §2.6.2） | 🟡 | 官方主仓无 Verifier（M5），必须自建。但本项目 G-05 五 Gate 双签已是业务级完成门禁 | **不做通用 Verifier 框架**；改为：在 P3 契约里为每个 agent 声明「完成证据是哪几个字段」，由现有 `AuditLogService` / Gate 校验收口。**不新建平行门禁** |
| **P7** | **多维预算补齐**（02 §2.1.2）：至少补 Token 预算 + 高风险动作次数 | 🟡 | 现有 `.maxIters` + `DeadlineMiddleware` 已覆盖 2/6 维 | 在 B 面 `DeadlineMiddleware` 旁加 `TokenBudget` 计数（读 `AgentEvent` 的 usage），超阈值**产生明确失败终态**而非静默截断。⚠️ 注意：`interrupt` 语义要照 `session-execution-control.md` 的 cancel/interrupt 区分做 |
| **P8** | **Memory / Knowledge 分权**（03 §3.4.3） | 🔴 | 多租户 IPD 业务系统，用户事实存在业务表不在对话里；`disableMemoryHooks()` 是**正确**决策 | 保持关闭。理由登记入 P3 契约。⚠️ 风险登记：关闭后跨会话"记住这个项目"只能靠 RAG，**需确认 RAG 召回覆盖对话历史**——这是当前盲区 |
| **P9** | **Context Manifest**（03 §3.1.4） | 🟡 | 官方无实现（M6），自建成本不低。但对「69 动作 + 5 Gate」的审计要求价值高 | **最小可行版**：只记 6 字段（agent 版本 / sysPrompt hash / AGENTS.md hash / skill 快照 hash / tool 名单 / token 估算），随 `runId` 落审计表。**不做**完整 9 字段 |
| **P10** | **Evaluation Harness 三层评测**（04 §4.6.2） | ⚪ | 「绝对进化是伪命题，只有相对进化可执行」（logistics usecase §5.3.2 与 blog 观点一致） | 需 owner 拍板：是否建 `evals/` 回归集。**建议先只做「轨迹层」**（越权/重复探索/预算超限三类规则），不引入模型评分 |
| **P11** | **工具精选 > 工具数量**（coding-agent blog §「工具精选比工具数量重要」；Stripe Minions ~500 → Open SWE ~15） | 🟢 | 本项目已做得很好：B 面有 `spec.toolIds()` 白名单 + 构建后 `exposes unselected tools` 断言 | **保持**。把 B 面的 `if (!toolIds.containsAll(exposed)) throw` 断言**复制到 A/C/D 三处**——这是本项目已有的好范式，只是没推广 |
| **P12** | **PoC 装配面移出 src/main**（本项目自创，非 blog） | 🟢 | `PocKernelSupport` 在 `src/main/java/org/ruoyi/chat/poc/kernel/`，随主 jar 打包 | 迁到 `src/test/java` 或明确标注 `@Profile("poc")`。**门禁**：主门禁加一条 |
| **P13** | **Skill 保持冻结 + SHA pin**（03 §3.5.4） | ✅ 保持 | D9/D10：本项目已强于官方 | **改造时红线**：任何"简化装配"改动不得摘掉 `FrozenProjectAgentSkills` 的 SHA 校验 |
| **P14** | **Jev 结构化决策**（jev blog）：模型路由 / 工具筛选 / 风险守卫三中间件 | 🔴 | 需外部 TypeSafe AI 服务（`api.typesafe.ai` + API Key），本项目是内网私有部署 + 已有多模型路由 | 不引入。**但其 `failOpen` 降级思想值得借用**：现有治理链任一环不可用时的降级策略应显式声明 |
| **P15** | **草稿/发布双轨 + 乐观锁全覆盖 + 增量持久化**（logistics usecase §5.1.5 七条设计哲学） | 🟡 | 本项目业务表已有乐观锁；agent 定义层无版本概念 | 待 P3 契约 + P10 评测落地后自然带出。**当前不动** |
| **P16** | **灰度路由用确定性分桶**（logistics usecase §5.6.3：`ThreadLocalRandom` → `hash(workNo) % 100`） | 🔴 **已核，不适用** | 第三轮排查结论：**全仓不存在任何灰度/分桶逻辑**。`grep -rln "灰度"` 在 `ruoyi-modules/` + `ruoyi-common/` + 配置 + SQL 的非文档命中为 **0**；Agent 装配面 `percent`/`bucket`/`hashCode()%`/`route` 全为 **0 命中**。全仓 `ThreadLocalRandom` / `SecureRandom` 的 6 处命中均为测试 ID 生成、导入临时名、确认令牌、需求随机码，**与灰度分桶无关** | **不引入，也不必修**。若将来引入灰度，直接按确定性分桶起步，不要先做随机版 |
| **P17** | **Session 与 Turn 时长分离**（service-release-tech §「几个值得提前避开的实现误区」第 3 条） | 🟡 | 本项目有 `ProjectAgentEventSink` + runId | 检查 Dashboard/审计是否有「Session 存活墙钟 = 活跃耗时」的度量混淆 |
| **P18** | **append-only 事件为唯一真相源，Preview 流不是**（service-release-tech §误区 1） | 🟡 | 本项目有 `HarnessEventOutboxService`（在 `.codex` 归档中）与 `ProjectAgentEventSink` | 核实现网事件是否 append-only 且有单调序号；若无，重连续传不可靠 |
| **P19** | **Preview—Approve—Commit—Verify**（04 §4.4.2） | 🟡 | G-05 五 Gate 双签已覆盖「Approve」；**「Verify 查询真实系统状态」与「Commit 用幂等键」需核** | 检查 IPD 写操作是否带幂等键 |
| **P20** | **agent_spawn / subagent 委派**（02 §2.3） | 🔴 | `disableSubagents()` + `disableDynamicSubagents()` 全关 | 保持关闭。IPD 69 动作走确定性服务调用，不走 LLM 委派 |

---

## 四、📄 文档声称 vs ✅ 源码实证：矛盾清单（共 7 条 + 1 张一致性速查表）

> 全部经源码核对，给出 `文件:行号`。**这是本文重点章节。**

### C1（★ 最重要）**上下文压缩与大结果卸载的默认开关——harness 文档说「默认全关」，源码是「默认全开」**

| 栏 | 内容 |
|---|---|
| 📄 文档声称 A | `zh/docs/harness/compaction.md:16`「`HarnessAgent` 内置了一整套压缩链路，**默认是关的**，按需 `.compaction(...)` 或 `.toolResultEviction(...)` 开启」；`:27`「四套策略**正交，可以任意组合，默认全部不开**」 |
| 📄 文档声称 A' | `en/docs/harness/compaction.md` 同源同错（未逐行读，但同批翻译） |
| ✅ 源码实证 B | `agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java:1240-1242`<br>`CompactionConfig compactionConfig = CompactionConfig.builder().build();`<br>`MemoryConfig memoryConfig = MemoryConfig.defaults();`<br>`ToolResultEvictionConfig toolResultEvictionConfig = ToolResultEvictionConfig.defaults();`<br>装配处 `:2613-2624`：`if (!disableCompaction && compactionConfig != null) { ... inner.middleware(compactionHook); }` / `if (!disableToolResultEviction && toolResultEvictionConfig != null) { inner.middleware(new ToolResultEvictionMiddleware(...)); }`<br>—— 三个字段**默认非 null**，`!= null` 判定恒真 → **默认开启** |
| ✅ 源码实证 B' | 博客侧与源码一致：`zh/blogs/managed-agents-agentscope-rumtime.md:52`「Harness **默认启用 compaction 与 tool-result eviction**，并允许业务覆盖阈值或显式关闭」 |
| **判定** | **harness/compaction.md 错，博客对，源码站在博客这边。** |
| **影响** | 按 compaction.md 理解会**误以为需要显式开**（无害）；真正的危害是反向——任何**依赖「默认关闭」的安全假设**的代码/门禁，在升级 SDK 后会静默失效。本项目 B/C 两处注释已写「官方 Builder 默认即装配全默认配置……此处显式声明与默认等价的配置，固化意图防官方默认漂移」——**本项目当时的判断是对的**，本文只是为它补上源码出处。 |

### C2 **`ToolResultEviction` 默认排除的工具清单——memory.md 漏报 5 个**

| 栏 | 内容 |
|---|---|
| 📄 文档声称 A | `zh/docs/harness/memory.md:229`「默认排除 `read_file`（避免回读完又被卸载）」——**只列 1 个** |
| 📄 文档声称 A' | `en/docs/harness/memory.md:229` 同错 |
| ✅ 源码实证 B | `.../memory/compaction/ToolResultEvictionConfig.java:66-73`<br>`DEFAULT_EXCLUDED_TOOLS = Set.of(read_file, write_file, edit_file, memory_search, memory_get, session_search)` —— **6 个**；Builder 默认值引用同常量（L122）；类注释 L64 明写「Shell (`execute`) is intentionally NOT excluded」 |
| ✅ 源码实证 B' | `zh/docs/harness/compaction.md:54` 列的 6 个 + `execute` 不排除 —— **正确** |
| **判定** | **memory.md 错（漏报 5 个），compaction.md 对。** |
| **影响** | 照 memory.md 配 `excludedToolNames` 会误判 `write_file`/`edit_file`/`memory_get` 等会被驱逐。**本项目已 `disableFilesystemTools`+`disableMemoryTools`，实际不受影响**；但这是留给后续 agent 的陷阱。 |

### C3 **`tools.json` 的 `allow` 是否过滤掉 Harness 内置工具——文档说会，源码有豁免**

| 栏 | 内容 |
|---|---|
| 📄 文档声称 A | `zh/docs/harness/workspace.md:447`「`allow` / `deny` 在所有工具注册完之后才应用——所以也会过滤掉 Harness 的内置工具（`read_file` / `memory_search` / `agent_spawn` 等）。**用 `allow` 列白名单时务必把要保留的内置工具一并列出**，否则会一起被砍掉」 |
| 📄 文档声称 A' | `en/docs/harness/workspace.md:452` 同错 |
| ✅ 源码实证 B | 时机**对**：`ToolFilter.apply(agentToolkit, resolvedToolsConfig)` 在 `HarnessAgent.java:2949`，位于 MCP 注册（L2780）与 skill 组装之后、`inner.toolkit(agentToolkit)`（L2960）之前<br>但**「会过滤内置工具」不成立**：`.../tools/HarnessPlatformTools.java:35-78` 枚举约 40 个平台工具名（`agent_spawn`/`agent_send`/`agent_list`/`task_*`/`wait_async_results`/`plan_*`/`skill_manage`/`propose_skill` + ~20 个 team 动作）；`.../tools/ToolFilter.java:68-79` 中 `isAllowed` 显式豁免：`!cfg.isStrictAllow() && HarnessPlatformTools.isPlatformTool(name)` → 直接放行。`HarnessPlatformTools` 类 javadoc 设计意图：*"They must not disable the coordination runtime itself"* |
| **判定** | **workspace.md 半错**：时机对，"allow 过滤内置工具"错。**但 `deny` 仍然生效**（`ToolFilter.java:62-65` deny 优先，javadoc「deny always wins」） |
| **影响** | 双向：(a) 以为 allow 会砍掉 `agent_spawn` → 不会，`allow:["read_file"]` 不会让编排层瘫痪；(b) **真风险**：以为 deny 也被豁免 → **错**，deny 是关掉某个内置工具的唯一手段。照文档字面理解会让运维**丢掉这个能力**。 |

### C4（次要）`agentscope-v1-harness.md` 的工作区布局与内置工具表是 v1 遗留

| 栏 | 内容 |
|---|---|
| 📄 文档声称 A | `zh/blogs/agentscope-v1-harness.md:211-215` 工作区含 `agents/<agentId>/context/`（会话状态快照）；`:285` 内置子任务工具列 6 个（`agent_spawn`/`agent_send`/`agent_list`/`task_output`/`task_list`/`task_cancel`） |
| ✅ 源码实证 B | v2 已把 `AgentState` 移出工作区树：`HarnessAgent.java:1147-1154` 默认 `~/.agentscope/state/<agentId>/`（`agentscope.state.home` 可改），`zh/docs/harness/workspace.md:51,305` 明确「`AgentState` 不是工作区内容」「独立子系统 `AgentStateStore`」<br>工具侧：`WaitAsyncResultsTool.java:98` 存在 `wait_async_results`，`zh/docs/harness/subagent.md:169` 也列了 7 个 |
| **判定** | **v1 blog 陈旧**（该文标题即 `v1`，但被放在 `v2/zh/blogs/` 目录下，有误导性）。`workspace.md` / `subagent.md` 正确 |
| **影响** | 按 v1 blog 找 `agents/<id>/context/` 会找不到。**本项目 D 面 `PocKernelSupport` 用的 `MysqlAgentStateStore` 是 2.0 姿势，没踩这个坑** |

### C5（补充）`wait_async_results` 不在 Plan Mode 白名单——文档未提示的实际边界

| 栏 | 内容 |
|---|---|
| 📄 文档声称 A | `zh/docs/harness/plan-mode.md:11` 列出 9 个白名单工具，**不含** `wait_async_results` / `task_cancel` |
| ✅ 源码实证 B | `.../middleware/PlanModeMiddleware.java:61-71` `ALWAYS_ALLOWED` 恰好 9 个，与文档**完全一致**；判定逻辑 L245-249 = `ALWAYS_ALLOWED ∪ additionalAllowed ∪ readOnlyResolver` |
| **判定** | 文档**没说错，但漏报了一个可用性陷阱** |
| **影响** | 若将来开 Plan Mode：模型用 `timeout_seconds=0` 派了后台子任务，**plan 阶段无法 `wait_async_results` 等它**，会拿到 `DENIED`。本项目已 `disableSubagents()`，当前无影响——**但 P4 判定若将来被推翻，这条必须先解决** |

### C6（★ 第二轮新发现）**沙箱快照实现清单——`filesystem.md` 与 `sandbox.md` 两页互相矛盾，且两页都不完整**

| 栏 | 内容 |
|---|---|
| 📄 文档声称 A1 | `zh/docs/harness/filesystem.md:249-253` 快照策略表列 5 个：`NoopSnapshotSpec` / `LocalSnapshotSpec` / `RedisSnapshotSpec` / `OssSnapshotSpec` / `RemoteSnapshotSpec` |
| 📄 文档声称 A2 | `zh/docs/harness/sandbox.md:72-76` 快照表列 5 个：`NoopSnapshotSpec` / `LocalSnapshotSpec` / `OssSnapshotSpec` / `RedisSnapshotSpec` / **`JdbcSnapshotSpec`** —— **与 A1 差一项**（A1 有 `Remote` 无 `Jdbc`；A2 有 `Jdbc` 无 `Remote`） |
| ✅ 源码实证 B | `find -name "*SnapshotSpec.java"`（排除 target/test）得到 **10 个具体实现**：`Noop` / `Local` / `Redis` / `Oss` / `Jdbc` / `Remote` / `Cos` / `Mongo` / `Postgres` / `ControlPlane`（+ 1 个抽象基类 `SandboxSnapshotSpec`） |
| **判定** | **不是中英问题，是 zh 内部两页互相矛盾，且两页都不完整。** 两页各漏对方那一个，同时都漏了 `Cos` / `Mongo` / `Postgres` / `ControlPlane` 共 4 个 |
| **影响** | 依赖「快照后端只有 5 种」做技术选型的读者会漏掉企业最常用的 `PostgresSnapshotSpec`（本项目已有 MySQL，`JdbcSnapshotSpec` 也是候选）。**本项目当前未开沙箱（P5 判定不需要），影响暂为潜在** |

### C7（第二轮新发现）`workspace.md` 的沙箱后端枚举漏掉 Daytona

| 栏 | 内容 |
|---|---|
| 📄 文档声称 A | `zh/docs/harness/workspace.md:266` / `en/docs/harness/workspace.md:271`「模式 2 · 沙箱（`DockerFilesystemSpec` / K8s / E2B / AgentRun）」——**4 项** |
| 📄 文档声称 A' | 同仓 `zh/docs/harness/filesystem.md:24` 的同一张模式表却写「Docker / Kubernetes / Daytona / E2B / AgentRun」——**5 项，含 Daytona** |
| ✅ 源码实证 B | `find -name "*FilesystemSpec.java"` 确认 `DaytonaFilesystemSpec.java` 存在（`agentscope-extensions-sandbox-daytona`） |
| **判定** | **`workspace.md` 漏 1 项**（中英同错），`filesystem.md` 完整。同一仓库内两页对同一枚举给出不同答案 |
| **影响** | 低（Daytona 是托管沙箱后端，本项目不用）。**但这是「同一事实两个答案」的又一例证**，强化 §九 附录的文档可信度分级结论 |

### C8（已核对为「一致」的默认值速查表

以下经源码逐项核对，**文档全部正确**，记录以防后续误改：

| 项 | 文档声称 | 源码 | 判定 |
|---|---|---|---|
| `CompactionConfig` | triggerMessages=50 / keepMessages=20 / flushBeforeCompact=true / offloadBeforeCompact=true | `CompactionConfig.java:273,276,282,283` | ✅ |
| `ToolResultEviction` 阈值 | 80K 字符触发、首尾各 2K | `ToolResultEvictionConfig.java:46` `DEFAULT_MAX_RESULT_CHARS=80_000`、`:49` `DEFAULT_PREVIEW_CHARS=2_000` | ✅ |
| Plan Mode `execute` 默认拒 | 是 | `HarnessAgent.java:1289` `planModeAllowShell=false`；`:2756-2759` 仅当 true 时入白名单 | ✅ |
| `allowShellInPlanMode` 默认 | false | 同上 | ✅ |
| stateStore 默认路径 | `~/.agentscope/state/<agentId>/` | `HarnessAgent.java:1147-1154` | ✅ |
| workspace 解析序 | builder > `agentscope.workspace` 属性 > `AGENTSCOPE_WORKSPACE` > `${user.dir}/.agentscope/workspace`，空白视为未设 | `HarnessAgent.java:1157-1189` | ✅ |
| `maxContextTokens` 默认 | 8000 | `HarnessAgent.java:1253` | ✅ |
| Skill 四层优先级 | projectGlobal < repository < workspace < userId | `HarnessAgentBuilderSupport.java:877-919`（注释就写 Layer 1/2/3/4） | ✅ |
| `IsolationScope` 默认 USER + userId 缺失降级 SESSION | 是 | `SandboxIsolationKey.java:67-95` | ✅ |
| `agent_spawn` timeout | 默认 30 / 最大 600 / 0=后台 | `AgentSpawnTool.java:109-110,1505,1536-1537` | ✅ |
| subagent 深度 | 上限 3 + 强制叶子 | `AgentSpawnTool.java:111` `MAX_SPAWN_DEPTH=3`；`HarnessAgent.java:2646` `leafSubagent` 独立控制装配——**两道独立防线，文档只讲一道** | ✅（机制描述略简） |
| `MemoryConfig` 默认 | 4000 tokens / 30min / 90d / 180d / ALWAYS | `MemoryConfig.java:55,58,61,64,240` | ✅ |
| `SkillCurator` 默认 | interval 7d / stale 30d / archive 90d | `SkillCuratorConfig.java:76-78` | ✅ |

---

## 五、中英文一致性判定（`en/docs/harness/` 10 篇全文逐行对照）

### 5.1 总体结论

**中英一致性整体非常好：严格 1:1 对译，不是"松散翻译"。**

| 健康度指标 | 结果 |
|---|---|
| 篇目集合 | zh / en 各 10 个 `.md`，`diff` **完全相同，无单边篇目** |
| 段落结构 | 10 对文件空行分块数**逐一相等**（22/22、77/77、39/39、129/129、77/77、61/61、85/85、130/130、133/133、138/138）→ 严格 1:1 对译，无段落增删 |
| builder 方法名集合 | **零差异**（10 对文件全部） |
| 数值 token 集合 | **零差异**（10 对文件全部） |
| `en_link` / `zh_link` | 20/20 双向齐全，**0 条断链** |
| 跨页链接 | 抽查 28 个目标（`building-blocks/*`、`quickstart`、`change-log`、`integration/*`）→ **28/28 存在**；中英锚点一一对应 |

**因此：中文翻译不是本次矛盾的原因。** C2 / C3 两处中英**同步地错**，判定为**上游文档 bug**（C6 / C7 更是 zh 内部两页自相矛盾，与翻译无关）。

### 5.2 中英不一致清单（4 条实体差异，均非数值/枚举/代码类）

| # | 篇目 | 类别 | 中文 | 英文 | 哪边更可能正确 |
|---|---|---|---|---|---|
| I1 | workspace | 枚举标注 | `zh/workspace.md:76` `tools.json ← 静态：MCP server + 工具白名单（可选）` | `en/workspace.md:78` `tools.json ← static: MCP servers + tool **allow/deny** (optional)` | **英文更准确**。同页代码块（zh:424-427 / en:428-431）同时有 `allow` 与 `deny`，且后文强调 deny 优先于 allow。中文标注漏了 deny 这一半 |
| I2 | skill | 限定词 | `zh/skill.md:404`「控制在 **2k tokens 上下**」 | `en/skill.md:404` "Aim for **≤ 2k tokens**" | **英文更严格**。"上下"（约）是软约束，"≤"（不超过）是硬上限。属收紧非反转 |
| I3 | subagent | 限定词 | `zh/subagent.md:218`「暴露为**用户可直接交互的入口**」 | `en/subagent.md:220` "directly addressable by the user **through the Channel**" | **英文更完整**。英文点明前置条件是 Channel；中文要读到 `zh/subagent.md:263` 才知道"没绑定 Channel 时会被静默忽略" |
| I4 | sandbox | 代码标识符 | `zh/sandbox.md:91`「一个非 **`NoopSnapshotSpec`** 的快照」 | `en/sandbox.md:91` "A non-**`Noop`** snapshot" | **中文更准确**。英文把类名缩写，与同页表格 `en:72` 的 `NoopSnapshotSpec` 自相矛盾 |

> 另有若干纯书写风格差异（尖括号占位符 `<alice>/` vs `alice/`、反引号标注有无、`source == null` 写法），**不构成语义不一致，未计入**。

### 5.3 哪边更可信的排序

1. **源码（唯一事实源）** —— 无争议
2. **官方 blog** —— 在 C1 上比 harness docs 更可信（blog 说"默认启用" = 源码行为；docs 说"默认关闭" ≠ 源码行为）
3. **`docs/harness/`** —— 默认值经核对准确（C8 表 13 项全对），但在**默认开关**（C1）与**跨页枚举一致性**（C6 / C7）上不可信
4. **英文版 `en/docs/harness/`** —— 与中文 1:1 对齐且无额外偏差；在 I1 / I2 / I3 三处比中文更准确/更完整/更严格，是**更好的对照源**；但在 I4 这类标识符简写上不如中文
5. **中文翻译** —— 未发现翻译引入的语义偏差
6. **`agentscope-v1-*.md`** —— 虽在 `v2/zh/blogs/` 目录下，内容是 1.x 时代，**可信度最低**

### 5.4 补读带来的新发现

第二轮补读 `en/` 全部 10 篇后，**新增 2 条矛盾**（均已回源码复核）：

- **C6**：沙箱快照实现清单在 `filesystem.md` 与 `sandbox.md` **两页互相矛盾**（各漏对方一个），源码实为 **10 个实现**，两页各只列 5 个
- **C7**：`workspace.md` 的沙箱后端枚举漏掉 `Daytona`，而同仓 `filesystem.md` 同一张表是完整的

**这 2 条与翻译无关**——进一步说明问题是**上游 harness 文档的维护质量**，而非语言版本差异。对本项目的意义：**不能把任何单一文档页当作枚举/默认值的权威来源，必须回源码**（这正好是 §九 附录规则要固化的）。

> **本项目已有的 `.claude/skills/agentscope-harness/SKILL.md`（自动发现技能）**是本仓对 AgentScope 契约的第一手落档。**本文 C1 的结论应回写进该技能的 references**，否则后续 agent 仍会以 compaction.md 为准。这是本文给 owner 的唯一「必须立刻做」事项（本文不改任何已存在文件，仅登记）。

---

## 六、「本项目 harness 增强规范」草案提纲

> 只出提纲与要点，**不写完整规范**。完整规范由后续计划产出。

### §1 范围与术语
- 1.1 适用对象：`HarnessAgent` 的 4 个装配点（A/B/C/D）
- 1.2 术语对齐：Call / Session / Task / Run / Agent Contract / State Patch / Verifier
- 1.3 与既有资产的边界：`ToolPolicyEngine` 13 文件 609 行的处置（**保留，不重写**）

### §2 Agent Contract（对应 P3）
- 2.1 契约必备 6 段：为谁工作 / 输入 / 输出 / 允许影响 / 必须拒绝或审批 / **完成证据**
- 2.2 每份契约须声明「不适用项及理由」（如 P4 Plan Mode / P5 Sandbox / P8 Memory）——**目的是让后续 agent 不再反复提议**
- 2.3 契约与代码版本绑定规则

### §3 装配基线（对应 P1 / P2 / P11 / P12）
- 3.1 `HarnessAgentBaseline` 单一工厂：把 4 处共性配置收口（A/C/D 目前缺失的 `.maxIters`/`.skillsEnabled(false)` 需显式决策）
- 3.2 各装配点的**差异声明表**（只允许在基线之上加，不得绕过基线）
- 3.3 显式声明清单：`.compaction`、`.toolResultEviction`、`.modelExecutionConfig`、`.toolExecutionConfig`、`.maxIters` —— 全部显式，禁隐式默认
- 3.4 「默认开关」条款：引用 §四 C1，规范要求**任何依赖默认值的配置必须显式写出**

### §4 任务与完成（对应 P6 / P7 / D3 / D4）
- 4.1 本项目 Task 状态机与业务表的映射（**不新建平行状态机**）
- 4.2 完成证据的声明式表达（接 G-05 五 Gate，不造新门禁）
- 4.3 多维预算：现有 2 维（步数/时长）+ 待补 2 维（Token / 高风险动作次数）
- 4.4 预算耗尽的终态语义：明确失败 + 未完成清单，**禁止静默截断**（照 `session-execution-control.md` 的 cancel/interrupt 区分）

### §5 权限与执行面（对应 P13 / P5 / P19）
- 5.1 现有 `READ_ONLY` 单模式 → 是否升级为 ALLOW/DENY/ASK 三态（⚠️ 涉及 owner 拍板）
- 5.2 「未来若放开任何命令执行」的前置条件清单 = Environment Contract 9 项
- 5.3 写操作幂等键要求（核实现网 69 动作是否已覆盖）
- 5.4 **红线**：`FrozenProjectAgentSkills` 的 SHA-256 pin 校验不可摘除

### §6 可观测（对应 P9 / P11 / P17 / P18）
- 6.1 最小 Context Manifest 6 字段（不做完整 9 字段）
- 6.2 工具白名单断言从 B 面推广到 A/C/D
- 6.3 事件 append-only + 单调序号核查项
- 6.4 Session 时长 / Turn 时长度量分离核查项

### §7 评估与反退化（对应 P10 / P15，⚪ 待 owner 拍板）
- 7.1 只做「轨迹层」规则评测（越权 / 重复探索 / 预算超限），不引入模型评分
- 7.2 回归集必须含：原失败用例 + 相邻正常用例 + 安全对抗用例
- 7.3 Trace→Harness Patch 闭环（禁止从单条失败直接改 prompt）

### §8 门禁清单（对应 SOP-2 惯例）
拟新增脚本（**本次不建**）：
- `scripts/check-harness-contract.sh` —— P3 契约齐全性
- `scripts/check-harness-baseline.sh` —— 4 处 builder 必经基线 + 5 项显式声明齐全
- 挂进 `.harness/verify.sh` + 走 FAIL_SEED 双向触发

### §9 附录：官方文档可信度分级
- §四 C1~C7 全部条目 + C8 一致性速查表 + §五 中英对照（20 文件逐行）
- **固化规则（由本文 7 条矛盾归纳得出）**：
  1. **行为语义（默认开关、生效条件）→ 一律回源码**。C1 是反例：docs 说关、blog 说开、源码站 blog。
  2. **单点数值默认值 → 可信 `docs/harness/`**，但**跨页枚举不可信**：C6 / C7 证明同一仓库两页对同一枚举给出不同答案。
  3. **枚举/实现类清单（SnapshotSpec / FilesystemSpec / 工具名 / 后端）→ 必须 `find -name` 回源码**，任何文档列表都当不完整。
  4. **blog 优先于 `docs/harness/`**，仅限行为语义层；blog 的产品叙述（如 Managed Agents 架构）不可作实现依据。
  5. `agentscope-v1-*.md` 一律当 1.x 历史材料。
- 落到本项目：`.claude/skills/agentscope-harness/references/` 应收录上述 7 条矛盾的结论，而不是让 agent 每次重新读 docs。

---

## 七、给 owner 的三个决策点

| # | 决策 | 影响面 | 建议 |
|---|---|---|---|
| 1 | 是否立刻把 §四 C1（压缩默认开关）回写进 `.claude/skills/agentscope-harness/references/` | 小，但**阻塞后续 agent 正确判断** | **是**，且优先于本规范全文。建议一并回写 C2 / C3 / C6 / C7（5 条） |
| 2 | `ToolPolicyEngine` 13 文件 609 行：保留 / 收编进基线 / 废弃 | 大，决定 §3 基线的边界 | **保留并收编**（它是 D13 权限三态的唯一实现来源，也是 P11 断言的来源） |
| 3 | 是否建 `evals/` 回归集（P10） | 中 | P10 先只做轨迹层。**P16 已于第三轮核销为「不适用」（全仓无灰度逻辑），不再是待查项** |

---

## 八、本文档的自我限定

- 本文**只做提炼与判定**，不含完整规范。§六 是提纲，不是规范。
- 7 条矛盾中，**C1 / C2 / C3 / C6 / C7 已有源码行号**；C4 / C5 是文档陈旧与未提示的边界，非硬错误。
- **`en/blogs/` 9 篇仍未读**（且 `en/blogs/` 无 `how-to-build-agent-harness/`，该系列只有中文）。若后续需要英文 blog 口径，需再补一轮。
- 本文所有对本项目的判定基于**当前工作树**。**第三轮已重核**：4 个装配点行数 483 / 568 / 211 / 107，与第一轮完全一致，无新装配点（`HarnessAgent.builder()` 在 `src/main` 仍为 4 处 + `src/test` 3 处），§二 差异表仍然成立。

### 8.1 三轮工作记录

| 轮次 | 做了什么 | 净产出 |
|---|---|---|
| 一轮 | how-to-build 4 篇 + harness 10 篇 + blogs 其余 + usecases 读完；18 项源码验证 | §一~§七 初稿，6 条矛盾 |
| 二轮 | 补读 `en/docs/harness/` 10 篇全文逐行对照 | §五 重写；**新增 C6 / C7**（矛盾 6→7） |
| 三轮 | 核销 P16（灰度分桶）；用 `grep -c` 量化重核 D1 / P2 | P16 ⚪→🔴 **不适用**；D1 双向漂移量化；P2 获硬证据 |

**三轮之后的剩余未做**：只剩 §七 决策点 1（回写 C1~C7 到 `.claude/skills/agentscope-harness/references/`）需要 owner 授权——它要改**已存在文件**，超出本任务「只写 1 份新 docs」的纪律，本文只登记不执行。

---

*本文只新增此一份文档。未修改 `src/`、未修改任何已存在文件、未执行 `git add` / `git commit`。*
