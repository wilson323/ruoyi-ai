# Mem0 与 HayStack 接入前置契约（2026-09-29）

> 触发：owner 拍板「D2b §2.4 四扩展判定中，Mem0/HayStack 若推进，先立两项前置契约」。
> 本文档是**立项前置件**，不是接入实施件——在本文契约落地并通过负例验证之前，D2b §2.4 的
> 「Mem0 暂缓 / HayStack 保留自研 RAG 权威」判定维持不变。判定口径与 ADR-0075 矩阵同源（替换/包装/保留三选一）。
> 上游事实源：`docs/ipd-系统说明/验收/D轮-生产就绪独立验证-20260929/D2b-*.md` §2.4；owner 提供的官方扩展文档原文（Mem0 / HayStack）。

---

## 1. 四维隔离键 → Mem0 三维 ID + metadata 映射契约

### 1.1 缺口陈述（为什么必须先立契约）

- 本项目内核隔离键为**四维**：`project × user × agent × session`，唯一收口点
  `KernelScopeKey.of(...)`（折叠产物 `p{projectId}:u{userId}` + `a{agentId}:s{sessionId}`，
  段内含 `':'` / `'..'` fail-closed 拒绝；真链证据见 §3）。
- `agentscope-extensions-mem0` 的 `Mem0LongTermMemory` 身份面只有**三维** ID：
  `agentName / userId / runName`（三选一至少其一，不足抛 `IllegalArgumentException`），
  外加 `metadata`（同时作用于写入与检索过滤）。
- 直接映射不可行的原因：四维压三维必有维度被降格，降格维度若只靠调用方"记得传"就是串桶漏洞。
  契约的核心就是规定**哪一维进哪个槽、降格维靠什么机械保证**。

### 1.2 映射表（唯一合法映射，其他映射视为违规）

| 本仓维度 | Mem0 落点 | 取值规则 | 保证机制 |
|---|---|---|---|
| project × user | `userId` | **恒取** `KernelScopeKey` 折叠产物 `compositeUserId = "p{projectId}:u{userId}"`，禁止手拼、禁止只传裸 userId | 四维收口铁律 #1：业务代码只准经 `KernelScopeKey`；裸 userId 进 Mem0 = project 维丢失 = 跨项目泄漏 |
| agent | `agentName` | 恒取 `agentId` 原值（与 `compositeSessionId` 的 `a` 段同源） | builder 装配期一次性注入，运行期不可变 |
| session | `runName` | 恒取原始 `sessionId`（非折叠串） | 会话级记忆生命周期；跨会话长期记忆本就应聚合到 user 层（见 1.4 语义声明） |
| 全四维 | `metadata` | `{project_id, user_id, agent_id, session_id}` 四键**写读双侧**都必填 | Mem0 metadata 同时作用写入与检索过滤——这是检索侧的第二道闸，不得因"已有三维 ID"省略 |

**字符集实证项（接入前必做，属 BLOCKED_ENVIRONMENT 前置）**：`compositeUserId` 含 `':'`，
Mem0 PLATFORM 与 SELF_HOSTED（`Mem0ApiType` 两型）对 ID 字符集的处理未实证。须先各跑一发
`add + search` 往返证明 `':'` 不被转义/截断，才能启用本映射；若平台侧拒绝 `':'`，映射契约作废重议
（**禁止**未经契约变更就把 `':'` 静默替换成其他字符——那会造出第二套折叠语法，违反收口铁律）。

### 1.3 串桶负例清单（验收用例，全部须实跑为红→绿闭环）

| # | 场景 | 断言 |
|---|---|---|
| N1 | 同 user 异 project 各写一条记忆，以 `pP1:uX` 身份检索 | 检索结果**零命中** P2 项目写入的记忆 |
| N2 | 同 project×user 异 agent 写入，以 `agentName=A1` 检索 | 不串到 A2 写入（若 Mem0 三维 ID 语义允许跨 agent 召回，须在 metadata 过滤层补闸，仍判 FAIL 直到补闸通过） |
| N3 | 检索请求缺失 metadata 四键中的任一键 | 调用**拒绝发起**（客户端装配层抛错），不得降级为无过滤全库检索 |
| N4 | 段值含 `':'` / `'..'` 的 project/user 注入 | 在 `KernelScopeKey.of(...)` 即被拒，**根本到不了** Mem0 客户端（防线唯一性断言：Mem0 装配层不加第二套校验） |
| N5 | 同 project×user 异 session（N5 是**正例**） | 长期记忆跨会话可见——这是 LongTermMemory 的契约语义，不是缺陷；session 隔离由 runName 维的写入归属保证，检索侧不做 session 过滤 |

### 1.4 数据出境与保留（owner 拍板项，接入前必须单列决策）

`Mem0ApiType.PLATFORM` 意味着记忆内容（含 `compositeUserId` 可反解出的租户身份）发往域外 SaaS。
接入立项时必须在 ADR 中二选一并记录：PLATFORM（便利、数据出境）或 SELF_HOSTED（本地部署、自管保留策略）。
本项未拍板前，1.2 映射即使全部实证通过也不得合入生产装配。

