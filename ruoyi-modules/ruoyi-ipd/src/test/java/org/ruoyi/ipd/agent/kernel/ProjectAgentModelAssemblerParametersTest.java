package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelCreationContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.KernelModelRequest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentModelAssemblerParametersTest {
    @Test
    void rejectsEndpointBeforeResolverOrCredentialAssembly() {
        java.util.concurrent.atomic.AtomicBoolean resolved = new java.util.concurrent.atomic.AtomicBoolean();
        ProjectAgentModelAssembler assembler = new ProjectAgentModelAssembler((key, context) -> {
            resolved.set(true);
            return null;
        }, endpoint -> { throw new IllegalArgumentException("endpoint rejected"); });
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(
            new KernelModelRequest("model", "openai", "secret", "http://127.0.0.1")));
        assertFalse(resolved.get());
    }

    @Test
    void productionConstructorRequiresGatewayAndDelegatesGuard() {
        assertThrows(NullPointerException.class, () -> new ProjectAgentModelAssembler(
            (org.ruoyi.ipd.service.ai.AiGateway) null));
        org.ruoyi.ipd.service.ai.AiGateway gateway = org.mockito.Mockito.mock(org.ruoyi.ipd.service.ai.AiGateway.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("blocked")).when(gateway).validateEndpoint("http://127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> new ProjectAgentModelAssembler(gateway).assemble(
            new KernelModelRequest("model", "openai", "secret", "http://127.0.0.1")));
        org.mockito.Mockito.verify(gateway).validateEndpoint("http://127.0.0.1");
    }

    @Test
    void actualPersonAndRunIdentityReachNativeProviderContext() {
        AtomicReference<ModelCreationContext> captured = new AtomicReference<>();
        new ProjectAgentModelAssembler((key, context) -> { captured.set(context); return null; })
            .assemble(new KernelModelRequest("model", "dify", "secret", "https://example.invalid"), 42L, 77L);
        assertEquals("42", captured.get().option("userId"));
        assertEquals("77", captured.get().option("sessionId"));
    }

    @Test
    void passesConfiguredParametersToActualSdkComponent() {
        AtomicReference<ModelCreationContext> captured = new AtomicReference<>();
        ProjectAgentModelAssembler assembler = new ProjectAgentModelAssembler((key, context) -> {
            captured.set(context);
            return null;
        });
        assembler.assemble(new KernelModelRequest("model", "openai", "secret", "https://example.invalid", 0.3, 2048, 15000));
        GenerateOptions options = captured.get().component(GenerateOptions.class);
        assertEquals(0.3, options.getTemperature());
        assertEquals(2048, options.getMaxTokens());
        assertEquals(Duration.ofSeconds(15), options.getExecutionConfig().getTimeout());
    }

    @Test
    void legacyRequestPreservesSdkDefaultsAndRedaction() {
        AtomicReference<ModelCreationContext> captured = new AtomicReference<>();
        KernelModelRequest request = new KernelModelRequest("model", "openai", "secret", "https://example.invalid");
        new ProjectAgentModelAssembler((key, context) -> { captured.set(context); return null; }).assemble(request);
        GenerateOptions defaults = captured.get().component(GenerateOptions.class);
        assertNotNull(defaults);
        assertNull(defaults.getTemperature());
        assertNull(defaults.getMaxTokens());
        assertNull(defaults.getExecutionConfig(), "无显式超时配置时保留SDK执行默认值");
        assertFalse(request.toString().contains("secret"));
        assertFalse(request.toString().contains("example.invalid"));
    }
    @Test void frozenFallbackNeverRereadsMutableCatalog() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var model = org.mockito.Mockito.mock(io.agentscope.core.model.Model.class);
        var assembler = new ProjectAgentModelAssembler((key, context) -> model)
            .withFallbackSource(primary -> { calls.incrementAndGet(); throw new IllegalStateException("must not query"); });
        var frozen = new KernelModelRequest("GLM-5.3-Flash", "zhipu", "fixture", "https://fixture.example/v1");
        assertSame(model, assembler.assembleConfiguredFallback(frozen, 1L, 2L));
        assertNull(assembler.assembleConfiguredFallback(null, 1L, 2L));
        assertEquals(0, calls.get());
    }

}
