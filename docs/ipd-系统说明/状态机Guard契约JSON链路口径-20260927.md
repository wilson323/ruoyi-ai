# 状态机 Guard 契约 JSON 链路口径（§5-1，2026-09-27）

> 出处：《工作流系统性梳理-补遗-20260927.md》§5 优先序第 1 项。目标：前后端状态迁移图单一事实源，
> 消灭 C7/C9/C10 一类"前端手写词表/迁移图与后端守卫表漂移"。**零新依赖**（Jackson/Vite JSON import/
> vitest 均为仓内既有能力）。

## 1. 链路

```
后端 DefaultStateMachineGuard.initRules()（42 条规则，内存唯一事实源）
  → StateMachineGuardRulesExportTest 导出契约 JSON（双写）
      ├─ 本仓 docs/ipd-系统说明/state-machine-guard-rules.json（留档对账）
      └─ 前端仓 apps/web-antd/src/views/ipd/_shared/state-machine-guard-rules.json（消费源）
  → 前端 guard-rules.ts 读 JSON 派生 transitions → ipd-state-machines.ts 消费
```

## 2. 契约文件格式

- `meta`：source / version（rules 数组 JSON 的 sha256）/ ruleCount。**不含时间戳**（保证可重复生成、diff 干净）。
- `rules[]`：仅 `entityType / fromState / toState / trigger / crossDomain` 五字段，按
  entityType|from|to|trigger 排序。`key` 可由前四字段推导（不重复导出）；`description` 为人读注释、
  非机器契约（不导出，避免改注释引起契约噪声 diff）。

## 3. 命令（与 baseline 棘轮同构：默认会红，显式 flag 才重写）

```bash
# 校验（改规则不重导出 → 必红）
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=StateMachineGuardRulesExportTest test
# 重导出（改规则后跑一次，git diff 呈现契约变更供 review；前端目录可用 IPD_FE_SHARED_DIR 覆盖）
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=StateMachineGuardRulesExportTest -Dguard.rules.export=true test
```

## 4. 前端消费范围（本期边界，避免大面积改页面）

| 前端机器 | 对应后端 entityType | 处置 |
|---|---|---|
| BONUS_STATUS | bonus_pool | **transitions 改为 JSON 派生**（顺带修复真实漂移：后端已登记 DRAFT→DISTRIBUTED 直分，前端机器原缺失） |
| CHANGE_STATUS | requirement_change | **transitions 改为 JSON 派生**（词表一致，无行为变化） |
| DELETION_STATUS | deletion_request | KNOWN_DRIFT：前端词表 PENDING/LEADER_APPROVED/PURGED 与后端 DRAFT/LEADER_REVIEW/DELETED 完全不同（=补遗 C7 死定义），词表收敛另案，契约测试显式钉住差异现状 |
| GATE_STATUS | gate_review | KNOWN_DRIFT：同上（C7，页面实际用 ipd-enums 正确词表） |
| 其余 5 台（PROJECT/DEMAND/BID/BID_RESPONSE/ACTION） | 无守卫规则 | 保持手写；接入前提=补遗 §5-2 接线批次 |

派生规则：`fromState="INITIAL"` 的创建迁移过滤掉（前端机器状态集无 INITIAL）；`fromState="*"` 通配仅
按后端 `isTerminalState` 语义展开到 DELETED/REJECTED/WITHDRAWN/CONFIRMED/DISTRIBUTED/APPROVED/REJECTED/ARCHIVED 终态集合。

## 5. 与既有机制的边界（防双轨声明）

- `scripts/check-api-contract-fe-be.mjs`（pre-commit 门禁 3）：管 **API 端点**孤儿/消费对账，不管迁移图——本链路是其互补，不重叠。
- `StateMachineGuardContractTest`：管**后端守卫表内部**（42 条哨兵 + 表驱动放行/拒绝）——本链路测试管守卫表**导出面**与跨仓文件，两者联动：改规则必须同时过两测试。
- `ipd-frontend-drift-guard.cjs`：管前端 api/ipd 导出冲突，与本文件无关。
- 不新增 CI job、不新增 hook；校验命令挂现有测试通道（vitest / mvn test）。

## 6. 变更纪律

1. 改 `DefaultStateMachineGuard.initRules()` 增删迁移 → 必须跑 export 重生成 + 前后端契约测试全绿 + git diff 中契约 JSON 变更随 PR review。
2. 前端新增机器 → 若后端守卫已有对应 entityType，transitions 必须从 JSON 派生，禁止再手写迁移图。
3. KNOWN_DRIFT 两台机器的词表收敛（C7）属独立事项：收敛后将其从 KNOWN_DRIFT 升为 JSON 派生，勿混入其他批次。
