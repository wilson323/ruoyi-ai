package org.ruoyi.chat.kernel;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1 事件→帧映射单测（矩阵 #7 包装 + #12 权限三态薄缝）：
 * TextBlockDelta/ThinkingBlockDelta → content/reasoning 帧；ToolCallStart → PolicyDecision
 * 三态（ALLOW/ASK/DENY）→ mcp_tool 帧 status 词汇。纯单测，不触引擎/存储。
 */
@Tag("dev")
@DisplayName("W1 内核事件帧映射 + 权限三态薄缝")
class KernelEventFramesTest {

    private static final ToolDescriptor READ_TOOL = new ToolDescriptor(
        "kb-lookup", Set.of(ToolCapability.READ), true, 1_000L, 1_024L, 1_024L, false, "read-only lookup");

    private static final ToolDescriptor EXECUTE_TOOL = new ToolDescriptor(
        "shell-run", Set.of(ToolCapability.EXECUTE), false, 1_000L, 1_024L, 1_024L, false, "executes commands");

    @Test
    @DisplayName("TextBlockDelta → content 帧（增量原文透传）")
    void textDeltaMapsToContentFrame() {
        RecordingSink sink = new RecordingSink();
        KernelEventFrames frames = new KernelEventFrames(
            new ToolPolicyEngine(List.of()), HarnessPermissionMode.READ_ONLY);

        frames.dispatch(new TextBlockDeltaEvent("r1", "b1", "你好"), sink);

        assertEquals(List.of("content:你好"), sink.log);
    }

    @Test
    @DisplayName("ThinkingBlockDelta → reasoning 帧（增量原文透传）")
    void thinkingDeltaMapsToReasoningFrame() {
        RecordingSink sink = new RecordingSink();
        KernelEventFrames frames = new KernelEventFrames(
            new ToolPolicyEngine(List.of()), HarnessPermissionMode.READ_ONLY);

        frames.dispatch(new ThinkingBlockDeltaEvent("r1", "b1", "思考中"), sink);

        assertEquals(List.of("reasoning:思考中"), sink.log);
    }

    @Test
    @DisplayName("权限三态 DENY：未知工具 fail-closed（空 descriptor 即拒绝）")
    void unknownToolIsDeniedFailClosed() {
        RecordingSink sink = new RecordingSink();
        KernelEventFrames frames = new KernelEventFrames(
            new ToolPolicyEngine(List.of()), HarnessPermissionMode.FULL_ACCESS);

        frames.dispatch(new ToolCallStartEvent("r1", "c1", "rogue-tool"), sink);

        assertEquals(1, sink.mcpTools.size());
        assertEquals("rogue-tool", sink.mcpTools.get(0)[0]);
        assertEquals("denied", sink.mcpTools.get(0)[1]);
        assertTrue(sink.mcpTools.get(0)[2].contains("Unknown tools are denied"), sink.mcpTools.get(0)[2]);
    }

    @Test
    @DisplayName("权限三态 ALLOW：READ 工具 + READ_ONLY 模式放行")
    void readOnlyToolAllowedInReadOnlyMode() {
        RecordingSink sink = new RecordingSink();
        KernelEventFrames frames = new KernelEventFrames(
            new ToolPolicyEngine(List.of(READ_TOOL)), HarnessPermissionMode.READ_ONLY);

        frames.dispatch(new ToolCallStartEvent("r1", "c1", "kb-lookup"), sink);

        assertEquals("allowed", sink.mcpTools.get(0)[1]);
    }

    @Test
    @DisplayName("权限三态 ASK：EXECUTE 工具 + WORKSPACE_WRITE 模式待审批")
    void executeToolAsksInWorkspaceWriteMode() {
        RecordingSink sink = new RecordingSink();
        KernelEventFrames frames = new KernelEventFrames(
            new ToolPolicyEngine(List.of(EXECUTE_TOOL)), HarnessPermissionMode.WORKSPACE_WRITE);

        frames.dispatch(new ToolCallStartEvent("r1", "c1", "shell-run"), sink);

        assertEquals("approval_required", sink.mcpTools.get(0)[1]);
    }

    /** 记录型 sink：帧序列 + mcp_tool 三元组。 */
    private static final class RecordingSink implements KernelChatSink {

        private final List<String> log = new ArrayList<>();
        private final List<String[]> mcpTools = new ArrayList<>();

        @Override
        public void onContent(String delta) {
            log.add("content:" + delta);
        }

        @Override
        public void onReasoning(String delta) {
            log.add("reasoning:" + delta);
        }

        @Override
        public void onMcpTool(String toolName, String status, String result) {
            mcpTools.add(new String[] {toolName, status, result});
            log.add("mcp_tool:" + toolName);
        }

        @Override
        public void onError(String code, String message) {
            log.add("error:" + code);
        }

        @Override
        public void onComplete() {
            log.add("complete");
        }
    }
}
