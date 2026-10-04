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
    /**
     * 每日 09:35 P0 升级链阈值检查（状态翻转 + publish 永久去重，重跑静默）。
     *
     * <p>2026-10-03 补：**把「表空」与「无到期行」分开**。此前两者都打同一句 INFO，
     * 而升级链的写入方在生产代码里零调用方（它依赖的上游「P0 阻塞事件」域尚未建），
     * 于是这条链会**每天静默跑完、看起来一切正常**。现在表空时升为 WARN 并明确标注
     * 「生产者未接线、未升级判定从未真正生效」，让缺口可被日志/告警发现而不是靠人记得。
     * 不改变任何业务行为：升级判定与通知路径原样。
     */
    @Scheduled(cron = "0 35 9 * * ?")
    public void dailyEscalationCheck() {
        int escalated = p0EscalationService.checkEscalation();
        if (escalated == 0 && p0EscalationService.pendingCount() == 0L) {
            log.warn("P0EscalationScanScheduler: scanDate={} escalated=0 pendingRows=0 —— "
                + "升级链表为空。已知缺口：recordP0Unresolved 在生产代码里零调用方，"
                + "上游「P0 阻塞事件」域尚未建，故「连续 2 次 P0 未升级 → 升级双方组长」"
                + "这条规则从未真正生效（写入侧未接线）。本条不代表「今天没有需要升级的」。",
                LocalDate.now(clock));
            return;
        }
        log.info("P0EscalationScanScheduler: scanDate={} escalated={}", LocalDate.now(clock), escalated);
    }
}
