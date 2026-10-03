package org.ruoyi.chat.kernel;

import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class KernelFinalResponseTest {
    @Test void finalMessageCarriesReplacementAndRealUsageWithoutEmittingAnotherDelta() {
        ChatUsage usage = new ChatUsage(12, 8, 0);
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("old text")
            .metadata(Map.of("replacementText", "reviewed text", "conversationId", "conv-1")).usage(usage).build();
        KernelChatSink sink = mock(KernelChatSink.class);
        new KernelEventFrames(new ToolPolicyEngine(List.of()), HarnessPermissionMode.READ_ONLY)
            .dispatch(new AgentResultEvent(result), sink);
        verify(sink).onResult(result);
        verify(sink, never()).onContent(any());
        assertThat(KernelFinalResponse.text(result, "old text")).isEqualTo("reviewed text");
        var response = KernelFinalResponse.response(result, "old text");
        assertThat(response.getUsage()).isSameAs(usage);
        assertThat(response.getMetadata()).containsEntry("conversationId", "conv-1");
    }
    @Test void absentUsageRemainsUnknownAndFinalTextOverridesIntermediateRounds() {
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("final answer").build();
        assertThat(KernelFinalResponse.text(result, "intermediate final answer")).isEqualTo("final answer");
        assertThat(KernelFinalResponse.response(result, "intermediate").getUsage()).isNull();
    }
}
