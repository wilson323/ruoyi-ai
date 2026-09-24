# R212 孤儿端点证据化分桶表（96 条 · 全程只读取证 · 生成 2026-09-24）

> 口径：/tmp/r212_l1_table.md 96 行为准。contract.json 98 条与其差 2 条（`GET /api/v1/ai-copilot`、`GET /api/v1/resource`，路径归一后 96⊂98），本表不覆盖该 2 条。

> 桶定义：A=真缺口(该接没接)；B=内部/运维专用(不该接，含 owner 裁决预留)；C=存疑/疑似废弃（含 C-FP=门禁误报，前端实际已接线，如实单列不硬分）。


| # | 方法+路径 | Controller | 桶 | 一句话理由 | 关键证据 |
|---|---|---|---|---|---|
| 00 | GET /api/v1/admin/permanent-delete/audit | AdminPermanentDeleteController | B | 超管审计对账端点（javadoc自称"前端对账视图用"但原型无页；存疑见附录）| AdminPermanentDeleteController.java:72-80 "仅超管；前端对账视图用"+requireAdmin() |
| 01 | POST /api/v1/admin/permanent-delete/{entityType}/{id} | AdminPermanentDeleteController | B | 超管运维永久清除：白名单实体+confirmCode，原型无对应消费页 | AdminPermanentDeleteController.java:56-60 @SaCheckPermission(OPERATION_PERMANENT_DELETE)+requireAdmin();ZK-IPD spec grep "永久删除" 0命中 |
| 02 | POST /api/v1/ai-copilot/chat | AiCopilotController | C | IPD自建copilot无产品页对应；前端AI助理走平台通道；R183系todo卡未合流 | AiCopilotController.java:65-70;ZK-IPD spec grep "副驾|copilot" 0命中;看板 a1af61b7[R183-S2 todo] |
| 03 | GET /api/v1/ai-documents/{id}/history | AiDocumentController | C | 与已接线 /versions 同源冗余变体 | AiDocumentController.java:115-120 javadoc"与 /versions 同源；独立 URL 便于前端按场景切换";前端已接 /{id}/versions |
| 04 | GET /api/v1/audit-logs | AuditLogController | C | 旧接口保留（javadoc自证），/scope 版已接线 | AuditLogController.java:60-66 "保留原接口"+requireAdmin();前端 audit.ts 接 /audit-logs/scope(audit.test.ts:41 实证) |
| 05 | GET /api/v1/audit-logs/export | AuditLogController | C | 方法体为 stub：只落审计事件不出文件 | AuditLogController.java:98-106 "// 简化：未来接 CSV/PDF 流；当前落审计事件" return {"exported":"queued"};看板 a4657cec[SEC-04 inprogress]承接导出真实化 |
| 06 | GET /api/v1/bid-responses/by-rd-pm/{rdPmId} | BidController | A | 后端完整+前端零命中；应标查询属招标组队域 | BidController.java GET /bid-responses/by-rd-pm/{rdPmId} perm=const-ref;前端 grep "by-rd-pm" 0命中;原型页19招标域(看板 P2-3 汇总卡 todo 7ad0b052) |
| 07 | PUT /api/v1/bid-invitations/{id}/admin-assign | BidController | A | admin-assign 后端完整，前端仅有权限码 reserved 声明无调用 | BidController.java PUT /bid-invitations/{id}/admin-assign;前端 ipd-permission-codes.ts:125 声明 BID_INVITATION_ADMIN_ASSIGN 但无 api 调用;看板 a2eb3b5e[SEC-REV-BID done 后端加固] |
| 08 | PUT /api/v1/bid-invitations/{id}/modify | BidController | A | 改标端点后端完整前端零调用 | BidController.java PUT /bid-invitations/{id}/modify;前端 grep "bid-invitations.*modify" 0命中(bid.ts 仅 list/get/create 等) |
| 09 | POST /api/v1/bid-invitations/p231-create | BidP231Controller | C | OPS-09 单写者约束的平行迁移入口，非产品功能 | BidP231Controller.java 类注释:因 OPS-09 单写者约束独立建 controller，与已接线 POST /bid-invitations 平行 |
| 10 | GET /api/v1/bonus-pool/page | BonusPoolController | A | 奖金池核算页数据源未接 | BonusPoolController.java GET /bonus-pool/page perm=const-ref;前端 grep "bonus-pool" 0命中;原型页34奖金池核算(_导航地图.md:277);看板 f7a1cfb6[P0-10.34 前端页34 cancelled] |
| 11 | POST /api/v1/bonus-pool/auto-compute | BonusPoolController | A | auto-compute 核算触发属页34操作按钮 | BonusPoolController.java POST /bonus-pool/auto-compute;原型页34核算流程(batch-03 页34节) |
| 12 | POST /api/v1/bonus-pool/coefficient/preview | BonusPoolController | A | 系数预览属核算前交互 | BonusPoolController.java GET /bonus-pool/coefficient/preview;看板 a5d5e395[P3-4.5 done 系数分档后端] |
| 13 | GET /api/v1/compliance/audit-trail/{resourceType}/{resourceId} | ComplianceController | B | owner裁决B预留能力：保留不建前端不删除 | 看板 d81af12c[owner裁决 done 2026-09-07]:"归属判定：B 预留能力登记…A排除(49页无合规页)";AC-COMP 在 ZK-IPD spec 0命中(裁决卡自证"自发自收") |
| 14 | GET /api/v1/compliance/data-retention-rules | ComplianceController | B | 同上 owner 裁决 | 看板 d81af12c 裁决覆盖 retention-rules/data-deletion-request/audit-trail/permission-separation 4端点 |
| 15 | GET /api/v1/compliance/permission-separation/{userId} | ComplianceController | B | 同上 owner 裁决 | 看板 d81af12c |
| 16 | POST /api/v1/compliance/data-deletion-request | ComplianceController | B | 同上 owner 裁决 | 看板 d81af12c |
| 17 | GET /api/v1/gates/{gateId}/legacy | GateElementResultController | A | legacy评审视图前端未接 | GateElementResultController.java GET /gates/{gateId}/legacy;前端 grep gates.*legacy 命中均为 project.ts legacyEffectiveAt 噪音,无调用 |
| 18 | POST /api/v1/gates/{gateId}/submit | GateElementResultController | A | Gate提交属评审工作台核心动作，后端完整前端零调用 | GateElementResultController.java POST /gates/{gateId}/submit;前端 grep submit 命中均 change/deletion 域噪音;看板 927fa743[P2-5.1 done Gate提交后端] |
| 19 | GET /api/v1/gates/{gateId}/observers | GateReviewController | A | 列席人查看：原型页24「邀约列席」组件有对应，前端仅在视图数据里渲染 observers 未调端点 | GateReviewController.java GET /gates/{gateId}/observers;_导航地图.md:266 页24邀约列席;前端 gate-detail/index.vue:367 只渲染 view.observers 字段;看板 a5bb3e68[MEDIUM-1.3 done 后端] |
| 20 | POST /api/v1/gates/{gateId}/observers/invite | GateReviewController | A | 邀约列席动作未接 | GateReviewController.java POST /gates/{gateId}/observers/invite;原型页24 |
| 21 | POST /api/v1/gates/{gateId}/observers/{observerId}/opinion | GateReviewController | A | 列席意见提交未接 | GateReviewController.java POST /gates/{gateId}/observers/{observerId}/opinion;原型页24 |
| 22 | POST /api/v1/gates/sign/scan-remind | GateSignScanController | B | 签到扫描手动触发（scheduler配套运维入口） | GateSignScanController scan-remind;模式同 HandoverService.java:704 注释"调度cron每日扫+端点SUPER_ADMIN手动" |
| 23 | POST /api/v1/gates/sign/scan-timeout | GateSignScanController | B | 签到超时扫描手动触发 | GateSignScanController scan-timeout |
| 24 | GET /api/v1/handovers/monthly-attribution | HandoverController | A | 月度归因属移交域结算配套，后端完整未接 | HandoverController.java POST /handovers/monthly-attribution;前端 grep "monthly-attribution" 0命中(handover.ts 头注端点清单不含) |
| 25 | POST /api/v1/handovers/scan-overdue | HandoverController | B | 超期扫描：@Scheduled 已自动跑 | HandoverOverdueScanner.java:56 @Scheduled(cron="0 5 9 * * ?")直调service不经HTTP |
| 26 | POST /api/v1/handovers/{id}/archive | HandoverController | A | 移交归档动作未接 | HandoverController.java POST /handovers/{id}/archive;前端 grep handovers.*archive 0命中 |
| 27 | GET /api/v1/hr-sync/last-run | HrSyncController | B | last-run 状态查询为运维排障端点 | HrSyncController.java GET /hr-sync/last-run;HR同步链路 HrSyncJob.java:51 @Scheduled(cron ipd.hr.sync.cron) |
| 28 | GET /api/v1/hr-sync/pending-handovers | HrSyncController | A | 离职待移交清单：原型页27有产品对应 | HrSyncController.java GET /hr-sync/pending-handovers;_导航地图.md 页27项目移交+batch-03:401 BR-USER-06 15日逾期升级;前端 grep "person-sync|hr-sync" 0命中 |
| 29 | POST /api/v1/hr-sync/escalate-stale-resignations | HrSyncController | B | 逾期升级扫描由@Scheduled自动 | PersonResignEscalator.java:35 @Scheduled(cron="0 0 9 * * ?")直调 hrSyncService.escalateStaleResignations |
| 30 | POST /api/v1/hr-sync/mark-resigned | HrSyncController | B | HR真源标记离职：数据同步链运维动作 | HrSyncController.java mark-resigned;HrSyncJob 链路;无前端页对应(离职操作产品入口=页27/49另一组端点) |
| 31 | POST /api/v1/hr-sync/sync-now | HrSyncController | B | 手动全量同步：cron 的手动兜底 | HrSyncController.java sync-now;HrSyncJob.java:51-53 cron→runOnce("CRON_DAILY")同逻辑手动入口 |
| 32 | POST /api/v1/hr-sync/sync-one | HrSyncController | B | 单人同步：运维回补 | HrSyncController.java sync-one;看板 bee81fc9[P2-2.3 done 异常项回补] |
| 33 | GET /api/v1/auth/me | IpdAuthController | C-FP | 前端实际已接线 | store/ipd-auth.ts:245 authenticatedRequest('/auth/me');api/ipd/auth.ts:138 契约声明;看板 70c3a904[FE-P0 done /auth/me 接线根修] |
| 34 | POST /api/v1/auth/change-password | IpdAuthController | C-FP | 前端实际已接线 | store/ipd-auth.ts:318 + router/routes/core.ts:73 change-password.vue 页面在路由 |
| 35 | POST /api/v1/auth/logout | IpdAuthController | C-FP | 前端实际已接线 | store/ipd-auth.ts:349 authenticatedRequest('/auth/logout') |
| 36 | POST /api/v1/auth/wecom/qr-login | IpdAuthController | A | 企微扫码登录：前端占位文案未开放，后端已交付 | IpdAuthController.java POST /auth/wecom/qr-login;前端 login.vue:178 占位"企业微信登录暂未开放";grep "qr-login" 0命中;看板 18851855[P0-7.4 done 后端Mock绑定扫码] |
| 37 | DELETE /api/v1/kpi/functional-metrics/{id} | KpiFunctionalMetricsController | A | 功能指标删除：前端仅 GET/PUT 封装无 DELETE | KpiFunctionalMetricsController.java DELETE /kpi/functional-metrics/{id};前端 kpi.ts:190-210 仅 list/get/put 三封装;views/kpi/functional/index.vue 只调 listFunctionalMetrics |
| 38 | GET /api/v1/kpi/functional-metrics | KpiFunctionalMetricsController | C-FP | 前端实际已接线 | api/ipd/kpi.ts:196 ipdGet('/kpi/functional-metrics') ← views/ipd/kpi/functional/index.vue:210 调用 |
| 39 | GET /api/v1/kpi/functional-metrics/codes | KpiFunctionalMetricsController | A | codes 封装函数已写但从未被页面 import（前端用硬编码8项枚举） | api/ipd/kpi.ts:200-202 listFunctionalMetricCodes 全仓无调用方(grep 排除test 0命中);kpi.ts:19 硬编码枚举 |
| 40 | GET /api/v1/kpi/raw-records/types | KpiRawRecordController | A | 原始记录类型权威枚举未接（页面硬编码） | KpiRawRecordController.java GET /kpi/raw-records/types;前端 grep "raw-records/types" 0命中;kpi.ts:19 硬编码 |
| 41 | GET /api/v1/kpi/rules | KpiRulesController | C | 无任何产品页/前端注释对应 | KpiRulesController.java GET /kpi/rules;ZK-IPD spec grep "考核规则" 无对应页;前端 grep "kpi/rules" 0命中 |
| 42 | POST /api/v1/gates/legacy/scan-overdue | LegacyScanController | B | legacy Gate 超期扫描手动触发 | LegacyScanController.java scan-overdue;同扫描系模式 |
| 43 | GET /api/v1/system/menu/getRouters | MenuController | C-FP | 前端实际已接线 | api/core/menu.ts:51 ipdGet('/system/menu/getRouters');router/access.ts:289 注释确认迁至本端点 |
| 44 | GET /api/v1/negative-feedbacks/by-project/{projectId} | NegativeFeedbackController | C | by-project 全量版已被 /effective 变体取代接线 | api/ipd/negative-feedback.ts:143 前端调 /by-project/{id}/effective;裸版0调用;看板 30d13ae9[P0-10.36 前端页36 cancelled] |
| 45 | GET /api/v1/negative-feedbacks/by-severity/{severity} | NegativeFeedbackController | C | by-severity 无产品对应 | NegativeFeedbackController.java;spec 页36 无按严重度视图;前端0命中 |
| 46 | PUT /api/v1/negative-feedbacks/{id}/status | NegativeFeedbackController | C | 状态直改变体：前端0调用且改状态应走执行流 | NegativeFeedbackController.java PUT /{id}/status;前端 grep 命中均 status 泛词噪音 |
| 47 | POST /api/v1/notifications/async-dispatch | NotificationController | B | 异步派发：@Scheduled outbox scanner 自动 | NotificationOutboxScanner.java:61 @Scheduled(fixedDelay 30s)→dispatcher.consumeOnce;端点为手动补跑 |
| 48 | POST /api/v1/notifications/dispatch-pending | NotificationController | B | pending 派发同上 | NotificationController.java dispatch-pending |
| 49 | GET /api/v1/p0/escalation-chain | P0EscalationController | C | 升级链列表：AC-C4 内部引擎配套，无原型页 | P0EscalationChain.java:19-33 "R149 batch2b C4…AC-C4";ZK-IPD spec grep "升级链|escalation" 0命中;前端 /p0/ 0命中 |
| 50 | POST /api/v1/p0/escalation-chain/check | P0EscalationController | B | 手动扫描触发：注释自证运维/测试用 | P0EscalationController.java:54-58 "手动触发扫描（仅超管；运维/测试用；正常轮询待 scheduler 合入）" |
| 51 | POST /api/v1/p0/escalation-chain/{id}/resolve | P0EscalationController | C | resolve 处置关闭：随 list 同域无产品页对应 | P0EscalationController.java:65-69;spec 0命中(与49同判,处置入口需 owner 确认是否并入通知流) |
| 52 | POST /api/v1/persons/{id}/rehire | PersonController | C | 复职：ZK-IPD 全库0命中"复职"，归属存疑 | PersonController.java POST /{id}/rehire;grep -rn "复职" /Users/mac/Documents/ZK-IPD 0命中;但看板 6922cd48[P2-1.3 done]按内部规划卡交付→建议 owner 裁决而非直接建前端 |
| 53 | POST /api/v1/persons/{id}/resign | PersonController | A | 离职冻结：原型页27/49+附录D6 明确产品动作 | PersonController.java POST /{id}/resign;附录-D6:64"账户失效…解绑企微";batch-04:556"全部项目移交完→DISABLED";前端 grep "persons/.*resign" 0命中 |
| 54 | POST /api/v1/persons/{id}/wecom/unbind | PersonController | A | 企微解绑：spec 明文产品动作未接 | PersonController.java POST /{id}/wecom/unbind;batch-04:571 "Then accountStatus=DISABLED；企微解绑";前端0命中 |
| 55 | GET /api/v1/person-sync/jobs/abnormal | PersonSyncController | B | 异常项回查：系统间回调/运维 | PersonSyncController.java jobs/abnormal;看板 9a38b8d8[PERSON-SYNC-P0 done 同步链路]+bee81fc9[P2-2.3 异常项回补 done] |
| 56 | POST /api/v1/person-sync/jobs | PersonSyncController | B | 建同步任务：HR系统回调入口 | PersonSyncController.java POST /person-sync/jobs |
| 57 | POST /api/v1/person-sync/jobs/retry-all | PersonSyncController | B | 全量重试运维 | PersonSyncController.java jobs/retry-all |
| 58 | POST /api/v1/person-sync/jobs/{id}/retry | PersonSyncController | B | 单任务重试运维 | PersonSyncController.java jobs/{id}/retry;前端 grep person-sync 全仓0命中 |
| 59 | GET /api/v1/post-launch-reviews/pending | PostLaunchReviewController | A | 待复盘清单：BR-KPI-08 30/90日流程有产品对应 | PostLaunchReviewController.java GET /post-launch-reviews/pending;batch-03:972 "BR-KPI-08 上市后30日提醒与90日升级";前端 grep "post-launch" 0命中 |
| 60 | POST /api/v1/post-launch-reviews | PostLaunchReviewController | A | 复盘列表/创建未接 | PostLaunchReviewController.java |
| 61 | POST /api/v1/post-launch-reviews/{id}/complete | PostLaunchReviewController | A | 复盘完成动作未接 | PostLaunchReviewController.java /{id}/complete;看板 fab011b1[P2-5.6 done 后端待办重排] |
| 62 | POST /api/v1/products/{id}/unbind-project | ProductController | A | 解绑与已接线的 bind-project 成对，单边缺接 | ProductController.java:99-100 "P1-1.1：解绑项目（产品↔项目双向1:1）";前端 product.ts:140 已接 bind-project 而 unbind 0命中;看板 c2fa59a2[W28-2 P1-1.1收口 done] |
| 63 | POST /api/v1/product-groups/{id}/remove | ProductGroupController | B | 删除入口被产品规则主动关闭 | ProductGroupController.java:71-73 注释"删除入口已关闭：须走删除审核（P0-6.2）" |
| 64 | GET /api/v1/projects/{id}/cert-items | ProjectController | A | 认证项清单：batch-01 明示缺失接口 | ProjectController.java GET /{id}/cert-items;batch-01:465 "缺失接口(4项):/api/projects/:id /members /cert-checklist /stage-advance";前端 cert-items 0命中;看板 12252bc2[P0-10.18 前端页18 cancelled] |
| 65 | POST /api/v1/projects/legacy-import/batch | ProjectController | A | 存量导入批量入口未接（页09） | ProjectController.java POST /projects/legacy-import/batch;前端 grep "legacy-import" 仅 project.ts 类型噪音无调用;看板 f299ac3a[P1-9.1 done 后端] 91bf9ff3[前端页09 cancelled] |
| 66 | POST /api/v1/projects/{id}/baselines | ProjectController | A | 基线创建未接 | ProjectController.java POST /{id}/baselines;前端 grep "projects/.*baselines" 0命中 |
| 67 | POST /api/v1/projects/{id}/cert-items/sync | ProjectController | A | 认证项同步：与 cert-items 同组 | ProjectController.java POST /{id}/cert-items/sync;看板 0aac25e1[R8-P0-6 done 后端sync实现] |
| 68 | POST /api/v1/projects/{id}/cert-items/{itemId}/status | ProjectController | A | 认证项状态流转：同组 | ProjectController.java PUT /{id}/cert-items/{itemId}/status;看板 0b4bc1fc[R8-P0-9 done] |
| 69 | POST /api/v1/projects/{id}/launch-date | ProjectController | A | 上市时间设定未接 | ProjectController.java /{id}/launch-date;前端 grep 命中均 launchDate 字段噪音无端点调用;看板 12f918a8[LaunchDateChange后端链 done] |
| 70 | POST /api/v1/projects/{projectId}/members | ProjectMemberController | A | 成员管理：batch-01 明示缺失 /members | ProjectMemberController.java GET /projects/{projectId}/members;batch-01:465 同#64证据;看板 284eda20[P2-4.1 done 后端绑定快照] |
| 71 | POST /api/v1/project-score-tasks/scan | ProjectScoreTaskController | B | 评分任务扫描由@Scheduled自动 | ProjectScoreScheduleService.java:160 @Scheduled(cron="0 15 9 * * ?");端点为手动补扫 |
| 72 | GET /api/v1/public/demands/{code} | PublicPortalController | C-FP | 前端实际已接线（URL运行时拼接致漏检） | api/ipd/portal.ts:101 fetch(`/api/v1/public${path}`) + :218;views/ipd/portal/status/index.vue:74 fetchPortalDemandByCode 调用 |
| 73 | GET /api/v1/public/products | PublicPortalController | C-FP | 同上 | portal.ts:217 fetchPortalProducts ← views/ipd/portal/submit/index.vue:96 调用 |
| 74 | POST /api/v1/public/demands | PublicPortalController | C-FP | 同上 | portal.ts:222 submitPortalDemand ← submit/index.vue:129 调用;原型页38/39需求门户在盘 |
| 75 | GET /api/v1/receipt-ledgers/by-project/{projectId} | ReceiptLedgerController | A | 回款台账按项目查询：AC-INC-16c 产品对应 | ReceiptLedgerController.java GET /by-project;验收清单 AC-INC-16c"回款台账录入(LC01)可月度录入+上传凭证";前端 grep "receipt" 0命中;看板 5510f9fa[P3-4.1 done 后端台账] |
| 76 | POST /api/v1/receipt-ledgers | ReceiptLedgerController | A | 台账录入未接 | ReceiptLedgerController.java POST /receipt-ledgers |
| 77 | POST /api/v1/receipt-ledgers/{projectId}/refunds | ReceiptLedgerController | A | 退款窗口查询未接 | ReceiptLedgerController.java GET /{projectId}/refunds |
| 78 | GET /api/v1/requirement-changes/open | RequirementChangeController | A | open 列表：前端页面注释已宣告契约为 Tab3 数据源但函数未实现 | RequirementChangeController.java GET /requirement-changes/open;前端 change.ts:11 契约注释含/open 但无封装;views/project/detail/changes.vue:20 "Tab3接完整工作流…GET /requirement-changes/open";看板 aacc6e2c/296d3b2d[页25/26前端卡 cancelled] |
| 79 | GET /api/v1/kpi/shared/confirms | SharedKpiController | A | 共担确认列表：原型页30 | SharedKpiController.java GET /kpi/shared/confirms;_导航地图.md 页30共担KPI;看板 56d97bb0[P3-1.2-BACKEND done 读端点] 5818452d[前端页30 cancelled];前端 grep "shared/confirms" 0命中 |
| 80 | GET /api/v1/kpi/shared/deadline-config | SharedKpiController | A | 截止时间配置未接 | SharedKpiController.java GET/PUT deadline-config;batch-03:794 reminderLevel/截止参数(kpi.monthlyDeadlineDay) |
| 81 | POST /api/v1/kpi/shared/deadline-scan | SharedKpiController | B | 截止扫描由cron自动 | batch-03:822 D.6.6 "cron 任务 kpi_monthly_collect_cron 服务端执行";端点为手动补扫 |
| 82 | POST /api/v1/kpi/shared/{id}/confirm | SharedKpiController | A | 确认动作未接 | SharedKpiController.java POST /{id}/confirm;batch-03:817 "③审核 产品组长复核…截止次月第5工作日" |
| 83 | GET /api/v1/sop-templates/instances | SopTemplateController | A | 模板实例列表未接 | SopTemplateController.java GET /sop-templates/instances;前端 grep 0命中;看板 454673b0[P1-3.3 done 实例版本快照后端] |
| 84 | POST /api/v1/sop-templates/{templateId}/instantiate | SopTemplateController | A | 模板手动实例化入口未接（页46 SOP模板管理动作） | SopTemplateController.java POST /{templateId}/instantiate;_导航地图.md 页46 SOP模板;区别于 stage-actions/instantiate(自动联动,B) |
| 85 | POST /api/v1/stage-actions/ensure-bio-compliance | StageActionController | B | 生物特征合规自动挂载：C12 服务端自动 | StageActionController.java ensure-bio-compliance;验收清单 C12"生物特征合规自动挂载";前端0命中 |
| 86 | POST /api/v1/stage-actions/instantiate | StageActionController | B | 阶段动作实例化由建项目自动生成，非前端手动 | batch-01:532 "自动生成六阶段+69动作instances";看板 0288ffad[P1-3.1 done 服务端生成] |
| 87 | GET /api/v1/switching-acceptance | SwitchingAcceptanceController | A | owner裁决归P3-7.1激励联测消化：AC-INC-50/51 产品对应存在 | 看板 d81af12c 裁决注记:"SwitchingAcceptanceController 归属明确为 P3-7.1(AC-INC-50/51)，零消费待 P3 激励联测自然消化，不另建卡";fef12922[P3-7.1 done] |
| 88 | GET /api/v1/switching-acceptance/{month} | SwitchingAcceptanceController | A | 同上(P3-7.1并案) | 前端 ipd-permission-codes.ts:107-108 "SWITCHING_ACCEPTANCE_* reserved(A23):无对应视图" |
| 89 | POST /api/v1/switching-acceptance/{month}/lock | SwitchingAcceptanceController | A | 同上 | SwitchingAcceptanceController.java /{month}/lock |
| 90 | POST /api/v1/switching-acceptance/{month}/run | SwitchingAcceptanceController | A | 同上 | SwitchingAcceptanceController.java /{month}/run |
| 91 | POST /api/v1/switching-acceptance/{month}/unlock | SwitchingAcceptanceController | A | 同上 | SwitchingAcceptanceController.java /{month}/unlock |
| 92 | GET /api/v1/system-configs/{key:.+} | SystemConfigController | C | 单键即时读：前端封装已写但零页面调用，与已接 list 变体冗余 | SystemConfigController.java:66-68;前端 system-config.ts:114 getSystemConfig 定义但全仓无 import;listSystemConfigs 已接(admin/config/index.vue) |
| 93 | GET /api/v1/system-configs/{key:.+}/as-of | SystemConfigController | C-FP | 前端实际已接线 | api/ipd/system-config.ts:155 + views/ipd/admin/config/index.vue:270 resolveSystemConfigAsOf 调用;看板 775b8c3e[P0-3.3 done 时点解析] |
| 94 | GET /api/v1/workbench/my-initiated | WorkbenchController | A | 我发起的流程：原型页04 | WorkbenchController.java GET /workbench/my-initiated;_导航地图.md 页04我的申请;前端 grep "my-initiated" 0命中;看板 ebeaa66d[P0-10.4 cancelled] |
| 95 | GET /api/v1/workbench/my-pending-approvals | WorkbenchController | A | 待我审批：原型页03/05 | WorkbenchController.java GET /my-pending-approvals;_导航地图.md 页03工作台;看板 180f74ff[P4-3.1 done 聚合后端] 50dcbadf[前端页03 cancelled] |

