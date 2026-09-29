package org.ruoyi.chat.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * W2 模型层（2026-09-28，ADR-0075 矩阵 #8「替换」+ #9「包装」）：内核模型选择唯一入口。
 *
 * <p><b>装配（矩阵 #8「替换」）</b>：模型装配只经 AgentScope 原生 model 配置/装配 API
 * —— {@link ModelRegistry#resolve(String, ModelCreationContext)}（provider SPI），
 * 凭据/端点由 {@link ModelCreationContext} 承载；当前 ruoyi-chat 调用方配置来自
 * {@code chat_model}，IPD {@code ai_model_configs} 尚未接入。本类即内核面
 * 唯一装配点；W1 遗留的 {@code fixedModel/modelId/resolveModel()} 平行自定义层由本类吸收消除，
 * 13 家自装配（langchain4j 直连）不进内核面（其保留期与回滚职责见 ADR-0075 W2 回滚点）。
 *
 * <p><b>动态切换（矩阵 #9「包装」）</b>：请求 {@code model} 字段 → 薄归一（厂商别名 +
 * "provider:model" 注册键）→ 原生 {@code ModelRegistry} 解析；选型/降级是业务政策薄壳，
 * <b>解析唯一源 = ModelRegistry，禁第二套路由</b>。可解析性由原生解析裁定：
 * 不猜测厂商语义；明确选定模型解析失败即拒绝本轮，禁止隐式换模型。
 *
 * <p><b>默认路径</b>：仅调用方没有提供模型时使用
 * {@code chat.kernel.agentscope.model-id}；明确选定模型装配失败时只记录错误类型并上抛，
 * 不用默认模型或 {@code fallbackModel} 掩盖实际执行身份。
 * 回滚点沿用 W1 双保险：开关关（{@code chat.kernel.agentscope.enabled} matchIfMissing=false）
 * 或 Bean 缺席 → 不触本类（既有 langchain4j 路径零行为变化）。
 *
 * <p>测试缝 {@link ModelAssembler} 镜像原生 {@code modelResolver(Function&lt;String, Model&gt;)}
 * 语义；生产恒为 {@link ModelRegistry#resolve}，不产出平行解析实现。
 */
final class KernelModelSelector {

    /** 选型来源（降级审计面）。 */
    enum Source {
        /** 请求 model 字段入选。 */
        REQUEST,
        /** 降级到 chat.kernel.agentscope.model-id 默认配置。 */
        DEFAULT_FALLBACK
    }

    /** 选型 + 装配结果（registryKey 进 Agent 缓存键：换模型换实例）。 */
    record ModelPlan(String registryKey, Source source, Model model, String requestedKey,
                     String configurationIdentity) {

        public ModelPlan {
            Objects.requireNonNull(registryKey, "registryKey");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(configurationIdentity, "configurationIdentity");
        }
    }

    /** 装配缝（镜像原生 modelResolver 语义；生产实现 = {@link ModelRegistry#resolve}）。 */
    @FunctionalInterface
    interface ModelAssembler {

        Model assemble(String registryKey, ModelCreationContext context);
    }

    private static final Logger log = LoggerFactory.getLogger(KernelModelSelector.class);

    private final String defaultModelId;
    private final ModelAssembler assembler;

    /** 生产装配：解析唯一源 = 原生 {@link ModelRegistry}（provider SPI）。 */
    KernelModelSelector(String defaultModelId) {
        this(defaultModelId, ModelRegistry::resolve);
    }

    KernelModelSelector(String defaultModelId, ModelAssembler assembler) {
        this.defaultModelId = Objects.requireNonNull(defaultModelId, "defaultModelId");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    String defaultModelId() {
        return defaultModelId;
    }

    /** 选型 + 装配；明确选定模型不可装配时 fail-closed。 */
    ModelPlan plan(KernelModelRequest request) {
        if (request == null || request.modelName() == null || request.modelName().isBlank()) {
            log.warn("kernel_model operation=ROUTE status=FALLBACK reason=BLANK_REQUEST");
            return defaultPlan(null);
        }
        String key = registryKey(request);
        try {
            Model model = assembler.assemble(key, context(request));
            log.info("kernel_model operation=ROUTE status=SELECTED source=REQUEST registryKey={}", key);
            return new ModelPlan(key, Source.REQUEST, model, key, request.configurationIdentity());
        } catch (RuntimeException e) {
            log.warn("kernel_model operation=ROUTE status=REJECTED reason=ASSEMBLE_FAILED"
                    + " requestedKey={} errorType={}", key, e.getClass().getName());
            throw e;
        }
    }

    private ModelPlan defaultPlan(String requestedKey) {
        Model model = assembler.assemble(defaultModelId, ModelCreationContext.empty());
        return new ModelPlan(defaultModelId, Source.DEFAULT_FALLBACK, model, requestedKey, "default");
    }

    /**
     * 薄归一（翻译，不是路由）：业务模型名/厂商码 → AgentScope "provider:model" 注册键。
     * 已是注册键（含 ':'）原样透传；无厂商段也原样透传（可解析性由原生 ModelRegistry 裁定）。
     */
    static String registryKey(KernelModelRequest request) {
        String name = request.modelName().trim();
        if (name.indexOf(':') >= 0) {
            return name;
        }
        String providerCode = request.providerCode();
        if (providerCode == null || providerCode.isBlank()) {
            return name;
        }
        return providerAlias(providerCode) + ':' + name;
    }

    /**
     * 厂商别名（确定性翻译表，OpenAI 兼容端点归一）：
     * zhipu→glm、qianwen→dashscope、OpenAI 兼容自建端点（custom_api/atlas/ppio/xiaomi）→openai
     * （端点由 ModelCreationContext.baseUrl=apiHost 承载）；其余小写直通，
     * 未覆盖厂商（dify/coze/custom_anthropic 等）不猜测语义，由原生解析裁定（失败即拒绝）。
     */
    static String providerAlias(String providerCode) {
        String normalized = providerCode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "zhipu" -> "glm";
            case "qianwen" -> "dashscope";
            case "custom_api", "atlas", "ppio", "xiaomi" -> "openai";
            default -> normalized;
        };
    }

    /** 装配上下文：凭据/端点走原生 ModelCreationContext（凭据不落代码，C6）。 */
    private static ModelCreationContext context(KernelModelRequest request) {
        ModelCreationContext.Builder builder = ModelCreationContext.builder();
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            builder.apiKey(request.apiKey());
        }
        if (request.apiHost() != null && !request.apiHost().isBlank()) {
            builder.baseUrl(request.apiHost());
        }
        return builder.build();
    }
}
