package org.ruoyi.ipd.agent.catalog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.service.AiModelConfigService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@Tag("dev")
class ProjectAgentModelCatalogParametersTest {
    private ProjectAgentModelCatalog catalog(String json) {
        AiModelConfig config = new AiModelConfig();
        config.setIsActive(true); config.setProvider("openai"); config.setModelName("m"); config.setConfigJson(json);
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectById(1L)).thenReturn(config);
        return new ProjectAgentModelCatalog(mapper, mock(AiModelConfigService.class));
    }
    @Test void mapsExistingGenerationConfigurationAndClampsTimeout() {
        var request = catalog("{\"temperature\":0.4,\"maxTokens\":1234,\"generateTimeoutMs\":999999}").resolve(1L).orElseThrow();
        assertEquals(0.4, request.temperature()); assertEquals(1234, request.maxTokens()); assertEquals(120000, request.timeoutMs());
        assertEquals(10000, catalog("{\"generateTimeoutMs\":1}").resolve(1L).orElseThrow().timeoutMs());
        assertEquals(60000, catalog("{\"generateTimeoutMs\":0}").resolve(1L).orElseThrow().timeoutMs());
    }
    @Test void absentOrInvalidConfigurationPreservesDefaults() {
        for (String json : new String[]{null, "", "{}", "bad-json"}) {
            var request = catalog(json).resolve(1L).orElseThrow();
            assertNull(request.temperature()); assertNull(request.maxTokens()); assertNull(request.timeoutMs());
        }
    }
}
