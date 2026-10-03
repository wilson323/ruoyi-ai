package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 保留会话与 transcript 消费者，在官方持久化扩展点只移除结构化内部思考。 */
public final class ProjectAgentDeliveryStateMiddleware implements MiddlewareBase {
    @Override
    public int order() { return Integer.MIN_VALUE; }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).concatWith(Mono.defer(() -> {
            AgentState state = RuntimeContext.resolveAgentState(context, agent);
            if (state != null) state.contextMutable().replaceAll(ProjectAgentDeliveryStateMiddleware::delivery);
            return Mono.<AgentEvent>empty();
        }));
    }

    private static Msg delivery(Msg message) {
        return message.withContent(message.getContent().stream()
            .filter(block -> !(block instanceof ThinkingBlock)).toList());
    }

    private static State persistentCopy(State state) {
        if (state instanceof AgentState agentState) {
            AgentState copy = AgentState.fromJsonString(agentState.toJson());
            copy.contextMutable().replaceAll(ProjectAgentDeliveryStateMiddleware::delivery);
            return copy;
        }
        return state instanceof Msg message ? delivery(message) : state;
    }

    public static AgentStateStore persistentStore(AgentStateStore delegate) {
        return new AgentStateStore() {
            public void save(String user, String session, String key, State state) {
                delegate.save(user, session, key, persistentCopy(state));
            }
            public void save(String user, String session, String key, List<? extends State> states) {
                delegate.save(user, session, key, states.stream().map(ProjectAgentDeliveryStateMiddleware::persistentCopy).toList());
            }
            public boolean supportsVersioning() { return delegate.supportsVersioning(); }
            public <T extends State> VersionedState<T> getVersioned(String user, String session, String key, Class<T> type) {
                return delegate.getVersioned(user, session, key, type);
            }
            public long saveIfVersion(String user, String session, String key, State state, long version) {
                return delegate.saveIfVersion(user, session, key, persistentCopy(state), version);
            }
            public <T extends State> Optional<T> get(String user, String session, String key, Class<T> type) {
                return delegate.get(user, session, key, type);
            }
            public <T extends State> List<T> getList(String user, String session, String key, Class<T> type) {
                return delegate.getList(user, session, key, type);
            }
            public boolean exists(String user, String session) { return delegate.exists(user, session); }
            public void delete(String user, String session) { delegate.delete(user, session); }
            public void delete(String user, String session, String key) { delegate.delete(user, session, key); }
            public Set<String> listSessionIds(String user) { return delegate.listSessionIds(user); }
            public void close() { delegate.close(); }
        };
    }
}
