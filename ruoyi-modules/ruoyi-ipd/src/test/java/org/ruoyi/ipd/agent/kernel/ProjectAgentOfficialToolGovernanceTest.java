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
        var param = ToolCallParam.builder().input(Map.of("command", "git status")).build();
        assertThrows(IllegalStateException.class, () -> wrapper.callAsync(param).block());
        verify(nativeTool, never()).callAsync(any());
        verify(sink, never()).onStep(eq("TOOL_EXECUTION"), any());
    }

    @Test void executePermissionRejectsInjectionBeforeNativeAllow() {
        var tool = nativeTool("execute");
        when(tool.checkPermissions(any(), any())).thenReturn(Mono.just(PermissionDecision.allow("native allow")));
        var wrapper = new ProjectAgentOfficialToolGovernance.OwnedTool(tool, mock(ProjectAgentEventSink.class));
        assertEquals(io.agentscope.core.permission.PermissionBehavior.DENY,
            wrapper.checkPermissions(Map.of("command", "git status; printf injected"), null).block().getBehavior());
        verify(tool, never()).checkPermissions(any(), any());
        assertEquals(io.agentscope.core.permission.PermissionBehavior.ALLOW,
            wrapper.checkPermissions(Map.of("command", "git status"), null).block().getBehavior());
    }

    @Test void officialAllowedExecutionCannotBypassCommandValidation() {
        var tool = nativeTool("execute");
        var param = approvedCall(tool, Map.of("command", "git status && printf injected"));
        var guarded = param.getAgent().getToolkit().getTool("execute");
        assertThrows(IllegalArgumentException.class, () -> guarded.callAsync(param).block());
        verify(tool, never()).callAsync(any());
        verify(tool, never()).checkPermissions(any(), any());
    }

    @Test void approvedExecuteDelegatesCanonicalLiteralArgsWithOriginalApprovalBinding() {
        var tool = nativeTool("execute");
        when(tool.callAsync(any())).thenReturn(Mono.just(ToolResultBlock.text("executed")));
        var param = approvedCall(tool, Map.of("command", "git status 'file; printf injected'", "working_directory", "src"));
        var guarded = param.getAgent().getToolkit().getTool("execute");
        assertNotNull(guarded.callAsync(param).block());
        var received = org.mockito.ArgumentCaptor.forClass(ToolCallParam.class);
        verify(tool).callAsync(received.capture());
        assertEquals("'git' 'status' 'file; printf injected'", received.getValue().getInput().get("command"));
        assertEquals("src", received.getValue().getInput().get("working_directory"));
        assertSame(param.getToolUseBlock(), received.getValue().getToolUseBlock());
        assertEquals("git status 'file; printf injected'", param.getInput().get("command"));
        assertThrows(IllegalStateException.class, () -> guarded.callAsync(param).block());
    }

    @Test void fileToolsReceiveTheirOriginalInputsWithoutCommandPolicyFiltering() {
        var tool = nativeTool("write_file");
        when(tool.callAsync(any())).thenReturn(Mono.just(ToolResultBlock.text("written")));
        var original = Map.<String, Object>of("path", "notes.md", "content", "sh -c source; $literal", "command", "not a process");
        var param = approvedCall(tool, original);
        assertNotNull(param.getAgent().getToolkit().getTool("write_file").callAsync(param).block());
        var received = org.mockito.ArgumentCaptor.forClass(ToolCallParam.class);
        verify(tool).callAsync(received.capture());
        assertSame(param, received.getValue());
        assertEquals(original, received.getValue().getInput());
    }

    @Test void backgroundOffloadDoesNotCancelAnApprovedFileListing() {
        var tool = nativeTool("list_files");
        when(tool.callAsync(any())).thenReturn(Mono.just(ToolResultBlock.text("listed")));
        var input = Map.<String, Object>of("path", "/workspace");
        var param = approvedCall(tool, input);
        var state = param.getRuntimeContext().getAgentState();
        state.contextMutable().add(io.agentscope.core.message.Msg.builder()
            .role(io.agentscope.core.message.MsgRole.TOOL)
            .content(List.of(ToolResultBlock.text(
                "<system-reminder>Tool 'list_files' is running in background (id=owned-tool-call) for over 30s.")
                .withIdAndName("owned-tool-call", "list_files"))).build());
        state.contextMutable().add(io.agentscope.core.message.Msg.builder()
            .role(io.agentscope.core.message.MsgRole.ASSISTANT)
            .content(List.of(io.agentscope.core.message.TextBlock.builder().text("继续").build())).build());
        assertNotNull(param.getAgent().getToolkit().getTool("list_files").callAsync(param).block());
        verify(tool).callAsync(any());
    }

    @Test void completedListingResultStillRejectsReplay() {
        var tool = nativeTool("list_files");
        var param = approvedCall(tool, Map.of("path", "/workspace"));
        param.getRuntimeContext().getAgentState().contextMutable().add(io.agentscope.core.message.Msg.builder()
            .role(io.agentscope.core.message.MsgRole.TOOL)
            .content(List.of(ToolResultBlock.text("Error: already listed")
                .withIdAndName("owned-tool-call", "list_files"))).build());
        assertThrows(IllegalStateException.class,
            () -> param.getAgent().getToolkit().getTool("list_files").callAsync(param).block());
        verify(tool, never()).callAsync(any());
    }

    private static ToolCallParam approvedCall(ToolBase original, Map<String, Object> input) {
        var toolkit = new Toolkit(); toolkit.registerAgentTool(original);
        var agent = mock(Agent.class); when(agent.getToolkit()).thenReturn(toolkit);
        var context = io.agentscope.core.agent.RuntimeContext.builder().userId("person-test").sessionId("run-test").build();
        var call = io.agentscope.core.message.ToolUseBlock.builder().id("owned-tool-call").name(original.getName())
            .input(input).content("original-approved-input").state(io.agentscope.core.message.ToolCallState.ALLOWED).build();
        context.setAgentState(io.agentscope.core.state.AgentState.builder().userId("person-test").sessionId("run-test")
            .addMessage(io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .content(List.of(call)).build()).build());
        var governance = new ProjectAgentOfficialToolGovernance(mock(ProjectAgentEventSink.class));
        governance.onActing(agent, context, new ActingInput(List.of(call)), ignored -> Flux.empty()).blockLast();
        return ToolCallParam.builder().agent(agent).runtimeContext(context).toolUseBlock(call).input(input).build();
    }

    private static ToolBase nativeTool(String name) {
        var tool = mock(ToolBase.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn("Native official test tool");
        when(tool.getParameters()).thenReturn(Map.of("type", "object"));
        return tool;
    }
}
