package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.ruoyi.mcp.service.core.ManagedMcpAsyncClient;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.ruoyi.chat.kernel.tool.KernelGovernedTool;
import org.ruoyi.chat.kernel.tool.KernelToolGovernance;
import org.ruoyi.ipd.agent.catalog.ProductLineMcpCatalog.Endpoint;
import org.ruoyi.ipd.agent.kernel.ProductLineMcpQuery.LineSession;
import org.ruoyi.ipd.mapper.ProductLineNameMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把本次选定的产线服务标识登记进同一次项目运行的工具集。
 *
 * <p>对外名称是服务标识。调用时才用共享有限生命周期工厂打开 2.0.3 官方客户端，
 * 再 {@code initialize}、{@code listTools}。恰好一个协议工具时，按它声明的必填参数
 * {@code callTool}。不调用 {@code registration().apply()}，也不使用 {@code buildSync}。
 */
public final class ProductLineMcpTool implements AgentTool {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final Endpoint endpoint;
    private final ProjectAgentEventSink sink;
    private final ProductLineMcpQuery query = new ProductLineMcpQuery();
    private final SessionOpener sessions;

    private ProductLineMcpTool(Endpoint endpoint, ProjectAgentEventSink sink) {
        this(endpoint, sink, null);
    }

    private ProductLineMcpTool(Endpoint endpoint, ProjectAgentEventSink sink, SessionOpener sessions) {
        this.endpoint = endpoint;
        this.sink = sink;
        this.sessions = sessions;
    }

    /**
     * 测试替身。生产装配不走这里，调用时仍打开共享生命周期的官方客户端。
     *
     * @param endpoint 已选定端点
     * @param sink 事件出口
     * @param session 不访问网络的客户端
     * @return 只用于测试的工具
     */
    static ProductLineMcpTool openWith(Endpoint endpoint, ProjectAgentEventSink sink, LineSession session) {
        return openWith(endpoint, sink, (SessionOpener) () -> session);
    }

    /**
     * 测试握手失败。打开动作抛出的异常记为 INITIALIZE，不访问协议工具。
     *
     * @param endpoint 已选定端点
     * @param sink 事件出口
     * @param opener 会失败的打开动作
     * @return 只用于测试的工具
     */
    static ProductLineMcpTool openWith(Endpoint endpoint, ProjectAgentEventSink sink, SessionOpener opener) {
        return new ProductLineMcpTool(endpoint, sink, opener);
    }

    /**
     * 按库里的服务标识和本次选定登记产线工具。不按展示名过滤，装配时不连外部地址。
     *
     * @param toolkit 本次运行的工具集
     * @param governance 既有工具裁决
     * @param spec 运行输入
     * @param names 服务标识查询，可为 null
     * @param sink 事件出口
     */
    public static void bind(Toolkit toolkit, KernelToolGovernance governance, ProjectAgentRunSpec spec,
                            ProductLineNameMapper names, ProjectAgentEventSink sink) {
        String storedServiceId = null;
        if (names != null && spec.projectId() != null) {
            storedServiceId = names.selectServiceId(spec.projectId());
        }
        for (Endpoint chosen : new ProductLineMcpQuery().select(storedServiceId, spec.toolIds())) {
            toolkit.registerAgentTool(KernelGovernedTool.wrap(
                new ProductLineMcpTool(chosen, sink), governance));
        }
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return endpoint.serviceId();
    }

    /** {@inheritDoc} */
    @Override
    public String getDescription() {
        return "只读查询产线「" + endpoint.lineName() + "」的知识库。服务标识 "
            + endpoint.serviceId() + "。";
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("query", Map.of(
            "type", "string", "description", "向该产线知识库提出的问题")));
        schema.put("required", List.of("query"));
        schema.put("additionalProperties", Boolean.FALSE);
        return schema;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 调用时才连接。模型传入的问题会交给协议工具声明的必填参数，不把参数名写成 query。
     * 没有命中时返回错误结果，空资料不算成功。
     *
     * @param param 本次工具调用
     * @return 命中正文或错误结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null || param.getInput() == null
            ? Map.of() : param.getInput();
        Object raw = input.get("query");
        String question = raw instanceof String written ? written.trim() : "";
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        String toolCallId = use == null ? null : use.getId();
        if (question.isEmpty() || question.length() > 8000) {
            return Mono.just(finish("", toolCallId, query.codedFailure(
                endpoint, "LOCAL", "INVALID_QUERY", "查询须为1至8000字的文本")));
        }
        return Mono.fromCallable(() -> invokeOnce(question, toolCallId))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock invokeOnce(String question, String toolCallId) {
        ProductLineMcpQuery.Outcome outcome;
        if (sink != null) {
            sink.requireActiveOwnership();
        }
        try (LineSession session = sessions == null ? open(endpoint) : sessions.open()) {
            outcome = query.invokeOutcome(endpoint, question, session);
        } catch (Exception ex) {
            outcome = query.protocolFailureOutcome(endpoint, "INITIALIZE", ex);
        }
        return finish(question, toolCallId, outcome);
    }

