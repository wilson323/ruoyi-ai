package org.ruoyi.ipd.service.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.service.AiModelBudgetService;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI-STRAT-2 统一 AI 调用层（2026-09-10）：IPD 生成主链从裸 HttpClient（{@link AiChatClient}）
 * 迁 Langchain4j OpenAI 兼容 {@link OpenAiChatModel}，终结「IPD 自写单轮 vs 平台 Langchain4j」两套并存。
 * <ul>
 *   <li>同签名：{@link #chat(AiTestConfig, String, Integer, BigDecimal)} 与旧 AiChatClient.chat
 *       完全一致（含返回 {@link AiChatResult}），调用方与测试桩零语义迁移；</li>
 *   <li>安全契约原样继承：① SSRF 前置校验复用 {@link AiChatClient#ssrfCheck}（内网黑名单 +
 *       DNS rebinding 双解析 + allowlist，SEC P1-3/P1-15 + R-NEW S-6 唯一实现不复制）；
 *       ② apiKey 仅进 builder 内存消费，不进日志/审计/异常消息（BR-AI-PROV-02）；
 *       ③ prompt/响应原文一律不落日志（BR-AI-04），观测走长度分桶 + SHA-256 短指纹；</li>
 *   <li>错误码白名单与旧链同源：AUTH_FAILED / HTTP_n / TIMEOUT / UNREACHABLE / EMPTY_RESPONSE /
 *       UNSUPPORTED_PROTOCOL——Langchain4j JDK 客户端异常（HttpException/TimeoutException/
 *       IOException 包装 RuntimeException）逐类映射，{@link #mapFailure} 单一出口可表驱动测试；</li>
 *   <li>每次调用按 AiModelConfig 动态构建 model 实例（运营可改配置；构建为轻量对象包装，
 *       生成链已有 MAX_CONCURRENT=3 限流闸，QPS 量级无池化必要）；</li>
 *   <li>C2-3（2026-09-29）：预算预占→结算与用量落账挂本层——cfg 携带 {@link AiCallScope} 时生效，
 *       额度拒绝 fail-closed 不出站（BUDGET_EXCEEDED）；限流与业务审计仍在调用方服务。</li>
 * </ul>
 * <p>后续 AI 卡（SSE 流式/重试/结构化输出/RAG embedding）一律挂本层，禁止再裸写 HTTP 调模型。
 */
@Slf4j
@Component
public class AiGateway {

    private final AiChatClient legacyClient;
    /** C2-3 预算预占→结算面（Spring 恒注入；测试口可为 null = 不限额）。 */
    private final AiModelBudgetService budgetService;
    /** C2-3 用量账本面（Spring 恒注入；测试口可为 null = 不落账）。 */
    private final AiModelUsageLedgerService usageLedgerService;
    private final PromptLenBucketLogger bucketLogger = new PromptLenBucketLogger();
    /** 业务时钟注入（裸时钟守卫禁 System.currentTimeMillis，见 治理/测试编写三禁）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    @Autowired
    public AiGateway(AiChatClient legacyClient, AiModelBudgetService budgetService,
                     AiModelUsageLedgerService usageLedgerService) {
        this.legacyClient = legacyClient;
        this.budgetService = budgetService;
        this.usageLedgerService = usageLedgerService;
    }

    /** 测试口：无预算/账本面（cfg.scope 为 null 的调用本就不记账；既有单测构造零迁移）。 */
    AiGateway(AiChatClient legacyClient) {
        this(legacyClient, null, null);
    }

    /** 测试口：注入固定时钟（同 AiGenerationService.withClock 惯例）。 */
    AiGateway withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /**
     * 单轮生成（同步）。cfg 复用 {@link AiTestConfig}（provider/endpoint/key/model/timeoutMs）；
     * maxTokens/temperature 为 null 时不携带（由服务端默认值决定）。
     * SSRF 校验失败抛 {@link IpdBusinessException}（与旧链同语义直通 controller 层）。
     */
    public AiChatResult chat(AiTestConfig cfg, String prompt, Integer maxTokens,
                              BigDecimal temperature) {
        long start = clock.millis();
        // SSRF 前置（旧链同款：黑名单 + DNS rebinding 双解析 + allowlist）
        legacyClient.ssrfCheck(cfg.baseUrl());
        int promptLen = prompt == null ? 0 : prompt.length();
        // C2-3 预算预占（fail-closed：拒绝即不出站）；无记账面 pre=0 直通过
        long pre = preoccupy(cfg, prompt, maxTokens, start);
        if (pre < 0) {
            return AiChatResult.fail("BUDGET_EXCEEDED", "模型月度预算不足", elapsed(start));
        }
        try {
            OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(stripTrailingSlash(cfg.baseUrl()))
                .apiKey(cfg.apiKey())
                .modelName(cfg.modelName())
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .temperature(temperature == null ? null : temperature.doubleValue())
                .maxTokens(maxTokens)
                .build();
            ChatResponse resp = model.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build());
            String content = resp == null || resp.aiMessage() == null ? null : resp.aiMessage().text();
            if (content == null || content.isBlank()) {
                logAiRequest(cfg, promptLen, null);
                settleAndRecord(cfg, pre, 0, 0, "EMPTY_RESPONSE", elapsed(start));
                return AiChatResult.fail("EMPTY_RESPONSE", "模型返回空内容", elapsed(start));
            }
            TokenUsage usage = resp.metadata() == null ? null : resp.metadata().tokenUsage();
            int promptTokens = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
            int completionTokens = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
            logAiRequest(cfg, promptLen, content.length());
            settleAndRecord(cfg, pre, promptTokens, completionTokens, "ok", elapsed(start));
            return AiChatResult.ok(content, promptTokens, completionTokens, elapsed(start));
        } catch (Exception e) {
            AiChatResult fail = mapFailure(e, elapsed(start));
            logAiRequest(cfg, promptLen, null);
            log.warn("[AI] gateway fail: model={} promptLenBucket={} errorCode={} latencyMs={} promptHash={}",
                cfg.modelName(), PromptLenBucket.of(promptLen).label(), fail.errorCode(),
                fail.latencyMs(), AiChatClient.shortHash(prompt));
            settleAndRecord(cfg, pre, 0, 0, "FAIL:" + fail.errorCode(), fail.latencyMs());
            return fail;
        }
    }

    /**
     * 批量向量化（AI-STRAT-1，同步）：OpenAI 兼容 {@code POST {base}/embeddings}，
     * 返回与输入等长的向量列表（调用方按序号对位）。安全/错误契约与 {@link #chat}
     * 同源：SSRF 前置 + apiKey 不落日志 + 白名单错误码（mapFailure 同一出口）。
     * 失败返回 {@code null}（调用方降级，不重试）——与 chat 返回 AiChatResult.fail
     * 不同：embedding 是增强链路，失败语义只需「有没有」，无需错误细节上拋。
     */
    public List<float[]> embed(AiTestConfig cfg, List<String> texts) {
        long start = clock.millis();
        legacyClient.ssrfCheck(cfg.baseUrl());
        try {
            OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .baseUrl(stripTrailingSlash(cfg.baseUrl()))
                .apiKey(cfg.apiKey())
                .modelName(cfg.modelName())
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .build();
            List<TextSegment> segments = new ArrayList<>(texts.size());
            for (String t : texts) {
                segments.add(t == null ? TextSegment.from("") : TextSegment.from(t));
            }
            Response<List<Embedding>> resp = model.embedAll(segments);
            List<Embedding> content = resp == null ? null : resp.content();
            if (content == null || content.size() != texts.size()) {
                log.warn("[AI] embed size mismatch: expect={} actual={}",
                    texts.size(), content == null ? -1 : content.size());
                return null;
            }
            List<float[]> out = new ArrayList<>(content.size());
            for (Embedding e : content) {
                out.add(e.vector());
            }
            log.info("[AI] embed ok: model={} chunks={} latencyMs={} endpointHost={}",
                cfg.modelName(), texts.size(), elapsed(start), hostOf(cfg.baseUrl()));
            return out;
        } catch (Exception e) {
            AiChatResult fail = mapFailure(e, elapsed(start));
            log.warn("[AI] embed fail: model={} chunks={} errorCode={} latencyMs={}",
                cfg.modelName(), texts.size(), fail.errorCode(), fail.latencyMs());
            return null;
        }
    }

    /**
     * 流式生成（AI-STRAT-3 / L0-4 SSE 真流式，2026-09-23）：Langchain4j {@link OpenAiStreamingChatModel}
     * 异步 token-by-token 推送，调用方通过 {@link StreamHandler} 接收 onDelta/onComplete/onError。
     *
     * <p>安全契约与 {@link #chat} 同源：
     * <ul>
     *   <li>SSRF 前置校验（黑名单 + DNS rebinding + allowlist），失败同步抛 {@link IpdBusinessException}；</li>
     *   <li>apiKey 仅进 builder 内存消费，不进日志/审计/异常消息（BR-AI-PROV-02）；</li>
     *   <li>prompt/响应原文一律不落日志（BR-AI-04），观测走长度分桶 + SHA-256 短指纹；</li>
     *   <li>错误码白名单与 chat 同源（{@link #mapFailure} 单一出口）。</li>
     * </ul>
     *
     * <p>与 {@link #chat} 区别：chat 同步返回 {@link AiChatResult}（含完整 content）；stream 异步推送，
     * 调用方在 {@code onDelta} 逐段收 token、{@code onComplete} 收聚合 tokenUsage（OpenAI SSE 末帧 usage）、
     * {@code onError} 收白名单错误码——本方法无返回值（异步语义）。
     *
     * @param cfg         模型配置（provider/endpoint/key/model/timeoutMs）
     * @param prompt      用户输入原文（不落日志，仅记长度分桶 + 短指纹）
     * @param maxTokens   最大生成 token 数（null = 服务端默认）
     * @param temperature 采样温度（null = 服务端默认）
     * @param handler     流式回调（onDelta 收增量 token、onComplete 收 tokenUsage、onError 收白名单错误码）
     */
    public void stream(AiTestConfig cfg, String prompt, Integer maxTokens,
                       BigDecimal temperature, StreamHandler handler) {
        long start = clock.millis();
        // SSRF 前置（与 chat 同款；失败同步抛 IpdBusinessException 直通调用方）
        legacyClient.ssrfCheck(cfg.baseUrl());
        int promptLen = prompt == null ? 0 : prompt.length();
        // C2-3 预算预占（与 chat 同款 fail-closed）；拒绝走 onError 不出站
        long pre = preoccupy(cfg, prompt, maxTokens, start);
        if (pre < 0) {
            handler.onError(AiChatResult.fail("BUDGET_EXCEEDED", "模型月度预算不足", elapsed(start)));
            return;
        }
        OpenAiStreamingChatModel model;
        try {
            model = OpenAiStreamingChatModel.builder()
                .baseUrl(stripTrailingSlash(cfg.baseUrl()))
                .apiKey(cfg.apiKey())
                .modelName(cfg.modelName())
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .temperature(temperature == null ? null : temperature.doubleValue())
                .maxTokens(maxTokens)
                .build();
        } catch (Exception e) {
            // builder 构建失败（配置非法等）：走 onError，不抛（异步语义统一）
            AiChatResult fail = mapFailure(e, elapsed(start));
            log.warn("[AI] gateway stream build fail: model={} promptLenBucket={} errorCode={}",
                cfg.modelName(), PromptLenBucket.of(promptLen).label(), fail.errorCode());
            settleAndRecord(cfg, pre, 0, 0, "FAIL:" + fail.errorCode(), fail.latencyMs());
            handler.onError(fail);
            return;
        }
        // Langchain4j 1.17.2：StreamingChatModel.chat(ChatRequest, StreamingChatResponseHandler)
        // 异步推送——onPartialResponse 每段一次，onCompleteResponse 聚合（含 tokenUsage），onError 失败。
        model.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build(),
            new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    // 透传增量 token 给调用方（SseEmitter delta 帧）；空段跳过
                    if (partialResponse != null && !partialResponse.isEmpty()) {
                        handler.onDelta(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    long latency = elapsed(start);
                    TokenUsage usage = response == null ? null : response.tokenUsage();
                    int promptTokens = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
                    int completionTokens = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
                    logAiStreamComplete(cfg, promptLen, response, latency);
                    settleAndRecord(cfg, pre, promptTokens, completionTokens, "ok", latency);
                    handler.onComplete(promptTokens, completionTokens, latency);
                }

                @Override
                public void onError(Throwable error) {
                    // 错误映射到白名单错误码（mapFailure 单一出口），透传给调用方
                    AiChatResult fail = mapFailure(error, elapsed(start));
                    log.warn("[AI] gateway stream fail: model={} promptLenBucket={} errorCode={} latencyMs={} promptHash={}",
                        cfg.modelName(), PromptLenBucket.of(promptLen).label(), fail.errorCode(),
                        fail.latencyMs(), AiChatClient.shortHash(prompt));
                    settleAndRecord(cfg, pre, 0, 0, "FAIL:" + fail.errorCode(), fail.latencyMs());
                    handler.onError(fail);
                }
            });
    }

    /** 流式回调（项目级，解耦 Langchain4j 类型；调用方 = AiCopilotService）。 */
    public interface StreamHandler {
        /** 每收到一段增量 token 触发（可能多次；调用方推 SSE delta 帧）。 */
        void onDelta(String token);

        /** 流正常结束：聚合 token 用量 + 端到端延迟（调用方推 done 帧 + 审计 streaming）。 */
        void onComplete(int promptTokens, int completionTokens, long latencyMs);

        /** 流失败：白名单错误码（mapFailure 同源；调用方推 error 帧 + 审计 FAIL）。 */
        void onError(AiChatResult failure);
    }

    /** 流式完成观测日志：长度分桶 + tokenUsage 聚合（BR-AI-04 prompt 原文不落）。 */
    private void logAiStreamComplete(AiTestConfig cfg, int promptLen, ChatResponse response, long latencyMs) {
        boolean transitioned = bucketLogger.record(promptLen);
        TokenUsage usage = response == null ? null : response.tokenUsage();
        int promptTokens = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int completionTokens = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        AiMessage msg = response == null ? null : response.aiMessage();
        int contentLen = msg == null || msg.text() == null ? 0 : msg.text().length();
        log.info("[AI] gateway stream complete: model={} promptLenBucket={} bucketTransitioned={} contentLen={} promptTokens={} completionTokens={} latencyMs={} endpointHost={}",
            cfg.modelName(), PromptLenBucket.of(promptLen).label(), transitioned,
            contentLen, promptTokens, completionTokens, latencyMs, hostOf(cfg.baseUrl()));
    }

    /**
     * C2-3 预算预占：cfg 携带 {@link AiCallScope} 时先占额度再出站（fail-closed，好过超卖）。
     * 返回预占 token 数（0 = 无记账面直通过）；-1 = 拒绝（已尽力落 REJECTED 账，调用方不得出站）。
     */
    private long preoccupy(AiTestConfig cfg, String prompt, Integer maxTokens, long start) {
        AiCallScope scope = cfg.scope();
        if (scope == null) {
            return 0L;
        }
        long estimate = estimateTokens(prompt, maxTokens);
        if (budgetService == null) {
            return estimate; // 测试口无预算面 = 不限额（生产 Spring 恒注入非空）
        }
        try {
            if (budgetService.preoccupy(scope.modelConfigId(), estimate)) {
                return estimate;
            }
        } catch (Exception e) {
            // fail-closed：预算面故障按拒绝处理；账本状态区分 CHECK_FAILED
            log.warn("[AI] budget preoccupy error, reject fail-closed: scene={} estimateTokens={} err={}",
                scope.scene(), estimate, e.getClass().getSimpleName());
            recordBestEffort(scope, 0, 0, 0, "REJECTED:BUDGET_CHECK_FAILED");
            return -1L;
        }
        log.warn("[AI] budget exceeded: model={} scene={} estimateTokens={}",
            cfg.modelName(), scope.scene(), estimate);
        recordBestEffort(scope, 0, 0, elapsed(start), "REJECTED:BUDGET");
        return -1L;
    }

    /** C2-3 结算+落账（恒执行：失败回冲全额预占、超支差额由预算服务披露不吞账）；无记账面 no-op。 */
    private void settleAndRecord(AiTestConfig cfg, long preoccupied, int promptTokens, int completionTokens,
                                 String status, long latencyMs) {
        AiCallScope scope = cfg.scope();
        if (scope == null) {
            return;
        }
        if (budgetService != null) {
            try {
                budgetService.settle(scope.modelConfigId(), preoccupied,
                    (long) promptTokens + completionTokens);
            } catch (Exception e) {
                // 记账面故障不翻转调用结果（生成已发生）但必须出声；预占回冲缺失留月度对账
                log.warn("[AI] budget settle error: modelConfigId={} pre={} err={}",
                    scope.modelConfigId(), preoccupied, e.getClass().getSimpleName());
            }
        }
        recordBestEffort(scope, promptTokens, completionTokens, latencyMs, status);
    }

    /** 落账尽力而为：账本故障只告警不翻转调用结果（账本=事后审计面，非事务性约束）。 */
    private void recordBestEffort(AiCallScope scope, int promptTokens, int completionTokens,
                                  long latencyMs, String status) {
        if (usageLedgerService == null) {
            return;
        }
        try {
            usageLedgerService.recordUsage(scope.modelConfigId(), scope.actorId(), scope.scene(),
                promptTokens, completionTokens, latencyMs, status, MDC.get("traceId"));
        } catch (Exception e) {
            log.warn("[AI] usage ledger record error: modelConfigId={} status={} err={}",
                scope.modelConfigId(), status, e.getClass().getSimpleName());
        }
    }

    /** token 预估（预占口径）：≈4 字符/token + maxTokens 上限预算；结算以真实 usage 为准。 */
    static long estimateTokens(String prompt, Integer maxTokens) {
        int chars = prompt == null ? 0 : prompt.length();
        long estimate = (chars + 3) / 4 + (maxTokens == null || maxTokens <= 0 ? 0 : maxTokens.longValue());
        return Math.max(1L, estimate);
    }

    /**
     * 异常 → 白名单错误码单一映射口（package-private 供表驱动单测）。
     * Langchain4j JDK 客户端契约（1.17.2 JdkHttpClient 源码实证）：
     * 非 2xx → HttpException(statusCode,body)；HttpTimeoutException → TimeoutException；
     * ConnectException/UnknownHostException 等 IOException → RuntimeException 包装（走 cause 解链）。
     * C2-3 实测：JDK HttpClient 连接拒绝链为「ConnectException → 包装 ConnectException →
     * ClosedChannelException」多层，root 是 ClosedChannelException——故连接类判定按全链命中
     * （任一层 ConnectException/UnknownHostException 即 UNREACHABLE），不能只看 root。
     */
    static AiChatResult mapFailure(Throwable e, long latencyMs) {
        if (e instanceof TimeoutException) {
            return AiChatResult.fail("TIMEOUT", "gateway: timeout", latencyMs);
        }
        if (e instanceof HttpException he) {
            int code = he.statusCode();
            if (code == 401 || code == 403) {
                return AiChatResult.fail("AUTH_FAILED", "HTTP " + code, latencyMs);
            }
            return AiChatResult.fail("HTTP_" + code, "HTTP " + code, latencyMs);
        }
        Throwable connect = findInChain(e, java.net.ConnectException.class, java.net.UnknownHostException.class);
        if (connect != null) {
            return AiChatResult.fail("UNREACHABLE",
                "connect: " + connect.getClass().getSimpleName(), latencyMs);
        }
        if (findInChain(e, java.net.http.HttpTimeoutException.class) != null) {
            return AiChatResult.fail("TIMEOUT", "gateway: timeout", latencyMs);
        }
        return AiChatResult.fail("UNSUPPORTED_PROTOCOL",
            "gateway: " + e.getClass().getSimpleName(), latencyMs);
    }

    /** 全链扫描：返回第一个命中指定类型（isInstance）的异常；找不到返回 null（自引用 cause 不死循环）。 */
    private static Throwable findInChain(Throwable e, Class<?>... types) {
        Throwable cur = e;
        while (cur != null) {
            for (Class<?> t : types) {
                if (t.isInstance(cur)) {
                    return cur;
                }
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return null;
    }

    /** 观测日志：长度分桶（R-NEW S-7 消指纹）+ prompt 短指纹，原文不落（BR-AI-04）。 */
    private void logAiRequest(AiTestConfig cfg, int promptLen, Integer contentLen) {
        boolean transitioned = bucketLogger.record(promptLen);
        log.info("[AI] gateway request: model={} promptLenBucket={} bucketTransitioned={} contentLen={} endpointHost={}",
            cfg.modelName(), PromptLenBucket.of(promptLen).label(), transitioned,
            contentLen == null ? -1 : contentLen, hostOf(cfg.baseUrl()));
    }

    private long elapsed(long start) {
        return Math.max(0, clock.millis() - start);
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
