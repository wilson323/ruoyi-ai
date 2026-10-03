package org.ruoyi.ipd.agent.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.ruoyi.ipd.agent.domain.*;
import org.ruoyi.ipd.agent.model.*;
import org.ruoyi.ipd.agent.support.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class ProjectAgentRunOwnershipTest {
    private final InMemoryAgentRunStore store = new InMemoryAgentRunStore();
    private final ObjectMapper mapper = new ObjectMapper();
    private IpdAgentRun run(String key, String status, boolean owned) {
        var run = IpdAgentRun.builder().tenantId("tenant").personId(7L).projectId(9L).status(status)
            .idempotencyKey(key).version(1).build();
        store.insertRun(run);
        if (owned) store.appendEvent(ProjectAgentRunEvents.of(run.getId(), "tenant", 7L, 1, AgentEventType.STEP,
            "{\"kind\":\"EXECUTION_OWNER\",\"epoch\":1}", new Date()));
        return run;
    }
    private ProjectAgentRunOwnership.Lease lease(boolean held) {
        var lease = mock(ProjectAgentRunOwnership.Lease.class);
        when(lease.held()).thenReturn(held); when(lease.token()).thenReturn(20L); return lease;
    }
    @Test void lostLeaseDropsLateFramesAndNeverExecutesSuccessSideEffect() {
        var run = run("lost", "RUNNING", true);
        var lease = lease(true);
        var closed = new AtomicInteger(); var applied = new AtomicInteger();
        var artifacts = new InMemoryArtifactVersionStore();
        var handle = new ProjectAgentRunHandle(run, store, artifacts, mapper, System::currentTimeMillis, closed::incrementAndGet);
        handle.setFinishTransaction(AgentOwnershipTestTransactions.create()); handle.setOwnership(lease, 1);
        handle.whenSucceeded(text -> applied.incrementAndGet());
        handle.onText("正文");
        when(lease.held()).thenReturn(false);
        handle.onText("迟到帧"); handle.onComplete();
        assertTrue(handle.isClosed()); assertEquals(1, closed.get()); assertEquals(0, applied.get());
        assertEquals(0, artifacts.size()); assertEquals("RUNNING", store.findRun(run.getId()).orElseThrow().getStatus());
        assertEquals(1, store.events(run.getId()).size());
    }
    @Test void newerEpochFencesEvenAnApparentlyHeldOldLeaseAndUsageSideEffect() {
        var run = run("epoch", "RUNNING", true);
        var handle = new ProjectAgentRunHandle(run, store, mapper, System::currentTimeMillis, () -> { });
        handle.setFinishTransaction(AgentOwnershipTestTransactions.create()); handle.setOwnership(lease(true), 1);
        assertEquals(2, store.claimEpoch(run.getId(), 1, Set.of(AgentRunStatus.RUNNING)).orElseThrow());
        var writes = new AtomicInteger();
        assertThrows(ProjectAgentRunOwnership.OwnershipLost.class, () -> handle.runOwned(writes::incrementAndGet));
        handle.onStep("MODEL_CALL", Map.of("inputTokens", 10)); handle.onComplete();
        assertEquals(0, writes.get()); assertTrue(handle.isClosed()); assertEquals(1, store.events(run.getId()).size());
    }
    @Test void nativeFinalReplacementUsesSameEventAndAuthoritativeArtifactBody() {
        var run = run("final", "RUNNING", true); var artifacts = new InMemoryArtifactVersionStore();
        var handle = new ProjectAgentRunHandle(run, store, artifacts, mapper, System::currentTimeMillis, () -> { });
        handle.setFinishTransaction(AgentOwnershipTestTransactions.create()); handle.setOwnership(lease(true), 1);
        handle.onText("old streamed text"); handle.onFinalText("corrected final text"); handle.onFinalText("corrected final text");
        assertEquals("corrected final text", handle.assistantText()); handle.onComplete();
        var replacements = store.events(run.getId()).stream().filter(e -> e.getPayload().contains("\"replace\":true")).toList();
        assertEquals(1, replacements.size()); assertTrue(replacements.get(0).getPayload().contains("corrected final text"));
        assertEquals("corrected final text", artifacts.listByRunIds(List.of(run.getId())).get(0).getContent());
    }

    @Test void recoveryRequiresOwnershipMarkerAndFreeLockAndPreservesApproval() {
        var legacy = run("legacy", "RUNNING", false); var waiting = run("wait", "WAITING_APPROVAL", true);
        var active = run("active", "RUNNING", true); var orphan = run("orphan", "RUNNING", true);
        var ownership = mock(ProjectAgentRunOwnership.class);
        when(ownership.acquire(active.getId())).thenReturn(Optional.empty());
        var acquired = lease(true); when(ownership.acquire(orphan.getId())).thenReturn(Optional.of(acquired));
        var recovery = new ProjectAgentRunRecovery(store, ownership, AgentOwnershipTestTransactions.create(), mapper);
        assertFalse(recovery.recover(legacy)); assertFalse(recovery.recover(waiting)); assertFalse(recovery.recover(active));
        verify(ownership, never()).acquire(legacy.getId()); verify(ownership, never()).acquire(waiting.getId());
        assertTrue(recovery.recover(orphan)); assertFalse(recovery.recover(orphan));
        assertEquals("FAILED", store.findRun(orphan.getId()).orElseThrow().getStatus());
        assertEquals("INTERRUPTED", store.findRun(orphan.getId()).orElseThrow().getErrorCode());
        assertEquals("RUNNING", store.findRun(active.getId()).orElseThrow().getStatus());
        assertEquals("WAITING_APPROVAL", store.findRun(waiting.getId()).orElseThrow().getStatus());
        assertEquals(1, store.events(orphan.getId()).stream().filter(e -> "ERROR".equals(e.getEventType())).count());
        verify(acquired).close();
    }
    @Test void interruptedRecoveryRetainsOwnedAndNeighborCheckpointsForReconciliation() {
        var run = run("checkpoint", "RUNNING", true);
        var nativeStore = new io.agentscope.core.state.InMemoryAgentStateStore();
        var scope = org.ruoyi.chat.kernel.KernelScopeKey.of("9", "7", org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID, String.valueOf(run.getId()));
        var neighbor = org.ruoyi.chat.kernel.KernelScopeKey.of("9", "7", org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID, "other-run");
        nativeStore.save(scope.userId(), scope.sessionId(), "agent_state", io.agentscope.core.state.AgentState.builder().build());
        nativeStore.save(neighbor.userId(), neighbor.sessionId(), "agent_state", io.agentscope.core.state.AgentState.builder().build());
        var owner = mock(ProjectAgentRunOwnership.class); var acquired = lease(true);
        when(owner.acquire(run.getId())).thenReturn(Optional.of(acquired));
        var recovery = new ProjectAgentRunRecovery(store, owner, AgentOwnershipTestTransactions.create(), mapper);
        recovery.setStateStore(nativeStore); assertTrue(recovery.recover(run));
        assertTrue(nativeStore.exists(scope.userId(), scope.sessionId()));
        assertTrue(nativeStore.exists(neighbor.userId(), neighbor.sessionId()));
        assertEquals("FAILED", store.findRun(run.getId()).orElseThrow().getStatus());
        assertEquals("INTERRUPTED", store.findRun(run.getId()).orElseThrow().getErrorCode());
    }

    @Test void queuedOwnedPendingCanRecoverWithoutInputReplay() {
        var pending = run("queued", "PENDING", true); var owner = mock(ProjectAgentRunOwnership.class); var acquired = lease(true);
        when(owner.acquire(pending.getId())).thenReturn(Optional.of(acquired));
        var recovery = new ProjectAgentRunRecovery(store, owner, AgentOwnershipTestTransactions.create(), mapper);
        assertTrue(recovery.recover(pending));
        assertEquals("FAILED", store.findRun(pending.getId()).orElseThrow().getStatus());
        assertTrue(store.events(pending.getId()).get(1).getPayload().contains("retryAsNewRun"));
    }

    @Test void cancelRequestedRecoversToCancelledWithoutInterruptedCode() {
        var requested = run("cancel", "CANCEL_REQUESTED", true);
        var owner = mock(ProjectAgentRunOwnership.class);
        var acquired = lease(true);
        when(owner.acquire(requested.getId())).thenReturn(Optional.of(acquired));
        var recovery = new ProjectAgentRunRecovery(store, owner, AgentOwnershipTestTransactions.create(), mapper);
        assertTrue(recovery.recover(requested));
        var closed = store.findRun(requested.getId()).orElseThrow();
        assertEquals("CANCELLED", closed.getStatus());
        assertNull(closed.getErrorCode());
        var finished = store.events(requested.getId()).stream()
            .filter(event -> "RUN_FINISHED".equals(event.getEventType())).toList();
        assertEquals(1, finished.size());
        assertTrue(finished.get(0).getPayload().contains("\"status\":\"CANCELLED\""));
        assertFalse(finished.get(0).getPayload().contains("INTERRUPTED"));
    }

    @Test void waitingApprovalReleasesLocalResourcesWithoutClosingBusinessRun() {
        var run = run("pause", "WAITING_APPROVAL", true); var released = new AtomicInteger();
        var handle = new ProjectAgentRunHandle(run, store, mapper, System::currentTimeMillis, released::incrementAndGet);
        handle.setFinishTransaction(AgentOwnershipTestTransactions.create()); handle.setOwnership(lease(true), 1);
        handle.pauseForApproval(); handle.pauseForApproval(); handle.onComplete();
        assertEquals(1, released.get()); assertEquals("WAITING_APPROVAL", store.findRun(run.getId()).orElseThrow().getStatus());
        assertEquals(1, store.events(run.getId()).size());
    }
}
