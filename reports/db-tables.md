# DB 表清单（R139 配套，2026-09-20）

> 抓取时间：2026-09-20（周日）
> 抓取命令：`mysql --defaults-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf -e "USE ipd_dev; SHOW TABLES;"`
> DB 库：ipd_dev@13306（socket 通；兄弟会话未启后端）
> 总表数：152
> 业务表（IPD 域）：~65 张（59 个 @TableName 显式 + 6 个隐式映射）
> RuoYi 系统表（sys_）：~32 张
> 定时任务表（sj_）：~22 张
> 短剧 / 智能体 / chat / knowledge / workflow / mcp / t_workflow / trace / gen / test 等杂项：~33 张

## 业务表（按功能分组 + 数据行数）

### A. 项目 / 产品核心（18 张）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| products | 54 | ✅ Product.java | ProductController |
| product_groups | 12 | ✅ ProductGroup.java | ProductGroupController |
| product_retirements | 0 | ✅ ProductRetirement.java | （后端未交付） |
| projects | 45 | ✅ Project.java | ProjectController |
| project_stages | 246 | ✅ ProjectStage.java | ProjectController |
| project_members | 21 | ✅ ProjectMember.java | ProjectMemberController |
| project_followers | 0 | ✅ ProjectFollower.java | （后端未交付） |
| stage_actions | 2304 | ✅ StageAction.java | StageActionController |
| project_cert_items | 28 | ✅ ProjectCertItem.java | ProjectController |
| cert_templates | 39 | ✅ CertTemplate.java | CertTemplateController |
| sop_templates | 1 | ✅ SopTemplate.java | SopTemplateController |
| sop_template_instances | 0 | ✅ SopTemplateInstance.java | （后端未交付） |
| deliverables | 1 | ✅ Deliverable.java | （端点未交付） |
| switching_acceptance | 0 | ✅ SwitchingAcceptance.java | SwitchingAcceptanceController |
| project_circle_posts | 0 | ✅ ProjectCirclePost.java | ProjectCircleController |
| project_circle_comments | 0 | ✅ ProjectCircleComment.java | ProjectCircleController |
| project_scores | 0 | ✅ ProjectScore.java | ProjectScoreController |
| project_score_records | 0 | ✅ ProjectScoreRecord.java | （后端未交付） |
| project_score_tasks | 0 | ✅ ProjectScoreTask.java | ProjectScoreTaskController |

### B. Gate 评审 / 流程（9 张）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| gates | 21 | ✅ Gate.java | GateElementResultController |
| gate_reviews | 29 | ✅ GateReview.java | GateReviewController |
| gate_review_elements | 76 | ✅ GateReviewElement.java | GateElementResultController |
| gate_review_observers | 0 | ✅ GateReviewObserver.java | GateReviewController |
| gate_element_results | 30 | ✅ GateElementResult.java | GateElementResultController |
| gate_arbitrations | 3 | ✅ GateArbitration.java | GateReviewController |
| gate_waivers | 0 | ✅ GateWaiver.java | （后端未交付） |
| ipd_business_config | 13 | ✅ IpdBusinessConfig.java | SystemConfigController |
| ipd_business_config_versions | 0 | ✅ IpdBusinessConfigVersion.java | SystemConfigController |

### C. 激励 / KPI / 贡献度（10 张）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| kpi_records | 2 | ✅ KpiRecord.java | KpiRecordController |
| kpi_shared_confirms | 4 | ✅ KpiSharedConfirm.java | SharedKpiController |
| kpi_rule_snapshots | 0 | ✅ KpiRuleSnapshot.java | （端点未交付，P0 卡） |
| contributions | 2 | ✅ Contribution.java | ContributionController |
| contribution_versions | 0 | ✅ ContributionVersion.java | ContributionController |
| bonus_pools | 19 | ✅ BonusPool.java | BonusPoolController |
| bonus_allocations | 0 | ✅ BonusAllocation.java | （端点未交付） |
| allowance_ledgers | 7 | ✅ AllowanceLedger.java | AllowanceLedgerController |
| receipt_ledger | 0 | ✅ ReceiptLedger.java | ReceiptLedgerController |
| coefficient_change_requests | 2 | ✅ CoefficientChangeRequest.java | CoefficientChangeController |
| launch_date_change_requests | 3 | ✅ LaunchDateChangeRequest.java | LaunchDateChangeController |
| negative_feedbacks | 2 | ✅ NegativeFeedback.java | NegativeFeedbackController |

### D. 需求 / 变更 / 删除（5 张）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| requirement_pool | 0 | ✅ RequirementPool.java | （端点未交付） |
| requirements | 2 | ✅ Requirement.java | DemandController |
| requirement_changes | 0 | ✅ RequirementChange.java | RequirementChangeController |
| deletion_requests | 28 | ✅ DeletionRequest.java | DeletionRequestController |
| demands | — | ✅ Demand.java | DemandController（db 表名待确认） |

