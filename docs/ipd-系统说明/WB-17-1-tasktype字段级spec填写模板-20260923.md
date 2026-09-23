# WB-17-1 taskType 字段级 spec 填写模板（2026-09-23）

> **目的**：本表用于收集 8 类 PLANNED taskType 的**字段级 spec**，由 owner/产品填写。
> **填完后**：本表 + 现有 `workbench-tasktype-契约登记.yaml` 合并，作为后续工程实施的字段依据。
> **不动**：现有契约登记 YAML 已 commit 的 9 类 implemented 部分（避免与兄弟会话 `agent-batch8-wb171` 撞号）。
>
> 配套背景：
> - `WB-17-1-拍板4项建议-20260908.md` —— 4 项必拍内容（仅枚举、4 卡口径、4 类缺表决策等）
> - `workbench-tasktype-契约登记.yaml` —— 17 类契约登记（9 implemented + 8 PLANNED）
> - `WorkbenchTaskContractDriftTest.java` —— 门禁测试（保护契约登记不被改坏）
>
> **填写人**：owner 或产品（业务字段定义属业务决策）
> **收口动作**：owner 拍板后，由 R179+ 治理轮把本表内容合并回 `workbench-tasktype-契约登记.yaml`，后端按合并后的字段扩展聚合器/枚举/Service。

---

## 填写指南

每个 PLANNED 类需要 owner 拍板以下 5 类信息：

1. **字段列表**（fields）：该 taskType 在前端工作台卡上要展示哪些字段？字段名/类型/是否必填/中文标签/显示顺序
2. **可筛子字段**（filters）：前端列表页允许按什么过滤？
4. **上游表/字段**（source）：数据源是哪个表、关键锚字段是什么？
5. **业务规则**（rules）：该 taskType 的特殊业务约束（如状态机、权限、生命周期）

如果 owner 暂未确定某字段，可填「TBD」+ 备注，不要空着不填（门禁脚本会校验必填字段非空）。

---

## 8 类 PLANNED 字段级 spec

### 1. waiver_review（豁免审批）

```yaml
waiver_review:
  source_table: gate_waivers       # 待建（原型已有，本会话确认建表 or 复用）
  fields:
    - { name: projectId,    type: Long,   required: true,  label: "项目",   order: 1 }
    - { name: gateCode,    type: String, required: true,  label: "门禁",   order: 2 }
    - { name: reason,      type: String, required: true,  label: "豁免原因", order: 3 }
    - { name: applicantId, type: Long,   required: true,  label: "申请人", order: 4 }
    - { name: status,      type: String, required: true,  label: "状态",   order: 5 }  # PENDING / APPROVED / REJECTED
    # ... owner 补充
  filters: [projectId, gateCode, status]
  rules: TBD  # owner 补：状态机/权限/双签？
```

### 2. rd_replacement（研发替补审批）

```yaml
rd_replacement:
  source_table: TBD                # 原型双表 vs project_members 扩展（拍板建议 Q4-Q6）
  fields:
    - { name: projectId,     type: Long,   required: true, label: "项目",       order: 1 }
    - { name: originalPmId,  type: Long,   required: true, label: "原研发PM",   order: 2 }
    - { name: newPmId,       type: Long,   required: true, label: "替补PM",     order: 3 }
    - { name: effectiveDate, type: Date,   required: true, label: "生效日期",   order: 4 }
    - { name: status,        type: String, required: true, label: "状态",       order: 5 }
  filters: TBD
  rules: TBD
```

### 3. receipt_review（收据审核）

```yaml
receipt_review:
  source_table: receipt_ledger     # 域已建表但流程未贯通
  fields:
    - { name: projectId,    type: Long,   required: true, label: "项目",     order: 1 }
    - { name: amount,       type: BigDecimal, required: true, label: "金额", order: 2 }
    - { name: receiptNo,    type: String, required: true, label: "收据编号", order: 3 }
    - { name: submittedBy,  type: Long,   required: true, label: "提交人",   order: 4 }
    - { name: status,       type: String, required: true, label: "审核状态", order: 5 }
  filters: TBD
  rules: TBD
```

### 4. retirement_review（退市评审）

