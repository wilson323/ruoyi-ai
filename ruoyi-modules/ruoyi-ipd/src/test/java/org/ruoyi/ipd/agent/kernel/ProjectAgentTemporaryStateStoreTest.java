package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.state.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.*;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentTemporaryStateStoreTest {
    @TempDir Path workspace;
    private static final KernelScopeKey.Scope SCOPE = KernelScopeKey.of("9", "7", ProjectAgentConstants.AGENT_ID, "1234567");
    private static final KernelScopeKey.Scope OTHER = KernelScopeKey.of("9", "7", ProjectAgentConstants.AGENT_ID, "1234568");
    private static final KernelScopeKey.Scope FOREIGN_USER = KernelScopeKey.of("9", "8", ProjectAgentConstants.AGENT_ID, "1234567");
    private AgentState state(KernelScopeKey.Scope scope) {
        return AgentState.builder().userId(scope.userId()).sessionId(scope.sessionId())
            .addMessage(Msg.builder().role(MsgRole.ASSISTANT).content(List.of(ThinkingBlock.builder().thinking("private reasoning").build())).build()).build();
    }
    @Test void sealedRunIsDeletedAndLateWritesCannotRecreateItOrTouchAdjacentScope() {
        var nativeStore = new InMemoryAgentStateStore();
        nativeStore.save(OTHER.userId(), OTHER.sessionId(), "agent_state", state(OTHER));
        var store = new ProjectAgentTemporaryStateStore(nativeStore, SCOPE, new Sink());
        assertEquals(1, store.saveIfVersion(SCOPE.userId(), SCOPE.sessionId(), "agent_state", state(SCOPE), 0));
        assertTrue(nativeStore.exists(SCOPE.userId(), SCOPE.sessionId()));
        store.sealAndDelete();
        assertFalse(nativeStore.exists(SCOPE.userId(), SCOPE.sessionId()));
        assertTrue(nativeStore.exists(OTHER.userId(), OTHER.sessionId()));
        assertThrows(IllegalStateException.class, () -> store.saveIfVersion(SCOPE.userId(), SCOPE.sessionId(), "agent_state", state(SCOPE), 0));
        assertThrows(IllegalArgumentException.class, () -> store.delete(OTHER.userId(), OTHER.sessionId()));
        assertFalse(nativeStore.exists(SCOPE.userId(), SCOPE.sessionId()));
    }
    @Test void officialSandboxAndChildSlotsStayUnderTheirOwningRunAndAreCleaned() {
        var nativeStore = new InMemoryAgentStateStore();
        String child = "sub-12345678-1234-1234-1234-123456789abc";
        var lineage = new ProjectAgentChildLineageRegistry(SCOPE.toRuntimeContext(), () -> { });
        var actor = mock(io.agentscope.core.agent.Agent.class);
        lineage.registerFactoryChild(actor);
        lineage.registerInvocation(actor, io.agentscope.core.agent.RuntimeContext.builder(SCOPE.toRuntimeContext())
            .sessionId(child).build());
        var first = new ProjectAgentTemporaryStateStore(nativeStore, SCOPE, new Sink(), lineage);
        var second = new ProjectAgentTemporaryStateStore(nativeStore, OTHER, new Sink());
        first.save(SCOPE.userId(), child, "test", state(SCOPE));
        first.save(null, "sandbox/session/" + SCOPE.sessionId(), "sandbox_state", state(SCOPE));
        first.save(null, "sandbox/session/" + child, "sandbox_state", state(SCOPE));
        assertTrue(first.get(SCOPE.userId(), child, "test", AgentState.class).isPresent());
        assertEquals(SCOPE.userId(), OTHER.userId(), "同一用户相邻run应通过session隔离，不能当成不同用户反例");
        assertThrows(IllegalArgumentException.class,
            () -> second.get(OTHER.userId(), child, "test", AgentState.class));
        assertThrows(IllegalArgumentException.class,
            () -> first.get(SCOPE.userId(), "sub-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "test", AgentState.class));
        assertFalse(nativeStore.exists(SCOPE.userId(), child));
        assertFalse(nativeStore.exists(null, "sandbox/session/" + child));
        assertThrows(IllegalArgumentException.class,
            () -> first.get(null, "sandbox/session/" + OTHER.sessionId(), "sandbox_state", AgentState.class));
        assertThrows(IllegalArgumentException.class,
            () -> first.get(FOREIGN_USER.userId(), child, "test", AgentState.class));
        first.sealAndDelete();
        assertTrue(nativeStore.listSessionIds(SCOPE.userId()).isEmpty());
    }

    @Test void nativeCasConflictThrowsBeforeSdkOverwriteFallbackCanRun() {
        var nativeStore = spy(new InMemoryAgentStateStore());
        var store = new ProjectAgentTemporaryStateStore(nativeStore, SCOPE, new Sink());
        store.saveIfVersion(SCOPE.userId(), SCOPE.sessionId(), "agent_state", state(SCOPE), 0);
        assertThrows(IllegalStateException.class, () -> store.saveIfVersion(SCOPE.userId(), SCOPE.sessionId(), "agent_state", state(SCOPE), 0));
        assertThrows(IllegalStateException.class, () -> store.saveIfVersion(SCOPE.userId(), SCOPE.sessionId(), "agent_state", state(SCOPE), AgentStateStore.UNVERSIONED));
        assertThrows(IllegalStateException.class, () -> store.save(SCOPE.userId(), SCOPE.sessionId(), "agent_state", state(SCOPE)));
        verify(nativeStore, never()).saveIfVersion(any(), any(), any(), any(), eq(AgentStateStore.UNVERSIONED));
        verify(nativeStore, never()).save(any(), any(), any(), any(State.class));
        assertEquals(1, nativeStore.getVersioned(SCOPE.userId(), SCOPE.sessionId(), "agent_state", AgentState.class).version());
    }
    @Test void cancelCleansOwnedCheckpointAndStaleEpochCannotDeleteSuccessorState() {
        var runs = new org.ruoyi.ipd.agent.support.InMemoryAgentRunStore();
        var row = org.ruoyi.ipd.agent.domain.IpdAgentRun.builder().id(1234567L).tenantId("tenant").personId(7L).projectId(9L)
            .status("RUNNING").version(1).idempotencyKey("temporary-state").build();
        runs.insertRun(row);
        var lease = mock(org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership.Lease.class);
        when(lease.held()).thenReturn(true);
        var handle = new org.ruoyi.ipd.agent.service.ProjectAgentRunHandle(row, runs, new com.fasterxml.jackson.databind.ObjectMapper(),
            System::currentTimeMillis, () -> { });
        handle.setFinishTransaction(org.ruoyi.ipd.agent.support.AgentOwnershipTestTransactions.create()); handle.setOwnership(lease, 1);
        var nativeStore = new InMemoryAgentStateStore();
        var temporary = new ProjectAgentTemporaryStateStore(nativeStore, SCOPE, handle);
        handle.registerTemporaryStateCleanup(temporary::sealAndDelete);
        temporary.saveIfVersion(SCOPE.userId(), SCOPE.sessionId(), "agent_state", state(SCOPE), 0);
        assertTrue(runs.transition(row.getId(), Set.of(org.ruoyi.ipd.agent.model.AgentRunStatus.RUNNING),
            org.ruoyi.ipd.agent.model.AgentRunStatus.CANCEL_REQUESTED, null, new Date()));
        assertTrue(handle.cancel()); assertFalse(nativeStore.exists(SCOPE.userId(), SCOPE.sessionId()));
        assertThrows(org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership.OwnershipLost.class,
            () -> temporary.saveIfVersion(SCOPE.userId(), SCOPE.sessionId(), "agent_state", state(SCOPE), 0));

        var secondRow = org.ruoyi.ipd.agent.domain.IpdAgentRun.builder().id(1234568L).tenantId("tenant").personId(7L).projectId(9L)
            .status("RUNNING").version(1).idempotencyKey("successor-state").build();
        runs.insertRun(secondRow);
        var stale = new org.ruoyi.ipd.agent.service.ProjectAgentRunHandle(secondRow, runs, new com.fasterxml.jackson.databind.ObjectMapper(),
            System::currentTimeMillis, () -> { });
        stale.setFinishTransaction(org.ruoyi.ipd.agent.support.AgentOwnershipTestTransactions.create()); stale.setOwnership(lease, 1);
        var otherTemporary = new ProjectAgentTemporaryStateStore(nativeStore, OTHER, stale);
        stale.registerTemporaryStateCleanup(otherTemporary::sealAndDelete);
        nativeStore.save(OTHER.userId(), OTHER.sessionId(), "agent_state", state(OTHER));
        runs.claimEpoch(secondRow.getId(), 1, Set.of(org.ruoyi.ipd.agent.model.AgentRunStatus.RUNNING));
        assertThrows(org.ruoyi.ipd.agent.service.ProjectAgentRunOwnership.OwnershipLost.class, stale::releaseTemporaryState);
        assertTrue(nativeStore.exists(OTHER.userId(), OTHER.sessionId()));
    }

    @Test void actualSdkSavesThinkingBeforeFinalizerDeletesCheckpointAndReportsCompletion() throws Exception {
        var writes = new AtomicInteger(); var sawThinking = new AtomicBoolean();
        var nativeStore = new InMemoryAgentStateStore() {
            public long saveIfVersion(String u, String s, String key, State value, long expected) {
                long version = super.saveIfVersion(u, s, key, value, expected);
                writes.incrementAndGet();
                if (value instanceof AgentState agent) sawThinking.set(agent.getContext().stream()
                    .flatMap(message -> message.getContent().stream()).anyMatch(ThinkingBlock.class::isInstance));
                return version;
            }
        };
        nativeStore.save(OTHER.userId(), OTHER.sessionId(), "agent_state", state(OTHER));
        var sink = new Sink() {
            public void onComplete() { assertFalse(nativeStore.exists(SCOPE.userId(), SCOPE.sessionId())); super.onComplete(); }
        };
        var execution = kernel(nativeStore).execute(spec(), sink);
        try {
            assertTrue(sink.done.await(40, TimeUnit.SECONDS));
            assertTrue(sink.complete); assertTrue(sink.errors.isEmpty(), sink.errors.toString());
            assertTrue(writes.get() > 0); assertTrue(sawThinking.get());
            assertFalse(nativeStore.exists(SCOPE.userId(), SCOPE.sessionId()));
            assertTrue(nativeStore.exists(OTHER.userId(), OTHER.sessionId()));
        } finally { execution.dispose(); }
    }
    @Test void nativeStateSaveFailureCannotReportSuccessfulBusinessCompletion() throws Exception {
        var nativeStore = new InMemoryAgentStateStore() {
            public long saveIfVersion(String u, String s, String key, State value, long expected) {
                throw new IllegalStateException("native state save failed");
            }
        };
        var sink = new Sink(); var execution = kernel(nativeStore).execute(spec(), sink);
        try {
            assertTrue(sink.done.await(40, TimeUnit.SECONDS));
            assertFalse(sink.complete); assertFalse(sink.errors.isEmpty());
        } finally { execution.dispose(); }
    }
    private AgentScopeProjectAgentKernel kernel(AgentStateStore store) {
        Model model = mock(Model.class); when(model.getModelName()).thenReturn("test");
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(ChatResponse.builder().finishReason("stop")
            .content(List.of(ThinkingBlock.builder().thinking("private reasoning").build(), TextBlock.builder().text("final answer").build())).build()));
        var kernel = new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler((key, context) -> model),
            (p, d, q) -> new RetrievalContext(0, 0, ""), workspace, 2);
        kernel.setStateStore(store); return kernel;
    }
    private ProjectAgentRunSpec spec() {
        return new ProjectAgentRunSpec(1234567L, 9L, "tenant", 7L, null, "question", List.of(), List.of(),
            new KernelModelRequest("test", "openai", "test", "https://example.invalid/v1"), Duration.ofSeconds(30));
    }
    private static class Sink implements ProjectAgentEventSink {
        final CountDownLatch done = new CountDownLatch(1); final List<String> errors = new CopyOnWriteArrayList<>();
        volatile boolean complete;
        public void onStep(String kind, Map<String, Object> detail) { }
        public void onToolCall(String id, String name) { }
        public void onToolResult(String id, String name, String result) { }
        public void onSource(Map<String, Object> source) { }
        public void onText(String text) { }
        public void onArtifact(String id, String title, String hash, int version) { }
        public void onError(String error) { errors.add(error); done.countDown(); }
        public void onComplete() { complete = true; done.countDown(); }
    }
}
