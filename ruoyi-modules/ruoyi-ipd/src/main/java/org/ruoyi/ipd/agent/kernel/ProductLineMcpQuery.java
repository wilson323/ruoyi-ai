package org.ruoyi.ipd.agent.kernel;

import io.modelcontextprotocol.spec.McpSchema;
import org.ruoyi.ipd.agent.catalog.ProductLineMcpCatalog;
import org.ruoyi.ipd.agent.catalog.ProductLineMcpCatalog.Endpoint;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按本次选定的服务标识决定产线知识库，并解释官方客户端的工具列表与调用结果。
 *
 * <p>不按展示名选择。已选择 MCP 但与库里的服务绑定不一致时明确拒绝。未记下服务标识时，
 * 本次选定的每条都留给模型选择。
 */
public final class ProductLineMcpQuery {

    /** 外部调用失败时的前缀，后面只带安全原因，不带连接地址或凭据。 */
    public static final String CALL_FAILED = "【产线知识库调用失败】";

    /** 命中前缀。后面带产线名、服务标识和出处。 */
    public static final String HIT = "【产线知识库｜";

    /** 服务返回空列表时写入工具结果的原句。不能当成成功出处。 */
    public static final String NO_DATA = "该产线知识库没有返回资料";

    /** 来源事件 reasonCode 白名单。不含异常原文、地址或凭据。 */
    public static final Set<String> REASON_CODES = Set.of(
        "TIMEOUT", "CANCELLED", "PROTOCOL_OR_TRANSPORT", "REMOTE_IS_ERROR",
        "NO_TOOLS", "AMBIGUOUS_TOOLS", "UNSUPPORTED_SCHEMA", "EMPTY_RESULT",
        "INVALID_QUERY", "INVALID_ARGUMENTS", "SCHEMA_CHANGED", "NO_ENDPOINT", "NO_CLIENT", "TOOLS_CAPABILITY_MISSING");

    /** 仅限本机2.0.3/0.17.2/3.7.13及共享工厂源码已核的精确类，不接受远端或动态类名。 */
    private static final Set<String> SDK_CLASSES = Set.of(
        "io.agentscope.core.tool.mcp.McpAsyncClientWrapper",
        "io.modelcontextprotocol.client.McpAsyncClient",
        "io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport",
        "io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport$Builder",
        "io.modelcontextprotocol.json.McpJsonMapper",
        "io.modelcontextprotocol.json.McpJsonInternal",
        "reactor.core.publisher.BlockingSingleSubscriber",
        "reactor.core.publisher.Mono",
        "org.ruoyi.mcp.service.core.ManagedMcpAsyncClient");

    private static final Pattern DIAGNOSTIC = Pattern.compile(
        "（阶段=(LOCAL|INITIALIZE|LIST_TOOLS|CALL_TOOL)；原因=([A-Z_]+)(?:；异常类型=([A-Za-z0-9_$>]+))?）$");

    /** 安全诊断。stage 为 LOCAL、INITIALIZE、LIST_TOOLS 或 CALL_TOOL；errorTypes 可空。 */
    public record Diagnostic(String stage, String reasonCode, String errorTypes) {
    }

    /** SDK帧仅进来源事件，不拼入模型正文；保留原字符串接口兼容性。 */
    public record Outcome(String text, Diagnostic diagnostic, List<String> sdkFrames) {
        public Outcome { sdkFrames = List.copyOf(sdkFrames); }
    }

    private static Outcome outcome(String text) {
        return new Outcome(text, readDiagnostic(text), List.of());
    }

    /**
     * 一次已打开的官方客户端。生产实现在调用时创建，测试用替身。
     */
    public interface LineSession extends AutoCloseable {

        /**
         * 列出该服务当前的协议工具。
         *
         * @return 工具列表；没有时为空
         * @throws Exception 连接或协议失败
         */
        List<McpSchema.Tool> listTools() throws Exception;

        /** 测试替身可使用 typed schema；生产会话必须返回同响应捕获的完整 schema。 */
        default Map<String, Object> rawInputSchema(String name) throws Exception {
            var matches = listTools().stream().filter(tool -> name.equals(tool.name())).toList();
            if (matches.size() != 1 || matches.get(0).inputSchema() == null) return null;
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                .convertValue(matches.get(0).inputSchema(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        }

        default McpSchema.ServerCapabilities serverCapabilities() { return null; }

        /**
         * 按协议工具名和其声明的参数调用一次。
         *
         * @param name 协议工具名
         * @param arguments 参数
         * @return 调用结果
         * @throws Exception 连接或协议失败
         */
        McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) throws Exception;

        /** 关闭客户端。 */
        @Override
        void close();
    }

