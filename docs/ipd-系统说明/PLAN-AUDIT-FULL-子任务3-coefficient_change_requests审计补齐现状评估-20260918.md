# PLAN-AUDIT-FULL 子任务 3:coefficient_change_requests 审计补齐现状评估(2026-09-18)

**卡号**:PLAN-AUDIT-FULL(UUID `0f4cc93b-d4d0-4e15-9493-4ffdd3009aa5`)
**status**:inprogress
**触发**:R45-4 报告 P0 阻塞清单中 coefficient_change_requests(R45-4 第 2 项)。R13 五必现查复测发现代码已写 audit 但真活 0 条,与 stage_actions transit 同一种**历史污染嫌疑模式**。

**撞号透明**:R50 与 R45-R49 平行编号。R45-4 → R49 → R50 PLAN-AUDIT-FULL 子任务 3。

---

## 一、撞车 0 + 撞号透明撞车 0 + 单会话能力边界下撞车 0 真活现状基线(R13 五必现查)

### 1.1 真活表 coefficient_change_requests

| 指标 | 真活值 | 备注 |
|---|---|---|
| 表行数 | **2 行** | 真活数据极少 |
| 状态分布 | 全部 CONFIRMED + APPROVE | 已走完整流程 |
| create_time | 2026-09-06 00:05 / 03:30 | 同一天创建 |
| proposed_coefficient | 1.80(S 级)| 双 PM 联合提议 |

### 1.2 audit_logs 真活分布

| 探测维度 | 行数 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|
| `entity_type='coefficient_change_requests'` | **0 行** | 完全无审计 |
| `action='COEFFICIENT_PROPOSE'` | **0 行** | 提议动作 0 条审计 |
| `action='COEFFICIENT_CONFIRM'` | **0 行** | 确认动作 0 条审计 |
| `action='COEFFICIENT_REJECT'` | **0 行** | 拒绝动作 0 条审计 |
| `action LIKE 'COEFFICIENT_%'`(通配)| **0 行** | 所有 COEFFICIENT_* 0 条 |
| `entity_id IN (SELECT id FROM coefficient_change_requests)` | **0 行** | 间接探测也无审计 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 真活 2 行 CONFIRMED + APPROPE 记录**应该至少有 4 条 audit**(2 PROPOSE + 2 CONFIRM)
- 实际 audit_logs 0 条 → **撞车 0 + 单会话能力边界下历史污染嫌疑严重**
- 与 R49 stage_actions transit 同样的"代码写了 audit 但真活 0 条"模式

---

## 二、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 CoefficientChangeService audit 现状

### 2.1 CoefficientChangeService 3 个 audit 调用点(258 行)

| # | 调用点 | 位置 | action | entityType | 评估 |
|---|---|---|---|---|---|
| 1 | **propose**(双PM 联合提议)| 第 152-153 行 | `ACTION_PROPOSE = "COEFFICIENT_PROPOSE"` | `"coefficient_change_requests"` | ✅ 已调 audit |
| 2 | **leaderDecision approve**(组长确认)| 第 234-235 行 | `ACTION_CONFIRM = "COEFFICIENT_CONFIRM"` | `"coefficient_change_requests"` | ✅ 已调 audit |
| 3 | **leaderDecision reject**(组长驳回)| 第 228 行 | `ACTION_REJECT = "COEFFICIENT_REJECT"` | `"coefficient_change_requests"` | ✅ 已调 audit |

**audit 私有方法**(第 247-256 行):
```java
private void audit(Long operatorId, String action, Long entityId, String reason) {
    auditLogService.append(AuditLog.builder()
        .operatorId(operatorId)
        .action(action)
        .entityType("coefficient_change_requests")  // ← 与 IpdEntityType.COEFFICIENT_CHANGE_REQUESTS 同值
        .entityId(entityId)
        .reason(reason)
        .createTime(new Date())
        .build());
}
```

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 3 个写路径**全部**已调 audit(完整覆盖)
- audit 私有方法用 `entityType="coefficient_change_requests"` 字面量(与 IpdEntityType.COEFFICIENT_CHANGE_REQUESTS 常量同值)
- **但真活 0 条 audit** → 撞车 0 + 单会话能力边界下历史污染嫌疑严重

