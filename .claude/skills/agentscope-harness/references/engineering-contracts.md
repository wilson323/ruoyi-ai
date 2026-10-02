# engineering-contracts — 三份工程契约（任务 / 信息 / 行动）

> 来源：《如何构建 Agent Harness》二、三、四篇
> `02-task-orchestration`（任务）· `03-context-and-state`（信息）· `04-controlled-execution`（行动）
> 抓取时间 2026-09-28。本篇只留可执行判据与契约字段，论证过程读原文。

---

# 契约一：任务编排、长程推进与协作流转（blog 02）

## 是什么

稳定的 Agent Loop + 权威任务状态 + 多维预算 + 外置计划 + 受控委派 + 证据化完成。

### 0. 术语来源声明（2026-10-02 全量核对后追加）

本文件（及 SKILL.md 的 §五层责任边界、§任务状态机、§多维预算、§Action Plane、§Permission 三态、§Verifier、§Trace→Harness Patch）描述的是**本项目自研的 harness 工程契约**，叠加在 AgentScope 2.0.3 之上，**不是 AgentScope 官方设计**。

核对方法与结论：

```bash
rg "五层责任边界|Action Plane|Verifier|任务状态机" \
   /Users/mac/Documents/agentscope-java/docs/v2/zh/docs/harness/*.md
# → 零命中
```

官方 harness 文档（`docs/v2/zh/docs/harness/` 共 10 篇）只讲「三条核心工作原理」与「状态分三层」，不含上述任何术语。**注意该核对范围只覆盖 `docs/`**：官方 **blogs**《如何构建 Agent Harness》01 篇已含同构的五层责任边界表（时序上本项目 skill 先行，官方方法论后续追平；原文见本地存档 `blogs/how-to-build-agent-harness/01-patterns.md`）。因此「零命中」的准确表述是「docs/harness 与源码零命中」；写对照文档时区分 `docs/`（API 依据）与 `blogs/`（方法论印证，非 API 依据）两个来源。**因此：写「对照 Agent 设计原理」类文档时，不得以本文件为官方对照物**；官方对照物只能是 `/Users/mac/Documents/agentscope-java/docs/v2/zh/docs/` 与 `agentscope-harness/src/main/java/`。

本轮同时实证了两条**不得当作官方能力**的边界：

1. **harness 无图编排原语**——`StateGraph` / `addEdge` / `addNode` / `CompiledGraph` 在 `agentscope-harness` 与 `agentscope-core` 均零命中；`HarnessAgent implements Agent`（`:169`），`getDelegate()` 返回 `ReActAgent`（`:492`）。编排只有 middleware 叠加 + 子 agent 委派两条路。
2. **无 checkpoint / 从第 N 步恢复 API**——全仓 `checkpoint` 只命中 `core/shutdown/*`（JVM 优雅停机钩子，非可回放执行检查点）；官方恢复仅三场景（session transcript 分段、`TaskRecord`、远程 SSE `lastEventSeq` 续传），且孤儿任务只标 FAILED 不重跑。

引用本文件结论时，请同时标注**「已实证（给 file:line）」/「仅文档提及（源码未找到）」**两栏——这是本项目强制口径。

## 为什么（根因）

消息历史只记录"模型和用户曾交换过什么"，不能当任务状态的唯一来源。模型看到几百条历史就得靠猜进度；预算没有外部边界就会悄然截断；完成没有验收器就会把"模型说完成了"当成事实。

## 怎么识别 / 怎么做

### 1.1 Agent Loop 五步（核心 Loop 保持稳定，能力从扩展点加入）

`Prepare → Model → Act → Observe → Verify`

- **Verify 不接受"我已经完成"作为唯一依据**，而是运行与任务相匹配的验收器
- 浏览文件 / 执行代码 / 查业务系统 / 调度远程 Agent 都是 Act 的不同实现
- 用户追加要求 / 工具返回 / 异步任务完成都是 Observe 的不同来源

### 1.2 权威任务状态机（至少这 10 态，必须显式化）

| 状态 | 语义 | 允许的下一步 |
|---|---|---|
| `CREATED` | 已建立未开始 | 进入运行或取消 |
| `RUNNING` | 正在准备、推理或行动 | 继续、暂停、等待、验证、失败、取消 |
| `WAITING_INPUT` | 缺用户或业务信息 | 接收输入后恢复，或超时结束 |
| `WAITING_APPROVAL` | 行动明确但需批准 | 批准、拒绝、修改、取消 |
| `WAITING_EVENT` | 等工具 / 子任务 / 外部系统 | 收到事件后恢复，或按策略超时 |
| `PAUSED` | 用户或系统主动暂停 | 恢复、修改规则、取消 |
| `VERIFYING` | 正在核验阶段或最终结果 | 通过、生成修复项、失败 |
| `COMPLETED` | 验收条件已满足 | 交付结果与证据 |
| `FAILED` | 当前策略下不能继续 | 重开尝试、转人工、结束 |
| `CANCELLED` | 被显式终止 | 清理资源并保留审计事实 |

