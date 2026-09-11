package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.SopTemplate;
import org.ruoyi.ipd.domain.SopTemplateInstance;
import org.ruoyi.ipd.dto.SopTemplateListItem;
import org.ruoyi.ipd.dto.SopTemplateSaveReq;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.SopTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * P1-3.3 SOP 模板接口（/api/v1/sop-templates，BR-IPD-07）——actionCode 维度版本链 + 实例化快照。
 * <ul>
 *   <li>读：list(?actionCode) / current(?actionCode) / get —— 内部角色（ipd:sop-template:list）</li>
 *   <li>写：copy / update / publish / revert —— 仅超管（ipd:sop-template:edit）</li>
 *   <li>实例化：instantiate / listInstances —— MARKET_PM/RD_PM/GROUP_LEADER</li>
 * </ul>
 * <p>版本机：DRAFT --publish--> PUBLISHED --被新版本替代--> ARCHIVED（AC-IPD-27：发布只影响此后实例化）。
 */
@RestController
@RequestMapping("/api/v1/sop-templates")
@RequiredArgsConstructor
public class SopTemplateController {

    private final SopTemplateService sopTemplateService;
    private final IpdPermission ipdPermission;

    /** 版本列表（?actionCode 必填；version 倒序；轻量视图不含正文，仅 contentLen） */
    @GetMapping
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<SopTemplateListItem>> list(@RequestParam String actionCode) {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(sopTemplateService.listByActionCode(actionCode));
    }

    /** 当前生效 SOP（PUBLISHED + effectiveTo IS NULL 最新版；含正文；无则 404/50001） */
    @GetMapping("/current")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<SopTemplate> current(@RequestParam String actionCode) {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(sopTemplateService.currentForAction(actionCode));
    }

    /** 版本详情（任意状态；含正文；在研项目按快照 sopId 取历史版本也走本端点） */
    @GetMapping("/{id}")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<SopTemplate> get(@PathVariable Long id) {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(sopTemplateService.getById(id));
    }

    /** 复制 PUBLISHED/ARCHIVED 为新草稿（仅超管；已有草稿时 409） */
    @PostMapping("/{id}/copy")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE_EDIT, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<SopTemplate> copy(@PathVariable Long id) {
        return ApiV1Response.ok(sopTemplateService.copyToDraft(id, ipdPermission.requireAdmin()));
    }

    /** 编辑草稿（仅 DRAFT 可改；白名单 title/content；仅超管） */
    @PostMapping("/{id}/update")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE_EDIT, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<SopTemplate> update(@PathVariable Long id,
                                             @RequestBody SopTemplateSaveReq req) {
        return ApiV1Response.ok(sopTemplateService.updateDraft(id, req, ipdPermission.requireAdmin()));
    }

    /** 发布草稿（旧 PUBLISHED 自动 ARCHIVED；仅影响此后实例化的项目；仅超管） */
    @PostMapping("/{id}/publish")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE_EDIT, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<SopTemplate> publish(@PathVariable Long id) {
        return ApiV1Response.ok(sopTemplateService.publishDraft(id, ipdPermission.requireAdmin()));
    }

    /** 历史恢复：ARCHIVED 版本复制为新草稿（走正常发布流程后才重新生效；仅超管） */
    @PostMapping("/{id}/revert")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE_EDIT, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<SopTemplate> revert(@PathVariable Long id) {
        return ApiV1Response.ok(sopTemplateService.revertToDraft(id, ipdPermission.requireAdmin()));
    }

    /** 实例化模板 → 生成 SopTemplateInstance 快照（MARKET_PM/RD_PM/GROUP_LEADER） */
    @PostMapping("/{templateId}/instantiate")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<SopTemplateInstance> instantiate(@PathVariable Long templateId,
                                                          @RequestParam Long projectId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(sopTemplateService.instantiate(templateId, projectId, actor));
    }

    /** 按项目列出实例快照 */
    @GetMapping("/instances")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_SOP_TEMPLATE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<SopTemplateInstance>> listInstances(@RequestParam Long projectId) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(sopTemplateService.listInstancesByProject(projectId, actor));
    }
}
