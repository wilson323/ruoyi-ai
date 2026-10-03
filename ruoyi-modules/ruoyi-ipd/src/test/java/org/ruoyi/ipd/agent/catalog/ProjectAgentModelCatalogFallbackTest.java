package org.ruoyi.ipd.agent.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.service.AiModelConfigService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentModelCatalogFallbackTest {
    private final AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
    private final AiModelConfigService secrets = mock(AiModelConfigService.class);
    private final ProjectAgentModelCatalog catalog = new ProjectAgentModelCatalog(mapper, secrets);
    private final KernelModelRequest primary = new KernelModelRequest("MiniMax-M3", "minimax", null, "host");

    private AiModelConfig config(String provider, String model, String owner) {
        AiModelConfig config = new AiModelConfig(); config.setId(202L);
        config.setProvider(provider); config.setModelName(model);
        config.setEndpointUrl("https://fixture.example/v1");
        config.setIsActive(false);
        config.setConfigJson("{\"fallbackFor\":\"" + owner + "\"}");
        return config;
    }

    @Test void explicitCrossProviderFallbackDoesNotFilterAwayItsRow() {
        var backup = config("zhipu", "GLM-5.3-Flash", "MiniMax-M3");
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<?> query = invocation.getArgument(0);
            assertTrue(query.getExpression().getNormal().isEmpty(), "Provider must not be filtered");
            return List.of(backup);
        });
        when(secrets.decryptApiKey(backup)).thenReturn("fixture-key");
        var resolved = catalog.resolveFallback(primary).orElseThrow();
        assertEquals("zhipu", resolved.providerCode());
        assertEquals("GLM-5.3-Flash", resolved.modelName());
        verify(secrets).decryptApiKey(backup);
    }

    @Test void multipleExplicitCandidatesFailBeforeCredentialDecryption() {
        when(mapper.selectList(any())).thenReturn(List.of(
            config("zhipu", "GLM-5.3-Flash", "MiniMax-M3"),
            config("minimax", "MiniMax-M2.7", "MiniMax-M3")));
        assertThrows(IllegalArgumentException.class, () -> catalog.resolveFallback(primary));
        verifyNoInteractions(secrets);
    }

    @Test void selfReferenceRequiresBothProviderAndModelToMatch() {
        when(mapper.selectList(any())).thenReturn(List.of(config("minimax", "MiniMax-M3", "MiniMax-M3")));
        assertThrows(IllegalArgumentException.class, () -> catalog.resolveFallback(primary));
        verifyNoInteractions(secrets);
        when(mapper.selectList(any())).thenReturn(List.of(config("zhipu", "MiniMax-M3", "MiniMax-M3")));
        assertEquals("zhipu", catalog.resolveFallback(primary).orElseThrow().providerCode());
    }

    @Test void absentAssociationIsEmptyButIncompleteAssociationIsAnError() {
        when(mapper.selectList(any())).thenReturn(List.of(config("zhipu", "GLM-5.3-Flash", "Other")));
        assertTrue(catalog.resolveFallback(primary).isEmpty());
        var invalid = config("zhipu", "GLM-5.3-Flash", "MiniMax-M3");
        invalid.setEndpointUrl(null);
        when(mapper.selectList(any())).thenReturn(List.of(invalid));
        assertThrows(IllegalArgumentException.class, () -> catalog.resolveFallback(primary));
        verifyNoInteractions(secrets);
    }
}
