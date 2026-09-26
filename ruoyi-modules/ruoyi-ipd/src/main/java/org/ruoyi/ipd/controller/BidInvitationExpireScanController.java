package org.ruoyi.ipd.controller;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.BidInvitationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R219 卡④（ef20c06a）超管手动过期扫描端点（GuestDemandOverdueScanController 同型）。
 *
 * <p>POST /api/v1/bid-invitations/expire-scan —— 把到期仍 OPEN 的邀标批量置 EXPIRED 并
 * 通知市场 PM 重新发起（AC-TEAM-08 / ZK-IPD §四.1.3）。与
 * {@code BidInvitationExpireScheduler} 每日 09:45 定时路径共用同一 service 入口，
 * 本端点为验收复测与 ops 补扫兜底（条件 UPDATE 幂等，可安全重复触发）。
 */
@RestController
@RequiredArgsConstructor
public class BidInvitationExpireScanController {

    private final BidInvitationService bidInvitationService;
    private final IpdPermission permission;

    @PostMapping("/api/v1/bid-invitations/expire-scan")
    public ApiV1Response<Map<String, Object>> expireScan() {
        permission.requireAdmin();
        return ApiV1Response.ok(Map.of("expiredCount", bidInvitationService.expireOverdue()));
    }
}
