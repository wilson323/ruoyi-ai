package org.ruoyi.service.chat;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import org.ruoyi.chat.kernel.AgentScopeModelFactory;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;

/** 平台模型均使用 AgentScope Model，模型创建唯一委托共享注册表。 */
public interface AbstractChatService {
    default Model buildStreamingChatModel(ChatModelVo config, ChatRequest request) {
        return buildNativeModel(config, request, 180_000);
    }

    default Model buildChatModel(ChatModelVo config) {
        return buildNativeModel(config, null, 120_000);
    }

    default Model buildNativeModel(ChatModelVo config, ChatRequest request, int timeoutMs) {
        var modelRequest = new KernelModelRequest(config.getModelName(), getProviderName(),
            config.resolveApiKeyForConfiguredEndpoint(getProviderName()), config.getApiHost(), null, null, timeoutMs);
        var options = GenerateOptions.builder().stream(true);
        if (request != null && request.getReasoningEffort() != null && !request.getReasoningEffort().isBlank()) {
            options.reasoningEffort(request.getReasoningEffort());
        }
        return new org.ruoyi.observability.MyChatModelListener().wrap(
            AgentScopeModelFactory.create(modelRequest, options.build()));
    }

    String getProviderName();
}
