package org.ruoyi.ipd.service;

import java.util.Date;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI-P3 #7 审计异常检测调度器：每日 10:05 回看 24h 扫 audit_logs（只读）。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:00~09:55 晨间家族已占满、每月 1 日 10:00
 * 台账占用，本 job 取 <b>10:05</b> 每日型（guardKey 10:05 无撞点）。
 *
 * <p>卡面硬约束「只报不拦」：本 job 仅 SELECT + 发通知，绝不 UPDATE/DELETE audit_logs，
 * 也不在任何业务写事务路径上设卡。检测语义见 {@link AuditAnomalyScanService}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditAnomalyScanScheduler {

    private final AuditAnomalyScanService auditAnomalyScanService;

    /** 每日 10:05 执行异常扫描（窗口 24h，与 job 间隔对齐实现无缝覆盖）。 */
    @Scheduled(cron = "0 5 10 * * ?")
    public void dailyAnomalyScan() {
        int hits = auditAnomalyScanService.scanAndNotify(new Date());
        log.info("AuditAnomalyScanScheduler: AI-P3#7 审计异常扫描完成 hits={}", hits);
    }
}
