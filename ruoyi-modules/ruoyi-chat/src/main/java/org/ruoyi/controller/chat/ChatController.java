package org.ruoyi.controller.chat;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.sse.core.SseErrorEmitter;
import org.ruoyi.service.chat.impl.ChatServiceFacade;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


/**
 * 聊天管理
 *
 * @author ageerle@163.com
 * @date 2023-03-01
 */
@Controller
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatController {

    private final ChatServiceFacade chatService;

    /**
     * 聊天 SSE 流式端点。
     *
     * <p>2026-09-11：入口鉴权异常不再走 advice 返回 JSON（EventSource / fetch + ReadableStream
     * 收到 JSON 都无法解析为 SSE 帧），改为推 error 帧 + complete()。
     */
    @PostMapping("/send")
    @ResponseBody
    public SseEmitter sseChat(@RequestBody @Valid ChatRequest chatRequest) {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            return chatService.sseChat(chatRequest);
        } catch (Exception e) {
            log.warn("[CHAT-SSE] sseChat rejected: {}", e.getMessage());
            SseErrorEmitter.completeWithError(emitter, "AUTH_REQUIRED", e.getMessage(), log);
            return emitter;
        }
    }

}
