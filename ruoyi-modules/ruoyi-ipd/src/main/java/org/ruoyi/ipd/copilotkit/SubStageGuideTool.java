package org.ruoyi.ipd.copilotkit;

import org.ruoyi.ipd.vo.SubStageView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小阶段引导 AG-UI 工具（Track A4）：把「小阶段 × 动作 × pm-skill 引导卡」翻译为 AG-UI 事件序列。
 *
 * <p>复用 {@link AgUiFrameTranslator} 四帧翻译：onDelta → 文本开场（TEXT_MESSAGE_*），
 * onDone{card} → TOOL_CALL_START/ARGS/END/RESULT 组 + RUN_FINISHED；STATE_DELTA 进度补丁
 * 插在 RUN_FINISHED 前（RUN_* 成对与末帧契约不破，见 AgUiFrameTranslator javadoc）。
 *
 * <p>纯函数、无状态、不落存储（与 AgUiCopilotRun 同一 C08 红线）；不改 AgUiEvents/AgUiFrameTranslator。
 */
public final class SubStageGuideTool {

    /** AG-UI 工具名（TOOL_CALL_START toolCallName = card.type，与前端工具注册名一致）。 */
    public static final String TOOL_NAME = "sub-stage.guide";
    /** 卡片协议版本（TOOL_CALL_RESULT content={version,sourceRefs}）。 */
    public static final int CARD_VERSION = 1;

    private SubStageGuideTool() {
    }

    /** 卡片帧载荷（AgUiFrameTranslator.onDone 的 card 键契约：{type,version,data,sourceRefs}）。 */
    public static Map<String, Object> cardFrame(SubStageView guide, List<String> sourceRefs) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", TOOL_NAME);
        card.put("version", CARD_VERSION);
        card.put("data", guide);
        card.put("sourceRefs", sourceRefs == null ? List.of() : sourceRefs);
        return card;
    }

    /**
     * 翻译一次引导下发为完整 AG-UI 事件序列：
     * [RUN_STARTED, TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END,
     *  TOOL_CALL_START, TOOL_CALL_ARGS, TOOL_CALL_END, TOOL_CALL_RESULT, (STATE_DELTA), RUN_FINISHED]。
     *
     * @param threadId      AG-UI 线程 ID
     * @param runId         AG-UI run ID
     * @param introText     开场引导文本（TEXT_MESSAGE_* 承载）
     * @param guide         小阶段引导卡（card.data）
     * @param sourceRefs    来源引用（card.sourceRefs，形如 ipd_sub_stage/CONCEPT-S1）
     * @param progressPatch RFC 6902 进度补丁；null/空则不发 STATE_DELTA
     */
    public static List<Map<String, Object>> translateGuide(String threadId, String runId, String introText,
                                                           SubStageView guide, List<String> sourceRefs,
                                                           List<Object> progressPatch) {
        AgUiFrameTranslator tx = new AgUiFrameTranslator(threadId, runId);
        List<Map<String, Object>> out = new ArrayList<>(tx.onDelta(introText));
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", "ok");
        done.put("card", cardFrame(guide, sourceRefs));
        out.addAll(tx.onDone(done));
        if (progressPatch != null && !progressPatch.isEmpty()) {
            // RUN_FINISHED 恒为末帧（AgUiFrameTranslator 契约）——STATE_DELTA 插在其前
            out.add(out.size() - 1, AgUiEvents.stateDelta(progressPatch));
        }
        return out;
    }
}