**`WAITING` 不是失败，`PAUSED` 不是结束。** 只有显式化，Runtime 才能在等待期释放资源并准确恢复；界面才能说明 Agent 在等什么；观测才能区分执行慢 / 审批慢 / 工具慢。

权威状态至少含：目标、当前阶段、Plan 与 Todo、已确认事实、阻塞项、子任务、Artifact、剩余预算、等待原因、完成依据。

### 1.3 多维预算（Loop 的外部终止边界）

步数 · 总时长 · Token · 费用 · 工具调用次数 · 子任务并发数 · 高风险动作次数，全部进预算。

- 接近阈值 → Harness 可要求模型收敛范围、停止新委派、优先完成可交付部分、或请用户选择
- 耗尽 → **产生明确终态 + 未完成清单**，绝不悄然截断

### 1.4 三种计划控制方式（按任务复杂度选）

| 模式 | 表示方式 | 适用任务 |
|---|---|---|
| 轻量 Todo | 简短有序的待办列表 | 目标明确、步骤少、反馈快 |
| Plan Mode | 只读探索 → 形成计划 → 确认后执行 | 影响面较大、需要审阅、环境尚不清楚 |
| Planner–Executor | Planner 维护阶段和依赖，Executor 逐项执行 | 长任务、多依赖、可并行、需要专业角色 |

纪律：

- 计划**必须允许修订**。工具结果可能推翻假设、用户可能改目标、环境可能暴露新约束。每次重规划要说明触发事实并保留已完成项，**不能通过改写目标掩盖失败**。
- Todo 只记录会改变任务可交付状态的事项，不记录每次微小工具调用；保持唯一的当前进行项或明确的并行分组。
- AgentScope Plan Mode 实现：探索阶段只开放只读工具 + `plan_enter` / `plan_write` / `plan_exit` / `todo_write`；`plan_exit` 触发人工确认，获批后才进入可写阶段；计划写 `plans/PLAN.md`，Todo 存 Agent 状态，两者都跨调用恢复。
- **Prompt 与 Harness 控制的区别**：Prompt 可以要求"先规划再修改"，但只有权限模式 + 工具白名单 + 持久状态 + HITL 共同生效，系统才真正具备"计划获批前不可写"的约束。

### 1.5 阶段门禁（长任务不能只在最后验证）

漏洞修复案例的五阶段：

| 阶段 | 主要产物 | 进入下一阶段的门禁 |
|---|---|---|
| 影响分析 | 受影响模块、依赖链、运行实例清单 | 影响范围可追溯，版本事实已核验 |
| 修复规划 | 升级方案、兼容风险、回滚方案 | 计划获批准，写操作权限被放开 |
| 变更实施 | 代码差异、依赖锁文件、测试补充 | 修改仅发生在授权工作区 |
| 独立验证 | 单测、集成测试、安全扫描、评审意见 | 所有强制检查通过或缺口被显式接受 |
| 发布准备 | 变更单、发布窗口、回滚入口 | 责任人审批；**不自动执行生产发布** |

有效的阶段描述必须回答"**输出是什么、证据在哪里、谁来确认**"，而不是"分析问题""处理代码""确保质量"。

### 1.6 Subagent 委派

**何时值得委派**（只有收益超过通信+合并成本才做）：

1. 上下文隔离 — 子任务只加载相关文件/工具/历史，避免主 Agent 窗口被探索过程占满
2. 能力隔离 — 不同子任务用不同模型、指令、Skill、工具、权限
3. 并行执行 — 互不依赖的检索/实现/验证同时推进，缩短墙钟时间

不值得：任务很短、步骤高度依赖、共享对象频繁变化。

**委派契约 7 项（每个子任务都要带，可机读）**：

| 契约项 | 要回答 |
|---|---|
| 目标与边界 | 交付什么；哪些目录、系统、动作在范围内 |
| 已知上下文 | 哪些事实已确认；哪些决定不可自行改变 |
| 能力与权限 | 可用模型、Skill、Tool、环境、权限 |
| 预算 | 最大时间、步骤、Token、费用、并发 |
| 输出与证据 | 结果结构；证据和来源如何附带 |
| 失败语义 | 何时重试、返回部分结果、升级、终止 |
| 验收条件 | 父 Agent 用什么条件判断结果可采用 |

