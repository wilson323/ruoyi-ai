package org.ruoyi.chat.kernel;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.time.Duration;
import java.util.Locale;

/** 业务配置的薄翻译口；模型创建唯一委托 AgentScope 原生注册表。 */
public final class AgentScopeModelFactory {
    private AgentScopeModelFactory() { }

    public static Model create(KernelModelRequest request) {
        return new org.ruoyi.observability.MyChatModelListener().wrap(ModelRegistry.resolve(registryKey(request), context(request)));
    }

    public static Model create(KernelModelRequest request, GenerateOptions options) {
        return new org.ruoyi.observability.MyChatModelListener().wrap(ModelRegistry.resolve(registryKey(request), context(request, options)));
    }

    public static String registryKey(KernelModelRequest request) {
        if (request == null || request.modelName() == null || request.modelName().isBlank()) {
            throw new IllegalArgumentException("modelName required");
        }
        String name = request.modelName().trim();
        if ("minimax".equalsIgnoreCase(request.providerCode()) && request.apiHost() != null
            && request.apiHost().replaceAll("/+$", "").endsWith("/anthropic")) { return "anthropic:" + name; }
        if ("ollama".equalsIgnoreCase(request.providerCode()) && !name.startsWith("ollama:")) {
            return "ollama:" + name;
        }
        if (name.indexOf(':') >= 0) { return name; }
        if (request.providerCode() == null || request.providerCode().isBlank()) { return name; }
        return providerAlias(request.providerCode()) + ':' + name;
    }

    public static String providerAlias(String provider) {
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "zhipu", "glm" -> "glm";
            case "qianwen", "dashscope" -> "dashscope";
            case "moonshot" -> "kimi";
            case "custom_anthropic" -> "anthropic";
            case "custom_api", "atlas", "ppio", "xiaomi", "openai", "qwen" -> "openai";
            default -> provider.trim().toLowerCase(Locale.ROOT);
        };
    }

    public static ModelCreationContext context(KernelModelRequest request) {
        return context(request, null);
    }

    public static ModelCreationContext context(KernelModelRequest request, GenerateOptions overrides) {
        return context(request, overrides, null, null);
    }

    public static ModelCreationContext context(KernelModelRequest request, GenerateOptions overrides,
                                               String userId, String sessionId) {
        ModelCreationContext.Builder context = ModelCreationContext.builder();
        if (request.apiKey() != null && !request.apiKey().isBlank()) { context.apiKey(request.apiKey()); }
        if (request.apiHost() != null && !request.apiHost().isBlank()) { context.baseUrl(request.apiHost()); }
        GenerateOptions.Builder options = GenerateOptions.builder()
            .temperature(request.temperature()).maxTokens(request.maxTokens());
        if (request.timeoutMs() != null) {
            options.executionConfig(ExecutionConfig.builder()
                .timeout(Duration.ofMillis(request.timeoutMs())).maxAttempts(1).build());
        }
        if (userId != null) { context.option("userId", userId); }
        if (sessionId != null) { context.option("sessionId", sessionId); }
        context.component(GenerateOptions.class, GenerateOptions.mergeOptions(overrides, options.build()));
        return context.build();
    }
}
