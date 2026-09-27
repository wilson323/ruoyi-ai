package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.BidAiCompareService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI-P2-2 #3 遴选对比汇总 API（只读；路由风格对齐既有 bid-invitations 子资源）。
 *
 * <p>POST /api/v1/bid-invitations/{id}/ai-compare —— 入参 2~5 份应标 id，
 * 输出维度对照表 + 差异高亮 JSON，仅展示不落库。
 *
 * <p>权限：注解层与 bid-select（PUT /bid-invitations/{id}/select）同码
 * {@code ipd:project:edit}（目录已登记，不新增码不动 catalog），方法内再经
 * {@link IpdPermission#requireLeaderOrAdmin()} 收紧为组长/超管（遴选资格角色）；
 * MARKET_PM / RD_PM 调用即 403。
 *
 * <p>红线：本端点与 confirmToken 两阶段人工遴选流（/pre-select-token、/select，
 * ROOT-R6-D1-WHITE-B 契约）零交集——不调用、不校验、不清空该流程的任何状态。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BidAiCompareController {

    private final BidAiCompareService bidAiCompareService;
    private final IpdPermission ipdPermission;

    /** 请求体：参与对比的应标 id 列表（2~5 份，服务端去重后校验）。 */
    public record BidCompareReq(List<Long> responseIds) {}

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/bid-invitations/{id}/ai-compare")
    public ApiV1Response<BidAiCompareService.CompareView> aiCompare(@PathVariable Long id,
                                                                    @RequestBody BidCompareReq req) {
        return ApiV1Response.ok(bidAiCompareService.compare(ipdPermission.requireLeaderOrAdmin(),
            id, req == null ? null : req.responseIds()));
    }
}
