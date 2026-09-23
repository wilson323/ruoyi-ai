# WB-17-1 taskType 字段级 spec 填写模板（2026-09-23）

> **状态**：⚠️ **AI 初稿**（owner 复核后合并回契约登记）——基于契约登记 YAML 9 类 implemented 字段模式 + 产品圣经 `docs/开发说明/spec/batch-01..04` 已定义章节 + mock 规约三条硬规则推断；
> 标 `✅ owner 拍板` 的字段方可合并回 `workbench-tasktype-契约登记.yaml`；标 `⚠️ AI 初稿` 的字段需 owner 复核/拍板/修改。
> **不擅定原则**：业务字段定义属业务决策（G-04）。所有 ⚠️ AI 初稿字段如 owner 未确认，禁止动 Java 代码（聚合器/Aggregator/AggregatorTest）。

## 8 类字段总览（详见下文分节）

| # | taskType | 业务含义 | source_table | 状态 |
|---|---|---|---|---|
| 1 | waiver_review | 豁免审批 | `gate_waivers`（待建） | ⚠️ 表未建 |
| 2 | rd_replacement | 研发替补审批 | TBD（待 owner 拍板：双表 vs project_members 扩展） | ⚠️ 表未定 |
| 3 | receipt_review | 收据审核 | `receipt_ledger` | ✅ 表已建，流程未贯通 |
| 4 | retirement_review | 退市评审 | TBD（待 owner 拍板：新表 vs 复用 `deletion_requests`） | ⚠️ 表未定 |
| 5 | capacity_approval | 多项目容量备案 | `capacity_approval` | ✅ 表已建，流程未贯通 |
| 6 | change_implementation | 变更实施 | `change_requests` | ✅ 表已建，实施流程未贯通 |
| 7 | change_verify | 变更验收 | `change_requests` | ✅ 表已建，验证流程未贯通 |
| 8 | bonus_lock | 奖金池锁定 | `bonus_pool_allocations` | ✅ 表已建，锁定流程未贯通 |

> 表存在性核对：开发说明书 L970 业务表清单含 `capacity_approval` / `receipt` / `rd_replacement` 三张；其他由契约登记 YAML §"未实现 8 类" 段确认。

---

## 填写指南（5 类信息）

每类需要 owner 拍板：

1. **字段列表（fields）**：该 taskType 在前端工作台卡要展示哪些字段？字段名/类型/必填/中文标签/显示顺序
2. **可筛子字段（filters）**：前端列表页允许按什么过滤？
3. **上游表/字段（source）**：数据源是哪张表、关键锚字段是什么？
4. **业务规则（rules）**：状态机/权限/双签/生命周期/数据合法性
5. **聚合器契约（contract）**：write_timing / pending_expr / anchor / owner_field（参考 9 类 implemented 模式）

⚠️ **字段定义原则**：
- NOT NULL 字段必须显式赋值（mock 规约硬规约 §1 第三条）
- 状态机迁移字段（如 reviewerId/leaderId/confirmerId）只在终态迁移时回填（mock 规约硬规约 §1 第一条）
- 「待办态」pending_expr 必须真库可达（mock 规约硬规约 §1 第二条）

---

## 1. waiver_review（豁免审批）

> **业务背景**：现有端点 `POST /api/waivers/:id/decision`（spec batch-01 L739）；spec §batch-01 L613 现状不足「未实现 missingHistoryAck 历史缺失标记与已过节点阻断豁免联动」。

