package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiFunctionalMetric;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.dto.UpsertKpiFunctionalMetricReq;
import org.ruoyi.ipd.mapper.KpiFunctionalMetricMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
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
    private final KpiFunctionalMetricMapper kpiFunctionalMetricMapper;
    private final ProjectMapper projectMapper;

    /**
     * 量表记录归属守卫（横向越权防护，写库前执行）。
     *
     * <p>KpiFunctionalMetric 唯一归属字段是 {@code projectId}，组归属需经
     * {@code projectId → Project.mainGroupId} 二级解析。三要素与 BidController 同款：
     * actor 只来自会话；归属在写库前解析完；失败统一「无权操作」，不泄漏存在性。
     * 复用 {@link IpdIdorGuard#assertSameGroupIpd}。
     *
     * @param actor     会话身份
     * @param projectId 量表所属项目 ID（可空——无项目归属的量表退化为按 id 不可达即拒）
     * @throws IpdBusinessException {@link ApiV1ErrorCode#FORBIDDEN} 项目不存在或跨组
     */
    private void requireProjectGroup(IpdActor actor, Long projectId) {
        if (projectId == null) {
            return;
        }
        Project project = projectMapper.selectById(projectId);
        IpdIdorGuard.assertSameGroupIpd(actor, project == null ? null : project.getMainGroupId());
    }

    /**
     * 读口归属守卫（横向越权读取防护，查询前执行）。
     *
     * <p>与写口 {@link #requireProjectGroup} 的差异只有一处，且是刻意的：
     * 写口允许 projectId 为空（无项目归属的量表退化为按 id 不可达即拒），
     * 读口的 projectId 是必填入参——传 null 说明调用方在构造一个查不到任何东西的请求，
     * 继续放行等于让归属判断被一个恒假参数短路。因此这里对 null / 项目不存在 / 无主组
     * 一律 fail-closed。
     *
     * @param actor     会话身份
     * @param projectId 待查量表所属项目 ID（必填）
     * @throws IpdBusinessException {@link ApiV1ErrorCode#FORBIDDEN} 项目缺失或跨组
     */
    private void requireProjectGroupForRead(IpdActor actor, Long projectId) {
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作");
        }
        IpdIdorGuard.assertSameGroupIpd(actor, project.getMainGroupId());
    }

    /**
     * 列出某项目功能指标量表记录。
     *
     * <p>读口归属校验（本轮补）：projectId 是必填入参，属「按对象直读」而非「列表里混进他人行」，
     * 因此与写口同口径——跨组直接 FORBIDDEN，不返回任何行（半截结果比报错更容易被当数据用）。
     * 修法不照抄写口的「空 projectId 放行」分支：读口 projectId 必填，
     * 项目不存在一律 fail-closed，不让「传一个不存在的 projectId」绕过归属判断。
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
        IpdActor actor = ipdPermission.requireInternal();
        requireProjectGroupForRead(actor, projectId);
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
        IpdActor actor = ipdPermission.requireInternal();
        // projectId 由客户端指定——先验归属再落库，避免写进别人项目的量表
        requireProjectGroup(actor, req.projectId());
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
        IpdActor actor = ipdPermission.requireInternal();
        // 归属解析必须在写库之前：id → 量表 → projectId → 项目主组
        KpiFunctionalMetric existing = kpiFunctionalMetricMapper.selectById(id);
        if (existing == null) {
            // 统一 FORBIDDEN——不区分「记录不存在」与「无权删除」
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作");
        }
        requireProjectGroup(actor, existing.getProjectId());
        kpiFunctionalMetricsService.delete(id);
        return ApiV1Response.ok();
    }
}
