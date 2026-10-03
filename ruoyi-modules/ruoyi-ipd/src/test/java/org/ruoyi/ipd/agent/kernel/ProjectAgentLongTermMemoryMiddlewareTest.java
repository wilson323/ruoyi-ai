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

    private final ProjectAgentEventSink sink = mock(ProjectAgentEventSink.class);

    private ProjectAgentLongTermMemoryMiddleware middleware() {
        when(agent.getAgentState()).thenReturn(state);
        when(state.getContext()).thenReturn(messages);
        when(memory.retrieve(any())).thenReturn(Mono.empty());
        return new ProjectAgentLongTermMemoryMiddleware(memory, sink);
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

    /**
     * run 2106378468009717761 的直接反证：答案正文与 TEXT_MESSAGE_END 早已推送落库，
     * 事后一个记忆抽取超时经 concatWith 冒泡进主流，整轮被改判 FAILED/STREAM_ERROR，
     * 对外文案「模型输出中断」与事实相反。记忆是回答<b>之后</b>的副作用，失败不得改写终态，
     * 但必须留下可查、可重试的持久回执——既不吞，也不伪装成功。
     */
    @Test void recordFailureDoesNotFailMainChainButWritesRetryableReceipt() {
        var middleware = middleware();
        var timeout = new java.util.concurrent.TimeoutException("stream stalled");
        when(memory.record(messages)).thenReturn(Mono.error(timeout));
        when(memory.lastOutcome()).thenReturn(
            new ProjectScopedLongTermMemory.RecordOutcome(0, 0, timeout));

        // 主链正常收尾：事件一个不少，也不抛错。
        var success = rootSuccess();
        var events = middleware.onAgent(agent, context, input, ignored -> Flux.just(success))
            .collectList().block(Duration.ofSeconds(2));
        assertThat(events).containsExactly(success);

        @SuppressWarnings("unchecked")
        var receiptCaptor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(sink).onMemoryReceipt(receiptCaptor.capture());
        assertThat(receiptCaptor.getValue())
            .containsEntry("status", "WRITE_FAILED")
            .containsEntry("errorType", "TimeoutException")
            .containsEntry("retryable", true)
            .containsEntry("saved", 0);
    }

    /** 记忆写成功同样要留痕，否则「写了多少」永远无法与「没写」区分。 */
    @Test void successfulRecordWritesWrittenReceiptWithCounts() {
        var middleware = middleware();
        when(memory.record(messages)).thenReturn(Mono.empty());
        when(memory.lastOutcome()).thenReturn(
            new ProjectScopedLongTermMemory.RecordOutcome(3, 2, null));

        middleware.onAgent(agent, context, input, ignored -> Flux.just(rootSuccess()))
            .blockLast(Duration.ofSeconds(2));

        @SuppressWarnings("unchecked")
        var receiptCaptor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(sink).onMemoryReceipt(receiptCaptor.capture());
        assertThat(receiptCaptor.getValue())
            .containsEntry("status", "WRITTEN")
            .containsEntry("extracted", 3)
            .containsEntry("saved", 2)
            .containsEntry("retryable", false);
    }

    /**
     * 回执写失败是另一类故障：连「失败过」这个事实都没留下，必须向上抛。
     * 若此条也不抛，一次性抖动就变成永久无痕的数据缺失，正是本次要根除的机制。
     */
    @Test void receiptWriteFailureStillFailsMainChain() {
        var middleware = middleware();
        var timeout = new java.util.concurrent.TimeoutException("stream stalled");
        when(memory.record(messages)).thenReturn(Mono.error(timeout));
        when(memory.lastOutcome()).thenReturn(
            new ProjectScopedLongTermMemory.RecordOutcome(0, 0, timeout));
        org.mockito.Mockito.doThrow(new IllegalStateException("receipt sink is not bound"))
            .when(sink).onMemoryReceipt(any());

        assertThatThrownBy(() -> middleware.onAgent(agent, context, input, ignored -> Flux.just(rootSuccess()))
            .blockLast(Duration.ofSeconds(2))).hasMessageContaining("receipt sink is not bound");
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
