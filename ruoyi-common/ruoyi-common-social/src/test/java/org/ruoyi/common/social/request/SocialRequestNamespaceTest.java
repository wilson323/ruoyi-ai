package org.ruoyi.common.social.request;

import me.zhyd.oauth.cache.AuthStateCache;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.config.AuthDefaultSource;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.utils.HttpUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Tag("dev")
@Tag("dev")
class SocialRequestNamespaceTest {
    private AuthConfig config() {
        return AuthConfig.builder().clientId("fixture-corp").clientSecret("fixture-secret")
            .redirectUri("https://fixture.invalid/callback").agentId("fixture-agent")
            .loginType("CorpApp").lang("zh").build();
    }

    @Test void wechatAuthorizationAndValidationMatchSdkV2() {
        AuthStateCache cache = mock(AuthStateCache.class);
        var adapter = new RuoyiAuthWeChatEnterpriseQrcodeV2Request(config(), cache);
        var sdk = new me.zhyd.oauth.request.AuthWeChatEnterpriseQrcodeV2Request(config(), cache);
        assertEquals(sdk.authorize("fixture-state"), adapter.authorize("fixture-state"));
        AuthConfig invalid = config();
        invalid.setAgentId(null);
        assertThrows(AuthException.class, () -> new RuoyiAuthWeChatEnterpriseQrcodeV2Request(invalid, cache));
    }

    @Test void wechatLowercaseUseridAndJacksonSensitiveMergeRemainApplied() {
        var adapter = new RuoyiAbstractAuthWeChatEnterpriseRequest(config(), AuthDefaultSource.WECHAT_ENTERPRISE_V2,
            mock(AuthStateCache.class)) {
            @Override protected String doGetUserInfo(AuthToken token) {
                return "{\"userid\":\"fixture-person\",\"user_ticket\":\"fixture-ticket\"}";
            }
            @Override protected String doGetAuthorizationCode(String code) {
                return "{\"access_token\":\"fixture-token\",\"expires_in\":7200}";
            }
        };
        AuthToken token = adapter.getAccessToken(AuthCallback.builder().code("fixture-code").build());
        assertEquals("fixture-token", token.getAccessToken());
        assertEquals(7200, token.getExpireIn());
        AtomicInteger calls = new AtomicInteger();
        try (var mocked = mockConstruction(HttpUtils.class, (client, context) -> {
            when(client.get(anyString())).thenReturn(client);
            when(client.post(anyString(), anyString())).thenReturn(client);
            when(client.getBody()).thenReturn(calls.getAndIncrement() == 0
                ? "{\"errcode\":0,\"name\":\"base-name\",\"email\":\"base@example.invalid\"}"
                : "{\"errcode\":0,\"alias\":\"sensitive-alias\"}");
        })) {
            var user = adapter.getUserInfo(token);
            assertEquals("fixture-person", user.getUuid());
            assertEquals("base-name", user.getUsername());
            assertEquals("sensitive-alias", user.getNickname());
            assertEquals("base@example.invalid", user.getEmail());
            assertEquals(2, mocked.constructed().size());
        }
    }

    @Test void dingtalkJacksonTokenAndUserMappingRemainApplied() {
        AtomicInteger calls = new AtomicInteger();
        try (var ignored = mockConstruction(HttpUtils.class, (client, context) -> {
            when(client.post(anyString(), anyString())).thenReturn(client);
            when(client.get(anyString(), any(), any(), eq(false))).thenReturn(client);
            when(client.getBody()).thenReturn(calls.getAndIncrement() == 0
                ? "{\"accessToken\":\"fixture-token\",\"refreshToken\":\"fixture-refresh\",\"expireIn\":3600}"
                : "{\"openId\":\"fixture-open\",\"unionId\":\"fixture-union\",\"nick\":\"fixture-name\",\"visitor\":true}");
        })) {
            var request = new RuoyiAuthDingTalkV2Request(config(), mock(AuthStateCache.class));
            var token = request.getAccessToken(AuthCallback.builder().code("fixture-code").build());
            assertEquals("fixture-token", token.getAccessToken());
            assertEquals(3600, token.getExpireIn());
            var user = request.getUserInfo(token);
            assertEquals("fixture-union", user.getUuid());
            assertEquals("fixture-name", user.getNickname());
            assertEquals("fixture-open", token.getOpenId());
            assertTrue(user.isSnapshotUser());
        }
    }
}