**Delegation vs Handoff**（关键区分）：

- **Delegation**：父任务保留责任，委派有边界的子任务；结果返回后仍由父 Agent 整合和验收
- **Handoff**：任务控制权转移，接收者成为当前责任人，并获得继续推进所需的目标、状态、恢复位置
- **两者都不能只靠一条自然语言消息实现**，至少要有任务关系、状态与责任变更记录

主 Agent 负责全局目标、计划、预算、依赖、最终结果，**不应把"任务完成"的责任一并交出去**。研究 / 执行 / 评审 Subagent 是运行时职责，不一定是永久角色。

### 1.7 长任务四对象（缺一不可）

| 对象 | 内容 |
|---|---|
| **Agent Definition** | 固定模型、Harness 配置、Tool、Skill、Subagent、权限策略 + 可追溯版本 |
| **Task** | 目标、业务状态、预算、幂等键、审批条件、Artifact、完成证据 |
| **Runtime Context** | 当前用户与 Session 身份，用于状态和资源隔离 |
| **Event** | 可流式展示、可重放的进度 / 工具 / 审批 / 结果 / 错误事件 |

- 任务队列只负责"何时由哪个 Worker 运行"，**不能代替** Harness 内部的 Plan / Todo / Session 状态
- Harness Session **也不能代替**业务 Task 的状态机和完成标准

### 1.8 恢复纪律

- 恢复前**必须核对外部系统状态、幂等键和已产生的 Artifact**，不能机械重放失效前的写操作
- 恢复测试要能在"补丁已写入但测试结果尚未返回"这种中间态强制中断：恢复后应先查询原测试任务，而不是再次改文件或重复启动发布
- 模型输出可用固定样本 / 录制回放 / 模拟器替代来验证 Harness 的**确定性控制**；真实模型的端到端效果属于 Evaluation

### 1.9 失败恢复：先分类再选策略（"Agent 失败"不是可执行诊断）

| 失败类型 | 策略 |
|---|---|
| 瞬时模型 / 网络错误 | 有界退避重试 |
| 参数错误 | 修正参数 |
| 环境缺失 | 重建环境 |
| 权限拒绝 | 等待或终止（**不重试**） |
| 重复探索 | 重新规划 |
| 业务条件不满足 | 明确缺口 |

- **有副作用的行动重试前必须先回答"上一次究竟有没有发生"**。发布 / 通知 / 写库的超时可能只是响应丢失。生成幂等键、记录请求与结果、优先查询状态；无法证明未执行时不得直接重复。
- 切换模型 / 工具 / 环境的**降级路径也要被记录**，因为能力、权限和结果质量可能已经变化。
- **无进展检测比最大步数更早发现问题**。信号：连续调用相同工具且参数高度相似 · 反复得到同一错误 · Plan 长时间不变 · 工作区没有新增事实 · 模型在少数行动间循环。检测后先要求模型按结构化证据重新规划，再逐步缩小任务 / 切换能力 / 创建独立评审 / 转人工。

### 1.10 完成验证：五层强度，与风险匹配

**模型只能提出完成申请，Harness 才能提交完成状态。**

| 层级 | 验证方式 | 适用结果 |
|---|---|---|
| 结构验证 | Schema、必填字段、格式、文件存在 | 低风险结构化产物 |
| 环境验证 | 查询真实系统、检查文件差异与执行结果 | 工具和工作区任务 |
| 确定性验证 | 测试、规则、静态检查、业务校验 | 可编码成功条件 |
| 独立模型验证 | 用独立 Context 检查质量与遗漏 | 开放式分析和复杂内容 |
| 人工验收 | 责任人审阅、签署、批准 | 高影响、主观、合规任务 |

- Verifier 应返回**结构化缺口、失败证据和可修复性**
- **验证失败不是简单结束，而是新的 Observation**：Harness 决定继续修复 / 重新规划 / 转交 / 失败
- Verifier = 单次任务完成门禁（跑在 Agent Harness 内部）；跨版本判断某个 Harness 是否更好 = Evaluation Harness（见契约三 §3.6）

Outcome 结构（可直接迁移到合同审查 / 数据修复 / 客户工单等场景，变的只是 Verifier）：

```text
state: COMPLETED
outcome: change_ready_for_approval
evidence:
  dependency_check: artifacts/dependency-tree.json
  code_diff: artifacts/remediation.patch
  unit_tests: artifacts/unit-test.xml
  integration_tests: artifacts/integration-test.xml
  security_scan: artifacts/security-scan.sarif
  review: artifacts/review.json
  change_request: CR-18427
remaining_actions:
  - 由服务负责人审批并安排发布窗口
```

