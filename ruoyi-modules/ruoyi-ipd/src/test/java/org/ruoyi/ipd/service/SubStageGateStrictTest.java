package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class SubStageGateStrictTest {

    @Test
    void newProjectRejectsMissingBlockingActionWhileLegacyKeepsHistoricalExemption() {
        IpdSubStageService catalog = mock(IpdSubStageService.class);
        IpdActionSkillMapService maps = mock(IpdActionSkillMapService.class);
        StageActionMapper actions = mock(StageActionMapper.class);
        when(catalog.listAll()).thenReturn(List.of(
            IpdSubStage.builder().code("CONCEPT-S1").isResident("0").build(),
            IpdSubStage.builder().code("CONCEPT-S2").isResident("0").build()));
        when(maps.listAll()).thenReturn(List.of(
            IpdActionSkillMap.builder().actionCode("C01").subStageCode("CONCEPT-S1").build()));
        when(actions.selectList(any())).thenReturn(List.of());
        SubStageGateService gate = new SubStageGateService(catalog, maps, actions);

        assertThatThrownBy(() -> gate.assertAdvanceAllowed(1001L, "CONCEPT-S2", true))
            .isInstanceOfSatisfying(IpdBusinessException.class, e -> {
                assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
                assertThat(e.getMessage()).contains("C01(缺行)");
            });
        assertThatCode(() -> gate.assertAdvanceAllowed(1001L, "CONCEPT-S2", false))
            .doesNotThrowAnyException();
    }

    @Test
    void historicalMissingMarkerDoesNotExemptNewProject() {
        IpdSubStageService catalog = mock(IpdSubStageService.class);
        IpdActionSkillMapService maps = mock(IpdActionSkillMapService.class);
        StageActionMapper actions = mock(StageActionMapper.class);
        when(catalog.listAll()).thenReturn(List.of(
            IpdSubStage.builder().code("CONCEPT-S1").isResident("0").build(),
            IpdSubStage.builder().code("CONCEPT-S2").isResident("0").build()));
        when(maps.listAll()).thenReturn(List.of(
            IpdActionSkillMap.builder().actionCode("C01").subStageCode("CONCEPT-S1").build()));
        when(actions.selectList(any())).thenReturn(List.of(
            StageAction.builder().projectId(1001L).actionCode("C01")
                .isBlocking("1").status("PENDING").historyMark("HISTORICAL_MISSING").build()));
        SubStageGateService gate = new SubStageGateService(catalog, maps, actions);

        assertThatThrownBy(() -> gate.assertAdvanceAllowed(1001L, "CONCEPT-S2", true))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED));
        assertThatCode(() -> gate.assertAdvanceAllowed(1001L, "CONCEPT-S2", false))
            .doesNotThrowAnyException();
    }
}
