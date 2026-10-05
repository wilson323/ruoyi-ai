package org.ruoyi.chat.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * W2 模型层单测（ADR-0075 矩阵 #8「替换」+ #9「包装」）。
 *
 * <p>被测语义：
 * <ol>
 *   <li>请求 {@code model} 字段路由（动态切换）：modelName/providerCode → "provider:model"
 *       注册键薄归一（翻译不是路由）；</li>
 *   <li>装配只经原生解析缝（ModelAssembler = ModelRegistry.resolve 语义），
 *       凭据/端点由 ModelCreationContext 承载；</li>
 *   <li>空请求 → 默认配置（BLANK_REQUEST）；显式选型装配失败 → 拒绝本轮
 *       （ASSEMBLE_FAILED）；默认配置也不可装配 → 异常上抛（调用方转 KERNEL_ERROR）。</li>
 * </ol>
 */
@Tag("dev")
@DisplayName("W2 模型选择：注册键薄归一 + 原生装配 + 降级留痕")
class KernelModelSelectorTest {

    private static final String DEFAULT_KEY = "minimax:MiniMax-M3";

    @Test
    @DisplayName("同名模型配置身份随端点和凭据变化，摘要和对象字符串不得泄露凭据")
    void configurationIdentityTracksEndpointAndCredentialWithoutDisclosure() {
        KernelModelRequest first = new KernelModelRequest("m1", "minimax", "test-secret-a", "https://a.test/v1");
        KernelModelRequest changedEndpoint = new KernelModelRequest("m1", "minimax", "test-secret-a", "https://b.test/v1");
        KernelModelRequest changedCredential = new KernelModelRequest("m1", "minimax", "test-secret-b", "https://a.test/v1");

        assertEquals(first.configurationIdentity(), first.configurationIdentity());
        assertNotEquals(first.configurationIdentity(), changedEndpoint.configurationIdentity());
        assertNotEquals(first.configurationIdentity(), changedCredential.configurationIdentity());
        assertFalse(first.configurationIdentity().contains("test-secret-a"));
        assertFalse(first.toString().contains("test-secret-a"));
        assertFalse(first.toString().contains("https://a.test/v1"));
    }

    @Test
    void ollamaTagIsPartOfModelName() {
        assertEquals("ollama:qwen3:0.6b", AgentScopeModelFactory.registryKey(
            new KernelModelRequest("qwen3:0.6b", "ollama", null, null)));
        assertEquals("ollama:qwen3:0.6b", AgentScopeModelFactory.registryKey(
            new KernelModelRequest("ollama:qwen3:0.6b", "ollama", null, null)));
    }

    /** 记录型装配缝：记录 (key, context) 并按脚本返回/抛错。 */
    private static final class RecordingAssembler implements KernelModelSelector.ModelAssembler {

        private final List<String> keys = new ArrayList<>();
        private final List<ModelCreationContext> contexts = new ArrayList<>();
        private final List<String> failingKeys = new ArrayList<>();
        private final Model model = mock(Model.class);

        @Override
        public Model assemble(String registryKey, ModelCreationContext context) {
            keys.add(registryKey);
            contexts.add(context);
            if (failingKeys.contains(registryKey)) {
                throw new IllegalStateException("assemble failed: " + registryKey);
            }
            return model;
        }
    }

    @Test
    @DisplayName("请求 model + providerCode → provider:model 注册键入选（source=REQUEST）")
    void requestModelRoutesToProviderKey() {
        RecordingAssembler assembler = new RecordingAssembler();
        KernelModelSelector selector = new KernelModelSelector(DEFAULT_KEY, assembler);

        KernelModelSelector.ModelPlan plan =
                selector.plan(new KernelModelRequest("MiniMax-M3", "minimax", "sk-test", "https://api.example.com/v1"));

        assertEquals("minimax:MiniMax-M3", plan.registryKey());
        assertEquals(KernelModelSelector.Source.REQUEST, plan.source());
        assertEquals("minimax:MiniMax-M3", plan.requestedKey());
        assertSame(assembler.model, plan.model());
        // 装配上下文携带传入凭据/端点；当前生产来源仍是 chat_model，非 ai_model_configs。
        assertEquals("sk-test", assembler.contexts.get(0).getApiKey());
        assertEquals("https://api.example.com/v1", assembler.contexts.get(0).getBaseUrl());
    }

