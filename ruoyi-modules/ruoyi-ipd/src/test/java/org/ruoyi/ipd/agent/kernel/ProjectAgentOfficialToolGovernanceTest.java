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

    /**
     * web_search 出站治理正反控。
     *
     * <p>背景：文档三处曾把 {@code ToolsConfig.setDeny} 列为 web 工具的「已覆盖」安全缓解，
     * 但该 deny 已被 commit {@code b757fa7a}（commit message 仅 "test"）删除，
     * web_search 曾在完全无治理的状态下暴露给模型。本测试锁死补上的这道闸。
     *
     * <p>注意 web_search 的风险形态与 web_fetch <b>不同</b>：SDK 内目标端点写死为
     * {@code https://api.tavily.com/search}，没有 SSRF 面；真实风险是模型生成的
     * {@code query} 可被提示词注入诱导而夹带凭据发往第三方。
     */
    @Test void webSearchQueryCarryingCredentialsIsDeniedBeforeReachingTavily() {
        var search = nativeTool("web_search");
        var sink = mock(ProjectAgentEventSink.class);
        var wrapper = new ProjectAgentOfficialToolGovernance.OwnedTool(search, sink);

        // 反例：查询词夹带凭据 —— 必须 DENY，且不得下探到官方工具
        assertEquals(io.agentscope.core.permission.PermissionBehavior.DENY,
            wrapper.checkPermissions(Map.of("query", "查一下 our password 是什么"), null)
                .block().getBehavior());
        assertEquals(io.agentscope.core.permission.PermissionBehavior.DENY,
            wrapper.checkPermissions(Map.of("query", "项目 apikey 泄漏排查"), null)
                .block().getBehavior());
        // 超长查询词同样是异常载荷
        assertEquals(io.agentscope.core.permission.PermissionBehavior.DENY,
            wrapper.checkPermissions(Map.of("query", "x".repeat(501)), null).block().getBehavior());
        // 空查询词
        assertEquals(io.agentscope.core.permission.PermissionBehavior.DENY,
            wrapper.checkPermissions(Map.of("query", "   "), null).block().getBehavior());
        verify(search, never()).checkPermissions(any(), any());
    }

    @Test void ordinaryWebSearchQueryIsNotBlockedByTheGovernance() {
        var search = nativeTool("web_search");
        when(search.checkPermissions(any(), any()))
            .thenReturn(Mono.just(PermissionDecision.allow("authorized")));
        var sink = mock(ProjectAgentEventSink.class);
        var wrapper = new ProjectAgentOfficialToolGovernance.OwnedTool(search, sink);

        // 正例：正常业务查询必须放行，不能把闸做成闭门
        assertEquals(io.agentscope.core.permission.PermissionBehavior.ALLOW,
            wrapper.checkPermissions(Map.of("query", "智能锁联动 竞品分析 2026"), null)
                .block().getBehavior());
        verify(search).checkPermissions(any(), any());
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
