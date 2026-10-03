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
            // 记忆抽取失败不再改写业务终态（兄弟会话 2026-10-03 语义变更），改为事后另发回执。
            // 回执行若只落在下面的 ipd_event 业务行里，客户端没有任何理由为它渲染提示，
            // 「记忆没存上」就会静默消失；这里按 ERROR 的既有形态补一个专属对外信号。
            // 非终态：不得触发 RUN_ERROR / RUN_FINISHED。
            case "MEMORY_RECEIPT" -> {
                // 出站边界只认白名单：回执来自异常路径，不能指望写入方永远只放安全的键。
                row = receiptRow(row);
                frames.add(encoder.encodeToJson(new AguiEvent.Custom(runId, runId, "ipd.memory_receipt", row)));
            }
            default -> { }
        }
        // 原业务行是唯一已消费游标；在原生帧之后交付，避免半行消费导致重连丢帧。
        frames.add(encoder.encodeToJson(new AguiEvent.Custom(runId, runId, "ipd_event", row)));
        return List.copyOf(frames);
    }

    /**
     * 记忆回执的出站白名单：只保留状态、是否可重试、计数与错误类别。
     *
     * <p>错误类别只取类名（{@code Throwable#getSimpleName}），不带异常原文；
     * 载荷里的其它键——异常消息、堆栈、对话正文、凭据——一律不带出服务器。
     */
    private static ProjectAgentViews.Event receiptRow(ProjectAgentViews.Event row) {
        Map<?, ?> payload = row.payload() instanceof Map<?, ?> value ? value : Map.of();
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        safe.put("status", string(payload.get("status"), "UNKNOWN"));
        safe.put("retryable", Boolean.TRUE.equals(payload.get("retryable")));
        safe.put("extracted", count(payload.get("extracted")));
        safe.put("saved", count(payload.get("saved")));
        String errorType = string(payload.get("errorType"), null);
        if (errorType != null) safe.put("errorType", errorType);
        return new ProjectAgentViews.Event(row.seq(), row.type(), safe, row.createdAt());
    }

    private static int count(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String string(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }
}