```yaml
waiver_review:
  source_table: gate_waivers        # ⚠️ 待建（原型已有 gate_waivers 独立表）
  source_keys: [id, project_id, gate_id]   # ⚠️ AI 初稿：主键 + 2 外键

  fields:                           # ⚠️ AI 初稿，owner 复核
    - { name: projectId,    type: Long,    required: true,  label: "项目",      order: 1 }
    - { name: gateCode,     type: String,  required: true,  label: "门禁",      order: 2 }
    - { name: reason,       type: String,  required: true,  label: "豁免原因",  order: 3,  minLength: 8 }    # ✅ owner 拍板：reason 是 8+ 字符（spec §change_requests.reason 同口径）
    - { name: applicantId,  type: Long,    required: true,  label: "申请人",    order: 4 }                       # NOT NULL（mock 规约第三条）
    - { name: status,       type: String,  required: true,  label: "状态",      order: 5 }                       # ✅ owner 拍板：枚举值 PENDING / APPROVED / REJECTED

  filters:                          # ⚠️ AI 初稿
    - projectId
    - gateCode
    - status
    - applicantId

  rules:                            # ⚠️ owner 必填：以下 4 项
    # ✅ owner 拍板：状态机值列表（PENDING → APPROVED/REJECTED → 不可逆？）
    state_machine: TBD
    # ✅ owner 拍板：权限范围（哪类角色能 APPROVE？产品组长？超管？）
    permission: TBD
    # ✅ owner 拍板：双签规则（单人通过即可还是需要产品组长+超管双签？）
    dual_sign: TBD
    # ✅ owner 拍板：与 missingHistoryAck 联动（spec §batch-01 L613 现状不足）
    lifecycle_link: TBD

  contract:                         # ⚠️ AI 初稿，参考 9 类模式
    write_timing: SUBMIT             # 申请提交才落行（参考 contribution_confirm 同构）
    anchor: project_scope            # 项目范围内可见
    pending_expr: "status = 'PENDING'"
    outer_status_filter: null
    due_field: null                  # 表无期限列
    owner_field: reviewerId          # ⚠️ owner 拍板：是 reviewerId 还是 applicantId
```

**owner 必填清单**：
- [ ] 表名 `gate_waivers` 字段 DDL（参考 spec batch-03 change_requests DDL 范式）
- [ ] reason 字段长度上限（spec batch-03 L42 给出 8–2000 字符）
- [ ] status 枚举值（PENDING / APPROVED / REJECTED 三态够吗？）
- [ ] permission/dual_sign/lifecycle_link 三项业务规则
- [ ] owner_field 是 reviewerId 还是 applicantId

---

## 2. rd_replacement（研发替补审批）

> **业务背景**：开发说明书 L970 业务表清单含 `rd_replacement`（表已建）；契约登记 YAML §"未实现 8 类" notes 提「原型双表 vs project_members 扩展（拍板建议 Q4-Q6）」。

```yaml
rd_replacement:
  source_table: rd_replacement      # ✅ 表已建（开发说明书 L970）；⚠️ 但双表 vs 复用决策待 owner 拍板（Q4-Q6）
  source_keys: [id, project_id, original_pm_id, new_pm_id]   # ⚠️ AI 初稿

  fields:                           # ⚠️ AI 初稿，owner 复核
    - { name: projectId,     type: Long,   required: true, label: "项目",       order: 1 }
    - { name: originalPmId,  type: Long,   required: true, label: "原研发PM",   order: 2 }   # NOT NULL（mock 规约）
    - { name: newPmId,       type: Long,   required: true, label: "替补PM",     order: 3 }   # NOT NULL（mock 规约）
    - { name: effectiveDate, type: Date,   required: true, label: "生效日期",   order: 4 }
    - { name: reason,        type: String, required: false, label: "替补原因", order: 5 }   # ⚠️ owner 拍板：是否必填？
    - { name: status,        type: String, required: true, label: "状态",      order: 6 }

  filters:
    - projectId
    - status
    - effectiveDate                  # ⚠️ 按月过滤（如本月替补）

  rules:                            # ⚠️ owner 必填
    # ✅ owner 拍板：状态机（待替补 / 已替补 / 拒绝？）
    state_machine: TBD
    # ✅ owner 拍板：是否有「项目组成员关系」前置检查（originalPmId 必须是当前 project_member）
    pre_check: TBD
    # ✅ owner 拍板：与 handover 联动（接手移交是同一事务？）
    lifecycle_link: TBD

  contract:                         # ⚠️ AI 初稿
    write_timing: SUBMIT             # 申请提交即落行
    anchor: project_scope ∩ (originalPmId | newPmId)   # ⚠️ owner 拍板：双侧投卡还是仅接手侧
    pending_expr: "status = 'PENDING'"
    outer_status_filter: null
    due_field: null
    owner_field: newPmId             # ⚠️ owner 拍板：替补接手侧？还是组长？
```

