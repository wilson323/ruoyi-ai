package org.ruoyi.mcp.service.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.net.URI;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.process.ChildProcessSecretSanitizer;
import org.ruoyi.domain.entity.mcp.McpTool;
import org.ruoyi.enums.McpToolStatus;
import org.ruoyi.mapper.mcp.McpToolMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 原生 AgentScope 工具装配；市场编号不参与 IPD 能力包编号转换。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentScopeMcpToolProviderService {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private final McpToolMapper mcpToolMapper;
    private final ObjectMapper objectMapper;
    private final BuiltinToolRegistry builtinToolRegistry;
    private final Set<ToolSession> sessions = ConcurrentHashMap.newKeySet();
    private final Map<Long, Boolean> health = new ConcurrentHashMap<>();

    /** 只注册明确选定且仍启用的市场工具。调用方须在运行结束/取消后 close。 */
    public ToolSession createSession(List<Long> toolIds) {
        List<Long> ids = toolIds == null ? List.of() : toolIds.stream()
            .filter(Objects::nonNull).distinct().toList();
        List<McpTool> tools = ids.isEmpty() ? List.of() : mcpToolMapper.selectList(
            new LambdaQueryWrapper<McpTool>().in(McpTool::getId, ids)
                .eq(McpTool::getStatus, McpToolStatus.ENABLED.getValue()));
        Map<Long, McpTool> selected = new LinkedHashMap<>();
        tools.forEach(tool -> selected.put(tool.getId(), tool));
        ToolSession session = new ToolSession();
        sessions.add(session);
        try {
            for (Long id : ids) {
                McpTool tool = selected.get(id);
                if (tool == null) {
                    throw new IllegalStateException("选定的工具不存在或未启用");
                }
                register(session, tool);
            }
            return session;
        } catch (RuntimeException error) {
            session.close();
            throw new IllegalStateException("工具装配失败", error);
        }
    }

    public ToolSession createAllEnabledSession() {
        return createSession(mcpToolMapper.selectList(new LambdaQueryWrapper<McpTool>()
            .eq(McpTool::getStatus, McpToolStatus.ENABLED.getValue())).stream()
            .map(McpTool::getId).toList());
    }

    private void register(ToolSession session, McpTool tool) {
        synchronized (session) {
            if (session.closed) {
                throw new IllegalStateException("工具会话已关闭");
            }
            registerOwned(session, tool);
        }
    }

    private void registerOwned(ToolSession session, McpTool tool) {
        if (BuiltinToolRegistry.TYPE_BUILTIN.equals(tool.getType())) {
            Object instance = builtinToolRegistry.getBuiltinToolObject(tool.getName());
            if (instance == null) {
                throw new IllegalStateException("内置工具未注册");
            }
            Toolkit discovered = new Toolkit();
            discovered.registerTool(instance);
            if (discovered.getToolNames().stream().anyMatch(name -> !discovered.getTool(name).isReadOnly())) {
                throw new IllegalStateException("聊天内置工具仅开放只读操作");
            }
            rejectDuplicateNames(session.toolkit, discovered.getToolNames());
            discovered.getToolNames().forEach(name -> session.toolkit.registerAgentTool(discovered.getTool(name)));
            return;
        }
        McpClientWrapper client = openClient(tool);
        session.addClient(tool.getId(), client);
        try {
            client.initialize().block(TIMEOUT);
            List<McpSchema.Tool> discovered = client.listTools().block(TIMEOUT);
            if (discovered == null || discovered.isEmpty()) {
                throw new IllegalStateException("MCP没有可调用工具");
            }
            rejectDuplicateNames(session.toolkit, discovered.stream().map(McpSchema.Tool::name).toList());
            session.toolkit.registerMcpClient(client).block(TIMEOUT);
            health.put(tool.getId(), true);
        } catch (RuntimeException failure) {
            health.put(tool.getId(), false);
            throw failure;
        }
    }

    static void rejectDuplicateNames(Toolkit toolkit, java.util.Collection<String> names) {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        for (String name : names) {
            if (name == null || name.isBlank() || !seen.add(name) || toolkit.getToolNames().contains(name)) {
                throw new IllegalStateException("MCP工具名称重复或无效，无法安全装配");
            }
        }
    }

    /** 配置地址和凭据只交给协议客户端，日志不打印。 */
    McpClientWrapper openClient(McpTool tool) {
        try {
            JsonNode config = objectMapper.readTree(tool.getConfigJson());
            McpClientTransport transport;
            if ("LOCAL".equals(tool.getType())) {
                String command = config.path("command").asText("").trim();
                if (command.isEmpty()) {
                    throw new IllegalArgumentException("LOCAL工具缺少command");
                }
                List<String> args = new ArrayList<>();
                config.path("args").forEach(arg -> args.add(arg.asText()));
                Map<String, String> environment = new LinkedHashMap<>();
                config.path("env").properties().forEach(e -> environment.put(e.getKey(), e.getValue().asText()));
                environment.keySet().removeIf(ChildProcessSecretSanitizer.DEEPSEEK_API_KEY::equalsIgnoreCase);
                environment.putAll(ChildProcessSecretSanitizer.emptyProviderSecretOverride());
                transport = new StdioClientTransport(ServerParameters.builder(resolveCommand(command))
                    .args(args).env(environment).build(), McpJsonMapper.getDefault());
            } else if ("REMOTE".equals(tool.getType())) {
                String url = config.path("baseUrl").asText("").trim();
                if (url.isEmpty()) {
                    throw new IllegalArgumentException("REMOTE工具缺少baseUrl");
                }
                URI uri = URI.create(url);
                String endpoint = uri.getRawPath();
                if (endpoint == null || endpoint.isEmpty()) { endpoint = "/"; }
                if (uri.getRawQuery() != null) { endpoint += "?" + uri.getRawQuery(); }
                String origin = new URI(uri.getScheme(), uri.getRawAuthority(), null, null, null).toString();
                Map<String, String> headers = new LinkedHashMap<>();
                config.path("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));
                if ("SSE".equalsIgnoreCase(config.path("transport").asText())) {
                    transport = HttpClientSseClientTransport.builder(origin).sseEndpoint(endpoint)
                        .customizeRequest(request -> headers.forEach(request::header)).build();
                } else {
                    transport = HttpClientStreamableHttpTransport.builder(origin).endpoint(endpoint)
                        .customizeRequest(request -> headers.forEach(request::header)).build();
                }
            } else {
                throw new IllegalArgumentException("工具类型不支持");
            }
            return ManagedMcpAsyncClient.create("market-mcp-" + tool.getId(), transport, TIMEOUT);
        } catch (Exception failure) {
            throw new IllegalStateException("MCP客户端创建失败");
        }
    }

    static String resolveCommand(String command) {
        if (System.getProperty("os.name").toLowerCase().contains("win")
            && Set.of("npx", "npm", "node", "pnpm", "yarn", "uvx", "uv").contains(command.toLowerCase())) {
            return command + ".cmd";
        }
        return command;
    }

    /** 健康检查使用短生命周期客户端，实际初始化与发现后关闭。 */
    public boolean checkToolHealth(Long toolId) {
        McpTool tool = mcpToolMapper.selectById(toolId);
        if (tool == null || !McpToolStatus.isEnabled(tool.getStatus())) {
            return false;
        }
        if (BuiltinToolRegistry.TYPE_BUILTIN.equals(tool.getType())) {
            return builtinToolRegistry.hasTool(tool.getName());
        }
        McpClientWrapper client = null;
        boolean healthy = false;
        try {
            client = openClient(tool);
            client.initialize().block(TIMEOUT);
            List<McpSchema.Tool> tools = client.listTools().block(TIMEOUT);
            healthy = tools != null && !tools.isEmpty();
        } catch (RuntimeException failure) {
            log.warn("mcp_client operation=HEALTH_CHECK status=FAILED errorType={}", failure.getClass().getName());
        } finally {
            if (client != null) {
                closeClient(client);
            }
            health.put(toolId, healthy);
        }
        return healthy;
    }

    public Map<Long, Boolean> getAllToolsHealthStatus() {
        Map<Long, Boolean> statuses = new LinkedHashMap<>();
        for (McpTool tool : mcpToolMapper.selectList(new LambdaQueryWrapper<McpTool>()
                .eq(McpTool::getStatus, McpToolStatus.ENABLED.getValue()))) {
            statuses.put(tool.getId(), BuiltinToolRegistry.TYPE_BUILTIN.equals(tool.getType())
                ? builtinToolRegistry.hasTool(tool.getName()) : health.getOrDefault(tool.getId(), false));
        }
        return statuses;
    }

    public void refreshClient(Long toolId) {
        health.remove(toolId);
        sessions.forEach(session -> session.invalidate(toolId));
    }

    public int getActiveClientCount() {
        return sessions.stream().mapToInt(session -> session.clients.size()).sum();
    }

    @PreDestroy
    public void cleanup() {
        List.copyOf(sessions).forEach(ToolSession::close);
        ManagedMcpAsyncClient.awaitPendingClose(Duration.ofSeconds(2));
    }

    private void closeClient(McpClientWrapper client) {
        if (client instanceof ManagedMcpAsyncClient managed) {
            managed.close();
            return;
        }
        try {
            client.close();
        } catch (RuntimeException failure) {
            log.warn("mcp_client operation=CLOSE status=FAILED errorType={}", failure.getClass().getName());
        }
    }

    /** 每次运行独占的工具集和客户端；不得把会话跨运行缓存。 */
    public final class ToolSession implements AutoCloseable {
        private final Toolkit toolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        private final Map<Long, McpClientWrapper> clients = new ConcurrentHashMap<>();
        private boolean closed;

        public Toolkit toolkit() { return toolkit; }

        private synchronized void addClient(Long id, McpClientWrapper client) {
            if (closed) {
                closeClient(client);
                throw new IllegalStateException("工具会话已关闭");
            }
            clients.put(id, client);
        }

        private synchronized void invalidate(Long id) {
            if (clients.containsKey(id)) {
                // 配置变化撤销整个运行的工具会话，避免模型继续调用旧配置。
                close();
            }
        }

        @Override
        public synchronized void close() {
            if (closed) { return; }
            closed = true;
            List.copyOf(clients.values()).forEach(AgentScopeMcpToolProviderService.this::closeClient);
            clients.clear();
            sessions.remove(this);
        }
    }
}
