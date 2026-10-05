package org.ruoyi.service.chat.impl.provider;

import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.enums.ChatModeType;
import org.ruoyi.observability.MyChatModelListener;
import org.ruoyi.service.chat.AbstractChatService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * MiniMax chat service.
 * <p>
 * Supports MiniMax-M3 and MiniMax-M2.7 through the OpenAI-compatible and
 * Anthropic-compatible APIs in both global and China regions.
 *
 * @author octopus
 * @date 2026/3/21
 */
@Service
@Slf4j
public class MinimaxServiceImpl implements AbstractChatService {

    private static final String MINIMAX_M3 = "MiniMax-M3";
    private static final String ANTHROPIC_PATH_SUFFIX = "/anthropic";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    @Override
    public io.agentscope.core.model.Model buildStreamingChatModel(ChatModelVo config, ChatRequest runtime) {
        String baseUrl = normalizeBaseUrl(config.getApiHost());
        boolean thinking = runtime != null && Boolean.TRUE.equals(runtime.getEnableThinking());
        var options = io.agentscope.core.model.GenerateOptions.builder().stream(true);
        String type = thinkingType(config.getModelName(), thinking);
        if (type != null) { options.additionalBodyParam("thinking", Map.of("type", type)); }
        String provider = isAnthropicBaseUrl(baseUrl) ? "anthropic" : getProviderName();
        return new org.ruoyi.observability.MyChatModelListener().wrap(org.ruoyi.chat.kernel.AgentScopeModelFactory.create(
            new org.ruoyi.chat.kernel.KernelModelRequest(config.getModelName(), provider,
                config.resolveApiKeyForConfiguredEndpoint(getProviderName()), baseUrl, null, null, 180000), options.build()));
    }

    @Override
    public io.agentscope.core.model.Model buildChatModel(ChatModelVo config) {
        return buildStreamingChatModel(config, null);
    }

    @Override
    public String getProviderName() {
        return ChatModeType.MINIMAX.getCode();
    }

    static String thinkingType(String modelName, boolean thinkingEnabled) {
        if (!MINIMAX_M3.equals(modelName)) {
            return null;
        }
        return thinkingEnabled ? "adaptive" : "disabled";
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("MiniMax API Host must not be blank");
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("MiniMax API Host must be a valid URL", exception);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("MiniMax API Host must be an absolute URL");
        }
        return uri.toString();
    }

    private static boolean isAnthropicBaseUrl(String baseUrl) {
        return URI.create(baseUrl).getPath().endsWith(ANTHROPIC_PATH_SUFFIX);
    }

    private static String toAnthropicClientBaseUrl(String baseUrl) {
        // 兼容历史端点格式；AgentScope formatter 维护实际请求路径。
        return baseUrl + "/v1";
    }

}