**目标边界纪律**：若目标只到"形成可审批变更"，系统不得把"尚未发布"误判为未完成；若目标包含发布，则必须进一步核验审批和真实部署状态。

### 1.11 Middleware 与内核测试

保持核心 Loop 稳定，能力通过 Middleware 组合演进；对**确定性部分**做契约测试（状态转换、预算扣减、权限决策、幂等键生成、恢复点语义），模型输出用录制回放替代。

---

# 契约二：上下文、状态与可复用能力资产（blog 03）

## 是什么

Context 构建管线 + Context Manifest + 分层活动上下文与压缩 + Call/Session/Task 三层边界 + Workspace 外部工作记忆 + Skill 渐进披露与资产治理。

## 为什么（根因）

窗口有限而任务事实持续增长。全量塞历史 → 成本、延迟、注意力退化三杀；简单截断最早内容 → 丢初始目标和关键决定。只按相似度排序 → 平台政策被经验建议挤掉。只存 Prompt 文本 → 无法解释模型为什么没看见某项材料。

## 怎么识别 / 怎么做

### 2.1 Context 优先级函数（7 维，不能只按相似度）

约束强度（平台政策 > 经验建议）· 任务相关性 · 时间有效性（当前环境事实 > 过期历史结论）· 来源可信度（权威系统事实 > 未确认的模型摘要）· 执行依赖（即将调用的工具说明和验收条件优先保留）· 信息增量（重复的合并或移除）· Token 成本（同等价值取更紧凑、可引用的表达）

**管线顺序纪律**：先做身份和权限过滤，再做相关性排序。**不能为了排序方便先把跨租户内容交给检索器或模型。** 外部内容还要保留来源和信任等级，避免检索到的文档或网页把自身文本伪装成高优先级指令（Prompt Injection）。

**预算分区**：为不可覆盖规则、当前目标和状态保留固定下限；为最近交互、检索知识、Skill、工具 Schema 分配动态额度；预留模型输出与后续 Observation 空间。预算不是静态百分比——工具密集阶段工具定义和环境状态权重上升，最终综合阶段证据和验收条件更重要。

### 2.2 Context Manifest（每次模型调用都要生成，记录"模型究竟看到了什么"）

| 字段 | 说明 |
|---|---|
| `source_type` / `source_id` | 来源类型与稳定标识 |
| `scope` | Global / Tenant / Project / User / Session / Task |
| `version` | 指令、文档、Skill、Tool Schema 或摘要版本 |
| `trust_level` | 平台规则 / 企业事实 / 用户输入 / 外部内容 |
| `permission_basis` | 本轮为何有权读取该内容 |
| `selected_reason` | 规则命中 / 当前阶段 / 检索相关 / 显式引用 |
| `token_count` | 实际占用窗口大小 |
| `transform` | 原文 / 摘要 / 截断 / 去重 / 引用化 |
| `content_hash` | 支持回放与变更检测的内容摘要 |

Manifest 支撑三类工作：开发时解释模型为什么遗漏某项信息；评估时比较两个 Agent 版本的 Context 差异；安全审计时确认某条敏感内容为什么进入模型输入。**只记录 Prompt 文本无法稳定完成这些任务**——同一文本片段的来源、权限、版本可能完全不同。

把上下文层级、检索范围、预算分配、压缩阈值、工具披露、敏感内容处理统一定义为 **Context Policy**，并与可运行的 Agent 版本绑定。这样才能把"偶然在某次调用中看见了什么"转化为可测试、可回放的工程行为。

### 2.3 分层活动上下文（原始历史存外部状态，按当前阶段构造活动窗口）

```text
Active Context
├── Stable goal and constraints  长期稳定、不可遗漏
├── Current task state           当前阶段、Plan、Todo、预算和阻塞
├── Recent verbatim turns        需要精确理解的最近交互
├── Structured history summary   更早过程的压缩表示
├── Retrieved facts              本轮相关 Memory / Knowledge
└── Artifact references          可按需继续读取的外部内容
```

**"最近"不只按时间定义**：用户对目标的最新修改、尚未解决的工具错误、待审批动作、验收失败证据，即使产生得更早也算活动状态；已完成且可由 Artifact 证明的探索过程可以退出活动窗口。

生命周期四步：`Commit → Compact → Rebuild → Validate`。大工具结果**退出窗口而不退出任务**（落盘 + 只留占位符/引用），ContextOverflow 兜底重试是最后防线，不是常规路径。

### 2.4 Call / Session / Task 三层边界（常被合并成"会话"，是恢复能力的头号杀手）

