package org.ruoyi.ipd.service.ai;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * L0-4 SSE 真流式（AI-STRAT-3，2026-09-23）：{@link AiGateway#stream} 真活单测。
 *
 * <p>mock 合法性（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md）：本类<b>不</b> mock
 * Langchain4j ChatModel 假造响应——走真 HTTP 打到本机 mock OpenAI 兼容 streaming server
 * （{@code 127.0.0.1:8765}，见 /tmp/mock_chat_stream.py），验证 onPartialResponse → onDelta
 * 真逐段到达（≥3 段）+ onCompleteResponse → onComplete 聚合触发。这是「真活」而非「假绿」。
 *
 * <p>mock server 未起时用 {@link Assumptions#assumeTrue} 诚实 skip（不伪装通过）；SSRF 前置
 * 用 mock {@link AiChatClient} 放行（mock server 是 loopback，会被真 SSRF 黑名单拦，与本测无关）。
 */
@Tag("dev")
@DisplayName("L0-4 AiGateway.stream：真活 vs mock streaming server（≥3 段 delta）")
class AiGatewayStreamingTest {

    private static final String MOCK_BASE = "http://127.0.0.1:8765/v1";

    /**
     * 探测 mock streaming server 是否在监听（TCP 连接级，2s 超时）。
     * <p><b>不用 HTTP body-read 探测</b>：mock 是 SSE + {@code Connection: keep-alive}，发完 {@code [DONE]}
     * 不关连接，{@code BodyHandlers.ofString()} 会一直读到 body EOF → 无限阻塞（{@code HttpRequest.timeout}
     * 只约束到响应头，不约束 body 累积）——上一版探针就此把 surefire fork 挂死 16min。TCP connect 只验
     * 端口在听，不触 body 读；真流式是否收到 ≥3 delta 由下方 gateway.stream + latch 兜底证明。
     */
    private static boolean mockReachable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 8765), 2_000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("stream：真活 mock server → onDelta ≥3 段 + onComplete 触发（异步聚合）")
    void streamRealDeltasFromMockServer() throws Exception {
        Assumptions.assumeTrue(mockReachable(),
            "mock streaming server 127.0.0.1:8765 未起 —— 诚实 skip（非假绿；启动 /tmp/mock_chat_stream.py 后重跑）");

        AiChatClient legacy = mock(AiChatClient.class);
        // 绕过 SSRF 黑名单：mock server 是 loopback，真 ssrfCheck 会拦（allowlist 是运行时配置，单测走 mock 放行）
        doNothing().when(legacy).ssrfCheck(anyString());
        AiGateway gateway = new AiGateway(legacy);

        List<String> deltas = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger completePromptTokens = new AtomicInteger(-1);
        AtomicReference<AiChatResult> error = new AtomicReference<>();

        gateway.stream(new AiTestConfig("openai", MOCK_BASE, "sk-test", "test-chat", 15_000),
            "你好", 200, new BigDecimal("0.50"),
            new AiGateway.StreamHandler() {
                @Override
                public void onDelta(String token) {
                    deltas.add(token);
                }

                @Override
                public void onComplete(int promptTokens, int completionTokens, long latencyMs) {
                    completePromptTokens.set(promptTokens);
                    latch.countDown();
                }

                @Override
                public void onError(AiChatResult failure) {
                    error.set(failure);
                    latch.countDown();
                }
            });

        assertTrue(latch.await(20, TimeUnit.SECONDS),
            "流式应在 20s 内结束（onComplete 或 onError 触发 latch）；实收 delta=" + deltas);
        assertNull(error.get(),
            () -> "不应 onError：" + (error.get() == null ? "" : error.get().errorCode() + "/" + error.get().errorMessage()));
        assertTrue(deltas.size() >= 3,
            "真流式应收 ≥3 段 delta（mock 固定 5 段），实收=" + deltas.size() + " → " + deltas);
        assertTrue(completePromptTokens.get() >= 0,
            "onComplete 应被触发并聚合 tokenUsage（promptTokens≥0；mock 无 usage 时为 0）");
    }
}
