package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.service.ai.AiGateway;
import java.util.function.Consumer;
import java.util.function.Function;

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
    private final Consumer<String> endpointGuard;
    /** 回退模型请求源（官方 fallbackModel 扩展点取数缝；null = 未配置回退）。 */
    private Function<KernelModelRequest, KernelModelRequest> fallbackSource;

    /** 生产装配。 */
    public ProjectAgentModelAssembler(AiGateway gateway) {
        this(ModelRegistry::resolve, Objects.requireNonNull(gateway, "gateway")::validateEndpoint);
    }

    /**
     * @param resolver 装配缝
     */
    ProjectAgentModelAssembler(Resolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "test resolver");
        this.endpointGuard = null; // 同包测试专用，不是生产构造。
    }

    ProjectAgentModelAssembler(Resolver resolver, Consumer<String> endpointGuard) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.endpointGuard = Objects.requireNonNull(endpointGuard, "endpointGuard");
    }

    /**
     * 装配模型。
     *
     * @param request 选定模型请求
     * @return 模型实例
     * @throws IllegalArgumentException 请求缺 modelName
     */
    public Model assemble(KernelModelRequest request) {
        return assemble(request, null, null);
    }

    public Model assemble(KernelModelRequest request, Long personId, Long runId) {
        if (request == null || request.modelName() == null || request.modelName().isBlank()) {
            throw new IllegalArgumentException("modelName required");
        }
        if (endpointGuard != null) {
            endpointGuard.accept(request.apiHost());
        }
        return resolver.resolve(registryKey(request), org.ruoyi.chat.kernel.AgentScopeModelFactory.context(request, null, personId == null ? null : personId.toString(), runId == null ? null : runId.toString()));
    }

    /**
     * 绑定回退模型请求源（生产 = 模型目录 fallbackFor 约定；测试可注入替身）。
     *
     * @param source 主模型请求 → 回退模型请求；无回退返回 null
     * @return 本装配器（链式）
     */
    public ProjectAgentModelAssembler withFallbackSource(Function<KernelModelRequest, KernelModelRequest> source) {
        this.fallbackSource = source;
        return this;
    }

    /**
     * 装配官方回退模型（AgentScope 2.0.3 {@code ReActAgent/HarnessAgent Builder#fallbackModel} 形态）。
     *
     * <p>回退请求与主请求同缝装配：endpointGuard 前置 + 原生 {@code ModelRegistry} 解析，
     * 不另造第二路由。未配置回退源、目录无回退行 → null（fail-open：回退缺席不阻断主模型，
     * 业务闸门与主装配语义不变）；回退请求装配失败异常原样上抛，由调用方决定降级路径。
     *
     * @param primary 主模型装配请求
     * @param personId 运行人（context userId）
     * @param runId 运行（context sessionId）
     * @return 回退模型；无回退配置时 null
     */
    public Model assembleFallback(KernelModelRequest primary, Long personId, Long runId) {
        if (fallbackSource == null || primary == null) {
            return null;
        }
        KernelModelRequest fallback = fallbackSource.apply(primary);
        if (fallback == null || fallback.modelName() == null || fallback.modelName().isBlank()) {
            return null;
        }
        return assemble(fallback, personId, runId);
    }

    /**
     * IPD provider → AgentScope 注册键（已是 "provider:model" 形式原样透传）。
     *
     * @param request 模型请求
     * @return 注册键
     */
    static String registryKey(KernelModelRequest request) {
        return org.ruoyi.chat.kernel.AgentScopeModelFactory.registryKey(request);
    }
}