| 对象 | 定义 | 生命周期 | 典型内容 |
|---|---|---|---|
| **Call** | 一次应用对 Agent 的请求或恢复动作 | 秒到分钟 | 请求 ID、当前身份、临时凭证、输入、返回游标 |
| **Session** | 某用户或调用方与 Agent 的连续交互边界 | 分钟到数天 | 参与者、Channel、消息、偏好、可见任务 |
| **Task** | 围绕一个可验收目标持续存在的执行对象 | 可跨 Call / Session / 进程 / 节点 | 目标、状态、计划、子任务、预算、Artifact、完成证据 |

一个 Session 可发起多个 Task；一个长 Task 也可在多个 Session 中被查看、干预、恢复。**把 Task ID 绑定为消息线程 ID，会限制后台执行、多人协作和跨渠道续接。** Harness 应分别保留两者并显式记录关联关系。

Agent Session 负责**推理连续性**，业务 Task 负责**目标与验收**，两者不能混为一谈。业务系统仍要保存 `taskId`、Agent 版本、输入、Artifact、审批记录和 Outcome。

### 2.5 状态三表示（不能互相替代）

| 表示 | 记录什么 | 适合 |
|---|---|---|
| **Event Log** | 发生过什么 | 追踪因果、审计、重建 |
| **Snapshot** | 某时刻的聚合状态 | 快速读取当前视图 |
| **Checkpoint** | 可安全恢复执行的位置 | 除 Snapshot 外还含 Continuation、幂等、环境依赖 |

只有 Event Log → 恢复成本随任务长度增长；只有 Snapshot → 无法解释状态如何形成；把每次状态保存都叫 Checkpoint → 掩盖"某些工具事务仍在进行、不能安全重放"的事实。

**面向逻辑状态接口编程**，不要把恢复能力绑定到本地内存或某个数据库：

`append_event` · `load_task_state` / `commit_task_patch` · `save_snapshot` / `load_snapshot` · `put_artifact` / `get_artifact` · `create_checkpoint` / `resume_checkpoint` · `search_workspace` / `read_range`

本地 Agent 映射到文件和进程内状态；分布式在线 Agent 映射到外置状态服务。逻辑语义一致，Framework / SDK / 托管路径就能接入同一企业状态平台。

### 2.6 Workspace 外部工作记忆（与 Memory 的区别）

- **Workspace** 服务当前任务的显式工作过程，内容通常可被用户直接查看和编辑
- **Memory** 是跨任务选择性保留的经验和事实

生命周期与责任必须分开，不能因为都在文件系统里就用同样的保留和权限策略：

```text
Workspace
├── inputs/       用户提供或任务同步的输入（保持来源）
├── scratch/      临时分析、搜索结果、中间文件（任务结束可清理）
├── state/        Plan、Todo、Continuation、结构化任务视图（Harness 管理）
├── artifacts/    可交付产物与机器可读结果（可能交付/发布/进下游）
├── evidence/     测试、查询、审批、验证证据（用于证明完成）
└── manifest      来源、版本、权限、状态、保留策略
```

每个对象至少记录：稳定 ID · 路径或对象引用 · 内容类型 · 创建者 · 来源 · 版本 · 权限范围 · 所属任务 · 状态 · 校验摘要 · 保留期限。

Artifact 生命周期：`Draft → Validating → Ready → Published / Rejected → Archived / Deleted`。**模型写出文件不等于产物已完成**：进入 `Ready` 前应通过格式 / 测试 / 业务验收；进入 `Published` 往往还需权限审批和提交动作。

### 2.7 Skill 的能力资产模型（Tool 说"能做什么动作"，Skill 说"在某类任务中如何正确使用若干动作"）

```text
Skill Package
├── manifest        name / version / owner · description / applicability
│                   required tools / permissions / environment · input / output / acceptance contract
├── instructions
├── scripts
├── templates
├── examples
├── references
└── tests / evaluation cases
```

Skill ≠ 一段 Prompt，≠ Tool 的别名。**Skill 不直接拥有额外权限**——只有当前用户、任务、环境允许时，相关能力才能执行。

**渐进式披露三层**（数百个 Skill 时全量注入会耗尽窗口并让模型选错能力）：

1. **发现层**：模型只看见名称、简短描述、适用条件、主要风险
2. **选择层**：Harness 按任务、权限、环境解析候选，加载完整 Manifest
3. **执行层**：只有真正需要某一步时，才读详细指令、脚本、模板、参考资源

Skill 选择**不应只依赖模型语义匹配**，Harness 还要检查模型兼容性、工具依赖、环境条件、租户许可、数据范围、版本状态。若 Skill 要求写生产系统而当前处于只读探索阶段，它**可以被发现，但不能进入可执行状态**。

