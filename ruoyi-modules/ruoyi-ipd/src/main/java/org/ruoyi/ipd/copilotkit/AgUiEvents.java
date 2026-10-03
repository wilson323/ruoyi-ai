package org.ruoyi.ipd.copilotkit;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;

import java.util.List;

/**
 * CopilotKit AG-UI 桥（2026-10-02，对齐 AgentScope 官方 2.0.3）：AG-UI 事件体构造工厂。
 *
 * <p>直接组装官方 record（{@code io.agentscope.core.agui.event.AguiEvent.*}，线格式 {@code type} 值 =
 * 官方 {@code AguiEventType} 枚举 name()），序列化统一走官方 {@code AguiEventEncoder}——字段名/缺省
 * 规则以官方实现为准（便捷构造省略的可选字段序列化不下发），不发明不裁剪。
 *
 * <p>子智能体/蜂群语义：官方 2.0.3 词表<b>无</b> SUBAGENT_* 类型（@ag-ui/core 超前词表不采），官方承载
 * 形态为 {@code AguiEvent.Custom(name="subagent.*")}（官方 SubagentEventConverter 保留名族）——将来真实
 * swarm 生产者接入时循主链 {@code ProjectAgentAguiBridge} 已示范的 Custom 形态扩展，本工厂不预铺词表。
 */
public final class AgUiEvents {

    private AgUiEvents() {
    }

    /** RunStarted：threadId/runId 必填（parentRunId/input 官方 optional，便捷构造缺省不下发）。 */
    public static AguiEvent.RunStarted runStarted(String threadId, String runId) {
        return new AguiEvent.RunStarted(threadId, runId);
    }

    /** RunFinished：threadId/runId 必填（result/outcome 官方 optional，便捷构造缺省不下发）。 */
    public static AguiEvent.RunFinished runFinished(String threadId, String runId) {
        return new AguiEvent.RunFinished(threadId, runId);
    }

    /** RunError：message 必填；code 空白归一 null（官方 optional，序列化缺省不下发）。 */
    public static AguiEvent.RunError runError(String threadId, String runId, String message, String code) {
        return new AguiEvent.RunError(threadId, runId, message == null ? "" : message,
            code == null || code.isBlank() ? null : code);
    }

    /** TextMessageStart：role 固定 assistant（桥只产助手消息；官方 record 对 null role 直接拒绝）。 */
    public static AguiEvent.TextMessageStart textMessageStart(String threadId, String runId, String messageId) {
        return new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant");
    }

    /** TextMessageContent：messageId + delta。 */
    public static AguiEvent.TextMessageContent textMessageContent(String threadId, String runId,
                                                                  String messageId, String delta) {
        return new AguiEvent.TextMessageContent(threadId, runId, messageId, delta);
    }

    /** TextMessageEnd：messageId。 */
    public static AguiEvent.TextMessageEnd textMessageEnd(String threadId, String runId, String messageId) {
        return new AguiEvent.TextMessageEnd(threadId, runId, messageId);
    }

    /** ToolCallStart：toolCallId + toolCallName。 */
    public static AguiEvent.ToolCallStart toolCallStart(String threadId, String runId,
                                                        String toolCallId, String toolCallName) {
        return new AguiEvent.ToolCallStart(threadId, runId, toolCallId, toolCallName);
    }

    /** ToolCallArgs：toolCallId + delta（JSON 文本增量）。 */
    public static AguiEvent.ToolCallArgs toolCallArgs(String threadId, String runId,
                                                      String toolCallId, String delta) {
        return new AguiEvent.ToolCallArgs(threadId, runId, toolCallId, delta);
    }

    /** ToolCallEnd：toolCallId。 */
    public static AguiEvent.ToolCallEnd toolCallEnd(String threadId, String runId, String toolCallId) {
        return new AguiEvent.ToolCallEnd(threadId, runId, toolCallId);
    }

    /** ToolCallResult：toolCallId + content；role 固定 tool（官方便捷构造必填位，与官方 schema 默认值一致）。 */
    public static AguiEvent.ToolCallResult toolCallResult(String threadId, String runId, String toolCallId,
                                                          String messageId, String content) {
        return new AguiEvent.ToolCallResult(threadId, runId, toolCallId, content, "tool", messageId);
    }

    /** StateDelta：delta = 官方 JsonPatchOperation（RFC 6902）操作数组；null 归一空补丁（官方 record 必填 List）。 */
    public static AguiEvent.StateDelta stateDelta(String threadId, String runId, List<JsonPatchOperation> patch) {
        return new AguiEvent.StateDelta(threadId, runId, patch == null ? List.of() : patch);
    }
}
