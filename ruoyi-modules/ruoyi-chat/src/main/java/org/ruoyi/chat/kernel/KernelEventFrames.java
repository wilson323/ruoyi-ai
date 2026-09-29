package org.ruoyi.chat.kernel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.util.Map;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.PolicyDecision;
import org.ruoyi.service.coding.harness.tool.ToolInvocation;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEvaluation;

/**
 * W1 事件→帧映射（ADR-0075 矩阵 #7「包装」：事件源换成熟内核流，帧契约保留）。
 *
 * <p>映射面（其余 AgentEvent 类型 W1 不映射，契约帧不扩）：
 * <ul>
 *   <li>{@link TextBlockDeltaEvent#getDelta()} → {@link KernelChatSink#onContent}；</li>
 *   <li>{@link ThinkingBlockDeltaEvent#getDelta()} → {@link KernelChatSink#onReasoning}；</li>
 *   <li>{@link ToolCallStartEvent} → 权限三态裁决 → {@link KernelChatSink#onMcpTool}。</li>
 * </ul>
 *
 * <p>权限三态薄缝（红线 D1：{@code PolicyDecision} 是裁决唯一源）：工具裁决只经既有
 * {@link ToolPolicyEngine}（fail-closed：unknown tool → DENY），不新增任何自研策略逻辑；
 * AgentScope permission-system 接管在 W3（矩阵 #12），此处只做拦截点映射。
 */
public final class KernelEventFrames {

    private final ToolPolicyEngine toolPolicy;
    private final HarnessPermissionMode permissionMode;

    public KernelEventFrames(ToolPolicyEngine toolPolicy, HarnessPermissionMode permissionMode) {
        if (toolPolicy == null) {
            throw new IllegalArgumentException("Tool policy engine is required");
        }
        if (permissionMode == null) {
            throw new IllegalArgumentException("Permission mode is required");
        }
        this.toolPolicy = toolPolicy;
        this.permissionMode = permissionMode;
    }

    /** 单事件 → 帧回调。未知事件类型静默跳过（契约帧不扩）。 */
    public void dispatch(AgentEvent event, KernelChatSink sink) {
        if (event == null || sink == null) {
            return;
        }
        if (event instanceof TextBlockDeltaEvent text) {
            sink.onContent(text.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent thinking) {
            sink.onReasoning(thinking.getDelta());
        } else if (event instanceof ToolCallStartEvent toolCall) {
            String callId = isBlank(toolCall.getToolCallId()) ? "kernel-uncorrelated" : toolCall.getToolCallId();
            String toolName = isBlank(toolCall.getToolCallName()) ? "kernel-unknown" : toolCall.getToolCallName();
            ToolPolicyEvaluation evaluation = toolPolicy.evaluate(
                ToolInvocation.of(callId, toolName, Map.of()), permissionMode, null);
            sink.onMcpTool(toolName, statusOf(evaluation), evaluation.reason());
        }
    }

    /** PolicyDecision 三态 → mcp_tool 帧 status 词汇。 */
    static String statusOf(ToolPolicyEvaluation evaluation) {
        PolicyDecision decision = evaluation.decision();
        return switch (decision) {
            case ALLOW -> "allowed";
            case ASK -> "approval_required";
            case DENY -> "denied";
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
