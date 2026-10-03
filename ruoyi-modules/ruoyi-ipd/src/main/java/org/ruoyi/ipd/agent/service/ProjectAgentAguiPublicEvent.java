package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;

/** HTTP/SSE 共用的只读出站视图；只去服务器内部关联，保留原存储及官方工具参数。 */
public final class ProjectAgentAguiPublicEvent {
    public static final String INTERNAL_ORIGIN = "ipd.server.child.invocation";
    private ProjectAgentAguiPublicEvent() { }

    public static ProjectAgentViews.Event project(ObjectMapper mapper, ProjectAgentViews.Event row) {
        JsonNode payload = mapper.valueToTree(row.payload()).deepCopy();
        if ("STEP".equals(row.type())) cleanPayload(mapper, payload);
        // 手工重建 Event，避免反射 DTO 往返改变字符串 ID、seq 或 createdAt。
        return new ProjectAgentViews.Event(row.seq(), row.type(), mapper.convertValue(payload, Object.class), row.createdAt());
    }

    private static void cleanPayload(ObjectMapper mapper, JsonNode payload) {
        removeInternal(payload); // 原 STEP 顶层的服务器 receipt 容器。
        if ("AGUI_RESUMED".equals(payload.path("kind").asText()) && payload instanceof ObjectNode object)
            object.remove(ProjectAgentAguiPauseResumeService.INTERNAL_RESUME_INTENT);
        if ("AWAIT_USER".equals(payload.path("kind").asText())
                && "AGUI_INTERRUPT".equals(payload.path("reason").asText())) {
            JsonNode interrupts = payload.path("interrupts");
            if (interrupts.isObject() || interrupts.isArray())
                interrupts.elements().forEachRemaining(ProjectAgentAguiPublicEvent::cleanInterrupt);
        }
        if ("AGUI".equals(payload.path("kind").asText()) && payload.path("events").isArray()) {
            var events = (com.fasterxml.jackson.databind.node.ArrayNode) payload.get("events");
            for (int i = 0; i < events.size(); i++) {
                if (!events.get(i).isTextual()) throw new IllegalStateException("invalid persisted AG-UI event");
                try {
                    JsonNode frame = mapper.readTree(events.get(i).textValue());
                    if (frame == null || !frame.isObject() || !frame.hasNonNull("type"))
                        throw new IllegalStateException("invalid persisted AG-UI event");
                    cleanNative(frame);
                    events.set(i, mapper.getNodeFactory().textNode(mapper.writeValueAsString(frame)));
                } catch (java.io.IOException invalid) {
                    throw new IllegalStateException("invalid persisted AG-UI event", invalid);
                }
            }
        }
    }

    private static void cleanNative(JsonNode frame) {
        removeInternal(frame.path("metadata"));
        removeInternal(frame.path("rawEvent").path("metadata"));
        String type = frame.path("type").asText();
        if ("RAW".equals(type)) removeInternal(frame.path("event").path("metadata"));
        if ("RUN_FINISHED".equals(type)) {
            JsonNode interrupts = frame.path("outcome").path("interrupts");
            if (interrupts.isArray()) interrupts.elements().forEachRemaining(ProjectAgentAguiPublicEvent::cleanInterrupt);
        }
        if ("CUSTOM".equals(type)) {
            String name = frame.path("name").asText();
            if (name.startsWith("subagent.")) removeInternal(frame.path("value").path("metadata"));
        }
    }

    private static void cleanInterrupt(JsonNode interrupt) { removeInternal(interrupt.path("metadata")); }
    private static void removeInternal(JsonNode container) {
        if (container instanceof ObjectNode object) object.remove(INTERNAL_ORIGIN);
    }
}