    @Test
    @DisplayName("已是 provider:model 注册键 → 原样透传，不二次拼接")
    void registryKeyFormPassesThrough() {
        RecordingAssembler assembler = new RecordingAssembler();
        KernelModelSelector selector = new KernelModelSelector(DEFAULT_KEY, assembler);

        selector.plan(new KernelModelRequest("openai:gpt-4o-mini", "ignored", null, null));

        assertEquals("openai:gpt-4o-mini", assembler.keys.get(0));
    }

    @Test
    @DisplayName("厂商别名薄归一：zhipu→glm、qianwen→dashscope、OpenAI 兼容端点归一 custom_api→openai")
    void providerAliasIsDeterministicTranslation() {
        assertEquals("glm:glm-4", KernelModelSelector.registryKey(new KernelModelRequest("glm-4", "zhipu", null, null)));
        assertEquals("dashscope:qwen-max",
                KernelModelSelector.registryKey(new KernelModelRequest("qwen-max", "QianWen", null, null)));
        assertEquals("openai:my-model",
                KernelModelSelector.registryKey(new KernelModelRequest("my-model", "custom_api", null, null)));
        assertEquals("deepseek:deepseek-chat",
                KernelModelSelector.registryKey(new KernelModelRequest("deepseek-chat", "deepseek", null, null)));
    }

    @Test
    @DisplayName("空 model 字段 → 降级默认配置（DEFAULT_FALLBACK），不触请求键")
    void blankRequestFallsBackToDefaultWithRecordedReason() {
        RecordingAssembler assembler = new RecordingAssembler();
        KernelModelSelector selector = new KernelModelSelector(DEFAULT_KEY, assembler);

        KernelModelSelector.ModelPlan fromNull = selector.plan(null);
        KernelModelSelector.ModelPlan fromBlank = selector.plan(new KernelModelRequest("  ", null, null, null));

        assertEquals(DEFAULT_KEY, fromNull.registryKey());
        assertEquals(KernelModelSelector.Source.DEFAULT_FALLBACK, fromNull.source());
        assertNull(fromNull.requestedKey());
        assertEquals(DEFAULT_KEY, fromBlank.registryKey());
        assertEquals(KernelModelSelector.Source.DEFAULT_FALLBACK, fromBlank.source());
        assertEquals(List.of(DEFAULT_KEY, DEFAULT_KEY), assembler.keys);
    }

    @Test
    @DisplayName("请求模型装配失败 → 不调用默认模型，错误显式上抛")
    void assembleFailureFailsClosed() {
        RecordingAssembler assembler = new RecordingAssembler();
        assembler.failingKeys.add("dify:m1");
        KernelModelSelector selector = new KernelModelSelector(DEFAULT_KEY, assembler);

        assertThrows(IllegalStateException.class,
                () -> selector.plan(new KernelModelRequest("m1", "dify", null, null)));
        assertEquals(List.of("dify:m1"), assembler.keys,
                "明确请求的模型失败时不得装配另一个模型");
    }

    @Test
    @DisplayName("默认配置也不可装配 → 异常上抛（调用方转 KERNEL_ERROR 固定安全文案）")
    void defaultFailurePropagates() {
        RecordingAssembler assembler = new RecordingAssembler();
        assembler.failingKeys.add(DEFAULT_KEY);
        KernelModelSelector selector = new KernelModelSelector(DEFAULT_KEY, assembler);

        assertThrows(IllegalStateException.class, () -> selector.plan(null));
    }

    @Test
    @DisplayName("请求模型入选后不预装默认模型")
    void requestSelectionDoesNotLoadDefault() {
        RecordingAssembler assembler = new RecordingAssembler();
        KernelModelSelector selector = new KernelModelSelector(DEFAULT_KEY, assembler);

        KernelModelSelector.ModelPlan requested =
                selector.plan(new KernelModelRequest("m1", "minimax", null, null));
        assertEquals("minimax:m1", requested.registryKey());
        assertEquals(List.of("minimax:m1"), assembler.keys,
                "不得为选定模型隐式准备默认模型");
    }

    @Test
    @DisplayName("无 providerCode 的裸模型名 → 原样交给原生解析（解析裁定，不猜厂商）")
    void bareNameDelegatesToNativeResolution() {
        RecordingAssembler assembler = new RecordingAssembler();
        KernelModelSelector selector = new KernelModelSelector(DEFAULT_KEY, assembler);

        selector.plan(new KernelModelRequest("MiniMax-M3", null, null, null));

        assertEquals("MiniMax-M3", assembler.keys.get(0));
    }
}
