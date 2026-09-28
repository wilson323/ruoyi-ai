package org.ruoyi.ipd.copilotkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CopilotKit AG-UI 桥（2026-09-28）：四帧（meta/delta/done/error）→ AG-UI 事件序列映射单测。
 * 样本即既有 ai-copilot 流式链的真实帧语义（见 AiCopilotController 帧构造），逐事件断言顺序与字段。
 */
@Tag("dev")
@DisplayName("CopilotKit AG-UI 桥：四帧 → AG-UI 事件映射")
class AgUiFrameTranslatorTest {

    private static List<String> types(List<Map<String, Object>> events) {
        List<String> t = new ArrayList<>();
        for (Map<String, Object> e : events) {
            t.add(String.valueOf(e.get("type")));
        }
        return t;
    }

    private static Map<String, Object> donePlain() {
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", "ok");
        done.put("tokenPrompt", 10);
        done.put("tokenCompletion", 6);
        done.put("latencyMs", 120L);
        return done;
    }

    @Test
    @DisplayName("meta → RUN_STARTED（threadId/runId 透传）")
    void metaMapsToRunStarted() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<Map<String, Object>> out = tx.onMeta();
        assertEquals(List.of("RUN_STARTED"), types(out));
        assertEquals("t-1", out.get(0).get("threadId"));
        assertEquals("r-1", out.get(0).get("runId"));
    }

    @Test
    @DisplayName("delta×3 + done：TEXT_MESSAGE_START + CONTENT×3 + END + RUN_FINISHED（messageId 同一）")
    void deltaAndDoneMapToTextMessageTriad() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<Map<String, Object>> all = new ArrayList<>(tx.onMeta());
        all.addAll(tx.onDelta("你好"));
        all.addAll(tx.onDelta("，我是"));
        all.addAll(tx.onDelta("副驾"));
        all.addAll(tx.onDone(donePlain()));

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
            "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED"), types(all));
        Object msgId = all.get(1).get("messageId");
        assertEquals(msgId, all.get(2).get("messageId"));
        assertEquals(msgId, all.get(4).get("messageId"));
        assertEquals(msgId, all.get(5).get("messageId"));
        assertEquals("assistant", all.get(1).get("role"));
        assertEquals("副驾", all.get(4).get("delta"));
        assertTrue(tx.isFinished());
    }

    @Test
    @DisplayName("done+fillPayload（R221 对话即填表）：END → STATE_DELTA(op=add,path=/fillPayload) → RUN_FINISHED")
    void doneWithFillPayloadMapsToStateDelta() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<Map<String, Object>> all = new ArrayList<>(tx.onMeta());
        all.addAll(tx.onDelta("已准备 1 个字段建议"));
        Map<String, Object> done = donePlain();
        done.put("fillPayload", Map.of("scene", "stage-action-fields",
            "fields", Map.of("certNo", "C-1"), "mode", "suggest"));
        all.addAll(tx.onDone(done));

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
            "TEXT_MESSAGE_END", "STATE_DELTA", "RUN_FINISHED"), types(all));
        Map<String, Object> stateDelta = all.get(4);
        List<?> patch = (List<?>) stateDelta.get("delta");
        assertEquals(1, patch.size());
        Map<?, ?> op = (Map<?, ?>) patch.get(0);
        assertEquals("add", op.get("op"));
        assertEquals("/fillPayload", op.get("path"));
        Map<?, ?> value = (Map<?, ?>) op.get("value");
        assertEquals("stage-action-fields", value.get("scene"));
    }

    @Test
    @DisplayName("done+card（P2-01 四键）：END → TOOL_CALL_START(name=card.type)+ARGS(data JSON)+END+RESULT → RUN_FINISHED")
    void doneWithCardMapsToToolCallSequence() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<Map<String, Object>> all = new ArrayList<>(tx.onMeta());
        all.addAll(tx.onDelta("生成卡片"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "TR2 评审");
        data.put("count", 3);
        Map<String, Object> done = donePlain();
        done.put("card", Map.of("type", "ipd.task_card", "version", 1,
            "data", data, "sourceRefs", Map.of("projectId", 7)));
        all.addAll(tx.onDone(done));

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
            "TEXT_MESSAGE_END", "TOOL_CALL_START", "TOOL_CALL_ARGS",
            "TOOL_CALL_END", "TOOL_CALL_RESULT", "RUN_FINISHED"), types(all));
        String toolCallId = (String) all.get(4).get("toolCallId");
        assertEquals("ipd.task_card", all.get(4).get("toolCallName"));
        assertEquals(toolCallId, all.get(5).get("toolCallId"));
        assertEquals(toolCallId, all.get(6).get("toolCallId"));
        assertEquals(toolCallId, all.get(7).get("toolCallId"));
        // TOOL_CALL_ARGS delta = card.data 的 JSON；TOOL_CALL_RESULT content = {version,sourceRefs} 的 JSON（契约 §4.3）
        String argsJson = (String) all.get(5).get("delta");
        assertTrue(argsJson.contains("\"title\":\"TR2 评审\""), argsJson);
        assertEquals("{\"version\":1,\"sourceRefs\":{\"projectId\":7}}", all.get(7).get("content"));
        assertEquals("tool", all.get(7).get("role"));
    }

    @Test
    @DisplayName("done 同时带 card+fillPayload（契约 §4.2 叠加）：TOOL_CALL_* 组 → STATE_DELTA → RUN_FINISHED（不互斥）")
    void doneWithCardAndFillPayloadStacksInContractOrder() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<Map<String, Object>> all = new ArrayList<>(tx.onMeta());
        all.addAll(tx.onDelta("卡片 + 填表"));
        Map<String, Object> done = donePlain();
        done.put("card", Map.of("type", "ipd.task_card", "version", 2,
            "data", Map.of("title", "T"), "sourceRefs", Map.of("projectId", 9)));
        done.put("fillPayload", Map.of("scene", "stage-action-fields",
            "fields", Map.of("certNo", "C-2"), "mode", "suggest"));
        all.addAll(tx.onDone(done));

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
            "TEXT_MESSAGE_END", "TOOL_CALL_START", "TOOL_CALL_ARGS",
            "TOOL_CALL_END", "TOOL_CALL_RESULT", "STATE_DELTA", "RUN_FINISHED"), types(all));
        assertEquals("{\"version\":2,\"sourceRefs\":{\"projectId\":9}}", all.get(7).get("content"));
        Map<String, Object> stateDelta = all.get(8);
        List<?> patch = (List<?>) stateDelta.get("delta");
        assertEquals("/fillPayload", ((Map<?, ?>) patch.get(0)).get("path"));
    }

    @Test
    @DisplayName("error → RUN_ERROR（code/message 透传）；error 先于 meta 也补 RUN_STARTED 闭合 run 边界")
    void errorMapsToRunError() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<Map<String, Object>> all = new ArrayList<>(tx.onMeta());
        all.addAll(tx.onError("50001", "项目不可见"));
        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), types(all));
        assertEquals("50001", all.get(1).get("code"));
        assertEquals("项目不可见", all.get(1).get("message"));

        AgUiFrameTranslator tx2 = new AgUiFrameTranslator("t-1", "r-1");
        List<Map<String, Object>> early = tx2.onError("20001", "未认证或凭证失效");
        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), types(early));
    }

    @Test
    @DisplayName("FILL_PAGE 空 answer：无 TEXT_MESSAGE_* 直接 STATE_DELTA + RUN_FINISHED（不发空文本三段式）")
    void doneWithoutDeltaEmitsNoTextFrames() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<Map<String, Object>> all = new ArrayList<>(tx.onMeta());
        Map<String, Object> done = donePlain();
        done.put("fillPayload", Map.of("scene", "stage-action-fields", "fields", Map.of(), "mode", "suggest"));
        all.addAll(tx.onDone(done));
        assertEquals(List.of("RUN_STARTED", "STATE_DELTA", "RUN_FINISHED"), types(all));
    }
}
