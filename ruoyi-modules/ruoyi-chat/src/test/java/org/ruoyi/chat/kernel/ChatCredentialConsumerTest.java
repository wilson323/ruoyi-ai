package org.ruoyi.chat.kernel;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ChatCredentialConsumerTest {
    @TempDir Path workspace;
    @Test void authenticatedEntryPassesSelectedPlanSecretsToTheNativeProfile() {
        var received = new AtomicReference<List<String>>();
        var selector = new KernelModelSelector("stub:default", (key, context) -> mock(Model.class));
        try (var profile = mockStatic(ChatOfficialCapabilities.class);
             var kernel = new AgentScopeChatKernel(selector, () -> mock(MysqlAgentStateStore.class), workspace)) {
            profile.when(() -> ChatOfficialCapabilities.configure(any(HarnessAgent.Builder.class), any(Path.class),
                anyString(), anyString(), anyList())).thenAnswer(call -> {
                    received.set(List.copyOf(call.getArgument(4)));
                    // Stop at the assembly seam: this test checks credential consumption, not Docker/model behavior.
                    throw new IllegalStateException("ASSEMBLY_SEAM_CAPTURED");
                });
            kernel.stream("P", "U", "credential-fixture", "S", "hello", "business",
                new KernelModelRequest("selected", "openai", "CHAT_CANARY_KEY", null), mock(KernelChatSink.class));
            assertThat(received.get()).containsExactly("CHAT_CANARY_KEY");
        }
    }
}
