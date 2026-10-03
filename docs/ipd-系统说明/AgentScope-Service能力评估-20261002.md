> 版本：1.1.0；2026-10-02 本轮纠正。下文旧评估保留供追溯，冲突时以下修订优先。SDK Workspace/Memory/Channel并非Service独有；它们在SDK/Harness/集成也有对应能力。External控制面回连可走私网，不一律要求公网；钉钉Stream和自托管Worker等可只出站。注册、会话可视化和任务派发是分别验收的能力，Service价值不只“漂亮目录”。当前本项目SDK进程内模式，不称External；不引入Service为当前建议，不是永久禁止或证明所有模式不可实现。锁定2.0.3与最新预览文档接口需要逐项兼容验证；不能由方法缺失推出所有适配方案不可能，不能承诺升级2.0.4即兼容。未来评估仅在真实跨应用治理需求、可部署发行物、版本兼容、权限/状态唯一权威和运维收益得到验证后启动。

# AgentScope Service 能力评估（2026-10-02）

> 证据基础：`/Users/mac/Documents/agentscope-java/docs/v2/zh/service/` **全部 47 篇 md 逐篇读完**（含 `cases/` 目录外的全部文件）。
> 标注约定：📄 = 文档声称（已实读原文）；✅ = 回到源码 / 本仓库实证。
> 本文只做评估，不改任何代码或配置。

---

## 1. AgentScope Service 是什么（大白话）

### 一句话

**AgentScope SDK 是「造机器的零件」，AgentScope Service 是「管一整间工厂的车间系统 + 调度中心 + 监控台」。**

- **SDK（我们已经在用的）**：一堆 Java 类。你在 Spring Boot 进程里 `HarnessAgent.builder()...build()`，然后自己 `agent.call()` 或挂进 SSE 流。模型怎么调、工具怎么批、上下文怎么压、跑到第几轮，全在**你自己的进程里、你的线程里、你的数据库事务边界内**。
- **Service（另一个产品）**：一整套**独立部署的服务端 + Web 控制台 + 数据库**。你把 Agent「注册」进去，它替你启动、执行、调度、记录、审批、发布成 API。像 Jira + CI + 网关的合体，用来管「公司里所有 Agent」这件事。

### SDK vs Service 对照表

| 维度 | AgentScope SDK（本项目现状） | AgentScope Service |
|---|---|---|
| 形态 | Maven 依赖（`io.agentscope:*`），**进程内库** | 独立服务（Gateway / Control / Dataplane / Scheduler + PostgreSQL + Web 控制台）📄 |
| 谁跑模型循环 | 你的应用线程 | Service 的 Dataplane 容器（Managed 模式）📄 |
| 部署 | 打进现有 `ruoyi-admin` jar，零新增组件 📄（现状） | 需另起一套栈：4 个应用组件 + PostgreSQL + 3 个持久卷；生产还要 Helm + K8s + RWX 存储 📄 |
| 生命周期 | 你的 bean 生命周期 | 控制面调度 → Scheduler → Attempt 租约 → 恢复 ✅（文档多处强调「重试是新的 Attempt」） |
| 状态持久化 | 你自己写（我们用 MySQL / trace_run / trace_node） | 平台 PostgreSQL（cp/rt/dp 三 schema）+ Workspace 卷 + Artifact 卷 📄 |
| Agent 身份 | 没有统一目录，靠 Spring bean | 控制台 Agent 目录 + agentKey + instanceKey + generation 📄 |
| 多 Agent 编排 | 自己写（本项目 4 个 `HarnessAgent` 装配点互不感知）✅ | Team（Leader 动态委派）/ Workflow（固定 DAG + approval 节点）📄 |
| 人工审批 | 自己写（本项目 Gate 双签在业务表里）✅ | Inbox 审批 + 工具确认 + Issue 验收三套独立流程 📄 |
| 权限模型 | 本项目 Sa-Token + IpdRolePermissionCatalog ✅ | 自己的账号/Namespace/角色体系（与 RuoYi 权限**完全平行、不互通**）📄 |
| 网络暴露面 | 无新增（进程内） | 必须有 Gateway 入口；External 模式还要求**控制面回连你的应用**（双向往返）📄 |
| 是否必须 | 不是必须，是可选的 agent 能力包 | 完全可选；不装也不影响 SDK 使用 |

### 关键澄清（文档反复强调的三条）

