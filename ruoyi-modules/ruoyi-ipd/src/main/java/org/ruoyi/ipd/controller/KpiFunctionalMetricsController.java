package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.KpiFunctionalMetric;
import org.ruoyi.ipd.dto.UpsertKpiFunctionalMetricReq;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.KpiFunctionalMetricsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * KPI 功能指标量表 Controller（A2 P1 期，R148.1 §2.2 方案②）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET    /api/v1/kpi/functional-metrics?projectId&amp;metricCode} — 量表列表，
 *       权限 {@code ipd:kpi:config:query}（内部四角色可读）</li>
 *   <li>{@code GET    /api/v1/kpi/functional-metrics/codes} — 8 项指标编码枚举（前端下拉），
 *       权限 {@code ipd:kpi:config:query}</li>
 *   <li>{@code PUT    /api/v1/kpi/functional-metrics} — 录入 / 更新（upsert），
 *       权限 {@code ipd:kpi:config}（SUPER_ADMIN / MARKET_PM / RD_PM 可写）</li>
 *   <li>{@code DELETE /api/v1/kpi/functional-metrics/{id}} — 软删除，
 *       权限 {@code ipd:kpi:config}</li>
 * </ul>
 *
 * <p>写操作挂专用码（不挂在 {@code ipd:kpi:query} 上），符合 R-NEW-SEC-5 治理：
 * 注解层只把住「此类操作可被哪一类角色调」，对象级校验由 service 二次兜底。
 */
@RestController
@RequestMapping("/api/v1/kpi/functional-metrics")
@RequiredArgsConstructor
@Validated
public class KpiFunctionalMetricsController {

    private final IpdPermission ipdPermission;
    private final KpiFunctionalMetricsService kpiFunctionalMetricsService;

    /**
     * 列出某项目功能指标量表记录。
     *
     * @param projectId  项目 ID（必填）
     * @param metricCode 指标编码（可空；为空返回全部 8 项记录）
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_KPI_CONFIG_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<List<KpiFunctionalMetric>> list(
        @RequestParam Long projectId,
        @RequestParam(required = false) String metricCode
    ) {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(kpiFunctionalMetricsService.listByProject(projectId, metricCode));
    }

    /**
     * 8 项功能指标编码枚举（前端录入下拉用）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_KPI_CONFIG_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/codes")
    public ApiV1Response<List<String>> listCodes() {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(kpiFunctionalMetricsService.listMetricCodes());
    }

    /**
     * 录入 / 更新功能指标量表（以 projectId + metricCode + period 幂等 upsert）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_KPI_CONFIG, type = IpdAuthSession.LOGIN_TYPE)
    @PutMapping
    public ApiV1Response<KpiFunctionalMetric> upsert(@Valid @RequestBody UpsertKpiFunctionalMetricReq req) {
        ipdPermission.requireInternal();
        KpiFunctionalMetric draft = new KpiFunctionalMetric()
            .setProjectId(req.projectId())
            .setMetricCode(req.metricCode())
            .setPeriod(req.period())
            .setMetricValue(req.metricValue())
            .setTargetValue(req.targetValue())
            .setScaleVersion(req.scaleVersion())
            .setRemark(req.remark());
        return ApiV1Response.ok(kpiFunctionalMetricsService.upsert(draft));
    }

    /**
     * 软删除一条量表记录。
     *
     * @param id 主键
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_KPI_CONFIG, type = IpdAuthSession.LOGIN_TYPE)
    @DeleteMapping("/{id}")
    public ApiV1Response<Void> delete(@PathVariable Long id) {
        ipdPermission.requireInternal();
        kpiFunctionalMetricsService.delete(id);
        return ApiV1Response.ok();
    }
}
