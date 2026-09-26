package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 阶段动作逾期每日提醒调度器（R219 卡④ / 看板 ef20c06a，ACTION_OVERDUE 零发布方接线补齐 2026-09-26）。
 *
 * <p>背景：{@code NotificationService.Types.ACTION_OVERDUE} 长期只有声明零发布方——
 * 真库 notifications 无任何 ACTION_OVERDUE 行，dueDate 已过的开放动作无人提醒。
 *
 * <p>调度语义：每日 09:50 扫描逾期开放动作（NOT_STARTED/IN_PROGRESS/DELAYED，LIMIT 500），
 * 按 ownerRole 解析接收人（MARKET_PM/RD_PM/BOTH→在职成员；GROUP_LEADER→主组组长）后
 * publishDaily 发 ACTION 类通知（同日重扫幂等不重发，次日再提醒，AC-IPD-12）。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:45 邀标过期 / <b>09:50 本 job</b> /
 * 每月 1 日 10:00 津贴台账生成。
 *
 * <p>手动兜底路径：POST /api/v1/stage-actions/overdue-scan（超管，验收复测用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StageActionOverdueScheduler {

    private final StageActionService stageActionService;

    /**
     * 每日 09:50 执行动作逾期提醒扫描（ACTION_OVERDUE）。
     */
    @Scheduled(cron = "0 50 9 * * ?")
    public void dailyOverdueScan() {
        int notified = stageActionService.notifyOverdueActions();
        log.info("StageActionOverdueScheduler: ACTION_OVERDUE 逾期动作提醒完成 notified={}", notified);
    }
}
