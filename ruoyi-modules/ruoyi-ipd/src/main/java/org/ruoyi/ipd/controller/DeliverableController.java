package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.IDeliverableService;
import org.ruoyi.ipd.service.IStageActionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * P1-4.2（卡 fde68b8c）：真实附件上传下载与动作归属鉴权入口。
 *
 * <p>鉴权分层（全部复用既有模式）：
 * SaCheckPermission 功能权限 + {@link IpdPermission#requireActionWriter} 动作归属写门
 * （与 {@code POST /api/v1/stage-actions/{id}/deliverables} 同闸）+ service 层
 * {@code IpdIdorGuard} 对象级守卫（下载校验项目归属）。
 *
 * <p>上传链不接收客户端 ossId——由服务端 {@code ISysOssService.upload} 产生并回读，
 * 消除卡面源状态所记「现有客户端 ossId 不可信」缺口（相邻 CONSISTENCY-13 旧入口保留不动）。
 */
@RestController
@RequestMapping("/api/v1/deliverables")
@RequiredArgsConstructor
public class DeliverableController {

    private final IDeliverableService deliverableService;
    private final IStageActionService stageActionService;
    private final IpdPermission ipdPermission;

    /**
     * 真实附件上传并登记交付物（AC1 对象存在才登记 / AC2 100MB+格式策略 /
     * AC3 上传者、大小、hash 入库 / AC5 登记失败孤儿补偿，判定均在 service 层）。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION_DELIVERABLE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Deliverable> upload(@RequestParam Long actionId,
                                             @RequestPart("file") MultipartFile file) {
        // 动作归属鉴权：owner 角色或 SUPER_ADMIN 才可传（requireActionWriter 既有闸）
        IpdActor actor = ipdPermission.requireActionWriter(() -> stageActionService.getById(actionId));
        return ApiV1Response.ok(deliverableService.upload(actionId, file, actor));
    }

    /** 下载交付物附件（AC4 下载校验项目归属：项目在职成员或 SUPER_ADMIN，service 层 fail-closed）。 */
    @GetMapping("/{id}/download")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION, type = IpdAuthSession.LOGIN_TYPE)
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        IpdActor actor = ipdPermission.requireInternal();
        deliverableService.download(id, actor, response);
    }
}