**确定性 vs 模型判断的边界**：稳定、重复、可编码、失败代价高的步骤 → 沉淀为脚本 / 工具 / 规则（格式转换、固定校验、权限查询、测试执行）；需要理解模糊目标、比较方案、解释异常、按新证据调整方向的部分 → 保留为模型指令。**但脚本不能藏在说明文本中被不受控地运行**，仍要过 Action Plane、环境和权限契约。

**从 Trace 沉淀 Skill 的正确顺序**：提取稳定步骤 → 移除特定任务 ID / 临时路径 / 一次性判断 → 为脚本和模板补测试 → 形成 Skill。**一次任务成功不等于它已成为可复用能力。**

### 2.8 Skill 资产治理与反退化

Skill Registry 至少记录：所有者 · 作用域 · 版本 · 依赖 · 权限需求 · 支持的 Agent/Model · 测试结果 · 发布日期 · 弃用状态 · 使用效果。

生命周期：`Draft（编写与本地试验）→ Test（脚本、契约、任务用例）→ Review（安全、权限、领域评审）→ Publish（进入允许的作用域）→ Observe（使用率、成功率、失败模式）→ Update / Deprecate / Rollback → Test`

- 评估**不只看是否被模型选中**，还要比较启用前后的任务成功率、步骤数、工具错误、人工修改量、成本、安全事件
- 很少被采用或持续降低效果的 Skill → 调整描述、缩小适用范围或下线
- **线上 Trace 必须能定位到具体 Skill 版本**。`latest` 指针适合开发，不适合不可追溯的生产执行
- Agent 版本可锁定允许的 Skill 集与版本范围；紧急修复通过新 Agent 版本 / 受控热补丁 / 明确的策略覆盖生效，并触发相关回归集
- 多租户资产：用作用域和元数据建立边界；防止污染并**支持真正的删除**；资产变更纳入反退化闭环

---

# 契约三：受控执行、验证反馈与交付准备（blog 04）

## 是什么

Action Plane（统一行动契约）+ 能力边界（Tool/MCP/Skill/Subagent/Remote Agent）+ Environment Contract + Permission 三态与 HITL + 面向任务语义的事件流 + Trace/Evaluation 效果闭环。

## 为什么（根因）

模型的工具调用只表达意图。不同连接方式（Function Calling / Shell / 浏览器 / Computer Use / 远程 Agent）如果各做一套预算、权限、审计、重试、Trace，治理逻辑就会在每套 Harness 里重复实现且互相漏。仅按 Tool 名称做静态白名单，无法区分"读取一条测试记录"和"导出整个生产库"。

## 怎么识别 / 怎么做

### 3.1 统一 Action Request（所有能力表达先转成它）

```text
Action Request
├── action_id / task_id / parent_event_id
├── capability_id / version
├── arguments and expected output schema
├── actor: user / service / agent identity
├── purpose and current task stage
├── requested environment and resource scope
├── side-effect / reversibility / risk classification
├── idempotency key and timeout
└── approval and audit requirements
```

**模型产生的自由文本说明只能作为 `purpose` 的候选输入**；能力名称、参数类型、影响范围、身份必须由确定性代码解析与校验。

Action 生命周期：`REQUESTED → VALIDATED → AUTHORIZED / WAITING_APPROVAL → RUNNING → SUCCEEDED / FAILED / CANCELLED`；外部异步系统还可能进入 `ACCEPTED` / `WAITING_RESULT`。

**Task 状态与 Action 状态相关但不能混为一谈**：一个 Tool 失败不一定使整个 Task 失败；一个 Task 取消也可能需要等已提交 Action 返回后再补偿。

### 3.2 Action Result（不能只有模型可读文本）

至少包括：成功/失败/未知/部分完成状态 · 结构化数据与面向模型的紧凑 Observation · 原始结果/日志/Artifact 的稳定引用 · 错误类别与可重试性与是否已产生副作用 · 实际执行身份/环境/时间/版本/成本 · 对 Task State 的候选 Patch · 可供 Verifier 使用的环境证据。

**Harness 统一提交 State Patch**，避免每个 Tool 任意修改任务权威状态。大结果按契约二 §2.3 卸载；敏感字段在进入 Context 和交互事件前**分别**脱敏。

### 3.3 能力边界表（避免两种常见混淆）