1. 📄 **Service 不替代 SDK**。官方原话：「目标不是替换你现有的 Agent 框架，而是提供一层统一控制面」。
2. 📄 **「注册进目录」≠「能干活」**。External Agent 明确分三层验收：目录身份 → 会话能力 → 工作执行。只注册不实现 `AgentTaskStarter` / `handle_agent_task`，就只有一个好看但没用的条目。
3. 📄 **预览版**。47 篇每篇顶部都是 `<Note>此为预览文档，正式版本尚未发布。</Note>`，Release 目录无 compose 制品 ✅。**API 不稳定。**
4. ⚠️ **文档代码示例与本项目锁定的 2.0.3 不兼容**（2026-10-02 新增实证，见 §5.3）。文档的接入示例用了 5 个 builder 方法（`tenant` / `instanceKey` / `registrationCredential` / `registeredIdentity` / `eventJournalDir`），**这 5 个在本项目使用的 2.0.3 中一个都不存在** ✅；任务派发入口 `AgentTaskStarter` / `HarnessAgentTaskStarter` 也是 2.0.3 之后（2026-09-10）才加入的 ✅。**照文档抄代码在本项目当前版本上编译不过。**

---

## 2. 47 篇文档的能力全景

| 能力 | Service 独有 / SDK 也有 | 本项目需要？ | 理由 |
|---|---|---|---|
| Agent 目录（agentKey/instance/namespace） | Service 独有 | ❌ | 本项目 4 个 agent 由 Spring 装配，身份即 bean 名，4 个都是内部实现，无跨团队/跨租户目录需求 |
| 控制台 Web UI | Service 独有 | ❌ | 本项目前端是 `ruoyi-ipd-web`（49 页 IPD 业务页），控制台形态与业务页面不匹配，等于多一套 UI 要维护 |
| Managed Agent（云端托管，零代码） | Service 独有 | ❌ | 我们的 4 个 agent 都深度耦合 IPD Service/Mapper/鉴权，无法搬进 Dataplane 跑 |
| Hosted Agent（连接 Codex/Claude Code/Qoder） | Service 独有 | ❌ | 那是「让外部 Coding Agent 干活」；IPD 是业务流程系统，不是研发工具链。兄弟会话刚删掉 42k 行自研 coding harness，说明方向是**收敛到 SDK**，不是**外接 Coding Agent** |
| External Agent（注册自家应用） | Service 独有 | ❌（暂） | 唯一技术上说得通的模式，但**文档示例在本项目锁定的 agentscope 2.0.3 上编译不过**（§5.3.1：5 个 builder 方法在 2.0.3 缺失 + 任务派发入口晚于该 tag）；且要求控制面**反向回连** `ruoyi-admin` 的 18090 合约端口 |
| Team（Leader 动态委派） | Service 独有 | ❌ | IPD 的多角色协作是**业务实体**（5 Gate 双签、69 动作、奖金池），靠业务表和状态机承载，不是通用 agent 编排 |
| Workflow（固定 DAG + approval 节点） | Service 独有 | ❌ | IPD 六阶段流程是业务硬约束 G-01~G-11，权威在 `docs/开发说明/` 与业务表，不在 agent 编排引擎 |
| Workspace（AGENTS.md/Skills/Subagents 共享） | Service 独有 | ❌ | 本项目已有 `.claude/skills/` + `.agents/skills/` 镜像 + `agentscope-harness` skill，双轨再加一层无收益 |
| Environment（local/sandbox/E2B/remote/self_hosted） | Service 独有 | ❌ | 私有部署**禁止**外发到 E2B 云沙箱；local 模式文档明说是「Dataplane 容器内部」而非宿主机，对我们无价值 |
| Memory Store（共享知识） | Service 独有 | ❌ | 本项目已有 RAG 知识库（Weaviate/Milvus/Qdrant）+ `ruoyi-common-chat` LocalKnowledge；再加一份平台知识库是**知识分裂** |
| Vault（凭据托管） | Service 独有 | ❌ | 本项目走 env 占位符 + `CredentialLiteralGuardTest` + hook 阻断，机制已成体系。Service Vault 依赖 master key 与数据库同备份，**多一份密钥即多一份泄露面** |
| Issue（工作项 + 验收） | Service 独有 | ❌ | 与本项目看板/需求/任务体系重复且概念冲突 |
| Inbox（通知/审批/验收） | Service 独有 | ❌ | IPD 的 Gate 双签在业务库中有完整审计留痕；Service 的审批记录在它自己的 PG 里，**与本项目审计链断开** |
| Endpoint（发布为带认证的 API） | Service 独有 | ❌ | 本项目已有 69 动作 REST + Sa-Token + API 加解密。Endpoint 只会造出**第二套身份体系**（X-API-Key / Bearer） |
| SSE 事件流 | SDK 也有（`ruoyi-common-sse`） | ❌ | 已有；Service 的 SSE 协议是它自己的 `seq/sequence` 帧，不能直接复用 |
| Session / Run / Attempt 模型 | Service 独有 | ⚠️ 可选 | 我们的 `trace_run` / `trace_node` + `argtrace.*` 已在做类似事。Service 的六层模型（Issue→Run→Node→Task→Attempt→Session）比现有细，但**迁移成本远大于收益** |
| Automation（Cron/Webhook 定时） | Service 独有 | ⚠️ 可选 | 已有 SnailJob 分布式任务 + `ruoyi-snailjob-server`，重复 |
| Channel（钉钉/飞书等消息平台） | Service 独有 | ❌ | IPD 是内网系统，无 IM 接入需求；且需要公网 HTTPS 回调 |
| Namespace 多空间 | Service 独有 | ❌ | 本项目已有多租户（`ruoyi-common-tenant`），但租户是**数据隔离**不是**Agent 资源空间**，两者语义不通 |
| 模型多 provider 接入 | SDK 也有 | ✅ 已有 | 本项目已用 `agentscope-extensions-model-*` + Langchain4j 多模型 |

