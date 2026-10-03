package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.team.TeamTask;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelScopeKey;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentOfficialCollaborationTest {
    @TempDir Path root;

    @Test void realOfficialTaskCreateReadClaimAndRevocationKeepBusinessAuthority() {
        var active = new AtomicBoolean(true);
        var sink = ownershipSink(active);
        var scope = KernelScopeKey.of("101", "201", "project-agent", "301");
        var assembly = ProjectAgentOfficialCollaboration.create(scope, new InMemoryStore(),
            new LocalFilesystem(root, true, 16), sink);
        var client = assembly.teamClient();
        var context = assembly.teamContext();
        var task = client.createTask(context.namespace(), context.teamName(), "知识证据核对",
            "只读核对出处", List.of(), "").block();
        assertNotNull(task);
        assertEquals(task.taskId(), client.listTasks(context.namespace(), context.teamName()).block().get(0).taskId());
        var claimed = client.claimTask(context.namespace(), context.teamName(), task.taskId(), "lead", task.version()).block();
        assertEquals(TeamTask.IN_PROGRESS, claimed.state());
        assertEquals("lead", claimed.owner());
        active.set(false);
        assertThrows(IllegalStateException.class, () -> client.completeTask(context.namespace(), context.teamName(), task.taskId(), "完成").block());
        assertThrows(IllegalStateException.class, () -> assembly.messageBus().queuePush("inbox", Map.of("content", "x")).block());
    }

    @Test void asyncRegistryAndMessageBusWriteAndReadRealWorkspaceFilesWithoutCrossRunExposure() {
        var store = new InMemoryStore();
        var fs = new LocalFilesystem(root, true, 16);
        var sink = ownershipSink(new AtomicBoolean(true));
        var first = ProjectAgentOfficialCollaboration.create(KernelScopeKey.of("101", "201", "project-agent", "301"), store, fs, sink);
        var second = ProjectAgentOfficialCollaboration.create(KernelScopeKey.of("101", "201", "project-agent", "302"), store, fs, sink);
        first.messageBus().queuePush("inbox", Map.of("content", "真实消息")).block();
        assertEquals(0, second.messageBus().queueDrain("inbox", 10).block().size());
        assertEquals(1, first.messageBus().queueDrain("inbox", 10).block().size());
        first.asyncToolRegistry().register(new AsyncToolRecord("async-1", "session-1", "execute", "call-1",
            AsyncToolRecord.RUNNING, Instant.now().minusSeconds(60))).block();
        assertEquals(1, first.asyncToolRegistry().findStale("session-1", Duration.ofSeconds(1)).block().size());
        first.asyncToolRegistry().complete("async-1", "执行结果").block();
        assertEquals(0, first.asyncToolRegistry().findStale("session-1", Duration.ofSeconds(1)).block().size());
    }

    @Test void deliveryCannotFabricateAReceiptAndNamespacesRejectTraversal() {
        var sink = ownershipSink(new AtomicBoolean(true));
        var target = ProjectAgentOfficialCollaboration.guardArtifactTarget((context, request) -> null, sink);
        assertThrows(NullPointerException.class, () -> target.deliver(null, null));
        var store = new ProjectAgentOfficialCollaboration.OwnedRunStore(new InMemoryStore(),
            KernelScopeKey.of("101", "201", "project-agent", "301"), sink);
        assertThrows(IllegalArgumentException.class, () -> store.put(List.of(".."), "x", Map.of()));
    }

    private ProjectAgentEventSink ownershipSink(AtomicBoolean active) {
        var sink = mock(ProjectAgentEventSink.class, CALLS_REAL_METHODS);
        doAnswer(invocation -> { if (!active.get()) throw new IllegalStateException("ownership revoked"); return null; })
            .when(sink).requireActiveOwnership();
        return sink;
    }
}
