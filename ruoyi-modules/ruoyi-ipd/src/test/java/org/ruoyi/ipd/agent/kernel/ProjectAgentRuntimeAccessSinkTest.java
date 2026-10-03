package org.ruoyi.ipd.agent.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentRuntimeAccessSinkTest {
    @Test void revokedAccessIsReadAgainAndPreventsTheNextEffect() {
        var delegate = mock(ProjectAgentEventSink.class, CALLS_REAL_METHODS);
        var spec = mock(ProjectAgentRunSpec.class);
        var authorized = new AtomicBoolean(true);
        var reads = new AtomicInteger();
        var effects = new AtomicInteger();
        var sink = new ProjectAgentRuntimeAccessSink(delegate, spec, current -> {
            assertSame(spec, current);
            reads.incrementAndGet();
            if (!authorized.get()) throw new IllegalStateException("Access revoked");
        });
        sink.withActiveOwnership(effects::incrementAndGet);
        authorized.set(false);
        assertThrows(IllegalStateException.class, () -> sink.withActiveOwnership(effects::incrementAndGet));
        assertThrows(IllegalStateException.class, sink::requireActiveOwnership);
        assertEquals(1, effects.get());
        assertEquals(3, reads.get());
        verify(delegate, times(3)).requireActiveOwnership();
    }

    @Test void freshAccessRunsInsideTheOriginalOwnershipTransaction() {
        var delegate = mock(ProjectAgentEventSink.class);
        var order = new ArrayList<String>();
        doAnswer(call -> {
            order.add("locked");
            return ((Supplier<?>) call.getArgument(0)).get();
        }).when(delegate).withActiveOwnership(any());
        var sink = new ProjectAgentRuntimeAccessSink(delegate, mock(ProjectAgentRunSpec.class),
            spec -> order.add("current-business-access"));
        sink.withActiveOwnership(() -> { order.add("effect"); return null; });
        assertEquals(List.of("locked", "current-business-access", "effect"), order);
    }

    @Test void childPauseAndEpochRequireFreshAccessBeforeTheOriginalOwner() {
        var delegate = mock(ProjectAgentEventSink.class);
        var authorized = new AtomicBoolean(true);
        var sink = new ProjectAgentRuntimeAccessSink(delegate, mock(ProjectAgentRunSpec.class), spec -> {
            if (!authorized.get()) throw new IllegalStateException("Access revoked");
        });
        var pending = List.<ProjectAgentChildLineageRegistry.ChildApproval>of();
        when(delegate.executionEpoch()).thenReturn(27L);
        assertEquals(27L, sink.executionEpoch());
        sink.onChildInterrupt(pending, 19L);
        verify(delegate).onChildInterrupt(pending, 19L);
        authorized.set(false);
        assertThrows(IllegalStateException.class, sink::executionEpoch);
        assertThrows(IllegalStateException.class, () -> sink.onChildInterrupt(pending, 20L));
        verify(delegate, times(1)).executionEpoch();
        verify(delegate, never()).onChildInterrupt(pending, 20L);
    }

    @Test void revokedBusinessAccessDoesNotBlockOwnedCheckpointCleanupAndPauseIsDelegated() {
        var delegate = mock(ProjectAgentEventSink.class, CALLS_REAL_METHODS);
        var authorized = new AtomicBoolean(true);
        var sink = new ProjectAgentRuntimeAccessSink(delegate, mock(ProjectAgentRunSpec.class), spec -> {
            if (!authorized.get()) throw new IllegalStateException("Access revoked");
        });
        var pending = Map.<String, io.agentscope.core.agui.event.AguiEvent.Interrupt>of();
        doNothing().when(delegate).onAguiInterrupt(pending, 19L);
        when(delegate.isPaused()).thenReturn(true);
        sink.onAguiInterrupt(pending, 19L);
        verify(delegate).onAguiInterrupt(pending, 19L);
        assertTrue(sink.isPaused());
        authorized.set(false);
        var cleaned = new AtomicBoolean();
        sink.checkpointOwnership().withActiveOwnership(() -> { cleaned.set(true); return null; });
        assertTrue(cleaned.get());
        assertThrows(IllegalStateException.class, () -> sink.onAguiInterrupt(pending, 20L));
        verify(delegate, never()).onAguiInterrupt(pending, 20L);
    }
}