## 汇总
- A=47 B=27 C=13（其中 C-FP 门禁误报 9）合计=96

## A 类域分组与派单粒度建议（A=47）

| 域 | 条数 | 端点# | 派单粒度建议 |
|---|---|---|---|
| Gate评审（提交/legacy/列席） | 5 | 17-21 | 1张卡（页24评审工作台前端补消费，列席3条一体） |
| 项目配置（认证项/基线/上市时间/成员/存量导入） | 7 | 64-70 | 2张卡（认证项4条=1卡；基线+launch-date+成员+存量导入=1卡） |
| 切换验收 | 5 | 87-91 | **0张新卡**（owner裁决 d81af12c 明令并入 P3-7.1 联测消化） |
| bid组队（应标查询/admin-assign/改标） | 3 | 6-8 | 0张新卡（挂既有 todo 卡 [P2-3] 7ad0b052 追加验收项） |
| 奖金池 | 3 | 10-12 | 1张卡（页34前端复刻） |
| 回款台账 | 3 | 75-77 | 1张卡（AC-INC-16b/c 台账前端） |
| KPI配置（功能指标DELETE/codes+原始记录types） | 3 | 37,39,40 | 1张卡（量表页补删除+权威枚举替换硬编码） |
| KPI共担（confirms/deadline-config/confirm） | 3 | 79,80,82 | 1张卡（页30共担归集前端） |
| 移交（archive/月度归因/离职待移交） | 3 | 24,26,28 | 1张卡（页27移交增强） |
| 上市复盘 | 3 | 59-61 | 1张卡（BR-KPI-08 复盘页） |
| 工作台（my-initiated/my-pending-approvals） | 2 | 94,95 | 1张卡（页03/04聚合补消费） |
| 人员（resign/wecom-unbind） | 2 | 53,54 | 1张卡（离职冻结-解绑联动前端入口，与移交卡相邻可并单） |
| SOP（instances/instantiate） | 2 | 83,84 | 0张新卡（挂既有 todo 卡 [P1-3] 53ecc543） |
| 登录（wecom qr-login） | 1 | 36 | 1张卡（页01扫码入口） |
| 需求变更（open列表） | 1 | 78 | 1张卡（changes.vue Tab3 契约兑现） |
| 产品解绑（unbind-project） | 1 | 62 | 并入「人员/移交」或独立微卡（bind已接、单边缺） |
| **合计** | **47** | | 建议新卡 13 张 + 挂既有 todo 卡 2 张 + owner已裁决不建卡 5 条 |

