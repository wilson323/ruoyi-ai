package org.ruoyi.ipd.agent.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.ruoyi.ipd.agent.domain.*;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.support.AgentOwnershipTestTransactions;
import reactor.core.scheduler.Schedulers;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class ProjectAgentOwnedCreationTest {
    @Test void newPendingAndMarkerShareTransactionAndLeasePrecedesDatabaseWrite() {
        var store = mock(AgentRunStore.class); var owner = mock(ProjectAgentRunOwnership.class);
        var lease = mock(ProjectAgentRunOwnership.Lease.class);
        when(owner.acquire(anyLong())).thenReturn(Optional.of(lease)); when(lease.held()).thenReturn(true); when(lease.token()).thenReturn(12L);
        when(store.insertRun(any())).thenAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()); return true;
        });
        when(store.appendEvent(any())).thenAnswer(call -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            IpdAgentRunEvent event = call.getArgument(0); assertEquals(1L, event.getSeq());
            assertTrue(event.getPayload().contains("EXECUTION_OWNER")); return true;
        });
        var executor = new ProjectAgentRunExecutor(store, null, new ObjectMapper(), Schedulers.immediate(), System::currentTimeMillis, 1);
        executor.setOwnership(owner); executor.setFinishTransaction(AgentOwnershipTestTransactions.create());
        var row = IpdAgentRun.builder().tenantId("tenant").personId(7L).status("PENDING").version(0).build();
        assertTrue(executor.insertReservedRun(row)); assertEquals(1, row.getVersion()); assertNotNull(row.getId());
        var order = inOrder(owner, store); order.verify(owner).acquire(row.getId()); order.verify(store).insertRun(row); order.verify(store).appendEvent(any());
        verify(lease, never()).close();
    }
    @Test void idempotencyLoserCreatesNoMarkerAndClosesOnlyItsOwnLease() {
        var store = mock(AgentRunStore.class); var owner = mock(ProjectAgentRunOwnership.class); var lease = mock(ProjectAgentRunOwnership.Lease.class);
        when(owner.acquire(anyLong())).thenReturn(Optional.of(lease)); when(lease.held()).thenReturn(true);
        when(store.insertRun(any())).thenReturn(false);
        var executor = new ProjectAgentRunExecutor(store, null, new ObjectMapper(), Schedulers.immediate(), System::currentTimeMillis, 1);
        executor.setOwnership(owner); executor.setFinishTransaction(AgentOwnershipTestTransactions.create());
        assertFalse(executor.insertReservedRun(IpdAgentRun.builder().tenantId("tenant").personId(7L).status("PENDING").version(0).build()));
        verify(store, never()).appendEvent(any()); verify(lease).close();
    }
    @Test void redisFailureDoesNotCreateAnUnownedRun() {
        var store = mock(AgentRunStore.class); var owner = mock(ProjectAgentRunOwnership.class);
        when(owner.acquire(anyLong())).thenThrow(new IllegalStateException("redis unavailable"));
        var executor = new ProjectAgentRunExecutor(store, null, new ObjectMapper(), Schedulers.immediate(), System::currentTimeMillis, 1);
        executor.setOwnership(owner); executor.setFinishTransaction(AgentOwnershipTestTransactions.create());
        assertThrows(IllegalStateException.class, () -> executor.insertReservedRun(IpdAgentRun.builder().status("PENDING").build()));
        verifyNoInteractions(store);
    }
}
