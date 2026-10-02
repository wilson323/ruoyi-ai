package org.ruoyi.service.chat.impl.provider;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import org.ruoyi.chat.kernel.AgentScopeModelFactory;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.security.ChatModelCredentialPolicy;
import org.ruoyi.enums.ChatModeType;
import org.ruoyi.service.chat.AbstractChatService;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class DeepseekServiceImpl implements AbstractChatService {
    @Override public String getProviderName() { return ChatModeType.DEEP_SEEK.getCode(); }
    private void validate(ChatModelVo config) {
        ChatModelCredentialPolicy.requireDeepSeekConfiguration(config.getProviderCode(), config.getModelName(), config.getApiHost(), config.getApiKey());
    }
    @Override public Model buildChatModel(ChatModelVo config) { validate(config); return AbstractChatService.super.buildChatModel(config); }
    @Override public Model buildStreamingChatModel(ChatModelVo config, ChatRequest runtime) {
        validate(config);
        boolean thinking = runtime != null && Boolean.TRUE.equals(runtime.getEnableThinking());
        var options = GenerateOptions.builder().stream(true).parallelToolCalls(true)
            .additionalBodyParams(thinkingParameters(thinking));
        if (thinking) { options.reasoningEffort("high"); }
        return new org.ruoyi.observability.MyChatModelListener().wrap(AgentScopeModelFactory.create(
            new KernelModelRequest(config.getModelName(), getProviderName(), config.resolveApiKeyForConfiguredEndpoint(getProviderName()),
                config.getApiHost(), null, null, 180000), options.build()));
    }
    static Map<String, Object> thinkingParameters(boolean enabled) {
        return Map.of("thinking", Map.of("type", enabled ? "enabled" : "disabled"));
    }
}
