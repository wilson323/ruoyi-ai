package org.ruoyi.ipd.service.ai;

import java.util.Objects;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.service.AiModelConfigService;

/**
 * C2 模型权威桥（2026-09-29）：IPD {@code ai_model_configs} → 内核
 * {@link KernelModelRequest} 的唯一映射口，使 ai_model_configs 成为进内核对话链的
 * 实际选择权威（C2 验收项 1）。
 *
 * <p>背景：内核装配面此前自述「IPD ai_model_configs 尚未接入」
 * （{@code KernelModelSelector} Javadoc），ruoyi-chat 侧配置来自 {@code chat_model}。
 * 本桥补齐后，C0 拍板 3 圈定的对话链（#1 AiCopilotService / #2 CopilotKit·AG-UI）
 * 委托内核时一律经 {@link #currentRequest()} 取模型身份，禁绕道第二权威。
 *
 * <p><b>热切换（C2 验收项 2/3）</b>：{@link #currentRequest()} 每次现读权威
 * （{@code currentEnabled()}），不缓存配置对象——A→B→A 切换即时生效；
 * 端点/密钥变化经 {@link KernelModelRequest} 的 {@code configurationIdentity()}
 * （SHA-256 摘要）产生新装配身份，内核 Agent 缓存键包含该身份
 * （{@code AgentScopeChatKernel} AgentConfiguration cacheKey）→ 换配置自动换实例
 * （实例回收，C2 验收项 6）。
 *
 * <p><b>失效拒绝（C2 验收项 6）</b>：ai_model_configs 无生效配置时
 * {@code currentEnabled()} 抛 {@code IpdBusinessException(STATE_CONFLICT)} fail-closed
 * ——拒绝本轮，禁止隐式回落默认模型（与内核「明确选定模型装配失败即拒绝」同语义）。
 *
 * <p><b>凭据纪律</b>：apiKey 仅内存消费进装配上下文；{@link KernelModelRequest#toString()}
 * 已脱敏（{@code apiKey=<redacted>}），不落日志/响应/审计正文。
 */
public final class AiModelConfigKernelBridge {

    private final AiModelConfigService modelConfigService;

    public AiModelConfigKernelBridge(AiModelConfigService modelConfigService) {
        this.modelConfigService = Objects.requireNonNull(modelConfigService, "modelConfigService");
    }

    /**
     * 现读权威 → 本轮模型身份。每次调用重查，热切换即时生效；
     * 无生效配置由 {@code currentEnabled()} fail-closed 抛出，不产出请求。
     */
    public KernelModelRequest currentRequest() {
        AiModelConfig config = modelConfigService.currentEnabled();
        return from(config, modelConfigService.decryptApiKey(config));
    }

    /**
     * 纯映射（可独立测试）：字段名薄翻译——{@code provider}→{@code providerCode}、
     * {@code endpointUrl}→{@code apiHost}、{@code modelName}→{@code modelName}。
     * 不承载任何选型/解析逻辑（解析唯一源 = 内核 ModelRegistry，禁第二套路由）。
     */
    static KernelModelRequest from(AiModelConfig config, String plainApiKey) {
        Objects.requireNonNull(config, "config");
        return new KernelModelRequest(
                config.getModelName(),
                config.getProvider(),
                plainApiKey,
                config.getEndpointUrl());
    }
}
