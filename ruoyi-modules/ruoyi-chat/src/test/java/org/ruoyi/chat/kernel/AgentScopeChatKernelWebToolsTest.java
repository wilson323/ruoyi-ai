package org.ruoyi.chat.kernel;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.tool.InMemoryKernelToolEffectLedger;
import org.ruoyi.chat.kernel.tool.KernelGovernedTool;
import org.ruoyi.chat.kernel.tool.KernelToolCallTrace;
import org.ruoyi.chat.kernel.tool.KernelToolGovernance;
import org.ruoyi.service.coding.harness.model.HarnessApprovalPolicy;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 聊天内核联网工具的放行边界：公网放行、内网拒绝；web_search 只在配置密钥后提供。 */
@Tag("dev")
class AgentScopeChatKernelWebToolsTest {

    private static AgentTool webFetchTool() {
        AgentTool delegate = mock(AgentTool.class);
        when(delegate.getName()).thenReturn("web_fetch");
        when(delegate.getDescription()).thenReturn("fetch");
        when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
        when(delegate.callAsync(any())).thenReturn(Mono.just(ToolResultBlock.text("fetched")));
        return delegate;
    }

    private static KernelGovernedTool governed(AgentTool delegate) {
        ToolDescriptor descriptor = new ToolDescriptor("web_fetch", Set.of(ToolCapability.NETWORK),
            false, 1_000L, 1_024L, 1_024L, false, "network");
        var governance = new KernelToolGovernance(new ToolPolicyEngine(List.of(descriptor)),
            HarnessPermissionMode.FULL_ACCESS, HarnessApprovalPolicy.NEVER,
            new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
        return KernelGovernedTool.wrap(delegate, governance);
    }

    @Test void webFetchToInternalAddressesIsRefusedAndNeverReachesTheOfficialTool() {
        for (String url : List.of("http://127.0.0.1:8080/admin", "http://169.254.169.254/latest/meta-data/",
                "http://10.0.0.5/", "http://localhost/", "http://[::1]/")) {
            AgentTool delegate = webFetchTool();
            var tool = governed(delegate);
            Map<String, Object> input = Map.of("url", url);
            var param = ToolCallParam.builder().toolUseBlock(new ToolUseBlock("c1", "web_fetch", input)).input(input).build();
            var result = tool.callAsync(param).block();
            assertNotNull(result, url);
            assertEquals(ToolResultState.ERROR, result.getState(), url);
            verify(delegate, never()).callAsync(any());
        }
    }

    @Test void webFetchToPublicAddressStillRunsDirectlyWithoutApproval() {
        AgentTool delegate = webFetchTool();
        var tool = governed(delegate);
        Map<String, Object> input = Map.of("url", "http://93.184.216.34/");
        var decision = tool.checkPermissions(input, null).block();
        assertNotNull(decision);
        assertEquals(io.agentscope.core.permission.PermissionBehavior.ALLOW, decision.getBehavior());
        var param = ToolCallParam.builder().toolUseBlock(new ToolUseBlock("c1", "web_fetch", input)).input(input).build();
        var result = tool.callAsync(param).block();
        assertNotNull(result);
        assertNotEquals(ToolResultState.ERROR, result.getState());
        verify(delegate, times(1)).callAsync(any());
    }

    @Test void webSearchStaysRegisteredEvenWithoutKey() {
        Toolkit withoutKey = new Toolkit();
        withoutKey.registerAgentTool(namedTool("web_search"));
        withoutKey.registerAgentTool(namedTool("web_fetch"));
        assertTrue(AgentScopeChatKernel.warnIfWebSearchUnconfigured(withoutKey, () -> null));
        assertNotNull(withoutKey.getTool("web_search"), "official tools must never be pruned");
        assertNotNull(withoutKey.getTool("web_fetch"));

        Toolkit blankKey = new Toolkit();
        blankKey.registerAgentTool(namedTool("web_search"));
        assertTrue(AgentScopeChatKernel.warnIfWebSearchUnconfigured(blankKey, () -> "  "));
        assertNotNull(blankKey.getTool("web_search"));

        Toolkit withKey = new Toolkit();
        withKey.registerAgentTool(namedTool("web_search"));
        assertFalse(AgentScopeChatKernel.warnIfWebSearchUnconfigured(withKey, () -> "tvly-test"));
        assertNotNull(withKey.getTool("web_search"));
    }

    private static AgentTool namedTool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn(name);
        when(tool.getParameters()).thenReturn(Map.of("type", "object"));
        return tool;
    }
}
