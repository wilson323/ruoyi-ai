package org.ruoyi.chat.kernel;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class NativeChatStateInitializationTest {
    @TempDir Path workspace;
    @Test void historyIsImportedOnlyOnceAndNativeStateBecomesAuthority() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        KernelScopeKey.Scope scope = KernelScopeKey.of("chat", "42", "employee", "100");
        AtomicInteger reads = new AtomicInteger();
        Supplier<List<Msg>> history = () -> { reads.incrementAndGet(); return List.of(
            Msg.builder().role(MsgRole.USER).textContent("prior message").build()); };
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(mock(Model.class), "stub:test", () -> store, workspace)) {
            var initialize = AgentScopeChatKernel.class.getDeclaredMethod("initializeHistoryIfAbsent",
                KernelScopeKey.Scope.class, Supplier.class);
            initialize.setAccessible(true);
            initialize.invoke(kernel, scope, history);
            initialize.invoke(kernel, scope, history);
            assertThat(reads.get()).isEqualTo(1);
            assertThat(store.get(scope.userId(), scope.sessionId(), "agent_state", AgentState.class)
                .orElseThrow().getContext()).hasSize(1);
        }
    }
    @Test void conflictDoesNotOverwriteNativeState() {
        InMemoryAgentStateStore original = new InMemoryAgentStateStore();
        FailClosedAgentStateStore store = new FailClosedAgentStateStore(original);
        AgentState first = AgentState.builder().sessionId("s").summary("first").build();
        store.saveIfVersion("u", "s", "agent_state", first, 0L);
        AgentState stale = AgentState.builder().sessionId("s").summary("stale").build();
        assertThatThrownBy(() -> store.saveIfVersion("u", "s", "agent_state", stale, 0L))
            .isInstanceOf(IllegalStateException.class);
        assertThat(store.get("u", "s", "agent_state", AgentState.class).orElseThrow().getSummary())
            .isEqualTo("first");
    }
}
