# harness-patterns — 架构范式与责任边界

> 来源：《如何构建 Agent Harness（一）：架构范式与责任边界》
> `https://java.agentscope.io/v2/zh/blogs/how-to-build-agent-harness/01-patterns`
> 节结构按 `.claude/skills/_templates/IPD-SKILL-DISCO-TEMPLATE.md` 的 6 槽形态适配（"怎么修"→"怎么做"，因为本篇是范式而非踩坑）。

## 是什么（一句话）

Harness = **模型之外、围绕 Agent Loop 组织上下文、能力、状态、环境与控制机制，并把模型判断转化为可执行、可恢复、可验证任务过程的代码、配置和执行逻辑**。

## 为什么（根因：一次模型调用天然缺四样东西）

| 缺失 | 后果 |
|---|---|
| 可靠的跨轮状态 | 不知道任务是否已在其他节点推进 |
| 无限上下文 | 长任务事实无法自然保留 |
| 执行授权语义 | 工具调用只表达"意图"，不等于当前用户有权执行 |
| 完成证据 | 模型可以声称完成，但证明不了文件已生成 / 测试已过 / 订单已提交 / 审批已生效 |

所以 Harness 的职责就是：**把概率性的模型判断嵌入确定性的系统边界**。

三条常见误解必须排除：

1. Harness ≠ 更长的 System Prompt。Prompt 只是它某一轮生成的输入之一；它还包括任务状态机、工具注册、计划管理、权限检查、环境适配、事件处理、错误恢复、完成验证等确定性逻辑。
2. Harness ≠ 某个 Agent Framework 的同义词。Framework 可以帮你实现 Harness；成熟 Coding Agent CLI/SDK 可以提供现成 Harness；Managed Agent 可以连执行服务一起托管。Harness 描述的是"Agent 如何工作"的系统层，不是产品形态。
3. Harness ≠ Runtime ≠ Sandbox。Harness 决定"下一步给模型什么、允许它提出什么行动、怎么推进"；Runtime 负责持续承载；Sandbox 负责把实际行动限制在可控环境。三者协同，责任不同。

## 怎么识别（五层责任边界表 — 判故障归属用）

| 层次 | 核心问题 | 主要责任 |
|---|---|---|
| Model | Agent 能理解和推理到什么程度 | 语义理解、规划判断、生成与工具选择 |
| **Harness 编排** | **Agent 如何工作** | Loop、Context、State、Plan、Tool、Skill、Subagent、Permission、Verification |
| Runtime | Agent 如何持续运行 | 进程承载、任务调度、并发、等待与故障恢复 |
| Sandbox / Environment | Agent 在哪里行动、影响范围多大 | 文件、进程、网络、Secret、资源和环境隔离 |
| Agent Platform | 如何规模化交付和治理 | 多租户、发布、网关、配额、观测、评估、安全和运营 |

**边界不一定对应五个独立部署单元**（一个 SDK 可同时含 Harness + 本地 Runtime，托管服务可同时给 Harness + Runtime + Sandbox），但架构设计中必须保留边界——否则无法判断故障归属、数据位置、迁移成本和最终责任。

## 怎么做

### A. 8 类构建对象检查表（选路径 = 决定哪些自建 / 哪些复用 / 哪些托管）

| 构建对象 | 开发阶段要回答 | 主要交付物 |
|---|---|---|
| Agent Contract | 为谁工作、目标是什么、允许和禁止什么 | 角色指令、任务输入输出、成功标准 |
| Execution | 模型怎样循环、规划、等待、委派、结束 | Agent Loop、状态机、预算、Verifier |
| Context & State | 每轮看见什么、任务事实存哪 | Context Policy、Session/Task Schema、Workspace、Memory |
| Capability | 可用哪些方法和外部能力 | Tool Schema、MCP、Skill、Subagent、能力目录 |
| Environment | 文件/命令/浏览器/企业系统在哪运行 | Environment Contract、Sandbox、Artifact 边界 |
| Control | 以谁的身份行动、哪些动作拒绝或审批 | Permission Policy、HITL、短时凭证 |
| Interaction | 用户和上层应用如何看进度、干预、恢复 | Event Schema、Streaming、Channel、恢复游标 |
| Quality | 如何证明完成、如何判断新版本更好 | Trace、Outcome、测试用例、评估基线 |

