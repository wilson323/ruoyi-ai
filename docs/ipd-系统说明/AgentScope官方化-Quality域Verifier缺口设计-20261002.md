> 版本：1.1.0。2026-10-02 修订：本专项归统一六计划P4。下文原设计保留为候选，不代表已批准的Schema/状态或当前源码。现有ProjectAgentCompletionGate必须先核能力，禁止再以本文“Verifier不存在”认定全部缺失；VERIFYING需核真实枚举，未存在就是拟新增合同。新增两表与DDL不是必选，先复用事件/产物版本/规则资源；实现与启动门按六计划总览P4。SDK middleware结束不是业务事务结束；终态返工必须新尝试，不能原地改结束运行。

# AgentScope 官方化 · Quality 域 Verifier 缺口设计（2026-10-02）

> 定位：统一六计划 P4（质量域，候选设计），设计文档（DRAFT → 待 owner 拍板后排期实施）。
> 历史P编号全部作废，实施只引用六计划总览v1.1.0对应节点。
> 依据：owner 2026-10-02 拍板「harness 按官方来、特性基于官方之上优化」+ agentscope-harness skill 工程契约（Verifier 节）+ 本仓 grep 实测。

## 一、缺口定位：8 类构建对象中唯一红色

按 harness 工程契约的 8 类构建对象（Model / Tool / Memory / Context / Permission / **Quality(Verifier)** / Evaluation / Observability）对官方化后的本项目做三态评估：

| 构建对象 | 状态 | 证据 |
|---|---|---|
| Model | ✅ 绿 | AgentScopeModelFactory 统一轨；ExecutionConfig 双挂载（P1/2026-10-02）+ 第三装配点 CodingServiceImpl（P3/2026-10-02） |
| Tool | ✅ 绿 | toolkit 白名单 + exposed.containsAll 校验 + toolsConfig deny + OwnershipMiddleware 四层防线 |
| Memory | ✅ 绿（**默认开、非有意关**） | 2026-10-02 字节码实证：`HarnessAgent$Builder.<init` 即 `MemoryConfig.defaults()`，不调 `.memory()` 也已启用；唯一否决条件是无 model 或 `disableMemoryHooks()`。**本行原写「有意关 / 双 disable」为失实陈述，已更正**——主源树 `disableMemory*` 零命中。「MEMORY.md 不当事实源」成立，但实现方式是 **`consolidationPrompt` 文本约束**（chat 与 IPD 各一套），不是 disable 开关。业务记忆在 ipd 库表；团队共享走 `ProjectKnowledgeRetriever`（按 projectId 检索），**不走记忆** |
| Context | ✅ 绿 | compaction 官方默认 + 显式固化；toolResultEviction defaults() |
| Permission | ✅ 绿（有意不上规则式） | core 权限流 + 四层防线；stopOnReject=false（ReActAgent$Builder:4939 默认） |
| **Quality(Verifier)** | ❌ **红** | **机器判定「运行完成」的 Verifier 不存在**（见 §二） |
| Evaluation | 🟡 黄 | 跨版本评估无 harness 级基线（技能 SHA-256 清单有，行为级无） |
| Observability | ✅ 绿 | AgentScopeAuditHook + audit_logs + STEP 事件链 |

Quality 域是唯一红色：其余七类要么已绿、要么「有意关闭且理由落档」（按总矩阵 §六判定标准，关闭≠缺口）。

## 二、现状盘点：已有的证据链 vs 缺失的机器判定

### 已有（不重复建设）

1. **文档审核链**：`ai_documents` 状态链（GENERATED→REVIEWED/REJECTED）+ `ipd:ai-document:review` 权限 + `review_comment` 落库。
2. **运行状态机**：agent_runs 状态（含 WAITING_APPROVAL）+ STEP 事件（kind=INTENT/AWAIT_USER）。
3. **审计**：AgentScopeAuditHook 落 audit_logs；token 用量按运行累加。
4. **人工门禁**：定档须绑定动作；大阶段完成须提交人+产线负责人双签。

### 缺失（本设计要补的）

**「这次运行产出的产物是否达到可交付标准」的机器可执行判定**——当前运行结束 = 模型停止输出，不存在一个返回「结构化缺口 + 可寻址证据路径」的校验步骤。表现：

- 文档产物质量全靠人工审核发现（审核人看到的第一眼才发现缺章节/空占位/格式错）；
- 「模型自述完成」与「产物可交付」之间无闸门，违反工程契约铁律 3（完成由证据决定）；
- 无法沉淀「同类动作的完成标准」，每次审核都是人肉比对。

## 三、设计原则（不可妥协的五条）

1. **Verifier 是 Harness 组件不是 Prompt**：判定逻辑沉淀为可测试的 Java 代码/规则表，禁止用「更长的 System Prompt 让模型自检」替代（skill 禁止清单 1）。
2. **返回缺口不返回布尔**：Verifier 输出结构化结果 `{verdict: PASS|GAPS, checks: [{id, status, evidencePath, gapSummary}]}`，缺口可寻址（指到产物的具体位置/字段）。
3. **缺证据停在 VERIFYING**：不直接 FAIL——VERIFYING 态人工可补证据后复检；只有明确反证才 REJECTED。
4. **任务级 Verifier 与跨版本 Evaluation 分离**（skill 禁止清单 5）：本设计只做任务级；Evaluation 另立（黄域，不在本刀）。
5. **基于官方扩展点，不自建第二编排**：Verifier 挂在 `MiddlewareBase` / `Hook`（AgentScope 官方扩展面），不引入新循环、不 fork agent 类。