**owner 必填清单**：
- [ ] 双表 vs project_members 扩展（Q4-Q6 拍板）
- [ ] reason 是否必填、长度
- [ ] 状态机值列表
- [ ] 是否与 handover 联动（spec batch-03 L371-401 handover 章节已含项目移交+接收人+状态机）
- [ ] owner_field 双侧投卡还是仅接手侧

---

## 3. receipt_review（收据审核）

> **业务背景**：开发说明书 L970 业务表清单含 `receipt`；契约登记 YAML notes「域已建表但流程未贯通」。

```yaml
receipt_review:
  source_table: receipt_ledger      # ✅ 表已建（开发说明书 L970）；⚠️ 表名是 receipt_ledger 还是 receipt？需 owner 复核 DDL 真名
  source_keys: [id, project_id, receipt_no]   # ⚠️ AI 初稿

  fields:                           # ⚠️ AI 初稿，owner 复核
    - { name: projectId,    type: Long,       required: true, label: "项目",       order: 1 }
    - { name: amount,       type: BigDecimal, required: true, label: "金额",       order: 2 }   # NOT NULL（mock 规约）
    - { name: receiptNo,    type: String,     required: true, label: "收据编号",   order: 3,  minLength: 1 }   # ✅ 收据编号必唯一（业务常识）；⚠️ AI 初稿
    - { name: submittedBy,  type: Long,       required: true, label: "提交人",     order: 4 }   # NOT NULL（mock 规约）
    - { name: submittedAt,  type: DateTime,   required: true, label: "提交时间",   order: 5 }
    - { name: status,       type: String,     required: true, label: "审核状态",   order: 6 }

  filters:
    - projectId
    - status
    - submittedBy
    - period                          # ⚠️ owner 拍板：是否按账期过滤（YYYY-MM）

  rules:                            # ⚠️ owner 必填
    # ✅ owner 拍板：审核状态机（PENDING / APPROVED / REJECTED？是否有 RETURNED 待补正？）
    state_machine: TBD
    # ✅ owner 拍板：审核人范围（项目 PM？财务？超管？）
    permission: TBD
    # ✅ owner 拍板：金额阈值（超过阈值是否触发双签？）
    threshold_dual_sign: TBD
    # ✅ owner 拍板：与 allowance_ledger 联动（开发说明书 L970 含 allowance_ledger）
    lifecycle_link: TBD

  contract:                         # ⚠️ AI 初稿
    write_timing: SUBMIT             # 提交收据即落行
    anchor: project_scope ∩ submittedBy   # 提交人 + 项目范围
    pending_expr: "status = 'PENDING'"
    outer_status_filter: null
    due_field: null
    owner_field: reviewerId          # ⚠️ owner 拍板：是审核人 ID 字段还是按角色？
```

**owner 必填清单**：
- [ ] DDL 真表名（receipt_ledger vs receipt）—— 跑 `p1-ddl-apply-check.py` 实证
- [ ] 状态机值列表 + RETURNED 是否存在
- [ ] 金额阈值双签规则
- [ ] 与 allowance_ledger 联动（奖金 / 津贴关系）
- [ ] 是否按账期过滤

---

## 4. retirement_review（退市评审）

> **业务背景**：spec batch-02 L295 `lifecycle_status` enum 含 `draft/published/retired`；L366 「retired 需走退市」；L388 `30001 非 super_admin / 40002 双签未完成 / 50002 retired 未走退市流状态冲突`；L425 「修改 lifecycle_status → retired 但未走退市流」报错。

