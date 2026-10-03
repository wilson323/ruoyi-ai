package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectAgentOfficialToolGovernanceTest {
    @Test void assembledAndLaterChildToolsAreGuardedWithoutCatalogFiltering() {
        var sink = mock(ProjectAgentEventSink.class);
        var governance = new ProjectAgentOfficialToolGovernance(sink);
        var parent = new Toolkit();
        var file = nativeTool("write_file");
        parent.registerAgentTool(file);
        governance.bind(parent);
        var wrapped = parent.getTool("write_file");
        governance.bind(parent);
        assertSame(wrapped, parent.getTool("write_file"));
        assertFalse(wrapped.isReadOnly());
        assertEquals(List.of("write_file"), List.copyOf(parent.getToolNames()));
        var child = new Toolkit();
        child.registerAgentTool(nativeTool("execute"));
        var agent = mock(Agent.class);
        when(agent.getToolkit()).thenReturn(child);
        governance.onActing(agent, null, new ActingInput(List.of()), input -> Flux.empty()).blockLast();
        assertInstanceOf(ProjectAgentOfficialToolGovernance.OwnedTool.class, child.getTool("execute"));
    }

    @Test void nativePermissionPatternsAndSensitiveMetadataSurviveWrapping() {
        var nativeTool = nativeTool("mcp_native");
        when(nativeTool.isMcp()).thenReturn(true);
        when(nativeTool.getMcpName()).thenReturn("line-knowledge");
        when(nativeTool.isExternalTool()).thenReturn(true);
        when(nativeTool.isStateInjected()).thenReturn(true);
        when(nativeTool.matchRule("path:allowed", Map.of())).thenReturn(true);
        var decision = PermissionDecision.ask("official approval required");
        when(nativeTool.checkPermissions(Map.of(), null)).thenReturn(Mono.just(decision));
        var sink = mock(ProjectAgentEventSink.class);
        var wrapper = new ProjectAgentOfficialToolGovernance.OwnedTool(nativeTool, sink);
        assertSame(decision, wrapper.checkPermissions(Map.of(), null).block());
        assertTrue(wrapper.matchRule("path:allowed", Map.of()));
        assertTrue(wrapper.isExternalTool());
        assertTrue(wrapper.isStateInjected());
        assertTrue(wrapper.isMcp());
        assertEquals("line-knowledge", wrapper.getMcpName());
        verify(sink).onStep("TOOL_PERMISSION", Map.of("toolName", "mcp_native", "behavior", "ASK"));
    }

    @Test void revokedOwnershipPreventsEveryPermissionAndActualToolInvocation() {
        var nativeTool = nativeTool("agent_spawn");
        var sink = mock(ProjectAgentEventSink.class);
        doThrow(new IllegalStateException("ownership revoked")).when(sink).requireActiveOwnership();
        var wrapper = new ProjectAgentOfficialToolGovernance.OwnedTool(nativeTool, sink);
        assertThrows(IllegalStateException.class, () -> wrapper.checkPermissions(Map.of(), null).block());
        assertThrows(IllegalStateException.class, () -> wrapper.callAsync(null).block());
        verify(nativeTool, never()).callAsync(any());
        verify(nativeTool, never()).checkPermissions(any(), any());
        verify(sink, never()).onStep(anyString(), any());
    }

    @Test void directInvocationCannotBypassNativeDenyOrReachLocalWebEndpoints() {
        var tool = nativeTool("write_file");
        when(tool.checkPermissions(any(), any())).thenReturn(Mono.just(PermissionDecision.deny("not owned")));
        var sink = mock(ProjectAgentEventSink.class);
        var wrapper = new ProjectAgentOfficialToolGovernance.OwnedTool(tool, sink);
        assertThrows(IllegalStateException.class,
            () -> wrapper.callAsync(ToolCallParam.builder().input(Map.of()).build()).block());
        verify(tool, never()).callAsync(any());
        var web = nativeTool("web_fetch");
        var webWrapper = new ProjectAgentOfficialToolGovernance.OwnedTool(web, sink);
        assertEquals(io.agentscope.core.permission.PermissionBehavior.DENY,
            webWrapper.checkPermissions(Map.of("url", "http://127.0.0.1:16039"), null).block().getBehavior());
        verify(web, never()).checkPermissions(any(), any());
    }

    @Test void directNativeAllowWithoutOfficialActingBindingCannotExecute() {
        var nativeTool = nativeTool("execute");
        when(nativeTool.checkPermissions(any(), any())).thenReturn(Mono.just(PermissionDecision.allow("authorized")));
        var sink = mock(ProjectAgentEventSink.class);
        var wrapper = new ProjectAgentOfficialToolGovernance.OwnedTool(nativeTool, sink);
        var param = ToolCallParam.builder().input(Map.of("command", "private-input")).build();
        assertThrows(IllegalStateException.class, () -> wrapper.callAsync(param).block());
        verify(nativeTool, never()).callAsync(any());
        verify(sink, never()).onStep(eq("TOOL_EXECUTION"), any());
    }

    private static ToolBase nativeTool(String name) {
        var tool = mock(ToolBase.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn("Native official test tool");
        when(tool.getParameters()).thenReturn(Map.of("type", "object"));
        return tool;
    }
}
