package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 开工批准：没有负责人时超管可批，已有负责人时超管不能代批。 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectStartServiceTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private ProductMapper productMapper;
    @Mock private ProductLineMapper lineMapper;
    @Mock private ProjectBootstrapService projectBootstrapService;
    @Mock private IProjectCertService projectCertService;
    @Mock private IAuditLogService auditLogService;
    @Mock private StateMachineGuard stateMachineGuard;

    private ProjectStartService service;

    @BeforeEach
    void setUp() {
        service = new ProjectStartService(projectMapper, productMapper, lineMapper,
            projectBootstrapService, projectCertService, auditLogService, stateMachineGuard);
    }

    @Test
    @DisplayName("没有负责人时，超管批准后进入组队并生成阶段")
    void superAdminApprovesWhenLineHasNoLeader() {
        Project project = pending(9L, null);
        when(projectMapper.selectOne(any())).thenReturn(project);
        when(projectMapper.findProductLineId(9L)).thenReturn(3L);
        when(lineMapper.selectById(3L)).thenReturn(line(3L, null));
        doNothing().when(stateMachineGuard).preCheck("project", "PENDING_START", "TEAMING", "approveStart");

        when(projectMapper.updateById(any(Project.class))).thenReturn(1);
        Project approved = service.approve(9L, new IpdActor(1L, "root", "SUPER_ADMIN", 1L));

        assertThat(approved.getStatus()).isEqualTo("TEAMING");
        assertThat(approved.getCurrentStage()).isEqualTo("CONCEPT");
        verify(projectBootstrapService).bootstrap(9L, 1L);
    }

    @Test
    @DisplayName("已有负责人时，超管不能代批")
    void superAdminCannotApproveWhenLeaderExists() {
        when(projectMapper.selectOne(any())).thenReturn(pending(9L, 50L));
        when(projectMapper.findProductLineId(9L)).thenReturn(3L);
        when(lineMapper.selectById(3L)).thenReturn(line(3L, 8L));

        assertThatThrownBy(() -> service.approve(9L, new IpdActor(1L, "root", "SUPER_ADMIN", 1L)))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("只有产品线负责人能批准开工");
        verify(projectBootstrapService, never()).bootstrap(9L, 1L);
    }

    @Test
    @DisplayName("只有创建人可以再次提交")
    void onlyCreatorResubmits() {
        Project project = pending(9L, 50L);
        project.setStatus("START_REJECTED");
        project.setCreateBy(7L);
        when(projectMapper.selectOne(any())).thenReturn(project);

        assertThatThrownBy(() -> service.resubmit(9L, new IpdActor(8L, "other", "MARKET_PM", 1L)))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("只有创建人可以再次提交开工");
    }

    @Test
    @DisplayName("项目写入冲突时不启动后续阶段")
    void failedProjectUpdateDoesNotBootstrap() {
        when(projectMapper.selectOne(any())).thenReturn(pending(9L, 50L));
        when(projectMapper.findProductLineId(9L)).thenReturn(3L);
        when(lineMapper.selectById(3L)).thenReturn(line(3L, 8L));
        when(projectMapper.updateById(any(Project.class))).thenReturn(0);
        assertThatThrownBy(() -> service.approve(9L, new IpdActor(8L, "leader", "MARKET_PM", 1L)))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("项目已变更");
        verify(projectBootstrapService, never()).bootstrap(9L, 8L);
    }

    private Project pending(Long id, Long productId) {
        Project project = new Project();
        project.setId(id);
        project.setProductId(productId);
        project.setStatus("PENDING_START");
        project.setName("待开工");
        return project;
    }

    private ProductLine line(Long id, Long leaderId) {
        ProductLine line = new ProductLine();
        line.setId(id);
        line.setLeaderPersonId(leaderId);
        line.setDelFlag("0");
        line.setTenantId("000000");
        return line;
    }
    @Test
    void rightfulLeaderCannotApproveIterationAfterProductRetirementBecameEffective() {
        Project project = pending(9L, 50L);
        when(projectMapper.selectOne(any())).thenReturn(project);
        when(projectMapper.findProductLineId(9L)).thenReturn(3L);
        when(lineMapper.selectById(3L)).thenReturn(line(3L, 8L));
        when(productMapper.isRetirementLockedForUpdate(50L)).thenReturn(true);
        assertThatThrownBy(() -> service.approve(9L, new IpdActor(8L, "leader", "MARKET_PM", 1L)))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("关联项目不能批准开工");
        verify(projectMapper, never()).updateById(any(Project.class));
        verify(productMapper, never()).insert(any(org.ruoyi.ipd.domain.Product.class));
        org.mockito.Mockito.verifyNoInteractions(projectBootstrapService, stateMachineGuard, auditLogService);
        assertThat(project.getStatus()).isEqualTo("PENDING_START");
    }

}
