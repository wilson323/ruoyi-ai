package org.ruoyi.ipd.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
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

/** AgentScope 原生模型流通过本测试拥有的真实 HTTP SSE 服务验证。 */
@Tag("dev")
@DisplayName("L0-4 AiGateway.stream：真活 vs mock streaming server（≥3 段 delta）")
class AiGatewayStreamingTest {

    @Test
    @DisplayName("stream：真活 mock server → onDelta ≥3 段 + onComplete 触发（异步聚合）")
    void streamRealDeltasFromMockServer() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                for (String text : List.of("逐", "段", "到", "达", "完成")) {
                    String frame = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text
                        + "\"},\"finish_reason\":null}]}\n\n";
                    out.write(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.flush();
                }
                out.write(("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":5,\"total_tokens\":12}}\n\n"
                    + "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } finally { exchange.close(); }
        });
        server.start();
        try {
        String mockBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        AiChatClient legacy = mock(AiChatClient.class);
        // 绕过 SSRF 黑名单：mock server 是 loopback，真 ssrfCheck 会拦（allowlist 是运行时配置，单测走 mock 放行）
        doNothing().when(legacy).ssrfCheck(anyString());
        AiGateway gateway = new AiGateway(legacy);

        List<String> deltas = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger completePromptTokens = new AtomicInteger(-1);
        AtomicReference<AiChatResult> error = new AtomicReference<>();

        gateway.stream(new AiTestConfig("openai", mockBase, "sk-test", "test-chat", 15_000),
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
        org.junit.jupiter.api.Assertions.assertEquals(7, completePromptTokens.get(),
            "真实响应中的用量必须经过原生 SDK 完整传递");
        } finally { server.stop(0); }
    }
}