    /**
     * 从本次选定的工具编号里取出产线服务。
     *
     * @param storedServiceId product_lines.mcp_service_id，可空
     * @param toolIds 本次运行选定的工具编号
     * @return 要登记的端点；没选产线服务，或库里的标识不在本次选定里时为空
     */
    public List<Endpoint> select(String storedServiceId, List<String> toolIds) {
        List<String> asked = new ArrayList<>();
        if (toolIds != null) {
            for (String toolId : toolIds) {
                if (ProductLineMcpCatalog.isServiceId(toolId) && !asked.contains(toolId)) {
                    asked.add(toolId);
                }
            }
        }
        if (asked.isEmpty()) {
            return List.of();
        }
        String stored = storedServiceId == null ? "" : storedServiceId.trim();
        if (!stored.isEmpty()) {
            Endpoint hit = ProductLineMcpCatalog.byServiceId(stored);
            if (hit == null) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "项目绑定的知识库服务不可用，请检查产品线配置");
            }
            if (!asked.contains(hit.serviceId())) {
                throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "本次选择未包含项目绑定的知识库，请调整工具选择");
            }
            return List.of(hit);
        }
        List<Endpoint> selected = new ArrayList<>();
        for (String serviceId : asked) {
            Endpoint endpoint = ProductLineMcpCatalog.byServiceId(serviceId);
            if (endpoint != null) {
                selected.add(endpoint);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 列出工具后调用。恰好一个工具且只有一个必填字符串参数时使用该参数；多个工具不猜测。
     *
     * @param endpoint 已选定端点
     * @param question 本次问题
     * @param session 已打开的客户端
     * @return 给运行来源事件的正文
     */
    public String invoke(Endpoint endpoint, String question, LineSession session) {
        return invokeOutcome(endpoint, question, session).text();
    }

    /** 生产调用的结构化结果；不通过远端正文携带SDK诊断。 */
    public Outcome invokeOutcome(Endpoint endpoint, String question, LineSession session) {
        return invokeOutcome(endpoint, question, null, session);
    }

    /** 仍只消费已授权的单工具知识 query；摘要由同服务发现结果绑定，漂移不调用。 */
    public Outcome invokeOutcome(Endpoint endpoint, String question, String expectedSchemaDigest, LineSession session) {
        if (endpoint == null) {
            return outcome(codedFailure(null, "LOCAL", "NO_ENDPOINT", "没有端点"));
        }
        if (session == null) {
            return outcome(codedFailure(endpoint, "LOCAL", "NO_CLIENT", "没有客户端"));
        }
        String stage = "LIST_TOOLS";
        try {
            List<McpSchema.Tool> tools = session.listTools();
            if (tools == null || tools.isEmpty() || tools.get(0) == null) {
                return outcome(codedFailure(endpoint, "LIST_TOOLS", "NO_TOOLS", "没有可调用的工具"));
            }
            if (tools.size() > 1) {
                return outcome(codedFailure(endpoint, "LIST_TOOLS", "AMBIGUOUS_TOOLS", "列出多个工具，不猜测"));
            }
            McpSchema.Tool tool = tools.get(0);
            String argument = requiredArgument(tool);
            if (tool.name() == null || tool.name().isBlank() || argument == null) {
                return outcome(codedFailure(endpoint, "LIST_TOOLS", "UNSUPPORTED_SCHEMA", "没有唯一必填字符串参数"));
            }
            Map<String, Object> schema = session.rawInputSchema(tool.name());
            final com.networknt.schema.Schema validator;
            final String digest;
            try {
                validator = schemaValidator(schema);
                digest = schemaDigest(tool.name(), schema);
            } catch (RuntimeException invalid) {
                return outcome(codedFailure(endpoint, "LIST_TOOLS", "UNSUPPORTED_SCHEMA", "完整参数声明无法安全校验"));
            }
            if (expectedSchemaDigest != null && !expectedSchemaDigest.equals(digest))
                return outcome(codedFailure(endpoint, "LIST_TOOLS", "SCHEMA_CHANGED", "协议工具参数声明已改变，请重新读取工具目录"));
            Map<String, Object> arguments = Map.of(argument, question == null ? "" : question);
            try {
                if (!validator.validate(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(arguments)).isEmpty())
                    return outcome(codedFailure(endpoint, "LOCAL", "INVALID_ARGUMENTS", "查询不符合协议工具的完整参数要求"));
            } catch (RuntimeException invalid) {
                return outcome(codedFailure(endpoint, "LIST_TOOLS", "UNSUPPORTED_SCHEMA", "完整参数声明无法安全校验"));
            }
            stage = "CALL_TOOL";
            McpSchema.CallToolResult result = session.callTool(tool.name(), arguments);
            return outcome(presentResult(endpoint, result));
        } catch (Exception ex) {
            return protocolFailureOutcome(endpoint, stage, ex);
        }
    }

    /** 完整 schema 只作诊断，列出多个工具不意味着拥有这些工具的业务执行权。 */
    public String discover(Endpoint endpoint, LineSession session) throws Exception {
        var tools = session.listTools();
        var declared = new ArrayList<Map<String, Object>>();
        for (var tool : tools) {
            var schema = session.rawInputSchema(tool.name());
            schemaValidator(schema);
            declared.add(Map.of("toolName", tool.name(), "inputSchema", schema,
                "schemaDigest", schemaDigest(tool.name(), schema)));
        }
        var caps = session.serverCapabilities();
        return "【产线知识库协议目录；仅诊断，不是知识命中或操作授权】\n"
            + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("tools", declared,
                "capabilities", Map.of("tools", caps != null && caps.tools() != null,
                    "resources", caps != null && caps.resources() != null, "prompts", caps != null && caps.prompts() != null)));
    }

    static String schemaDigest(String toolName, Map<String, Object> schema) {
        try {
            byte[] canonical = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(Map.of("toolName", toolName, "inputSchema", schema));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception invalid) { throw new IllegalArgumentException("schema digest unavailable", invalid); }
    }

    private static com.networknt.schema.Schema schemaValidator(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) throw new IllegalArgumentException("missing complete schema");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.valueToTree(schema);
        if (node.toString().length() > 262144) throw new IllegalArgumentException("schema exceeds limit");
        requireOfflineSchema(node, 0);
        var version = node.has("$schema") ? com.networknt.schema.SpecificationVersion.fromDialectId(node.path("$schema").asText())
            .orElseThrow(() -> new IllegalArgumentException("unsupported schema dialect"))
            : com.networknt.schema.SpecificationVersion.DRAFT_2020_12;
        var registry = com.networknt.schema.SchemaRegistry.withDefaultDialect(version,
            builder -> builder.resourceLoaders(loaders -> loaders.values(java.util.List::clear)));
        var compiled = registry.getSchema(node);
        compiled.initializeValidators();
        return compiled;
    }

    /** 外部引用与标识不能触发网络解析；仅当前完整 schema 内的引用可用。 */
    private static void requireOfflineSchema(com.fasterxml.jackson.databind.JsonNode node, int depth) {
        if (depth > 64) throw new IllegalArgumentException("schema depth exceeds limit");
        if (node.isObject()) {
            for (String key : List.of("$ref", "$dynamicRef", "$recursiveRef")) {
                if (node.has(key) && (!node.get(key).isTextual() || !node.get(key).asText().startsWith("#")))
                    throw new IllegalArgumentException("external schema reference is not authorized");
            }
            if (node.has("$id") && !node.get("$id").asText().startsWith("#"))
                throw new IllegalArgumentException("external schema identifier is not authorized");
        }
        if (node.isContainerNode()) node.forEach(child -> requireOfflineSchema(child, depth + 1));
    }

    /**
     * 外层原因优先：取消、然后超时、然后一般协议失败。原文、地址和凭据不进入返回值。
     *
     * @param endpoint 端点，可空
     * @param stage INITIALIZE、LIST_TOOLS 或 CALL_TOOL
     * @param failure 异常链
     * @return 安全失败正文
     */
    public String protocolFailure(Endpoint endpoint, String stage, Throwable failure) {
        return protocolFailureOutcome(endpoint, stage, failure).text();
    }

    /** 有限因果链和精确SDK帧白名单，不保留message、参数或地址。 */
    public Outcome protocolFailureOutcome(Endpoint endpoint, String stage, Throwable failure) {
        List<String> types = new ArrayList<>();
        List<String> frames = new ArrayList<>();
        String reason = "PROTOCOL_OR_TRANSPORT";
        boolean interrupt = false;
        boolean toolsCapabilityMissing = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.size() < 8 && seen.add(cause); cause = cause.getCause()) {
            String simple = cause.getClass().getSimpleName();
            if (simple != null && simple.matches("[A-Za-z0-9_$]{1,100}")) {
                types.add(simple);
            }
            if ("PROTOCOL_OR_TRANSPORT".equals(reason)) {
                if (cancelled(cause)) {
                    reason = "CANCELLED";
                } else if (timedOut(cause)) {
                    reason = "TIMEOUT";
                }
            }
            StackTraceElement[] stack = cause.getStackTrace();
            boolean toolListFrame = false;
            for (int index = 0; index < Math.min(stack.length, 64); index++) {
                StackTraceElement frame = stack[index];
                toolListFrame |= "io.modelcontextprotocol.client.McpAsyncClient".equals(frame.getClassName())
                    && "listToolsInternal".equals(frame.getMethodName());
                if (SDK_CLASSES.contains(frame.getClassName())
                    && frame.getMethodName().matches("[A-Za-z0-9_$]{1,100}")
                    && frame.getLineNumber() >= -1) {
                    String safe = frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
                    if (frames.size() < 8 && !frames.contains(safe)) frames.add(safe);
                }
            }
            toolsCapabilityMissing |= cause instanceof IllegalStateException && toolListFrame
                && "Server does not provide tools capability".equals(cause.getMessage());
            interrupt |= cause instanceof InterruptedException;
        }
        if (interrupt) {
            Thread.currentThread().interrupt();
        }
        if (toolsCapabilityMissing && "PROTOCOL_OR_TRANSPORT".equals(reason)) reason = "TOOLS_CAPABILITY_MISSING";
        String safeStage = stage == null || stage.isBlank() ? "LOCAL" : stage;
        String text = codedFailure(endpoint, safeStage, reason, "连接或协议调用失败，请稍后重试");
        if (!types.isEmpty() && text.endsWith("）")) {
            text = text.substring(0, text.length() - 1) + "；异常类型=" + String.join(">", types) + "）";
        }
        return new Outcome(text, readDiagnostic(text), frames);
    }

    private static boolean cancelled(Throwable cause) {
        String simple = cause.getClass().getSimpleName();
        return cause instanceof CancellationException
            || cause instanceof InterruptedException
            || (simple != null && simple.contains("Cancel"));
    }

    /** 只在本地识别阻塞读超时，不把 message 写回调用方。 */
    private static boolean timedOut(Throwable cause) {
        if (cause instanceof TimeoutException || cause.getClass().getSimpleName().contains("Timeout")) {
            return true;
        }
        String message = cause instanceof IllegalStateException ? cause.getMessage() : null;
        return message != null && message.contains("Timeout on blocking read");
    }

    /**
     * 从失败正文读回安全诊断。原因码不在白名单时返回 null。
     *
     * @param text 工具或来源正文
     * @return 诊断；没有安全标记时为 null
     */
    public static Diagnostic readDiagnostic(String text) {
        if (text == null || !text.startsWith(CALL_FAILED)) {
            return null;
        }
        Matcher matcher = DIAGNOSTIC.matcher(text);
        if (!matcher.find() || !REASON_CODES.contains(matcher.group(2))) {
            return null;
        }
        return new Diagnostic(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    /**
     * 失败正文加上阶段和原因码。detail 必须已是安全中文。
     *
     * @param endpoint 端点，可空
     * @param stage 阶段
     * @param reasonCode 白名单原因码
     * @param detail 给模型看的安全说明
     * @return 失败正文
     */
    public String codedFailure(Endpoint endpoint, String stage, String reasonCode, String detail) {
        return failure(endpoint, detail) + "（阶段=" + stage + "；原因=" + reasonCode + "）";
    }

    /**
     * 把成功正文整理成出处或空资料。失败正文不从这里走。
     *
     * @param endpoint 端点
     * @param body 工具正文
     * @return 来源正文
     */
    public String present(Endpoint endpoint, String body) {
        String text = body == null ? "" : body.trim();
        if (endpoint != null && endpoint.url() != null && !endpoint.url().isBlank()) {
            text = text.replace(endpoint.url(), "[连接地址已隐藏]");
        }
        if (text.isEmpty()) {
            return codedFailure(endpoint, "CALL_TOOL", "EMPTY_RESULT", "外部返回空正文");
        }
        List<String> sources = sourceNames(text);
        if (!sources.isEmpty()) {
            return HIT + "产线：" + endpoint.lineName()
                + "｜服务标识：" + endpoint.serviceId()
                + "｜远端声明出处：" + String.join("、", sources) + "】\n远端返回资料：\n" + text;
        }
        if (isEmptyDataset(text)) {
            return "产线：" + endpoint.lineName()
                + "。服务标识：" + endpoint.serviceId()
                + "。" + NO_DATA + "。";
        }
        return HIT + "产线：" + endpoint.lineName()
            + "｜服务标识：" + endpoint.serviceId()
            + "｜资料类型：远端应用回答；未提供原始出处】\n" + text;
    }

    /**
     * 失败正文。保留原因，不返回空串。
     *
     * @param endpoint 端点
     * @param detail 原因原文
     * @return 失败正文
     */
    public String failure(Endpoint endpoint, String detail) {
        String line = endpoint == null || endpoint.lineName() == null ? "" : endpoint.lineName();
        String serviceId = endpoint == null || endpoint.serviceId() == null ? "" : endpoint.serviceId();
        return CALL_FAILED + "产线：" + line + "。服务标识：" + serviceId + "。" + (detail == null ? "" : detail);
    }

    /**
     * 仅接受唯一必填字符串参数。参数不受支持时不得猜测或发出无效调用。
     *
     * @param tool 协议工具
     * @return 参数名；没有时为 null
     */
    static String requiredArgument(McpSchema.Tool tool) {
        if (tool == null || tool.inputSchema() == null || tool.inputSchema().required() == null
            || tool.inputSchema().required().size() != 1) {
            return null;
        }
        String name = tool.inputSchema().required().get(0);
        if (name == null || name.isBlank()) {
            return null;
        }
        Object property = tool.inputSchema().properties() == null ? null : tool.inputSchema().properties().get(name);
        if (!(property instanceof Map<?, ?> fields) || !"string".equals(fields.get("type"))) {
            return null;
        }
        return name;
    }

    private String presentResult(Endpoint endpoint, McpSchema.CallToolResult result) {
        if (result == null) {
            return codedFailure(endpoint, "CALL_TOOL", "EMPTY_RESULT", "外部返回空正文");
        }
        String body = textOf(result);
        if (Boolean.TRUE.equals(result.isError())) {
            return codedFailure(endpoint, "CALL_TOOL", "REMOTE_IS_ERROR", "远端工具返回错误，请稍后重试");
        }
        return present(endpoint, body);
    }

    private static String textOf(McpSchema.CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent written) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(written.text() == null ? "" : written.text());
            }
        }
        return text.toString();
    }

    /**
     * 从服务正文里取出资料名。没有 sourceName 时返回空列表。
     *
     * @param body 外部正文
     * @return 去重后的资料名，最多 5 个
     */
    static List<String> sourceNames(String body) {
        List<String> names = new ArrayList<>();
        if (body == null) {
            return names;
        }
        int from = 0;
        String text = normalize(body);
        while (names.size() < 5) {
            int at = text.indexOf("\"sourceName\"", from);
            if (at < 0) {
                break;
            }
            int colon = text.indexOf(':', at);
            int open = colon < 0 ? -1 : text.indexOf('"', colon + 1);
            int close = open < 0 ? -1 : text.indexOf('"', open + 1);
            if (close < 0) {
                break;
            }
            String name = text.substring(open + 1, close).trim();
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
            from = close + 1;
        }
        return names;
    }

    /**
     * 服务明确返回了空 dataset，且没有任何资料名。
     *
     * @param body 外部正文
     * @return 空列表时为 true
     */
    static boolean isEmptyDataset(String body) {
        if (body == null) {
            return false;
        }
        String compact = normalize(body).replace(" ", "");
        return compact.contains("\"dataset\":[]") && sourceNames(body).isEmpty();
    }

    private static String normalize(String body) {
        if (body == null) {
            return "";
        }
        return body.replace("\\\"", "\"");
    }
}
