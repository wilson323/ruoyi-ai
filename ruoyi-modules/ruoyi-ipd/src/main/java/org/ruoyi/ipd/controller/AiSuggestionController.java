package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.dto.AiSuggestReq;
import org.ruoyi.ipd.dto.AiSuggestResp;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.AiSuggestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R227-C1（AI-FUSION Layer 2，2026-09-26）：域内 AI 建议统一端点（方案 §5.1）。
 * <ul>
 *   <li>{@code POST /api/v1/ai/suggest}：单入口多场景，scene 白名单分发；</li>
 *   <li>权限码复用 {@code OPERATION_AI_COPILOT}（"ipd:ai-copilot:chat"）——同为
 *       "内部角色可读 AI"语义，不新增权限码避免前后端权限注册面双扩；
 *       对象级越权（项目可见性 / gate 评审归属）由 service 二次校验（BR-AI-05）；</li>
 *   <li>Controller 不掺业务：审计 / 降级 / 上下文全在 {@link AiSuggestionService}。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiSuggestionController {

    private final AiSuggestionService service;
    private final IpdPermission ipdPermission;

    @PostMapping("/suggest")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<AiSuggestResp> suggest(@Valid @RequestBody AiSuggestReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(service.suggest(actor, req));
    }
}