---

## 3. 三种 Agent 模式对照与本项目归属

| | **Managed** | **Hosted** | **External** |
|---|---|---|---|
| 一句话 | 平台替你跑模型循环 | 平台调度、**你的电脑**上跑 Codex/Claude Code | 平台只登记，**你的应用**自己跑 |
| 你的代码在哪 | 不在平台（写 Instructions + 配工具） | 不在平台（provider 是现成 CLI） | 在你自己进程 ✅ |
| 网络方向 | 你 → Gateway | 你 → Gateway + Host → Gateway | 你 → Gateway **且 Gateway → 你的 18090 合约端口**（双向）📄 |
| 新增组件 | Dataplane 已在栈内 | 每台主机装 `agentscope` CLI + provider | 应用加 `agentscope-extensions-aistio` 依赖（**本项目 2.0.3 版接口不兼容，见 §5.3.1** ✅） |
| 能力上限 | 托管 agentToolset + MCP | provider 原生能力（各家不同）📄 | 取决于你实现的 adapter 能力📄 |

**本项目归属判定：**

- ❌ **Managed 不匹配**。IPD 的 4 个 `HarnessAgent` 全部需要注入 `IpdIdorGuard`、Mapper、多租户上下文、乐观锁校验——它们是**业务应用的一部分**，不可能脱钩成 Dataplane 里的托管 agent。
- ❌ **Hosted 不匹配**。这是研发工具链编排（GitHub Issue → PR → CI）。IPD 是产品经理工作平台，没有 Coding Agent 干活的场景。
- ⚠️ **External 技术上唯一可行，但当前不可用**。理由三条：
  1. ✅ **版本断层**（本轮新实证，最硬的一条）：本项目锁定 `agentscope.version=2.0.3`，而文档接入示例所需的 5 个 builder 方法（`tenant` / `instanceKey` / `registrationCredential` / `registeredIdentity` / `eventJournalDir`）在 2.0.3 中**全部不存在**；任务派发入口 `AgentTaskStarter` / `HarnessAgentTaskStarter` 由 2026-09-10 的 `be41da40` 引入，也**晚于 v2.0.3 tag（2026-09-07）**。即：**照文档写在本项目上编译不过**；不升级 agentscope 版本就用不了。
  2. 📄 External 要求控制面**反向回连** `ruoyi-admin` 的合约端口。在私有部署、内网、无公网 DNS 的环境里，这意味着要么给数据库应用开一个对外可达的 HTTP 端口（**攻击面显著扩大**），要么让 Service 与 ruoyi-admin 强行共享网络栈。
  3. 即使接上，得到的也只是「一个漂亮的目录条目 + 会话可视化」，拿不到 Team/Workflow/审批的价值（因为那些都要求任务派发能力；Python 侧文档明说**当前内置适配器没有实现 `handle_agent_task`** 📄，Java 侧的 `AgentTaskStarter` 在本项目版本里也不存在 ✅）。

**结论：本项目当前归属 = 都不需要。继续用 SDK 进程内装配。**

---

## 4. 对本项目的明确建议

### 结论：**不用**（维持 SDK 进程内模式），不设条件触发。

### 理由

1. **产品形态不匹配（首要）**。本项目是**单企业私有部署**的 IPD 产品经理系统（`CLAUDE.md` / `README-IPD-OVERRIDE.md` 明确）。Service 解决的问题是「公司里有几十个异构 Agent，需要一个中央管控台统一治理」——我们是**一个应用内的 4 个 agent**。这是个体量级错配，不是配置问题。

