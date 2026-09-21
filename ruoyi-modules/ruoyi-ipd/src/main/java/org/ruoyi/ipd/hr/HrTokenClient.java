package org.ruoyi.ipd.hr;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HR 平台 Token 客户端（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>对接 {@code zkteco.token.get}，token 默认 7200 秒；本地内存缓存（有效期提前 300 秒失效），
 * TODO: 多实例部署时迁到 Redis（key=`ipd:hr:token`）。每次拿新 token 都会刷新末次刷新时间。
 *
 * <p>接口契约（来自 HR 文档 §1.3）：
 * <ul>
 *   <li>method  ：zkteco.token.get</li>
 *   <li>入参    ：公共参 6 个（appId/format/method/timestamp/signType/sign），无 body、无 token、无 data</li>
 *   <li>响应    ：code=0 成功 → data.token + data.expiresIn（秒）</li>
 *   <li>签名    ：见 {@link HrSignatureUtil}，sign 不参与自身签名</li>
 * </ul>
 *
 * <p>失败语义：HR 返回 code≠0 抛 {@link HrApiException}；HTTP 4xx/5xx 抛 {@link HrApiException}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrTokenClient {

    private final HrSyncProperties props;
    private final HrSignatureUtil signatureUtil;

    /** 简单 volatile 缓存（多实例下 TODO 切 Redis，已在类注释留位）。 */
    private volatile CachedToken cached;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /**
     * 获取可用 token；优先复用缓存，过期或缺失则调 {@code zkteco.token.get}。
     */
    public synchronized String getToken() {
        if (cached != null && !cached.isExpired()) {
            return cached.token();
        }
        return refresh();
    }

    /** 强制刷新（运维手动调用：发现 token 失效后单点刷新）。 */
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
            throw new HrApiException(code, root.path("msg").asText("token.get failed"), null);
        }
        String token = root.path("data").path("token").asText(null);
        long expiresIn = root.path("data").path("expiresIn").asLong(7200L);
        if (token == null || token.isBlank()) {
            throw new HrApiException(-1, "token.get 响应缺 token", null);
        }
        this.cached = new CachedToken(token, System.currentTimeMillis() + (expiresIn - 300) * 1000L);
        log.info("ipd_hr_token_refreshed expiresIn={}s", expiresIn);
        return token;
    }

    /** 公开给上层做 POST 复用（HrApiClient 用得到）；返回完整 JsonNode。 */
    public JsonNode postJson(String path, String jsonBody) {
        if (!props.isEnabled()) {
            throw new HrApiException(-1, "ipd.hr.enabled=false，跳过调用", null);
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
                throw new HrApiException(sc, "HTTP " + sc, raw);
            }
            try {
                return cn.hutool.json.JSONUtil.parse(raw);
            } catch (Exception e) {
                throw new HrApiException(sc, "响应 JSON 解析失败", raw);
            }
        } catch (HrApiException e) {
            throw e;
        } catch (Exception e) {
            throw new HrApiException(-1, "HTTP 调用异常: " + e.getMessage(), null);
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private record CachedToken(String token, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() >= expiresAt; }
    }
}
