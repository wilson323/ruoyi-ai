# R215 孤儿定性登记表（终版 87 条，2026-09-24）

> **口径**：门禁 `check-api-contract-fe-be.mjs` R215 盲区修正后（96→87）；桶定义沿 R212 分桶。
> **本轮出清**：C-FP 门禁误报 9 条全平反（auth/me、auth/logout、getRouters、change-password、portal 3 条、system-configs 2 条）。
> **桶分布**：{'B': 27, 'C': 12, 'A': 46}（A47 中 2 条分桶时归 A 的 system-configs 裸版/as-of 已平反出清 → A 实存 46）

| # | 端点 | Controller | 桶 | 派单/归属 |
|---|---|---|---|---|
| 1 | `PUT /bid-invitations/{VAR}/admin-assign` | BidController | A |  |
| 2 | `PUT /bid-invitations/{VAR}/modify` | BidController | A |  |
| 3 | `GET /bid-responses/by-rd-pm/{VAR}` | BidController | A |  |
| 4 | `POST /bonus-pool/auto-compute` | BonusPoolController | A |  |
| 5 | `POST /bonus-pool/coefficient/preview` | BonusPoolController | A |  |
| 6 | `GET /bonus-pool/page` | BonusPoolController | A |  |
| 7 | `GET /gates/{VAR}/legacy` | GateElementResultController | A |  |
| 8 | `POST /gates/{VAR}/submit` | GateElementResultController | A |  |
| 9 | `GET /gates/{VAR}/observers` | GateReviewController | A |  |
| 10 | `POST /gates/{VAR}/observers/invite` | GateReviewController | A |  |
| 11 | `POST /gates/{VAR}/observers/{VAR}/opinion` | GateReviewController | A |  |
| 12 | `GET /handovers/monthly-attribution` | HandoverController | A |  |
| 13 | `POST /handovers/{VAR}/archive` | HandoverController | A |  |
| 14 | `GET /hr-sync/pending-handovers` | HrSyncController | A |  |
| 15 | `POST /auth/wecom/qr-login` | IpdAuthController | A |  |
| 16 | `DELETE /kpi/functional-metrics/{VAR}` | KpiFunctionalMetricsController | A |  |
| 17 | `GET /kpi/raw-records/types` | KpiRawRecordController | A |  |
| 18 | `POST /persons/{VAR}/resign` | PersonController | A |  |
| 19 | `POST /persons/{VAR}/wecom/unbind` | PersonController | A |  |
| 20 | `POST /post-launch-reviews` | PostLaunchReviewController | A |  |
| 21 | `GET /post-launch-reviews/pending` | PostLaunchReviewController | A |  |
| 22 | `POST /post-launch-reviews/{VAR}/complete` | PostLaunchReviewController | A |  |
| 23 | `POST /products/{VAR}/unbind-project` | ProductController | A |  |
| 24 | `POST /projects/legacy-import/batch` | ProjectController | A |  |
| 25 | `POST /projects/{VAR}/baselines` | ProjectController | A |  |
| 26 | `GET /projects/{VAR}/cert-items` | ProjectController | A |  |
| 27 | `POST /projects/{VAR}/cert-items/sync` | ProjectController | A |  |
| 28 | `POST /projects/{VAR}/cert-items/{VAR}/status` | ProjectController | A |  |
| 29 | `POST /projects/{VAR}/launch-date` | ProjectController | A |  |
| 30 | `POST /projects/{VAR}/members` | ProjectMemberController | A |  |
| 31 | `POST /receipt-ledgers` | ReceiptLedgerController | A |  |
| 32 | `GET /receipt-ledgers/by-project/{VAR}` | ReceiptLedgerController | A |  |
| 33 | `POST /receipt-ledgers/{VAR}/refunds` | ReceiptLedgerController | A |  |
| 34 | `GET /requirement-changes/open` | RequirementChangeController | A |  |
| 35 | `GET /kpi/shared/confirms` | SharedKpiController | A |  |
| 36 | `GET /kpi/shared/deadline-config` | SharedKpiController | A |  |
| 37 | `POST /kpi/shared/{VAR}/confirm` | SharedKpiController | A |  |
| 38 | `GET /sop-templates/instances` | SopTemplateController | A |  |
| 39 | `POST /sop-templates/{VAR}/instantiate` | SopTemplateController | A |  |
| 40 | `GET /switching-acceptance` | SwitchingAcceptanceController | A |  |
| 41 | `GET /switching-acceptance/{VAR}` | SwitchingAcceptanceController | A |  |
| 42 | `POST /switching-acceptance/{VAR}/lock` | SwitchingAcceptanceController | A |  |
| 43 | `POST /switching-acceptance/{VAR}/run` | SwitchingAcceptanceController | A |  |
| 44 | `POST /switching-acceptance/{VAR}/unlock` | SwitchingAcceptanceController | A |  |
| 45 | `GET /workbench/my-initiated` | WorkbenchController | A |  |
| 46 | `GET /workbench/my-pending-approvals` | WorkbenchController | A |  |
| 47 | `GET /admin/permanent-delete/audit` | AdminPermanentDeleteController | B | 运维登记(成本0) |
| 48 | `POST /admin/permanent-delete/{VAR}/{VAR}` | AdminPermanentDeleteController | B | 运维登记(成本0) |
| 49 | `GET /compliance/audit-trail/{VAR}/{VAR}` | ComplianceController | B | 运维登记(成本0) |
| 50 | `POST /compliance/data-deletion-request` | ComplianceController | B | 运维登记(成本0) |
| 51 | `GET /compliance/data-retention-rules` | ComplianceController | B | 运维登记(成本0) |
| 52 | `GET /compliance/permission-separation/{VAR}` | ComplianceController | B | 运维登记(成本0) |
| 53 | `POST /gates/sign/scan-remind` | GateSignScanController | B | 运维登记(成本0) |
| 54 | `POST /gates/sign/scan-timeout` | GateSignScanController | B | 运维登记(成本0) |
| 55 | `POST /handovers/scan-overdue` | HandoverController | B | 运维登记(成本0) |
| 56 | `POST /hr-sync/escalate-stale-resignations` | HrSyncController | B | 运维登记(成本0) |
| 57 | `GET /hr-sync/last-run` | HrSyncController | B | 运维登记(成本0) |
| 58 | `POST /hr-sync/mark-resigned` | HrSyncController | B | 运维登记(成本0) |
| 59 | `POST /hr-sync/sync-now` | HrSyncController | B | 运维登记(成本0) |
| 60 | `POST /hr-sync/sync-one` | HrSyncController | B | 运维登记(成本0) |
| 61 | `POST /gates/legacy/scan-overdue` | LegacyScanController | B | 运维登记(成本0) |
| 62 | `POST /notifications/async-dispatch` | NotificationController | B | 运维登记(成本0) |
| 63 | `POST /notifications/dispatch-pending` | NotificationController | B | 运维登记(成本0) |
| 64 | `POST /p0/escalation-chain/check` | P0EscalationController | B | 运维登记(成本0) |
| 65 | `POST /person-sync/jobs` | PersonSyncController | B | 运维登记(成本0) |
| 66 | `GET /person-sync/jobs/abnormal` | PersonSyncController | B | 运维登记(成本0) |
| 67 | `POST /person-sync/jobs/retry-all` | PersonSyncController | B | 运维登记(成本0) |
| 68 | `POST /person-sync/jobs/{VAR}/retry` | PersonSyncController | B | 运维登记(成本0) |
| 69 | `POST /product-groups/{VAR}/remove` | ProductGroupController | B | 运维登记(成本0) |
| 70 | `POST /project-score-tasks/scan` | ProjectScoreTaskController | B | 运维登记(成本0) |
| 71 | `POST /kpi/shared/deadline-scan` | SharedKpiController | B | 运维登记(成本0) |
| 72 | `POST /stage-actions/ensure-bio-compliance` | StageActionController | B | 运维登记(成本0) |
| 73 | `POST /stage-actions/instantiate` | StageActionController | B | 运维登记(成本0) |
| 74 | `POST /ai-copilot/chat` | AiCopilotController | C | 存疑/owner裁决池 |
| 75 | `GET /ai-documents/{VAR}/history` | AiDocumentController | C | 存疑/owner裁决池 |
| 76 | `GET /audit-logs` | AuditLogController | C | 存疑/owner裁决池 |
| 77 | `GET /audit-logs/export` | AuditLogController | C | 存疑/owner裁决池 |
| 78 | `POST /bid-invitations/p231-create` | BidP231Controller | C | 存疑/owner裁决池 |
| 79 | `GET /kpi/rules` | KpiRulesController | C | 存疑/owner裁决池 |
| 80 | `GET /negative-feedbacks/by-project/{VAR}` | NegativeFeedbackController | C | 存疑/owner裁决池 |
| 81 | `GET /negative-feedbacks/by-severity/{VAR}` | NegativeFeedbackController | C | 存疑/owner裁决池 |
| 82 | `PUT /negative-feedbacks/{VAR}/status` | NegativeFeedbackController | C | 存疑/owner裁决池 |
| 83 | `GET /p0/escalation-chain` | P0EscalationController | C | 存疑/owner裁决池 |
| 84 | `POST /p0/escalation-chain/{VAR}/resolve` | P0EscalationController | C | 存疑/owner裁决池 |
| 85 | `POST /persons/{VAR}/rehire` | PersonController | C | 存疑/owner裁决池 |
| 86 | `GET /ai-copilot` | AiCopilotController | ? | 存疑/owner裁决池 |
| 87 | `GET /resource` | IpdSseController | ? | 存疑/owner裁决池 |

## 附：2 条分桶表未匹配条目的归属裁定（R215 现查）

| 端点 | Controller | 裁定 | 依据 |
|---|---|---|---|
| `GET /ai-copilot` | AiCopilotController | **C 存疑** | 与 POST /ai-copilot/chat 同域（分桶 #02 已判 C，R183 三卡在建未合流）；GET 版为 copilot 状态/配置读取，同样待 R183 合流裁决 |
| `GET /resource` | IpdSseController | **B 运维登记** | SSE 资源探活端点（类名 IpdSse 表明是 SSE 通道配套），无产品页对应，运维/网关健康检查用 |

## 结论

- **87 = A46（13 卡派单 + 3 挂靠，全部 todo 待执行）+ B27（运维专用登记，成本 0）+ C12（存疑/owner 裁决池）+ 附 2 条已裁定**
- owner 裁决池遗留 5 项（沿 R212 分桶附录）：permanent-delete/audit、persons/{id}/rehire、P0 升级链 list/resolve、负反馈 3 变体、ai-copilot/chat——集中进 R215 拍板包（WP4）
- 本表与 `contract-verify-final.json`（门禁 R215 修正版）绑定；C-FP 9 条平反明细见 R215-P1~DONE 卡 `9c2a8abc` 描述
