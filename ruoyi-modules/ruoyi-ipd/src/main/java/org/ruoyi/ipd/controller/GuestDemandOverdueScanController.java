package org.ruoyi.ipd.controller;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.GuestDemandService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R218 卡2（AC-PROD-09）超管手动扫描端点（独立全局端点，LegacyScanController 同型）。
 *
 * <p>POST /api/v1/guest-demands/overdue-scan —— 扫描待指派超 5 工作日需求，
 * audit 留痕 + publishDaily 提醒产品组组长（同日重扫幂等不重发，可安全重复触发）。
 * 与 {@code GuestDemandOverdueScheduler} 每日 09:40 定时路径共用同一 service 入口，
 * 本端点为验收复测与 ops 补扫兜底。
 */
@RestController
@RequiredArgsConstructor
public class GuestDemandOverdueScanController {

    private final GuestDemandService guestDemandService;
    private final IpdPermission permission;

    @PostMapping("/api/v1/guest-demands/overdue-scan")
    public ApiV1Response<Map<String, Object>> scanOverdueUnassigned() {
        permission.requireAdmin();
        return ApiV1Response.ok(Map.of("notifiedCount", guestDemandService.notifyOverdueUnassigned()));
    }
}
