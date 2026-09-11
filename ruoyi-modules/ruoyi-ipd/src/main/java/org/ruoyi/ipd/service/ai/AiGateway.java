package org.ruoyi.ipd.service.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.IpdBusinessException;
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
 *   <li>预算/限流/审计仍在 AiGenerationService（本层只管「调模型」，不管「管额度」）。</li>
 * </ul>
 * <p>后续 AI 卡（SSE 流式/重试/结构化输出/RAG embedding）一律挂本层，禁止再裸写 HTTP 调模型。
 */
@Slf4j
@Component
public class AiGateway {

    private final AiChatClient legacyClient;
    private final PromptLenBucketLogger bucketLogger = new PromptLenBucketLogger();
    /** 业务时钟注入（裸时钟守卫禁 System.currentTimeMillis，见 治理/测试编写三禁）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public AiGateway(AiChatClient legacyClient) {
        this.legacyClient = legacyClient;
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
                return AiChatResult.fail("EMPTY_RESPONSE", "模型返回空内容", elapsed(start));
            }
            TokenUsage usage = resp.metadata() == null ? null : resp.metadata().tokenUsage();
            int promptTokens = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
            int completionTokens = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
            logAiRequest(cfg, promptLen, content.length());
            return AiChatResult.ok(content, promptTokens, completionTokens, elapsed(start));
        } catch (Exception e) {
            AiChatResult fail = mapFailure(e, elapsed(start));
            logAiRequest(cfg, promptLen, null);
            log.warn("[AI] gateway fail: model={} promptLenBucket={} errorCode={} latencyMs={} promptHash={}",
                cfg.modelName(), PromptLenBucket.of(promptLen).label(), fail.errorCode(),
                fail.latencyMs(), AiChatClient.shortHash(prompt));
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
     * 异常 → 白名单错误码单一映射口（package-private 供表驱动单测）。
     * Langchain4j JDK 客户端契约（1.17.2 JdkHttpClient 源码实证）：
     * 非 2xx → HttpException(statusCode,body)；HttpTimeoutException → TimeoutException；
     * ConnectException/UnknownHostException 等 IOException → RuntimeException 包装（走 cause 解链）。
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
        Throwable root = rootCause(e);
        if (root instanceof java.net.ConnectException || root instanceof java.net.UnknownHostException) {
            return AiChatResult.fail("UNREACHABLE",
                "connect: " + root.getClass().getSimpleName(), latencyMs);
        }
        if (root instanceof java.net.http.HttpTimeoutException) {
            return AiChatResult.fail("TIMEOUT", "gateway: timeout", latencyMs);
        }
        return AiChatResult.fail("UNSUPPORTED_PROTOCOL",
            "gateway: " + e.getClass().getSimpleName(), latencyMs);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
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
