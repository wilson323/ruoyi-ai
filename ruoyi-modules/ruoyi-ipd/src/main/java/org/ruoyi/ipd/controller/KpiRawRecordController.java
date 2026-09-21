package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.KpiRawRecord;
import org.ruoyi.ipd.dto.CreateKpiRawRecordReq;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.KpiRawRecordService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * KPI 原始数据 Controller（A2 KPI P1 期补口，R149 batch2a）。
 *
 * <p>2 端点：
 * <ul>
 *   <li>{@code POST /api/v1/kpi/raw-records} — 组长录入某项目某 KPI 类型某月的原始值，
 *       权限 {@code ipd:kpi:raw:create}（GROUP_LEADER / SUPER_ADMIN）</li>
 *   <li>{@code GET  /api/v1/kpi/raw-records} — 列出某项目某类型所有记录，
 *       权限 {@code ipd:kpi:raw:query}（MARKET_PM / RD_PM / GROUP_LEADER / SUPER_ADMIN）</li>
 * </ul>
 *
 * <p>本 Controller 不复用 {@link KpiRecordController} 的 {@code OPERATION_KPI_QUERY} 码：
 * R-NEW-SEC-5 治理要求写操作必须挂在专用权限码上，与既有 :query 分离。
 */
@RestController
@RequestMapping("/api/v1/kpi/raw-records")
@RequiredArgsConstructor
@Validated
public class KpiRawRecordController {

    private final IpdPermission ipdPermission;
    private final KpiRawRecordService kpiRawRecordService;

    /**
     * 录入 KPI 原始数据（按月）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_KPI_RAW_CREATE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping
    public ApiV1Response<KpiRawRecord> create(@Valid @RequestBody CreateKpiRawRecordReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        KpiRawRecord draft = new KpiRawRecord()
            .setKpiType(req.kpiType())
            .setProjectId(req.projectId())
            .setRecordPeriod(req.recordPeriod())
            .setRawValue(req.rawValue());
        return ApiV1Response.ok(kpiRawRecordService.record(draft, actor));
    }

    /**
     * 列出某项目某类型的所有 KPI 原始记录。
     *
     * @param projectId 项目ID（必填）
     * @param kpiType   KPI 类型（可空；为空时返回该项目全部类型）
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_KPI_RAW_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<List<KpiRawRecord>> list(
        @RequestParam Long projectId,
        @RequestParam(required = false) String kpiType
    ) {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(kpiRawRecordService.list(projectId, kpiType));
    }

    /**
     * 列出当前支持的 KPI 类型枚举（前端下拉用）。
     */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_KPI_RAW_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/types")
    public ApiV1Response<List<String>> listTypes() {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(kpiRawRecordService.listSupportedTypes());
    }
}
