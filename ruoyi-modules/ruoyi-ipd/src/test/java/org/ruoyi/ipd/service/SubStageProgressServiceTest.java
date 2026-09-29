package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.mapper.ProjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SubStageProgressServiceTest {

    private ProjectMapper projectMapper;
    private IpdSubStageService subStageService;
    private SubStageGateService gateService;
    private SubStageProgressService service;
    private IAuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        projectMapper = mock(ProjectMapper.class);
        subStageService = mock(IpdSubStageService.class);
        gateService = mock(SubStageGateService.class);
        auditLogService = mock(IAuditLogService.class);
        service = new SubStageProgressService(projectMapper, subStageService, gateService, auditLogService);
    }

    private static Project project(String cursor, long version) {
        return Project.builder().id(1001L).tenantId("000000").source("NEW")
            .currentStage("CONCEPT").currentSubStageCode(cursor).subStageVersion(version)
            .status("ACTIVE").build();
    }

    private static List<IpdSubStage> catalog() {
        return List.of(
            IpdSubStage.builder().code("CONCEPT-S1").stageCode("CONCEPT").isResident("0").build(),
            IpdSubStage.builder().code("CONCEPT-S2").stageCode("CONCEPT").isResident("0").build(),
            IpdSubStage.builder().code("PLAN-S1").stageCode("PLAN").isResident("0").build(),
            IpdSubStage.builder().code("KPI-S1").stageCode("KPI").isResident("1").build());
    }

    @Test
    void advancesOnceAndReadsPersistedVersion() {
        Project before = project("CONCEPT-S1", 1);
        Project after = project("CONCEPT-S2", 2).setLastSubStageGateResult("PASSED");
        when(projectMapper.selectById(1001L)).thenReturn(before, after);
        when(subStageService.listAll()).thenReturn(catalog());
        when(projectMapper.advanceSubStage(1001L, "000000", "CONCEPT", "CONCEPT-S1", "CONCEPT-S2", 1L, 11L))
            .thenReturn(1);

        var progress = service.advance(1001L, "CONCEPT-S2", 1L, 11L);

        assertThat(progress.currentSubStageCode()).isEqualTo("CONCEPT-S2");
        assertThat(progress.version()).isEqualTo(2L);
        assertThat(progress.gateResult()).isEqualTo("PASSED");
        assertThat(progress.replayed()).isFalse();
        verify(gateService).assertAdvanceAllowed(1001L, "CONCEPT-S2", true);
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("PROJECT_SUB_STAGE_ADVANCE");
        assertThat(audit.getValue().getBeforeData()).contains("CONCEPT-S1");
        assertThat(audit.getValue().getAfterData()).contains("CONCEPT-S2", "PASSED");
    }

    @Test
    void repeatedTargetReturnsStoredStateWithoutAnotherWrite() {
        when(projectMapper.selectById(1001L)).thenReturn(project("CONCEPT-S2", 2));
        when(subStageService.listAll()).thenReturn(catalog());

        var progress = service.advance(1001L, "CONCEPT-S2", 1L, 11L);

        assertThat(progress.replayed()).isTrue();
        assertThat(progress.version()).isEqualTo(2L);
        verify(projectMapper, never()).advanceSubStage(1001L, "000000", "CONCEPT", "CONCEPT-S1", "CONCEPT-S2", 1L, 11L);
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    void staleVersionOrSkippedStepCannotWrite() {
        when(projectMapper.selectById(1001L)).thenReturn(project("CONCEPT-S1", 1));
        when(subStageService.listAll()).thenReturn(catalog());

        assertThatThrownBy(() -> service.advance(1001L, "CONCEPT-S2", 0L, 11L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        assertThatThrownBy(() -> service.advance(1001L, "PLAN-S1", 1L, 11L))
            .isInstanceOf(IpdBusinessException.class);
        verify(gateService, never()).assertAdvanceAllowed(1001L, "CONCEPT-S2", true);
    }

    @Test
    void casFailureReadsWinnerWithoutReportingFalseSuccess() {
        when(projectMapper.selectById(1001L))
            .thenReturn(project("CONCEPT-S1", 1), project("PLAN-S1", 2));
        when(subStageService.listAll()).thenReturn(catalog());

        assertThatThrownBy(() -> service.advance(1001L, "CONCEPT-S2", 1L, 11L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        verify(auditLogService, never()).append(any(AuditLog.class));
    }
}
