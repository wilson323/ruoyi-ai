---
topic: modules/ipd-workflow
title: ruoyi-ipd — IPD 业务工作流（六阶段 / Gate 评审 / 阶段动作）
updated: 2026-09-27
raw:
  - raw/ipd-source/gate-review-service.md
  - raw/ipd-source/stage-action-service.md
---

# ruoyi-ipd — IPD 业务工作流

`ruoyi-ipd` 是 IPD 产品经理管理系统的核心业务模块（**528 个 Java 文件**）。它的工作流与 `ruoyi-workflow`（Warm-Flow BPMN 审批）、`ruoyi-aiflow`（图驱动 AI 编排）都不同——**这是自研的领域状态机**，直接编码 IPD 方法论（六阶段 + 5 Gate + 69 标准动作），不接任何第三方流程引擎。

参见治理报告：`docs/ipd-系统说明/工作流系统性梳理-20260927.md`（三套工作流对比与边界裁决）。

## 三套"工作流"的边界

| 维度 | ruoyi-workflow | ruoyi-aiflow | ruoyi-ipd（本模块） |
|---|---|---|---|
| 引擎 | Warm-Flow BPMN | 自研图驱动 | 自研领域状态机 |
| 适用 | 通用审批（请假/报销） | AI 处理管道 | IPD 六阶段/Gate/动作 |
| 状态 | BPMN 标准状态机 | WfState/WfNodeState | 枚举 + StateMachineGuard |
| 是否配置化 | 是（BPMN XML） | 是（可视化画布） | **否（代码硬编码业务规则）** |

## 组成一：六阶段与阶段动作（StageAction）

六个阶段：`CONCEPT → PLAN → DEV → VALID → LAUNCH → LIFECYCLE`，共 **69 个标准动作**（ActionCatalog 定义，源：ZK-IPD 原型 D1 拍板）。

动作实例的状态迁移唯一入口是 `StageActionService.transit()`（**不叫 advance**，外部文档常见误称）：

- 5 个状态：`NOT_STARTED / IN_PROGRESS / DONE / NA / DELAYED`
- 幂等、NA 必填原因、乐观锁、每次迁移写审计（详见 [stage-action-service](../../raw/ipd-source/stage-action-service.md)）

相关 Controller：`StageActionController`；定时器：`StageActionOverdueScheduler`（扫描逾期动作）。

## 组成二：Gate 评审（5 个 Gate）

5 个 Gate（LR/LAD 等）由 `GateReviewService`（1028 行）承载**双签盲签**规则：

- 签发角色固定两方：`MARKET_PM` + `RD_PM`（缺一不可）
- 决策值：`APPROVE / REJECT`
- 11 个关键方法：`sign / view / reopen / scanTimeout / scanRemind / arbitrate / finalRuling / inviteObservers / recordOpinion / listObservers / extendDeadline`
- 详见 [gate-review-service](../../raw/ipd-source/gate-review-service.md)

Gate 体系 Controller 群：`GateReviewController / GateElementController / GateElementResultController / GateMaterialController / GatePrecheckController / GateSignScanController`；服务群：`GateService / GateCreationService / GateEngine / GateElementService / GatePrecheckService / GateMaterialChecker`。

## 组成三：状态机守卫

`StateMachineGuard`（接口）+ `DefaultStateMachineGuard`（实现）负责阶段级流转合法性校验；`RequirementStateMachine` 负责需求侧状态流转。

## 契约测试

后端契约测试在 `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/`，含 `AiCardBlindSignContractTest` 等 6 个 Gate 相关测试类。

## 已知风险点（2026-09-27 盘点）

- 业务规则硬编码在 Java（69 动作 / 状态白名单），改流程要动代码，无 BPMN/画布配置路径——这是与另两套工作流最大的差异，也是刻意选择（业务确定性优先）。
- wiki 此前完全未收录本模块工作流，本文是首篇（补 `raw/ipd-source/` 空目录）。
