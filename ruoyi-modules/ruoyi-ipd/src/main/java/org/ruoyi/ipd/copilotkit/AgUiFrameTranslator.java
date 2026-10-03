package org.ruoyi.ipd.copilotkit;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CopilotKit AG-UI 桥（2026-09-28，单轨融合；2026-10-02 载体换官方 record）：四帧（meta/delta/done/error）
 * → AG-UI 事件翻译器。
 *
 * <p>映射语义（与前端 {@code ruoyi-ipd-web/docs/copilotkit单轨融合契约-20260928.md} 锁定）：
 * <ul>
 *   <li>{@code meta} → RUN_STARTED；</li>
 *   <li>{@code delta} → 首个 delta 前发 TEXT_MESSAGE_START（messageId 生成），每个 delta 一个 TEXT_MESSAGE_CONTENT；</li>
 *   <li>{@code done}+fillPayload → TEXT_MESSAGE_END（若文本已开）→ STATE_DELTA（payload 映射 state）→ RUN_FINISHED；</li>
 *   <li>{@code done}+card{type,version,data,sourceRefs} → TEXT_MESSAGE_END（若文本已开）→
 *       TOOL_CALL_START(name=card.type) + TOOL_CALL_ARGS(data JSON) + TOOL_CALL_END
 *       + TOOL_CALL_RESULT(content=JSON({version,sourceRefs}))；</li>
 *   <li>done 同时带 card 与 fillPayload 时二者<b>叠加</b>：TOOL_CALL_* 组 → STATE_DELTA → RUN_FINISHED
 *       （顺序锁定契约 §4.2 对账表）；</li>
 *   <li>{@code error} → RUN_ERROR。</li>
 * </ul>
 *
 * <p>AG-UI 官方契约：RUN_STARTED 与 RUN_FINISHED/RUN_ERROR 必须成对构成 run 边界——本翻译器
 * <b>惰性发 RUN_STARTED</b>（首个语义帧前补发），保证 error 先于 meta 的路径（鉴权/参数拒绝）也合法闭合。
 *
 * <p>纯函数式、每 run 一个实例（状态：messageId/文本开合/run 边界），不落任何存储（C08 红线）。
 */
public class AgUiFrameTranslator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String threadId;
    private final String runId;

    private boolean runStarted;
    private boolean textOpen;
    private boolean finished;
    private String messageId;

    public AgUiFrameTranslator(String threadId, String runId) {
        this.threadId = threadId;
        this.runId = runId;
    }

    /** meta 帧 → RUN_STARTED（meta 自身无 AG-UI 对应字段，结构化数据已由既有链消费/前端契约承载）。 */
    public List<AguiEvent> onMeta() {
        List<AguiEvent> out = new ArrayList<>();
        ensureRunStarted(out);
        return out;
    }

    /** delta 帧 → TEXT_MESSAGE_START（首个）+ TEXT_MESSAGE_CONTENT。空段跳过（与 AiGateway 空段过滤一致）。 */
    public List<AguiEvent> onDelta(String token) {
        List<AguiEvent> out = new ArrayList<>();
        if (token == null || token.isEmpty()) {
            return out;
        }
        ensureRunStarted(out);
        if (!textOpen) {
            messageId = UUID.randomUUID().toString();
            textOpen = true;
            out.add(AgUiEvents.textMessageStart(threadId, runId, messageId));
        }
        out.add(AgUiEvents.textMessageContent(threadId, runId, messageId, token));
        return out;
    }

    /**
     * done 帧 → TEXT_MESSAGE_END（若文本已开）+ STATE_DELTA 或 TOOL_CALL_* + RUN_FINISHED。
     *
     * @param doneFrame 既有 done 帧载荷（{status,tokenPrompt,tokenCompletion,latencyMs,fillPayload?,card?}）
     */
    public List<AguiEvent> onDone(Map<String, Object> doneFrame) {
        List<AguiEvent> out = new ArrayList<>();
        ensureRunStarted(out);
        if (textOpen) {
            out.add(AgUiEvents.textMessageEnd(threadId, runId, messageId));
            textOpen = false;
        }
        Object card = doneFrame == null ? null : doneFrame.get("card");
        Object fillPayload = doneFrame == null ? null : doneFrame.get("fillPayload");
        if (card instanceof Map<?, ?> c) {
            String toolCallId = UUID.randomUUID().toString();
            Object type = c.get("type");
            out.add(AgUiEvents.toolCallStart(threadId, runId, toolCallId, type == null ? "card" : String.valueOf(type)));
            out.add(AgUiEvents.toolCallArgs(threadId, runId, toolCallId, toJson(c.get("data"))));
            out.add(AgUiEvents.toolCallEnd(threadId, runId, toolCallId));
            // 契约 §4.3：TOOL_CALL_RESULT content = JSON.stringify({version, sourceRefs})（非 data JSON）
            Map<String, Object> resultBody = new LinkedHashMap<>();
            resultBody.put("version", c.get("version"));
            resultBody.put("sourceRefs", c.get("sourceRefs"));
            out.add(AgUiEvents.toolCallResult(threadId, runId, toolCallId,
                UUID.randomUUID().toString(), toJson(resultBody)));
        }
        if (fillPayload != null) {
            out.add(AgUiEvents.stateDelta(threadId, runId,
                List.of(JsonPatchOperation.add("/fillPayload", fillPayload))));
        }
        out.add(AgUiEvents.runFinished(threadId, runId));
        finished = true;
        return out;
    }

    /** error 帧 → RUN_ERROR（code 语义沿用四帧 error 的既有错误码串，如 "50001"/"UNREACHABLE"）。 */
    public List<AguiEvent> onError(String code, String message) {
        List<AguiEvent> out = new ArrayList<>();
        ensureRunStarted(out);
        out.add(AgUiEvents.runError(threadId, runId, message, code));
        finished = true;
        return out;
    }

    /** run 是否已终结（RUN_FINISHED / RUN_ERROR 已发）。 */
    public boolean isFinished() {
        return finished;
    }

    private void ensureRunStarted(List<AguiEvent> out) {
        if (!runStarted) {
            runStarted = true;
            out.add(AgUiEvents.runStarted(threadId, runId));
        }
    }

    /** 对象 → JSON 文本（TOOL_CALL_ARGS delta=card.data / TOOL_CALL_RESULT content={version,sourceRefs}）；失败兜底空对象，不阻断流。 */
    private static String toJson(Object data) {
        if (data == null) {
            return "{}";
        }
        if (data instanceof String s) {
            return s;
        }
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
