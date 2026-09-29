package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.vo.AdvanceGateView;
import org.ruoyi.ipd.vo.GuideSequenceView;
import org.ruoyi.ipd.vo.GuideStepView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SubStageGuideOrchestratorTest {

    @Mock private IpdSubStageService subStageService;
    @Mock private IpdActionSkillMapService skillMapService;
    @Mock private SubStageGateService gateService;
    @Mock private StageActionMapper stageActionMapper;
    @InjectMocks private SubStageGuideOrchestrator orchestrator;

    private static IpdSubStage subStage(String code, String name, String stage, int sort, String resident) {
        IpdSubStage s = new IpdSubStage();
        s.setCode(code); s.setName(name); s.setStageCode(stage); s.setSortOrder(sort); s.setIsResident(resident);
        return s;
    }

    private static IpdActionSkillMap map(long id, String action, String subStage, int sort) {
        return IpdActionSkillMap.builder().id(id).actionCode(action).subStageCode(subStage).sortOrder(sort).build();
    }

    @Test
    @DisplayName("buildSequence：步骤按 sortOrder 升序、话术/降级齐全、stepState 四态映射")
    void buildsOrderedStepsWithPromptAndState() {
        when(subStageService.getByCode("CONCEPT-S1"))
            .thenReturn(subStage("CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0"));
        when(subStageService.listAll()).thenReturn(List.of(
            subStage("CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0"),
            subStage("CONCEPT-S2", "竞争与客群", "CONCEPT", 2, "0"),
            subStage("KPI-S1", "共担KPI归集", "KPI", 99, "1")));
        when(skillMapService.listBySubStage("CONCEPT-S1"))
            .thenReturn(List.of(map(1L, "C01", "CONCEPT-S1", 1), map(2L, "C05", "CONCEPT-S1", 2)));
        StageAction done = new StageAction();
        done.setActionCode("C01"); done.setStatus("DONE");
        when(stageActionMapper.selectList(any())).thenReturn(List.of(done));
        when(gateService.pendingBlockingActions(anyLong(), anyString())).thenReturn(List.of("C12"));

        GuideSequenceView seq = orchestrator.buildSequence(7L, "CONCEPT-S1");

        assertThat(seq.steps()).extracting(GuideStepView::actionCode).containsExactly("C01", "C05");
        assertThat(seq.steps().get(0).stepState()).isEqualTo("DONE");
        assertThat(seq.steps().get(1).stepState()).isEqualTo("NOT_INSTANTIATED"); // 缺行
        assertThat(seq.steps().get(0).guidePrompt()).isNotBlank();
        assertThat(seq.steps().get(0).degradedSteps()).isNotEmpty();              // C3 降级
        assertThat(seq.steps().get(1).skillNames()).isEmpty();                    // NONE 动作
        assertThat(seq.advanceGate().nextSubStageCode()).isEqualTo("CONCEPT-S2");
        assertThat(seq.advanceGate().advanceAllowed()).isFalse();                 // C12 未完成
        assertThat(seq.advanceGate().pendingBlockingCodes()).containsExactly("C12");
        assertThat(seq.introText()).contains("2 个动作").contains("推进已阻断");
    }

    @Test
    @DisplayName("HISTORICAL_MISSING 优先于 status；KPI-S1 无推进目标")
    void historyMissingAndResidentSemantics() {
        StageAction hist = new StageAction();
        hist.setActionCode("C01"); hist.setStatus("DONE"); hist.setHistoryMark("HISTORICAL_MISSING");
        assertThat(SubStageGuideOrchestrator.stepStateOf(hist)).isEqualTo("HISTORICAL_MISSING");
        assertThat(SubStageGuideOrchestrator.stepStateOf(null)).isEqualTo("NOT_INSTANTIATED");
    }

    @Test
    @DisplayName("C2 验收补例：stepState 其余分支——NA→NA、status 空→PENDING（四分支映射全覆盖）")
    void stepStateNaAndPendingBranches() {
        StageAction na = new StageAction();
        na.setActionCode("C01"); na.setStatus("NA");
        assertThat(SubStageGuideOrchestrator.stepStateOf(na)).isEqualTo("NA");
        StageAction blank = new StageAction();
        blank.setActionCode("C01");
        assertThat(SubStageGuideOrchestrator.stepStateOf(blank)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("C2 验收补例：缺话术/脏数据码 fail-loud（50001，脏码无法进入序列——不变量⑤）")
    void missingScriptFailsLoud() {
        when(subStageService.getByCode("CONCEPT-S1"))
            .thenReturn(subStage("CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0"));
        when(subStageService.listAll()).thenReturn(List.of(
            subStage("CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0"),
            subStage("CONCEPT-S2", "竞争与客群", "CONCEPT", 2, "0")));
        when(skillMapService.listBySubStage("CONCEPT-S1"))
            .thenReturn(List.of(map(1L, "A01", "CONCEPT-S1", 1)));

        assertThatThrownBy(() -> orchestrator.buildSequence(7L, "CONCEPT-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND);
                assertThat(ex.getMessage()).contains("引导话术不存在");
            });
    }
}
