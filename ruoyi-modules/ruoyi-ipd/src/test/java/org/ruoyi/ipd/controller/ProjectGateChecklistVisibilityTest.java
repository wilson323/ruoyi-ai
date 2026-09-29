package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.GateEngine;
import org.ruoyi.ipd.service.ProjectService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectGateChecklistVisibilityTest {
    @Mock private ProjectService projectService;
    @Mock private GateEngine gateEngine;
    @Mock private IpdPermission ipdPermission;
    @InjectMocks private ProjectController controller;

    @Test
    void foreignProjectIsRejectedBeforeChecklistIsRead() {
        long projectId = 2096266884247736321L;
        IpdActor actor = new IpdActor(42L, "other-project-pm", "MARKET_PM", 7L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.getVisibleById(projectId, actor))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该项目"));

        assertThatThrownBy(() -> controller.gateChecklist(projectId, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("无权访问该项目");

        verify(projectService).getVisibleById(projectId, actor);
        verify(projectService, never()).getById(projectId);
        verifyNoInteractions(gateEngine);
    }

    @Test
    void visibleProjectIsPassedToChecklistEngine() {
        long projectId = 2096266884247736321L;
        IpdActor actor = new IpdActor(42L, "project-pm", "MARKET_PM", 7L);
        Project project = new Project();
        project.setId(projectId);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(projectService.getVisibleById(projectId, actor)).thenReturn(project);

        controller.gateChecklist(projectId, "CONCEPT");

        verify(gateEngine).explainChecklist(eq(project), eq("CONCEPT"));
        verify(projectService, never()).getById(projectId);
    }
}