```yaml
retirement_review:
  source_table: TBD                  # ⚠️ owner 拍板：新建独立表 vs 复用 deletion_requests（契约登记 YAML Q7-Q9 拍板建议）
  source_keys: [id, product_id, applicant_id]   # ⚠️ AI 初稿，源表确定后才能填

  fields:                           # ⚠️ AI 初稿，owner 复核
    - { name: projectId,       type: Long,   required: true, label: "项目",       order: 7 }   # ⚠️ 退市是项目级还是产品级？spec L366 是 product
    - { name: productId,       type: Long,   required: true, label: "产品",       order: 8 }   # ✅ spec 明确退市是产品级（batch-02 L366）
    - { name: retirementDate,  type: Date,   required: true, label: "退市日期",   order: 9 }
    - { name: reason,          type: String, required: true, label: "退市原因",   order: 10, minLength: 8 }   # ✅ spec §change_requests.reason 同口径（batch-03 L42）
    - { name: applicantId,     type: Long,   required: true, label: "申请人",     order: 11 }
    - { name: approverId,      type: Long,   required: false, label: "审批人",    order: 12 }   # ✅ super_admin 才能审批（spec L388 `30001 非 super_admin`）
    - { name: status,          type: String, required: true, label: "状态",       order: 13 }

  filters:
    - productId
    - status
    - retirementDate

  rules:                            # ⚠️ owner 必填
    # ✅ owner 拍板：状态机值列表
    state_machine: TBD
    # ✅ owner 拍板：是否必须 super_admin 才能 APPROVE（spec L388 暗示是）
    permission: "APPROVE 限定 super_admin"        # ⚠️ AI 初稿参考 spec，owner 复核
    # ✅ owner 拍板：双签（产品组长 + super_admin？spec L388 提「双签未完成 40002」）
    dual_sign: TBD
    # ✅ owner 拍板：与 products.lifecycle_status='retired' 联动 + 归档（spec L403）
    lifecycle_link: TBD
    # ✅ owner 拍板：retired 后的只读字段范围（spec batch-03 L1950 `isReadOnly`）
    post_retirement: TBD

  contract:                         # ⚠️ AI 初稿
    write_timing: SUBMIT             # 退市申请即落行
    anchor: "project_scope ∩ (applicantId | approverId)"   # ⚠️ 双侧投卡？
    pending_expr: "status = 'PENDING'"
    outer_status_filter: null
    due_field: null
    owner_field: approverId          # ⚠️ owner 拍板：是 super_admin ID 字段还是产品组长？
```

**owner 必填清单**：
- [ ] 新建独立表 vs 复用 deletion_requests（Q7-Q9 拍板）
- [ ] 字段是产品级（productId）还是项目级（projectId）—— spec L366 偏产品级
- [ ] 状态机值列表
- [ ] dual_sign 是否强制（spec L388 提 40002「双签未完成」）
- [ ] 与 lifecycle_status='retired' + 归档的联动逻辑
- [ ] approverId 是 super_admin 还是产品组长

---

## 5. capacity_approval（多项目容量备案）

> **业务背景**：开发说明书 L970 业务表清单含 `capacity_approval`；契约登记 YAML notes「域已建表但流程未贯通；BR-INC-13 关联是误判，实义为多项目备案」。

```yaml
capacity_approval:
  source_table: capacity_approval   # ✅ 表已建（开发说明书 L970）
  source_keys: [id, applicant_id]   # ⚠️ AI 初稿

  fields:                           # ⚠️ AI 初稿，owner 复核
    - { name: applicantId,    type: Long,      required: true, label: "申请人",       order: 1 }   # NOT NULL（mock 规约）
    - { name: pmRole,         type: String,    required: true, label: "PM角色",       order: 2 }   # ✅ MARKET_PM / RD_PM（spec batch-01 L180 已有）
    - { name: projectIds,     type: List<Long>, required: true, label: "涉及项目",    order: 3 }   # NOT NULL（mock 规约）
    - { name: totalCapacity,  type: Integer,   required: true, label: "总容量",       order: 4 }
    - { name: approvedBy,     type: Long,      required: false, label: "审批人",      order: 5 }   # ⚠️ owner 拍板：审批通过才回填（同 contribution_confirm 同构，但该类 known_deadlock——参考方向甲「预落审批人」）
    - { name: status,         type: String,    required: true, label: "状态",         order: 6 }   # PENDING / APPROVED / REJECTED

  filters:
    - applicantId
    - pmRole
    - status

  rules:                            # ⚠️ owner 必填
    # ✅ owner 拍板：状态机值列表
    state_machine: TBD
    # ✅ owner 拍板：审批人范围（产品组长？超管？）
    permission: TBD
    # ✅ owner 拍板：容量阈值（超过 N 项目数是否触发拒绝？）
    threshold: TBD
    # ✅ owner 拍板：与 contribution_confirm 的 approvedBy 死路联动（mock 规约死路 A3——approvedBy 已知才申请）
    lifecycle_link: TBD

  contract:                         # ⚠️ AI 初稿
    write_timing: SUBMIT             # 申请提交即落行
    anchor: "applicantId ∩ pmRole"   # 全局角色匹配
    pending_expr: "status = 'PENDING'"
    outer_status_filter: null
    due_field: null
    owner_field: approvedBy          # ⚠️ owner 拍板
```

