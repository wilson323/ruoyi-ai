package org.ruoyi.chat.kernel.tool;

import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Capability registration must not permit a direct-call bypass of business approval. */
@Tag("dev")
class OfficialCapabilitiesBusinessGateRegressionTest {
    @Test
    void registeredWriteCapabilityStillRejectsDeniedAndUnapprovedExecution() {
        for (HarnessPermissionMode mode : List.of(HarnessPermissionMode.READ_ONLY,
                HarnessPermissionMode.WORKSPACE_WRITE)) {
            AgentTool delegate = mock(AgentTool.class);
            when(delegate.getName()).thenReturn("official_shell");
            when(delegate.getDescription()).thenReturn("registered execution capability");
            when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
            ToolDescriptor descriptor = new ToolDescriptor("official_shell", Set.of(ToolCapability.EXECUTE),
                false, 1_000L, 1_024L, 1_024L, false, "execution requires authorization");
            var governance = new KernelToolGovernance(new ToolPolicyEngine(List.of(descriptor)), mode,
                new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
            var tool = KernelGovernedTool.wrap(delegate, governance);
            Map<String, Object> input = Map.of("cmd", "write-business-state");
            var decision = tool.checkPermissions(input, null).block();
            assertNotNull(decision);
            assertEquals(mode == HarnessPermissionMode.READ_ONLY ? PermissionBehavior.DENY : PermissionBehavior.ASK,
                decision.getBehavior());
            var param = ToolCallParam.builder().toolUseBlock(new ToolUseBlock("bypass", "official_shell", input)).build();
            var result = tool.callAsync(param).block();
            assertNotNull(result);
            assertEquals(ToolResultState.ERROR, result.getState());
            verify(delegate, never()).callAsync(any());
        }
    }

    /** 聊天内核现行配置（FULL_ACCESS + NEVER）：官方能力直接放行执行，且全部留痕。 */
    @Test
    void fullAccessWithNeverApprovalExecutesRegisteredCapabilities() {
        for (ToolCapability capability : List.of(ToolCapability.WRITE, ToolCapability.EXECUTE, ToolCapability.NETWORK)) {
            AgentTool delegate = mock(AgentTool.class);
            when(delegate.getName()).thenReturn("official_tool");
            when(delegate.getDescription()).thenReturn("registered capability");
            when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
            when(delegate.callAsync(any())).thenReturn(reactor.core.publisher.Mono.just(
                io.agentscope.core.message.ToolResultBlock.text("ok")));
            ToolDescriptor descriptor = new ToolDescriptor("official_tool", Set.of(capability),
                false, 1_000L, 1_024L, 1_024L, false, "official capability");
            var governance = new KernelToolGovernance(new ToolPolicyEngine(List.of(descriptor)),
                HarnessPermissionMode.FULL_ACCESS,
                org.ruoyi.service.coding.harness.model.HarnessApprovalPolicy.NEVER,
                new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
            var tool = KernelGovernedTool.wrap(delegate, governance);
            Map<String, Object> input = Map.of("arg", "v");
            var decision = tool.checkPermissions(input, null).block();
            assertNotNull(decision);
            assertEquals(PermissionBehavior.ALLOW, decision.getBehavior(), capability.name());
            var param = ToolCallParam.builder().toolUseBlock(new ToolUseBlock("c1", "official_tool", input)).build();
            var result = tool.callAsync(param).block();
            assertNotNull(result);
            assertNotEquals(ToolResultState.ERROR, result.getState(), capability.name());
            verify(delegate, times(1)).callAsync(any());
        }
    }
}

