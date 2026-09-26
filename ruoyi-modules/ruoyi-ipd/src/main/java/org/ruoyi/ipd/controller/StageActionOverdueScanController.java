package org.ruoyi.ipd.controller;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.StageActionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R219 卡④（ef20c06a）超管手动动作逾期扫描端点（GuestDemandOverdueScanController 同型）。
 *
 * <p>POST /api/v1/stage-actions/overdue-scan —— 扫描逾期开放动作并按 ownerRole 解析接收人，
 * publishDaily 发 ACTION_OVERDUE 通知（同日重扫幂等不重发，可安全重复触发）。与
 * {@code StageActionOverdueScheduler} 每日 09:50 定时路径共用同一 service 入口，
 * 本端点为验收复测与 ops 补扫兜底。
 */
@RestController
@RequiredArgsConstructor
public class StageActionOverdueScanController {

    private final StageActionService stageActionService;
    private final IpdPermission permission;

    @PostMapping("/api/v1/stage-actions/overdue-scan")
    public ApiV1Response<Map<String, Object>> overdueScan() {
        permission.requireAdmin();
        return ApiV1Response.ok(Map.of("notifiedCount", stageActionService.notifyOverdueActions()));
    }
}
