# Agent Contract 模板

> 适用场景：新建或改造任一 `HarnessAgent` 前的**第一份产物**。契约未填满 → 状态只能是 `DRAFT_ONLY`，不得进入实现。
> 引用：`references/harness-patterns.md` §A（8 类构建对象）、`references/engineering-contracts.md` 契约一 §1.2/§1.10、契约三 §3.5。
> 复制本文件到 `docs/ipd-系统说明/工程合同/agent-contract-<agent-name>-<YYYYMMDD>.md` 后逐节填。

```markdown
# Agent Contract — <agent-name>@<version>

- Owner（责任人 handle）：
- 风险等级：low | medium | high     ← 决定 §5 权限默认策略与是否每步授权
- 运行形态：本地工作区 | 嵌入式应用 | 长任务服务 | 平台化交付
- 路径控制：Workflow | Agent 主导 | Hybrid
- 关联看板卡 / Issue：
- Agent 定义版本 + Workspace 资产版本（发布单元，必须同时回滚）：

## 1. 目标与边界

- 为谁工作（租户 / 角色 / 上游系统）：
- 目标（一句话，可验收）：
- 明确**不做**什么（越界即 DENY）：
- 目标终点语义：<!-- 例：只到"形成可审批变更"，还是包含"已发布生产"。
     写错这里 → Verifier 会把"尚未发布"误判为未完成，或把"未发布"当"已完成" -->

## 2. 输入 / 输出 / 交付物

| 项 | 结构 | 存放位置 | 生命周期 |
|---|---|---|---|
| 输入 | | `workspace/inputs/` | 保持来源 |
| 中间产物 | | `workspace/scratch/` | 任务结束可清理 |
| 交付 Artifact | | `workspace/artifacts/` | Draft→Validating→Ready→Published/Rejected→Archived |
| 完成证据 | | `workspace/evidence/` | 与 Artifact 同保留期 |

## 3. Task Schema 与状态机

- `taskId` 生成规则（**不得等于 sessionId / 消息线程 ID**）：
- 幂等键规则：`<taskId>:<capability_id>`
- 状态集合与允许的转换：<!-- 至少覆盖 CREATED/RUNNING/WAITING_INPUT/WAITING_APPROVAL/
     WAITING_EVENT/PAUSED/VERIFYING/COMPLETED/FAILED/CANCELLED -->
- 状态存储后端：内存 | JSON 文件 | MySQL | Redis（`stateStore`）
- 隔离键：`KernelScopeKey.of(projectId, userId, agentId, sessionId)` → slotId `p{}:u{}:a{}:s{}`
- Event Log / Snapshot / Checkpoint 三表示各落在哪：
- 租约与并发写入策略（同一 taskId 谁持锁）：
- 恢复安全点定义 + 恢复前必须核对的外部状态 / 幂等键 / 已产 Artifact：

## 4. 预算（外部终止边界，耗尽必须产明确终态 + 未完成清单）

| 维度 | 上限 | 接近阈值时的动作 |
|---|---|---|
| 步数 | | 收敛范围 / 停止新委派 |
| 总时长 | | |
| Token | | |
| 费用 | | |
| 工具调用次数 | | |
| 子任务并发 | | |
| 高风险动作次数 | | 优先完成可交付部分 / 请用户选择 |

## 5. 能力与权限

| capability_id@version | 类型（Tool/MCP/Skill/Subagent/Remote Agent） | 副作用 | 可逆 | 风险 | 默认策略 | 幂等键 | 超时 |
|---|---|---|---|---|---|---|---|
| | | 无/有 | 是/否 | low/med/high | ALLOW/ASK/DENY | | |

- 身份三记录：发起任务的用户/服务身份 · 发起行动的 Agent 版本 · 实际执行的 Runtime 身份
- 凭据形态：短时凭证（禁长期 Secret）；来源文件（gitignored）：
- 必须 **DENY** 的动作清单：<!-- 跨租户数据访问 / 绕过安全控制 / 请求长期 Secret 一律 DENY -->
- 必须 **ASK** 的动作清单 + 审批人 + 批准范围（仅本次 / 当前 Task / 一类受限动作）：
- 不可逆动作走 Preview → Approve → Commit → Verify，Preview 中必须写明"无法回滚"：
- Environment Contract：文件 / 命令 / 网络 / Secret / 资源边界；filesystem 模式（本机 / remote / sandbox）：
- 代码修改只允许发生在哪个隔离工作区路径：

## 6. 计划与阶段门禁

| 阶段 | 主要产物 | 进入下一阶段的门禁（输出是什么 / 证据在哪 / 谁确认） |
|---|---|---|
| | | |

- 计划模式：轻量 Todo | Plan Mode（`plans/PLAN.md` + HITL 确认）| Planner–Executor
- 重规划纪律：每次重规划记录触发事实 + 保留已完成项，**禁止改写目标掩盖失败**

## 7. Subagent 委派契约（每个子任务一份，可机读）

| 契约项 | 内容 |
|---|---|
| 目标与边界 | 交付什么；哪些目录 / 系统 / 动作在范围内 |
| 已知上下文 | 哪些事实已确认；哪些决定不可自行改变 |
| 能力与权限 | 可用模型 / Skill / Tool / 环境 / 权限 |
| 预算 | 最大时间 / 步骤 / Token / 费用 / 并发 |
| 输出与证据 | 结果结构；证据与来源如何附带 |
| 失败语义 | 何时重试 / 返回部分结果 / 升级 / 终止 |
| 验收条件 | 父 Agent 用什么条件判断结果可采用 |

- 关系类型：Delegation（父保留责任）| Handoff（控制权转移，需责任变更记录）
- 委派收益说明：上下文隔离 / 能力隔离 / 并行，至少命中一项且收益 > 通信+合并成本

## 8. Verifier（完成门禁）与 Outcome

| 验收项 | 验证层级（结构/环境/确定性/独立模型/人工） | 证据路径 | 强制 |
|---|---|---|---|
| | | `evidence/...` | 是/否 |

- Verifier 返回：结构化缺口 + 失败证据 + 可修复性；验证失败 = 新的 Observation（继续修复/重规划/转交/失败）
- Outcome 枚举：<!-- 例 change_ready_for_approval / deployed / rejected -->
- 缺证据时最高只能到 `VERIFYING`，**禁止直接 `COMPLETED`**

## 9. 事件与交互

- 事件类型清单（进度 / 工具 / 审批 / 结果 / 错误）+ 每类是否对用户可见
- 事件存储与重放：`eventStore.append(taskId, event)`；恢复游标定义
- Channel 映射（API / 工作台 / IM）；限流与按身份展示规则
- 敏感字段脱敏位置：进入 Context 前 / 进入交互事件前（**分别**处理）

## 10. 观测与反退化

- Trace 必含字段：model call · context policy 版本 · tool/action 决策 · permission 决策 · subagent 状态 · 成本 · 时延
- Context Manifest 是否落库（`source_type/scope/version/trust_level/permission_basis/selected_reason/token_count/transform/content_hash`）
- 失败回流路径：Trace → Failure Cluster → Diagnosis（Model/Context/State/Tool/Policy/Environment/Loop）→ Harness Patch → Regression → Release Gate
- 回归集构成：原失败用例 + 相邻正常用例 + 安全对抗用例
- 评估指标：任务成功率 · 完成质量 · 人工接管率 · 工具错误 · 权限事件 · 延迟 · 成本 · 业务价值

## 11. 失败语义与恢复策略

| 失败类型 | 策略 |
|---|---|
| 瞬时模型 / 网络错误 | 有界退避重试 |
| 参数错误 | 修正参数 |
| 环境缺失 | 重建环境 |
| 权限拒绝 | 等待或终止（不重试） |
| 重复探索 | 重新规划 |
| 业务条件不满足 | 明确缺口 |

- 无进展检测信号：连续相同工具+相似参数 / 反复同一错误 / Plan 长期不变 / 工作区无新增事实 / 少数行动间循环
- 有副作用动作重试前必答："上一次究竟有没有发生"（查幂等键 + 真实系统状态；无法证明未执行 → 不重复提交）
- 降级路径（换模型 / 换工具 / 换环境）必须被记录

## 12. 自检（提交前逐条打勾）

- [ ] 12 节全部填实，无 `TBD` / 空表格
- [ ] Task 与 Session 分别定义，taskId ≠ sessionId
- [ ] 隔离键经 `KernelScopeKey.of(...)`，四维齐全，fail-closed 说明在场
- [ ] 预算 7 维均有上限与阈值动作
- [ ] 每个 capability 有副作用/可逆/风险/策略/幂等键/超时六项
- [ ] DENY 清单非空（跨租户 / 绕安全控制 / 长期 Secret 至少三条）
- [ ] 每条强制验收项有可寻址证据路径
- [ ] Agent 定义版本与 Workspace 资产版本绑定，回滚同时回滚
- [ ] `bash .claude/skills/agentscope-harness/scripts/verify.sh` EXIT=0
```