## 看板对账（A 类各域 · 只查不建，project 01dcf15c，477 卡）

| 域 | 看板命中 | 结论 |
|---|---|---|
| bid组队 | 7ad0b052 [P2-3 汇总]招标组队 **todo**；a2eb3b5e [SEC-REV-BID] done；4b82d0a1 [P0-10.19 前端页19] cancelled | 有活卡（todo），无需新建 |
| Gate评审 | 4912a562 [P1-6 汇总] todo；745b0141 [P1-6.1] inreview；a5bb3e68 [MEDIUM-1.3 列席] done；927fa743 [P2-5.1] done | 有 todo/inreview 卡可挂靠；纯前端消费卡 **需建** |
| 移交 | 移交系卡全 done（SEC-REV-HANDOVER-01/02 等），无 open 前端消费卡 | **需建卡** |
| 人员 | 6922cd48 [P2-1.3] done；18851855 [P0-7.4] done（均后端交付） | 前端消费 **需建卡** |
| 奖金池 | 5d00a4b0 [P3-4 汇总] done；f7a1cfb6 [P0-10.34 前端页34] **cancelled** | 原页卡已撤 → **需建卡** |
| 回款台账 | 5510f9fa [P3-4.1] done；deb327b1 [P0-10.37] cancelled | **需建卡** |
| KPI配置/共担 | 56d97bb0 [P3-1.2-BACKEND] done；5818452d [P0-10.30 前端页30] cancelled | **需建卡** |
| 项目配置 | f299ac3a [P1-9.1 存量导入] done；284eda20 [P2-4.1 成员] done；12252bc2 [P0-10.18] cancelled；91bf9ff3 [P0-10.9] cancelled | **需建卡** |
| 上市复盘 | fab011b1 [P2-5.6] done；e39bf6e7 [P3-2.3] done（后端/待办向） | 前端复盘页 **需建卡** |
| SOP | 53ecc543 [P1-3 汇总]69动作Seed **todo**；e24970c1 [P0-10.46] cancelled | 挂 todo 卡 |
| 登录 | 18851855 [P0-7.4] done | 前端扫码入口 **需建卡** |
| 工作台 | 94a0c673 [P4-3] done；180f74ff [P4-3.1] done；50dcbadf [P0-10.3] cancelled；e30c86a2 [WB-17-1] inprogress | 挂 WB-17-1 或 **需建卡** |
| 切换验收 | d81af12c owner裁决：不另建卡；fef12922 [P3-7.1] done | **不建卡**（按裁决） |
| 需求变更 | 1b1e4fdf [P2-6] done；aacc6e2c/296d3b2d [页25/26] cancelled | **需建卡** |
| 产品解绑 | c2fa59a2 [W28-2 P1-1.1收口] done；e11427e6 [bind/unbind守卫] done | 后端收口卡全done → 前端微卡 **需建** |
| 合规中心(参照) | d81af12c owner裁决 done=B预留 | 不建卡 |
| 负反馈(参照C) | 98db7e00 [P3-8 汇总] **todo**；30d13ae9 [P0-10.36] cancelled | C判定与todo汇总卡存在张力，见「无法定性」 |

