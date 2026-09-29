package org.ruoyi.chat.poc.kernel;

import org.ruoyi.chat.kernel.KernelScopeKey;

import cn.dev33.satoken.exception.NotLoginException;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.UUID;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.sse.core.SseErrorEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * [PoC G4] 最小 SSE 出口适配样例:HarnessAgent.streamEvents(AgentEvent 流) → text/event-stream。
 *
 * <p>刻意不接现有前端契约(不复用 /api/v1/chat 协议、不动既有 SSE 出口),纯 PoC 验证:
 * okhttp 5.3.2 收敛后流式链路无类冲突、事件可增量推送。W-cutover 时删除或改写为正式出口。
 *
 * <p><b>ADR-0075 D9 收口(2026-09-29)</b>:①装配面默认关闭(chat.kernel.poc.enabled,
 * 与 chat.kernel.agentscope.enabled 同族开关),PoC 路由不再流入正式面;②userId 恒从登录会话
 * 推导(LoginHelper),请求参数自报已移除,未登录 fail-closed——没有"默认 U1"式降级。
 * 残留自报参数(projectId/agentId/sessionId)仅供 PoC 接线验证,cutover 时按 W1 裁决收口。
 *
 * <p>用法(需开关 chat.kernel.poc.enabled=true + 登录态):
 * GET /poc/kernel/chat/stream?text=...&projectId=P1&agentId=emp-a1&sessionId=S1
 */
@RestController
@RequestMapping("/poc/kernel")
@ConditionalOnProperty(name = "chat.kernel.poc.enabled", havingValue = "true", matchIfMissing = false)
public class PocSseController {

    private static final Logger log = LoggerFactory.getLogger(PocSseController.class);

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam("text") String text,
            @RequestParam(value = "projectId", defaultValue = "P1") String projectId,
            @RequestParam(value = "agentId", defaultValue = "emp-a1") String agentId,
            @RequestParam(value = "sessionId", defaultValue = "") String sessionId) {
        String sid = sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            // 身份可信源(SEC-API-01/D9):userId 恒从登录会话推导,请求参数自报已移除
            String userId = resolveUserId();
            // 四维收口:所有内核调用点只经 KernelScopeKey
            KernelScopeKey.Scope scope = KernelScopeKey.of(projectId, userId, agentId, sid);
            emitter.send(SseEmitter.event().name("scope").data(scope.slotId()));
            Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
            PocKernelSupport.agent(agentId)
                    .streamEvents(msg, scope.toRuntimeContext())
                    .subscribe(
                            ev -> sendEvent(emitter, ev),
                            err -> SseErrorEmitter.completeWithError(
                                emitter, "KERNEL_STREAM_ERROR", String.valueOf(err), log),
                            emitter::complete);
        } catch (Exception e) {
            SseErrorEmitter.completeWithError(emitter, "KERNEL_ERROR", String.valueOf(e), log);
        }
        return emitter;
    }

    /**
     * 身份可信源唯一入口(D9/SEC-API-01):userId 恒从登录会话推导,禁止请求参数自报。
     * 未登录 fail-closed——经 stream() 的 catch 转 KERNEL_ERROR 帧,不给默认身份。
     */
    private String resolveUserId() {
        try {
            return String.valueOf(LoginHelper.getUserId());
        } catch (NotLoginException e) {
            throw new IllegalStateException(
                    "D9: userId 必须来自登录会话,请携带有效 token 调用(请求参数自报已移除)", e);
        }
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event) {
        try {
            if (event instanceof TextBlockDeltaEvent delta) {
                emitter.send(SseEmitter.event().name("text_delta").data(delta.getDelta()));
            } else {
                emitter.send(SseEmitter.event().name("event").data(event.getType().name()));
            }
        } catch (Exception e) {
            SseErrorEmitter.completeWithError(emitter, "STREAM_SEND_ERROR", String.valueOf(e), log);
        }
    }
}
