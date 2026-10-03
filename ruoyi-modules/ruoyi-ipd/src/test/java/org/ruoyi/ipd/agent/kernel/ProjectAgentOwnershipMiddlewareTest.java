package org.ruoyi.ipd.agent.kernel;
import org.junit.jupiter.api.*;
import io.agentscope.core.tool.ToolBase;
import java.util.Map;
import org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership;
import reactor.core.publisher.Flux;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class ProjectAgentOwnershipMiddlewareTest {
    @Test void ownershipFailureNeverStartsModelOrActingOrTool() {
        var sink = mock(ProjectAgentEventSink.class);
        doThrow(new ProjectAgentRunOwnership.OwnershipLost()).when(sink).requireActiveOwnership();
        var middleware = new AgentScopeProjectAgentKernel.OwnershipMiddleware(sink);
        var calls = new AtomicInteger();
        assertThrows(ProjectAgentRunOwnership.OwnershipLost.class, () -> middleware.onModelCall(null, null, null,
            input -> { calls.incrementAndGet(); return Flux.empty(); }).blockLast());
        assertThrows(ProjectAgentRunOwnership.OwnershipLost.class, () -> middleware.onActing(null, null, null,
            input -> { calls.incrementAndGet(); return Flux.empty(); }).blockLast());
        var original = mock(ToolBase.class);
        when(original.getName()).thenReturn("owned_test_tool");
        when(original.getDescription()).thenReturn("Ownership test tool");
        when(original.getParameters()).thenReturn(Map.of());
        var tool = new AgentScopeProjectAgentKernel.OwnershipGuardedTool(original, sink);
        assertThrows(ProjectAgentRunOwnership.OwnershipLost.class, () -> tool.callAsync(null).block());
        assertThrows(ProjectAgentRunOwnership.OwnershipLost.class,
            () -> tool.checkPermissions(Map.of(), null).block());
        verify(original, never()).checkPermissions(any(), any());
        verify(original, never()).callAsync(any()); assertEquals(0, calls.get());
    }
}
