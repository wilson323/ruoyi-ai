package org.ruoyi.mcp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * 「一次 POST、一次响应」形态的 MCP HTTP 客户端传输层。
 *
 * <p><b>为什么需要它</b>：MCP Java SDK 自带的两种 HTTP 传输层
 * （{@code HttpClientStreamableHttpTransport} / {@code HttpClientSseClientTransport}）
 * 都会在发消息之前先发一个 <b>GET</b> 请求建立服务端推送通道。但有一类 MCP 服务
 * 是「无状态」的——只接受 POST，每次现算现回，不提供推送通道，对 GET 直接返回
 * 405 且响应类型是 {@code text/html}。SDK 拿到 HTML 后按协议解析，抛
 * {@code Unknown media type returned: text/html;charset=utf-8}。
 *
 * <p>秘塔搜索 MCP（{@code https://metaso.cn/api/mcp}）正是这一类，2026-10-07 实测：
 * GET 返回 405 text/html，POST 返回 {@code application/json} 的裸 JSON-RPC。
 * 本类把响应直��交给 SDK 自己的多态反序列化器
 * {@link McpSchema#deserializeJsonRpcMessage}，因此协议语义仍然由 SDK 判定，
 * 本类只负责「怎么把一个 JSON-RPC 报文送出去、把一个 JSON 报文收回来」。
 *
 * <p><b>不做什么</b>：不解析业务语义、不缓存工具列表、不重试。所有出错路径都
 * 一律以异常上抛，绝不静默降级——静默失败长得像成功。
 */
public final class JsonRpcHttpClientTransport implements McpClientTransport {

    /** 无状态服务端通常只回固定的老协议版本；不要向 SDK 声明更新的版本号去碰运气。 */
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2024-11-05");
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String ACCEPT_JSON_AND_EVENT_STREAM = "application/json, text/event-stream";

    private final McpJsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration requestTimeout;
    private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler =
            new AtomicReference<>();
    private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

    private volatile String negotiatedProtocolVersion;

    private JsonRpcHttpClientTransport(McpJsonMapper jsonMapper, HttpClient httpClient, URI endpoint,
            Map<String, String> headers, Duration requestTimeout) {
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.headers = Map.copyOf(headers);
        this.requestTimeout = requestTimeout;
    }

    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    @Override
    public List<String> protocolVersions() {
        return SUPPORTED_PROTOCOL_VERSIONS;
    }

    /**
     * 无长连接，握手即刻成立。
     *
     * <p>SDK 会把本方法返回的 Mono 直接 {@code subscribe()} 并丢弃其 Disposable
     * （{@code McpClientSession} 构造函数第 127 行），因此返回 {@code Mono.empty()}
     * 即表示「已连接」；返回未终结的 Mono 反而会让会话挂着。
     */
    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        // 会话侧的 handler 形态是 `inbound -> inbound.doOnNext(this::handle)`
        // （McpClientSession 的 lambda$new$0，javap 实测），也就是「一次 apply 只消费一条」。
        // 因此这里只登记 handler，由每次响应到达时逐条 apply；connect 本身没有长连接可等待，
        // 立即返回空 Mono 表示「已连接」——SDK 会 subscribe 后丢弃其 Disposable。
        this.handler.set(handler);
        return Mono.empty();
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        exceptionHandler.set(handler);
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromCallable(() -> jsonMapper.writeValueAsString(message))
                .flatMap(this::post)
                .doOnNext(response -> emit(response))
                .doOnError(this::report)
                .onErrorComplete()
                .then();
    }

    /** 把一个 JSON-RPC 响应报文交给 SDK 会话；通知类（无 id）服务端会回空体，直接跳过。 */
    private void emit(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return;
        }
        try {
            McpSchema.JSONRPCMessage parsed = McpSchema.deserializeJsonRpcMessage(jsonMapper, responseBody);
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> current = handler.get();
            if (current != null) {
                current.apply(Mono.just(parsed)).subscribe();
            }
        } catch (IOException failure) {
            report(failure);
        }
    }

    private Mono<String> post(String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("Accept", ACCEPT_JSON_AND_EVENT_STREAM)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        String version = negotiatedProtocolVersion;
        if (version != null && !version.isBlank()) {
            request.header("MCP-Protocol-Version", version);
        }
        return Mono.fromFuture(httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()))
                .flatMap(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        // 刻意不把响应体带进异常：服务端错误页里可能含请求头回显。
                        return Mono.error(new IllegalStateException("HTTP 状态码 " + status));
                    }
                    return Mono.just(response.body());
                });
    }

    private void report(Throwable failure) {
        Consumer<Throwable> handler = exceptionHandler.get();
        if (handler != null) {
            handler.accept(failure);
        }
    }

    @Override
    public Mono<Void> closeGracefully() {
        handler.set(null);
        return Mono.empty();
    }

    @Override
    public <T> T unmarshalFrom(Object object, TypeRef<T> type) {
        return jsonMapper.convertValue(object, type);
    }

    @Override
    public String toString() {
        return "JsonRpcHttpClientTransport[endpoint=" + endpoint + "]";
    }

    /** 构造器；凭证只交给协议客户端，不进日志、不进 {@link #toString()}。 */
    public static final class Builder {
        private final URI endpoint;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration requestTimeout = Duration.ofSeconds(30);

        private Builder(URI endpoint) {
            this.endpoint = endpoint;
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> values) {
            values.forEach(this::header);
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        public JsonRpcHttpClientTransport build() {
            return new JsonRpcHttpClientTransport(McpJsonMapper.getDefault(),
                    HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
                    endpoint, headers, requestTimeout);
        }
    }
}