### 1.5 与既有波次的关系

- 前置顺序：W 波次先开 AgentScope **内生 Memory**（MEMORY.md + memory/，§2.4 判定"暂缓 Mem0"的另一半理由）；
  Mem0 仅在出现"跨 agent 长期事实记忆"真实需求时按本契约评估。
- 本契约不新增门禁/台账（防过度设计）：N1~N5 以普通回归测试类落地即可，命名建议
  `Mem0ScopeMappingTest`（`@Tag("dev")`，遵循 gen-test 规约）。

---

## 2. RAG 写链防双写核对（HayStack 接入前置）

### 2.1 本仓写链现状盘（2026-09-29 现查，两条独立写链）

| 链 | 唯一写入者 | 落点 | 说明 |
|---|---|---|---|
| IPD 项目文档嵌入链 | `org.ruoyi.ipd.service.AiDocEmbeddingService`（ruoyi-ipd） | MySQL `ai_doc_embedding`（`embeddingMapper.delete + insert`，`embedAsync` 单线程 executor） | 检索侧 `retrieveContext(projectId, docType, query)` 同表读取 |
| chat 知识库向量链 | `org.ruoyi.service.knowledge.impl.*` → `VectorStoreServiceImpl` → `VectorStoreStrategyFactory` 策略 | qdrant / weaviate / milvus（启动日志实证三策略注册） | 知识库附件/片段向量化 |

两条链**互不重叠**（业务域与存储都不同），但对外而言它们是"本仓 RAG 写权威"的完整清单——
HayStack 若接入，任何写入都必须归入其中一条既有链的写入者，**不允许长出第三条链**。

### 2.2 HayStack 官方契约要点（管控分离）

- `HayStackKnowledge.addDocuments()` 抛 `UnsupportedOperationException`：索引流水线（切分、embedding、
  落库）归 HayStack 服务端 own，SDK 侧只有读取（检索）面。
- 关键参数 `baseUrl / topK / filterPolicy` 均为检索侧配置。

### 2.3 防双写规则（接入判定的前置约束）

1. **只读适配**：HayStack 若接入，形态是「包装」检索面（实现 `Knowledge` 接口），写链零改动。
   本仓文档永不双写（既不推 HayStack pipeline，也不因 HayStack 存在而停既有链——除非走 2.4 迁移案）。
2. **权威唯一**：同一文档集只允许一条链拥有索引权威。若某域（如 IPD 项目文档）要转 HayStack 权威，
   必须先立迁移 ADR：写明旧链（`AiDocEmbeddingService`）在该域降级为只读/退役的时点与回滚路径，
   未过 ADR 之前双写即违规。
3. **入口守卫走代码评审而非新门禁**：评审清单加一条"新增 vector/embedding 写点必须先对照 §2.1 清单认领写入者"
   （不新建扫描门禁——两条既有链各有归属测试，过度机制化违反 owner「避免过度设计」纪律）。

### 2.4 接入前待核项（BLOCKED 清单）

- [ ] HayStack 服务的 `filterPolicy` 能否表达本仓 project/tenant 二维过滤（否则多租户检索隔离不成立，接入否决）；
- [ ] chat 链三策略（qdrant/weaviate/milvus）中实际启用的是哪个（`application*.yml` 现查随部署态变），迁移案以启用者为基准；
- [ ] gRPC 1.62.2 钉版与 HayStack 客户端依赖冲突预检（AGENTS.md：升级/新增依赖前先重测 Milvus 面）。

---

## 3. 关联证据（本文档引用）

- D9 真链 HTTP 证据（2026-09-29 重启窗口，`chat.kernel.poc.enabled=true` + 换票链
  `/api/v1/auth/login → /api/v1/auth/platform-token`）：
  `docs/ipd-系统说明/验收/D轮-生产就绪独立验证-20260929/d9-poc-truechain-20260929.json`（含取证脚本 `d9-poc-truechain.py`）。
  三例结论：①未登录 → 拦截器 401，无 scope 帧、无默认身份；②登录态 → `event:scope data:pP1:u1:aemp-a1:sS-scope`
  （`u1` 源自会话，非参数自报）+ 真模型流 `text_delta → AGENT_END`；③`projectId=P1:evil` →
  `KERNEL_ERROR: [KernelScopeKey] projectId must not contain ':'` fail-closed。
- §1.2 映射契约的可执行前提（`':'` 收口）已由例③在真链坐实：内核唯一收口点先于任何扩展客户端拒绝注入段。

## 4. 结论与拍板留痕

- 本契约文档落档 = 「两项前置」立项完成；Mem0/HayStack 判定仍为 **暂缓 / 保留自研权威**（D2b §2.4 不变）。
- 推进条件：Mem0 = §1.2 实证项 + N1~N4 负例全绿 + §1.4 出境拍板；HayStack = §2.4 三项核对完成 + ADR 立「包装」决策。
- 触发本次收口的 owner 指令：「都做，你来重启」（2026-09-29）。
