---
source: file:///Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageActionService.java
collected: 2026-09-27
published: 2026-09-27
topic: ipd-source
---

# StageActionService.java（IPD 阶段动作状态流转）

源文件：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageActionService.java`
体量：约 587 行
业务定位：六阶段（CONCEPT/PLAN/DEV/VALID/LAUNCH/LIFECYCLE）共 69 个标准动作的实例状态管理（ZK-IPD 原型 D1 拍板：69 动作）

## 状态迁移唯一入口：transit（L112）

注意：入口方法叫 `transit`，不叫 `advance`（外部文档常见误称，见治理报告 §4 契约错位表）。

```java
@Transactional(rollbackFor = Exception.class)
public StageAction transit(Long id, String target, String reason, String operator)
```

迁移规则（源码注释 P1-4.3，L102-110）：

| 规则 | 说明 |
|---|---|
| 状态机白名单 | 深度（DEEP/轻管）+ 目标态双重校验 |
| 幂等 | 当前态 == 目标态直接返回，不写库、不写审计 |
| NA 必填原因 | 标记 NA 时 reason 为空抛 ServiceException（防绕过） |
| DONE 强校验 | 触发深度+数值双重校验 |
| 乐观锁 | `@Version`，version 冲突抛 ServiceException |
| 审计 | 每次成功迁移写审计 `action=TRANSIT` |
| 写权限 | `assertProjectWritable(projectId)` 前置校验 |

## 状态集合

- 轻管动作：`LIGHT_STATUSES`（不支持延期态，BR-IPD-05 三字段登记）
- 深管动作：`DEEP_ALLOWED` + `DEEP_EXTRA_STATUSES`（含 DELAYED 延期态）
- 枚举值：NOT_STARTED / IN_PROGRESS / DONE / NA / DELAYED

## 其他方法

| 方法 | 行号 | 职责 |
|---|---|---|
| `getById` | L87 | 按 ID 取动作实例，不存在抛异常 |
| `listByProject` | L95 | 按项目列动作，按 actionCode 排序 |

## 关联组件

- `ActionCatalog`：69 动作定义源（6 阶段分布）
- `StateMachineGuard`（接口 96 行，实现 `DefaultStateMachineGuard`）：阶段级流转守卫
- 契约测试：`ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/`

## 引用来源

- 原型：ZK-IPD `IPD系统_六阶段标准动作清单_v3.md`（69 动作，D1 拍板）
- 治理报告：`docs/ipd-系统说明/工作流系统性梳理-20260927.md` §4 契约错位（transit vs advance 命名漂移）
