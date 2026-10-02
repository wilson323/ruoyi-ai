package org.ruoyi.service.chat.impl.provider;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.enums.ChatModeType;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class MinimaxServiceImplTest {
    private final MinimaxServiceImpl service = new MinimaxServiceImpl();
    @Test void providerAndThinkingContractPreserved() {
        assertEquals(ChatModeType.MINIMAX.getCode(), service.getProviderName());
        assertEquals("adaptive", MinimaxServiceImpl.thinkingType("MiniMax-M3", true));
        assertEquals("disabled", MinimaxServiceImpl.thinkingType("MiniMax-M3", false));
        assertNull(MinimaxServiceImpl.thinkingType("MiniMax-M2.7", false));
    }
    @Test void regionsAndProtocolsResolveNativeModels() {
        for (String host : java.util.List.of("https://api.minimax.cn/v1", "https://api.minimaxi.com/v1",
            "https://api.minimax.cn/anthropic", "https://api.minimaxi.com/anthropic")) {
            for (String name : java.util.List.of("MiniMax-M3", "MiniMax-M2.7")) {
                ChatModelVo config = org.mockito.Mockito.spy(new ChatModelVo());
                config.setProviderCode(ChatModeType.MINIMAX.getCode());
                config.setModelName(name);
                config.setApiHost(host);
                org.mockito.Mockito.doReturn("fixture-key").when(config)
                    .resolveApiKeyForConfiguredEndpoint(ChatModeType.MINIMAX.getCode());
                assertInstanceOf(Model.class, service.buildStreamingChatModel(config, new ChatRequest()));
                assertInstanceOf(Model.class, service.buildChatModel(config));
                org.mockito.Mockito.verify(config, org.mockito.Mockito.times(2))
                    .resolveApiKeyForConfiguredEndpoint(ChatModeType.MINIMAX.getCode());
            }
        }
    }
    @Test void invalidHostStillRejected() {
        ChatModelVo config = new ChatModelVo();
        config.setProviderCode(ChatModeType.MINIMAX.getCode());
        config.setModelName("MiniMax-M3");
        config.setApiHost("invalid");
        config.setApiKey("test-key");
        assertThrows(IllegalArgumentException.class, () -> service.buildChatModel(config));
    }
}
