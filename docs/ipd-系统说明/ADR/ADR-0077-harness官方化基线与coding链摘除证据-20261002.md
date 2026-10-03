# ADR-0077：harness 官方化基线（基于官方定制，禁双轨）+ coding harness 链摘除证据

- 状态：**`approved`（方向裁决）**——owner 2026-10-02 两次拍板（见 §1）；分项执行（P3 摘链 / P4 langgraph4j）仍各走 gate，不在本 ADR 内视为已批。
- 日期：2026-10-02 · 决策域：AgentScope Java harness 单轨化
- 关联：[ADR-0075](ADR-0075-agentscope内核替换三选一-20260928.md)（三选一基座，`partially-approved`）、`AgentScope官方化迁移矩阵-20261002.md`（总矩阵，本 ADR 增量输入）、`AgentScope接入ADR与Contract六问-20260928.md`（六问基线）
- 本 ADR 三件事：① 把 owner 裁决固化为可执行判定规则；② 补总矩阵遗漏的第三装配点（CodingServiceImpl）对照；③ 补 coding harness 链摘除（总矩阵 P3）的四条新证据与两个遗漏消费者。

## 1. 最新目标升级与历史裁决

2026-10-02 最新用户原文：“官方能力全量启用、禁止禁用、禁止降级，以 /Users/mac/Documents/agentscope-java 源码为参考确保完整应用”；“能力全开 + 业务语义经官方扩展点挂载”。本次指令优先于本 ADR 原“按需关闭”规则。以下两次拍板保留为单轨原则的历史依据，不能恢复旧关闭口径。

当前状态：目标已确认，实施与运行验收 `PENDING_VALIDATION`；本文修订不证明所有能力已加载。

1. 上午（单轨收口轮，log.md 已登记）：「harness 42k 行自研 → **逐项归位官方**；langgraph4j → 迁到 AgentScope harness 编排」。
2. 本轮（harness 专项）：「harness 也要按照官方的来，如果本项目特性需要**基于官方的上边优化**，记住是**基于官方的上边定制而不是双轨**」。

两次合并 = 本 ADR 方向：**官方 `HarnessAgent` 是唯一 agent 装配面与执行面；本项目特性只允许经官方扩展点挂载定制；自研平行执行面（双轨）必须收敛。**

## 2. 判定规则（四条，验收级）

1. **官方基线**：`HarnessAgent.builder()` 是唯一装配入口；官方 ReAct 循环、Model 调用、工具执行协议（`ToolBase`/`checkPermissions`/`callAsync`）、`AgentStateStore`、`RuntimeContext` 是唯一执行与状态面。
2. **定制走扩展点白名单**：本项目治理与业务语义只允许经下列官方扩展点挂载——`.middleware(...)`（MiddlewareBase）、`ToolBase#checkPermissions`（裁决映射回官方 `PermissionDecision`）、自定义/包装 `AgentStateStore`、`ToolsConfig`、`.hook(...)`、`.workspace(path)` 布局、官方能力配置与权限扩展点（不得以 disable 开关关闭整类能力）。
3. **官方能力全量启用，禁止禁用、禁止降级**：owner 本次最新指令取代旧“按需关闭”口径。以 `/Users/mac/Documents/agentscope-java` 真实源码为参考逐项落实完整应用；官方能力通过官方装配面与扩展点接入，不以整类 disable、空实现或静默回退代替交付。业务闸门保留在官方扩展点上：技能 owner 拍板、审批流、权限、文档审核、动作批准和 Gate 不删除、不绕过。技能发现、加载与草案生成不构成发布或晋升批准；记忆内容不自动成为业务权威；子智能体与团队不得取得超出原主体的授权。参考仓与实际依赖版本差异须核实，不混用 API，不把目标声明写成已启用证据。
4. **双轨红线**：自研实现与官方重叠职责（ReAct 循环 / Model 调用 / 工具执行 / 状态持久化）不得并行服务；每处收敛必须「替换 / 包装 / 保留」三选一记 ADR（ADR-0075 基座 + 本 ADR 增量），无 ADR 的静默保留视为违规。

**防"为开而开"与防"为留而留"双向标准**（继承总矩阵 §六）：充分应用 = ①官方组件为默认底座 ②特性一律走扩展点 ③不重复实现官方底层 ④业务语义经官方扩展点挂载，能力完整启用，具体未授权操作按业务闸门拒绝或进入审批。反向亦成立：**保留自研的理由只能是"官方无等价或语义不符"，不能是"自研已在所以官方不上"**（该方向已被 owner 否决，见 §6）。

