package org.ruoyi.ipd.hr;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HR 平台 Token 客户端（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>对接 {@code zkteco.token.get}（HR §1.3）：无 body / 无 token / 无 data；
 * token 默认 7200 秒，本地 volatile 缓存（TTL = expiresIn − 300 秒）。
 *
 * <p>多实例部署时本缓存不能跨节点共享——TODO 切 Redis（{@code ipd:hr:token}）。
 *
 * <p>失败语义：HR 业务 code≠0 / HTTP 4xx/5xx → 抛 {@link HrApiException}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrTokenClient {

    private final HrSyncProperties props;
    private final HrSignatureUtil signatureUtil;

    private volatile CachedToken cached;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public synchronized String getToken() {
        if (cached != null && !cached.isExpired()) return cached.token();
        return refresh();
    }

    public synchronized String refresh() {
        long ts = System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("appId", nvl(props.getAppId()));
        params.put("format", "JSON");
        params.put("method", "zkteco.token.get");
        params.put("timestamp", ts);
        params.put("signType", "md5");
        params.put("sign", signatureUtil.sign(params, nvl(props.getSecretKey())));

        String body = JsonUtil.toJsonString(params);
        JsonNode root = postJson("/token/get", body);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new HrApiException(HrApiException.PERMANENT, String.valueOf(code),
                root.path("msg").asText("token.get failed"), null);
        }
        String token = root.path("data").path("token").asText(null);
        long expiresIn = root.path("data").path("expiresIn").asLong(7200L);
        if (token == null || token.isBlank()) {
            throw new HrApiException(HrApiException.PERMANENT, "-1",
                "token.get 响应缺 token", null);
        }
        this.cached = new CachedToken(token, System.currentTimeMillis() + (expiresIn - 300) * 1000L);
        log.info("ipd_hr_token_refreshed expiresIn={}s", expiresIn);
        return token;
    }

    /** POST JSON + 解析响应；HTTP 4xx/5xx → HrApiException。 */
    public JsonNode postJson(String path, String jsonBody) {
        if (!props.isEnabled()) {
            throw new HrApiException(HrApiException.PERMANENT, "DISABLED",
                "ipd.hr.enabled=false，跳过调用", null);
        }
        String url = nvl(props.getBaseUrl()) + path;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getHttp().getReadTimeoutMs()))
                .header("Content-Type", "application/json;charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int sc = resp.statusCode();
            String raw = resp.body();
            if (sc < 200 || sc >= 300) {
                throw new HrApiException(HrApiException.TRANSIENT, String.valueOf(sc),
                    "HTTP " + sc, raw);
            }
            try {
                return JsonUtil.parseTree(raw);
            } catch (Exception e) {
                throw new HrApiException(HrApiException.TRANSIENT, String.valueOf(sc),
                    "响应 JSON 解析失败", raw);
            }
        } catch (HrApiException e) {
            throw e;
        } catch (Exception e) {
            throw new HrApiException(HrApiException.TRANSIENT, "-1",
                "HTTP 调用异常: " + e.getMessage(), null);
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private record CachedToken(String token, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() >= expiresAt; }
    }
}