### E. 招标 / 招募（2 张）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| bid_invitations | 3 | ✅ BidInvitation.java | BidController |
| bid_responses | 4 | ✅ BidResponse.java | BidController |

### F. 人员 / 移交 / 同步（5 张）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| persons | 27 | ✅ Person.java | PersonController |
| persons_bk_b3_20260919 | 27 | ✅ | 备份表（2026-09-19 B3 整改） |
| person_sync_jobs | 1 | ✅ PersonSyncJob.java | PersonSyncController |
| handover_records | 2 | ✅ HandoverRecord.java | HandoverController |
| post_launch_reviews | 0 | ✅ PostLaunchReview.java | PostLaunchReviewController |

### G. 审计 / 通知 / 合规（3 张）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| audit_logs | 1359 | ✅ AuditLog.java | AuditLogController |
| audit_log_chain_heads | 0 | ✅ AuditChainHead.java | AuditLogController |
| notification_events | 68 | ✅ NotificationEvent.java | NotificationController |

### H. AI 文档 / AI 模型 / 报告（5 张）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| ai_documents | 4 | ✅ AiDocument.java | AiDocumentController |
| ai_doc_embeddings | 0 | ✅ AiDocEmbedding.java | AiDocumentController |
| ai_model_configs | 0 | ✅ AiModelConfig.java | AiModelConfigController |
| legacy_imports | 0 | ✅ LegacyImport.java | ProjectController |
| correction_logs | 0 | ✅ CorrectionLog.java | （后端未交付） |

### I. 待办 / 变更（2 张 — 0 行）
| 表名 | 行数 | Entity | Controller |
|---|---|---|---|
| multi_project_capacity_approvals | 0 | ✅ MultiProjectCapacityApproval.java | （端点未交付） |
| rd_replacements | 0 | ✅ RdReplacement.java | （端点未交付） |
| rd_replacement_approvals | 0 | ✅ RdReplacementApproval.java | （端点未交付） |

## 业务表统计
- 业务表总数（@TableName 显式）：59 张
- 业务表（隐式映射，如 demands/requirements 等）：约 6 张
- 业务表中有数据的：~25 张
- 业务表中 0 行的：~27 张（含 schema 已建但无数据）
- 后端有 Entity 但缺端点的：~10 张（product_retirements / sop_template_instances / deliverable / bonus_allocations / kpi_rule_snapshots / requirement_pool / multi_project_capacity_approvals / rd_replacements / rd_replacement_approvals / correction_logs）

## RuoYi 系统表（sys_）
~32 张（sys_user/sys_role/sys_menu/sys_dept/sys_config/sys_oss/sys_oss_config/sys_social/sys_tenant/sys_tenant_package/sys_url/sys_post/sys_user_role/sys_role_menu/sys_role_dept/sys_user_post/sys_dict_type/sys_dict_data/sys_notice/sys_oper_log/sys_logininfor/sys_config 等）— RuoYi 底座自带，业务无关。

## 定时任务表（sj_）
~22 张（sj_distributed_lock / sj_group_config / sj_job / sj_job_executor / sj_job_log_message / sj_job_summary / sj_job_task / sj_job_task_batch / sj_namespace / sj_notify_config / sj_notify_recipient / sj_retry / sj_retry_dead_letter / sj_retry_scene_config / sj_retry_summary / sj_retry_task / sj_retry_task_log_message / sj_server_node / sj_system_user / sj_system_user_permission / sj_workflow / sj_workflow_node / sj_workflow_task_batch）— SnailJob 调度框架。

## 杂项表（短剧 / 智能体 / chat / knowledge / mcp / t_workflow / flow / trace / gen / test）
~33 张 — 来自 RuoYi-AI 多模态底座，业务无关。

## DB ↔ Entity ↔ Controller 三方对照

### 表已建但 0 行（潜在空转风险）
27 张：
- ai_doc_embeddings / ai_model_configs / audit_log_chain_heads
- bonus_allocations / contribution_versions / correction_logs
- gate_waivers / gate_review_observers
- ipd_business_config_versions / kpi_rule_snapshots
- legacy_imports / multi_project_capacity_approvals
- post_launch_reviews / product_retirements
- project_circle_comments / project_circle_posts / project_followers
- project_score_records / project_score_tasks / project_scores
- rd_replacement_approvals / rd_replacements
- receipt_ledger / requirement_changes / requirement_pool
- sop_template_instances / switching_acceptance

### 表名不规范（单数违规 — R128 P0 #4）
3 张：
- requirement_pool（应为 requirement_pools）
- receipt_ledger（应为 receipt_ledgers）
- switching_acceptance（应为 switching_acceptances）

### Controller 端点 vs DB 表 覆盖度
- 后端 Controller：51 个
- 业务表：~65 张
- 完全对齐（Controller + Entity + DB 表 + 前端 API 调用）：约 25 张
- Entity 存在但端点缺失：~10 张（product_retirements / sop_template_instances / 等）
- 表存在但 Entity 缺失（隐式映射）：~6 张（demands/requirements 等）
