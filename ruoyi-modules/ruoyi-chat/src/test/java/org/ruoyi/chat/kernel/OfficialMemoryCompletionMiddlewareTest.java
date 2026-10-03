package org.ruoyi.chat.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class OfficialMemoryCompletionMiddlewareTest {
    @Test void waitsForNativeCompletionDispatchedWorkBeforePropagatingCompletion() {
        var finished = new AtomicBoolean();
        new OfficialMemoryCompletionMiddleware().onAgent(null, RuntimeContext.empty(), new AgentInput(List.of()),
            input -> Flux.<io.agentscope.core.event.AgentEvent>empty().doOnComplete(() -> {
                MemoryBackgroundTasks.begin();
                new Thread(() -> {
                    try { Thread.sleep(50); finished.set(true); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { MemoryBackgroundTasks.end(); }
                }).start();
            })).blockLast();
        assertThat(finished).isTrue();
    }

    @Test void interruptedDrainFailsExplicitlyInsteadOfReportingSuccessfulCompletion() {
        MemoryBackgroundTasks.begin();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new OfficialMemoryCompletionMiddleware().onAgent(null,
                RuntimeContext.empty(), new AgentInput(List.of()), input -> Flux.empty()).blockLast())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not finish before sandbox release");
        } finally { Thread.interrupted(); MemoryBackgroundTasks.end(); }
    }
    @Test void fiveSecondCandidateRejectsStillRunningLegitimateWork() {
        MemoryBackgroundTasks.begin();
        try {
            assertThatThrownBy(() -> new OfficialMemoryCompletionMiddleware().onAgent(null,
                RuntimeContext.empty(), new AgentInput(List.of()), input -> Flux.empty()).blockLast())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not finish before sandbox release");
        } finally { MemoryBackgroundTasks.end(); }
    }

    @Test void nativeQuiescenceDoesNotProveBackgroundWorkSucceeded() {
        var failed = new AtomicBoolean();
        new OfficialMemoryCompletionMiddleware().onAgent(null, RuntimeContext.empty(), new AgentInput(List.of()),
            input -> Flux.<io.agentscope.core.event.AgentEvent>empty().doOnComplete(() -> {
                MemoryBackgroundTasks.begin();
                try { failed.set(true); } finally { MemoryBackgroundTasks.end(); }
            })).blockLast();
        // The native counter carries no task result: completing is not a maintenance success claim.
        assertThat(failed).isTrue();
    }

}
