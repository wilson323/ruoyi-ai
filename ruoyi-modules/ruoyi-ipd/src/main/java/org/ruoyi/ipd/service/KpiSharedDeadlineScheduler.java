package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.service.KpiSharedCollectionService.DeadlineScanResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 共担 KPI 月度截止催办调度器（R215-GAP-B1，2026-09-25 补齐；P3-1.3 催办链收口）。
 *
 * <p>背景：{@code scanMonthlyDeadlines}（逾期第 1 天提醒 / 第 3 天升级）与
 * {@code scanDueSoon}（截止前 1 天 FYI）长期只有 HTTP 手动入口
 * （POST /api/v1/kpi/shared/deadline-scan，仅超管），全模块无 @Scheduled 覆盖——
 * 无人触发即功能空转（孤儿盘点定性 GAP-B1）。
 *
 * <p>调度语义：每日 09:10 扫描<b>上一自然月</b>归集周期（其截止日 = 本自然月第 N 个工作日，
 * N 由 {@code kpi.monthlyDeadlineDay} 实时配置）。周期内已 FINALIZED 的项目跳过；
 * 催办走 NotificationService.publishDaily（dedupKey 含自然日），同日重扫幂等不重发。
 * 截止日未过 / 非截止前一天时两个扫描均为静默零副作用，无需在调度器内做日期判断。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:00 离职升级 / 09:05 移交超时 /
 * <b>09:10 本 job</b> / 09:15 上市评分待办。@Scheduled 由 OPS-04 的
 * {@code IpdSchedulingConfig} 全局启用（已合入主树，本 job 部署即生效）。
 *
 * <p>手动兜底路径保留：POST /api/v1/kpi/shared/deadline-scan?period=YYYY-MM
 * （补扫任意历史周期，调度器只覆盖上一周期）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KpiSharedDeadlineScheduler {

    private final KpiSharedCollectionService collectionService;

    /** 可注入时钟（仿 HandoverOverdueScanner；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private Clock clock = Clock.systemDefaultZone();

    public void setClock(Clock clock) {
        this.clock = (clock == null) ? Clock.systemDefaultZone() : clock;
    }

    /**
     * 每日 09:10 对上一自然月归集周期执行「截止前 1 天提醒 + 逾期催办升级」双扫描。
     */
    @Scheduled(cron = "0 10 9 * * ?")
    public void dailyDeadlineScan() {
        LocalDate scanDate = LocalDate.now(clock);
        YearMonth collectionPeriod = YearMonth.from(scanDate).minusMonths(1);
        DeadlineScanResult dueSoon = collectionService.scanDueSoon(scanDate, collectionPeriod);
        DeadlineScanResult overdue = collectionService.scanMonthlyDeadlines(scanDate, collectionPeriod);
        log.info("KpiSharedDeadlineScheduler: period={} dueSoon(提醒={}/跳过={}) overdue(催办={}/升级={}/跳过={})",
            collectionPeriod, dueSoon.day1Reminders(), dueSoon.skippedProjects(),
            overdue.day1Reminders(), overdue.day3Escalations(), overdue.skippedProjects());
    }
}
