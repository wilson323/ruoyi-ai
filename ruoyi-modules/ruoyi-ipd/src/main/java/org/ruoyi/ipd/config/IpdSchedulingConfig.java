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
 * NotificationOutboxScanner 每 30s 轮询（常驻间隔任务，非整点，与上述无时刻冲突；
 * 间隔可配 ipd.notification.dispatch.interval-ms）。
 */
@Configuration
@EnableScheduling
public class IpdSchedulingConfig {
}