## 四、分层设计

### 4.1 完成标准的事实源：动作级 Checklist 表

新增 `ipd_action_verify_rules`（动作级完成规则，与 `ipd_action_skill_map` 同构治理）：

| 列 | 说明 |
|---|---|
| action_code | 关联动作（同一 FK 体系） |
| check_id | 规则标识（如 `doc.section.charter.risk`） |
| check_type | `STRUCTURAL`（结构）/ `CONTENT`（内容）/ `REFERENTIAL`（引用） |
| rule_expr | 机器可执行规则（JSON：字段路径 + 断言类型 + 参数） |
| severity | BLOCK / WARN |
| status | DRAFT / ENABLED（owner 拍板启用，同技能晋升闸门） |

规则示例：市场需求文档动作 → `STRUCTURAL`：产物含「市场分析/竞品/风险」三章节；`CONTENT`：风险章节非占位文本（长度>阈值）；`REFERENTIAL`：引用的项目 ID 真实存在。

### 4.2 执行面：VerifyMiddleware（官方扩展点实现）

```
agent.call() 结束 → artifact 落库（GENERATED）后 → VerifyMiddleware.afterRun(runId, artifacts)
  → 按 action_code 载入 ENABLED 规则 → 逐条执行 → 写 ipd_run_verify_results
  → 全 PASS：运行保持终态，产物标注「机器校验通过」
  → 有 BLOCK 缺口：运行进入 VERIFYING，缺口列表挂 STEP 事件（复用 kind=INTENT 的 questions 载体，不新增事件枚举）
```

要点：
- **复用既有状态与事件**：VERIFYING 不新造运行状态列，落在 agent_runs 既有 status 值域扩展位；事件复用 STEP（AGENTS.md「不要新增事件枚举」红线）。
- **幂等**：复检按 (run_id, artifact_version, check_id) 唯一键 upsert，重跑不重复计数。
- **审核链不动**：Verifier 在人工审核**前**生效（第一道闸），`ai_documents` 审核语义、`ipd:ai-document:review` 权限、「待审核」可见名全部保持。

### 4.3 与 WAITING_APPROVAL 的关系

两者正交：WAITING_APPROVAL 是**过程**暂停（需澄清/需计划确认，用户输入后继续）；VERIFYING 是**结果**校验（产物已生成，等证据补齐或人工复核）。状态迁移：VERIFYING → (复检 PASS) 终态；VERIFYING → (人工审核 REJECTED) 沿既有退回链。

## 五、落地形态（三刀切片）

| 刀 | 内容 | 侵入面 | 验收 |
|---|---|---|---|
| V-1 | DDL + 规则表 + 种子（2~3 个高频动作的规则） | 新表 + tenant.excludes 登记 | drift-check 0；种子动作规则可回读 |
| V-2 | VerifyMiddleware + 结果表 + STEP 事件挂载 | agent 内核链一个新类（官方扩展点） | 单测：PASS/GAPS/复检幂等三态；既有运行回归无影响 |
| V-3 | 前端「本次运行」缺口列表展示 + 复检按钮 | 前端运行详情页 | 缺口项可寻址跳转到产物位置 |

依赖：V-1 无依赖可先做；V-2 依赖 V-1；V-3 依赖 V-2。与 P2 摘链四刀无文件交集（不同包、不同表）。

## 六、验收标准（判定「完成」的证据）

1. 规则表 DDL apply 真库 + drift-check EXIT=0；
2. 种子动作的一次真实运行：产物缺章节时运行停在 VERIFYING 且缺口列表含证据路径；补产物复检后 PASS——HTTP + 库表双证据；
3. 无规则的动作运行行为与现状完全一致（零回归）；
4. 规则启用走 owner 拍板（status 流转留痕），运行时不可动态增删规则。

## 七、待 owner 拍板

1. 规则表是否立项（建议：先 V-1 V-2，V-3 视审核效率数据再定）；
2. 首批种子动作选哪 2~3 个（候选：市场需求文档、Gate 评审材料、深管动作交付物——已有明确章节要求的最先受益）；
3. VERIFYING 是否对前端可见为独立状态名（建议：白话「校验中」，与「待审核」区分）。

## 八、边界与不做清单

- 不做模型自评（让 LLM 给自己的产物打分）——那会把判定拉回概率面；
- 不做跨版本 Evaluation（黄域另立）；
- 不改 `ai_documents` 审核链与权限语义；
- 不新增事件枚举、不新建第二套文档表；
- 规则表达式 DSL 不求全，STRUCTURAL/REFERENTIAL 起步，CONTENT 仅做阈值类断言（长度/占位检测），语义级判断留给人。

- marker agentscope-quality-verifier-gap-design-20261002
