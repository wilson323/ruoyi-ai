package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.ProductLine;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.ProductLineMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段验收：提交人不能自批，没有负责人时只有超管能批。
 */
@Tag("dev")
class StageAcceptanceServiceTest {

    private StageActionMapper actionMapper;
    private ProjectStageMapper stageMapper;
    private ProjectMapper projectMapper;
    private ProductMapper productMapper;
    private ProductLineMapper lineMapper;
    private org.ruoyi.ipd.mapper.ProjectMemberMapper memberMapper;
    private GateEngine gateEngine;
    private StageAcceptanceService service;

    @BeforeEach
    void setUp() {
        actionMapper = mock(StageActionMapper.class);
        stageMapper = mock(ProjectStageMapper.class);
        projectMapper = mock(ProjectMapper.class);
        productMapper = mock(ProductMapper.class);
        lineMapper = mock(ProductLineMapper.class);
        gateEngine = mock(GateEngine.class);
        service = new StageAcceptanceService(actionMapper, stageMapper, projectMapper,
            productMapper, lineMapper, gateEngine, mock(IAuditLogService.class))
            .withClock(java.time.Clock.fixed(java.time.Instant.parse("2026-10-02T12:00:00Z"), java.time.ZoneOffset.UTC));
        memberMapper = mock(org.ruoyi.ipd.mapper.ProjectMemberMapper.class);
        service.setProjectMemberMapper(memberMapper);
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(actionMapper.updateById(any(StageAction.class))).thenReturn(1);
        when(stageMapper.updateById(any(ProjectStage.class))).thenReturn(1);
    }

    @Test
    @DisplayName("产线负责人批准已提交的小阶段")
    void leaderApprovesSubmittedAction() {
        wireLine(9L);
        when(actionMapper.selectOne(any())).thenReturn(submittedAction(8L));
        StageAction saved = service.acceptAction(5L, new IpdActor(9L, "负责人", "MARKET_PM", null));
        assertThat(saved.getConfirmedBy()).isEqualTo(9L);
        assertThat(saved.getConfirmedAt()).isEqualTo(java.util.Date.from(java.time.Instant.parse("2026-10-02T12:00:00Z")));
    }

