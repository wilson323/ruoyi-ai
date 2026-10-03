package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.*;
import org.ruoyi.ipd.mapper.*;
import org.ruoyi.ipd.security.IpdActor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AiDocumentTeamReadAccessTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProductLineMapper lines = mock(ProductLineMapper.class);
    private final IpdCopilotAccess memberAccess = mock(IpdCopilotAccess.class);
    private final ProjectService projectAccess = new ProjectService(projects, mock(ProductMapper.class),
        mock(StageActionMapper.class), mock(KpiRecordMapper.class), mock(IAuditLogService.class),
        mock(GateEngine.class), mock(ProjectBootstrapService.class), mock(IProjectCertService.class), null, null);
    private final AiDocumentService documents = new AiDocumentService(mock(AiDocumentMapper.class));
    private final IpdActor leader = new IpdActor(9L, "leader", "MARKET_PM", 2L);
    private final ProductLine line = new ProductLine();

    AiDocumentTeamReadAccessTest() {
        projectAccess.setProductLineMapper(lines);
        projectAccess.setProjectMemberMapper(mock(ProjectMemberMapper.class));
        Project project = new Project();
        project.setId(100L); project.setTenantId("000000"); project.setStatus("ACTIVE");
        when(projects.selectById(100L)).thenReturn(project);
        when(projects.findProductLineId(100L)).thenReturn(7L);
        line.setLeaderPersonId(9L); line.setDelFlag("0");
        when(lines.selectById(7L)).thenReturn(line);
        when(memberAccess.requireVisible(leader, null)).thenReturn("000000");
        when(memberAccess.requireVisible(leader, 100L))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"));
        documents.setProjectAccess(memberAccess);
        documents.setProjectReadAccess(projectAccess);
    }

    @Test void nonMemberLineLeaderCanReadButCannotWrite() {
        assertThat(documents.requireProjectReadable(leader, 100L)).isEqualTo("000000");
        assertThatThrownBy(() -> documents.requireProjectVisible(leader, 100L))
            .isInstanceOf(IpdBusinessException.class);
    }
    @Test void replacementLeaderImmediatelyRevokesReadAccess() {
        documents.requireProjectReadable(leader, 100L);
        line.setLeaderPersonId(8L);
        assertThatThrownBy(() -> documents.requireProjectReadable(leader, 100L))
            .isInstanceOf(IpdBusinessException.class);
    }
    @Test void ordinaryNonMemberCannotRead() {
        IpdActor other = new IpdActor(8L, "other", "RD_PM", 2L);
        when(memberAccess.requireVisible(other, null)).thenReturn("000000");
        assertThatThrownBy(() -> documents.requireProjectReadable(other, 100L))
            .isInstanceOf(IpdBusinessException.class);
    }
    @Test void staleOrInactivePersonDeniedBeforeProjectRead() {
        when(memberAccess.requireVisible(leader, null))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> documents.requireProjectReadable(leader, 100L))
            .isInstanceOf(IpdBusinessException.class);
        verify(projects, never()).selectById(100L);
    }
    @Test void crossTenantReadStillDenied() {
        when(memberAccess.requireVisible(leader, null)).thenReturn("another");
        assertThatThrownBy(() -> documents.requireProjectReadable(leader, 100L))
            .isInstanceOf(IpdBusinessException.class);
    }
}
