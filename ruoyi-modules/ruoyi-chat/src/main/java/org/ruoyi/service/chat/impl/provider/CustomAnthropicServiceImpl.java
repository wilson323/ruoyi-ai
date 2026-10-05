package org.ruoyi.service.chat.impl.provider;

import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.security.CustomApiCredentialPolicy;
import org.ruoyi.enums.ChatModeType;
import org.ruoyi.observability.MyChatModelListener;
import org.ruoyi.service.chat.AbstractChatService;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 自定义 Anthropic Messages 兼容接口。 */
@Service
public class CustomAnthropicServiceImpl implements AbstractChatService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final int DEFAULT_MAX_TOKENS = 4096;

    @Override
    public io.agentscope.core.model.Model buildStreamingChatModel(ChatModelVo config, ChatRequest runtime) {
        String baseUrl = validateConfiguration(config);
        return new org.ruoyi.observability.MyChatModelListener().wrap(org.ruoyi.chat.kernel.AgentScopeModelFactory.create(
            new org.ruoyi.chat.kernel.KernelModelRequest(config.getModelName(), "anthropic",
                config.resolveApiKeyForConfiguredEndpoint(getProviderName()), baseUrl, null, DEFAULT_MAX_TOKENS, 180000)));
    }

    @Override
    public io.agentscope.core.model.Model buildChatModel(ChatModelVo config) { return buildStreamingChatModel(config, null); }

    private String validateConfiguration(ChatModelVo config) {
        if (!getProviderName().equals(config.getProviderCode())) {
            throw new IllegalArgumentException("模型厂商与 Anthropic 自定义适配器不匹配");
        }
        return CustomApiCredentialPolicy.requireConfiguration(
            getProviderName(), config.getModelName(), config.getApiHost(), config.getApiKey());
    }

    @Override
    public String getProviderName() {
        return ChatModeType.CUSTOM_ANTHROPIC.getCode();
    }
}
