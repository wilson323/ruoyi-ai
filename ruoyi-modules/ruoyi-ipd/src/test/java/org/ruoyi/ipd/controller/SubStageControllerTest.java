package org.ruoyi.ipd.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.IpdActionSkillMapService;
import org.ruoyi.ipd.service.IpdSubStageService;
import org.ruoyi.ipd.service.SubStageGateService;
import org.ruoyi.ipd.service.SubStageGuideOrchestrator;
import org.ruoyi.ipd.vo.ActionSkillView;
import org.ruoyi.ipd.vo.AdvanceGateView;
import org.ruoyi.ipd.vo.GuideSequenceView;
import org.ruoyi.ipd.vo.GuideStepView;
import org.ruoyi.ipd.vo.SubStageView;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SubStageControllerTest {

    private static final IpdActor ACTOR = new IpdActor(11L, "超管", "SUPER_ADMIN", 900001L);

    @Mock
    private IpdSubStageService subStageService;
    @Mock
    private IpdActionSkillMapService skillMapService;
    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private SubStageGateService subStageGateService;
    @Mock
    private SubStageGuideOrchestrator guideOrchestrator;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @InjectMocks
    private SubStageController controller;

    @Test
    @DisplayName("项目级引导先校验在职成员，未知项目不得读取进度")
    void guideEventsRejectsInvisibleProjectBeforeProgressRead() {
        when(ipdPermission.requireInternal())
            .thenReturn(new IpdActor(12L, "市场PM-乙", "MARKET_PM", 900001L));

        assertThatThrownBy(() -> controller.guideEvents("CONCEPT-S1", 1001L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class);
        verifyNoInteractions(subStageService, guideOrchestrator);
    }

    @Test
    @DisplayName("A3：list 返回 code0/message 包络 + 小阶段归并动作技能映射 + 字符串 ID")
    void listMergesSubStagesAndSkillMaps() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(subStageService.listAll()).thenReturn(List.of(
            IpdSubStage.builder().id(1846876543210987521L).code("CONCEPT-S1").name("市场洞察")
                .stageCode("CONCEPT").sortOrder(1).isGate("0").gateCode(null)
                .skillHint("pm-product-discovery + pm-market-research").isResident("0")
                .ownerRole("MARKET_PM").build(),
            IpdSubStage.builder().id(1846876543210987522L).code("CONCEPT-S2").name("竞争与客群")
                .stageCode("CONCEPT").sortOrder(2).isGate("0").gateCode(null)
                .skillHint("pm-market-research").isResident("0").ownerRole("MARKET_PM").build()));
        when(skillMapService.listAll()).thenReturn(List.of(
            IpdActionSkillMap.builder().id(1846876543210987601L).actionCode("C01")
                .subStageCode("CONCEPT-S1").skillNames("[\"interview-script\",\"market-sizing\"]")
                .sortOrder(1).build(),
            IpdActionSkillMap.builder().id(1846876543210987602L).actionCode("C02")
                .subStageCode("CONCEPT-S2").skillNames(null).sortOrder(1).build()));

        ApiV1Response<List<SubStageView>> resp = controller.list();

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getMessage()).isEqualTo("ok");
        List<SubStageView> data = resp.getData();
        assertThat(data).hasSize(2);
        assertThat(data.get(0).id()).isEqualTo("1846876543210987521");
        assertThat(data.get(0).code()).isEqualTo("CONCEPT-S1");
        List<ActionSkillView> actions = data.get(0).actions();
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).actionCode()).isEqualTo("C01");
        assertThat(actions.get(0).actionName()).isEqualTo("市场机会与痛点调研");
        assertThat(actions.get(0).skillNames()).containsExactly("interview-script", "market-sizing");
        // skill_names NULL → 空列表（§3 未定稿口径），不回 null
        assertThat(data.get(1).actions().get(0).skillNames()).isEmpty();
    }

    @Test
    @DisplayName("A3：小阶段无映射行时动作列表为空而非 null")
    void subStageWithoutMapsYieldsEmptyActions() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(subStageService.listAll()).thenReturn(List.of(
            IpdSubStage.builder().id(1846876543210987523L).code("KPI-S1").name("共担KPI归集（常驻）")
                .stageCode("KPI").sortOrder(99).isGate("0").gateCode(null)
                .skillHint("pm-data-analytics").isResident("1").ownerRole("GROUP_LEADER").build()));
        when(skillMapService.listAll()).thenReturn(List.of());

        ApiV1Response<List<SubStageView>> resp = controller.list();

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getData().get(0).actions()).isEmpty();
    }

    @Test
    @DisplayName("A4：guide-events 返回 AG-UI 事件序列并带 sourceRefs")
    void guideEventsBuildsAgUiSequence() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(subStageService.listAll()).thenReturn(List.of(
            IpdSubStage.builder().id(1846876543210987521L).code("CONCEPT-S1").name("市场洞察")
                .stageCode("CONCEPT").sortOrder(1).isGate("0").gateCode(null)
                .skillHint("pm-product-discovery + pm-market-research").isResident("0")
                .ownerRole("MARKET_PM").build()));
        when(skillMapService.listAll()).thenReturn(List.of(
            IpdActionSkillMap.builder().id(1846876543210987601L).actionCode("C01")
                .subStageCode("CONCEPT-S1").skillNames(null).sortOrder(1).build()));
        when(guideOrchestrator.buildSequence(1001L, "CONCEPT-S1")).thenReturn(sequenceFixture());

        ApiV1Response<List<Map<String, Object>>> resp = controller.guideEvents("CONCEPT-S1", 1001L);

        assertThat(resp.getCode()).isEqualTo(0);
        List<Map<String, Object>> events = resp.getData();
        assertThat(events.get(0).get("type")).isEqualTo("RUN_STARTED");
        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("RUN_FINISHED");
        String content = String.valueOf(events.stream()
            .filter(e -> "TOOL_CALL_RESULT".equals(e.get("type"))).findFirst().orElseThrow()
            .get("content"));
        assertThat(content).contains("ipd_sub_stage/CONCEPT-S1", "ipd_action_skill_map/C01");
    }

    @Test
    @DisplayName("C2：guide-events STATE_DELTA op.value 含 guideSteps/advanceGate 两新键，既有字段与事件序不变")
    @SuppressWarnings("unchecked")
    void guideEventsCarriesGuideStepsAndGate() throws Exception {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(subStageService.listAll()).thenReturn(List.of(
            IpdSubStage.builder().id(1846876543210987521L).code("CONCEPT-S1").name("市场洞察")
                .stageCode("CONCEPT").sortOrder(1).isGate("0").gateCode(null)
                .skillHint("pm-product-discovery").isResident("0").ownerRole("MARKET_PM").build()));
        when(skillMapService.listAll()).thenReturn(List.of(
            IpdActionSkillMap.builder().id(1846876543210987601L).actionCode("C01")
                .subStageCode("CONCEPT-S1").skillNames(null).sortOrder(1).build()));
        when(guideOrchestrator.buildSequence(7L, "CONCEPT-S1")).thenReturn(sequenceFixture());

        ApiV1Response<List<Map<String, Object>>> resp = controller.guideEvents("CONCEPT-S1", 7L);
        List<Map<String, Object>> events = resp.getData();

        // 事件序合同：RUN_STARTED → TEXT_MESSAGE_* → TOOL_CALL_* → STATE_DELTA → RUN_FINISHED
        assertThat(events.get(0).get("type")).isEqualTo("RUN_STARTED");
        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("RUN_FINISHED");
        int state = indexOf(events, "STATE_DELTA");
        assertThat(state).isGreaterThan(indexOf(events, "TOOL_CALL_RESULT")).isPositive();
        assertThat(state).isLessThan(events.size() - 1);

        Map<String, Object> stateEvent = events.get(state);
        List<Map<String, Object>> patch = (List<Map<String, Object>>) stateEvent.get("delta");
        Map<String, Object> op = patch.get(0);
        assertThat(op.get("op")).isEqualTo("add");
        assertThat(op.get("path")).isEqualTo("/subStageGuide");
        Map<String, Object> value = (Map<String, Object>) op.get("value");
        // 既有字段不变 + 两新键
        assertThat(value).containsEntry("subStageCode", "CONCEPT-S1").containsEntry("projectId", "7");
        assertThat(value).containsKeys("guideSteps", "advanceGate");
        // wire Map 断言（事件经官方 encoder 序列化，嵌套载荷与线格式同构为 Map）
        List<Map<String, Object>> steps = (List<Map<String, Object>>) value.get("guideSteps");
        assertThat(steps).isNotEmpty();
        assertThat(steps.get(0).get("actionCode")).isEqualTo("C01");
        Map<String, Object> gate = (Map<String, Object>) value.get("advanceGate");
        assertThat(gate.get("nextSubStageCode")).isEqualTo("CONCEPT-S2");
        assertThat(gate.get("advanceAllowed")).isEqualTo(false);
        assertThat((List<String>) gate.get("pendingBlockingCodes")).containsExactly("C12");
        // 线格式 JSON 形状自证（两新键真实序列化下发）
        String json = new ObjectMapper().writeValueAsString(value);
        assertThat(json).contains("\"guideSteps\"").contains("\"advanceGate\"")
            .contains("\"subStageCode\":\"CONCEPT-S1\"").contains("\"pendingBlockingCodes\"");
    }

    /** 编排器载荷夹具（话术/降级由 SubStageGuideOrchestratorTest 锁定，控制器只透传）。 */
    private static GuideSequenceView sequenceFixture() {
        GuideStepView step = new GuideStepView("C01", "市场机会与痛点调研", 1,
            "BIND", "AI_GENERATE", List.of("interview-script"), List.of("/interview prep"),
            List.of(), "先用 JTBD/Mom Test 提纲做痛点访谈。", "PENDING", true);
        AdvanceGateView gate = new AdvanceGateView("CONCEPT-S2", false, List.of("C12"));
        return new GuideSequenceView("CONCEPT-S1", "市场洞察", "CONCEPT",
            "小阶段「市场洞察」共 1 个动作；推进已阻断：待完成阻断动作 C12。", List.of(step), gate);
    }

    private static int indexOf(List<Map<String, Object>> events, String type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.equals(events.get(i).get("type"))) {
                return i;
            }
        }
        return -1;
    }
}
