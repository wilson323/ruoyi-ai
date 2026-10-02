package org.ruoyi.ipd.service.ai;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelHttpException;
import org.ruoyi.chat.kernel.AgentScopeModelFactory;
import org.ruoyi.chat.kernel.KernelModelRequest;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
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
 * IPD 唯一模型出站口：AgentScope 原生模型、流与嵌入。
 * 业务侧保留 SSRF、预算预占/结算、用量账本及白名单错误，公共服务接口不变。
 */
@Slf4j
@Component
public class AiGateway {

    private final AiChatClient endpointGuard;
    /** C2-3 预算预占→结算面（Spring 恒注入；测试口可为 null = 不限额）。 */
    private final AiModelBudgetService budgetService;
    /** C2-3 用量账本面（Spring 恒注入；测试口可为 null = 不落账）。 */
    private final AiModelUsageLedgerService usageLedgerService;
    private final PromptLenBucketLogger bucketLogger = new PromptLenBucketLogger();
    /** 业务时钟注入（裸时钟守卫禁 System.currentTimeMillis，见 治理/测试编写三禁）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    @Autowired
    public AiGateway(AiChatClient endpointGuard, AiModelBudgetService budgetService,
                     AiModelUsageLedgerService usageLedgerService) {
        this.endpointGuard = endpointGuard;
        this.budgetService = budgetService;
        this.usageLedgerService = usageLedgerService;
    }

    /** 测试口：无预算/账本面（cfg.scope 为 null 的调用本就不记账；既有单测构造零迁移）。 */
    AiGateway(AiChatClient endpointGuard) {
        this(endpointGuard, null, null);
    }

