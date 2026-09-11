package org.ruoyi.ipd.service.ai;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.net.ConnectException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * AI-STRAT-2 统一 AI 调用层单测：错误码白名单映射（表驱动）+ SSRF 前置复用验证。
 * <p>mock 合法性（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md）：
 * 本类不 mock Langchain4j ChatModel 假造响应——真模型路径靠 P422AcceptanceTest
 * 的 AiGateway 桩在 Service 层覆盖；本类只测「不依赖模型行为」的纯映射与前置防御。
 */
@Tag("dev")
@DisplayName("AI-STRAT-2 AiGateway：错误映射 + SSRF 前置")
class AiGatewayTest {

    @Test
    @DisplayName("mapFailure：Langchain4j 异常 → 白名单错误码（表驱动）")
    void mapFailureTable() {
        assertMap(new TimeoutException("t"), "TIMEOUT");
        assertMap(http(401), "AUTH_FAILED");
        assertMap(http(403), "AUTH_FAILED");
        assertMap(http(429), "HTTP_429");
        assertMap(http(500), "HTTP_500");
        // IOException 被 JDK 客户端包成 RuntimeException（源码实证）→ cause 解链后归类
        assertMap(new RuntimeException("wrapped", new ConnectException("refused")), "UNREACHABLE");
        assertMap(new RuntimeException("wrapped", new UnknownHostException("nohost")), "UNREACHABLE");
        assertMap(new RuntimeException("wrapped",
            new java.net.http.HttpTimeoutException("read timeout")), "TIMEOUT");
        assertMap(new IllegalStateException("boom"), "UNSUPPORTED_PROTOCOL");
    }

    @Test
    @DisplayName("mapFailure：HTTP code 透传且不带响应 body（apiKey/原文不泄露面）")
    void mapFailureMessageWhitelist() {
        AiChatResult r = AiGateway.mapFailure(http(503), 42L);
        assertEquals("HTTP_503", r.errorCode());
        assertEquals("HTTP 503", r.errorMessage());
        assertEquals(42L, r.latencyMs());
    }

    @Test
    @DisplayName("chat：SSRF 前置校验复用 AiChatClient.ssrfCheck（内网端点直拒，不触模型）")
    void chatSsrfPrecheckRejected() {
        AiChatClient legacy = mock(AiChatClient.class);
        doThrow(new IpdBusinessException("SSRF blocked: private/loopback/link-local endpoint 127.0.0.1"))
            .when(legacy).ssrfCheck(anyString());
        AiGateway gateway = new AiGateway(legacy);
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> gateway.chat(new AiTestConfig("openai", "http://127.0.0.1:11434", "sk-x", "m"), "p", null, null));
        assertTrue(ex.getMessage().contains("SSRF blocked"));
    }

    @Test
    @DisplayName("chat：SSRF 校验拿配置原值；尾斜杠 URL 不炸（builder 层另做剥离）")
    void chatStripsTrailingSlash() {
        AiChatClient legacy = mock(AiChatClient.class);
        org.mockito.ArgumentCaptor<String> baseUrlCaptor =
            org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.doNothing().when(legacy).ssrfCheck(baseUrlCaptor.capture());
        AiGateway gateway = new AiGateway(legacy);
        // 公网假端点：SSRF 校验放行（mock），模型调用必然 UNREACHABLE——验证点在「不因 URL 形态炸」
        AiChatResult r = gateway.chat(
            new AiTestConfig("openai", "https://api.example.com/v1/", "sk-x", "m"), "ping", null, null);
        // DNS 解析失败（api.example.com 不存在）→ UNREACHABLE / TIMEOUT / UNSUPPORTED_PROTOCOL 皆合法，
        // 只要不抛未分类异常即证明 URL 拼接路径正常
        assertTrue(!r.success());
        // SSRF 校验拿配置原值（host 解析不受尾斜杠影响）；剥离仅发生在 OpenAiChatModel.builder 层
        assertEquals("https://api.example.com/v1/", baseUrlCaptor.getValue());
    }

    private static void assertMap(Throwable e, String expectedCode) {
        AiChatResult r = AiGateway.mapFailure(e, 7L);
        assertEquals(expectedCode, r.errorCode(), () -> "异常 " + e + " 应映射 " + expectedCode);
    }

    private static HttpException http(int code) {
        return new HttpException(code, "body-irrelevant");
    }
}
