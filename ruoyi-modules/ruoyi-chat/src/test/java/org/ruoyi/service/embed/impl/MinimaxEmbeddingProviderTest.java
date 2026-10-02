package org.ruoyi.service.embed.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.enums.ModalityType;
import org.ruoyi.service.embed.BaseEmbedModelService;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MinimaxEmbeddingProvider
 */
@Tag("dev")
class MinimaxEmbeddingProviderTest {

    private MinimaxEmbeddingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MinimaxEmbeddingProvider();
    }

    @Test
    void implementsBaseEmbedModelService() {
        assertInstanceOf(BaseEmbedModelService.class, provider);
    }

    @Test
    void extendsOpenAiEmbeddingProvider() {
        assertInstanceOf(OpenAiEmbeddingProvider.class, provider);
    }

    @Test
    void getSupportedModalities_returnsText() {
        Set<ModalityType> modalities = provider.getSupportedModalities();
        assertNotNull(modalities);
        assertTrue(modalities.contains(ModalityType.TEXT));
        assertEquals(1, modalities.size());
    }

    @Test
    void configure_setsModelConfig() {
        ChatModelVo config = org.mockito.Mockito.spy(new ChatModelVo());
        config.setApiHost("https://api.minimax.io/v1");
        config.setProviderCode("minimax");
        org.mockito.Mockito.doReturn("fixture-key").when(config).resolveApiKeyForConfiguredEndpoint("minimax");
        config.setModelName("embo-01");
        config.setModelDimension(1536);

        provider.configure(config);
        assertEquals("embo-01", provider.getModelName());
        assertEquals(1536, provider.getDimensions());
        org.mockito.Mockito.verify(config).resolveApiKeyForConfiguredEndpoint("minimax");
    }
}
