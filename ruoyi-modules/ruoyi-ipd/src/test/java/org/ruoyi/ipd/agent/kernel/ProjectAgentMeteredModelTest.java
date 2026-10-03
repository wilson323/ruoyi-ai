package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.service.AiModelUsageLedgerService;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentMeteredModelTest {
    static class Recorder implements ProjectAgentEventSink {
        final List<Map<String, Object>> ends = new ArrayList<>();
        public synchronized void onStep(String kind, Map<String, Object> detail) {
            if ("END".equals(detail.get("phase"))) ends.add(detail);
        }
        public void onToolCall(String id, String name) { }
        public void onToolResult(String id, String name, String state) { }
        public void onSource(Map<String, Object> source) { }
        public void onText(String text) { }
        public void onArtifact(String id, String title, String hash, int version) { }
        public void onError(String error) { }
        public void onComplete() { }
        int total(String key) {
            return ends.stream().filter(end -> end.containsKey(key))
                .mapToInt(end -> ((Number) end.get(key)).intValue()).sum();
        }
    }

    private static Model model(Supplier<Flux<ChatResponse>> response) {
        return new Model() {
            public String getModelName() { return "synthetic"; }
            public int getContextWindowSize() { return 8192; }
            public boolean supportsNativeStructuredOutput() { return true; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return response.get();
            }
        };
    }

    private static ChatResponse usage(String id, int input, int output) {
        return ChatResponse.builder().id(id).content(List.of())
            .usage(new ChatUsage(input, output, 0)).build();
    }

    @Test void cumulativeSnapshotsAndRepeatedColdSubscriptionsAreIndependent() {
        var sink = new Recorder();
        var metered = new ProjectAgentMeteredModel(model(() -> Flux.just(
            usage("response", 10, 2), usage("response", 10, 5))), sink);
        var cold = metered.stream(List.of(), null, null);
        cold.blockLast();
        cold.blockLast();
        assertEquals(2, sink.ends.size());
        assertEquals(20, sink.total("inputTokens"));
        assertEquals(10, sink.total("outputTokens"));
        assertNotEquals(sink.ends.get(0).get("modelCallId"), sink.ends.get(1).get("modelCallId"));
    }

    @Test void officialModelCapabilitiesSurviveDecoration() {
        var metered = new ProjectAgentMeteredModel(model(Flux::empty), new Recorder());
        assertEquals(8192, metered.getContextWindowSize());
        assertTrue(metered.supportsNativeStructuredOutput());
        assertTrue(metered.supportsNativeStructuredOutputWithTools());
    }

    @Test void missingUsageDoesNotInventZeroOrReachLedger() {
        var sink = new Recorder();
        new ProjectAgentMeteredModel(model(() -> Flux.just(
            ChatResponse.builder().id("unknown").content(List.of()).build())), sink)
            .stream(List.of(), null, null).blockLast();
        assertFalse(sink.ends.get(0).containsKey("inputTokens"));
        assertFalse(sink.ends.get(0).containsKey("outputTokens"));
        var ledger = mock(AiModelUsageLedgerService.class);
        new ProjectAgentUsageSink(sink, ledger, 123L, "person", "run")
            .onStep("MODEL_CALL", sink.ends.get(0));
        verifyNoInteractions(ledger);
    }

    @Test void errorPersistsOnlyKnownUsageOnceWithErrorOutcome() {
        var sink = new Recorder();
        var metered = new ProjectAgentMeteredModel(model(() -> Flux.concat(
            Flux.just(usage("failed", 3, 1)), Flux.error(new IllegalStateException("synthetic")))), sink);
        assertThrows(IllegalStateException.class, () -> metered.stream(List.of(), null, null).blockLast());
        assertEquals(1, sink.ends.size());
        assertEquals(3, sink.total("inputTokens"));
        assertEquals("ERROR", sink.ends.get(0).get("outcome"));
        var ledger = mock(AiModelUsageLedgerService.class);
        new ProjectAgentUsageSink(sink, ledger, 123L, "person", "run")
            .onStep("MODEL_CALL", sink.ends.get(0));
        verify(ledger).recordUsage(123L, "person", "project_agent", 3, 1, 0L, "error", "run");
    }

    @Test void cancellationPersistsKnownUsageOnce() {
        var sink = new Recorder();
        new ProjectAgentMeteredModel(model(() -> Flux.concat(
            Flux.just(usage("cancelled", 4, 2)), Flux.never())), sink)
            .stream(List.of(), null, null).take(1).blockLast();
        assertEquals(1, sink.ends.size());
        assertEquals(4, sink.total("inputTokens"));
        assertEquals("CANCELLED", sink.ends.get(0).get("outcome"));
    }

    @Test void missingUsageOnErrorAndCancellationStaysUnknown() {
        var error = new Recorder();
        assertThrows(IllegalStateException.class, () -> new ProjectAgentMeteredModel(
            model(() -> Flux.error(new IllegalStateException("synthetic"))), error)
            .stream(List.of(), null, null).blockLast());
        assertFalse(error.ends.get(0).containsKey("inputTokens"));
        var cancelled = new Recorder();
        var subscription = new ProjectAgentMeteredModel(model(Flux::never), cancelled)
            .stream(List.of(), null, null).subscribe();
        subscription.dispose();
        assertEquals(1, cancelled.ends.size());
        assertFalse(cancelled.ends.get(0).containsKey("inputTokens"));
    }

    @Test void retrySubscriptionsAndDistinctProviderResponsesAreNotDoubleCounted() {
        var sink = new Recorder();
        var attempts = new AtomicInteger();
        new ProjectAgentMeteredModel(model(() -> attempts.incrementAndGet() == 1
            ? Flux.concat(Flux.just(usage("failed-attempt", 2, 1)),
                Flux.error(new IllegalStateException("synthetic")))
            : Flux.just(usage("successful-attempt", 10, 5))), sink)
            .stream(List.of(), null, null).retry(1).blockLast();
        assertEquals(2, sink.ends.size());
        assertEquals(12, sink.total("inputTokens"));
        assertEquals(6, sink.total("outputTokens"));
        var internal = new Recorder();
        new ProjectAgentMeteredModel(model(() -> Flux.just(usage("attempt-a", 2, 1),
            usage("attempt-b", 10, 4), usage("attempt-b", 10, 5))), internal)
            .stream(List.of(), null, null).blockLast();
        assertEquals(1, internal.ends.size());
        assertEquals(12, internal.total("inputTokens"));
        assertEquals(6, internal.total("outputTokens"));
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace;

    @Test void fullDefaultMemoryAndMainCallsUseSameLedgerWithoutBridgeDuplicates() {
        var calls = new AtomicInteger();
        var receivedTask = new java.util.concurrent.atomic.AtomicBoolean();
        Model delegate = new Model() {
            public String getModelName() { return "no-network-metering"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                    GenerateOptions options) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return Flux.just(ChatResponse.builder().id("response-" + call)
                        .usage(new ChatUsage(10, 5, 0))
                        .content(List.of(io.agentscope.core.message.ToolUseBlock.builder()
                            .id("wait-call").name("wait_async_results")
                            .input(Map.of("timeout_seconds", 5, "task_ids", "owned-task"))
                            .content("{\"timeout_seconds\":5,\"task_ids\":\"owned-task\"}")
                            .build())).finishReason("tool_calls").build());
                }
                for (Msg message : messages) {
                    for (var block : message.getContent()) {
                        if (block instanceof io.agentscope.core.message.ToolResultBlock result) {
                            for (var output : result.getOutput()) {
                                if (output instanceof io.agentscope.core.message.TextBlock text
                                    && text.getText().contains("owned-background-result")) {
                                    receivedTask.set(true);
                                }
                            }
                        }
                    }
                }
                return Flux.just(ChatResponse.builder().id("response-" + call)
                    .usage(new ChatUsage(10, 5, 0))
                    .content(List.of(io.agentscope.core.message.TextBlock.builder()
                        .text("safe-finished").build())).finishReason("stop").build());
            }
        };
        var recorder = new Recorder();
        var ledger = mock(AiModelUsageLedgerService.class);
        var identity = new org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity(123L, "synthetic", "no-network-metering");
        var sink = new ProjectAgentUsageSink(recorder, ledger, List.of(identity), "person", "run");
        var agent = io.agentscope.harness.agent.HarnessAgent.builder()
            .name("metering-test").workspace(workspace)
            .model(new ProjectAgentMeteredModel(delegate, sink, sink, identity)).maxIters(4)
            .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore()).build();
        var context = io.agentscope.core.agent.RuntimeContext.builder()
            .userId("synthetic-person").sessionId("owned-session").build();
        try {
            var task = agent.getTaskRepository().putTask(context, "owned-task", "worker", "owned-session",
                new io.agentscope.harness.agent.subagent.task.TaskRunSpec.LocalTaskRunSpec(() -> {
                    try { Thread.sleep(1500); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return "owned-background-result";
                }));
            var events = agent.streamEvents(Msg.builder()
                .role(io.agentscope.core.message.MsgRole.USER).textContent("Synthetic wait test").build(),
                context).collectList().block(java.time.Duration.ofSeconds(30));
            assertNotNull(events);
            assertTrue(task.isCompleted());
            assertTrue(receivedTask.get());
            // Same token-free lifecycle STEP as the proposed production EventBridge change.
            events.stream().filter(event -> event instanceof io.agentscope.core.event.ModelCallEndEvent)
                .forEach(event -> sink.onStep("MODEL_CALL", Map.of("phase", "STREAM_END")));
            assertEquals(3, calls.get());
            assertEquals(3, recorder.ends.size());
            assertEquals(30, recorder.total("inputTokens"));
            assertEquals(15, recorder.total("outputTokens"));
            verify(ledger, times(3)).recordUsage(123L, "person", "project_agent", 10, 5, 0L, "ok", "run");
        } finally {
            agent.close();
        }
    }

    @Test void officialCompactionAndFlushShareMeteredModel() {
        var calls = new AtomicInteger();
        var recorder = new Recorder();
        var metered = new ProjectAgentMeteredModel(model(() -> {
            int call = calls.incrementAndGet();
            return Flux.just(ChatResponse.builder().id("compact-" + call)
                .usage(new ChatUsage(10, 5, 0))
                .content(List.of(io.agentscope.core.message.TextBlock.builder()
                    .text("synthetic summary").build())).build());
        }), recorder);
        var manager = new io.agentscope.harness.agent.workspace.WorkspaceManager(workspace);
        var flush = new io.agentscope.harness.agent.memory.MemoryFlushManager(manager, metered);
        var compactor = new io.agentscope.harness.agent.memory.compaction.ConversationCompactor(metered, flush);
        var messages = new ArrayList<Msg>();
        for (int index = 0; index < 6; index++) {
            messages.add(Msg.builder().role(io.agentscope.core.message.MsgRole.USER)
                .textContent("synthetic conversation " + index).build());
        }
        var compacted = compactor.compactIfNeeded(io.agentscope.core.agent.RuntimeContext.empty(), messages,
            io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder()
                .triggerMessages(2).keepMessages(1).keepTokens(0).build(), "agent", "session")
            .block(java.time.Duration.ofSeconds(10));
        assertNotNull(compacted);
        assertTrue(compacted.isPresent());
        assertEquals(2, calls.get());
        assertEquals(2, recorder.ends.size());
        assertEquals(20, recorder.total("inputTokens"));
        assertEquals(10, recorder.total("outputTokens"));
    }

    @Test void defaultChildFactoryUsesParentMeteredModel() {
        var calls = new AtomicInteger();
        var recorder = new Recorder();
        var metered = new ProjectAgentMeteredModel(model(() -> {
            int call = calls.incrementAndGet();
            return Flux.just(ChatResponse.builder().id("child-" + call)
                .usage(new ChatUsage(10, 5, 0))
                .content(List.of(io.agentscope.core.message.TextBlock.builder()
                    .text("synthetic child result").build())).finishReason("stop").build());
        }), recorder);
        var parent = io.agentscope.harness.agent.HarnessAgent.builder()
            .name("metered-parent").workspace(workspace).model(metered)
            .stateStore(new io.agentscope.core.state.InMemoryAgentStateStore()).build();
        var context = io.agentscope.core.agent.RuntimeContext.builder()
            .userId("synthetic-person").sessionId("owned-parent-session").build();
        io.agentscope.core.agent.Agent child = null;
        try {
            child = parent.getSubagentAgentManager().createAgent("general-purpose", context);
            var result = parent.getSubagentAgentManager()
                .invokeAgent(child, "owned-child-session", "synthetic-person", "Synthetic child prompt", context)
                .block(java.time.Duration.ofSeconds(30));
            assertNotNull(result);
            assertTrue(calls.get() > 0);
            assertEquals(calls.get(), recorder.ends.size());
            assertEquals(calls.get() * 10, recorder.total("inputTokens"));
            assertEquals(calls.get() * 5, recorder.total("outputTokens"));
        } finally {
            if (child instanceof io.agentscope.harness.agent.HarnessAgent harness) harness.close();
            parent.close();
        }
    }

    @Test void reportedUsageSurvivesMembershipWithdrawalButNewCallsDoNot() {
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        var providerCalls = new AtomicInteger();
        var recorder = new Recorder();
        var ledger = mock(AiModelUsageLedgerService.class);
        var identity = new org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity(123L, "synthetic", "synthetic");
        var accounting = new ProjectAgentUsageSink(recorder, ledger, List.of(identity), "person", "run");
        var spec = new ProjectAgentRunSpec(1L, 2L, "tenant", 3L, null, "synthetic", List.of(), List.of(),
            new org.ruoyi.chat.kernel.KernelModelRequest("synthetic", "synthetic", "synthetic", "https://synthetic.example"), null);
        var execution = new ProjectAgentRuntimeAccessSink(accounting, spec, ignored -> {
            if (!active.get()) throw new IllegalStateException("synthetic membership withdrawn");
        });
        var metered = new ProjectAgentMeteredModel(model(() -> {
            providerCalls.incrementAndGet();
            active.set(false);
            return Flux.just(usage("response", 10, 5));
        }), execution, execution.checkpointOwnership(), identity);
        metered.stream(List.of(), null, null).blockLast();
        verify(ledger).recordUsage(123L, "person", "project_agent", 10, 5, 0L, "ok", "run");
        assertEquals(1, recorder.ends.size());
        assertThrows(IllegalStateException.class, () -> metered.stream(List.of(), null, null).blockLast());
        assertThrows(IllegalStateException.class, execution::requireActiveOwnership);
        assertEquals(1, providerCalls.get());
        verifyNoMoreInteractions(ledger);
    }

    @Test void reportedUsageCannotWriteAfterOriginalRunOwnershipIsLost() {
        var owned = new java.util.concurrent.atomic.AtomicBoolean(true);
        var ownerSink = mock(ProjectAgentEventSink.class);
        doAnswer(invocation -> {
            if (!owned.get()) throw new IllegalStateException("synthetic owner lost");
            return ((Supplier<?>) invocation.getArgument(0)).get();
        }).when(ownerSink).withActiveOwnership(any());
        var ledger = mock(AiModelUsageLedgerService.class);
        var accounting = new ProjectAgentUsageSink(ownerSink, ledger, 123L, "person", "run");
        var metered = new ProjectAgentMeteredModel(model(() -> {
            owned.set(false);
            return Flux.just(usage("response", 10, 5));
        }), new Recorder(), accounting);
        assertThrows(IllegalStateException.class, () -> metered.stream(List.of(), null, null).blockLast());
        verifyNoInteractions(ledger);
    }
    @Test void actualFallbackIdentityAppearsOnStartAndFailedEndWithoutFakeUsage() {
        var events = new ArrayList<Map<String,Object>>();
        var recorder = new Recorder() {
            @Override public void onStep(String kind, Map<String,Object> detail) { events.add(detail); }
        };
        var identity = new org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity(202L, "zhipu", "GLM-5.3-Flash");
        var ledger = mock(AiModelUsageLedgerService.class);
        var sink = new ProjectAgentUsageSink(recorder, ledger, List.of(identity), "person", "run");
        var metered = new ProjectAgentMeteredModel(model(() -> Flux.error(new IllegalStateException("synthetic rejection"))), sink, sink, identity);
        assertThrows(IllegalStateException.class, () -> metered.stream(List.of(), null, null).blockLast());
        assertEquals(2, events.size());
        for (var event : events) {
            assertEquals("202", event.get("modelConfigId"));
            assertEquals("zhipu", event.get("providerCode"));
            assertEquals("GLM-5.3-Flash", event.get("modelName"));
            assertFalse(event.containsKey("inputTokens"));
        }
        assertEquals("START", events.get(0).get("phase"));
        assertEquals("ERROR", events.get(1).get("outcome"));
        assertEquals(false, events.get(1).get("usageAvailable"));
        verifyNoInteractions(ledger);
    }
}
