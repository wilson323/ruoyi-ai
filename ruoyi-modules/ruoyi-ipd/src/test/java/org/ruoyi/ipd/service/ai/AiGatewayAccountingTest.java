package org.ruoyi.ipd.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.service.AiModelBudgetService;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;

/**
 * C2-3 AiGateway 用量记账面测试：预算预占→出站→结算+落账全链语义。
 *
 * <p><b>注意</b>：必须 {@code @Tag("dev")}（Surefire groups=${profiles.active} 过滤，缺 tag 假绿）。
 *
 * <p>出站证据：用 JDK 内置 {@link HttpServer} 起测试内 OpenAI 兼容 mock（真 HTTP 经
 * {@code OpenAiChatModel} 发出），并以请求计数证明
 * 「预算拒绝 = 零出站」。SSRF 前置经 mock {@link AiChatClient} 放行（loopback 本会被拦），
 * 与 {@code AiGatewayStreamingTest} 同一口径。
 */
@Tag("dev")
@DisplayName("C2-3 AiGateway 记账面：预占→出站→结算+落账")
class AiGatewayAccountingTest {

    private static final String COMPLETION_JSON = "{\"id\":\"c1\",\"object\":\"chat.completion\","
        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},"
        + "\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":5,\"total_tokens\":12}}";

    private AiChatClient legacy;
    private AiModelBudgetService budget;
    private AiModelUsageLedgerService ledger;
    private AiGateway gateway;
    private HttpServer mockServer;
    private final AtomicInteger outboundRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        legacy = mock(AiChatClient.class);
        budget = mock(AiModelBudgetService.class);
        ledger = mock(AiModelUsageLedgerService.class);
        gateway = new AiGateway(legacy, budget, ledger);

        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.createContext("/v1/chat/completions", exchange -> {
            outboundRequests.incrementAndGet();
            byte[] body = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        mockServer.start();
    }

    @AfterEach
    void tearDown() {
        mockServer.stop(0);
    }

    private String mockBaseUrl() {
        return "http://127.0.0.1:" + mockServer.getAddress().getPort() + "/v1";
    }

    private static AiTestConfig cfgWith(String baseUrl, AiCallScope scope) {
        return new AiTestConfig("openai", baseUrl, "sk-test", "test-chat", 5000, scope);
    }

    @Test
    @DisplayName("chat 成功：预占→出站→settle(预占, 真实 usage) + 落账 ok")
    void chatSuccessSettlesAndRecords() {
        when(budget.preoccupy(eq(7L), anyLong())).thenReturn(true);

        AiChatResult result = gateway.chat(cfgWith(mockBaseUrl(),
                new AiCallScope(7L, "42", "suggest")), "你好", 200, new BigDecimal("0.5"));

        assertThat(result.success()).isTrue();
        assertThat(result.promptTokens()).isEqualTo(7);
        assertThat(result.completionTokens()).isEqualTo(5);
        long estimate = AiGateway.estimateTokens("你好", 200);
        verify(budget).preoccupy(7L, estimate);
        verify(budget).settle(7L, estimate, 12L);
        verify(ledger).recordUsage(eq(7L), eq("42"), eq("suggest"),
            eq(7), eq(5), anyLong(), eq("ok"), any());
    }

    @Test
    @DisplayName("预算拒绝 = 零出站：BUDGET_EXCEEDED + REJECTED:BUDGET 落账 + 不结算")
    void budgetRejectedMeansNoOutbound() {
        when(budget.preoccupy(eq(7L), anyLong())).thenReturn(false);

        AiChatResult result = gateway.chat(cfgWith(mockBaseUrl(),
                new AiCallScope(7L, "42", "suggest")), "你好", 200, null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(outboundRequests.get()).isZero();
        verify(budget, never()).settle(anyLong(), anyLong(), anyLong());
        verify(ledger).recordUsage(eq(7L), eq("42"), eq("suggest"),
            eq(0), eq(0), anyLong(), eq("REJECTED:BUDGET"), any());
    }

    @Test
    @DisplayName("预算面故障 fail-closed：按拒绝处理 + REJECTED:BUDGET_CHECK_FAILED 落账")
    void budgetServiceErrorRejectsFailClosed() {
        when(budget.preoccupy(eq(7L), anyLong())).thenThrow(new IllegalStateException("db down"));

        AiChatResult result = gateway.chat(cfgWith(mockBaseUrl(),
                new AiCallScope(7L, null, "generate")), "你好", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(outboundRequests.get()).isZero();
        verify(ledger).recordUsage(eq(7L), isNull(), eq("generate"),
            eq(0), eq(0), anyLong(), eq("REJECTED:BUDGET_CHECK_FAILED"), any());
    }

    @Test
    @DisplayName("失败路径：出站 UNREACHABLE → settle(预占, 0) 回冲 + FAIL:UNREACHABLE 落账")
    void failurePathSettlesZeroAndRecordsFail() throws Exception {
        when(budget.preoccupy(eq(7L), anyLong())).thenReturn(true);
        String closedPortUrl;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPortUrl = "http://127.0.0.1:" + probe.getLocalPort() + "/v1";
        }

        AiChatResult result = gateway.chat(cfgWith(closedPortUrl,
                new AiCallScope(7L, "42", "bid_check")), "你好", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNREACHABLE");
        long estimate = AiGateway.estimateTokens("你好", null);
        verify(budget).settle(7L, estimate, 0L);
        verify(ledger).recordUsage(eq(7L), eq("42"), eq("bid_check"),
            eq(0), eq(0), anyLong(), eq("FAIL:UNREACHABLE"), any());
    }

    @Test
    @DisplayName("无记账面（scope=null）：调用成功但零预占/零落账（存量口径不变）")
    void noScopeMeansNoAccounting() {
        AiChatResult result = gateway.chat(cfgWith(mockBaseUrl(), null), "你好", null, null);

        assertThat(result.success()).isTrue();
        assertThat(outboundRequests.get()).isEqualTo(1);
        verify(budget, never()).preoccupy(anyLong(), anyLong());
        verify(budget, never()).settle(anyLong(), anyLong(), anyLong());
        verify(ledger, never()).recordUsage(any(), any(), any(), anyInt(), anyInt(),
            anyLong(), any(), any());
    }

    @Test
    @DisplayName("stream 预算拒绝 = 零出站：onError(BUDGET_EXCEEDED) + REJECTED:BUDGET 落账")
    void streamBudgetRejectedMeansNoOutbound() throws Exception {
        when(budget.preoccupy(eq(7L), anyLong())).thenReturn(false);
        RecordingHandler handler = new RecordingHandler();

        gateway.stream(cfgWith(mockBaseUrl(), new AiCallScope(7L, "42", "copilot")),
            "讲个长故事", 200, null, handler);

        assertThat(handler.latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.errors).hasSize(1);
        assertThat(handler.errors.get(0).errorCode()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(outboundRequests.get()).isZero();
        verify(ledger).recordUsage(eq(7L), eq("42"), eq("copilot"),
            eq(0), eq(0), anyLong(), eq("REJECTED:BUDGET"), any());
    }

    @Test
    @DisplayName("stream 失败路径：onError(UNREACHABLE) + settle(预占, 0) + FAIL:UNREACHABLE 落账")
    void streamFailureSettlesAndRecordsFail() throws Exception {
        when(budget.preoccupy(eq(7L), anyLong())).thenReturn(true);
        String closedPortUrl;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPortUrl = "http://127.0.0.1:" + probe.getLocalPort() + "/v1";
        }
        RecordingHandler handler = new RecordingHandler();

        gateway.stream(cfgWith(closedPortUrl, new AiCallScope(7L, "42", "copilot")),
            "讲个长故事", null, null, handler);

        assertThat(handler.latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.errors).hasSize(1);
        assertThat(handler.errors.get(0).errorCode()).isEqualTo("UNREACHABLE");
        long estimate = AiGateway.estimateTokens("讲个长故事", null);
        verify(budget).settle(7L, estimate, 0L);
        verify(ledger).recordUsage(eq(7L), eq("42"), eq("copilot"),
            eq(0), eq(0), anyLong(), eq("FAIL:UNREACHABLE"), any());
    }

    @Test
    @DisplayName("estimateTokens 纯函数：≈4 字符/token + maxTokens 上限预算，下限 1")
    void estimateTokensPure() {
        assertThat(AiGateway.estimateTokens(null, null)).isEqualTo(1L);
        assertThat(AiGateway.estimateTokens("你好", 200)).isEqualTo(201L);
        assertThat(AiGateway.estimateTokens("abcdefgh", 10)).isEqualTo(12L);
        assertThat(AiGateway.estimateTokens("abc", -5)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AiCallScope.of：modelConfigId 空 → null（无记账面，存量调用点零感知）")
    void ofNullModelConfigIdMeansNoScope() {
        assertThat(AiCallScope.of(null, null, "suggest")).isNull();
        assertThat(AiCallScope.of(7L, null, "suggest")).isNotNull();
    }

    /** 流式回调收集器（onComplete/onError 二选一触发 latch）。 */
    private static final class RecordingHandler implements AiGateway.StreamHandler {
        final List<String> deltas = new CopyOnWriteArrayList<>();
        final List<AiChatResult> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int promptTokens = -1;

        @Override
        public void onDelta(String token) {
            deltas.add(token);
        }

        @Override
        public void onComplete(int promptTokens, int completionTokens, long latencyMs) {
            this.promptTokens = promptTokens;
            latch.countDown();
        }

        @Override
        public void onError(AiChatResult failure) {
            errors.add(failure);
            latch.countDown();
        }
    }
}
