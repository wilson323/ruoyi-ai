package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内部需求池（P0 域#24 差距项；对齐 ZK-IPD 原型 GET /api/demands + triage + link-project）。
 * 页：产品空间-需求池 Tab。游客侧提交见 PublicPortalController/GuestDemandService。
 */
@RestController
@RequestMapping("/api/v1/demands")
@RequiredArgsConstructor
public class DemandController {

    /** Requirement.status 合法值域（v3 TS-06）。 */
    private static final Set<String> STATUSES = Set.of(
        "SUBMITTED", "ACCEPTED", "EVALUATING", "SCHEDULED", "PROCESSING", "CLOSED", "ARCHIVED");

    private final RequirementMapper requirementMapper;
    private final ProductMapper productMapper;
    private final ProjectMapper projectMapper;
    private final PersonMapper personMapper;
    private final IpdPermission ipdPermission;

    /**
     * 需求归属守卫（横向越权防护，写库前执行）。
     *
     * <p>需求表本身没有 groupId 字段，组归属只能经 {@code Requirement.productId → Product.groupId}
     * 解析；本接口权限码 {@code OPERATION_PRODUCT_GROUP_BIND_PROJECT} 是产品组维度的，
     * 故按「操作人组 == 需求所属产品组」收口，与 {@code BidP231Validator} /
     * {@code GateReviewService} 的 {@link IpdIdorGuard#assertSameGroupIpd} 口径一致。
     *
     * <p>只校验需求自身的归属，不校验待关联项目的归属——跨组协同绑定项目正是本接口的业务目的，
     * 若一并拦住会误伤合法流程（项目侧归属是 owner 待确认的业务问题，不在本次范围）。
     *
     * @param requirement 已加载的需求
     * @return 需求所属产品组 ID（产品缺失时为 null，由调用点按 fail-closed 判 403）
     */
    private Long resolveDemandGroup(Requirement requirement) {
        Product product = requirement.getProductId() == null
            ? null
            : productMapper.selectById(requirement.getProductId());
        // 产品缺失与跨组统一由调用点的 assertSameGroupIpd 判 403，不泄漏该产品是否存在
        return product == null ? null : product.getGroupId();
    }

