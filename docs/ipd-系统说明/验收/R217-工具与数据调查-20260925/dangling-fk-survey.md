# R215-DATA / DATA-CLEAN-9140004 调查记录：悬空外键僵尸行

- **关联卡**:
  - 8a088a71-31b3-44ac-ae3e-024f4b2098de (R215-DATA deletion_requests 悬空外键)
  - 5d5c4fcc-59c7-444e-b3b3-accad5e21f94 (DATA-CLEAN-9140004 bonus_pools 悬空外键)
- **调查日期**: 2026-09-25
- **调查人**: IPD 工具/数据治理专员
- **操作模式**: 只读（SELECT/SHOW 限定，零写库）
- **数据库**: ipd_dev (MySQL 8.4.9, socket 连接)
- **R217 独立复核合流（2026-09-25 本会话 data lane）**: 本文为主文档，两调查卡结论逐条对账一致；增量证据（binlog 坐实物理 DELETE 时间 2026-09-18 21:37:58、幽灵项目 9140004 遗产组 ≥11 行跨 6 表悬空、ti5d 30001 实测原文、DELETE_NOOP 超管可通关代码读证、R46.1 物理清理先例 binlog 对账、A′/C 折中推荐）见同目录 `独立复核增量-zombie-rows.md`、`独立复核增量-bonus-pool-9140004.md`，不在此重复。**§2.3「推测 DBA 手工或 purge 任务」已被 binlog 证据收窄**为「09-18 21:37:58 手工 DELETE 单行，最可疑主体为同时间窗兄弟交互会话，身份无法 100% 定论」；§五翻 done 判断经独立复核维持。

---

## 一、deletion_requests 悬空外键分析

> **⚠️ 执行状态指引（20260925 清理轮追加）**：本节 5 条僵尸行的处置结论已执行完毕（软删，ipd_dev alive 5→0，见 docs/ipd-系统说明/log.md L12335 第⑥条）；单轨口径以 R215《cleanup-and-triage-20260925.md》任务2a 为准（owner 只拍板一次）；§1.6 幻影 DELETED 为另型异常、仍待 owner 拍板（SQL 草稿已移至同目录 `dangling-fk-cleanup-draft.sql`），不在本行覆盖范围。本节以下仅作调查记录保留。

### 1.1 表结构关键字段

| 字段 | 类型 | 说明 |
|------|------|------|
| entity_type | varchar(32) | 多态关联类型（projects/products/cert_templates/persons/gates） |
| entity_id | bigint | 目标实体主键 |
| status | varchar(24) | DRAFT→LEADER_REVIEW→ADMIN_REVIEW→DELETED/REJECTED/WITHDRAWN |
| del_flag | char(1) | 本表软删标记（当前全部='0'） |

**无物理外键约束**（information_schema.KEY_COLUMN_USAGE 查询结果为空）。

### 1.2 entity_type → 实体表映射（源码证据）

| entity_type | 执行器类 | 目标表 |
|-------------|---------|--------|
| projects | ProjectSoftDeleteExecutor | projects |
| products | ProductSoftDeleteExecutor | products |
| cert_templates | CertTemplateSoftDeleteExecutor | cert_templates |
| persons | PersonSoftDeleteExecutor | persons |
| gates | GateSoftDeleteExecutor | gates |

