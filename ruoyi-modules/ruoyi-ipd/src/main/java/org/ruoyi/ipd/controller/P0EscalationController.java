package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.P0EscalationChain;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.P0EscalationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * R149 batch2b C4：P0 升级链 API /api/v1/p0/escalation-chain。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/v1/p0/escalation-chain?projectId= 列表（组长/超管可读）</li>
 *   <li>POST /api/v1/p0/escalation-chain/check 触发扫描（仅超管）</li>
 *   <li>POST /api/v1/p0/escalation-chain/{id}/resolve 标记 RESOLVED（组长/超管）</li>
 * </ul>
 *
 * <p>业务记录（每次 P0 超期未升级 +1 count）由上游 P0 事件服务调用
 * {@link P0EscalationService#recordP0Unresolved}，不暴露 HTTP 端点（防越权伪造升级链）。
 */
@RestController
@RequestMapping("/api/v1/p0/escalation-chain")
@RequiredArgsConstructor
@Slf4j
public class P0EscalationController {

    private final P0EscalationService p0EscalationService;
    private final IpdPermission ipdPermission;

    /**
     * 列出升级链（按 projectId 过滤；组长/超管可读）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_P0_ESCALATION_READ, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<List<P0EscalationChain>> list(@RequestParam(required = false) Long projectId) {
        ipdPermission.requireLeaderOrAdmin();
        return ApiV1Response.ok(p0EscalationService.listByProject(projectId));
    }

    /**
     * 手动触发扫描（仅超管；运维/测试用；正常轮询待 scheduler 合入）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_P0_ESCALATION_READ, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/check")
    public ApiV1Response<Map<String, Object>> check() {
        ipdPermission.requireAdmin();
        int escalated = p0EscalationService.checkEscalation();
        return ApiV1Response.ok(Map.of("escalated", escalated));
    }

    /**
     * 标记 RESOLVED（仅组长/超管；处置完成后关闭）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_P0_ESCALATION_READ, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/{id}/resolve")
    public ApiV1Response<Map<String, Object>> resolve(@PathVariable Long id,
                                                      @RequestParam(required = false) String remark) {
        ipdPermission.requireLeaderOrAdmin();
        boolean ok = p0EscalationService.resolve(id, remark);
        return ApiV1Response.ok(Map.of("id", id, "resolved", ok));
    }
}
