package org.ruoyi.common.chat.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R226-B1（卡 b4da8962）行为锁：requireTrustedConfiguration 的 else 分支曾无条件调用
 * requireDeepSeekConfiguration，导致任何非 deepseek/ppio/atlas/custom provider 的配置
 * 在构建客户端时被误报为 "DeepSeek model provider is not trusted"。修复为与
 * requirePersistableConfiguration 同型的 isDeepSeekConfiguration 守卫。
 * 本测试同时锁死"修复未放松凭证防混淆"：env 引用白名单仍然拦住非 allowlisted 密钥。
 */
@Tag("dev")
class ChatModelCredentialPolicyTest {

    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String DEEPSEEK_REF = "env:DEEPSEEK_API_KEY";

    @Test
    void nonAllowlistedProviderIsNoLongerMislabelledAsDeepSeekFailure() {
        assertDoesNotThrow(() -> ChatModelCredentialPolicy.requireTrustedConfiguration(
            "minimax", "MiniMax-M2", "https://api.minimaxi.com/v1", "test-api-key"));
        assertDoesNotThrow(() -> ChatModelCredentialPolicy.requireTrustedConfiguration(
            "qianwen", "qwq-plus-latest", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "sk-not-an-env-reference"));
    }

    @Test
    void deepseekBranchRegressionKeepsFullValidation() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ChatModelCredentialPolicy.requireTrustedConfiguration(
                "deepseek", DEEPSEEK_MODEL, "https://evil.example.com", DEEPSEEK_REF));
        assertEquals("DeepSeek API host is not allowlisted", ex.getMessage());
        assertDoesNotThrow(() -> ChatModelCredentialPolicy.requireTrustedConfiguration(
            "deepseek", DEEPSEEK_MODEL, ChatModelCredentialPolicy.DEEPSEEK_API_HOST, DEEPSEEK_REF));
    }

    @Test
    void deepSeekNamedModelUnderForeignProviderStillFailsClosed() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ChatModelCredentialPolicy.requireTrustedConfiguration(
                "minimax", DEEPSEEK_MODEL, "https://api.minimaxi.com/v1", DEEPSEEK_REF));
        assertEquals("DeepSeek model provider is not trusted", ex.getMessage());
    }

    @Test
    void credentialConsumerBindingAndReferenceAllowlistRemainEnforced() {
        // 消费方与配置方不一致必须拒绝（守卫修复未触碰这道防线）。
        assertThrows(IllegalArgumentException.class,
            () -> ChatModelCredentialPolicy.resolveApiKeyForUse(
                "minimax", "qianwen", "qwq-plus-latest",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "env:DEEPSEEK_API_KEY",
                name -> "secret"));
        // 非 env 白名单引用必须在读取环境前被拒绝，密钥不得跨 provider 混用。
        assertThrows(IllegalArgumentException.class,
            () -> ChatModelCredentialPolicy.resolveApiKeyForUse(
                "minimax", "minimax", "MiniMax-M2",
                "https://api.minimaxi.com/v1", "test-api-key", name -> "secret"));
    }
}