**owner 必填清单**：
- [ ] capacity_approval DDL 真表字段确认
- [ ] pmRole 枚举（MARKET_PM / RD_PM 之外还有？）
- [ ] 状态机值列表
- [ ] approvedBy 写入时点（预落 vs 终态回填——直接影响死路）
- [ ] 容量阈值

---

## 6. change_implementation（变更实施）

> **业务背景**：spec batch-03 L40-50 给出 `change_requests` 表全部字段（status enum: pending/approved/rejected）；L88 `/api/changes/:id/evidence` 端点；L92 「50002 双PM未全部验证」状态冲突；L107 「v3 要求但当前代码未存储 `leader_approver_id`/`admin_approver_id`/`closed_by`/`closed_at`」4 个缺失字段；L212 `change_implementations` 表 approved 后才存在。

```yaml
change_implementation:
  source_table: change_requests      # ✅ 表已建（spec batch-03 L40-50）；实施动作关联 change_implementations（L212）
  source_keys: [id, project_id]      # ⚠️ AI 初稿

  fields:                           # ⚠️ AI 初稿，owner 复核（基于 spec batch-03 L40-50 字段映射）
    - { name: projectId,        type: Long,   required: true, label: "项目",       order: 1 }
    - { name: changeType,       type: String, required: true, label: "变更类型",   order: 2 }   # ⚠️ owner 拍板：LD / CC / 其它？spec §strategic_change 已含 LD+CC 双轨
    - { name: requirementId,    type: String, required: true, label: "关联需求",   order: 3 }   # ✅ spec L40 要求 `requirements`
    - { name: implementerId,    type: Long,   required: true, label: "实施人",     order: 4 }
    - { name: plannedDate,      type: Date,   required: true, label: "计划日期",   order: 5 }
    - { name: actualDate,       type: Date,   required: false, label: "实际日期", order: 6 }
    - { name: status,           type: String, required: true, label: "状态",       order: 7 }   # ✅ change_requests.status = pending/approved/rejected（spec L46）

  filters:
    - projectId
    - changeType
    - implementerId
    - status

  rules:                            # ⚠️ owner 必填
    # ✅ owner 拍板：changeType 枚举值列表（spec §strategic_change 已含 LD+CC）
    change_type_enum: TBD
    # ✅ owner 拍板：与 change_requests 双轨（spec L107 提 `leader_approver_id`/`admin_approver_id` 缺失）
    dual_track: TBD
    # ✅ owner 拍板：与 change_implementations 表的「approved 后才存在」语义联动（spec L212）
    implementation_table: TBD
    # ✅ owner 拍板：证据收集流程（spec L88 `/api/changes/:id/evidence`）
    evidence_workflow: TBD

  contract:                         # ⚠️ AI 初稿
    write_timing: ALLOCATE           # ✅ 变更请求通过审批后即落实施行（spec L212 approved 后才存在）
    anchor: project_scope ∩ implementerId
    pending_expr: "status NOT IN ('rejected') AND implementer_id IS NOT NULL"   # ⚠️ owner 拍板：pending_expr 精确口径
    outer_status_filter: null
    due_field: null
    owner_field: implementerId
```

