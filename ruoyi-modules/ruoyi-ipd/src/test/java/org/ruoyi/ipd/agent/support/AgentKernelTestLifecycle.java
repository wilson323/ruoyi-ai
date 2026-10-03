package org.ruoyi.ipd.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicLong;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.service.ProjectAgentRunHandle;
import org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership;
import static org.mockito.Mockito.*;

/** Actual RunHandle terminal receipt/cleanup contract with in-memory transaction boundaries; not SQL/Redis acceptance. */
public final class AgentKernelTestLifecycle {
    private static final AtomicLong IDS = new AtomicLong(9800000);
    private AgentKernelTestLifecycle() { }
    public static ProjectAgentRunHandle create() {
        var runs = new InMemoryAgentRunStore();
        long id = IDS.incrementAndGet();
        var row = IpdAgentRun.builder().id(id).tenantId("kernel-fixture").personId(7L).projectId(9L)
            .status("RUNNING").version(1).idempotencyKey("kernel-fixture-" + id).build();
        runs.insertRun(row);
        var lease = mock(ProjectAgentRunOwnership.Lease.class);
        when(lease.held()).thenReturn(true);
        var handle = new ProjectAgentRunHandle(row, runs, new ObjectMapper(), System::currentTimeMillis, () -> { });
        handle.setFinishTransaction(AgentOwnershipTestTransactions.create());
        handle.setOwnership(lease, 1);
        return handle;
    }
}
