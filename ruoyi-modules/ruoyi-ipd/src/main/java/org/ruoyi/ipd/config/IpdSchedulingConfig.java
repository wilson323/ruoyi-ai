package org.ruoyi.ipd.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OPS-04：IPD 侧调度启用开关。
 *
 * <p>背景：主应用未启用 Spring 调度——ruoyi-admin 不依赖 ruoyi-common-job，
 * {@code SnailJobConfig} 的 @EnableScheduling 因依赖缺失 + snail-job.enabled=false 双重不生效，
 * 导致 IPD 的 @Scheduled 任务（离职升级 09:00 / 移交超时 09:05，错峰 5 分钟）静默不跑。
 * 本配置类显式开启后双 job 生效；后续新增调度任务须在此登记错峰时刻。
 *
 * <p>任务登记（错峰表）：PersonResignEscalator 09:00 / HandoverOverdueScanner 09:05 /
 * KpiSharedDeadlineScheduler 09:10（共担 KPI 月度截止催办，R215-GAP-B1 接线补齐 2026-09-25）/
 * ProjectScoreScheduleService 09:15（P3-2.3 上市评分待办扫描，2026-09-19 接线补齐）/
 * GateSignScanScheduler 09:20 签署期限提醒 + 09:25 超时弃权折算（R215-GAP-B4 接线补齐 2026-09-25）/
 * GateLegacyScanScheduler 09:30（条件遗留逾期提醒，R215-GAP-B4）/
 * P0EscalationScanScheduler 09:35（P0 升级链阈值检查，R215-GAP-B4）/
 * GuestDemandOverdueScheduler 09:40（AC-PROD-09 待指派超5工作日提醒组长，R218 卡2 接线补齐 2026-09-25）/
 * BidInvitationExpireScheduler 09:45（AC-TEAM-08 邀标到期自动过期，R219 卡④ 接线补齐 2026-09-26）/
 * StageActionOverdueScheduler 09:50（ACTION_OVERDUE 逐日逾期提醒，R219 卡④；⑤刀 2026-10-06 把 ACTION_DUE_SOON 到期前预警并入同一方法，未新增时刻）/
 * AiProactiveScanScheduler 09:55（R221 AI 主动执行扫描：7 日内到期 AI 档动作建 SCHEDULE 任务，2026-09-26）/
 * AuditAnomalyScanScheduler 10:05（AI-P3 #7 审计异常检测：回看 24h 只读扫 audit_logs，命中 FYI 超管，2026-09-27）/
 * GateG3RecreateScheduler 10:10（F6-③ G3 双周开发复评扫描：DEV 阶段且距上次 G3 满复评周期即补建，
 * 间隔可配 gate.g3.recreate_interval_days；避开 09:00~09:55 晨间窗口与 10:05 审计异常扫描，
 * 亦不与每月 1 日 10:00 津贴台账撞时刻；2026-10-06 登记）/
 * AllowanceMonthlyLedgerScheduler 每月 1 日 10:00（上自然月台账全量生成，R219 卡④）/
 * NotificationOutboxScanner 每 30s 轮询（常驻间隔任务，非整点，与上述无时刻冲突；
 * 间隔可配 ipd.notification.dispatch.interval-ms）；
 * AiTaskFallbackScanner 每 30s 轮询（R221 兜底扫描，同为常驻间隔任务；
 * 间隔可配 ipd.aiexec.fallback.interval-ms）；
 * ProjectAgentSandboxReaper 每小时 :23（沙箱孤儿容器超龄清扫，另含启动全量清扫，2026-10-03；
 * 开关与窗口可配 ipd.agent.sandbox-reaper.enabled / orphan-ttl-hours）；
 * AuditChainIntegrityScheduler 03:30（审计链完整性定时自检：验链锚表判据 + 基线外新增 GAP 告警，2026-10-03；
 * 开关与已知基线可配 ipd.audit.chain-verify.enabled / baseline-gaps——夜间低谷时段，与 00:00 及 09:00~10:05 家族均不撞）。
 */
@Configuration
@EnableScheduling
public class IpdSchedulingConfig {
}