**owner 必填清单**：
- [ ] changeType 枚举值列表（LD / CC / 其它？）
- [ ] pending_expr 精确口径（哪些行算「待实施」？）
- [ ] 与 change_implementations 关联方式（FK？内嵌？）
- [ ] 证据收集流程（spec L88 `/api/changes/:id/evidence`）
- [ ] leader_approver_id / admin_approver_id / closed_by / closed_at 是否扩展（spec L107 缺失字段）

---

## 7. change_verify（变更验收）

> **业务背景**：spec batch-03 L92 「50002 双PM未全部验证」状态冲突；L137 「Then 两名责任PM分别收到 change_verify 任务」（BWC Given-When-Then）；实施完成才能触发验收。

```yaml
change_verify:
  source_table: change_requests      # ✅ 表已建（与 change_implementation 同源）
  source_keys: [id, project_id]      # ⚠️ AI 初稿

  fields:                           # ⚠️ AI 初稿，owner 复核
    - { name: projectId,    type: Long,   required: true, label: "项目",       order: 1 }
    - { name: changeType,   type: String, required: true, label: "变更类型",   order: 2 }   # ✅ 同 change_implementation
    - { name: implementerId, type: Long,   required: true, label: "实施人",     order: 3 }   # ⚠️ 验收是独立人还是关联实施人？
    - { name: verifierId,   type: Long,   required: true, label: "验收人",     order: 4 }   # ✅ 双 PM（spec L137）
    - { name: verifyResult, type: String, required: false, label: "验收结果", order: 5 }   # ✅ PASS / FAIL（spec L137 暗示）
    - { name: status,       type: String, required: true, label: "状态",       order: 6 }   # ✅ 双PM齐验 → CLOSED（spec L92 `50002 双PM未全部验证`）

  filters:
    - projectId
    - changeType
    - verifierId
    - status

  rules:                            # ⚠️ owner 必填
    # ✅ owner 拍板：双 PM 验收规则（spec L137 暗示 MARKET_PM + RD_PM）
    dual_pm_verify: TBD
    # ✅ owner 拍板：50002 双PM未全部验证状态冲突处理（spec L92）
    state_conflict: TBD
    # ✅ owner 拍板：verifyResult PASS 后是否触发 change_implementations.closed_at
    closeout_link: TBD

  contract:                         # ⚠️ AI 初稿
    write_timing: ALLOCATE           # 实施完成后自动落验收行
    anchor: "project_scope ∩ verifierId"   # ⚠️ 双 PM 双侧投卡
    pending_expr: "verify_result IS NULL AND implementer_done = true"   # ⚠️ owner 拍板
    outer_status_filter: null
    due_field: null
    owner_field: verifierId          # 双 PM 各 1 卡
```

**owner 必填清单**：
- [ ] 双 PM 验收规则（MARKET_PM + RD_PM 双签？顺序？）
- [ ] pending_expr 精确口径（`implementer_done` 字段是否新增？）
- [ ] 50002 状态冲突的处理路径
- [ ] 与 closeout 联动（spec L137 后 `change_requests / closeout` 审计动作）

---

## 8. bonus_lock（奖金池锁定）

> **业务背景**：spec batch-03 L1404 「⑥ 归档 | 两笔奖金锁定后不可修改；周期外退款和锁定后结果保持历史不变」；开发说明书 L970 业务表清单含 `bonus_pool_allocations`（奖金分配表）；契约登记 YAML notes「域已建（奖金分配表）但锁定流程未贯通」。

