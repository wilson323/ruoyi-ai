package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.CoefficientChangeRequest;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.CoefficientChangeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * S/B 级系数定值 API（AC-INC-15c）。
 *
 * <p>P1-2：补 2 个只读 GET 端点（按项目列表 + 按 ID 详情）—— 前端 {@code changes.vue}
 * Tab1「系数变更」原本挂着 {@code backend-pending} 占位，本轮接上后即可展示本项目全部
 * 系数变更单（pending / confirmed / rejected 全状态可查）。
 */
@RestController
@RequestMapping("/api/v1/coefficient-change-requests")
@RequiredArgsConstructor
public class CoefficientChangeController {

    private final CoefficientChangeService coefficientChangeService;
    private final IpdPermission ipdPermission;

    /**
     * 双PM 联合提议。
     *
     * @param body 项目/系数/理由/双PM
     * @return 待组长确认申请
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_COEFFICIENT_PROPOSE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping
    public ApiV1Response<CoefficientChangeRequest> propose(@Valid @RequestBody ProposeReq body) {
        // IPD 会话 loginType=ipd，禁止 LoginHelper（基线 StpUtil）取 userId
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(coefficientChangeService.propose(
            body.projectId(), body.proposedCoefficient(), body.reason(),
            body.marketPmId(), body.rdPmId(), actor.id(), actor));
    }

    /**
     * 产品组长确认或驳回。
     *
     * @param id      申请 ID
     * @param approve true=写入项目档案
     * @param opinion 意见
     * @return 终态申请
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_COEFFICIENT_CONFIRM, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/{id}/leader-decision")
    public ApiV1Response<CoefficientChangeRequest> leaderDecision(@PathVariable Long id,
                                                                  @RequestParam boolean approve,
                                                                  @RequestParam(required = false) String opinion) {
        IpdActor actor = ipdPermission.requireLeaderOrAdmin();
        return ApiV1Response.ok(coefficientChangeService.leaderDecision(
            id, actor.id(), approve, opinion, actor));
    }

    /**
     * P1-2：本项目系数变更单列表（按 {@code projectId} 过滤；缺省返回全量，按创建时间倒序）。
     * <p>权限口径与 {@code RequirementChangeController.list} 对齐——使用项目级普通查询码
     * {@code OPERATION_MODULE_PROJECT}，保证 PM/组长/超管均可在项目详情页拉取列表。
     * 财务敏感字段（proposedCoefficient / reason）按 IDOR 边界由 service 层承担。
     *
     * @param projectId 可选；缺省返回全库
     * @return 项目下系数变更单列表
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<List<CoefficientChangeRequest>> listByProject(
        @RequestParam(required = false) Long projectId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(coefficientChangeService.listByProject(
            projectId, String.valueOf(actor.id())));
    }

    /**
     * P1-2：按 ID 取系数变更单详情。权限码沿用项目级查询码，与 RequirementChangeController.detail 对齐。
     *
     * @param id 申请 ID
     * @return 单条系数变更单
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/{id}")
    public ApiV1Response<CoefficientChangeRequest> getById(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(coefficientChangeService.getByIdForReview(
            id, String.valueOf(actor.id())));
    }

    /** 联合提议入参。 */
    public record ProposeReq(
        @NotNull Long projectId,
        @NotNull BigDecimal proposedCoefficient,
        @NotBlank @Size(max = 500) String reason,
        @NotNull Long marketPmId,
        @NotNull Long rdPmId) {
    }
}
