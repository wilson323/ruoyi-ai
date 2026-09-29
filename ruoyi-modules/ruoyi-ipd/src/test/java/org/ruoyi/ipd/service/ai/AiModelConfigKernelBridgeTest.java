package org.ruoyi.ipd.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.service.AiModelConfigService;

/**
 * C2 模型权威桥测试：热切换 A→B→A、失效配置拒绝、映射纯函数、凭据不外泄。
 *
 * <p><b>注意</b>：必须 {@code @Tag("dev")}——本仓 Surefire 按 {@code <groups>${profiles.active}</groups>}
 * 过滤，缺 tag 会被静默跳过（假绿陷阱）。
 *
 * <p>Mock 合法性（mock 合法规约 2026-09-08）：仅 mock {@code currentEnabled()/decryptApiKey()}
 * 的读取面，产出的 {@code AiModelConfig} 字段组合与真库写入路径一致（isActive=true 单行、
 * 密文可空），不造真库不可能出现的组合。
 */
@Tag("dev")
@DisplayName("C2 模型权威桥：ai_model_configs → KernelModelRequest")
class AiModelConfigKernelBridgeTest {

    private AiModelConfigService modelConfigService;
    private AiModelConfigKernelBridge bridge;

    @BeforeEach
    void setUp() {
        modelConfigService = mock(AiModelConfigService.class);
        bridge = new AiModelConfigKernelBridge(modelConfigService);
    }

    private static AiModelConfig config(Long id, String provider, String modelName, String endpoint) {
        AiModelConfig cfg = new AiModelConfig();
        cfg.setId(id);
        cfg.setProvider(provider);
        cfg.setModelName(modelName);
        cfg.setEndpointUrl(endpoint);
        cfg.setIsActive(true);
        return cfg;
    }

    @Test
    @DisplayName("热切换 A→B→A：每次现读权威，模型身份随配置即时变化")
    void hotSwitchAtoBtoA() {
        AiModelConfig a = config(1L, "openai", "model-a", "https://a.example.com/v1");
        AiModelConfig b = config(2L, "zhipu", "model-b", "https://b.example.com/v1");
        when(modelConfigService.currentEnabled()).thenReturn(a, b, a);
        when(modelConfigService.decryptApiKey(a)).thenReturn("key-a");
        when(modelConfigService.decryptApiKey(b)).thenReturn("key-b");

        KernelModelRequest first = bridge.currentRequest();
        KernelModelRequest second = bridge.currentRequest();
        KernelModelRequest third = bridge.currentRequest();

        // A 轮
        assertThat(first.modelName()).isEqualTo("model-a");
        assertThat(first.providerCode()).isEqualTo("openai");
        assertThat(first.apiHost()).isEqualTo("https://a.example.com/v1");
        assertThat(first.apiKey()).isEqualTo("key-a");
        // B 轮（换模型/换端点/换密钥）
        assertThat(second.modelName()).isEqualTo("model-b");
        assertThat(second.apiKey()).isEqualTo("key-b");
        // 回 A：与首轮身份一致（内核 configurationIdentity 由同字段摘要产出 → 同配置同实例）
        assertThat(third).isEqualTo(first);
        // A≠B：装配身份不同 → 内核 Agent 缓存键不同 → 换配置自动换实例（实例回收）
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("失效配置拒绝：无生效配置 fail-closed 上抛，不隐式回落默认模型")
    void disabledConfigRejected() {
        when(modelConfigService.currentEnabled())
                .thenThrow(new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT));

        assertThatThrownBy(() -> bridge.currentRequest())
                .isInstanceOf(IpdBusinessException.class);
    }

    @Test
    @DisplayName("映射纯函数：字段薄翻译 provider→providerCode、endpointUrl→apiHost")
    void mappingIsThinTranslation() {
        AiModelConfig cfg = config(9L, "qianwen", "qwen-max", "https://q.example.com/v1");

        KernelModelRequest req = AiModelConfigKernelBridge.from(cfg, "plain-key");

        assertThat(req.modelName()).isEqualTo("qwen-max");
        assertThat(req.providerCode()).isEqualTo("qianwen");
        assertThat(req.apiHost()).isEqualTo("https://q.example.com/v1");
        assertThat(req.apiKey()).isEqualTo("plain-key");
    }

    @Test
    @DisplayName("未上送密钥：空串语义透传（mock embed/本地测试场景，非冲突态）")
    void blankKeyPassesThrough() {
        AiModelConfig cfg = config(3L, "openai", "embed-mock", "http://127.0.0.1:9999/v1");
        when(modelConfigService.currentEnabled()).thenReturn(cfg);
        when(modelConfigService.decryptApiKey(cfg)).thenReturn("");

        KernelModelRequest req = bridge.currentRequest();

        assertThat(req.apiKey()).isEmpty();
    }

    @Test
    @DisplayName("凭据不外泄：toString 脱敏，明文密钥不出现")
    void credentialsRedactedInToString() {
        AiModelConfig cfg = config(1L, "openai", "model-a", "https://a.example.com/v1");

        String rendered = AiModelConfigKernelBridge.from(cfg, "super-secret-key").toString();

        assertThat(rendered).doesNotContain("super-secret-key");
        assertThat(rendered).contains("<redacted>");
    }
}
