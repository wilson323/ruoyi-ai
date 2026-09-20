# 后端 Controller 端点清单（R139 配套，2026-09-20）

> 抓取时间：2026-09-20（周日）
> 抓取命令：`cd /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ && rg -oN '@(GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)\("([^"]+)"\)' <file>`
> 总条数：307 行（含类级 @RequestMapping + 方法级 4 映射）
> 基础路径去重：46 个
> Controller 数：51 个
> 完整原文：/tmp/all_endpoints_full.txt（307 行）

## Controller 分组（51 个）

### A. 业务核心 21 个
| Controller | basePath | 端点数 |
|---|---|---|
| ProjectController | /api/v1/projects | 14 |
| ProductController | /api/v1/products | 7 |
| ProductGroupController | /api/v1/product-groups | 3 |
| ProductWorkspaceController | /api/v1/products/{id}/workspace | 0（容器） |
| BidController | /api/v1 | 16（含 bid-invitations / bid-responses） |
| BidP231Controller | /api/v1/bid-invitations | 1 |
| GateElementController | /api/v1/gate-elements | 7 |
| GateElementResultController | /api/v1/gates/{gateId} | 5 |
| GateMaterialController | /api/v1/gates/{gateId}/materials | 0（容器） |
| GateReviewController | /api/v1/gates/{gateId} | 9 |
| GateSignScanController | /api/v1/gates/sign/* | 2（cron） |
| LegacyScanController | /api/v1/gates/legacy/* | 1（cron） |
| ProjectMemberController | /api/v1/projects/{projectId}/members | 0（容器） |
| ProjectScoreController | /api/v1/project-scores | 2 |
| ProjectScoreTaskController | /api/v1/project-score-tasks | 2 |
| ProjectCircleController | /api/v1/project-circle | 6 |
| StageActionController | /api/v1/stage-actions | 5 |
| CertTemplateController | /api/v1/cert-templates | 3 |
| SopTemplateController | /api/v1/sop-templates | 8 |
| RequirementChangeController | /api/v1 | 6 |
| PersonController | /api/v1/persons | 3 |

### B. 激励 / KPI 9 个
| Controller | basePath | 端点数 |
|---|---|---|
| AllowanceLedgerController | /api/v1/allowance | 3 |
| BonusPoolController | /api/v1/bonus-pool | 8 |
| ContributionController | /api/v1/contributions | 6 |
| KpiRecordController | /api/v1/kpi | 3 |
| SharedKpiController | /api/v1/kpi/shared | 4 |
| CoefficientChangeController | /api/v1/coefficient-change-requests | 1 |
| LaunchDateChangeController | /api/v1/launch-date-change-requests | 1 |
| NegativeFeedbackController | /api/v1/negative-feedbacks | 5 |
| ReceiptLedgerController | /api/v1/receipt-ledgers | 2 |

### C. 鉴权 / 用户 / 工作台 9 个
| Controller | basePath | 端点数 |
|---|---|---|
| IpdAuthController | /api/v1/auth | 6 |
| PersonSyncController | /api/v1/person-sync | 5 |
| HrSyncController | /api/v1/hr-sync | 3 |
| WorkbenchController | /api/v1/workbench | 1 |
| NotificationController | /api/v1/notifications | 5 |
| HandoverController | /api/v1/handovers | 8 |
| PostLaunchReviewController | /api/v1/post-launch-reviews | 2 |
| DeletionRequestController | /api/v1/deletion-requests | 8 |
| PublicPortalController | /api/v1/public | 3 |

### D. 智能体 / AI 文档 4 个
| Controller | basePath | 端点数 |
|---|---|---|
| AiCopilotController | /api/v1/ai-copilot | 1 |
| AiDocumentController | /api/v1/ai-documents | 9 |
| AiModelConfigController | /api/v1/ai-models | 4 |

### E. 治理 / 合规 / 配置 8 个
| Controller | basePath | 端点数 |
|---|---|---|
| AuditLogController | /api/v1/audit-logs | 5 |
| SwitchingAcceptanceController | /api/v1/switching-acceptance | 4 |
| SystemConfigController | /api/v1/system-configs | 4 |
| IpdMenuController | /api/v1/system/menu | 1 |
| ComplianceController | /api/v1/compliance | 4 |
| IpdReportController | /api/v1/report | 4 |
| PmDirectoryController | /api/v1 | 1 |
| IpdSseController | /api/v1/resource | 0（容器） |

## 端点统计
- 类级 @RequestMapping：51 个
- 方法级 HTTP 映射：~256 个
- 去重基础路径：46 个
- 唯一完整路由：约 220+ 个
