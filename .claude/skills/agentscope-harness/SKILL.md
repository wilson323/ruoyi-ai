---
name: agentscope-harness
description: AgentScope Java 2.0 HarnessAgent 的架构范式与工程契约技能包。用于设计、评审或落地 agent harness——五层责任边界（Model/Harness/Runtime/Sandbox/Platform）、任务状态机与多维预算、Context 管线与压缩、Action Plane 与 Permission 三态、Verifier 完成门禁、Trace→Harness Patch 反退化闭环，并给出本仓已实证的 AgentScope 2.0.3 API 面与四维隔离键收口规则。当用户要构建/改造/评审 agent harness、接入或替换 AgentScope 内核、设计 subagent/skill/memory/plan mode/workspace/多租户隔离、判定 agent 任务"完成"标准、或排查 harness 越权/上下文溢出/状态无法恢复时使用。
---

# agentscope-harness

把"裸 ReAct 循环"升级为"可执行、可恢复、可验证的任务过程"的工程契约入口。

一句话原则：**模型只能申请完成，Harness 才提交完成；能力按需披露，副作用统一过 Action Plane。**

## 何时使用

命中任一条就先进本 skill，再动手：

- 新建 / 改造 / 评审 agent harness（本仓 `poc/agentscope-kernel` 及后续接入分支）
- 引入或升级 `io.agentscope:*` 依赖、动 `HarnessAgent.builder()` 装配
- 设计 RuntimeContext / 多租户隔离键 / StateStore / Workspace / Filesystem 模式
- 设计 Skill、Subagent、Plan Mode、Memory、Compaction 中的任一项
- 定义"这个 agent 任务算不算完成"、写 Verifier、做跨版本评估
- 排查：越权执行、上下文溢出、断线后无法恢复、工具重试造成重复副作用

## 本仓现状（先读，防双轨）

- **已有一套自研 harness**：`org.ruoyi.service.coding.harness`（17 子包 / main 树 254 个 `.java`，**零** `io.agentscope` 引用）。其 `PolicyDecision` / `ToolEffectLedgerReconciler` / `CanonicalPlanHasher` / `HarnessBudget` / `ContextEngine` 已分别对应本 skill 的 Permission 三态 / 副作用幂等 / Plan 版本 / 预算 / Context 压缩契约。**禁止让 AgentScope 变成并行第二套 harness**；先用本 skill 当审计透镜盘点它，缺口按契约补。
- **AgentScope 内核替换在 PoC 分支**：`poc/agentscope-kernel`（worktree `.worktrees/poc-agentscope-kernel/`），G1~G5 已过，`io.agentscope:*` 只在该树声明。
- **单轨纪律**：内核替换必须做「替换 / 包装 / 保留」三选一的显式决策并记 ADR，禁止自研与 AgentScope 两套并行演进。
- 逐类能力对照表见 [references/agentscope-java.md](references/agentscope-java.md)。

## 第一步：先固定 Agent Contract（不许跳过）

不先写契约就选框架 / 开工具，是本 skill 要根除的头号病根。契约必须回答 6 问，缺一即 `DRAFT_ONLY`：

1. 为谁工作、目标是什么（**Task ≠ Session**，Task 才是可验收对象）
2. 输入 / 输出 / 交付物形态
3. 允许影响哪些系统、哪些动作必须 **DENY**、哪些必须 **ASK**
4. 什么**证据**能证明完成（不是"模型说完成了"）
5. 预算上限：步数 / 时长 / token / 费用 / 工具次数 / 并发
6. 失败语义：何时重试、何时降级、何时转人工

模板直接抄 [examples/agent-contract.md](examples/agent-contract.md)。

## 路由表（按"我现在要做什么"查）

| 我要做什么 | 打开 | 关键判据 |
|---|---|---|
| 选运行形态 / 定责任边界 / 判断该不该自建 harness | [references/harness-patterns.md](references/harness-patterns.md) | 五层边界表 + 四形态选型 + 8 类构建对象检查表 |
| 设计任务状态机、Plan/门禁、Subagent 委派、Context 预算、Action Plane、Permission、Verifier、Evaluation | [references/engineering-contracts.md](references/engineering-contracts.md) | 三份工程契约：任务 / 信息 / 行动 |
| 写 `HarnessAgent.builder()`、`RuntimeContext`、`MysqlAgentStateStore`、SSE 出口；查依赖冲突雷 | [references/agentscope-java.md](references/agentscope-java.md) | 本仓实证 API 面（标注"已实证 / 仅文档"）+ 四维键收口 + okhttp/langchain4j 棘轮 |

## 五条铁律

