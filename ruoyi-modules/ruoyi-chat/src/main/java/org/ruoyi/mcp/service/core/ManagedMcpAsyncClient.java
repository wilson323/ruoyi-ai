package org.ruoyi.mcp.service.core;

import io.agentscope.core.tool.mcp.McpAsyncClientWrapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 保留原生协议与工具缓存，只补充有限、非阻塞且可观察的关闭生命周期。 */
@Slf4j
public final class ManagedMcpAsyncClient extends McpAsyncClientWrapper {
    private static final java.util.Set<ManagedMcpAsyncClient> CLOSING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final McpAsyncClient client;
    private final Duration closeTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Sinks.One<Void> closeResult = Sinks.one();
    private final Map<String, Map<String, Object>> rawToolSchemas;

    ManagedMcpAsyncClient(String name, McpAsyncClient client, Duration closeTimeout) {
        this(name, client, closeTimeout, new java.util.concurrent.ConcurrentHashMap<>());
    }

    private ManagedMcpAsyncClient(String name, McpAsyncClient client, Duration closeTimeout,
            Map<String, Map<String, Object>> rawToolSchemas) {
        super(name, client);
        this.client = client;
        this.closeTimeout = closeTimeout;
        this.rawToolSchemas = rawToolSchemas;
    }

    /** 同一公开工厂供框架市场与IPD服务目录共享，不转换各自业务编号。 */
    public static ManagedMcpAsyncClient create(String name,
            io.modelcontextprotocol.spec.McpClientTransport transport, Duration requestTimeout) {
        var schemas = new java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>>();
        var preserving = new SchemaCapturingTransport(transport, schemas);
        return new ManagedMcpAsyncClient(name, io.modelcontextprotocol.client.McpClient.async(preserving)
            .requestTimeout(requestTimeout).initializationTimeout(requestTimeout)
            .jsonSchemaValidator(new io.modelcontextprotocol.json.schema.jackson.JacksonJsonSchemaValidatorSupplier().get())
            .clientInfo(new McpSchema.Implementation("ruoyi-agentscope", "2.0.3"))
            .capabilities(McpSchema.ClientCapabilities.builder().build()).build(), Duration.ofSeconds(1), schemas);
    }

    /** 同一次 listTools 响应的完整 JSON；0.17.2 typed JsonSchema 会丢顶层组合约束。 */
    public Map<String, Object> rawInputSchema(String name) {
        if (closed.get()) throw new IllegalStateException("MCP客户端已关闭");
        var schema = rawToolSchemas.get(name);
        return schema == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().convertValue(schema,
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    /** 只读协议能力诊断；不授予资源、提示或工具的业务访问权。 */
    public McpSchema.ServerCapabilities serverCapabilities() {
        if (closed.get()) throw new IllegalStateException("MCP客户端已关闭");
        return client.getServerCapabilities();
    }

    /** 官方 transport 的透明解码装饰器，不创建第二客户端或请求。 */
    static final class SchemaCapturingTransport implements io.modelcontextprotocol.spec.McpClientTransport {
        private final io.modelcontextprotocol.spec.McpClientTransport delegate;
        private final Map<String, Map<String, Object>> schemas;
        SchemaCapturingTransport(io.modelcontextprotocol.spec.McpClientTransport delegate,
                Map<String, Map<String, Object>> schemas) { this.delegate = delegate; this.schemas = schemas; }
        @Override public Mono<Void> connect(java.util.function.Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            return delegate.connect(handler);
        }
        @Override public void setExceptionHandler(java.util.function.Consumer<Throwable> handler) { delegate.setExceptionHandler(handler); }
        @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) { return delegate.sendMessage(message); }
        @Override public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }
        @Override public void close() { delegate.close(); }
        @Override public List<String> protocolVersions() { return delegate.protocolVersions(); }
        @Override public <T> T unmarshalFrom(Object data, io.modelcontextprotocol.json.TypeRef<T> type) {
            if (McpSchema.ListToolsResult.class.equals(type.getType())) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.valueToTree(data);
                var tools = root.path("tools");
                if (!tools.isArray()) throw new IllegalStateException("MCP工具列表无法保真读取");
                var page = new java.util.LinkedHashMap<String, Map<String, Object>>();
                for (var tool : tools) {
                    String name = tool.path("name").asText();
                    var schema = tool.path("inputSchema");
                    if (name.isBlank() || !schema.isObject() || page.containsKey(name))
                        throw new IllegalStateException("MCP工具声明无法保真读取");
                    page.put(name, mapper.convertValue(schema,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
                }
                schemas.putAll(page);
            }
            return delegate.unmarshalFrom(data, type);
        }
    }

    public static ManagedMcpAsyncClient streamableHttp(String name, String url,
                                                       Map<String, String> headers, Duration timeout) {
        java.net.URI uri = java.net.URI.create(url);
        String endpoint = uri.getRawPath();
        if (endpoint == null || endpoint.isEmpty()) { endpoint = "/"; }
        if (uri.getRawQuery() != null) { endpoint += "?" + uri.getRawQuery(); }
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        return create(name, io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
            .builder(origin).endpoint(endpoint)
            .jsonMapper(new io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier().get())
            .customizeRequest(request -> headers.forEach(request::header)).build(), timeout);
    }

    @Override public Mono<Void> initialize() {
        return Mono.defer(() -> closed.get() ? Mono.error(new IllegalStateException("MCP客户端已关闭"))
            : super.initialize());
    }

    @Override public Mono<List<McpSchema.Tool>> listTools() {
        return Mono.defer(() -> closed.get() ? Mono.error(new IllegalStateException("MCP客户端已关闭"))
            : super.listTools());
    }

    @Override public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
        return callTool(name, arguments, null);
    }

    @Override public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments,
                                                           Map<String, Object> metadata) {
        return Mono.defer(() -> closed.get() ? Mono.error(new IllegalStateException("MCP客户端已关闭"))
            : super.callTool(name, arguments, metadata));
    }

    /** close 调度清理后立即返回，不把 graceful 等待压在取消线程或运行终态上。 */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        CLOSING.add(this);
        initialized = false;
        cachedTools.clear();
        rawToolSchemas.clear();
        Mono.defer(client::closeGracefully).subscribeOn(Schedulers.boundedElastic())
            .timeout(closeTimeout)
            .subscribe(ignored -> { }, failure -> {
                try { client.close(); }
                catch (RuntimeException forcedFailure) { failure.addSuppressed(forcedFailure); }
                log.warn("mcp_client operation=CLOSE status=FAILED client={} errorType={}",
                    getName(), failure.getClass().getName());
                closeResult.tryEmitError(failure);
                CLOSING.remove(this);
            }, () -> {
                log.info("mcp_client operation=CLOSE status=COMPLETED client={}", getName());
                closeResult.tryEmitEmpty();
                CLOSING.remove(this);
            });
    }

    /** 容器关闭统一等待框架与IPD已经发起的清理，正常执行不等待。 */
    public static void awaitPendingClose(Duration timeout) {
        try {
            Mono.whenDelayError(List.copyOf(CLOSING).stream().map(ManagedMcpAsyncClient::closeCompletion)
                .toList()).block(timeout);
        } catch (RuntimeException failure) {
            log.warn("mcp_client operation=SHUTDOWN status=FAILED errorType={}", failure.getClass().getName());
        }
    }

    /** 健康/生命周期验证可等待真实关闭结果；失败或超时均不是成功。 */
    Mono<Void> closeCompletion() { return closeResult.asMono(); }
}
