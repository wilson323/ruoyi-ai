package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.support.AgentOwnershipTestTransactions;
import org.ruoyi.ipd.agent.support.InMemoryAgentRunStore;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 创建时间只是只读候选依据；启动诊断必须零状态更新、零事件追加。 */
@Tag("dev")
class ProjectAgentInterruptedRunCloserTest {
    private static final long BOOT = 2_000_000L;

    @Test
    void springContainerInjectsStoreUsingProductionConstructor() {
        AgentRunStore store = mock(AgentRunStore.class);
        when(store.listInterruptedCandidates(org.mockito.ArgumentMatchers.any(Date.class),
            org.mockito.ArgumentMatchers.eq(50))).thenReturn(List.of());
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(AgentRunStore.class, () -> store);
            context.register(ProjectAgentInterruptedRunCloser.class);
            context.refresh();
            var component = context.getBean(ProjectAgentInterruptedRunCloser.class);
            assertThat(component.diagnoseCandidates()).isZero();
            verify(store).listInterruptedCandidates(org.mockito.ArgumentMatchers.any(Date.class),
                org.mockito.ArgumentMatchers.eq(50));
            verifyNoMoreInteractions(store);
        }
    }

    @Test
    void onlyReadsOneBoundedPageAndLeavesAllCandidateStatesUnchanged() {
        AgentRunStore store = mock(AgentRunStore.class);
        List<IpdAgentRun> rows = List.of(
            IpdAgentRun.builder().id(1L).status("RUNNING").build(),
            IpdAgentRun.builder().id(2L).status("CANCEL_REQUESTED").build(),
            IpdAgentRun.builder().id(3L).status("WAITING_APPROVAL").build(),
            IpdAgentRun.builder().id(4L).status("PENDING").build());
        when(store.listInterruptedCandidates(new Date(BOOT), 50)).thenReturn(rows);
        assertThat(new ProjectAgentInterruptedRunCloser(store, BOOT).diagnoseCandidates()).isEqualTo(4);
        assertThat(rows).extracting(IpdAgentRun::getStatus)
            .containsExactly("RUNNING", "CANCEL_REQUESTED", "WAITING_APPROVAL", "PENDING");
        verify(store).listInterruptedCandidates(new Date(BOOT), 50);
        verifyNoMoreInteractions(store);
    }

    @Test
    void startupDiagnosticsNeverWrites() {
        AgentRunStore store = mock(AgentRunStore.class);
        when(store.listInterruptedCandidates(new Date(BOOT), 50))
            .thenReturn(List.of(IpdAgentRun.builder().id(1L).status("RUNNING").build()));
        new ProjectAgentInterruptedRunCloser(store, BOOT).onReady();
        verify(store).listInterruptedCandidates(new Date(BOOT), 50);
        verifyNoMoreInteractions(store);
    }

    @Test
    void readFailureDoesNotFallBackToMutation() {
        AgentRunStore store = mock(AgentRunStore.class);
        when(store.listInterruptedCandidates(new Date(BOOT), 50))
            .thenThrow(new IllegalStateException("storage unavailable"));
        new ProjectAgentInterruptedRunCloser(store, BOOT).onReady();
        verify(store).listInterruptedCandidates(new Date(BOOT), 50);
        verifyNoMoreInteractions(store);
    }

    @Test
    void ownedRunningAndCancelRequestedCloseWhileWaitingAndUnownedStay() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        IpdAgentRun running = owned(store, "running", "RUNNING");
        IpdAgentRun cancel = owned(store, "cancel", "CANCEL_REQUESTED");
        IpdAgentRun waiting = owned(store, "wait", "WAITING_APPROVAL");
        IpdAgentRun plain = run(store, "plain", "RUNNING", false);
        ProjectAgentRunOwnership ownership = mock(ProjectAgentRunOwnership.class);
        ProjectAgentRunOwnership.Lease runningLease = lease();
        ProjectAgentRunOwnership.Lease cancelLease = lease();
        when(ownership.acquire(running.getId())).thenReturn(Optional.of(runningLease));
        when(ownership.acquire(cancel.getId())).thenReturn(Optional.of(cancelLease));
        ProjectAgentRunRecovery recovery = new ProjectAgentRunRecovery(
            store, ownership, AgentOwnershipTestTransactions.create(), new ObjectMapper());
        ProjectAgentInterruptedRunCloser closer = new ProjectAgentInterruptedRunCloser(store, BOOT);
        closer.setRecovery(recovery);
        closer.recoverOwnedCandidates();
        assertThat(store.findRun(running.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(store.findRun(running.getId()).orElseThrow().getErrorCode()).isEqualTo("INTERRUPTED");
        assertThat(store.findRun(cancel.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(store.findRun(cancel.getId()).orElseThrow().getErrorCode()).isNull();
        assertThat(store.findRun(waiting.getId()).orElseThrow().getStatus()).isEqualTo("WAITING_APPROVAL");
        assertThat(store.findRun(plain.getId()).orElseThrow().getStatus()).isEqualTo("RUNNING");
    }

    private static ProjectAgentRunOwnership.Lease lease() {
        ProjectAgentRunOwnership.Lease held = mock(ProjectAgentRunOwnership.Lease.class);
        when(held.held()).thenReturn(true);
        when(held.token()).thenReturn(20L);
        return held;
    }

    private static IpdAgentRun owned(InMemoryAgentRunStore store, String key, String status) {
        return run(store, key, status, true);
    }

    private static IpdAgentRun run(InMemoryAgentRunStore store, String key, String status, boolean owned) {
        IpdAgentRun run = IpdAgentRun.builder().tenantId("tenant").personId(7L).projectId(9L)
            .status(status).idempotencyKey(key).version(1).build();
        store.insertRun(run);
        if (owned) {
            store.appendEvent(ProjectAgentRunEvents.of(run.getId(), "tenant", 7L, 1, AgentEventType.STEP,
                "{\"kind\":\"EXECUTION_OWNER\",\"epoch\":1}", new Date()));
        }
        return run;
    }
}
