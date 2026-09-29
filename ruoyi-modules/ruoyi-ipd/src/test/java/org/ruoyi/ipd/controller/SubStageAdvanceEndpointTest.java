package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.IpdActionSkillMapService;
import org.ruoyi.ipd.service.IpdSubStageService;
import org.ruoyi.ipd.service.SubStageGuideOrchestrator;
import org.ruoyi.ipd.service.SubStageProgressService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SubStageAdvanceEndpointTest {

    private static final IpdActor ACTOR = new IpdActor(11L, "超管", "SUPER_ADMIN", 900001L);

    @Mock
    private IpdSubStageService subStageService;
    @Mock
    private IpdActionSkillMapService skillMapService;
    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private SubStageProgressService progressService;
    @Mock
    private SubStageGuideOrchestrator guideOrchestrator;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @InjectMocks
    private SubStageController controller;

    @Test
    @DisplayName("非项目成员不得调用推进门禁")
    void advanceRejectsInvisibleProjectBeforeGate() {
        when(ipdPermission.requireInternal())
            .thenReturn(new IpdActor(12L, "市场PM-乙", "MARKET_PM", 900001L));

        assertThatThrownBy(() -> controller.advance(1001L, "CONCEPT-S2", 1L))
            .isInstanceOf(IpdBusinessException.class);
        verifyNoInteractions(progressService);
    }

    @Test
    @DisplayName("B2：持久化推进回执包含游标、版本及门禁结果")
    void advancePassesWhenGateAllows() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(progressService.advance(1001L, "CONCEPT-S2", 1L, ACTOR.id()))
            .thenReturn(new SubStageProgressService.Progress("1001", "CONCEPT", "CONCEPT-S2", 2L, "PASSED", false));

        ApiV1Response<Map<String, Object>> resp = controller.advance(1001L, "CONCEPT-S2", 1L);

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getMessage()).isEqualTo("ok");
        Map<String, Object> body = resp.getData();
        assertThat(body.get("projectId")).isEqualTo("1001");
        assertThat(body.get("currentSubStageCode")).isEqualTo("CONCEPT-S2");
        assertThat(body.get("version")).isEqualTo(2L);
        assertThat(body.get("gateResult")).isEqualTo("PASSED");
        assertThat(body.get("advanced")).isEqualTo(true);
    }

    @Test
    @DisplayName("A5：门禁拒绝（40001）原样上抛，由 IpdServiceExceptionAdvice 转包络")
    void advancePropagatesGateNotPassed() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        doThrow(new IpdBusinessException(ApiV1ErrorCode.GATE_NOT_PASSED,
            "小阶段门禁未通过：阻断动作未完成 C01"))
            .when(progressService).advance(1001L, "CONCEPT-S2", 1L, ACTOR.id());

        assertThatThrownBy(() -> controller.advance(1001L, "CONCEPT-S2", 1L))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
                assertThat(ex.getMessage()).contains("C01");
            });
    }

    @Test
    @DisplayName("B2：GET 回读需要项目可见并返回数据库游标")
    void progressReadsPersistedCursor() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(progressService.current(1001L))
            .thenReturn(new SubStageProgressService.Progress("1001", "CONCEPT", "CONCEPT-S2", 2L, "PASSED", false));

        ApiV1Response<Map<String, Object>> resp = controller.progress(1001L);

        assertThat(resp.getData().get("currentSubStageCode")).isEqualTo("CONCEPT-S2");
        assertThat(resp.getData().get("version")).isEqualTo(2L);
        assertThat(resp.getData().get("advanced")).isEqualTo(false);
    }
}
