package org.ruoyi.chat.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内核模型选择入口。业务配置经共享 AgentScopeModelFactory 归一，
 * 原生 ModelRegistry 是唯一解析源；明确选择失败直接拒绝，不隐式换模型。
 * 请求未提供模型时才使用配置的默认模型。
 * ModelAssembler 仅作为原生解析接口的测试缝。
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
                     String configurationIdentity, List<String> knownSecrets) {
        ModelPlan(String registryKey, Source source, Model model, String requestedKey, String configurationIdentity) {
            this(registryKey, source, model, requestedKey, configurationIdentity, List.of());
        }

        public ModelPlan {
            Objects.requireNonNull(registryKey, "registryKey");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(configurationIdentity, "configurationIdentity");
            knownSecrets = knownSecrets == null ? List.of() : List.copyOf(knownSecrets);
        }

        @Override public String toString() {
            return "ModelPlan[registryKey=" + registryKey + ", source=" + source
                + ", configurationIdentity=" + configurationIdentity + ", knownSecrets=<redacted>]";
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
    ModelPlan plan(KernelModelRequest request) { return plan(request, null, null); }

    ModelPlan plan(KernelModelRequest request, String userId, String sessionId) {
        if (request == null || request.modelName() == null || request.modelName().isBlank()) {
            log.warn("kernel_model operation=ROUTE status=FALLBACK reason=BLANK_REQUEST");
            return defaultPlan(null);
        }
        String key = registryKey(request);
        try {
            Model model = assembler.assemble(key, AgentScopeModelFactory.context(request, null, userId, sessionId));
            log.info("kernel_model operation=ROUTE status=SELECTED source=REQUEST registryKey={}", key);
            return new ModelPlan(key, Source.REQUEST, model, key, request.configurationIdentity() + ":u" + userId + ":s" + sessionId,
                request.apiKey() == null || request.apiKey().isBlank() ? List.of() : List.of(request.apiKey()));
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
        return AgentScopeModelFactory.registryKey(request);
    }

    /**
     * 厂商别名（确定性翻译表，OpenAI 兼容端点归一）：
     * zhipu→glm、qianwen→dashscope、OpenAI 兼容自建端点（custom_api/atlas/ppio/xiaomi）→openai
     * （端点由 ModelCreationContext.baseUrl=apiHost 承载）；其余小写直通，
     * 未覆盖厂商（dify/coze/custom_anthropic 等）不猜测语义，由原生解析裁定（失败即拒绝）。
     */
    static String providerAlias(String providerCode) {
        return AgentScopeModelFactory.providerAlias(providerCode);
    }

    /** 装配上下文：凭据/端点走原生 ModelCreationContext（凭据不落代码，C6）。 */
    private static ModelCreationContext context(KernelModelRequest request) {
        return AgentScopeModelFactory.context(request);
    }
}