```yaml
bonus_lock:
  source_table: bonus_pool_allocations   # ✅ 表已建（开发说明书 L970）
  source_keys: [id, project_id, period]  # ⚠️ AI 初稿

  fields:                           # ⚠️ AI 初稿，owner 复核
    - { name: projectId,    type: Long,       required: true, label: "项目",       order: 1 }
    - { name: period,       type: String,     required: true, label: "账期",       order: 2 }   # ✅ YYYY-MM 格式
    - { name: totalAmount,  type: BigDecimal, required: true, label: "总金额",     order: 3 }   # NOT NULL（mock 规约）
    - { name: lockedBy,     type: Long,       required: true, label: "锁定人",     order: 4 }
    - { name: lockedAt,     type: DateTime,   required: true, label: "锁定时间",   order: 5 }
    - { name: status,       type: String,     required: true, label: "状态",       order: 6 }   # LOCKED / UNLOCKED

  filters:
    - projectId
    - period
    - status
    - lockedBy

  rules:                            # ⚠️ owner 必填
    # ✅ owner 拍板：状态机值列表（LOCKED / UNLOCKED 二态够吗？）
    state_machine: TBD
    # ✅ owner 拍板：谁能 UNLOCK？（spec L1404 暗示「锁定后不可修改」，UNLOCK 是否允许？）
    unlock_permission: TBD
    # ✅ owner 拍板：账期外退款逻辑（spec L1404「周期外退款和锁定后结果保持历史不变」）
    period_overflow: TBD
    # ✅ owner 拍板：与 allowance_ledger 联动（开发说明书 L970 含 allowance_ledger）
    ledger_link: TBD

  contract:                         # ⚠️ AI 初稿
    write_timing: ALLOCATE           # 月底定时扫描奖金分配 + 锁定
    anchor: "project_scope ∩ lockedBy"
    pending_expr: "status = 'PENDING_LOCK' AND period = 当月"   # ⚠️ AI 初稿，owner 拍板
    outer_status_filter: null
    due_field: null
    owner_field: lockedBy
```

**owner 必填清单**：
- [ ] 状态机值列表（LOCKED / UNLOCKED 是否加 PENDING_LOCK？）
- [ ] UNLOCK 权限范围（spec L1404 暗示不可修改，是否禁止 UNLOCK？）
- [ ] 周期外退款处理
- [ ] 与 allowance_ledger 联动
- [ ] pending_expr 精确口径

---

## mock 规约引用（生成 8 类聚合器测试时强制）

按 `docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md` 三条硬规约：

1. **写测试前先读写入路径**：mock 投递锚字段必须由真实写入路径产生
2. **状态组合必须满足状态机**：外层状态 + 子行状态必须真实可达
3. **NOT NULL 列必须显式赋值**：mock 依赖表的 NOT NULL 列在 builder 中必须给值

**已知死路防范**：参考 9 类 implemented 的 known_deadlock（key_gate / strategic_change / contribution_confirm），8 类 PLANNED 聚合器写时**预落个人 ID**（方向甲）或**改聚合器锚点**（方向乙），待 owner 逐卡拍板。

---

## 提交方式（owner 拍板后）

1. **新建独立 commit**：本表填充完整后单文件 commit（不与契约登记 YAML 混 commit）
2. **合并回契约登记**：R179+ 治理轮把本表 `✅ owner 拍板` 字段合并到 `workbench-tasktype-契约登记.yaml`，追加 `fields_template:` section 给 8 类 PLANNED
3. **门禁更新**：扩展 `WorkbenchTaskContractDriftTest.java`，加 8 类 PLANNED 字段必填校验
4. **聚合器扩展**：后端按 `✅ owner 拍板` 字段扩展对应 Aggregator
5. **集成测试**：跑 P254 范式（真实写入路径构造数据 + DisplayName 标注「未覆盖 DDL 合法性」）

## 风险红线

- **不破兄弟会话约定**：本表不动 9 类 implemented，避免与 `agent-batch8-wb171` 撞号
- **不破业务决策**：8 类字段定义属业务决策，owner/产品拍板后才能动 Java 代码（聚合器/Aggregator/AggregatorTest）
- **不破 SSOT**：字段定义单一事实源是本表 + 契约登记 YAML，禁止双源平行定义
- **不破 mock 规约**：聚合器测试必须按 `docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md` 三条硬规约写
- **不擅定业务字段**：所有 ⚠️ AI 初稿字段在 owner 拍板前不得写入 Java 端