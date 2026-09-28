package org.ruoyi.ipd.copilotkit;

/**
 * CopilotKit AG-UI 桥（2026-09-28，单轨融合）：AG-UI 事件类型（线格式 {@code type} 值）。
 *
 * <p>取值与官方 {@code @ag-ui/core@0.0.59} EventType 常量逐一核对一致（事件名/字段不发明，
 * 以 AG-UI 文档缓存 + 官方 SDK 为准）；前端 CopilotKit HttpAgent（@ag-ui/client）按 {@code type} 分发。
 */
public enum AgUiEventType {

    /** 运行开始（threadId/runId；与 RUN_FINISHED/RUN_ERROR 成对构成 run 边界，官方文档强制）。 */
    RUN_STARTED("RUN_STARTED"),
    /** 运行正常结束（threadId/runId）。 */
    RUN_FINISHED("RUN_FINISHED"),
    /** 运行失败（message 必填，code 可选）；此后本 run 不再有任何事件。 */
    RUN_ERROR("RUN_ERROR"),
    /** 助手文本消息开始（messageId，role 默认 assistant）。 */
    TEXT_MESSAGE_START("TEXT_MESSAGE_START"),
    /** 助手文本增量（messageId + delta）。 */
    TEXT_MESSAGE_CONTENT("TEXT_MESSAGE_CONTENT"),
    /** 助手文本消息结束（messageId）。 */
    TEXT_MESSAGE_END("TEXT_MESSAGE_END"),
    /** 工具调用开始（toolCallId + toolCallName）。 */
    TOOL_CALL_START("TOOL_CALL_START"),
    /** 工具调用参数增量（toolCallId + delta，JSON 文本）。 */
    TOOL_CALL_ARGS("TOOL_CALL_ARGS"),
    /** 工具调用结束（toolCallId）。 */
    TOOL_CALL_END("TOOL_CALL_END"),
    /** 工具调用结果（messageId + toolCallId + content）。 */
    TOOL_CALL_RESULT("TOOL_CALL_RESULT"),
    /** 状态增量（delta = RFC 6902 JSON Patch 操作数组）。 */
    STATE_DELTA("STATE_DELTA");

    private final String wireName;

    AgUiEventType(String wireName) {
        this.wireName = wireName;
    }

    /** 线格式 type 值。 */
    public String wireName() {
        return wireName;
    }
}
