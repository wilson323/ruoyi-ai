package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1Response;
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
 *   <li>{@code GET /api/v1/ai-copilot/chat/stream}：SSE 流式——MVP 把同步结果分片推达成观感，
 *       避免调研 Langchain4j streaming API 阻塞 MVP 上线；真流式 AI-STRAT-3 留续；</li>
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

    /** 流式分片大小（字符）——MVP 取 30 字 / 50ms 间隔，肉眼可见逐字出现。 */
    static final int SSE_CHUNK_SIZE = 30;
    /** 流式分片间隔（毫秒）。 */
    static final long SSE_CHUNK_INTERVAL_MS = 50L;
    /** SSE 连接超时（默认 30s 已能覆盖最长 chitchat 30s+分片延迟）。 */
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
     * SSE 流式问答端点：MVP 实现 = 同步调 {@link AiCopilotService#chat} 后把 answer 分片推流，
     * 推送节奏 {@link #SSE_CHUNK_SIZE} 字符 / {@link #SSE_CHUNK_INTERVAL_MS} 毫秒，
     * 末帧 {@code [DONE]} 表示服务端生成结束。
     *
     * <p>事件契约：
     * <ul>
     *   <li>{@code event=meta}：首帧（intent / data / sources / token / latencyMs）——前端用来先渲染结构化数据；</li>
     *   <li>{@code event=delta}：answer 分片（多次）；</li>
     *   <li>{@code event=done}：末帧（status=ok | fail）；</li>
     *   <li>{@code event=error}：业务异常（仅在 service 抛 IpdBusinessException 时一次推送）。</li>
     * </ul>
     *
     * @param projectId 可空（与同步端同语义）
     * @param message   必填，URL query 上限 2000（与 DTO @Size 对齐）
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) Long projectId,
                             @RequestParam @jakarta.validation.constraints.NotBlank
                             @jakarta.validation.constraints.Size(max = 2000) String message) {
        IpdActor actor = ipdPermission.requireInternal();
        AiCopilotReq req = new AiCopilotReq(projectId, message, java.util.List.of());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        SSE_EXECUTOR.execute(() -> pushChunks(emitter, actor, req));
        return emitter;
    }

    private void pushChunks(SseEmitter emitter, IpdActor actor, AiCopilotReq req) {
        try {
            AiCopilotResp resp = service.chat(actor, req);
            // 1) meta 帧：先送结构化 + 元数据（前端可立即渲染 data 列表 + token）
            emitter.send(SseEmitter.event().name("meta").data(resp));
            // 2) delta 帧：answer 按 30 字符/片分推
            String answer = resp.answer() == null ? "" : resp.answer();
            for (int i = 0; i < answer.length(); i += SSE_CHUNK_SIZE) {
                int end = Math.min(answer.length(), i + SSE_CHUNK_SIZE);
                emitter.send(SseEmitter.event().name("delta").data(answer.substring(i, end)));
                if (end < answer.length()) {
                    try {
                        Thread.sleep(SSE_CHUNK_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // 3) done 帧：服务端生成结束
            emitter.send(SseEmitter.event().name("done").data(java.util.Map.of("status", "ok")));
            emitter.complete();
        } catch (org.ruoyi.ipd.common.IpdBusinessException biz) {
            // 业务异常：直接推 error 帧，让前端按 status=ok 但带 error 事件渲染（业务兜底）
            try {
                emitter.send(SseEmitter.event().name("error").data(java.util.Map.of(
                    "code", biz.getErrorCode() == null ? "" : String.valueOf(biz.getErrorCode().getCode()),
                    "message", biz.getMessage() == null ? "" : biz.getMessage())));
            } catch (IOException ignored) {
                // 客户端已断
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("[AI-COPILOT-SSE] push failed: actor={} messageLen={}", actor.id(),
                req.message() == null ? 0 : req.message().length(), e);
            try {
                emitter.send(SseEmitter.event().name("error").data(java.util.Map.of(
                    "code", "INTERNAL_ERROR",
                    "message", "AI 副驾流式推送失败")));
            } catch (IOException ignored) {
                // 客户端已断
            }
            emitter.completeWithError(e);
        }
    }
}
