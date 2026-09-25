# R215-GAP-B4 收口证据：gate/legacy/P0 扫描触发型端点调度接线（2026-09-25，批次 2）

## 背景
B1（kpi/shared/deadline-scan）同范式收口后，本轮处理剩余「运维扫描触发型」孤儿端点——只有超管手动 HTTP 入口（`permission.requireAdmin()` + curl），全模块无 @Scheduled 覆盖，无人触发即功能空转：

| 端点 | Controller | 服务方法 |
|------|-----------|---------|
| POST /api/v1/gates/sign/scan-timeout | GateSignScanController:32-36 | GateReviewService.scanTimeout |
| POST /api/v1/gates/sign/scan-remind | GateSignScanController:38-42 | GateReviewService.scanRemind |
| POST /api/v1/gates/legacy/scan-overdue | LegacyScanController:27-31 | GateElementResultService.scanOverdue |
| POST /api/v1/p0/escalation-chain/check | P0EscalationController:58-63 | P0EscalationService.checkEscalation |

端点口径说明：任务书写「5 端点」，上列 3 个 Controller 实际含 4 个扫描触发端点。盘点中常并列的第 5 个
`POST /api/v1/handover/scan-overdue`（HandoverController:156）已由 HandoverOverdueScanner 09:05 覆盖（OPS-04 既有接线，IpdSchedulingConfig 错峰表在案）；`/kpi/shared/deadline-scan` 已由 B1 09:10 覆盖。本轮无遗漏。

## 逐端点幂等取证表

去重底座：`NotificationService.publishDaily` dedupKey 追加自然日 yyyyMMdd（NotificationService.java:146-156，同日重扫撞库返回既有行不重发）；`publish` dedupKey = sourceType:eventType:sourceId:receiverId 永久去重（NotificationService.java:131-142）。

| 端点 | 通知路径 | 去重机制（文件:行号） | 结论 | 处置 |
|------|---------|---------------------|------|------|
| gates/sign/scan-remind | publishDaily(GATE_SIGN_SOON)（GateReviewService.java:573-577） | dedupKey 含自然日（NotificationService.java:153-155）；提醒窗口 `0 < untilDue ≤ 24h` 天然逐日轮转（GateReviewService.java:556-558） | **幂等安全** | 接线每日 09:20 |
| gates/sign/scan-timeout | notifyBothPms→publish（GateReviewService.java:540-542, 997-1005） | 双保险：扫描仅取 status=PENDING（GateReviewService.java:498-500），settleTimeout 落终态后不再命中且自带 PENDING 守卫防双 settle（GateReviewService.java:941-951）；知会 publish 永久 dedupKey | **幂等安全** | 接线每日 09:25 |
| gates/legacy/scan-overdue | publishDaily(GATE_CONDITION_OVERDUE)（GateElementResultService.java:410-416） | dedupKey 含自然日；逾期项未关闭前每日一次的再提醒正是 AC-GATE-17 催办语义（与 09:05 移交超时同范式）。审计行 LEGACY_SCAN_OVERDUE 为扫描留痕非用户通知（GateElementResultService.java:419-429） | **幂等安全** | 接线每日 09:30 |
| p0/escalation-chain/check | publish(NOTIFY_TYPE)（P0EscalationService.java:198-209） | 双保险：escalateOne 发后翻状态 PENDING→ESCALATED，二次扫描 query status=PENDING 不再命中（P0EscalationService.java:145-149, 211-217 注释「避免重复触发」）；publish 永久 dedupKey 兜底并发窗口 | **幂等安全** | 接线每日 09:35 |

无一端点落入「无去重不调度」档；四端点均无需传 scanDate 类参数（日期窗口判定在服务方法内部，基于自身可注入时钟），调度器直接零参委托。

## 实施
- 新增 `GateSignScanScheduler`（service 包）：09:20 期限提醒 + 09:25 超时弃权折算双 job；两窗口互斥（remind 只看未到期 24h 内、timeout 只看已超期），执行顺序无耦合。
- 新增 `GateLegacyScanScheduler`：09:30 条件遗留逾期提醒。
- 新增 `P0EscalationScanScheduler`：09:35 P0 升级链阈值检查（checkEscalation 无 actor 参数，直调）。
- 系统身份：`new IpdActor(0L, "system", "SYSTEM", null)` 审计落名，沿用 RequirementChangeService.java:74 SYSTEM_ACTOR 先例；服务方法内 operator 仅落审计行，无角色校验路径（scanRemind 形参甚至未使用，GateReviewService.java:550-582）。
- 三调度器均可注入 Clock（照抄 KpiSharedDeadlineScheduler/HandoverOverdueScanner 写法），log.info 销账行：`GateSignScanScheduler remind: scanDate=... reminded=N` / `timeout: ... abstainHandled=N` / `GateLegacyScanScheduler: ... overdueNotified=N` / `P0EscalationScanScheduler: ... escalated=N`。
- `IpdSchedulingConfig` javadoc 错峰表追加 09:20/09:25/09:30/09:35 登记行（唯一改动既有文件，纯注释）。@EnableScheduling 已随 OPS-04 在主树 → 部署即生效。
- 手动兜底路径全部保留（4 个 HTTP 端点不删，供超管补跑/验证）。

## 验证
- `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=GateSignScanSchedulerTest,GateLegacyScanSchedulerTest,P0EscalationScanSchedulerTest test`（无 -am/clean）：
  **Tests run: 9, Failures: 0, Errors: 0, Skipped: 0** BUILD SUCCESS（2026-09-25 03:43 实测；GateSign 4 + P0 3 + Legacy 2）。
- 用例覆盖：系统身份参数精确校验（eq(IpdActor(0,system,SYSTEM,null))）、单触发 times(1) 委托、同日双触发各自委托（去重责任锁定服务层）、零结果静默路径、setClock(null) 回退。@Tag("dev") 已挂（Surefire groups=${profiles.active} 假绿陷阱规避，pom.xml:479）。
- runtime 生效验证依赖下次后端重启 + 真实时钟过 09:20~09:35 档，登记于此不提前销账；重启后按上述 4 条 log.info 前缀逐行销账。

## 遗留
- 多实例部署下 @Scheduled 无分布式锁（SnailJob 未启用），两实例同日并发扫描时 scan-timeout/p0 check 的状态翻转存在竞态窗口——现有 publish dedupKey（唯一键撞库）保证通知不重发，状态 UPDATE 幂等收敛，风险仅审计双行；单实例部署（现状）无此问题。若未来多实例，需随 ShedLock 类方案统一治理（全模块 7 个 cron job 同摊）。
- P0EscalationController javadoc「正常轮询待 scheduler 合入」的注释已过时（本卡即该 scheduler），属在途文件不改动，登记于此供后续文档轮统一清理。