最小 Agent 可以只实现一部分；**进入企业生产后这 8 项都必须有明确责任人**。归纳为三个能力域：执行与编排 / 上下文与状态 / 行动与反馈。

### B. 先定路径控制方式，再选运行形态（两个正交维度，常被混为一谈）

路径控制方式：

- **Workflow**：步骤、分支、异常在设计时已明确
- **Agent 主导**：执行路径必须按中间结果和环境反馈动态决定
- **Hybrid**：高风险主流程（审批 / 交易 / 发布边界）由 Workflow 固定，检索 / 分析 / 方案生成交给 Agent

任务形态（Single-Agent / Long-Horizon / Multi-Agent）决定所需的状态与协作契约；运行形态决定这些能力部署在哪。**两者不并列。**

### C. 四种运行形态选型表（`HarnessAgent` 的部署视图，非产品分类，可渐进演进）

| 运行形态 | AgentScope 入口 | 企业直接控制 | 需补齐的运行能力 | 企业仍承担的责任 | 适合条件 |
|---|---|---|---|---|---|
| 本地工作区 | `HarnessAgent.builder()` + Local Filesystem | Loop、Context、Plan、工具与验证逻辑 | 本地进程、文件、开发者交互 | 任务正确性、安全配置、回归测试 | 单人开发、原型验证、可信环境 |
| 嵌入式应用 | `call` / `streamEvents` + `RuntimeContext` | 业务入口、Session 映射、权限、事件展示 | 应用进程、共享状态、业务工具 | 多租户接入、凭证、Artifact、Outcome | 接入现有 API / 工作台 / Channel |
| 长任务服务 | `HarnessAgent` Worker + StateStore + Sandbox | 任务调度、恢复点、预算、并发、完成门禁 | Worker、任务队列、隔离环境、事件存储 | 幂等、故障恢复、审批、数据边界、结果验收 | 异步、批量、跨请求长任务 |
| 平台化交付 | 版本化的 HarnessAgent 定义 + 工作区资产 | Agent 目录、资源组合、发布范围、质量基线 | 多租户 Runtime、网关、观测、评估 | 业务目标、所有权、风险策略、最终验收 | 多团队多 Agent 规模化运营 |

**演进路径**：本地验证过的 `HarnessAgent` → 嵌入业务服务 → 由任务队列调度到独立 Worker → 定义 / 工作区资产 / 权限策略 / 质量基线纳入统一平台。

选型四问：①任务效果是否依赖修改 Loop 或 Context；②数据和执行环境能否集中承载；③团队是否愿意维护状态恢复与 Sandbox；④最终交付对象是个人工作区、嵌入式应用、异步任务服务还是平台内业务 Agent。

### D. 三条构建路径的适用边界

| 路径 | 适合 | 不适合 / 必须自己补 |
|---|---|---|
| 高代码框架自建（AgentScope Java） | 业务逻辑独特；数据或执行环境不能外托；需要改 Loop / Context 策略；想沉淀统一 Agent 技术底座 | 框架只给构建材料，**不会自动补齐**多租户隔离、状态恢复、Sandbox、安全策略、评估基线、业务验收。要求团队具备模型应用 + 分布式 + 安全 + 效果评估能力 |
| 工作区资产组装 | 任务模式相近，差异主要来自领域指令和能力资产 | 需要改 Agent Loop、Context 编译、状态语义或权限决策时，**必须回到代码层扩展 Builder 与 Middleware**，不要把复杂控制塞进工作区文本 |
| Agent Platform 托管 | 多团队多 Agent，需要统一目录 / 身份 / 资源 / 版本 / 任务 / 质量事实 | 平台**不能替业务定义任务目的、正确性和最终责任**。"版本运行成功"只说明调用链结束，不代表业务 Outcome 达成 |

### E. 能力叠加的正确姿势：定契约，不是堆 Builder 方法

真正的构建工作不在于调用多少 Builder 方法，而在于**定义能力之间的契约**：

