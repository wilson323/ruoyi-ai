package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 持久事件的 AG-UI 线格式投影。只读，不运行智能体、不修改状态。 */
public final class ProjectAgentAguiProjection {
    private final AguiEventEncoder encoder = new AguiEventEncoder();
    private final ObjectMapper mapper;

    public ProjectAgentAguiProjection(ObjectMapper mapper) { this.mapper = mapper; }

    public List<String> encode(String runId, ProjectAgentViews.Event row) {
        row = ProjectAgentAguiPublicEvent.project(mapper, row);
        List<String> frames = new ArrayList<>();
        Map<?, ?> payload = row.payload() instanceof Map<?, ?> value ? value : Map.of();
        switch (row.type()) {
            case "RUN_STARTED" -> frames.add(encoder.encodeToJson(new AguiEvent.RunStarted(runId, runId)));
            case "RUN_FINISHED" -> frames.add(encoder.encodeToJson(new AguiEvent.RunFinished(runId, runId)));
            case "ERROR" -> frames.add(encoder.encodeToJson(new AguiEvent.RunError(runId, runId,
                string(payload.get("message"), "运行失败"), string(payload.get("errorCode"), "RUN_FAILED"))));
            case "STEP" -> {
                if ("AGUI".equals(payload.get("kind")) && payload.get("events") instanceof List<?> events) {
                    for (Object event : events) {
                        if (!(event instanceof String json)) throw new IllegalStateException("invalid persisted AG-UI event");
                        try {
                            var parsed = mapper.readTree(json);
                            if (!parsed.isObject() || !parsed.hasNonNull("type"))
                                throw new IllegalStateException("invalid persisted AG-UI event");
                        } catch (java.io.IOException invalid) { throw new IllegalStateException("invalid persisted AG-UI event", invalid); }
                        frames.add(json);
                    }
                }
            }
            default -> { }
        }
        // 原业务行是唯一已消费游标；在原生帧之后交付，避免半行消费导致重连丢帧。
        frames.add(encoder.encodeToJson(new AguiEvent.Custom(runId, runId, "ipd_event", row)));
        return List.copyOf(frames);
    }

    private static String string(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
}
