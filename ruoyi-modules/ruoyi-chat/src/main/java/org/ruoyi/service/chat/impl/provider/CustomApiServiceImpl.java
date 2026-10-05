package org.ruoyi.service.chat.impl.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.security.CustomApiCredentialPolicy;
import org.ruoyi.enums.ChatModeType;
import org.ruoyi.service.chat.AbstractChatService;
import org.ruoyi.service.chat.impl.provider.doubao.DoubaoStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 自定义 API 服务调用
 *
 * 适用于 OpenAI Chat Completions 兼容接口。
 * 通过模型配置中的 apiHost / apiKey / modelName 即可复用，不需要再写死具体供应商。
 *
 * <p>字节 Doubao-Seed-Evolving 模型经自定义 OpenAI 兼容供应商（custom_api）接入时，
 * 返回专用的 {@link DoubaoStreamingChatModel}，以支持思考等级（reasoning_effort/thinking）、
 * 图片精度 xhigh 与 encrypted_content 思考加密原文回传。密钥只来自环境变量引用，
 * 不写入任何代码、SQL、文档或日志。</p>
 *
 * @author better
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomApiServiceImpl implements AbstractChatService {

    /** OpenAI 兼容自定义模型的默认单轮超时（维持历史行为 180 秒）。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);

    /**
     * Doubao 单轮流式截止时间，可通过 Spring Duration 配置
     * {@code chat.custom-api.doubao.timeout=PT10M} 覆盖；默认 10 分钟，
     * 与调用侧的长任务总时限（如 coding 30 分钟）保持同一量级，
     * 使长思考回合由调用侧预算与单轮上限统一控制，而不是在接入层被提前切断。
     * 直接 new 本服务（无 Spring 容器）时该字段初始值同样是正确默认值。
     */
    @Value("${chat.custom-api.doubao.timeout:PT10M}")
    private Duration doubaoTimeout = Duration.ofMinutes(10);

    @Override
    public io.agentscope.core.model.Model buildStreamingChatModel(ChatModelVo chatModelVo, ChatRequest chatRequest) {
        String baseUrl = validateConfiguration(chatModelVo);
        if (isDoubao(chatModelVo.getModelName())) {
            return buildDoubaoStreamingModel(chatModelVo, chatRequest, baseUrl);
        }
        return AbstractChatService.super.buildStreamingChatModel(chatModelVo, chatRequest);
    }

    private io.agentscope.core.model.Model buildDoubaoStreamingModel(ChatModelVo chatModelVo,
                                                         ChatRequest chatRequest,
                                                         String baseUrl) {
        // Doubao 思考等级：默认 high；none 关闭思考（thinking.type=disabled 且不传 reasoning_effort）。
        // 其他模型不会进入此分支，Doubao 专属参数不会泄漏给非 Doubao 模型。
        String reasoningEffort = chatRequest == null || chatRequest.getReasoningEffort() == null
            || chatRequest.getReasoningEffort().isBlank()
            ? "high" : chatRequest.getReasoningEffort().strip();
        // Doubao 默认开启深度思考；仅当思考等级为 none 时关闭（thinking.type=disabled）。
        boolean thinkingEnabled = !"none".equalsIgnoreCase(reasoningEffort);
        return DoubaoStreamingChatModel.builder()
            .endpoint(baseUrl + "/chat/completions")
            .apiKey(chatModelVo.resolveApiKeyForConfiguredEndpoint(getProviderName()))
            .modelName(chatModelVo.getModelName())
            .timeout(doubaoTimeout)
            .reasoningEffort(reasoningEffort)
            .thinkingEnabled(thinkingEnabled)
            .build();
    }

    @Override
    public io.agentscope.core.model.Model buildChatModel(ChatModelVo config) {
        return buildStreamingChatModel(config, null);
    }

    @jakarta.annotation.PostConstruct
    public void registerNativeAdapter() {
        io.agentscope.core.model.ModelRegistry.registerFactory("openai:(?i:doubao).*", (String modelId, io.agentscope.core.model.ModelCreationContext context) -> {
            var options = context.component(io.agentscope.core.model.GenerateOptions.class);
            String effort = options == null || options.getReasoningEffort() == null ? "high" : options.getReasoningEffort();
            return DoubaoStreamingChatModel.builder().endpoint(context.getBaseUrl().replaceAll("/$", "") + "/chat/completions")
                .apiKey(context.getApiKey()).modelName(modelId.substring(modelId.indexOf(':') + 1))
                .timeout(doubaoTimeout).reasoningEffort(effort).thinkingEnabled(!"none".equalsIgnoreCase(effort)).build();
        });
    }

    @Override
    public String getProviderName() {
        return ChatModeType.CUSTOM_API.getCode();
    }

    /**
     * 是否为字节 Doubao-Seed-Evolving 模型（精确匹配两个官方 Model ID，不参与自动路由）。
     * 判定内联自 coding.harness.modelruntime.HarnessModelPolicy#isDoubao（自研 harness 随官方化摘链删除，2026-10-02）。
     */
    private static boolean isDoubao(String model) {
        String normalized = model == null ? "" : model.strip();
        return "doubao-seed-evolving".equals(normalized)
            || "bytedance/doubao-seed-evolving".equals(normalized);
    }

    private String validateConfiguration(ChatModelVo config) {
        if (!getProviderName().equals(config.getProviderCode())) {
            throw new IllegalArgumentException("模型厂商与 OpenAI 自定义适配器不匹配");
        }
        return CustomApiCredentialPolicy.requireConfiguration(
            getProviderName(), config.getModelName(), config.getApiHost(), config.getApiKey());
    }
}
