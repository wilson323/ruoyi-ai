package org.ruoyi.ipd.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/** OpenAI 兼容终止帧实测：截断或无正文不能升级为成功。 */
@Tag("dev")
@DisplayName("AiGateway 终止原因与空结论门禁")
class AiGatewayCompletionGuardTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("同步 finish_reason=length 即使有部分正文也不能标成功")
    void syncLengthIsNotSuccessful() throws Exception {
        startServer("application/json", """
            {"id":"c1","object":"chat.completion","choices":[{"index":0,
            "message":{"role":"assistant","content":"未写完的结论"},"finish_reason":"length"}],
            "usage":{"prompt_tokens":7,"completion_tokens":800,"total_tokens":807}}
            """);

        AiChatResult result = gateway().chat(config(), "请给结论", 800, new BigDecimal("0.5"));

        assertFalse(result.success());
        assertEquals("OUTPUT_TRUNCATED", result.errorCode());
    }

    @Test
    @DisplayName("同步 stop 只有 think 段，没有可交付正文")
    void syncReasoningOnlyIsNotSuccessful() throws Exception {
        startServer("application/json", """
            {"id":"c1","object":"chat.completion","choices":[{"index":0,
            "message":{"role":"assistant","content":"<think>正在分析</think>"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":7,"completion_tokens":533,"total_tokens":540}}
            """);

        AiChatResult result = gateway().chat(config(), "请给结论", 800, new BigDecimal("0.5"));

        assertFalse(result.success());
        assertEquals("EMPTY_RESPONSE", result.errorCode());
    }

    @Test
    @DisplayName("流式 finish_reason=length 发错误，不发完成")
    void streamLengthCannotSendDone() throws Exception {
        startServer("text/event-stream", """
            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"<think>分析未结束"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"length"}],"usage":{"prompt_tokens":7,"completion_tokens":800,"total_tokens":807}}

            data: [DONE]

            """);
        CompletionHandler handler = new CompletionHandler();

        gateway().stream(config(), "请给结论", 800, new BigDecimal("0.5"), handler);

        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));
        assertFalse(handler.completed.get());
        assertEquals("OUTPUT_TRUNCATED", handler.error.get().errorCode());
    }

    @Test
    @DisplayName("流式正常 stop 但没有正文，应报空结论")
    void streamEmptyConclusionCannotSendDone() throws Exception {
        startServer("text/event-stream", """
            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":533,"total_tokens":540}}

            data: [DONE]

            """);
        CompletionHandler handler = new CompletionHandler();

        gateway().stream(config(), "请给结论", 800, new BigDecimal("0.5"), handler);

        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));
        assertFalse(handler.completed.get());
        assertEquals("EMPTY_RESPONSE", handler.error.get().errorCode());
    }

    @Test
    @DisplayName("流式 stop 仅有 think 段而无结论，应报空结论")
    void streamReasoningOnlyCannotSendDone() throws Exception {
        startServer("text/event-stream", """
            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"<think>正在分析</think>"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":533,"total_tokens":540}}

            data: [DONE]

            """);
        CompletionHandler handler = new CompletionHandler();

        gateway().stream(config(), "请给结论", 800, new BigDecimal("0.5"), handler);

        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));
        assertFalse(handler.completed.get());
        assertEquals("EMPTY_RESPONSE", handler.error.get().errorCode());
    }

    @Test
    @DisplayName("流式 stop 有正常正文，保持成功兼容")
    void streamNormalStopStillCompletes() throws Exception {
        startServer("text/event-stream", """
            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"项目结论：先完成评审。"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":40,"total_tokens":47}}

            data: [DONE]

            """);
        CompletionHandler handler = new CompletionHandler();

        gateway().stream(config(), "请给结论", 800, new BigDecimal("0.5"), handler);

        assertTrue(handler.latch.await(5, TimeUnit.SECONDS));
        assertTrue(handler.completed.get());
        assertEquals(null, handler.error.get());
    }

    private AiGateway gateway() {
        AiChatClient legacy = mock(AiChatClient.class);
        doNothing().when(legacy).ssrfCheck(anyString());
        return new AiGateway(legacy);
    }

    private AiTestConfig config() {
        return new AiTestConfig("openai",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "sk-test", "test-chat", 3_000);
    }

    private void startServer(String contentType, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    private static final class CompletionHandler implements AiGateway.StreamHandler {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicReference<AiChatResult> error = new AtomicReference<>();

        @Override
        public void onDelta(String token) { }

        @Override
        public void onComplete(int promptTokens, int completionTokens, long latencyMs) {
            completed.set(true);
            latch.countDown();
        }

        @Override
        public void onError(AiChatResult failure) {
            error.set(failure);
            latch.countDown();
        }
    }
}