    private ToolResultBlock finish(String question, String toolCallId, String text) {
        return finish(question, toolCallId, new ProductLineMcpQuery.Outcome(
            text, ProductLineMcpQuery.readDiagnostic(text), List.of()));
    }

    private ToolResultBlock finish(String question, String toolCallId, ProductLineMcpQuery.Outcome outcome) {
        String text = outcome.text();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("toolCallId", toolCallId);
        source.put("tool", endpoint.serviceId());
        source.put("lineName", endpoint.lineName());
        source.put("serviceId", endpoint.serviceId());
        source.put("query", question);
        boolean hit = text != null && text.startsWith(ProductLineMcpQuery.HIT);
        boolean failed = text != null && text.startsWith(ProductLineMcpQuery.CALL_FAILED);
        boolean noHit = !hit && !failed && text != null && text.contains(ProductLineMcpQuery.NO_DATA);
        String retrievalStatus = hit ? "SUCCESS" : (noHit ? "NO_HIT" : "FAILED");
        source.put("retrievalStatus", retrievalStatus);
        if (hit) {
            source.put("hits", 1);
        } else if (noHit) {
            source.put("hits", 0);
        }
        if ("FAILED".equals(retrievalStatus)) {
            ProductLineMcpQuery.Diagnostic diagnostic = outcome.diagnostic();
            if (diagnostic != null) {
                source.put("reasonCode", diagnostic.reasonCode());
                source.put("mcpFailureStage", diagnostic.stage());
                source.put("mcpFailureReason", diagnostic.reasonCode());
                if (diagnostic.errorTypes() != null) {
                    source.put("mcpErrorTypes", diagnostic.errorTypes());
                }
                if ("TIMEOUT".equals(diagnostic.reasonCode())) {
                    source.put("timeoutSeconds", TIMEOUT.toSeconds());
                }
                if (!outcome.sdkFrames().isEmpty()) source.put("mcpSdkFrames", outcome.sdkFrames());
            }
        }
        source.put("sourceKind", "REMOTE_APPLICATION");
        source.put("citationText", hit ? text : "");
        source.put("chars", hit ? text.length() : 0);
        source.put("preview", text == null ? "" : (text.length() > 1000 ? text.substring(0, 1000) : text));
        if (sink != null) {
            sink.requireActiveOwnership();
            sink.onSource(source);
        }
        if (!hit && !noHit) {
            return ToolResultBlock.error(text == null ? "" : text);
        }
        return ToolResultBlock.text(text);
    }

    /**
     * 用官方 Streamable HTTP 打开并初始化。地址不改写。
     *
     * @param chosen 已选定端点
     * @return 可列出工具的会话
     */
    private static LineSession open(Endpoint chosen) {
        McpClientWrapper client = ManagedMcpAsyncClient.streamableHttp(
            chosen.serviceId(), chosen.url(), Map.of(), TIMEOUT);
        if (client == null) {
            throw new IllegalStateException("官方客户端没有返回");
        }
        try {
            client.initialize().block(TIMEOUT);
            return new OfficialSession(client);
        } catch (RuntimeException ex) {
            client.close();
            throw ex;
        }
    }

    /**
     * 测试注入的打开方式。
     */
    @FunctionalInterface
    interface SessionOpener {
        /**
         * @return 已打开的会话
         * @throws Exception 打开失败
         */
        LineSession open() throws Exception;
    }

    /**
     * 官方客户端的同步会话。只在 {@code initialize} 完成后列出和调用。
     */
    private static final class OfficialSession implements LineSession {

        private final McpClientWrapper client;

        private OfficialSession(McpClientWrapper client) {
            this.client = client;
        }

        /** {@inheritDoc} */
        @Override
        public List<McpSchema.Tool> listTools() {
            List<McpSchema.Tool> tools = client.listTools().block(TIMEOUT);
            return tools == null ? List.of() : tools;
        }

        /** {@inheritDoc} */
        @Override
        public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
            return client.callTool(name, arguments).block(TIMEOUT);
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            client.close();
        }
    }
}
