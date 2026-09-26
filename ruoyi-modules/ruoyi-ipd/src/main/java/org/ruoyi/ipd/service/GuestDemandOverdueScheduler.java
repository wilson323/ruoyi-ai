package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 需求池「待指派」超时提醒调度器（R218 卡2 / 看板 f62ab684，AC-PROD-09 接线补齐 2026-09-25）。
 *
 * <p>背景：{@code GuestDemandService.notifyOverdueUnassigned}（P4-1.3 实现）长期零调用方——
 * 11 个 @Scheduled 类无一覆盖、无 HTTP 端点，真库 audit {@code overdue_unassigned}=0 行，
 * 兜底提醒实际永不触发（R218 归因 DEF-B「实现完成但入口不可达」，同 GAP-B1/B4 孤儿盘点族）。
 *
 * <p>调度语义：每日 09:40 扫描 SUBMITTED + 双 PM 空 + 创建超 5 工作日的待指派需求，
 * 逐条 audit 留痕并 publishDaily 真通知产品组组长（同日重扫幂等不重发，次日可再提醒）。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:00 离职 / 09:05 移交 / 09:10 共担KPI /
 * 09:15 上市评分 / 09:20·09:25 Gate 签署 / 09:30 条件遗留 / 09:35 P0 升级 / <b>09:40 本 job</b>。
 * @Scheduled 由 {@code IpdSchedulingConfig} 全局启用（部署即生效）。
 *
 * <p>手动兜底路径保留：POST /api/v1/guest-demands/overdue-scan（超管，验收复测用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuestDemandOverdueScheduler {

    private final GuestDemandService guestDemandService;

    /**
     * 每日 09:40 执行待指派超时扫描（AC-PROD-09）。
     */
    @Scheduled(cron = "0 40 9 * * ?")
    public void dailyOverdueUnassignedScan() {
        int notified = guestDemandService.notifyOverdueUnassigned();
        log.info("GuestDemandOverdueScheduler: AC-PROD-09 待指派超时扫描完成 notified={}", notified);
    }
}
