package org.ruoyi.ipd.copilotkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CopilotKit AG-UI 桥（2026-09-28）：AG-UI 事件体构造工厂。
 *
 * <p>字段以 AG-UI 文档缓存（events 页属性表）+ 官方 {@code @ag-ui/core@0.0.59} schema 为准，不发明字段；
 * 事件体一律 {@link LinkedHashMap}（type 键在前，线格式 JSON 稳定可断言）。可选字段仅在有值时出现
 * （官方 schema optional 字段缺省可解析）。
 */
public final class AgUiEvents {

    private AgUiEvents() {
    }

    /** RunStarted：threadId/runId 必填（parentRunId/input 可选，桥侧不产）。 */
    public static Map<String, Object> runStarted(String threadId, String runId) {
        Map<String, Object> e = base(AgUiEventType.RUN_STARTED);
        e.put("threadId", threadId);
        e.put("runId", runId);
        return e;
    }

    /** RunFinished：threadId/runId 必填（result/outcome 可选，桥侧不产）。 */
    public static Map<String, Object> runFinished(String threadId, String runId) {
        Map<String, Object> e = base(AgUiEventType.RUN_FINISHED);
        e.put("threadId", threadId);
        e.put("runId", runId);
        return e;
    }

    /** RunError：message 必填；code 仅非空时下发（官方 schema optional）。 */
    public static Map<String, Object> runError(String message, String code) {
        Map<String, Object> e = base(AgUiEventType.RUN_ERROR);
        e.put("message", message == null ? "" : message);
        if (code != null && !code.isBlank()) {
            e.put("code", code);
        }
        return e;
    }

    /** TextMessageStart：role 固定 assistant（桥只产助手消息）。 */
    public static Map<String, Object> textMessageStart(String messageId) {
        Map<String, Object> e = base(AgUiEventType.TEXT_MESSAGE_START);
        e.put("messageId", messageId);
        e.put("role", "assistant");
        return e;
    }

    /** TextMessageContent：messageId + delta。 */
    public static Map<String, Object> textMessageContent(String messageId, String delta) {
        Map<String, Object> e = base(AgUiEventType.TEXT_MESSAGE_CONTENT);
        e.put("messageId", messageId);
        e.put("delta", delta);
        return e;
    }

    /** TextMessageEnd：messageId。 */
    public static Map<String, Object> textMessageEnd(String messageId) {
        Map<String, Object> e = base(AgUiEventType.TEXT_MESSAGE_END);
        e.put("messageId", messageId);
        return e;
    }

    /** ToolCallStart：toolCallId + toolCallName。 */
    public static Map<String, Object> toolCallStart(String toolCallId, String toolCallName) {
        Map<String, Object> e = base(AgUiEventType.TOOL_CALL_START);
        e.put("toolCallId", toolCallId);
        e.put("toolCallName", toolCallName);
        return e;
    }

    /** ToolCallArgs：toolCallId + delta（JSON 文本增量）。 */
    public static Map<String, Object> toolCallArgs(String toolCallId, String delta) {
        Map<String, Object> e = base(AgUiEventType.TOOL_CALL_ARGS);
        e.put("toolCallId", toolCallId);
        e.put("delta", delta);
        return e;
    }

    /** ToolCallEnd：toolCallId。 */
    public static Map<String, Object> toolCallEnd(String toolCallId) {
        Map<String, Object> e = base(AgUiEventType.TOOL_CALL_END);
        e.put("toolCallId", toolCallId);
        return e;
    }

    /** ToolCallResult：messageId + toolCallId + content；role 固定 tool（官方 schema 默认值）。 */
    public static Map<String, Object> toolCallResult(String messageId, String toolCallId, String content) {
        Map<String, Object> e = base(AgUiEventType.TOOL_CALL_RESULT);
        e.put("messageId", messageId);
        e.put("toolCallId", toolCallId);
        e.put("content", content);
        e.put("role", "tool");
        return e;
    }

    /** StateDelta：delta = RFC 6902 JSON Patch 操作数组（官方 schema {@code delta: any[]}）。 */
    public static Map<String, Object> stateDelta(List<Object> patch) {
        Map<String, Object> e = base(AgUiEventType.STATE_DELTA);
        e.put("delta", patch == null ? new ArrayList<>() : patch);
        return e;
    }

    private static Map<String, Object> base(AgUiEventType type) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type.wireName());
        return e;
    }
}