1. **四维隔离键唯一收口**：`project × user × agent × session` 一律经 `KernelScopeKey.of(...)`，业务代码禁止手拼 `userId + ":" + sessionId`；段内含 `':'` 或 `..` 必须 fail-closed 拒绝。
2. **权威状态外置**：任务状态机（CREATED/RUNNING/WAITING_*/PAUSED/VERIFYING/COMPLETED/FAILED/CANCELLED）落库或落盘，不以消息历史为唯一来源；`WAITING` 不是失败，`PAUSED` 不是结束。
3. **完成由证据决定**：Verifier 返回结构化缺口 + 可寻址证据路径；缺证据只能停在 `VERIFYING`，禁止直接 `COMPLETED`。
4. **副作用先问"上次到底发生没有"**：有副作用的动作重试前必须查幂等键 / 真实系统状态，无法证明未执行就不许重复提交。
5. **反退化不改 Prompt 了事**：线上失败走 Trace → Failure Cluster → Diagnosis → **Harness Patch**（Context/Memory/Skill/Tool Schema/Policy/Environment/Loop/Verifier 七类之一）→ Regression → Release Gate。

## 验证（上岗前必跑，两条都期望 EXIT=0）

```bash
bash .claude/skills/agentscope-harness/scripts/verify.sh; echo "EXIT=$?"
# 期望 0。三段：env-probe（环境探测，缺工具只 WARN）/ skill-lint（本 skill 自身契约 L1~L8）
#               / harness-contract-check（代码侧契约 C1~C8）

bash .claude/skills/agentscope-harness/scripts/verify.sh --self-red; echo "EXIT=$?"
# 也期望 0。它播种五组正反控：违规样本→必红 / 合规样本→必绿（防恒红）
#   / skill 变异→必红 / 子脚本失败→必被传播 / 空扫描根→必诚实 SKIP（防假绿）。
# 退 != 0 意味着**门禁自身失效**（假绿或恒红），此时禁止声称本 skill 已验证。
```

主干尚未接入 AgentScope 时，`harness-contract-check` 报 `SKIP(no HarnessAgent usage)` 并退 0——这是诚实的空对象，不是通过。有对象却全绿才叫绿。

⚠️ 本仓 pom 门禁必须按「声明 pom 所属工作树」查：`io.agentscope` 只在 PoC worktree 里，okhttp 钉版在该树根 pom、`banDuplicateClasses` 在 `ruoyi-modules/ruoyi-chat/pom.xml`。按主树查会三项全报缺失（纯假红，已实测踩过）。

## 交付物清单

一次完整的 harness 工作至少产出：Agent Contract、Task Schema + 状态转换规则、Context Policy、Tool/Skill 清单与能力边界、Environment Contract、Permission Policy、Verifier、Event 模型、回归用例。**模型版本与这些行为配置必须共同版本化**，否则线上结果不可复现、不可回滚。

## 禁止清单

1. 禁止把"更长的 System Prompt"当 harness；确定性步骤必须沉淀为 Tool / 脚本 / 策略。
2. 禁止把 HTTP 连接或 WebSocket 当 Session，禁止把 Session 当 Task。
3. 禁止一次性把所有知识、工具、Skill 全量注入 Context——走渐进式披露三层（发现 / 选择 / 执行）。
4. 禁止用 `ASK` 当兜底；先用更窄 Tool、参数约束、Preview、资源范围、Sandbox 降风险。
5. 禁止把单次任务完成门禁（Verifier）与跨版本评估（Evaluation Harness）混为一谈。

## 来源

- AgentScope Java v2 文档索引：`https://java.agentscope.io/llms.txt`（zh 版把 `/en/` 换成 `/zh/`，加 `.md` 取原文）
- 博客《如何构建 Agent Harness》一~四：`/v2/zh/blogs/how-to-build-agent-harness/01-patterns`、`02-task-orchestration`、`03-context-and-state`、`04-controlled-execution`
- Harness 文档：`/v2/zh/docs/harness/{architecture,workspace,skill,memory,subagent,compaction,plan-mode,filesystem,sandbox,channel}`、`/v2/zh/docs/building-blocks/{permission-system,context,middleware,tool,message-and-event}`
- 本仓实证：分支 `poc/agentscope-kernel`（commit `8019d9a5`→`129327db`，G1~G5），代码在 `ruoyi-modules/ruoyi-chat/src/{main,test}/java/org/ruoyi/chat/poc/kernel/`
- 项目规范：`.claude/skills/_templates/IPD-SKILL-DISCO-TEMPLATE.md`（DisCo 形态 + 自证能红）