2. **成熟度 + 版本不匹配**。Service 全 47 篇文档均标注「预览文档，正式版本尚未发布」📄；本机无 Release 制品 ✅；**且文档的接入示例在本项目锁定的 agentscope 2.0.3 上编译不过**（§5.3 实证：5 个 builder 方法缺失、任务派发入口晚于该 tag）。要在本项目用 External，唯一路径是**先把 agentscope 从 2.0.3 升到 2.0.4+**——这是把 4 个生产装配点的 agent 底座整体换版本，不是加个依赖的事。

3. **网络暴露面代价（安全）**。引入 Service = 新增一个必须对外提供 HTTPS 的 Gateway（且要支持 SSE 长连接：代理须及时转发、禁缓存、设长超时 📄）。External 模式更进一步要求**控制面反向回连业务应用端口**。对一个承载 Gate 双签、KPI、奖金池、权限目录的私有部署系统，这是不成比例的风险扩张。

4. **体系分裂代价（治理）**。Service 自带账号 / Namespace / 角色 / 审批 / Issue 状态机，与本项目已有的 Sa-Token + IpdRolePermissionCatalog + 审计链**完全平行**。上 Service 之后，「谁批准了这次执行」这个问题的答案会分散在两个系统两份日志里。本项目 `SEC-API-01` 那类审计连续性工作会倒退。

5. **重复建设**。Automation vs SnailJob、Memory Store vs 现有 RAG、Workspace vs 已有 skills、Endpoint vs 已有 69 动作 REST——每一项本项目都有对应物。引入即多一套要维护、要备份、要升级的东西。

### 如果一定要用，唯一合理的条件（供 owner 拍板时参考）

同时满足以下 4 条才值得启动评估（任缺一条即维持不用）：

1. 出现**真实的跨组织/跨部门 Agent 治理需求**（例如多个子公司各自部署 agent，需要一个中心台看全景、统一凭据、统一审计）；
2. Service 1.0 正式发布且 `agentscope-extensions-aistio` 进入 Maven Central；
3. 部署环境能提供**合规的双向网络**（Service → ruoyi-admin 合约端口），且经过安全评审；
4. 明确只走 **External** 模式（不迁 Managed、不上 Hosted），把 Service 降级为「可选的旁路观测端」，**任何情况下不成为 agent 执行的必经路径**。

即便如此，引入的也只是「可视化 + 目录」，不改变现有 4 个装配点。

### 明确不做的事

- ❌ 不在 `pom.xml` 加 `agentscope-extensions-aistio`
- ❌ 不部署 Service 的 docker-compose / Helm
- ❌ 不为 Service 改造 4 个 `HarnessAgent` 装配点
- ✅ 维持：SDK 2.0.3 进程内装配 + `service/coding/harness/{model,tool}` 13 文件 609 行的工具治理契约 ✅

---

## 5. 未实证清单

### 5.1 文档读取情况

- ✅ **47 / 47 篇全部读完**，无「未读到」项。清单：access、agents、api-reference、automation、channels、configuration、connect-hosted-agent、create-managed-agent、create-team、endpoints、environments、external-agent(-configuration/-execution/-frameworks)、first-session、hosted-agent(-configuration/-execution/-providers)、inbox、index、integrations、issues、kubernetes、managed-agent(-capabilities/-configuration/-execution)、managed-harness-task-outcomes、memory、operations、quickstart、register-agentscope-agent、releasing、runtime-host、sessions、sse-events、team-collaboration、team-configuration、team-execution、teams、troubleshooting、usecases、vault、workflows、workspaces。
- ✅ **`cases/` 3 篇补充读完**（2026-10-02 第二轮）：sdlc-team（全 Hosted，GitHub Issue→PR→CI→Approve→合并）、order-fulfillment（全 External，5 个 Java 应用组 Team，跨订单/库存/物流/售后）、presales-team（Managed 起步再扩 External + Hosted）。三篇均标注「基础资料可直接用于练习，模型输出、混合协作和文件交付仍需在实际部署中验证」📄。
  - **对结论的影响：无。** 三篇场景（研发闭环、跨系统异常处置、售前方案）**没有一个是 IPD 产品经理工作流**，进一步支持「场景不匹配」的判断。sdlc-team 是纯 Coding Agent 编排，正是本项目明确不需要的方向。

### 5.2 文档声称但未在本仓库实证

