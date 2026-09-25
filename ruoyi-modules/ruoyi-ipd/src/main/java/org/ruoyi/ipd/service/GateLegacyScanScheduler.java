package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 条件遗留逾期提醒扫描调度器（R215-GAP-B4，2026-09-25 批次 2 接线）。
 *
 * <p>背景：{@code GateElementResultService.scanOverdue}（AC-GATE-17 前半：OPEN 且期限
 * 已过的遗留项逐项目通知责任人）长期只有超管手动 HTTP 入口
 * （POST /api/v1/gates/legacy/scan-overdue），全模块无 @Scheduled 覆盖——逾期提醒空转。
 *
 * <p>幂等取证：通知走 {@code NotificationService.publishDaily}（dedupKey 含自然日
 * yyyyMMdd），同日重扫不重发；逾期项未关闭前每日再提醒一次正是 AC-GATE-17 的
 * 逾期催办语义（与 HandoverOverdueScanner 09:05 同范式）。审计行 LEGACY_SCAN_OVERDUE
 * 每次扫描追加一条，属扫描行为留痕而非用户通知，不构成轰炸。
 *
 * <p>调度语义：每日 09:30 全库扫描（窗口条件 leftOverDueAt &lt; now 由服务内部判定，
 * 无逾期项时静默零副作用）。系统身份审计落名沿用 GateSignScanScheduler.SYSTEM_ACTOR。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：本 job 09:30。手动兜底端点保留。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GateLegacyScanScheduler {

    private final GateElementResultService gateElementResultService;

    /** 可注入时钟（仿 KpiSharedDeadlineScheduler；仅用于日志 scanDate，生产零影响）。 */
    private Clock clock = Clock.systemDefaultZone();

    public void setClock(Clock clock) {
        this.clock = (clock == null) ? Clock.systemDefaultZone() : clock;
    }

    /**
     * 每日 09:30 条件遗留逾期提醒（publishDaily 同日去重，重跑不多发）。
     */
    @Scheduled(cron = "0 30 9 * * ?")
    public void dailyLegacyOverdueScan() {
        int overdue = gateElementResultService.scanOverdue(GateSignScanScheduler.SYSTEM_ACTOR);
        log.info("GateLegacyScanScheduler: scanDate={} overdueNotified={}", LocalDate.now(clock), overdue);
    }
}