## 3. H1 增量：第三装配点 CodingServiceImpl 对照（总矩阵遗漏）

总矩阵 §二只对照了 ProjectAgentKernel / ChatKernel 两装配点。**`ruoyi-modules/ruoyi-chat/.../service/coding/impl/CodingServiceImpl.java` 是第三个 `HarnessAgent.builder()` 装配点**（grep 实测，2026-10-02），对照如下：

| 官方能力 | CodingServiceImpl 现状 | 定级 |
|---|---|---|
| workspace | `.workspace(root)`（会话工作目录） | 已用官方 |
| stateStore | `new InMemoryAgentStateStore()`（try-with-resources 单次运行生命周期） | 已用官方 |
| toolkit | 官方 `Toolkit` + 官方原生文件工具（ReadFileTool/EditFileTool/ExecuteCommandTool 等，注入 channel） | 已用官方 |
| disable 开关 | **已移除**（2026-10-02 现查：`grep -rn "disableSubagents\|disableDynamicSubagents\|disableDynamicSkills" --include="*.java" ruoyi-modules/*/src/main/java` → EXIT=1 零命中；三装配点 `ChatOfficialCapabilities#configure` / `CodingServiceImpl#chat` / `AgentScopeProjectAgentKernel#buildManagedAgent` 同步移除） | 已按 §2 规则 3 全量启用。业务闸门改由官方扩展点承载（`.middleware(...)` / `ToolBase#checkPermissions` / `SkillPromotionGate` / `SkillVisibilityFilter` / `.permissionContext(...)`），是换承载面而非删闸门 |
| **modelExecutionConfig / toolExecutionConfig** | **已挂载**：三装配点均取官方 `ExecutionConfig.MODEL_DEFAULTS` / `TOOL_DEFAULTS`（`CodingServiceImpl#chat`、`AgentScopeChatKernel`、`AgentScopeProjectAgentKernel#buildManagedAgent`） | 已用官方。**勘误（2026-10-02 实读源码）**：本行原判"未挂载 / 真缺口 / 30 分钟长任务裸奔"是文档滞后于代码。"不设时 SDK 不套任何超时/重试"的机理仍成立（ReActAgent null 跳过 + OpenAIClient HTTP 层无重试），但本装配点不属该情形 |
| **compaction 显式固化** | **已显式声明**：三装配点均 `CompactionConfig.builder().build()` | 已用官方。**勘误（同上）**：本行原判"未显式声明"已失效，三装配点"显式固化防官方默认漂移"的意图声明现已一致 |
| ToolsConfig deny | 未挂 | 可选防御纵深：本装配点是手工白名单注册（暴露面=注册面），deny 属冗余防线；与两内核统一则挂 |

处置：归入总矩阵执行序列的下一分片（对齐挂载，零业务侵入；随 P1 先例走同一验收链：编译+单测+verify）。

## 4. H2 增量：coding harness 链摘除证据链（总矩阵 P3 的裁决输入）

总矩阵 P3「CodingHarnessController 入口去留——待 owner 拍板」。本轮四条新证据（全部 2026-10-02 现查）：

| # | 证据 | 命令/来源 | 含义 |
|---|---|---|---|
| 1 | **IPD 正式前端零调用** | `grep -rln "coding/harness" ruoyi-ipd-web/apps/web-antd/src` → files=0 | `/coding/harness/**` 全部 20+ 端点无任何前端消费者 |
| 2 | **不在 API 契约** | `grep "coding/harness" docs/ipd-系统说明/api-internal-whitelist.json scripts/baselines/*.json` → 0 命中 | 不属于内部白名单也不属于前端 baseline 契约面 |
| 3 | **本仓自建、非上游** | `git log --diff-filter=A` → `25ab33c7 2026-08-15 feat: add coding harness runtime`（本仓二开期自建） | 无"上游 RuoYi-AI 保留义务" |
| 4 | **旧口已单向指向它** | `CodingController` 返回 `"Legacy coding endpoints are disabled; use /coding/harness"` | 该域是自我闭环的孤岛（旧 coding 口禁用 → 新口无前端） |

**综合定级建议（供 owner 拍板 P3）**：倾向**废弃摘链删包**（自研 17 子包 254 文件随链归零），而非"整链迁 AgentScope 编排"——迁移的前提是有消费者，而消费者为零；保留唯一前提是存在未查明的非前端调用方（如脚本/第三方），摘除前按 OPS-09 惯例做一次全仓引用终审。

### 4.1 总矩阵 §四遗漏的两个域外消费者

