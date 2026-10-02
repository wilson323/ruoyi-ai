package org.ruoyi.service.embed;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.model.ExecutionConfig;
import org.ruoyi.common.chat.embedding.BuiltinEmbeddingDefaults;
import java.time.Duration;

/** 所有文本嵌入共享 AgentScope 原生客户端装配。 */
public final class EmbeddingModels {
    private EmbeddingModels() { }

    public static EmbeddingModel create(String provider, String model, String baseUrl, String apiKey,
                                        Integer dimensions, Duration timeout) {
        int dim = dimensions == null ? 1024 : dimensions;
        ExecutionConfig execution = ExecutionConfig.builder().timeout(timeout).build();
        boolean builtin = BuiltinEmbeddingDefaults.MODEL_NAME.equals(model)
            && (baseUrl == null || baseUrl.replaceAll("/v1/?$", "").equals(
                BuiltinEmbeddingDefaults.BASE_URL.replaceAll("/v1/?$", "")));
        if (builtin || "ollama".equalsIgnoreCase(provider)) {
            return OllamaTextEmbedding.builder().modelName(model).dimensions(dim)
                .baseUrl(baseUrl == null ? BuiltinEmbeddingDefaults.BASE_URL.replaceAll("/v1/?$", "")
                    : baseUrl.replaceAll("/v1/?$", ""))
                .executionConfig(execution).build();
        }
        if ("qianwen".equalsIgnoreCase(provider) || "alibailian".equalsIgnoreCase(provider)
            || "dashscope".equalsIgnoreCase(provider)) {
            if (baseUrl != null && baseUrl.contains("/compatible-mode/")) {
                return OpenAITextEmbedding.builder().modelName(model).dimensions(dim).baseUrl(baseUrl)
                    .apiKey(apiKey).executionConfig(execution).build();
            }
            var builder = DashScopeTextEmbedding.builder().modelName(model).dimensions(dim)
                .apiKey(apiKey).executionConfig(execution);
            if (baseUrl != null && !baseUrl.isBlank()) { builder.baseUrl(baseUrl); }
            return builder.build();
        }
        return OpenAITextEmbedding.builder().modelName(model).dimensions(dim).baseUrl(baseUrl)
            .apiKey(apiKey).executionConfig(execution).build();
    }
}
