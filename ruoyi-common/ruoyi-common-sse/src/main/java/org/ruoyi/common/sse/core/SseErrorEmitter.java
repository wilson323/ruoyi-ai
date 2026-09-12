package org.ruoyi.common.sse.core;

import org.slf4j.Logger;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * SSE 端点统一错误帧推送工具（2026-09-11）。
 *
 * <p>背景：SSE 协议要求 200 + {@code Content-Type: text/event-stream}；浏览器
 * EventSource 收到任何「非 SSE 形态」（200 + 空体 / 200 + JSON / 401 / 403 / 500）
 * 都会触发「MIME type ... is not "text/event-stream"」错误并盲目重连。
 *
 * <p>SSE 端点的「拒绝 / 失败」必须通过 push {@code event=error} 帧 +
 * {@link SseEmitter#complete()} 收尾实现，而非依赖 HTTP 状态码（EventSource
 * 拿不到状态码）；advice 层返回的 JSON 401/403/500 对 EventSource 同样是 MIME 错误。
 *
 * <p>用法（控制器同步线程 / 异步线程，统一）：
 * <pre>{@code
 *     SseEmitter emitter = new SseEmitter(timeout);
 *     try {
 *         actor = requireInternal();          // 抛 IpdBusinessException / NotLoginException
 *     } catch (Exception e) {
 *         SseErrorEmitter.completeWithError(emitter, "AUTH_REQUIRED", e.getMessage(), log);
 *         return emitter;
 *     }
 *     executor.execute(() -> {
 *         try {
 *             ...
 *         } catch (Exception e) {
 *             SseErrorEmitter.completeWithError(emitter, "INTERNAL_ERROR", "流式推送失败", log);
 *         }
 *     });
 *     return emitter;
 * }</pre>
 *
 * <p>事件契约与 {@code AiCopilotController.pushChunks} 对齐：{@code event=error}，
 * data 为 {@code Map{"code", "message"}}，前端按事件名识别业务错误。
 */
public final class SseErrorEmitter {

    private SseErrorEmitter() {}

    /**
     * 推送 error 帧并完成连接。
     *
     * <p>吞掉 {@link IllegalStateException}（emitter 已 complete 的并发场景）与
     * {@link IOException}（客户端断开），仅 debug 日志——错误帧推送失败对调用方
     * 不应再抛异常（异常路径本身就在处理错误）。
     *
     * @param emitter SseEmitter 实例；为 null 时 no-op（防御性）
     * @param code    业务错误码字符串；null 兜底为 {@code "INTERNAL_ERROR"}
     * @param message 错误消息；null 兜底为空串
     * @param log     控制器 logger；为 null 时不打印客户端断开日志
     */
    public static void completeWithError(SseEmitter emitter, String code, String message, Logger log) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of(
                "code", code == null ? "INTERNAL_ERROR" : code,
                "message", message == null ? "" : message)));
        } catch (IllegalStateException alreadyCompleted) {
            // emitter 已被关闭（并发 / 已 complete），吞掉
            debugLog(log, "[SSE] emitter already completed when sending error frame");
        } catch (IOException clientGone) {
            debugLog(log, "[SSE] client disconnected before error frame sent");
        }
        try {
            emitter.complete();
        } catch (IllegalStateException alreadyCompleted) {
            // 同上
        }
    }

    private static void debugLog(Logger log, String msg) {
        if (log != null && log.isDebugEnabled()) {
            log.debug(msg);
        }
    }
}