    /** 需求列表（可按产品/状态过滤；附产品名与双PM姓名）。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_GROUP, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<Map<String, Object>> list(@RequestParam(required = false) Long productId,
                                                   @RequestParam(required = false) String status) {
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<Requirement>()
            .orderByDesc(Requirement::getId);
        if (productId != null) {
            wrapper.eq(Requirement::getProductId, productId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Requirement::getStatus, status);
        }
        List<Requirement> requirements = requirementMapper.selectList(wrapper);

        Map<Long, String> productNames = namesOfProducts(requirements);
        Map<Long, String> personNames = namesOfPeople(requirements);

        List<Map<String, Object>> demands = new ArrayList<>();
        for (Requirement r : requirements) {
            demands.add(toDemandMap(r, productNames, personNames));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("demands", demands);
        result.put("total", demands.size());
        return ApiV1Response.ok(result);
    }

    /**
     * 需求详情（R215-E2E-C 看板卡 f4445a05 补缺口：此前仅 list/triage/link-project 三端点）。
     * 权限码与 list 同款（OPERATION_PRODUCT_GROUP 读码）；字段集与 list 共用 toDemandMap 防漂移；
     * 不存在时复用 requireDemand 的既有 NOT_FOUND，不自造错误码。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_GROUP, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/{id}")
    public ApiV1Response<Map<String, Object>> detail(@PathVariable Long id) {
        Requirement requirement = requireDemand(id);
        return ApiV1Response.ok(toDemandMap(requirement,
            namesOfProducts(List.of(requirement)),
            namesOfPeople(List.of(requirement))));
    }

    /** 分流：设定状态 + 分派双PM（原型 POST /api/demands/:id/triage）。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_GROUP_BIND_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/{id}/triage")
    public ApiV1Response<Map<String, Object>> triage(@PathVariable Long id,
                                                     @RequestBody TriageRequest request) {
        Requirement requirement = requireDemand(id);
        if (request.status() == null || !STATUSES.contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法需求状态：" + request.status());
        }
        if (request.marketPmId() != null && personMapper.selectById(request.marketPmId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "市场PM不存在");
        }
        if (request.rdPmId() != null && personMapper.selectById(request.rdPmId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "研发PM不存在");
        }
        requirement.setStatus(request.status());
        if (request.marketPmId() != null) {
            requirement.setMarketPmId(request.marketPmId());
        }
        if (request.rdPmId() != null) {
            requirement.setRdPmId(request.rdPmId());
        }
        requirement.setRoutedAt(new Date());
        requirementMapper.updateById(requirement);
        return ApiV1Response.ok(Map.of("id", id, "status", requirement.getStatus()));
    }

    /** 关联项目（原型 POST /api/demands/:id/link-project；绑定后置 SCHEDULED；前置：双 PM 已分派）。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_PRODUCT_GROUP_BIND_PROJECT, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/{id}/link-project")
    public ApiV1Response<Map<String, Object>> linkProject(@PathVariable Long id,
                                                          @RequestBody LinkProjectRequest request) {
        IpdActor actor = ipdPermission.requireInternal();
        Requirement requirement = requireDemand(id);
        // 归属校验排在任何写库之前；早于 R215-P2 分诊校验，保证越权者不触达业务规则
        IpdIdorGuard.assertSameGroupIpd(actor, resolveDemandGroup(requirement));
        // R215-P2：未分诊（双 PM 未分派）的需求不得关联项目，避免跳过 triage 直接 SCHEDULED
        if (requirement.getMarketPmId() == null || requirement.getRdPmId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "需求未完成分诊（双PM未分派），不能关联项目");
        }
        Project project = projectMapper.selectById(request.projectId());
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
        }
        requirement.setProjectId(project.getId());
        if ("SUBMITTED".equals(requirement.getStatus()) || "ACCEPTED".equals(requirement.getStatus())) {
            requirement.setStatus("SCHEDULED");
        }
        requirementMapper.updateById(requirement);
        return ApiV1Response.ok(Map.of("id", id, "projectId", project.getId(),
            "status", requirement.getStatus()));
    }

    /** 单条需求 → 输出 Map（list/detail 共用同一字段集，防两端点字段漂移）。 */
    private Map<String, Object> toDemandMap(Requirement r,
                                             Map<Long, String> productNames,
                                             Map<Long, String> personNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("productId", r.getProductId());
        m.put("productName", r.getProductId() != null ? productNames.get(r.getProductId()) : null);
        m.put("projectId", r.getProjectId());
        m.put("source", r.getSource());
        m.put("submitterName", r.getSubmitterName());
        m.put("customerName", r.getCustomerName());
        m.put("title", r.getTitle());
        m.put("status", r.getStatus());
        m.put("marketPmId", r.getMarketPmId());
        m.put("marketPmName", r.getMarketPmId() != null ? personNames.get(r.getMarketPmId()) : null);
        m.put("rdPmId", r.getRdPmId());
        m.put("rdPmName", r.getRdPmId() != null ? personNames.get(r.getRdPmId()) : null);
        m.put("createdAt", r.getCreateTime());
        return m;
    }

    private Requirement requireDemand(Long id) {
        Requirement requirement = requirementMapper.selectById(id);
        if (requirement == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "需求不存在");
        }
        return requirement;
    }

    private Map<Long, String> namesOfProducts(List<Requirement> requirements) {
        List<Long> ids = requirements.stream()
            .map(Requirement::getProductId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productMapper.selectBatchIds(ids).stream()
            .collect(java.util.stream.Collectors.toMap(Product::getId, Product::getProductName, (a, b) -> a));
    }

    private Map<Long, String> namesOfPeople(List<Requirement> requirements) {
        List<Long> ids = new ArrayList<>();
        requirements.forEach(r -> {
            if (r.getMarketPmId() != null) {
                ids.add(r.getMarketPmId());
            }
            if (r.getRdPmId() != null) {
                ids.add(r.getRdPmId());
            }
        });
        if (ids.isEmpty()) {
            return Map.of();
        }
        return personMapper.selectBatchIds(ids.stream().distinct().toList()).stream()
            .collect(java.util.stream.Collectors.toMap(Person::getId, Person::getName, (a, b) -> a));
    }

    public record TriageRequest(@NotBlank String status, Long marketPmId, Long rdPmId) { }

    public record LinkProjectRequest(@NotNull Long projectId) { }
}
