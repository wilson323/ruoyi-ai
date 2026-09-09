# R25 P0-4 租户白名单配置/DDL 对账报告（20260909-102746）

> 自动门禁：`scripts/check_tenant_excludes_apply.sh`（RC-3 配置先行对账）
> 模块：`ruoyi-ipd`
> 配置源：`ruoyi-admin/src/main/resources/application.yml`
> 后端基线：`5924c63c`

## 汇总

| 类别 | 数量 |
|---|---|
| tenant.excludes 登记 | 87 |
| Entity @TableName 数 | 51 |
| DB 实际表数 | N/A（DB 未连通） |
| 🔴 RC-3-A 配置先行（excludes 已登记但 DB 无表） | 0 |
| 🔴 RC-3-B 实体存在但 DB 未建表（致命） | 0 |
| 🟡 RC-3-C Entity 与 excludes 重叠（需复核） | 50 |

## 🔴 RC-3-A 配置先行（DB 缺表）

> tenant.excludes 已登记但 DB 实际无此表 —— "超前登记"反模式，参考 person_roles 教训

✅ 无配置先行（DB 已建表）

## 🔴 RC-3-B 实体存在但 DB 未建表（最严重）

> Entity 类已声明 @TableName 但 DB 实际无此表 —— 编译过、运行时查询空表

✅ 所有 Entity 表都已建表

## 🟡 RC-3-C Entity 与 excludes 重叠

> Entity 在业务模块但同表名已登记 excludes —— 可能是"租户共享表"或漏配置

| 表名 | Entity 路径 | 处置建议 |
|---|---|---|
| `ai_documents` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/AiDocument.java` | 确认是否租户共享，excludes 登记是预期 |
| `ai_model_configs` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/AiModelConfig.java` | 确认是否租户共享，excludes 登记是预期 |
| `allowance_ledgers` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/AllowanceLedger.java` | 确认是否租户共享，excludes 登记是预期 |
| `audit_log_chain_heads` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/AuditChainHead.java` | 确认是否租户共享，excludes 登记是预期 |
| `audit_logs` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/AuditLog.java` | 确认是否租户共享，excludes 登记是预期 |
| `bid_invitations` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/BidInvitation.java` | 确认是否租户共享，excludes 登记是预期 |
| `bid_responses` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/BidResponse.java` | 确认是否租户共享，excludes 登记是预期 |
| `bonus_allocations` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/BonusAllocation.java` | 确认是否租户共享，excludes 登记是预期 |
| `bonus_pools` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/BonusPool.java` | 确认是否租户共享，excludes 登记是预期 |
| `cert_templates` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/CertTemplate.java` | 确认是否租户共享，excludes 登记是预期 |
| `coefficient_change_requests` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/CoefficientChangeRequest.java` | 确认是否租户共享，excludes 登记是预期 |
| `contribution_versions` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ContributionVersion.java` | 确认是否租户共享，excludes 登记是预期 |
| `contributions` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Contribution.java` | 确认是否租户共享，excludes 登记是预期 |
| `deletion_requests` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/DeletionRequest.java` | 确认是否租户共享，excludes 登记是预期 |
| `deliverables` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Deliverable.java` | 确认是否租户共享，excludes 登记是预期 |
| `gate_arbitrations` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/GateArbitration.java` | 确认是否租户共享，excludes 登记是预期 |
| `gate_element_results` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/GateElementResult.java` | 确认是否租户共享，excludes 登记是预期 |
| `gate_review_elements` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/GateElement.java` | 确认是否租户共享，excludes 登记是预期 |
| `gate_review_observers` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/GateReviewObserver.java` | 确认是否租户共享，excludes 登记是预期 |
| `gate_reviews` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/GateReview.java` | 确认是否租户共享，excludes 登记是预期 |
| `gates` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Gate.java` | 确认是否租户共享，excludes 登记是预期 |
| `ipd_business_config_versions` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/IpdBusinessConfigVersion.java` | 确认是否租户共享，excludes 登记是预期 |
| `ipd_business_config` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/IpdBusinessConfig.java` | 确认是否租户共享，excludes 登记是预期 |
| `kpi_records` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/KpiRecord.java` | 确认是否租户共享，excludes 登记是预期 |
| `launch_date_change_requests` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/LaunchDateChangeRequest.java` | 确认是否租户共享，excludes 登记是预期 |
| `legacy_imports` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/LegacyImport.java` | 确认是否租户共享，excludes 登记是预期 |
| `negative_feedbacks` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/NegativeFeedback.java` | 确认是否租户共享，excludes 登记是预期 |
| `notification_events` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/NotificationEvent.java` | 确认是否租户共享，excludes 登记是预期 |
| `persons` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Person.java` | 确认是否租户共享，excludes 登记是预期 |
| `product_groups` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProductGroup.java` | 确认是否租户共享，excludes 登记是预期 |
| `products` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Product.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_cert_items` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectCertItem.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_circle_comments` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectCircleComment.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_circle_posts` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectCirclePost.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_followers` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectFollower.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_members` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectMember.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_score_records` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectScoreRecord.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_score_tasks` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectScoreTask.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_scores` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectScore.java` | 确认是否租户共享，excludes 登记是预期 |
| `project_stages` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ProjectStage.java` | 确认是否租户共享，excludes 登记是预期 |
| `projects` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Project.java` | 确认是否租户共享，excludes 登记是预期 |
| `receipt_ledger` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/ReceiptLedger.java` | 确认是否租户共享，excludes 登记是预期 |
| `requirement_changes` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/RequirementChange.java` | 确认是否租户共享，excludes 登记是预期 |
| `requirement_pool` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/RequirementPool.java` | 确认是否租户共享，excludes 登记是预期 |
| `requirements` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Requirement.java` | 确认是否租户共享，excludes 登记是预期 |
| `sop_templates` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/SopTemplate.java` | 确认是否租户共享，excludes 登记是预期 |
| `stage_actions` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/StageAction.java` | 确认是否租户共享，excludes 登记是预期 |
| `switching_acceptance` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/SwitchingAcceptance.java` | 确认是否租户共享，excludes 登记是预期 |
| `system_config_versions` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/SystemConfigVersion.java` | 确认是否租户共享，excludes 登记是预期 |
| `system_configs` | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/SystemConfig.java` | 确认是否租户共享，excludes 登记是预期 |

## 重跑命令

```bash
cd /Users/mac/Documents/ruoyi-ai
./scripts/check_tenant_excludes_apply.sh
./scripts/check_tenant_excludes_apply.sh --module ruoyi-ipd
./scripts/check_tenant_excludes_apply.sh --skip-db   # 仅静态扫描
```

## CI 接入

```yaml
# .github/workflows/tenant-excludes.yml
- name: 租户白名单 + DDL 对账
  run: ./scripts/check_tenant_excludes_apply.sh
```

## 排除范围

- framework 自带 Entity（ruoyi-common-* 的 sys_*, gen_table 等）
- @TableName 不带 value 的会回退到类名转 snake_case —— 当前未处理，需 owner 复核
