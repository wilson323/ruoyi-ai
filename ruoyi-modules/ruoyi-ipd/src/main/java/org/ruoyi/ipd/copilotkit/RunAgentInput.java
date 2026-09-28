package org.ruoyi.ipd.copilotkit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * CopilotKit AG-UI 桥（2026-09-28）：{@code POST /copilotkit/agent/:agentId/run} 请求体
 * —— AG-UI RunAgentInput（官方 {@code @ag-ui/core@0.0.59} RunAgentInputSchema 字段对齐，不发明字段）。
 *
 * <p>schema：{threadId(必), runId(必), parentRunId?, state, messages[], tools[], context[],
 * forwardedProps, resume?}；context 项 = {description, value}；未知字段一律忽略（官方 schema 兼容演进）。
 *
 * <p>安全：用户身份从 Bearer 会话取（{@link AgUiCopilotRun} 走 IpdPermission.requireInternal），
 * <b>不信任 body</b>；body 只承载会话语义（消息/上下文/工具声明）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunAgentInput(String threadId,
                            String runId,
                            String parentRunId,
                            Object state,
                            List<Message> messages,
                            List<Tool> tools,
                            List<Context> context,
                            Map<String, Object> forwardedProps,
                            Object resume) {

    /** AG-UI Message（role 七态联合；content 可为纯文本串或 content parts 数组——桥只消费纯文本串）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String role, String name, Object content) {
    }

    /** AG-UI Tool 声明（name 必填；parameters = JSON Schema 对象；metadata 可选）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tool(String name, String description, Object parameters, Object metadata) {
    }

    /** AG-UI Context 条目（description + value，均为字符串）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Context(String description, String value) {
    }
}