总矩阵 §四列的消费者为「① CodingHarnessController ② chat.kernel.tool.*（治理层，AgentScope 轨复用）③ 测试」。遗漏：

| 消费者 | 引用内容（grep 实测） | 定级 |
|---|---|---|
| `ruoyi-chat/.../shortdrama/impl/ShortDramaServiceImpl.java` | `harness.loop.model.*`（NativeModelRequest / ModelTurnListener / ModelTurnHandle / StreamingModelTurnAdapter；catch ModelTurnException） | **自研模型回转层的活跃业务消费者**（3400+ 行服务）。摘链前必须先迁：loop.model 的职责（模型请求/流式回转）官方 `Model` + `streamEvents` 已承接（两内核实证）。迁移是 ShortDrama 专项分片，不并入摘链刀 |
| `ruoyi-chat/.../chat/impl/provider/CustomApiServiceImpl.java` | `harness.modelruntime.HarnessModelPolicy`（单 import，注释协调 model-timeout 默认值） | 轻依赖：随 modelruntime 归位（总矩阵已列 modelruntime(6) 归并）一并处理 |

**摘链依赖序**：ShortDrama 迁官方 Model → CustomApi 摘 HarnessModelPolicy → CodingHarnessController 终审引用 → 摘链删 17 包（保留清单见 §5）。

### 4.2 摘链时的跨域保留清单（官方轨消费者）

以下组件被 AgentScope 轨（AgentScopeChatKernel / AgentScopeProjectAgentKernel / chat.kernel.tool 治理层）引用，**删 17 包前必须迁出**（建议迁至 `org.ruoyi.chat.kernel.*`，与 KernelToolGovernance 同域）：

- `harness.model.HarnessPermissionMode` / `harness.model.HarnessToolEffect`
- `harness.tool.PolicyDecision` / `ToolCapability` / `ToolDescriptor` / `ToolInvocation` / `ToolPolicyEngine` / `ToolPolicyEvaluation`

证据：`AgentScopeProjectAgentKernel` import 面与 `KernelToolGovernance` import 面（本轮全文精读）；它们本身已是"基于官方扩展点的定制"（`KernelGovernedTool extends ToolBase`，裁决挂官方 `checkPermissions`，映射回官方 `PermissionDecision`），符合判定规则 2，**保留合规**。

## 5. 与既有文档的结论更新关系

| 既有结论 | 更新 |
|---|---|
| ADR-0075 §二「替换 1 / 包装 2 / 保留 14」 | 前提已变：当时（09-28）coding harness 视为活跃产品入口做渐进替换；本轮证据（§4）表明消费者为零，终局从"渐进替换内核、保留门面"升级为"**摘链删包**"（待 P3 owner 终审）。治理资产（recovery/plan/approval/journal）在官方轨的对应物已由 `chat.kernel.tool` 治理层与 IPD 业务流继承 |
| 六问 ADR 问 2「禁止在 AgentScope 侧再造一套白名单语义」 | 已按此落地（KernelGovernedTool 裁决唯一源=ToolPolicyEngine，映射官方 PermissionDecision），维持 |
| 六问 ADR 问 6「CompactionConfig 📄 未实证，W5+ 再评估」 | 已过时：AP3 落地 + 勘误（官方默认即开、主模型回落），两内核已显式固化 |
| 总矩阵 §四消费者清单 | 增补 §4.1 两项 |
| 总矩阵 P3「待 owner 拍板」 | 输入齐备（§4 四证据 + 依赖序），待终审 |

## 6. 被否决的替代方向（登记防回潮）

**"自研保留 + 官方不上"**（曾以避免双轨为由判 permissionContext 规则式、官方 compaction 等不上）——方向反了：双轨的根源是自研平行实现，解法是归位官方，不是官方不用。owner 2026-10-02 裁决已明确。判别口诀：砍掉的应该是**与官方重叠的自研底层**，不是**与自研重叠的官方能力**。

## 7. 验收与门禁衔接

- 判定规则四条作为后续所有 AgentScope 相关刀的验收判据（与总矩阵 §六互引）。
- 摘链分片验收：`grep -rl "org.ruoyi.service.coding.harness" --include="*.java" ruoyi-modules/**/src/main` 归零（保留清单迁出后）+ `mvn -o -pl ruoyi-modules/ruoyi-chat test` 全绿 + `bash .claude/skills/agentscope-harness/scripts/verify.sh`（含 `--self-red` EXIT=0）+ langchain4j 棘轮维持 0。
- 本 ADR 为 docs-only，无代码变更；marker 见 log.md。

- marker adr-0077-harness-official-baseline-20261002
