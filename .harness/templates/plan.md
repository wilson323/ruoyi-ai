# plan.md — Build 阶段产物
<!--
  Anthropic AI Native SDLC Playbook · Build 阶段标准产物
  AI 必须先进计划模式产出本文件，人接受计划后才允许动代码。
  每个任务的证据字段是 Test 阶段的输入，也是 Maintain 阶段回灌规则的依据。
-->

## 元信息

```yaml
plan_id: PLAN-<YYYYMMDD>-<NNN>
spec_ref: SPEC-<YYYYMMDD>-<NNN>
created: <YYYY-MM-DD>
status: planning | approved | executing | blocked | complete
max_cost_usd: <无人值守运行必须填写>
```

## 1. 任务清单

每个任务 = 一个可独立验收的业务功能（**不是**按前端/后端/DB 分层切）。

### Task 1: <任务名>

| 项 | 内容 |
|---|---|
| **目标** | <这个任务做完后什么变好了> |
| **依据** | <对应 spec.md 的哪一节> |
| **依赖** | <必须先完成的其他 task> |
| **涉及文件** | <file list，≤5 个> |
| **验证方式** | <可执行的验证命令> |
| **验收标准** | <命令输出应该是什么> |
| **提交范围** | <本次 commit 应该包含哪些文件> |

**证据**（执行后回填，不可伪造）：

```
$ <验证命令>
<真实输出粘贴于此>
exit code: <0 / N>
```

**状态**: pending | in_progress | blocked | complete

---

### Task 2: <任务名>
<同上结构>

---

## 2. 执行顺序

```
Task 1 → Task 2 → Task 3
         ↘ Task 4（依赖 Task 2）
```

## 3. 升级记录（同错 3 次未过时填入）

| 时间 | Task | 错误 | 已尝试的修复 | 状态 |
|---|---|---|---|---|
| <ts> | <task> | <error> | 1. … 2. … 3. … | escalated |

## 4. 成本记录

| 项 | 值 |
|---|---|
| 累计 token | <n> |
| 累计成本 | $<n> |
| 剩余预算 | $<n> |

## 5. 决策日志（跨 session 恢复用）

| 时间 | 决策 | 理由 | 备选方案 |
|---|---|---|---|
| <ts> | <decision> | <why> | <alternatives considered> |

**规则**：换人接手或换 session 时，靠这张表还原当时怎么想的，不靠聊天记录。

## 6. 越界检查（收口前必填）

- [ ] 实际改动文件 ⊆ 计划声明的文件
- [ ] 没有"顺手重构"计划外的代码
- [ ] 没有新增计划外的依赖
- [ ] 没有修改计划说"不动"的系统

---

**下一步**：全部 task complete 且验收通过 → 进入 Test/Deploy，出具 `REVIEW.md`。