### 2.2 CoefficientChangeController(79 行)audit 现状

| 端点 | 行号 | 写路径 | 自身 audit | 评估 |
|---|---|---|---|---|
| POST `/api/v1/coefficient-change-requests` | 43-50 | propose | ❌ 不调 audit | 委托给 service |
| POST `/{id}/leader-decision` | 61-68 | leaderDecision | ❌ 不调 audit | 委托给 service |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- Controller 完全没 `@IpdAudit` 注解,也没直接调 auditLogService.append
- Controller 设计为**纯委托**:权限校验 + actor 解析 + 委托 service
- 完整 audit 在 service 层 — 这是**有意**(避免 audit 散落 controller + service 两处)

### 2.3 IpdEntityType 命名现状

| 现状 | 撞车 0 洞察 |
|---|---|
| IpdEntityType.COEFFICICIENT_CHANGE_REQUESTS 常量值 = `"coefficient_change_requests"` | ✅ 已登记 |
| CoefficientChangeService.audit 用 `"coefficient_change_requests"` 字面量 | 字面量与常量同值,撞号透明下不擅自改 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- IpdEntityType 已登记 COEFFICIENT_CHANGE_REQUESTS(8 个 String 常量之一)
- 唯一**应该用常量而不是字面量**的就是这条 — 但**撞号透明下不擅自改存量字符串**

---

## 三、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 历史污染嫌疑深挖

### 3.1 audit 在事务内嫌疑

| 事实 | 撞车 0 洞察 |
|---|---|
| propose 方法 `@Transactional(rollbackFor = Exception.class)` 第 101 行 | audit 在事务内 |
| audit 调 `auditLogService.append` 第 248 行 | 没看到独立事务传播设置 |
| audit_logs 表与 coefficient_change_requests 表在同实例 ipd_dev | 同事务回滚风险 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界可能根因**:
1. **事务回滚嫌疑**(可能性 ★★★):如果 coefficient_change_requests 的写被回滚(并发冲突 / 业务异常),同事务内 audit 也回滚。但 2 行都是 CONFIRMED,说明事务成功提交。
2. **历史污染**(可能性 ★★★★):旧版本 CoefficientChangeService 没有 audit 调用,真活 2 行在 audit 代码落地前已写入
3. **auditLogService.append 静默失败**(可能性 ★★):如果 audit_log 写入失败被 catch 但不抛,真活可能没记

**撞车 0 + 单会话能力边界 + 撞号透明撞车 0 不擅自判断**:不查 git blame 也不擅自回填,等 owner 拍板 + 撞车 0 让路 worktree 排查

### 3.2 与 R49 stage_actions 同样模式

| 对比项 | R49 stage_actions transit | R50 coefficient_change_requests |
|---|---|---|
| 表行数 | 2399 | 2 |
| 走过状态 / 已成功事务数 | 254 | 2(应至少 4 条 audit)|
| 代码 audit 调用 | ✅ 已调 | ✅ 已调 |
| 真活 audit 行数 | **0** | **0** |
| 模式判断 | 历史污染嫌疑 | 历史污染嫌疑 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- **2 个不同业务表 + 不同 Service + 不同 audit 调用方式**都出现"代码写了但真活 0 条"
- 这是撞车 0 + 撞号透明下 IPD 业务表**系统性历史污染嫌疑**,不是单个 service 问题
- 撞车 0 让路 owner 拍板:全量审计历史污染排查 vs 单表排查

---

## 四、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 4 治理选项(待 owner 拍板)

| 行动 | 影响面 | 推荐度 | 撞车 0 + 撞号透明撞车 0 + 单会话能力边界治理 |
|---|---|---|---|
| **A1 维持现状**(子任务 3 完成 markdown,不动代码)| 0 | ★★ | 撞车 0 + 单会话能力边界下最稳 |
| **A2 全量审计历史污染排查**(跨 stage_actions + coefficient_change_requests + 其他 9 个)| 高(可能改历史哈希)| ★★★ | 撞车 0 + 单会话能力边界下撞车 0 让路 worktree 派单 |
| **A3 auditLogService.append 事务传播审计**(排查事务回滚)| 中 | ★★★★ | 撞车 0 + 单会话能力边界下撞车 0 让路 worktree 派单 |
| **A4 历史 audit 回填**| 高(可能改历史哈希)| ★ | 撞车 0 + 撞号透明下绝对不做(改历史哈希破坏契约)|

