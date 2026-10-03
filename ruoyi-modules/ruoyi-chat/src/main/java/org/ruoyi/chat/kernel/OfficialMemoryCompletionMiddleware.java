package org.ruoyi.chat.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps the call sandbox alive until native completion-triggered memory work has drained. */
public final class OfficialMemoryCompletionMiddleware implements MiddlewareBase {
    // SDK 2.0.3 sorts descending: MAX_VALUE is the outermost middleware.
    @Override public int order() { return Integer.MAX_VALUE; }

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).concatWith(Mono.defer(() -> {
            if (!MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS)) {
                return Mono.error(new IllegalStateException("Official memory background work did not finish before sandbox release"));
            }
            return Mono.<AgentEvent>empty();
        }));
    }
}
