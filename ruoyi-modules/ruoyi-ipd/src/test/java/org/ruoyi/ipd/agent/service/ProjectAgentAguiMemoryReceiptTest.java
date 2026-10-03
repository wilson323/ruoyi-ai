package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 长期记忆写入回执（{@code MEMORY_RECEIPT}）的投影契约。
 *
 * <p>兄弟会话把「记忆抽取失败」从「整轮标失败」改成「整轮正常收尾 + 另发一条回执」。
 * 回执写进事件表后必须能被投影成对外可见的信号，否则等于把「给用户一个错误的失败提示」
 * 换成了「记忆没存上这件事对用户静默消失」。
 */
@Tag("dev")
class ProjectAgentAguiMemoryReceiptTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectAgentAguiProjection projection = new ProjectAgentAguiProjection(mapper);

    private ProjectAgentViews.Event receiptRow(long seq, String status, boolean retryable,
            Integer extracted, Integer saved, String errorType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("retryable", retryable);
        payload.put("extracted", extracted);
        payload.put("saved", saved);
        payload.put("errorType", errorType);
        return new ProjectAgentViews.Event(seq, "MEMORY_RECEIPT", payload, "2026-10-03T00:00:00Z");
    }

    private com.fasterxml.jackson.databind.JsonNode nativeFrame(List<String> frames, String name) {
        return frames.stream().map(json -> {
                try { return mapper.readTree(json); } catch (java.io.IOException e) { throw new AssertionError(e); }
            })
            .filter(node -> "CUSTOM".equals(node.path("type").asText()) && name.equals(node.path("name").asText()))
            .findFirst().orElse(null);
    }

    /** 抽取失败：状态、错误类别、可重试标记必须出现在对外帧里，且不能污染成运行失败。 */
    @Test void failedReceiptIsProjectedAsItsOwnSignalWithRetrySemantics() throws Exception {
        var frames = projection.encode("42", receiptRow(77, "WRITE_FAILED", true, 0, 0, "TimeoutException"));

        var signal = nativeFrame(frames, "ipd.memory_receipt");
        assertNotNull(signal, "记忆回执必须有独立对外信号，否则抽取失败对用户静默消失");
        assertEquals(77, signal.path("value").path("seq").asLong());
        var value = signal.path("value").path("payload");
        assertEquals("WRITE_FAILED", value.path("status").asText());
        assertTrue(value.path("retryable").asBoolean());
        assertEquals("TimeoutException", value.path("errorType").asText());

        // 回执是事后副作用记账，不得改写业务终态：不能出现任何 RUN_ERROR 帧。
        for (String frame : frames) {
            assertFalse(frame.contains("RUN_ERROR"), "记忆抽取失败不得再冒充模型输出中断");
            assertFalse(frame.contains("RUN_FINISHED"), "记忆抽取失败不得触发运行收尾帧");
        }
    }

    /** 抽取成功：同样要有对外信号，否则「存上了」也无从确认，两种状态前端无法区分。 */
    @Test void writtenReceiptIsProjectedSoSuccessAndFailureAreDistinguishable() throws Exception {
        var frames = projection.encode("42", receiptRow(78, "WRITTEN", false, 3, 2, null));

        var signal = nativeFrame(frames, "ipd.memory_receipt");
        assertNotNull(signal, "成功回执也要有对外信号，前端需据此区分存上/没存上");
        var value = signal.path("value").path("payload");
        assertEquals("WRITTEN", value.path("status").asText());
        assertFalse(value.path("retryable").asBoolean());
        assertEquals(3, value.path("extracted").asInt());
        assertEquals(2, value.path("saved").asInt());
    }

    /**
     * 回执行本身不能被投影丢弃：既有的 {@code ipd_event} 业务行是 SSE 游标与重连依据，
     * 任何事件类型都必须在其中出现。
     */
    @Test void receiptRowStillDeliversTheBusinessCursorRow() throws Exception {
        var frames = projection.encode("42", receiptRow(79, "WRITE_FAILED", true, 0, 0, "TimeoutException"));

        var cursor = nativeFrame(frames, "ipd_event");
        assertNotNull(cursor, "回执行必须保留 ipd_event 业务行，否则游标推进会断帧");
        assertEquals("MEMORY_RECEIPT", cursor.path("value").path("type").asText());
        assertEquals(79, cursor.path("value").path("seq").asLong());
        // 原生信号在前、业务行在后：游标帧仍是本行最后一帧。
        assertEquals("ipd_event", mapper.readTree(frames.get(frames.size() - 1)).path("name").asText());
    }

    /** 载荷只含状态/类别/计数，不得把异常原文或对话正文带出去。 */
    @Test void receiptProjectionCarriesNoRawFailureOrConversationBody() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("status", "WRITE_FAILED");
        payload.put("retryable", true);
        payload.put("errorType", "TimeoutException");
        payload.put("message", "conversation-body-should-never-be-persisted");
        var row = new ProjectAgentViews.Event(80, "MEMORY_RECEIPT", payload, "now");

        for (String frame : projection.encode("42", row)) {
            assertFalse(frame.contains("conversation-body-should-never-be-persisted"),
                "记忆回执不得把对话正文或异常原文投递给客户端");
        }
    }

    /** 其它事件类型的既有行为不得被本次改动影响。 */
    @Test void unrelatedEventTypesKeepTheirExistingShape() throws Exception {
        var source = projection.encode("42", new ProjectAgentViews.Event(9, "SOURCE",
            Map.of("title", "材料"), "now"));
        assertEquals(1, source.size(), "SOURCE 只应产出 ipd_event 业务行");
        assertEquals("ipd_event", mapper.readTree(source.get(0)).path("name").asText());

        var step = projection.encode("42", new ProjectAgentViews.Event(10, "STEP", Map.of("kind", "MODEL_CALL"), "now"));
        assertEquals(1, step.size(), "非 AGUI 的 STEP 同样只产出业务行");
        assertEquals("ipd_event", mapper.readTree(step.get(0)).path("name").asText());
    }
}