源码路径：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/executor/`

### 1.3 全量统计（32 行）

| entity_type | 总行数 | 物理不存在(僵尸) | 实体已软删(正常) | 实体仍存活(正常) |
|-------------|--------|-----------------|-----------------|-----------------|
| cert_templates | 23 | **0** | 22 | 1 |
| products | 8 | **4** | 2 | 2 |
| projects | 1 | **1** | 0 | 0 |
| **合计** | **32** | **5** | **24** | **3** |

### 1.4 僵尸行明细（5 行）

| # | dr.id | entity_type | entity_id | status | create_time | requester_id | reason |
|---|-------|-------------|-----------|--------|-------------|--------------|--------|
| 1 | 2096381708617248770 | products | 2 | LEADER_REVIEW | 2026-09-05 06:35:22 | 900101 | P0-6.3 25h |
| 2 | 2096373346072539138 | products | 999 | REJECTED | 2026-09-06 07:02:09 | 900101 | P0-6.1 集成验收 |
| 3 | 2096381707870662657 | products | 1 | WITHDRAWN | 2026-09-06 07:35:23 | 900101 | P0-6.3 round9 HTTP |
| 4 | 2096381708998930433 | products | 3 | ADMIN_REVIEW | 2026-09-06 07:35:23 | 900101 | P0-6.3 escalate |
| 5 | 2101930187108151297 | projects | 1 | LEADER_REVIEW | 2026-09-21 15:03:03 | 900101 | R152-B4 acceptance: verify Workdays.add |

**共同特征**：
- 全部由 requester_id=900101 (`ipd-admin`, person_type=**SUPER_ADMIN**) 创建
- entity_id 均为小整数（1/2/3/999），非雪花 ID → 早期测试探针数据
- reason 字段明确标注测试场景编号（P0-6.x / R152-B4）

### 1.5 非僵尸行说明（27 行，正常业务态）

- **cert_templates 22 行 DELETED + 实体 del_flag='1'**：删除申请已执行完毕，实体已软删 → 正常终态
- **cert_templates 1 行 LEADER_REVIEW + 实体 del_flag='0'**：待审中，实体存活 → 正常在途
- **products 2 行 DELETED + 实体 del_flag='1'**：正常终态
- **products 1 行 ADMIN_REVIEW + 实体 del_flag='0'**：待审中 → 正常在途
- **projects 0 行正常**（唯一 1 行即僵尸）

### 1.6 附带发现：幻影 DELETED（非悬空 FK，另型异常）

| dr.id | entity_id | dr.status | executed_at | product.del_flag | product.status |
|-------|-----------|-----------|-------------|-----------------|----------------|
| 2103430048831811585 | 2103429777619726337 | DELETED | 2026-09-25 18:23:07 | **0** | ON_SALE |

删除申请标记 DELETED 且 executed_at 已填，但产品实体仍为 del_flag='0'。
**成因**：R215-P1 验收时 @TableLogic 导致 updateById 静默剔除 del_flag（已知 bug，R216 已修复并用 R216PROBE 行验证）。
**定性**：历史 bug 残留，非悬空外键，不影响本卡处置。建议后续单独修正（将 product del_flag 置 '1' 或将 dr.status 回退）。

### 1.7 成因定性

**根因：SUPER_ADMIN 提交删除申请时不校验实体存在性。**

源码证据（`DeletionRequestServiceImpl.java` 第 444-456 行）：
```java
private void requireSubmitTargetAllowed(IpdActor actor, String entityType, Long entityId) {
    // ... 参数校验 ...
    if ("SUPER_ADMIN".equals(actor.role())) {
        return; // ← 超管直接放行，不检查 entity 是否存在
    }
    TargetScope scope = resolveScope(entityType, entityId);
    // 非超管：scope 为 null 时 fail-closed → FORBIDDEN
}
```

- 非 SUPER_ADMIN 用户：`resolveScope()` 查不到实体 → scope(null,null) → FORBIDDEN（fail-closed，正确）
- SUPER_ADMIN：**跳过一切存在性校验** → 可对任意不存在的 entity_id 建申请

5 条僵尸行全部由 ipd-admin (SUPER_ADMIN) 在集成测试/验收场景中创建，entity_id 为手工指定的小整数（1/2/3/999），对应实体从未在数据库中实际存在。

---

## 二、bonus_pools 悬空外键分析

### 2.1 全量统计（20 行）

| 分类 | 数量 | 说明 |
|------|------|------|
| 项目物理不存在（真悬空） | **1** | pool→project_id=9140004，projects 表无此行 |
| 项目已软删（逻辑孤儿） | **1** | pool→project_id=2098389746999926785，projects.del_flag='1' |
| 项目正常存活 | 18 | 正常业务态 |

### 2.2 真悬空行明细

| 字段 | 值 |
|------|-----|
| pool.id | 2097169984949182465 |
| pool.project_id | **9140004** |
| pool.status | **DISTRIBUTED**（已分配！） |
| pool.del_flag | 0 |
| pool.create_time | 2026-09-08 11:47:43 |
| pool.update_time | 2026-09-24 19:25:17 |
| pool.final_pool | 42000.00 |
| pool.distributions | {"rdShare":0.4,"rdAmount":16800.0,"marketShare":0.6,"marketAmount":25200.0} |
| projects.id=9140004 | **不存在**（物理删除，非软删） |

### 2.3 时间线还原（有 SQL 文件 + audit_logs 双重证据）

| 时间 | 事件 | 证据来源 |
|------|------|---------|
| 2026-09-06 | seed SQL 创建 projects.id=9140004 (ZK-GATE-TEST 如门禁测试) | `2026-09-06-ipd-zk-scenario-seed.sql:129` |
| 2026-09-08 11:47:42 | bonus_pool compute（项目当时存活） | audit_logs seq=2294 BONUS_POOL_COMPUTE |
| 2026-09-18 | R35 批量清理：projects.id=9140004 软删 (del_flag='1', status='ARCHIVED') | `2026-09-18-r35-data-cleanup-batch.sql:69` |
| 2026-09-18 | R36 回填 archived_at | `2026-09-18-r36-archived-at-backfill-9140004.sql` |
| 2026-09-18 后某时 | projects.id=9140004 被**物理删除**（非 repo SQL，推测 DBA 手工或 purge 任务） | projects 表无此行（含 del_flag='1' 也无） |
| **2026-09-24 19:25:16** | pool FREEZE (DRAFT→CONFIRMED) **成功** | audit_logs seq=3516 |
| **2026-09-24 19:25:17** | pool DISTRIBUTE (CONFIRMED→DISTRIBUTED) **成功** | audit_logs seq=3517 |

### 2.4 关键发现：运行时暴露已发生

卡面原述"freeze/distribute 路径会被门禁拒（项目不存在），确认无运行时暴露"——**实测证伪**。

2026-09-24 的 freeze+distribute 在项目已物理消失 6 天后仍然成功执行。原因：

`BonusPoolService.freeze()` (第 1036 行)：
```java
public BonusPool freeze(Long id, String reason, IpdActor actor) {
    BonusPool pool = requireById(id);  // ← 只校验 pool 自身存在
    // ... 状态机校验 ...
    // ❌ 无 pool.project_id → projects 存在性校验
}
```

`BonusPoolService.distribute()` (第 1071 行)：
```java
public BonusPool distribute(Long id, ...) {
    BonusPool pool = requireById(id);  // ← 只校验 pool 自身存在
    Contribution contribution = requireConfirmedContribution(pool); // ← 校验 contribution
    // ❌ 无 pool.project_id → projects 存在性校验
}
```

对比 `compute()` (第 961 行) 调用 `buildPoolFromProjectWithAchievement()` → 第 763-765 行：
```java
Project project = projectMapper.selectById(projectId);
if (project == null) throw new ServiceException("项目不存在: " + projectId);
```
**compute 有校验，freeze/distribute 没有。**

### 2.5 逻辑孤儿行（项目已软删）

| 字段 | 值 |
|------|-----|
| pool.id | 2098460715223445505 |
| pool.project_id | 2098389746999926785 |
| pool.status | DRAFT |
| pool.del_flag | 0 |
| project.del_flag | **1** (ARCHIVED) |
| project.update_time | 2026-09-18 16:04:51 (R35 批次) |

**成因**：R35 清理软删项目时，`ProjectSoftDeleteExecutor.softDelete()` 仅释放 products 的 projectId 指针（`releaseProductLink`），**不级联处理 bonus_pools**。

### 2.6 成因定性汇总

| 类型 | 根因 | 证据强度 |
|------|------|---------|
| 真悬空 (9140004) | ① 项目被物理删除（超出软删生命周期）② freeze/distribute 不校验项目存在性 ③ 无 DB 外键约束 | 强（audit_logs + SQL 文件 + 源码三重） |
| 逻辑孤儿 (2098389746999926785) | ProjectSoftDeleteExecutor 不级联 bonus_pools | 强（源码 + R35 SQL 时间戳） |

---

## 三、代码层根因修复建议（不动手，待主协调安排）

### 3.1 deletion_requests：SUPER_ADMIN 提交时校验实体存在

- **文件**: `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/DeletionRequestServiceImpl.java`
- **方法**: `requireSubmitTargetAllowed()` 第 454-456 行
- **修法**: SUPER_ADMIN 豁免权限校验（归属范围），但**不豁免实体存在性校验**。在 `return` 前加：
  ```java
  if ("SUPER_ADMIN".equals(actor.role())) {
      // 权限豁免，但实体必须存在
      if (resolveScope(entityType, entityId).projectId() == null
          && resolveScope(entityType, entityId).groupId() == null
          && !entityExists(entityType, entityId)) {
          throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "目标实体不存在: " + entityType + "/" + entityId);
      }
      return;
  }
  ```
  或更简洁：在 submit() 入口处统一加 `requireEntityExists(entityType, entityId)` 前置校验（不分角色）。

### 3.2 bonus_pools：freeze/distribute 前置校验项目存在

- **文件**: `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/BonusPoolService.java`
- **方法**: `freeze()` 第 1036 行、`distribute()` 第 1071 行
- **修法**: 在 `requireById(id)` 后追加：
  ```java
  Project project = projectMapper.selectById(pool.getProjectId());
  if (project == null || "1".equals(project.getDelFlag())) {
      throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
          "奖金池关联项目不存在或已归档: projectId=" + pool.getProjectId());
  }
  ```

### 3.3 ProjectSoftDeleteExecutor：级联处理 bonus_pools

- **文件**: `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/executor/ProjectSoftDeleteExecutor.java`
- **方法**: `softDelete()` 第 42 行后
- **修法**: 在 `releaseProductLink()` 后追加 `releaseBonusPoolLink(id)`：将该项目下 DRAFT 状态的 bonus_pool 软删或标记为 ORPHANED。已 CONFIRMED/DISTRIBUTED 的池保留（已产生财务事实）但写审计告警。

### 3.4 DB 层加固（可选，需评估影响）

- `bonus_pools.project_id` → 加 FK 约束 `REFERENCES projects(id)` 会阻止物理删除项目。需先确认 purge 流程是否依赖物理删。
- `deletion_requests` 为多态关联（entity_type 决定目标表），无法加单一 FK。建议用 CHECK + 触发器或应用层保证。

---

## 四、清洗方案概要

详见 SQL 草稿文件：`docs/ipd-系统说明/验收/R217-工具与数据调查-20260925/dangling-fk-cleanup-draft.sql`

| 类别 | 行数 | 建议处置 |
|------|------|---------|
| deletion_requests 僵尸行 | 5 | 软删 (del_flag='1') + remark 标注 R217 清理 |
| bonus_pools 真悬空 (9140004) | 1 | 软删 (del_flag='1') + remark 标注；或保留作回归样本（owner 定） |
| bonus_pools 逻辑孤儿 (软删项目) | 1 | 软删 (del_flag='1') + remark 标注 |
| 幻影 DELETED (product 仍 live) | 1 | 修正 product del_flag='1'（补 R215 未落地的软删）或回退 dr.status |

**全部标注「待 owner 拍板不 apply」。**

---

## 五、卡面翻 done 判断

| 卡 | 验收要求 | 当前状态 | 能否翻 done |
|----|---------|---------|------------|
| 8a088a71 (R215-DATA) | "处置建议：owner 拍板" + 调查定性 | 调查完成 + 清洗方案已产出 + 根因已定位 | **可翻**（调查类卡，方案产出即闭环；实际清洗等 owner 拍板后另卡执行） |
| 5d5c4fcc (DATA-CLEAN-9140004) | "①定位成因 ②确认无运行时暴露 ③形成处置建议" | ①✅ ②**证伪**（有运行时暴露） ③✅ | **可翻但需备注**：②的结论与卡面预期不符（实际有暴露），需在卡面追加调查结论后翻 done，或 owner 确认"证伪也是有效调查产出" |
| df893d81 (R215-KANBAN-BUG) | 登记为工具缺陷 | 调查完成 + 定性 + 绕过方案 | **可翻** |