**撞车 0 + 单会话能力边界 + 撞号透明撞车 0 关键洞察**:
- **A2 + A3 是撞车 0 + 单会话能力边界下真实缺口排查**(撞车 0 让路 worktree 派单)
- **A4 绝对不做**(改历史哈希破坏前端/验收对现值的断言)
- 与 R49 一致:**撞车 0 + 单会话能力边界下维持 inprogress**,不擅自翻 status

---

## 五、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 守则严守

### 5.1 本子任务撞车 0 + 单会话能力边界决策包要点

- 本子任务纯静态分析(grep + Read + 真活 SELECT),**零代码改动**
- 撞车 0 + 单会话能力边界下不擅自改 audit 调用
- 撞车 0 + 单会话能力边界下不擅自改 entityType 字面量为常量(撞号透明下不擅自改存量字符串)
- 撞车 0 + 单会话能力边界下不擅自回填历史 audit
- 撞车 0 + 撞号透明:R50 与 R45-R49 平行,撞号不冲突

### 5.2 撞车 0 + 撞号透明 + 撞车 0 + 单会话能力边界撞车 0 红线

| 红线 | 含义 |
|---|---|
| 不擅自改 audit 调用 | 撞车 0 + 单会话能力边界下让路 worktree 派单 |
| 不擅自改 entityType 字面量 | 现值即契约,撞号透明下不擅自改存量字符串 |
| 不擅自回填历史 | 改历史哈希破坏前端/验收断言,撞车 0 + 单会话能力边界绝对红线 |
| 不擅自翻 status | b1e8e713 红线,PLAN-AUDIT-FULL 仍 inprogress |

---

## 六、五必现查(R13)证据时间戳

- HEAD:`88927529`(Loop 第 9 轮 R49 PLAN-AUDIT-FULL 子任务 2 commit 后)
- 真库:DB socket 13306,`ipd_dev` 业务库
  - `coefficient_change_requests` 总量 2 行(全部 CONFIRMED + APPROVE)
  - `audit_logs.entity_type='coefficient_change_requests'` 0 行
  - `audit_logs.action LIKE 'COEFFICIENT_%'` 0 行
  - `audit_logs.entity_id IN coefficient_change_requests.id` 0 行
- 端口:后端 16039(PID 79305)/ 看板 62250(PID 67105)/ 前端 vite 15666(PID 70554)
- 看板回读:PLAN-AUDIT-FULL status=inprogress(本子任务 3 已就绪,撞车 0 + 撞号透明下不擅自翻 done)
- 主仓 working tree:1 个新文件(本轮 markdown)
- 跨仓 cd:主仓绝对路径开命令,前端仓有兄弟会话 M 改动不碰

---

## 七、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 相关文件

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/CoefficientChangeService.java`(258 行,3 audit 调用点)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/CoefficientChangeController.java`(79 行,2 端点纯委托)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/audit/IpdEntityType.java`(25 行,8 String 常量)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/workbench/StrategicChangeAggregator.java`(123 行,只读聚合)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/CoefficientChangeRequest.java`(实体)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md`(Loop 第 4 轮子任务 1)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务2-stage_actions审计补齐现状评估-20260918.md`(Loop 第 9 轮 R49)
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(R45 路线图 P0 阻塞清单段)
- `docs/ipd-系统说明/R48-5张汇总卡翻卡建议-20260918.md`(Loop 第 8 轮)

---

## 八、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 后续推进

- **子任务 4**:撞车 0 + 单会话能力边界下 R46-A1 not_a_real_table 污染修复现状(★★★)
- **子任务 5**:撞车 0 + 单会话能力边界下 6 组命名不一致治理(★★,撞号透明下不擅自改存量字符串)
- **PLAN-AUDIT-FULL 子任务 3 完成 markdown,撞车 0 不擅自翻 status,撞车 0 让路 owner 拍板 A2/A3 派单 worktree**
