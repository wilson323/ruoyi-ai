package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.AsyncToolMiddleware;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Official middleware and durable providers; delayed real IO fixture, not production tool acceptance. */
@Tag("dev")
class OfficialAsyncToolAcceptanceTest {
    @TempDir Path root;

    @Test
    void offloadedIoFinishesAndOfficialInboxCanReadItsResult() throws Exception {
        var filesystem = new LocalFilesystem(root);
        var bus = new WorkspaceMessageBus(filesystem, ".bus");
        var registry = new WorkspaceAsyncToolRegistry(filesystem, ".registry");
        var middleware = new AsyncToolMiddleware(bus, Duration.ofMillis(50), registry);
        var state = AgentState.builder().build();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("async-fixture");
        when(agent.getAgentState()).thenReturn(state);
        var context = RuntimeContext.builder().userId("fixture-person").sessionId("fixture-session").build();
        context.setAgentState(state);
        var input = new ActingInput(List.of(ToolUseBlock.builder().id("io-1").name("fixture_write").build()));
        Path marker = root.resolve("completed.txt");
        var foreground = middleware.onActing(agent, context, input, ignored ->
            Mono.fromCallable(() -> {
                Thread.sleep(250);
                Files.writeString(marker, "ASYNC_REAL_IO_COMPLETE");
                return (AgentEvent) new ToolResultTextDeltaEvent("reply", "io-1", "fixture_write", "ASYNC_REAL_IO_COMPLETE");
            }).subscribeOn(Schedulers.boundedElastic()).flux()).collectList().block(Duration.ofSeconds(2));
        assertNotNull(foreground);
        assertTrue(foreground.stream().filter(ToolResultTextDeltaEvent.class::isInstance)
            .map(ToolResultTextDeltaEvent.class::cast).anyMatch(e -> e.getDelta().contains("running in background")));
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!Boolean.TRUE.equals(bus.inboxHasMessages("fixture-session").block()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("ASYNC_REAL_IO_COMPLETE", Files.readString(marker));
        var inbox = bus.inboxDrain("fixture-session", 10).block(Duration.ofSeconds(1));
        assertNotNull(inbox);
        assertTrue(inbox.toString().contains("ASYNC_REAL_IO_COMPLETE"));
        // Recreate the native provider: the background result must be durable, not in-memory only.
        assertFalse(new WorkspaceMessageBus(new LocalFilesystem(root), ".bus")
            .inboxHasMessages("fixture-session").block(Duration.ofSeconds(1)));
        assertTrue(registry.findStale("fixture-session", Duration.ZERO).block(Duration.ofSeconds(1)).isEmpty());
    }
}
