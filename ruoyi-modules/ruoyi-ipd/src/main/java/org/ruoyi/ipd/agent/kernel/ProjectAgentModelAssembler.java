package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.ruoyi.chat.kernel.KernelModelRequest;

import java.util.Locale;
import java.util.Objects;

/**
 * 项目智能体模型装配：IPD {@code ai_model_configs} 装配请求 → AgentScope 原生
 * {@link ModelRegistry#resolve(String, ModelCreationContext)}（解析唯一源，不另造路由）。
 *
 * <p>注册键翻译按 IPD 自身 provider 词表（与 {@code OpenAiCompatibleTester} 的别名一致：
 * openai/deepseek/qwen/moonshot/MiniMax 为 OpenAI 兼容端点，端点经 baseUrl 承载），
 * 区别于 ruoyi-chat {@code chat_model} 的厂商码；{@code KernelModelSelector} 为包级私有且
 * 不在本切片写入面，W2 收敛为单一公开翻译口（见运行合同 §7）。装配失败直接上抛，
 * 由内核转 MODEL_UNAVAILABLE，不回落默认模型。
 */
public class ProjectAgentModelAssembler {

    /** 装配缝（生产 = ModelRegistry::resolve；测试注入替身模型）。 */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param registryKey "provider:model"
         * @param context 凭据/端点上下文
         * @return 模型
         */
        Model resolve(String registryKey, ModelCreationContext context);
    }

    private final Resolver resolver;

    /** 生产装配。 */
    public ProjectAgentModelAssembler() {
        this(ModelRegistry::resolve);
    }

    /**
     * @param resolver 装配缝
     */
    public ProjectAgentModelAssembler(Resolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * 装配模型。
     *
     * @param request 选定模型请求
     * @return 模型实例
     * @throws IllegalArgumentException 请求缺 modelName
     */
    public Model assemble(KernelModelRequest request) {
        if (request == null || request.modelName() == null || request.modelName().isBlank()) {
            throw new IllegalArgumentException("modelName required");
        }
        ModelCreationContext.Builder context = ModelCreationContext.builder();
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            context.apiKey(request.apiKey());
        }
        if (request.apiHost() != null && !request.apiHost().isBlank()) {
            context.baseUrl(request.apiHost());
        }
        return resolver.resolve(registryKey(request), context.build());
    }

    /**
     * IPD provider → AgentScope 注册键（已是 "provider:model" 形式原样透传）。
     *
     * @param request 模型请求
     * @return 注册键
     */
    static String registryKey(KernelModelRequest request) {
        String name = request.modelName().trim();
        if (name.indexOf(':') >= 0) {
            return name;
        }
        String provider = request.providerCode() == null ? "" : request.providerCode().trim().toLowerCase(Locale.ROOT);
        String alias = switch (provider) {
            case "zhipu", "glm" -> "glm";
            case "qianwen", "dashscope" -> "dashscope";
            case "ollama" -> "ollama";
            case "openai", "deepseek", "qwen", "moonshot", "minimax", "custom_api" -> "openai";
            default -> provider;
        };
        return alias.isEmpty() ? name : alias + ':' + name;
    }
}
