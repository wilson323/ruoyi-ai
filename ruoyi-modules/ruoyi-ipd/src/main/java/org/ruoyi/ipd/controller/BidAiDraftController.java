package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.BidAiDraftService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI-P2-2 #1 招标书起草 API（文档生成类）。
 *
 * <p>POST /api/v1/bid-invitations/ai-draft —— 入参 projectId/title/brief（PM 原始需求），
 * 出参起草稿（已登记 AiDocument v1，status=GENERATED 待审核）。权限与 createInvitation
 * （POST /bid-invitations）同码 {@code ipd:project:edit}，不新增权限码不动 catalog。
 *
 * <p>红线：本端点只出草稿，不建招标单、不发布——仍走 createInvitation/publishInvitation
 * 人工流；起草失败异常直通，不出半成品。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BidAiDraftController {

    private final BidAiDraftService bidAiDraftService;
    private final IpdPermission ipdPermission;

    /** 请求体：项目 id + 招标标题 + PM 原始需求（brief）。 */
    public record BidDraftReq(Long projectId, String title, String brief) {}

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/bid-invitations/ai-draft")
    public ApiV1Response<BidAiDraftService.BidDraftView> aiDraft(@RequestBody BidDraftReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(bidAiDraftService.draft(actor,
            req == null ? null : req.projectId(),
            req == null ? null : req.title(),
            req == null ? null : req.brief()));
    }
}