| 能力 | AgentScope 构建入口 | 应用团队必须决定 |
|---|---|---|
| 指令与上下文 | `AGENTS.md`、附加 Context、Middleware | 指令层级、动态信息、Token 预算、冲突规则 |
| 状态与记忆 | Workspace、StateStore、Memory、Compaction | Session/Task 边界、写入规则、恢复、多租户隔离 |
| 计划与任务 | Plan Mode、Todo、Task State | 何时先规划、谁批准、阶段目标如何验收 |
| 能力资产 | Tool、Skill Repository、Subagent | 能力发现、版本、权限、委派、输出契约 |
| 执行环境 | FileSystem、Docker 或其他 Sandbox | 文件、网络、Secret、资源、快照、隔离范围 |
| 控制与交互 | Permission、Channel、Middleware | ALLOW / DENY / ASK、用户干预、事件映射 |
| 质量与反馈 | Trace、Verifier、评估接口 | 完成证据、观测字段、版本回归标准 |

**分工原则**：稳定确定性的步骤 → 沉淀为 Tool / 脚本 / 策略；需要理解目标和权衡方案的部分 → 留在 Agent Loop；可能影响外部世界的动作 → 统一过权限和 Sandbox。这样 Harness 才有可测试边界，而不是"由 Prompt 驱动的一组隐式行为"。

**从任务成功标准反推能力，不要一次全开**。示例：漏洞修复 Agent 至少要读代码、生成补丁、执行测试、验证安全扫描；若"计划未确认不能改代码"→ 需要 Plan Mode + Permission；若分析与评审可并行 → 需要 Subagent；若跨多个调用 → 需要外置状态 + 可恢复 Workspace。

### F. 工作区资产 = 可发布资产（版本化纪律）

- `AGENTS.md`、Skill、子 Agent 规格、测试样例、策略文件 → **与 Harness 代码一起版本化**
- Memory、Session 快照、任务 Artifact → 按作用域写入运行存储
- 发布时固定代码版本 + 资产版本；**回滚时同时回滚**，避免"代码已回退但 Prompt / Skill / 权限规则仍是新版本"
- 多 Agent 差异优先体现在任务契约、工作区资产、工具权限、Subagent 规格，**而不是复制整套运行框架**（漏洞修复 / 数据分析 / 评审 Agent 可共享状态、Sandbox、事件、评估基础，只替换各自 `AGENTS.md` + Skill + Verifier）

### G. 分布式化的逻辑契约（Runtime 实现前 Harness 必须先定义）

在线实例**不应依赖进程内消息历史恢复任务**。必须：把 Session / Task / Plan / 子任务 / Artifact 映射到共享状态接口；为同一任务设置并发写入或执行租约；Worker 失效后从安全点恢复；按租户创建或复用 Sandbox；用短时身份访问企业工具。物理存储、调度、容灾属于 Runtime，但逻辑契约属于 Harness。

## 验证

```bash
bash .claude/skills/agentscope-harness/scripts/verify.sh
```

代码侧断言见 [agentscope-java.md](agentscope-java.md) 的"四维键收口"与 `scripts/harness-contract-check.sh`。

**Build 阶段最小交付物自检**（Framework 路径）：可测试的 Harness 代码 · Agent Contract · 状态 Schema · Context Policy · Tool 与 Skill 清单 · Environment Contract · Permission Policy · Verifier · 事件模型 · 回归用例。缺项就在卡面标 `PARTIAL`，不许标 done。

长任务形态额外交付：Task Schema · 状态转换规则 · 任务与 Session 映射 · 事件模型 · 租约策略 · 恢复点 · Sandbox Contract · Artifact 生命周期 · Verifier。**若这些对象仍只存在于 Prompt 或进程内存中，服务就无法可靠恢复。**

## 来源

- 博客一《架构范式与责任边界》全文（抓取时间 2026-09-28，`/v2/zh/blogs/how-to-build-agent-harness/01-patterns.md`，397 行）
- 文档索引 `https://java.agentscope.io/llms.txt`
- 系列后续：`02-task-orchestration`（任务契约）、`03-context-and-state`（信息契约）、`04-controlled-execution`（行动契约）→ 见 [engineering-contracts.md](engineering-contracts.md)
