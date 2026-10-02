package org.ruoyi.service.chat.impl.provider;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.enums.ChatModeType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("dev")
class CozeChatServiceImplTest {

    private final CozeChatServiceImpl service = new CozeChatServiceImpl();

    @Test
    void getProviderName_isCoze() {
        assertEquals(ChatModeType.COZE.getCode(), service.getProviderName());
    }

    @Test
    void buildStreamingChatModel_returnsModel() {
        Model model = service.buildStreamingChatModel(modelVo(), new ChatRequest());

        assertNotNull(model);
    }

    @Test
    void buildChatModel_returnsModel() {
        Model model = service.buildChatModel(modelVo());

        assertNotNull(model);
    }

    @Test
    void adapterIsReachableThroughSharedNativeRegistry() {
        service.registerNativeAdapter();
        var context = io.agentscope.core.model.ModelCreationContext.builder()
            .apiKey("local-test").baseUrl(modelVo().getApiHost()).option("userId", 7L).option("sessionId", 9L).build();
        assertNotNull(io.agentscope.core.model.ModelRegistry.resolve("coze:7480000000000000000", context));
    }

    private ChatModelVo modelVo() {
        ChatModelVo modelVo = new ChatModelVo();
        modelVo.setProviderCode(ChatModeType.COZE.getCode());
        modelVo.setModelName("7480000000000000000");
        modelVo.setApiHost("https://api.coze.cn");
        modelVo.setApiKey("pat-test");
        return modelVo;
    }
}