| 声称 | 状态 |
|---|---|
| Service 正式版发布 | ❌ 47 篇均标「预览文档，正式版本尚未发布」📄 |
| Release 页面有 compose 制品 | ❌ `agentscope-service/release/dist/` 为空 ✅ |
| Managed Agent 可用非 DashScope 模型（Anthropic/Gemini/Ollama 等） | ❌ 文档自述「标准 Service 镜像不因 SDK 存在扩展就自动包含全部 provider」，需**自定义 Dataplane 发行包**📄 |
| Python 内置适配器支持任务派发 | ❌ 文档明确「当前 Python 内置适配器**没有实现** `handle_agent_task`」📄 |
| Helm Chart 多副本 HA / 无停机升级 | ❌ 文档明确「当前 Chart 的单副本安装**不提供**多副本 HA 或无停机升级保证」📄 |
| Go 控制面 schema 迁移回滚安全 | ⚠️ 文档称「组件镜像回退**不保证**旧版本可读取新 schema」📄（风险提示，非能力声称） |

### 5.3 源码侧已实证

- ✅ 本项目 `pom.xml` 使用 `agentscope.version = 2.0.3`；`~/.m2` 已有 `agentscope-harness`、`agentscope-extensions-model-*`（openai/dashscope/ollama/anthropic）等。
- ✅ 4 个 `HarnessAgent.builder()` 生产装配点：`AgentScopeChatKernel` / `CodingServiceImpl` / `AgentScopeProjectAgentKernel` / `PocKernelSupport`（另 3 处在 test）。
- ✅ `service/coding/harness/{model,tool}` 13 文件 **609 行**（`ToolPolicyEngine` 134 行为最大单文件），确认是工具权限/审批/效果状态的自研治理契约。
- ✅ `agentscope-java` 仓库 HEAD = `e9721285`（2026-10-01，version `2.0.4-SNAPSHOT`），`agentscope-service/` 下确有 `aistio`（Go，`go.mod` 存在）+ `service-gateway` / `service-dataplane` / `service-scheduler` / `service-common`（Java）+ `frontend` + `helm` + `docker-compose.yml`，与文档描述的四组件架构一致。

### 5.3.1 **版本断层实证（本轮新增，结论的关键支撑）**

`agentscope-extensions-aistio` 模块**在仓库中确实存在**（首次提交 `e5d5cf87`，2026-07-30），**v2.0.3 tag 时已有 37 个 Java 源文件** ✅——但**功能与文档描述的接口不一致**：

| 文档 `external-agent-configuration.md` 给出的 builder | v2.0.3 是否存在 | HEAD(2.0.4-SNAPSHOT) 是否存在 |
|---|---|---|
| `tenant` | ❌ 无 | ✅ |
| `instanceKey`（v2.0.3 叫 `instanceId`） | ❌ 无此名 | ✅ |
| `registrationCredential` | ❌ 无 | ✅ |
| `registeredIdentity(agentId, bindingId, generation)` | ❌ 无 | ✅ |
| `eventJournalDir` | ❌ 无 | ✅ |

| 任务派发能力 | v2.0.3 | HEAD |
|---|---|---|
| `AgentTaskStarter` / `HarnessAgentTaskStarter` | ❌ **不存在** | ✅ 存在 |

- `AgentTaskStarter` 引入提交 = `be41da40`（2026-09-10，*Introduce new designed AgentScope Service implementation*）
- v2.0.3 tag 日期 = **2026-09-07** → **晚于本项目锁定版本 3 天**
- v2.0.3 时代对应的是 `TeamSessionStarter` / `HarnessTeamSessionStarter`（Team 协作上下文），**不是**任务派发入口

**结论（本项目最硬的一条「不用」理由）**：
> External Agent 接入方案，**照官方文档抄代码，在本项目当前的 agentscope 2.0.3 上编译不过**。
> 要用，必须先把 agentscope 从 2.0.3 升到 2.0.4+（当前还是 SNAPSHOT），而这会动到本项目 4 个生产 `HarnessAgent` 装配点的底座版本——为「多一个可视化目录」冒这个险不划算。

### 5.4 本评估未做（留待 owner 决策后另行安排）

### 5.4 本评估未做（留待 owner 决策后另行安排）

- 未实际部署 Service 做 PoC（无 Release 制品，且 Service 为预览版）
- 未验证 External Agent 双向往返网络在本项目私有部署环境的可行性
- 未验证 `agentscope` 2.0.3 → 2.0.4 升级对本项目 4 个 `HarnessAgent` 装配点的影响（版本断层是既成事实，升级代价未量化）
- 未核实 agentscope-extensions-aistio 是否已发布到 Maven Central（本地 `~/.m2` 无此 artifact，但**这不足以证明未发布**——本地仓库只装过项目实际依赖的包。v2.0.3 源码中存在该模块，故「未发布」的旧判断已从文档中移除）
