package org.ruoyi.ipd.service.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * P4-2.1 智谱 GLM Tester。
 * <p>
 * 接受历史 origin 或官方 /api/paas/v4 base；只探测连接，不证明模型正文或业务验收。
 */
public final class ZhipuTester implements AiProviderTester {

    private static final Set<String> ALIASES = Set.of("zhipu", "glm");

    private final HttpClient http;

    public ZhipuTester() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public ZhipuTester(HttpClient http) {
        this.http = http;
    }

    @Override
    public String provider() {
        return "zhipu";
    }

    @Override
    public Set<String> aliases() {
        return ALIASES;
    }

    @Override
    public AiTestResult test(AiTestConfig cfg) {
        long start = System.currentTimeMillis();
        try {
            String body = "{\"model\":\"" + jsonEscape(cfg.modelName()) + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\".\"}],"
                + "\"max_tokens\":1}";
            HttpRequest req = HttpRequest.newBuilder()
                .uri(completionsEndpoint(cfg.baseUrl()))
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return AiTestResult.ok(latency);
            }
            if (code == 401 || code == 403) {
                return AiTestResult.fail("AUTH_FAILED", "HTTP " + code, latency);
            }
            return AiTestResult.fail("HTTP_" + code, "HTTP " + code, latency);
        } catch (InvalidBaseUrl e) {
            return AiTestResult.fail("INVALID_BASE_URL", "智谱地址须为服务 origin 或 /api/paas/v4 基址", System.currentTimeMillis() - start);
        } catch (java.net.http.HttpTimeoutException e) {
            return AiTestResult.fail("TIMEOUT", "connect: timeout", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return AiTestResult.fail("UNSUPPORTED_PROTOCOL",
                "connect: " + e.getClass().getSimpleName(), System.currentTimeMillis() - start);
        }
    }

    private static URI completionsEndpoint(String baseUrl) {
        try {
            URI base = URI.create(baseUrl == null ? "" : baseUrl.strip());
            if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null) throw new InvalidBaseUrl();
            String path = base.getRawPath();
            if (!("".equals(path) || "/".equals(path) || "/api/paas/v4".equals(path)
                || "/api/paas/v4/".equals(path))) throw new InvalidBaseUrl();
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(),
                "/api/paas/v4/chat/completions", null, null);
        } catch (IllegalArgumentException | java.net.URISyntaxException invalid) {
            throw new InvalidBaseUrl();
        }
    }

    private static final class InvalidBaseUrl extends IllegalArgumentException { }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
