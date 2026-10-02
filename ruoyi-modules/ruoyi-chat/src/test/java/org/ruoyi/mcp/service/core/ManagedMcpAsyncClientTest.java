package org.ruoyi.mcp.service.core;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedMcpAsyncClientTest {
    @Test void streamableHttpConstructionDoesNotDependOnContextProviderDiscovery() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(new ClassLoader(null) { });
            var wrapper = ManagedMcpAsyncClient.streamableHttp("fixture", "http://127.0.0.1:1/mcp?fixture=1",
                java.util.Map.of("X-Fixture", "public"), Duration.ofMillis(100));
            assertThat(wrapper).isNotNull();
            wrapper.close();
            wrapper.closeCompletion().block(Duration.ofSeconds(2));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test void realClientClosesTransportExactlyOnceAfterCancellation() {
        McpClientTransport transport = mock(McpClientTransport.class);
        when(transport.protocolVersions()).thenReturn(java.util.List.of("2024-11-05"));
        when(transport.closeGracefully()).thenReturn(Mono.empty());
        var wrapper = new ManagedMcpAsyncClient("fixture", McpClient.async(transport).build(), Duration.ofMillis(100));
        var cancelledRun = Mono.<Void>never().doFinally(signal -> wrapper.close()).subscribe();
        cancelledRun.dispose();
        wrapper.close();
        wrapper.closeCompletion().block(Duration.ofSeconds(2));
        verify(transport, times(1)).closeGracefully();
    }

    @Test void stalledGracefulCloseIsBoundedAndForceClosedWithObservableFailure() {
        McpClientTransport transport = mock(McpClientTransport.class);
        when(transport.protocolVersions()).thenReturn(java.util.List.of("2024-11-05"));
        when(transport.closeGracefully()).thenReturn(Mono.never());
        var wrapper = new ManagedMcpAsyncClient("fixture", McpClient.async(transport).build(), Duration.ofMillis(40));
        long start = System.nanoTime();
        wrapper.close();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        assertThatThrownBy(() -> wrapper.closeCompletion().block(Duration.ofSeconds(2))).hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        verify(transport, times(1)).close();
        wrapper.close();
        verify(transport, times(1)).close();
        assertThatThrownBy(() -> wrapper.listTools().block()).isInstanceOf(IllegalStateException.class);
    }

    @Test void gracefulFailureIsVisibleAndForceCloses() {
        McpClientTransport transport = mock(McpClientTransport.class);
        when(transport.protocolVersions()).thenReturn(java.util.List.of("2024-11-05"));
        when(transport.closeGracefully()).thenReturn(Mono.error(new IllegalStateException("fixture")));
        var wrapper = new ManagedMcpAsyncClient("fixture", McpClient.async(transport).build(), Duration.ofMillis(100));
        wrapper.close();
        assertThatThrownBy(() -> wrapper.closeCompletion().block(Duration.ofSeconds(2))).isInstanceOf(IllegalStateException.class);
        verify(transport).close();
    }
}
