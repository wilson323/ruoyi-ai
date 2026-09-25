package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * P0 升级链阈值检查调度器（R215-GAP-B4，2026-09-25 批次 2 接线）。
 *
 * <p>背景：{@code P0EscalationService.checkEscalation}（R149 C4：同一 P0 连续两次
 * 未处置 → 升级到双方组长）只有超管手动 HTTP 入口
 * （POST /api/v1/p0/escalation-chain/check），Controller javadoc 自注
 * 「正常轮询待 scheduler 合入」——无调度即升级链只记录不推进。
 *
 * <p>幂等取证（双保险）：
 * <ul>
 *   <li>状态标记位：{@code escalateOne} 发通知后把链行 PENDING→ESCALATED
 *       （P0EscalationService 源码注释「避免重复触发」），二次扫描 query
 *       status=PENDING 不再命中该行；</li>
 *   <li>通知去重：升级通知走 {@code publish}（dedupKey=
 *       p0_escalation_chain:P0_ESCALATION_TO_LEADERS:chainId:receiverId 永久去重），
 *       即使并发窗口双触发亦不重发。</li>
 * </ul>
 * 故每日重跑对已升级行完全静默；count 未达阈值的行同样静默（无日期参数，服务内部判定）。
 *
 * <p>调度语义：每日 09:35 全库检查。错峰登记（IpdSchedulingConfig 任务表）：本 job 09:35。
 * 手动兜底端点保留（超管即时升级）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class P0EscalationScanScheduler {

    private final P0EscalationService p0EscalationService;

    /** 可注入时钟（仿 KpiSharedDeadlineScheduler；仅用于日志 scanDate，生产零影响）。 */
    private Clock clock = Clock.systemDefaultZone();

    public void setClock(Clock clock) {
        this.clock = (clock == null) ? Clock.systemDefaultZone() : clock;
    }

    /**
     * 每日 09:35 P0 升级链阈值检查（状态翻转 + publish 永久去重，重跑静默）。
     */
    @Scheduled(cron = "0 35 9 * * ?")
    public void dailyEscalationCheck() {
        int escalated = p0EscalationService.checkEscalation();
        log.info("P0EscalationScanScheduler: scanDate={} escalated={}", LocalDate.now(clock), escalated);
    }
}
