package org.ruoyi.mcp.service.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.ruoyi.domain.entity.mcp.McpTool;
import org.ruoyi.mapper.mcp.McpToolMapper;
import org.ruoyi.mcp.tools.ReadFileTool;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class AgentScopeMcpToolProviderServiceTest {
    private final McpToolMapper mapper = mock(McpToolMapper.class);
    private final BuiltinToolRegistry registry = mock(BuiltinToolRegistry.class);
    private final McpClientWrapper client = mock(McpClientWrapper.class);
    private final AgentScopeMcpToolProviderService service = new AgentScopeMcpToolProviderService(
        mapper, new ObjectMapper(), registry) {
        @Override McpClientWrapper openClient(McpTool tool) { return client; }
    };

    @Test
    void healthProbeClosesClientAfterFailure() {
        when(mapper.selectById(7L)).thenReturn(remote());
        when(client.initialize()).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        assertThat(service.checkToolHealth(7L)).isFalse();
        verify(client).close();
        assertThat(service.getActiveClientCount()).isZero();
    }

    @Test
    void healthProbeRequiresDiscoveredToolsAndClosesClient() {
        when(mapper.selectById(7L)).thenReturn(remote());
        when(client.initialize()).thenReturn(Mono.empty());
        when(client.listTools()).thenReturn(Mono.just(List.of()));
        assertThat(service.checkToolHealth(7L)).isFalse();
        verify(client).close();
    }

    @Test
    void refreshRevokesSessionAndClosesExactlyOnce() {
        when(mapper.selectList(any())).thenReturn(List.of(remote()));
        when(client.getName()).thenReturn("market-mcp-7");
        when(client.initialize()).thenReturn(Mono.empty());
        McpSchema.Tool tool = mock(McpSchema.Tool.class);
        when(tool.name()).thenReturn("lookup");
        when(client.listTools()).thenReturn(Mono.just(List.of(tool)));
        try (var session = service.createSession(List.of(7L))) {
            assertThat(session.toolkit().getToolNames()).contains("lookup");
            service.refreshClient(7L);
            assertThat(service.getActiveClientCount()).isZero();
        }
        verify(client, times(1)).close();
    }

    @Test
    void duplicateToolsFailInsteadOfOverwriting() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReadFileTool());
        assertThatThrownBy(() -> AgentScopeMcpToolProviderService.rejectDuplicateNames(
            toolkit, List.of("readFile"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AgentScopeMcpToolProviderService.rejectDuplicateNames(
            new Toolkit(), List.of("lookup", "lookup"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void writeBuiltinCannotEnterChatSession() {
        McpTool tool = remote();
        tool.setType("BUILTIN");
        tool.setName("write_file");
        when(mapper.selectList(any())).thenReturn(List.of(tool));
        when(registry.getBuiltinToolObject("write_file")).thenReturn(new org.ruoyi.mcp.tools.WriteFileTool());
        assertThatThrownBy(() -> service.createSession(List.of(7L)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(service.getActiveClientCount()).isZero();
    }

    @Test
    void nativeSchemasKeepReadAndWriteCapabilitiesDistinct() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReadFileTool());
        toolkit.registerTool(new org.ruoyi.mcp.tools.ListDirectoryTool());
        toolkit.registerTool(new org.ruoyi.mcp.tools.WriteFileTool());
        toolkit.registerTool(new org.ruoyi.mcp.tools.EditFileTool());
        toolkit.registerTool(new org.ruoyi.mcp.tools.DeleteFileTool());
        toolkit.registerTool(new org.ruoyi.mcp.tools.ExecuteCommandTool());
        assertThat(toolkit.getTool("readFile").isReadOnly()).isTrue();
        assertThat(toolkit.getTool("listDirectory").isReadOnly()).isTrue();
        for (String name : List.of("writeFile", "editFile", "deleteFile", "executeCommand")) {
            assertThat(toolkit.getTool(name).isReadOnly()).isFalse();
        }
        assertThat(toolkit.getTool("writeFile").getParameters()).containsKey("required");
    }

    private static McpTool remote() {
        McpTool tool = new McpTool();
        tool.setId(7L);
        tool.setName("lookup-service");
        tool.setStatus("ENABLED");
        tool.setType("REMOTE");
        return tool;
    }
}
