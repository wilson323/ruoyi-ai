package org.ruoyi.ipd.copilotkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.vo.ActionSkillView;
import org.ruoyi.ipd.vo.SubStageView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class SubStageGuideToolTest {

    private static SubStageView guideFixture() {
        return new SubStageView("1846876543210987521", "CONCEPT-S1", "市场洞察", "CONCEPT", 1,
            "0", null, "pm-product-discovery + pm-market-research", "MARKET_PM",
            List.of(new ActionSkillView("C01", "市场机会与痛点调研", "CONCEPT-S1",
                List.of("interview-script"), 1)));
    }

    private static List<Object> progressPatchFixture() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("subStageCode", "CONCEPT-S1");
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("path", "/subStageGuide");
        op.put("value", value);
        List<Object> patch = new ArrayList<>();
        patch.add(op);
        return patch;
    }

    @Test
    @DisplayName("A4：事件序合同——RUN_STARTED 首帧、RUN_FINISHED 末帧、STATE_DELTA 在 TOOL_CALL_* 后 RUN_FINISHED 前")
    void translateGuideEmitsContractSequence() {
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            "thread-1", "run-1", "小阶段「市场洞察」共 1 个动作，按序执行技能引导。",
            guideFixture(), List.of("ipd_sub_stage/CONCEPT-S1", "ipd_action_skill_map/C01"),
            progressPatchFixture());

        assertThat(events).isNotEmpty();
        assertThat(events.get(0).get("type")).isEqualTo("RUN_STARTED");
        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("RUN_FINISHED");

        int start = indexOfType(events, "TOOL_CALL_START");
        int args = indexOf(events, "TOOL_CALL_ARGS");
        int end = indexOf(events, "TOOL_CALL_END");
        int result = indexOf(events, "TOOL_CALL_RESULT");
        int state = indexOf(events, "STATE_DELTA");
        int finished = indexOf(events, "RUN_FINISHED");
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(start).isLessThan(args);
        assertThat(args).isLessThan(end);
        assertThat(end).isLessThan(result);
        assertThat(result).isLessThan(state);
        assertThat(state).isLessThan(finished);
    }

    @Test
    @DisplayName("A4：TOOL_CALL_START toolCallName=sub-stage.guide，RESULT content 含 version+sourceRefs")
    void toolCallCarriesCardContract() {
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            "thread-1", "run-1", "引导开始", guideFixture(),
            List.of("ipd_sub_stage/CONCEPT-S1"), progressPatchFixture());

        Map<String, Object> start = events.get(indexOfType(events, "TOOL_CALL_START"));
        assertThat(start.get("toolCallName")).isEqualTo(SubStageGuideTool.TOOL_NAME);
        String argsJson = String.valueOf(events.get(indexOfType(events, "TOOL_CALL_ARGS")).get("delta"));
        assertThat(argsJson).contains("\"code\":\"CONCEPT-S1\"");
        String content = String.valueOf(events.get(indexOfType(events, "TOOL_CALL_RESULT")).get("content"));
        assertThat(content).contains("\"version\":" + SubStageGuideTool.CARD_VERSION);
        assertThat(content).contains("ipd_sub_stage/CONCEPT-S1");
    }

    @Test
    @DisplayName("A4：progressPatch 为空时不发 STATE_DELTA，RUN_FINISHED 仍末帧")
    void emptyProgressPatchSkipsStateDelta() {
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            "thread-1", "run-1", "引导开始", guideFixture(), List.of("ipd_sub_stage/CONCEPT-S1"), List.of());

        assertThat(indexOf(events, "STATE_DELTA")).isEqualTo(-1);
        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("RUN_FINISHED");
    }

    private static int indexOf(List<Map<String, Object>> events, String type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.equals(events.get(i).get("type"))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfType(List<Map<String, Object>> events, String type) {
        int idx = indexOf(events, type);
        assertThat(idx).as("事件存在: %s", type).isGreaterThanOrEqualTo(0);
        return idx;
    }
}
