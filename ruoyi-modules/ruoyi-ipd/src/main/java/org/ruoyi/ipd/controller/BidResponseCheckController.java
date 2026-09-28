package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.BidResponseCheckService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI-P2-2 #2 应标完整性检查 API（提交前自检；纯只读）。
 *
 * <p>POST /api/v1/bid-invitations/{id}/ai-completeness-check —— 入参应标方案说明草稿，
 * 出参逐条检查表（MET/PARTIAL/MISSING + 证据）+ 总评 JSON，仅回显供补稿。权限与
 * submitResponse（POST /bid-responses）同码 {@code ipd:project:edit}，不新增权限码。
 *
 * <p>红线：检查≠提交——本端点零业务表写入，提交仍走 POST /bid-responses 人工流。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BidResponseCheckController {

    private final BidResponseCheckService bidResponseCheckService;
    private final IpdPermission ipdPermission;

    /** 请求体：应标方案说明草稿（检查对象）。 */
    public record BidCheckReq(String responseNote) {}

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/bid-invitations/{id}/ai-completeness-check")
    public ApiV1Response<BidResponseCheckService.CheckView> aiCheck(@PathVariable Long id,
                                                                    @RequestBody BidCheckReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(bidResponseCheckService.check(actor, id,
            req == null ? null : req.responseNote()));
    }
}
