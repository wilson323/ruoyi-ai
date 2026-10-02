package org.ruoyi.ipd.service.ai;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.service.AiModelBudgetService;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;
import reactor.core.Disposable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiGatewayCancellationTest {
    @Test
    void cancellationClosesRealHttpStreamAndSettlesOnce() throws Exception {
        runPhysicalTermination(false);
    }
    @Test
    void timeoutClosesRealHttpStreamAndSettlesOnce() throws Exception {
        runPhysicalTermination(true);
    }
    private void runPhysicalTermination(boolean timeout) throws Exception {
        var budget = mock(AiModelBudgetService.class);
        var ledger = mock(AiModelUsageLedgerService.class);
        when(budget.preoccupy(eq(7L), anyLong())).thenReturn(true);
        var gateway = new AiGateway(mock(AiChatClient.class), budget, ledger);
        var delta = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var errors = new AtomicInteger();
        var failed = new CountDownLatch(1);
        var completions = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            byte[] frame = ("data: {\"id\":\"cancel\",\"object\":\"chat.completion.chunk\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + "x".repeat(4096)
                + "\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8);
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 1000; i++) {
                    out.write(frame); out.flush(); Thread.sleep(5);
                }
            } catch (java.io.IOException disconnected) {
                closed.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var cfg = new AiTestConfig("openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", "cancel-http-fixture", timeout ? 750 : 10000, new AiCallScope(7L, "42", "copilot"));
            Disposable request = gateway.stream(cfg, "hello", 200, null, new AiGateway.StreamHandler() {
                @Override public void onDelta(String token) { delta.countDown(); }
                @Override public void onComplete(int input, int output, long latency) { completions.incrementAndGet(); }
                @Override public void onError(AiChatResult failure) {
                    assertThat(failure.errorCode()).isEqualTo(timeout ? "TIMEOUT" : "CANCELLED");
                    errors.incrementAndGet(); failed.countDown();
                }
            });
            assertThat(delta.await(5, TimeUnit.SECONDS)).isTrue();
            if (!timeout) { request.dispose(); request.dispose(); }
            assertThat(failed.await(5, TimeUnit.SECONDS)).as("one terminal failure notification").isTrue();
            assertThat(closed.await(5, TimeUnit.SECONDS)).as("provider socket closes physically").isTrue();
            assertThat(errors.get()).isEqualTo(1);
            assertThat(completions.get()).isZero();
            verify(budget, times(1)).settle(eq(7L), anyLong(), anyLong());
            verify(ledger, times(1)).recordUsage(eq(7L), eq("42"), eq("copilot"), anyInt(), anyInt(),
                anyLong(), eq(timeout ? "FAIL:TIMEOUT" : "FAIL:CANCELLED"), any());
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
