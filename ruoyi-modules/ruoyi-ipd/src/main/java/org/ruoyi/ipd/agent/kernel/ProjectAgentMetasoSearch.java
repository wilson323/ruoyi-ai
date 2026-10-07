package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.ruoyi.mcp.service.core.ManagedMcpAsyncClient;
import org.ruoyi.mcp.transport.JsonRpcHttpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把秘塔搜索挂进项目智能体的工具箱。
 *
 * <p>解决的是「智能体做调研、做外部事实核查时手里没有联网工具」这件事。官方自带的
 * {@code web_search} 走 SDK 内置的 Tavily，需要 {@code TAVILY_API_KEY}；本仓两处实测
 * 该变量从未配置，所以调研类任务实际上是在无网状态下硬跑的。秘塔提供另一条可用的路。
 *
 * <p><b>为什么不能用官方 {@code toolsConfig(...).mcpServers(...)}</b>：javap 实测
 * {@code McpServerRegistrar} 只认 stdio / sse / streamable-http 三种传输层，三者都在
 * POST 前先发 GET 建推送通道；秘塔对 GET 返回 405 text/html（curl 与 SDK 双向实测），
 * 因此必须走 {@link JsonRpcHttpClientTransport} 这条本仓自建的 POST-only 通道。
 *
 * <p><b>失败一律不静默</b>：密钥缺失、端点不可达、工具列表为空——三种情况都只记日志
 * 并返回未注册，绝不注册一个「看着在、调用就报错」的空壳工具。调研任务缺工具比
 * 慢一点严重得多。
 */
public final class ProjectAgentMetasoSearch {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgentMetasoSearch.class);

    /** 端点与鉴权方式取自秘塔官方 ModelScope 服务页（metasota/metaso-search）。 */
    private static final String ENDPOINT = "https://metaso.cn/api/mcp";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** 智能体可见的工具名（服务端实际暴露名，见 tools/list 实测）。 */
    public static final String WEB_SEARCH = "metaso_web_search";
    public static final String WEB_READER = "metaso_web_reader";
    public static final String CHAT = "metaso_chat";
    public static final List<String> TOOL_IDS = List.of(WEB_SEARCH, WEB_READER, CHAT);

    /** 密钥只从环境变量读；不入库、不进仓、不进日志。 */
    public static final String API_KEY_ENV = "METASO_MCP_API_KEY";

    private ProjectAgentMetasoSearch() { }

    public static String apiKeyFromEnvironment() {
        return System.getenv(API_KEY_ENV);
    }

    /**
     * 挂载结果：注册成功的工具名 + 持有该 MCP 客户端的句柄。
     *
     * <p>客户端必须由调用方在运行结束时关闭——不关会泄漏并发许可，累积到上限后整个
     * 智能体子系统会被限流。因此它不能藏在方法内部悄悄丢掉。
     */
    public record Bound(List<String> registered, ManagedMcpAsyncClient client) {
        public Bound { registered = List.copyOf(registered); }
        public boolean usable() { return !registered.isEmpty(); }
    }

    /** 未挂载：registered 为空，client 为 null。 */
    private static final Bound NOT_BOUND = new Bound(List.of(), null);

    /**
     * 注册秘塔搜索工具。
     *
     * @return 实际挂载结果；{@code registered} 为空即表示未注册，调用方据此如实呈现可用性
     */
    public static Bound bind(Toolkit toolkit, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("metaso_search operation=BIND status=SKIPPED reason={}_NOT_CONFIGURED", API_KEY_ENV);
            return NOT_BOUND;
        }
        ManagedMcpAsyncClient client = null;
        try {
            client = null;
            var transport = JsonRpcHttpClientTransport.builder(java.net.URI.create(ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .requestTimeout(TIMEOUT)
                    .build();
            client = ManagedMcpAsyncClient.create("project-agent-metaso", transport, TIMEOUT);
            // 必须先 initialize：ManagedMcpAsyncClient 只在装配时构造会话，
            // 未初始化就 listTools 会直接失败（此前实测 registered 为空，根因在此）。
            client.initialize().block(TIMEOUT);
            List<McpSchema.Tool> discovered = client.listTools().block(TIMEOUT);
            if (discovered == null || discovered.isEmpty()) {
                throw new IllegalStateException("秘塔未返回任何可调用工具");
            }
            for (McpSchema.Tool tool : discovered) {
                if (tool.name() == null || !TOOL_IDS.contains(tool.name())) {
                    continue;
                }
                if (toolkit.getToolNames().contains(tool.name())) {
                    throw new IllegalStateException("工具名重复，拒绝装配以免行为不可预期");
                }
            }
            toolkit.registerMcpClient(client).block(TIMEOUT);
            List<String> registered = discovered.stream().map(McpSchema.Tool::name)
                    .filter(TOOL_IDS::contains).toList();
            log.info("metaso_search operation=BIND status=OK registered={}", registered);
            return new Bound(registered, client);
        } catch (RuntimeException failure) {
            // 连接失败必须关掉已开的客户端，否则并发许可泄漏会拖垮整个子系统。
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                    // 关闭失败不掩盖首因；首因已在上方日志里。
                }
            }
            log.warn("metaso_search operation=BIND status=FAILED errorType={}",
                    failure.getClass().getSimpleName());
            return NOT_BOUND;
        }
    }

    /** 供运行内核断言用：本环境是否具备挂载联网搜索的条件。 */
    public static boolean available(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 工具默认放行参数：搜索类工具由调用方提供查询词，不接受 URL，故与 SSRF 面无关。 */
    static Map<String, Object> defaultSearchArguments(String query, int size) {
        return Map.of("q", query, "size", size);
    }
}
