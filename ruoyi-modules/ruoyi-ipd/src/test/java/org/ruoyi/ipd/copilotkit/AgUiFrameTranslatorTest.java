package org.ruoyi.ipd.copilotkit;

import io.agentscope.core.agui.event.AguiEvent;

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
 * CopilotKit AG-UI 桥（2026-09-28；2026-10-02 载体对齐官方 2.0.3 record）：四帧（meta/delta/done/error）
 * → AG-UI 事件序列映射单测。样本即既有 ai-copilot 流式链的真实帧语义（见 AiCopilotController 帧构造），
 * 逐事件断言顺序与字段（官方 record 访问器；wire 名 = 官方 AguiEventType name()）。
 */
@Tag("dev")
@DisplayName("CopilotKit AG-UI 桥：四帧 → AG-UI 事件映射")
class AgUiFrameTranslatorTest {

    private static List<String> types(List<AguiEvent> events) {
        List<String> t = new ArrayList<>();
        for (AguiEvent e : events) {
            t.add(e.getType().name());
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
        List<AguiEvent> out = tx.onMeta();
        assertEquals(List.of("RUN_STARTED"), types(out));
        assertEquals("t-1", out.get(0).getThreadId());
        assertEquals("r-1", out.get(0).getRunId());
    }

    @Test
    @DisplayName("delta×3 + done：TEXT_MESSAGE_START + CONTENT×3 + END + RUN_FINISHED（messageId 同一）")
    void deltaAndDoneMapToTextMessageTriad() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<AguiEvent> all = new ArrayList<>(tx.onMeta());
        all.addAll(tx.onDelta("你好"));
        all.addAll(tx.onDelta("，我是"));
        all.addAll(tx.onDelta("副驾"));
        all.addAll(tx.onDone(donePlain()));

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
            "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED"), types(all));
        AguiEvent.TextMessageStart start = (AguiEvent.TextMessageStart) all.get(1);
        String msgId = start.messageId();
        assertEquals(msgId, ((AguiEvent.TextMessageContent) all.get(2)).messageId());
        assertEquals(msgId, ((AguiEvent.TextMessageContent) all.get(4)).messageId());
        assertEquals(msgId, ((AguiEvent.TextMessageEnd) all.get(5)).messageId());
        assertEquals("assistant", start.role());
        assertEquals("副驾", ((AguiEvent.TextMessageContent) all.get(4)).delta());
        assertTrue(tx.isFinished());
    }

    @Test
    @DisplayName("done+fillPayload（R221 对话即填表）：END → STATE_DELTA(op=add,path=/fillPayload) → RUN_FINISHED")
    void doneWithFillPayloadMapsToStateDelta() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<AguiEvent> all = new ArrayList<>(tx.onMeta());
        all.addAll(tx.onDelta("已准备 1 个字段建议"));
        Map<String, Object> done = donePlain();
        done.put("fillPayload", Map.of("scene", "stage-action-fields",
            "fields", Map.of("certNo", "C-1"), "mode", "suggest"));
        all.addAll(tx.onDone(done));

        assertEquals(List.of("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
            "TEXT_MESSAGE_END", "STATE_DELTA", "RUN_FINISHED"), types(all));
        AguiEvent.StateDelta stateDelta = (AguiEvent.StateDelta) all.get(4);
        assertEquals(1, stateDelta.delta().size());
        AguiEvent.JsonPatchOperation op = stateDelta.delta().get(0);
        assertEquals("add", op.op());
        assertEquals("/fillPayload", op.path());
        Map<?, ?> value = (Map<?, ?>) op.value();
        assertEquals("stage-action-fields", value.get("scene"));
    }

    @Test
    @DisplayName("done+card（P2-01 四键）：END → TOOL_CALL_START(name=card.type)+ARGS(data JSON)+END+RESULT → RUN_FINISHED")
    void doneWithCardMapsToToolCallSequence() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<AguiEvent> all = new ArrayList<>(tx.onMeta());
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
        AguiEvent.ToolCallStart toolCallStart = (AguiEvent.ToolCallStart) all.get(4);
        String toolCallId = toolCallStart.toolCallId();
        assertEquals("ipd.task_card", toolCallStart.toolCallName());
        assertEquals(toolCallId, ((AguiEvent.ToolCallArgs) all.get(5)).toolCallId());
        assertEquals(toolCallId, ((AguiEvent.ToolCallEnd) all.get(6)).toolCallId());
        assertEquals(toolCallId, ((AguiEvent.ToolCallResult) all.get(7)).toolCallId());
        // TOOL_CALL_ARGS delta = card.data 的 JSON；TOOL_CALL_RESULT content = {version,sourceRefs} 的 JSON（契约 §4.3）
        String argsJson = ((AguiEvent.ToolCallArgs) all.get(5)).delta();
        assertTrue(argsJson.contains("\"title\":\"TR2 评审\""), argsJson);
        assertEquals("{\"version\":1,\"sourceRefs\":{\"projectId\":7}}", ((AguiEvent.ToolCallResult) all.get(7)).content());
        assertEquals("tool", ((AguiEvent.ToolCallResult) all.get(7)).role());
    }

    @Test
    @DisplayName("done 同时带 card+fillPayload（契约 §4.2 叠加）：TOOL_CALL_* 组 → STATE_DELTA → RUN_FINISHED（不互斥）")
    void doneWithCardAndFillPayloadStacksInContractOrder() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<AguiEvent> all = new ArrayList<>(tx.onMeta());
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
        assertEquals("{\"version\":2,\"sourceRefs\":{\"projectId\":9}}", ((AguiEvent.ToolCallResult) all.get(7)).content());
        AguiEvent.StateDelta stateDelta = (AguiEvent.StateDelta) all.get(8);
        assertEquals("/fillPayload", stateDelta.delta().get(0).path());
    }

    @Test
    @DisplayName("error → RUN_ERROR（code/message 透传）；error 先于 meta 也补 RUN_STARTED 闭合 run 边界")
    void errorMapsToRunError() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<AguiEvent> all = new ArrayList<>(tx.onMeta());
        all.addAll(tx.onError("50001", "项目不可见"));
        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), types(all));
        AguiEvent.RunError runError = (AguiEvent.RunError) all.get(1);
        assertEquals("50001", runError.code());
        assertEquals("项目不可见", runError.message());

        AgUiFrameTranslator tx2 = new AgUiFrameTranslator("t-1", "r-1");
        List<AguiEvent> early = tx2.onError("20001", "未认证或凭证失效");
        assertEquals(List.of("RUN_STARTED", "RUN_ERROR"), types(early));
    }

    @Test
    @DisplayName("FILL_PAGE 空 answer：无 TEXT_MESSAGE_* 直接 STATE_DELTA + RUN_FINISHED（不发空文本三段式）")
    void doneWithoutDeltaEmitsNoTextFrames() {
        AgUiFrameTranslator tx = new AgUiFrameTranslator("t-1", "r-1");
        List<AguiEvent> all = new ArrayList<>(tx.onMeta());
        Map<String, Object> done = donePlain();
        done.put("fillPayload", Map.of("scene", "stage-action-fields", "fields", Map.of(), "mode", "suggest"));
        all.addAll(tx.onDone(done));
        assertEquals(List.of("RUN_STARTED", "STATE_DELTA", "RUN_FINISHED"), types(all));
    }
}
