package org.ruoyi.ipd.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 邀标到期自动过期调度器（R219 卡④ / 看板 ef20c06a，零触发接线补齐 2026-09-26）。
 *
 * <p>背景：{@code BidInvitationService.expireOverdue}（AC-TEAM-08 实现）长期零 main 调用方——
 * 12+ 个 @Scheduled 类无一覆盖、无 HTTP 端点，OPEN 且已过 expireAt 的邀标永不转 EXPIRED
 * （R219 归因同 DEF-B「实现完成但入口不可达」孤儿盘点族）。
 *
 * <p>调度语义：每日 09:45 单 SQL 条件 UPDATE 把到期 OPEN 邀标批量置 EXPIRED，
 * 并逐条通知市场 PM（createBy）重新发起（ZK-IPD §四.1.3）。
 *
 * <p>错峰登记（IpdSchedulingConfig 任务表）：09:40 待指派提醒 / <b>09:45 本 job</b> / 09:50 动作逾期。
 *
 * <p>手动兜底路径：POST /api/v1/bid-invitations/expire-scan（超管，验收复测用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BidInvitationExpireScheduler {

    private final BidInvitationService bidInvitationService;

    /**
     * 每日 09:45 执行邀标到期扫描（AC-TEAM-08）。
     */
    @Scheduled(cron = "0 45 9 * * ?")
    public void dailyExpireScan() {
        int expired = bidInvitationService.expireOverdue();
        log.info("BidInvitationExpireScheduler: AC-TEAM-08 邀标到期扫描完成 expired={}", expired);
    }
}