## 填法示例（漏洞修复 Agent 的 §1 与 §8 节选）

```markdown
## 1. 目标与边界
- 为谁工作：payment-service 服务负责人（租户 acme）
- 目标：修复 CVE-XXXX 并形成**可审批变更**
- 明确不做：不合并 PR、不发布生产、不改 payment-service 以外目录
- 目标终点语义：`change_ready_for_approval`（**不含**已发布）。
  因此"尚未发布"不算未完成；若后续扩到含发布，必须新增审批与真实部署状态核验。

## 8. Verifier 与 Outcome
| 验收项 | 层级 | 证据路径 | 强制 |
|---|---|---|---|
| 受影响版本已替换且无传递依赖重新引入 | 确定性 | `artifacts/dependency-tree.json` | 是 |
| 代码差异仅落授权目录且与已批准计划一致 | 环境 | `artifacts/remediation.patch` | 是 |
| 单测 / 集成测试 / 安全扫描均有可寻址报告且强制项全通过 | 确定性 | `artifacts/unit-test.xml` 等 | 是 |
| 独立评审无未关闭阻断问题 | 独立模型 + 人工 | `artifacts/review.json` | 是 |
| 变更单含影响范围 / 回滚方案 / 证据引用 | 结构 | `CR-18427` | 是 |
```