| 对象 | 有独立 Agent Loop | 主要封装 | 状态与责任 | Harness 中的使用方式 |
|---|---|---|---|---|
| **Tool** | 否 | 一个可执行动作 | 调用方负责组合和验收 | 产生一次 Action Request |
| **MCP Server** | 否（协议不要求） | 一组工具、资源等能力 | Server 负责能力实现，Host 负责选择、授权、集成 | 发现后注册为 Tool / Resource |
| **Skill** | 否 | 完成某类任务的方法、脚本、资料 | 当前 Agent 仍负责 Loop 与结果 | 按需加载到 Context 并调用 Tool |
| **Subagent** | 是（通常同 Harness 或平台承载） | 有边界的子任务执行者 | 父 Agent 保留总体责任 | 本地 Delegation，父子状态可直接关联 |
| **Remote Agent** | 是（独立部署和治理） | 可持续执行的外部任务能力 | 远程 Agent 对其任务承诺负责，委派方负责最终采用 | 通过 A2A 或 Agent API 建立远程任务 |

两种混淆：把固定 API 包装成"Agent"**不会自动获得规划和恢复能力**；把复杂远程 Agent 当同步 Tool，**会丢失任务状态、异步事件和 Artifact 语义**。

Registry 与 Gateway 的取舍：能力少且信任边界简单 → Harness 直连；多 Agent / 多框架 / 多团队共享大量能力 → Registry + Gateway 避免凭证和治理逻辑在每套 Harness 中重复。**Tool 设计应优先暴露业务语义**（如 `change.create@v3`、`production.deploy`），而不是让模型通过通用 HTTP 或 Shell 自行拼接生产操作。

**案例纪律**："创建变更单"与"发布生产环境"必须是**两个 Action**——身份、风险、可逆性、审批要求完全不同。前者 `risk: medium / reversible: true / policy_decision: ALLOW`；后者引用已创建的变更单和批准版本，`risk: high`，Policy 返回 `ASK`，进入 `WAITING_APPROVAL`。即使最终调用同一变更平台，Harness 也能分别授权、审计、重试、验证。

### 3.4 Environment Contract（声明并兑现）

明确文件、命令、浏览器或企业系统在哪里运行；代码修改**只发生在隔离工作区**；Secret、网络与数据出站有显式边界。Sandbox 负责限制文件 / 进程 / 网络 / Secret / 资源，并支持快照与恢复（长任务能在进程重启后继续）。

### 3.5 Permission 三态 + HITL

Harness 需同时记录三类身份：**发起任务的用户或服务身份** · **发起行动的 Agent 版本** · **实际执行 Tool 或 Sandbox Action 的 Runtime 身份**。一次调用可以用服务身份，也可以传递用户委派，但必须明确数据访问和副作用最终归属于谁。

权限决策至少考虑：主体、租户、任务目的、能力、参数、目标资源、环境、当前阶段、数据敏感度、影响范围、可逆性、预算、历史审批。

三态：

- **ALLOW** — 当前条件下可直接执行，并记录决策依据
- **DENY** — 无论模型如何解释都不得执行；向 Loop 返回结构化原因和允许替代项
- **ASK** — 动作可执行，但需指定的人或系统确认

默认策略参考：

| 风险示例 | 建议默认策略 |
|---|---|
| 读取当前项目内非敏感文件 | ALLOW，记录范围 |
| 查询当前用户有权查看的业务数据 | ALLOW 或基于数据等级 ASK |
| 修改工作区文件但尚未提交外部系统 | ALLOW，并提供 Diff 与可撤销能力 |
| 发送外部消息、发布、支付、删除或改变生产数据 | ASK 或 DENY，要求预览和明确影响 |
| 访问跨租户数据、绕过安全控制、请求长期 Secret | **DENY** |

**`ASK` 不是默认兜底**。过多审批会让用户形成机械确认，也让 Agent 失去连续性。应优先通过更窄 Tool、参数约束、预览、资源范围、Sandbox 降低风险，只在目的或后果无法由策略充分判断时请求人工参与。

HITL 三层次：①**Plan 审批**（进入执行前确认目标、范围、方案、影响面）②**Action 审批**（对某次具体 Tool / 环境 / 远程 Agent 调用批准、拒绝、修改）③**结果验收**（对高影响 Artifact 或业务结果最终签署）。

审批请求应包含：Agent 想做什么、为什么、以谁的身份、作用于什么对象、预计影响、参数和差异、是否可逆、失败如何处理、批准范围是仅本次 / 当前 Task / 一类受限动作。**用户批准后，Harness 仍要重新校验对象版本和策略**，防止等待期间环境已变化。

不可逆或高影响动作统一走 **Preview → Approve → Commit → Verify**：

- Preview 展示接近实际提交的内容和影响（无法回滚的动作必须在 Preview 中明确说明）
- Approve 绑定身份、范围、对象版本
- Commit 使用幂等键执行
- Verify 查询真实系统状态；失败进入 Repair / Compensate / Escalate，**不把已发送的请求当作成功**