```yaml
retirement_review:
  source_table: TBD                # 原型新表 vs 复用 deletion_requests（拍板建议 Q7-Q9）
  fields:
    - { name: projectId,       type: Long,   required: true, label: "项目",         order: 1 }
    - { name: productId,       type: Long,   required: true, label: "产品",         order: 2 }
    - { name: retirementDate,  type: Date,   required: true, label: "退市日期",     order: 3 }
    - { name: reason,          type: String, required: true, label: "退市原因",     order: 4 }
    - { name: applicantId,     type: Long,   required: true, label: "申请人",       order: 5 }
    - { name: status,          type: String, required: true, label: "状态",         order: 6 }
  filters: TBD
  rules: TBD
```

### 5. capacity_approval（多项目容量备案）

```yaml
capacity_approval:
  source_table: TBD                # 待建（原型 multi_project_capacity_approvals，Q10-Q12）
  fields:
    - { name: applicantId,    type: Long,      required: true, label: "申请人",     order: 1 }
    - { name: pmRole,         type: String,    required: true, label: "PM角色",     order: 2 }  # MARKET_PM / RD_PM
    - { name: projectIds,     type: List<Long>, required: true, label: "涉及项目",   order: 3 }
    - { name: totalCapacity,  type: Integer,   required: true, label: "总容量",     order: 4 }
    - { name: approvedBy,     type: Long,      required: false, label: "审批人",     order: 5 }
    - { name: status,         type: String,    required: true, label: "状态",       order: 6 }  # PENDING / APPROVED / REJECTED
  filters: TBD
  rules: TBD  # owner 补：BR-INC-13 关联已确认是误判，本实义为多项目备案
```

### 6. change_implementation（变更实施）

```yaml
change_implementation:
  source_table: change_requests    # 域已建但实施动作流程未贯通
  fields:
    - { name: projectId,        type: Long,   required: true, label: "项目",     order: 1 }
    - { name: changeType,       type: String, required: true, label: "变更类型", order: 2 }  # LD/CC/其它
    - { name: implementerId,    type: Long,   required: true, label: "实施人",   order: 3 }
    - { name: plannedDate,      type: Date,   required: true, label: "计划日期", order: 4 }
    - { name: actualDate,       type: Date,   required: false, label: "实际日期", order: 5 }
    - { name: status,           type: String, required: true, label: "状态",     order: 6 }
  filters: TBD
  rules: TBD
```

### 7. change_verify（变更验收）

```yaml
change_verify:
  source_table: change_requests    # 域已建但验证动作流程未贯通
  fields:
    - { name: projectId,    type: Long,   required: true, label: "项目",       order: 1 }
    - { name: changeType,   type: String, required: true, label: "变更类型",   order: 2 }
    - { name: verifierId,   type: Long,   required: true, label: "验收人",     order: 3 }
    - { name: verifyResult, type: String, required: false, label: "验收结果", order: 4 }  # PASS / FAIL
    - { name: status,       type: String, required: true, label: "状态",       order: 5 }
  filters: TBD
  rules: TBD
```

### 8. bonus_lock（奖金池锁定）

```yaml
bonus_lock:
  source_table: bonus_pool_allocations  # 域已建（奖金分配表）但锁定流程未贯通
  fields:
    - { name: projectId,    type: Long,      required: true, label: "项目",       order: 1 }
    - { name: period,       type: String,    required: true, label: "账期",       order: 2 }  # YYYY-MM
    - { name: totalAmount,  type: BigDecimal, required: true, label: "总金额",     order: 3 }
    - { name: lockedBy,     type: Long,      required: true, label: "锁定人",     order: 4 }
    - { name: lockedAt,     type: DateTime,  required: true, label: "锁定时间",   order: 5 }
    - { name: status,       type: String,    required: true, label: "状态",       order: 6 }  # LOCKED / UNLOCKED
  filters: TBD
  rules: TBD
```

---

## 提交方式

owner 填完后，按下面方式收口：
1. **新建 PR/branch**：本表填充完整后单文件 commit，不与契约登记 YAML 混 commit
2. **合并回契约登记**：由 R179+ 治理轮把本表内容合并到 `workbench-tasktype-契约登记.yaml`（追加 `fields_template:` section 给 8 类 PLANNED）
3. **门禁更新**：同步扩展 `WorkbenchTaskContractDriftTest.java`，加 8 类 PLANNED 的字段必填校验
4. **聚合器扩展**：后端按字段级 spec 扩展对应 Aggregator

## 风险红线

- **不破兄弟会话约定**：本会话不动 9 类已实现部分，避免与 `agent-batch8-wb171` 撞号
- **不破业务决策**：8 类字段定义属业务决策，owner/产品拍板后才能动 Java 代码
- **不破 SSOT**：字段定义单一事实源是本表 + 契约登记 YAML，禁止双源平行定义