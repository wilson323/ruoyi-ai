package org.ruoyi.chat.poc.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class PocModelCredentialConsumerTest {
    @Test void officialMiniMaxCreationAndTranscriptReceiveTheSameResolvedLiteral() {
        var model = mock(Model.class);
        try (var registry = mockStatic(ModelRegistry.class)) {
            registry.when(() -> ModelRegistry.resolve(eq("minimax:MiniMax-M3"), any(ModelCreationContext.class)))
                .thenAnswer(call -> {
                    assertThat(((ModelCreationContext) call.getArgument(1)).getApiKey()).isEqualTo("POC_CANARY_KEY");
                    return model;
                });
            var binding = PocKernelSupport.resolveModel("minimax:MiniMax-M3", name -> " POC_CANARY_KEY ");
            assertThat(binding.model()).isSameAs(model);
            assertThat(binding.knownSecrets()).containsExactly("POC_CANARY_KEY");
            assertThat(binding.toString()).doesNotContain("POC_CANARY_KEY");
        }
    }

    @Test void unknownProviderIsNotPresentedAsHavingResolvedVaultCredentials() {
        var model = mock(Model.class);
        try (var registry = mockStatic(ModelRegistry.class)) {
            registry.when(() -> ModelRegistry.resolve("custom:vault-model")).thenReturn(model);
            var binding = PocKernelSupport.resolveModel("custom:vault-model", name -> { throw new AssertionError("Must not guess provider credentials"); });
            assertThat(binding.model()).isSameAs(model);
            assertThat(binding.knownSecrets()).isEmpty();
        }
    }
}
