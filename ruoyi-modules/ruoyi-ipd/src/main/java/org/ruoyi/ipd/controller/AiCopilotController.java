package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.common.sse.core.SseErrorEmitter;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.ipd.dto.AiCopilotResp;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.AiCopilotService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AI-P2-3（2026-09-11）：AI 副驾问答端点。
 * <ul>
 *   <li>{@code POST /api/v1/ai-copilot/chat}：同步，注解层 IpdPermissionCode.OPERATION_AI_COPILOT
 *       把住「内部角色可调」，对象级（项目可见性 / 越权）由 service 二次校验；</li>
 *   <li>{@code GET /api/v1/ai-copilot/chat/stream}：SSE 真流式（AI-STRAT-3 / L0-4，2026-09-23）——
 *       走 {@link AiCopilotService#chatStream} + {@link org.ruoyi.ipd.service.ai.AiGateway#stream}
 *       异步 token 推送，逐段送 delta 帧（不再是同步结果分片的伪流式）；</li>
 *   <li>两端的审计 / 越权 / 意图分类 / 上下文注入 / 三件套全在 service 集中处理，Controller 不掺业务；</li>
 *   <li>SSE 端不复读 token 进 URL query——同步端点走 IpdPermission.requireInternal 读 sa-token 上下文即可；
 *       真流式 EventSource 场景再补 IpdSseController 同款的 URL token 模式（本次非阻塞项）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai-copilot")
@RequiredArgsConstructor
public class AiCopilotController {

    /** SSE 连接超时（覆盖最长 chitchat 30s + 流式推送延迟）。 */
    static final long SSE_TIMEOUT_MS = 60_000L;

    private final AiCopilotService service;
    private final IpdPermission ipdPermission;

    /** 复用独立单线程 executor 推 SSE 流（不分业务线程池）；MVP 量级足够。 */
    private static final Executor SSE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ipd-ai-copilot-sse");
        t.setDaemon(true);
        return t;
    });

    /**
     * 同步问答端点：单轮立即返回结果（含 data/sources/token 用量）。
     */
    @PostMapping("/chat")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_AI_COPILOT, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<AiCopilotResp> chat(@Valid @RequestBody AiCopilotReq req) {
        IpdActor actor = ipdPermission.requireInternal();
        return ApiV1Response.ok(service.chat(actor, req));
    }

    /**
     * SSE 真流式问答端点（AI-STRAT-3 / L0-4，2026-09-23）：调 {@link AiCopilotService#chatStream}
     * 走 {@link org.ruoyi.ipd.service.ai.AiGateway#stream} 异步 token 推送，逐段送 delta 帧。
     *
     * <p>事件契约（与旧伪流式一致，前端零改造）：
     * <ul>
     *   <li>{@code event=meta}：首帧（intent / data / sources；真流式时 answer 空、token 0）——前端先渲染结构化数据；</li>
     *   <li>{@code event=delta}：answer 增量 token（真流式多次；意图兜底路径一次整段）；</li>
     *   <li>{@code event=done}：末帧（status=ok + 聚合 tokenPrompt/tokenCompletion/latencyMs）；</li>
     *   <li>{@code event=error}：错误（同步前置 IpdBusinessException 或异步 AI 流式失败时推送）。</li>
     * </ul>
     *
     * @param projectId 可空（与同步端同语义）
     * @param message   必填，URL query 上限 2000（与 DTO @Size 对齐）
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) Long projectId,
                             @RequestParam @jakarta.validation.constraints.NotBlank
                             @jakarta.validation.constraints.Size(max = 2000) String message) {
        // 2026-09-11：入口鉴权异常（requireInternal 抛 IpdBusinessException / NotLoginException）
        // 不再走 advice 返回 JSON——EventSource 收到 JSON 响应同样报 MIME 错误；
        // 改为推 error 帧 + complete()，前端按事件名识别业务错误（event=error）。
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        IpdActor actor;
        try {
            actor = ipdPermission.requireInternal();
        } catch (IpdBusinessException ibe) {
            log.warn("[AI-COPILOT-SSE] auth rejected: code={} msg={}",
                ibe.getErrorCode() == null ? "" : ibe.getErrorCode().getCode(), ibe.getMessage());
            SseErrorEmitter.completeWithError(emitter,
                ibe.getErrorCode() == null ? "AUTH_REQUIRED" : String.valueOf(ibe.getErrorCode().getCode()),
                ibe.getMessage(), log);
            return emitter;
        }
        AiCopilotReq req = new AiCopilotReq(projectId, message, java.util.List.of());
        SSE_EXECUTOR.execute(() -> pushChunks(emitter, actor, req));
        return emitter;
    }

    private void pushChunks(SseEmitter emitter, IpdActor actor, AiCopilotReq req) {
        try {
            service.chatStream(actor, req, new AiCopilotService.CopilotStreamSink() {
                @Override
                public void meta(AiCopilotResp resp) {
                    sendFrame(emitter, "meta", resp);
                }

                @Override
                public void delta(String token) {
                    sendFrame(emitter, "delta", token);
                }

                @Override
                public void done(AiCopilotResp resp) {
                    // R221 对话即填表：FILL_PAGE 意图时 done 帧携 fillPayload（仅非空时加键，Map.of 不容 null）
                    java.util.Map<String, Object> done = new java.util.LinkedHashMap<>();
                    done.put("status", "ok");
                    done.put("tokenPrompt", resp.tokenPrompt());
                    done.put("tokenCompletion", resp.tokenCompletion());
                    done.put("latencyMs", resp.latencyMs());
                    if (resp.fillPayload() != null) {
                        done.put("fillPayload", resp.fillPayload());
                    }
                    sendFrame(emitter, "done", done);
                    emitter.complete();
                }

                @Override
                public void error(String code, String message) {
                    sendFrame(emitter, "error", java.util.Map.of(
                        "code", code == null ? "" : code,
                        "message", message == null ? "" : message));
                    emitter.complete();
                }
            });
        } catch (IpdBusinessException biz) {
            // 同步前置错误（message 空 / 项目不可见）：推 error 帧 + complete（SSE 契约模式 B）
            log.warn("[AI-COPILOT-SSE] stream rejected: code={} msg={}",
                biz.getErrorCode() == null ? "" : biz.getErrorCode().getCode(), biz.getMessage());
            SseErrorEmitter.completeWithError(emitter,
                biz.getErrorCode() == null ? "PARAM_INVALID" : String.valueOf(biz.getErrorCode().getCode()),
                biz.getMessage(), log);
        } catch (Exception e) {
            log.error("[AI-COPILOT-SSE] stream failed: actor={} messageLen={}", actor.id(),
                req.message() == null ? 0 : req.message().length(), e);
            SseErrorEmitter.completeWithError(emitter, "INTERNAL_ERROR", "AI 副驾流式推送失败", log);
        }
    }

    /** 推一帧 SSE；客户端已断（IOException / IllegalStateException）静默吞——与 SseErrorEmitter 同款防御。 */
    private static void sendFrame(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开，静默（不再重复推 error，避免 SIGPIPE 噪声）
        }
    }
}
