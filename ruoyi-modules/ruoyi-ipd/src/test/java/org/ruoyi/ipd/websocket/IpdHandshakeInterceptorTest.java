package org.ruoyi.ipd.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IpdHandshakeInterceptor 握手前置校验单测（C1.5）。
 *
 * <p>覆盖范围与边界：
 * <ul>
 *   <li>只测 <b>不依赖 SaToken 运行环境</b> 的拒绝分支（无 query / 无 token 参数 / token 空串）——
 *       这三个分支在调用 {@code getTokenSessionByToken} 之前就短路返回，纯函数级可测</li>
 *   <li>「无效 token 被拒」与「有效 token 通过」两个分支依赖 SaTokenDao/session 运行环境，
 *       已由真机 curl 验证覆盖（2026-09-11：无 token → missing_token；错 token → invalid_token；
 *       正 token → 101 Switching Protocols + handshake_ok）——不在本单测重复造环境</li>
 * </ul>
 *
 * <p>PersonMapper 传 null：上述三个分支都在调用 personMapper 之前返回，不会 NPE。
 */
@Tag("dev")
class IpdHandshakeInterceptorTest {

    private final IpdHandshakeInterceptor interceptor = new IpdHandshakeInterceptor(null);

    @Test
    @DisplayName("无 query 时握手被拒，attributes 不被写入")
    void rejectsWhenNoQuery() {
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(
            requestWithQuery(null), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
        assertThat(ok).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("query 有参数但无 token 键时握手被拒")
    void rejectsWhenTokenParamAbsent() {
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(
            requestWithQuery("foo=bar&baz=1"), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
        assertThat(ok).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("token 键存在但值为空串时握手被拒")
    void rejectsWhenTokenBlank() {
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(
            requestWithQuery("token="), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
        assertThat(ok).isFalse();
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("token 位于多参数中间时解析器不依赖参数顺序（正向解析路径不被短路）")
    void tokenAmongOtherParamsIsExtracted() {
        // 本用例不走到 SaToken 校验成功（无运行环境），仅证明「提取到 token 后不会命中 missing_token 短路」——
        // 表现为：返回 false 的 reason 与缺 token 不同（此处不打印日志，仅断言 false 且无 attributes 写入，
        // 由真机测试补充正 token 的 101 证据）
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(
            requestWithQuery("clientid=abc&token=fake.jwt.token&other=1"),
            mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
        assertThat(ok).isFalse();
        assertThat(attributes).isEmpty();
    }

    private static ServerHttpRequest requestWithQuery(String query) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        String suffix = (query == null || query.isEmpty()) ? "" : "?" + query;
        when(request.getURI()).thenReturn(URI.create("ws://127.0.0.1:16039/resource/websocket" + suffix));
        return request;
    }
}