另需 Guardrail 与 Prompt Injection 防护：外部内容带来源和信任等级，不得让检索到的文本自我提升为高优先级指令。

### 3.6 Streaming / Channel / Observability / Evaluation

事件流面向**任务语义**，不是把模型 token 直接吐给前端。Channel 负责路由消息、管理会话、转发事件；事件要可续传、可限流、可按身份展示（恢复游标）。

**两套系统必须分开**：

| 系统 | 核心职责 | 输入 | 输出 |
|---|---|---|---|
| **Agent Harness** | 在真实或测试环境中完成一次任务 | 目标、Context、能力、环境、策略 | 轨迹、Artifact、完成证据、Outcome |
| **Evaluation Harness** | 以一致方式运行、回放、比较 Agent 版本 | 数据集、环境、预算、评分器、待测版本 | 指标、失败聚类、版本差异、发布建议 |

Evaluation Harness **必须固定或披露**模型、推理设置、Harness、工具版本、预算、重试、环境、评分规则——否则两个版本的分数差异可能来自运行条件，而不是所评估的 Harness Patch。Verifier 可以成为 Evaluation 的数据来源，Evaluation 也可能发现某类 Verifier 过松或过严，但**不应把昂贵的发布评估器直接嵌入每次线上任务**。

三层评测对象：

| 层级 | 评测对象 | 典型问题 | 适合方法 |
|---|---|---|---|
| 单步 | 某次 Context、模型判断或 Tool 调用 | 工具是否选对、参数是否正确、检索是否含关键证据 | 规则、Schema、标注、局部模型评分 |
| 轨迹 | 从任务开始到结束的状态与行动序列 | 是否绕路、重复、越权、错误委派、过度消耗 | 轨迹规则、序列比较、专家或模型评审 |
| 最终结果 | Artifact、环境终态、业务 Outcome | 目标是否真正达成、质量是否可接受 | 测试、业务查询、人工验收、独立 Evaluator |

只评最终结果可能掩盖高成本或高风险轨迹；只评单步又可能惩罚有效探索。需同时衡量：任务成功率、完成质量、人工接管率、工具错误、权限事件、延迟、成本、业务价值，并按任务类型和风险分层。**人工反馈也不是天然真值**——要区分用户偏好、业务结果、操作便利性，并与环境证据结合解释。

### 3.7 从 Trace 到 Harness Patch（效果闭环，禁止"失败就改 Prompt"）

```text
Trace + Outcome → Failure Cluster（按症状与根因聚类）
              → Diagnosis（Model / Context / State / Tool / Policy / Environment / Loop）
              → Harness Patch（最小针对性变更）
              → Regression（成功、成本与安全回归）
              → Release Gate（灰度或拒绝）
              → 新 Agent 版本 → Trace ...
```

Patch 与根因一一对应：

| 症状 | Patch 位置 |
|---|---|
| 缺少事实 | 调整 Context 或 Knowledge |
| 错误经验反复出现 | 修复 Memory |
| 不会执行稳定方法 | 新增或修订 Skill |
| 工具误用 | 改进 Tool Schema 或权限 |
| 无进展 | 调整 Loop、Plan 或模型 |
| 环境不一致 | 修复 Environment Contract |
| 完成误判 | 强化 Verifier |

**只有确定模型能力本身不足时，才优先更换模型或路由策略。**

回归集必须同时包含：原失败用例 + 相邻正常用例 + 安全对抗用例，防止局部补丁损害其他任务。**模型升级后，还应重新检查旧 Harness 中的补偿逻辑，删除已失效或阻碍新模型的规则。**

发布单元：Agent 版本、模型、System Context、Tool Schema、Skill、权限策略、Sandbox 镜像**进同一个发布单元**。每次变更先在固定数据集与环境中回放 → 灰度到受控流量 → 按 Outcome、成本、时延、越权率、人工介入率决定是否扩大发布。

## 验证

```bash
bash .claude/skills/agentscope-harness/scripts/verify.sh
```

## 来源

- blog 02《任务编排、长程推进与协作流转》410 行 · blog 03《上下文、状态与可复用能力资产》571 行 · blog 04《受控执行、验证反馈与交付准备》577 行（均 2026-09-28 抓取）
- 交叉印证：`/v2/zh/docs/harness/{plan-mode,compaction,memory,subagent,workspace,skill,sandbox,filesystem,channel}`、`/v2/zh/docs/building-blocks/{permission-system,middleware,context,tool,message-and-event}`
- 本项目对照：`.claude/skills/ipd-guard-fresh-verify`（完成由证据决定）、`.claude/skills/gen-test`（Verifier 与 mock 合法性）、`docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md`（独立模型验证的反面教材）