## 无法定性/需 owner 裁决条目（如实列，未硬分）
1. **#00 permanent-delete/audit**：javadoc 自称「前端对账视图用」（暗示应有前端消费），但仅超管权限且 49 页无对应 → 暂 B，待 owner 仿照 d81af12c 格式裁决。
2. **#52 persons/{id}/rehire 复职**：ZK-IPD 全库 0 命中「复职」，但看板 P2-1.3/P2-2.2 两张规划卡按内部需求已交付 → 暂 C，建议 owner 裁决产品归属。
3. **#49/#51 P0升级链 list/resolve**：AC-C4（R149 内部决策）配套端点，ZK-IPD spec 0 命中 → 暂 C；若 owner 认可升级处置需 UI，则转 A 并挂 R183/P3 系。
4. **#44-46 负反馈 3 变体**：分桶 C（变体冗余/无对应），但看板存在 todo 汇总卡 [P3-8] 98db7e00——若该卡落地时选用这 3 个裸端点而非已接变体，应回补为 A。
5. **#02 ai-copilot/chat**：spec 无对应（C），但 R183-S1/S2/S3 三张 todo 卡正在推进接 RAG——属「规划中未接」而非废弃，建议随 R183 验收时定性。

## C-FP（门禁误报 9 条，前端实际已接线）
#33 auth/me、#34 change-password、#35 logout（store/ipd-auth.ts 直调）；#43 getRouters（api/core/menu.ts:51）；#38 kpi/functional-metrics GET（index.vue:210）；#93 as-of（index.vue:270）；#72-74 public 三条（portal.ts:101 模板串拼接 URL，静态匹配盲区）。旁证：看板 67896f31 [AUD-TRI-R2]「9 处端点误判平反」数量吻合。**建议门禁改进：对 requestPortal/authenticatedRequest 类模板串调用增加调用图回溯。**

## 口径与统计核对
- 总数：A 47 + B 27 + C 13 + C-FP 9 = **96** ✓（以 /tmp/r212_l1_table.md 96 行为准，未重跑门禁改口径）
- contract.json 98 vs 96：差 `GET /api/v1/ai-copilot`、`GET /api/v1/resource` 两条（归一后 96⊂98），按任务口径未纳入分桶。
