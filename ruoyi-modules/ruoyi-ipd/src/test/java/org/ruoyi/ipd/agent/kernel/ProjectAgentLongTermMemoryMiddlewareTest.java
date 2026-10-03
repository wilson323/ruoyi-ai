package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentLongTermMemoryMiddlewareTest {
    private final ProjectScopedLongTermMemory memory = mock(ProjectScopedLongTermMemory.class);
    private final Agent agent = mock(Agent.class);
    private final AgentState state = mock(AgentState.class);
    private final RuntimeContext context = RuntimeContext.builder().userId("p1:u2").sessionId("a1:s3").build();
    private final List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("要表格").build());
    private final AgentInput input = new AgentInput(messages);

    private ProjectAgentLongTermMemoryMiddleware middleware() {
        when(agent.getAgentState()).thenReturn(state);
        when(state.getContext()).thenReturn(messages);
        when(memory.retrieve(any())).thenReturn(Mono.empty());
        return new ProjectAgentLongTermMemoryMiddleware(memory);
    }

    private AgentEvent rootSuccess() {
        return new io.agentscope.core.event.AgentResultEvent(Msg.builder()
            .role(MsgRole.ASSISTANT).textContent("done")
            .generateReason(io.agentscope.core.message.GenerateReason.MODEL_STOP).build());
    }

    @Test void completionWithoutResultDoesNotExtractMemory() {
        var middleware = middleware();
        assertThat(middleware.onAgent(agent, context, input, ignored -> Flux.empty())
            .collectList().block(Duration.ofSeconds(2))).isEmpty();
        verify(memory, never()).record(any());
    }

    @Test void childSuccessAloneDoesNotExtractRootMemory() {
        var middleware = middleware();
        AgentEvent child = rootSuccess().withSource("parent/child");
        assertThat(middleware.onAgent(agent, context, input, ignored -> Flux.just(child))
            .collectList().block(Duration.ofSeconds(2))).containsExactly(child);
        verify(memory, never()).record(any());
    }

    @Test void childPauseStillOverridesRootSuccess() {
        var middleware = middleware();
        AgentEvent child = new io.agentscope.core.event.RequireUserConfirmEvent("reply", List.of())
            .withSource("parent/child");
        middleware.onAgent(agent, context, input, ignored -> Flux.just(child, rootSuccess()))
            .blockLast(Duration.ofSeconds(2));
        verify(memory, never()).record(any());
    }

    @Test void successWaitsForRecordingCompletion() {
        var middleware = middleware();
        Sinks.Empty<Void> saved = Sinks.empty();
        when(memory.record(messages)).thenReturn(saved.asMono());
        AtomicBoolean completed = new AtomicBoolean();
        var subscription = middleware.onAgent(agent, context, input, ignored -> Flux.just(rootSuccess()))
            .subscribe(ignored -> {}, error -> fail(error.getMessage()), () -> completed.set(true));
        assertThat(completed).isFalse();
        verify(memory).record(messages);
        saved.tryEmitEmpty();
        assertThat(completed).isTrue();
        subscription.dispose();
    }

    @Test void recordFailureFailsMainChain() {
        var middleware = middleware();
        when(memory.record(messages)).thenReturn(Mono.error(new IllegalStateException("record failed")));
        assertThatThrownBy(() -> middleware.onAgent(agent, context, input, ignored -> Flux.just(rootSuccess()))
            .blockLast(Duration.ofSeconds(2))).hasMessageContaining("record failed");
    }

    @Test void recallFailurePreventsModelCallAndRecording() {
        var middleware = middleware();
        when(memory.retrieve(any())).thenReturn(Mono.error(new IllegalStateException("recall failed")));
        AtomicBoolean called = new AtomicBoolean();
        assertThatThrownBy(() -> middleware.onAgent(agent, context, input, ignored -> {
            called.set(true); return Flux.empty();
        }).blockLast(Duration.ofSeconds(2))).hasMessageContaining("recall failed");
        assertThat(called).isFalse();
        verify(memory, never()).record(any());
    }

    @Test void cancellationAndMainFailureDoNotRecord() {
        var middleware = middleware();
        Flux<AgentEvent> pending = middleware.onAgent(agent, context, input, ignored -> Flux.never());
        verifyNoInteractions(memory);
        var subscription = pending.subscribe();
        subscription.dispose();
        verify(memory, never()).record(any());
        assertThatThrownBy(() -> middleware.onAgent(agent, context, input,
            ignored -> Flux.error(new IllegalStateException("main failed")))
            .blockLast(Duration.ofSeconds(2))).hasMessageContaining("main failed");
        verify(memory, never()).record(any());
    }
    @Test void officialUserAndExternalPausesDoNotExtractMemory() {
        var middleware = middleware();
        for (AgentEvent pause : List.of(
            new io.agentscope.core.event.RequireUserConfirmEvent("reply", List.of()),
            new io.agentscope.core.event.RequireExternalExecutionEvent("reply", List.of()))) {
            var events = middleware.onAgent(agent, context, input, ignored -> Flux.just(pause))
                .collectList().block(Duration.ofSeconds(2));
            assertThat(events).containsExactly(pause);
        }
        verify(memory, never()).record(any());
    }

    @Test void officialNonSuccessfulGenerateReasonsDoNotExtractMemory() {
        var middleware = middleware();
        for (var reason : io.agentscope.core.message.GenerateReason.values()) {
            if (reason == io.agentscope.core.message.GenerateReason.MODEL_STOP
                || reason == io.agentscope.core.message.GenerateReason.STRUCTURED_OUTPUT) continue;
            AgentEvent result = new io.agentscope.core.event.AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT).textContent("paused").generateReason(reason).build());
            assertThat(middleware.onAgent(agent, context, input, ignored -> Flux.just(result))
                .collectList().block(Duration.ofSeconds(2))).containsExactly(result);
        }
        verify(memory, never()).record(any());
    }

    @Test void official203StructuredOutputIsSuccessfulCompletion() {
        var middleware = middleware();
        when(memory.record(messages)).thenReturn(Mono.empty());
        var success = new io.agentscope.core.event.AgentResultEvent(Msg.builder()
            .role(MsgRole.ASSISTANT).textContent("done")
            .generateReason(io.agentscope.core.message.GenerateReason.STRUCTURED_OUTPUT).build());
        middleware.onAgent(agent, context, input, ignored -> Flux.just(success)).blockLast();
        verify(memory).record(messages);
    }

    @Test void resumedInvocationRecordsOnlyAfterSuccessfulOfficialResult() {
        var middleware = middleware();
        when(memory.record(messages)).thenReturn(Mono.empty());
        var pause = new io.agentscope.core.event.RequireUserConfirmEvent("reply", List.of());
        middleware.onAgent(agent, context, input, ignored -> Flux.just(pause)).blockLast();
        verify(memory, never()).record(any());
        var success = new io.agentscope.core.event.AgentResultEvent(Msg.builder()
            .role(MsgRole.ASSISTANT).textContent("done")
            .generateReason(io.agentscope.core.message.GenerateReason.MODEL_STOP).build());
        middleware.onAgent(agent, context, input, ignored -> Flux.just(success)).blockLast();
        verify(memory).record(messages);
    }

}