    @Test
    @DisplayName("提交人不能批准自己提交的验收")
    void submitterCannotApproveSelf() {
        wireLine(9L);
        when(actionMapper.selectOne(any())).thenReturn(submittedAction(8L));
        assertThatThrownBy(() -> service.acceptAction(5L, new IpdActor(8L, "提交人", "RD_PM", null)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("提交人不能批准");
    }

    @Test
    @DisplayName("没有负责人时只有超管能批")
    void onlySuperAdminWhenLineHasNoLeader() {
        Project project = activeProject();
        project.setProductId(null);
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(actionMapper.selectOne(any())).thenReturn(submittedAction(8L));
        assertThatThrownBy(() -> service.acceptAction(5L, new IpdActor(9L, "其他人", "MARKET_PM", null)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("只有超管能批准");
        StageAction saved = service.acceptAction(5L, new IpdActor(1L, "超管", "SUPER_ADMIN", null));
        assertThat(saved.getConfirmedBy()).isEqualTo(1L);
    }

    @Test
    @DisplayName("已有负责人时超管不能代批")
    void superAdminCannotBypassLeader() {
        wireLine(9L);
        when(actionMapper.selectOne(any())).thenReturn(submittedAction(8L));
        assertThatThrownBy(() -> service.acceptAction(5L, new IpdActor(1L, "超管", "SUPER_ADMIN", null)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("只有该产品线负责人能批准");
    }

    @Test
    @DisplayName("重复批准同一人时直接返回")
    void acceptIsIdempotentForSameLeader() {
        wireLine(9L);
        StageAction done = submittedAction(8L);
        done.setConfirmedBy(9L);
        when(actionMapper.selectOne(any())).thenReturn(done);
        assertThat(service.acceptAction(5L, new IpdActor(9L, "负责人", "MARKET_PM", null)).getConfirmedBy())
            .isEqualTo(9L);
    }

    @Test
    @DisplayName("大阶段须先过小阶段门禁，再进入待批准")
    void submitStageRequiresAcceptedActions() {
        Project project = activeProject();
        when(projectMapper.selectById(100L)).thenReturn(project);
        ProjectStage stage = new ProjectStage();
        stage.setId(3L);
        stage.setStatus("NOT_STARTED");
        when(stageMapper.selectOne(any())).thenReturn(stage);
        ProjectStage saved = service.submitStage(100L, new IpdActor(8L, "提交人", "RD_PM", null));
        verify(gateEngine).check(project, "CONCEPT");
        assertThat(saved.getStatus()).isEqualTo(StageAcceptanceService.PENDING_ACCEPT);
        assertThat(saved.getUpdateBy()).isEqualTo(8L);
    }

    @Test
    @DisplayName("既有乐观锁拒绝时不发布批准结果")
    void optimisticConflictDoesNotPublishAcceptance() {
        wireLine(9L);
        when(actionMapper.selectOne(any())).thenReturn(submittedAction(8L));
        when(actionMapper.updateById(any(StageAction.class))).thenReturn(0);
        assertThatThrownBy(() -> service.acceptAction(5L, new IpdActor(9L, "负责人", "MARKET_PM", null)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("批准失败");
    }


    @Test
    @DisplayName("产线负责人不是项目成员也能批准小阶段（2026-10-06 死锁修复）")
    void lineLeaderWhoIsNotProjectMemberCanApprove() {
        wireLine(9L);
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.selectOne(any())).thenReturn(submittedAction(8L));
        StageAction saved = service.acceptAction(5L, new IpdActor(9L, "负责人", "GROUP_LEADER", 1L));
        assertThat(saved.getConfirmedBy()).isEqualTo(9L);
        verify(actionMapper).updateById(any(StageAction.class));
    }

    @Test
    void nonMemberCannotSubmitStage() {
        wireLine(9L);
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.selectOne(any())).thenReturn(submittedAction(8L));
        assertThatThrownBy(() -> service.submitStage(100L, new IpdActor(9L, "负责人", "GROUP_LEADER", 1L)))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class).hasMessageContaining("非项目成员");
        org.mockito.Mockito.verify(stageMapper, org.mockito.Mockito.never()).updateById(any(ProjectStage.class));
        org.mockito.Mockito.verifyNoInteractions(gateEngine);
    }

    @Test
    @DisplayName("非成员且非产线负责人的普通账号不能批准")
    void nonMemberNonLeaderCannotApproveAction() {
        wireLine(9L);
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(actionMapper.selectOne(any())).thenReturn(submittedAction(8L));
        // actor=8L：既不是本线负责人(9L)也不是项目成员，也不应是提交人路径
        assertThatThrownBy(() -> service.acceptAction(5L, new IpdActor(8L, "其他人", "MARKET_PM", 1L)))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class).hasMessageContaining("非项目成员");
        org.mockito.Mockito.verify(actionMapper, org.mockito.Mockito.never()).updateById(any(StageAction.class));
    }

    private void wireLine(Long leaderId) {
        when(projectMapper.selectById(100L)).thenReturn(activeProject());
        Product product = new Product();
        product.setProductLineId(4L);
        when(productMapper.selectById(3L)).thenReturn(product);
        ProductLine line = new ProductLine();
        line.setStatus("ACTIVE");
        line.setLeaderPersonId(leaderId);
        when(lineMapper.selectById(4L)).thenReturn(line);
    }

    private Project activeProject() {
        Project project = new Project();
        project.setId(100L);
        project.setProductId(3L);
        project.setStatus("ACTIVE");
        project.setDelFlag("0");
        project.setCurrentStage("CONCEPT");
        return project;
    }

    private StageAction submittedAction(Long submitterId) {
        StageAction action = StageAction.builder().id(5L).projectId(100L).status("DONE").build();
        action.setUpdateBy(submitterId);
        return action;
    }
}