    /** 测试口：注入固定时钟（同 AiGenerationService.withClock 惯例）。 */
    AiGateway withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /** 内部复用的模型出站校验；不执行模型调用或预算写入。 */
    public void validateEndpoint(String endpoint) {
        endpointGuard.ssrfCheck(endpoint);
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
        endpointGuard.ssrfCheck(cfg.baseUrl());
        int promptLen = prompt == null ? 0 : prompt.length();
        // C2-3 预算预占（fail-closed：拒绝即不出站）；无记账面 pre=0 直通过
        long pre = preoccupy(cfg, prompt, maxTokens, start);
        if (pre < 0) {
            return AiChatResult.fail("BUDGET_EXCEEDED", "模型月度预算不足", elapsed(start));
        }
        try {
            Reply reply = responses(cfg, prompt, maxTokens, temperature, false)
                .reduce(new Reply(), Reply::accept).block(Duration.ofMillis(cfg.timeoutMs()));
            String content = reply == null ? "" : reply.body();
            String failure = reply == null ? "EMPTY_RESPONSE" : reply.failure();
            if (failure != null) {
                logAiRequest(cfg, promptLen, null);
                settleAndRecord(cfg, pre, reply == null ? 0 : reply.input,
                    reply == null ? 0 : reply.output, failure, elapsed(start));
                return AiChatResult.fail(failure, "模型没有返回完整正文", elapsed(start));
            }
            int promptTokens = reply.input;
            int completionTokens = reply.output;
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
        endpointGuard.ssrfCheck(cfg.baseUrl());
        try {
            var model = org.ruoyi.service.embed.EmbeddingModels.create(cfg.provider(), cfg.modelName(),
                cfg.baseUrl(), cfg.apiKey(), null, Duration.ofMillis(cfg.timeoutMs()));
            List<float[]> out = org.ruoyi.service.embed.EmbeddingVectors.embedAll(model, texts, Duration.ofMillis(cfg.timeoutMs()));
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
     * 流式生成（AI-STRAT-3 / L0-4 SSE 真流式，2026-09-23）：AgentScope 原生 Model 流
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
     * {@code onError} 收白名单错误码——返回原生可取消订阅（异步语义）。
     *
     * @param cfg         模型配置（provider/endpoint/key/model/timeoutMs）
     * @param prompt      用户输入原文（不落日志，仅记长度分桶 + 短指纹）
     * @param maxTokens   最大生成 token 数（null = 服务端默认）
     * @param temperature 采样温度（null = 服务端默认）
     * @param handler     流式回调（onDelta 收增量 token、onComplete 收 tokenUsage、onError 收白名单错误码）
     */
    public reactor.core.Disposable stream(AiTestConfig cfg, String prompt, Integer maxTokens,
                       BigDecimal temperature, StreamHandler handler) {
        long start = clock.millis();
        endpointGuard.ssrfCheck(cfg.baseUrl());
        int promptLen = prompt == null ? 0 : prompt.length();
        long pre = preoccupy(cfg, prompt, maxTokens, start);
        if (pre < 0) {
            handler.onError(AiChatResult.fail("BUDGET_EXCEEDED", "模型月度预算不足", elapsed(start)));
            return reactor.core.Disposables.disposed();
        }
        Reply reply = new Reply();
        Object lifecycle = new Object();
        java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> source = new java.util.concurrent.atomic.AtomicReference<>();
        reactor.core.Disposable cancellation = new reactor.core.Disposable() {
            @Override public void dispose() {
                int input;
                int output;
                synchronized (lifecycle) {
                    if (!terminal.compareAndSet(false, true)) { return; }
                    cancelled.set(true); input = reply.input; output = reply.output;
                }
                reactor.core.Disposable active = source.get();
                if (active != null) { active.dispose(); }
                long latency = elapsed(start);
                settleAndRecord(cfg, pre, input, output, "FAIL:CANCELLED", latency);
                handler.onError(AiChatResult.fail("CANCELLED", "本次生成已取消", latency));
            }
            @Override public boolean isDisposed() { return terminal.get(); }
        };
        try {
            reactor.core.Disposable active = responses(cfg, prompt, maxTokens, temperature, true)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(response -> {
                    java.util.List<String> deltas = new java.util.ArrayList<>();
                    synchronized (lifecycle) {
                        if (terminal.get()) { return; }
                        reply.accept(response);
                        for (var block : response.getContent() == null ? List.<io.agentscope.core.message.ContentBlock>of()
                            : response.getContent()) {
                            if (block instanceof TextBlock text && text.getText() != null && !text.getText().isEmpty()) {
                                deltas.add(text.getText());
                            }
                        }
                    }
                    for (String delta : deltas) {
                        if (terminal.get()) { break; }
                        handler.onDelta(delta);
                    }
                }, error -> {
                    synchronized (lifecycle) {
                        if (!terminal.compareAndSet(false, true)) { return; }
                        AiChatResult failure = mapFailure(error, elapsed(start));
                        settleAndRecord(cfg, pre, reply.input, reply.output,
                            "FAIL:" + failure.errorCode(), failure.latencyMs());
                        handler.onError(failure);
                    }
                }, () -> {
                    synchronized (lifecycle) {
                        if (!terminal.compareAndSet(false, true)) { return; }
                        String failure = reply.failure();
                        long latency = elapsed(start);
                        settleAndRecord(cfg, pre, reply.input, reply.output,
                            failure == null ? "ok" : "FAIL:" + failure, latency);
                        logAiRequest(cfg, promptLen, reply.body().length());
                        if (failure == null) { handler.onComplete(reply.input, reply.output, latency); }
                        else { handler.onError(AiChatResult.fail(failure, "模型没有返回完整正文", latency)); }
                    }
                });
            source.set(active);
            if (cancelled.get() && !active.isDisposed()) { active.dispose(); }
        } catch (Exception error) {
            synchronized (lifecycle) {
                if (terminal.compareAndSet(false, true)) {
                    AiChatResult failure = mapFailure(error, elapsed(start));
                    settleAndRecord(cfg, pre, reply.input, reply.output, "FAIL:" + failure.errorCode(), failure.latencyMs());
                    handler.onError(failure);
                }
            }
        }
        return cancellation;
    }

    private Flux<ChatResponse> responses(AiTestConfig cfg, String prompt, Integer maxTokens,
                                         BigDecimal temperature, boolean streaming) {
        Model model = AgentScopeModelFactory.create(new KernelModelRequest(cfg.modelName(), cfg.provider(),
            cfg.apiKey(), stripTrailingSlash(cfg.baseUrl()), temperature == null ? null : temperature.doubleValue(),
            maxTokens, cfg.timeoutMs()));
        return Flux.defer(() -> {
            var expired = new java.util.concurrent.atomic.AtomicBoolean();
            return model.stream(List.of(Msg.builder().role(MsgRole.USER).textContent(prompt).build()), List.of(),
                    GenerateOptions.builder().stream(streaming).build())
                .takeUntilOther(reactor.core.publisher.Mono.delay(Duration.ofMillis(cfg.timeoutMs()))
                    .doOnNext(ignored -> expired.set(true)))
                .concatWith(Flux.defer(() -> expired.get()
                    ? Flux.error(new java.util.concurrent.TimeoutException("model deadline exceeded")) : Flux.empty()));
        });
    }

    /** SDK 返回增量内容；业务终态必须有完整正文，截断不能结算为成功。 */
    private static final class Reply {
        private final StringBuilder text = new StringBuilder();
        private String finishReason;
        private int input;
        private int output;

        private Reply accept(ChatResponse response) {
            if (response.getContent() != null) {
                response.getContent().forEach(block -> {
                    if (block instanceof TextBlock t && t.getText() != null) { text.append(t.getText()); }
                });
            }
            if (response.getMetadata() != null && response.getMetadata().get("replacementText") instanceof String replacement) {
                text.setLength(0);
                text.append(replacement);
            }
            ChatUsage usage = response.getUsage();
            if (usage != null) { input = usage.getInputTokens(); output = usage.getOutputTokens(); }
            if (response.getFinishReason() != null) { finishReason = response.getFinishReason(); }
            return this;
        }

        private String body() {
            return text.toString().replaceAll("(?is)<think>.*?(?:</think>|$)", "").trim();
        }

        private String failure() {
            if ("length".equals(finishReason) || "max_tokens".equals(finishReason)) { return "OUTPUT_TRUNCATED"; }
            if ("content_filter".equals(finishReason)) { return "OUTPUT_REJECTED"; }
            return body().isBlank() ? "EMPTY_RESPONSE" : null;
        }
    }

    /** 流式回调（项目级，解耦 SDK 类型；调用方 = AiCopilotService）。 */
    public interface StreamHandler {
        /** 每收到一段增量 token 触发（可能多次；调用方推 SSE delta 帧）。 */
        void onDelta(String token);

        /** 流正常结束：聚合 token 用量 + 端到端延迟（调用方推 done 帧 + 审计 streaming）。 */
        void onComplete(int promptTokens, int completionTokens, long latencyMs);

        /** 流失败：白名单错误码（mapFailure 同源；调用方推 error 帧 + 审计 FAIL）。 */
        void onError(AiChatResult failure);
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
     * AgentScope 原生 ModelHttpException 契约：
     * 非 2xx 按 ModelHttpException 状态映射；超时沿 cause 解链；
     * ConnectException/UnknownHostException 等 IOException → RuntimeException 包装（走 cause 解链）。
     * C2-3 实测：JDK HttpClient 连接拒绝链为「ConnectException → 包装 ConnectException →
     * ClosedChannelException」多层，root 是 ClosedChannelException——故连接类判定按全链命中
     * （任一层 ConnectException/UnknownHostException 即 UNREACHABLE），不能只看 root。
     */
    static AiChatResult mapFailure(Throwable e, long latencyMs) {
        if (findInChain(e, java.util.concurrent.TimeoutException.class,
            java.net.http.HttpTimeoutException.class) != null) {
            return AiChatResult.fail("TIMEOUT", "gateway: timeout", latencyMs);
        }
        Throwable http = findInChain(e, ModelHttpException.class);
        if (http instanceof ModelHttpException he && he.getStatusCode() != null) {
            int code = he.getStatusCode();
            return AiChatResult.fail(code == 401 || code == 403 ? "AUTH_FAILED" : "HTTP_" + code,
                "HTTP " + code, latencyMs);
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
