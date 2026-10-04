package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.ProductLineMember;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.ProductLineSpaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 产品线团队空间入口；项目 AI 动作仍由各项目接口单独校验成员身份。 */
@RestController
@RequestMapping("/api/v1/ipd/product-lines")
@RequiredArgsConstructor
public class ProductLineSpaceController {
    private final ProductLineSpaceService service;
    private final IpdPermission permission;
    private final org.ruoyi.ipd.agent.service.DemandTriageRun triage;

    public record CreateRequest(String code, String name) { }
    public record RenameRequest(String name) { }
    public record ReviewRequest(Boolean approve) { }
    public record LineView(Long id, String code, String name, Long leaderPersonId, String status) {
        static LineView from(ProductLine line) {
            return new LineView(line.getId(), line.getLineCode(), line.getLineName(),
                line.getLeaderPersonId(), line.getStatus());
        }
    }
    public record MemberView(Long personId, String status, Long reviewedBy) {
        static MemberView from(ProductLineMember member) {
            return new MemberView(member.getPersonId(), member.getStatus(), member.getReviewedBy());
        }
    }
    public record ProductView(Long id, String code, String name, String status) {
        static ProductView from(Product product) {
            return new ProductView(product.getId(), product.getProductCode(), product.getProductName(), product.getStatus());
        }
    }
    public record ProjectView(Long id, String code, String name, String currentStage, String status) {
        static ProjectView from(Project project) {
            return new ProjectView(project.getId(), project.getCode(), project.getName(),
                project.getCurrentStage(), project.getStatus());
        }
    }

    @GetMapping
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<LineView>> visibleLines() {
        return ApiV1Response.ok(service.visibleLines(permission.requireInternal()).stream().map(LineView::from).toList());
    }

    @GetMapping("/discoverable")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<LineView>> discoverableLines() {
        return ApiV1Response.ok(service.discoverableLines(permission.requireInternal())
            .stream().map(LineView::from).toList());
    }

    @PostMapping
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<LineView> create(@RequestBody CreateRequest request) {
        return ApiV1Response.ok(LineView.from(service.create(request.code(), request.name(), permission.requireAdmin())));
    }

    @PutMapping("/{lineId}/name")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<LineView> rename(@PathVariable Long lineId, @RequestBody RenameRequest request) {
        if (request == null) throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "产品线名称不能为空");
        return ApiV1Response.ok(LineView.from(service.rename(lineId, request.name(), permission.requireAdmin())));
    }

    @PutMapping("/{lineId}/deactivate")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<LineView> deactivate(@PathVariable Long lineId) {
        return ApiV1Response.ok(LineView.from(service.deactivate(lineId, permission.requireAdmin())));
    }

    @PostMapping("/{lineId}/join-applications")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_APPLY, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<MemberView> apply(@PathVariable Long lineId) {
        return ApiV1Response.ok(MemberView.from(service.apply(lineId, permission.requireInternal())));
    }

    @PostMapping("/{lineId}/leave")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_LEAVE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<MemberView> leave(@PathVariable Long lineId) {
        return ApiV1Response.ok(MemberView.from(service.leave(lineId, permission.requireInternal())));
    }

    @PostMapping("/{lineId}/members/{personId}/remove")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<MemberView> removeMember(@PathVariable Long lineId, @PathVariable Long personId) {
        return ApiV1Response.ok(MemberView.from(service.removeMember(lineId, personId, permission.requireAdmin())));
    }

    @GetMapping("/{lineId}/join-applications")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<MemberView>> pending(@PathVariable Long lineId) {
        return ApiV1Response.ok(service.pendingApplications(lineId, permission.requireInternal())
            .stream().map(MemberView::from).toList());
    }

    @PostMapping("/{lineId}/join-applications/{personId}/review")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<MemberView> review(@PathVariable Long lineId, @PathVariable Long personId,
                                             @RequestBody ReviewRequest request) {
        if (request == null || request.approve() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "审批决定必须明确填写 approve");
        }
        return ApiV1Response.ok(MemberView.from(service.review(lineId, personId, request.approve(),
            permission.requireInternal())));
    }

    @PutMapping("/{lineId}/leader/{personId}")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<LineView> appointLeader(@PathVariable Long lineId, @PathVariable Long personId) {
        return ApiV1Response.ok(LineView.from(service.appointLeader(lineId, personId, permission.requireAdmin())));
    }

    @PutMapping("/{lineId}/products/{productId}")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<ProductView> assignProduct(@PathVariable Long lineId, @PathVariable Long productId) {
        return ApiV1Response.ok(ProductView.from(service.assignProduct(lineId, productId, permission.requireAdmin())));
    }

    @PutMapping("/{lineId}/products/{productId}/unassign")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<ProductView> unassignProduct(@PathVariable Long lineId, @PathVariable Long productId) {
        return ApiV1Response.ok(ProductView.from(service.unassignProduct(lineId, productId, permission.requireAdmin())));
    }

    @GetMapping("/{lineId}/products")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<ProductView>> products(@PathVariable Long lineId) {
        return ApiV1Response.ok(service.products(lineId, permission.requireInternal())
            .stream().map(ProductView::from).toList());
    }

    @GetMapping("/{lineId}/projects")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<ProjectView>> projects(@PathVariable Long lineId) {
        return ApiV1Response.ok(service.projects(lineId, permission.requireInternal())
            .stream().map(ProjectView::from).toList());
    }

    @GetMapping("/{lineId}/demands")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<DemandView>> demands(@PathVariable Long lineId) {
        return ApiV1Response.ok(service.demands(lineId, permission.requireInternal()).stream()
            .map(demand -> new DemandView(demand.getId(), demand.getTitle(), demand.getStatus(), triage.status(demand))).toList());
    }

    @PostMapping("/{lineId}/demands/{demandId}/triage-retry")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<org.ruoyi.ipd.agent.service.DemandTriageRun.TriageStatus> retryTriage(
            @PathVariable Long lineId, @PathVariable Long demandId) {
        service.requireTriageDemand(lineId, demandId, permission.requireInternal(), true);
        return ApiV1Response.ok(triage.retry(demandId));
    }

    public record DemandView(Long id, String title, String status,
        org.ruoyi.ipd.agent.service.DemandTriageRun.TriageStatus triage) {}
}
