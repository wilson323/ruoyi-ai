package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.GateMaterialChecker;
import org.ruoyi.ipd.service.GateMaterialUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Gate 材料齐套性视图端点（[CONSISTENCY-15] + [SEC-FIX] 2026-09-06）。
 *
 * <p>授权：复用 {@code ipd:gate-review:list} 权限码（与 GateReviewController 同源），
 * 同时需满足 {@code projectId} 与 {@code gateId} 关联校验（防越权——避免构造
 * 合法 gateId + 别人 projectId 拉别人材料统计）。
 *
 * <p>GET /api/v1/gates/{gateId}/materials?projectId= → {total, uploaded, missing, isReady, items[]}
 */
@RestController
@RequestMapping("/api/v1/gates/{gateId}/materials")
@RequiredArgsConstructor
public class GateMaterialController {

    private final GateMaterialChecker gateMaterialChecker;
    private final GateMaterialUploadService gateMaterialUploadService;
    private final GateMapper gateMapper;
    private final IpdPermission ipdPermission;
    private final ProjectMapper projectMapper;

    /**
     * Gate 归属守卫（横向越权防护，写库前执行）。
     *
     * <p>GET {@link #materials} 已有 gateId↔projectId 关联校验，但 upload 只有 gateId 一个入参，
     * 组归属必须经 {@code gateId → Gate.projectId → Project.mainGroupId} 两级串联解析——
     * 与读口自相矛盾的缺口在此补齐。actor 只来自会话；失败统一「无权操作」，不泄漏存在性。
     * 复用 {@link IpdIdorGuard#assertSameGroupIpd}。
     *
     * @param gateId Gate ID
     * @return Gate 所属项目的主组 ID（Gate/项目缺失时 fail-closed 抛 FORBIDDEN）
     * @throws IpdBusinessException {@link ApiV1ErrorCode#FORBIDDEN} Gate 不存在或未挂项目
     */
    private Long resolveGateGroup(Long gateId) {
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null || gate.getProjectId() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作");
        }
        Project project = projectMapper.selectById(gate.getProjectId());
        return project == null ? null : project.getMainGroupId();
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_GATE_REVIEW, type = "ipd")
    @GetMapping
    public ApiV1Response<Map<String, Object>> materials(
            @PathVariable("gateId") Long gateId,
            @RequestParam("projectId") Long projectId) {
        // 关联校验：URL 提供的 gateId 必须属于请求中的 projectId——防越权 IDOR
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null || !projectId.equals(gate.getProjectId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "Gate 不存在或不属于该项目");
        }
        return ApiV1Response.ok(gateMaterialChecker.listMaterialStatus(gateId, projectId));
    }

    /** 上传评审材料或会议纪要，返回服务端 ossId。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_GATE_REVIEW, type = "ipd")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiV1Response<Map<String, String>> upload(@PathVariable("gateId") Long gateId,
                                                     @RequestPart("file") MultipartFile file) {
        IpdActor actor = ipdPermission.requireInternal();
        IpdIdorGuard.assertSameGroupIpd(actor, resolveGateGroup(gateId));
        return ApiV1Response.ok(gateMaterialUploadService.upload(gateId, file));
    }
}
