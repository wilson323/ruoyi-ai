package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.vo.AiAgentTaskView;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R232 P2-04：AiAgentTaskQueryService 只读查询单测（零写入面）。
 * {@code @Tag("dev")} 必须，否则 Surefire 静默跳过（假绿陷阱）。
 *
 * <p>覆盖：单查映射（含 resultSummary/errorMsg 透出）、不存在 → 50001 NOT_FOUND、
 * 列表映射 + 空项目空列表；并断言本类方法面零写（只允许 selectById/selectList）。
 *
 * <p><b>项目可见性守卫（AgentScope 执行链阶段 1「项目身份」）</b>：两端点均接受外部
 * taskId/projectId，角色级 {@code ipd:ai-document:list} 四角色全员可读不足以限定数据范围，
 * 故正例=在职成员/超管放行，反例=非在职成员、项目不存在、actor 缺失、projectId 缺失一律
 * fail-closed（FORBIDDEN/UNAUTHORIZED/PARAM_INVALID），且反例必须在查库之前拒绝——
 * 非成员不得拿到该项目任何任务行或计数（IpdIdorGuard 守卫 3 同口径）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AiAgentTaskQueryServiceTest {

    /** 项目 200 的在职成员（非超管，走完整守卫链）。 */
    private static final IpdActor MEMBER = new IpdActor(9001L, "alice", "MARKET_PM", 100L);
    /** 超管：守卫 3 豁免分支（不触达任何项目/成员 DB 读）。 */
    private static final IpdActor SUPER_ADMIN = new IpdActor(7001L, "root", "SUPER_ADMIN", 100L);

    @Mock
    private AiAgentTaskMapper taskMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private AiAgentTaskQueryService service;

    private static AiAgentTask sampleTask() {
        AiAgentTask t = AiAgentTask.builder()
            .projectId(200L).actionCode("C01").stageActionId(300L)
            .triggerType(AiAgentTask.TRIGGER_PASSIVE).execMode("AI_GENERATE")
            .status(AiAgentTask.STATUS_SUCCEEDED)
            .resultSummary("AI 草稿已生成，待市场 PM 审核（aiDocId=9001）")
            .aiDocId(9001L)
            .dedupKey("C01:300:PASSIVE")
            .fillPayload("{\"secret\":\"prompt 原文不得出 VO\"}")
            .inputDigest("sha256:deadbeef")
            .attempt(0)
            .triggeredBy(9001L)
            .build();
        t.setId(2104L);
        t.setCreateTime(new Date(1_700_000_000_000L));
        return t;
    }

    private static Project project200() {
        Project project = new Project();
        project.setId(200L);
        return project;
    }

    /** 在职成员放行所需的最小 stub（守卫 3：项目存在 + 在职成员计数 1）。 */
    private void givenActiveMemberOfProject200() {
        when(projectMapper.selectById(200L)).thenReturn(project200());
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
    }

    private static ApiV1ErrorCode codeOf(Throwable throwable) {
        return ((IpdBusinessException) throwable).getErrorCode();
    }

    @Test
    @DisplayName("P2-04：getByTaskId 在职成员放行并映射 AiAgentTaskView（fillPayload/inputDigest 不出 VO）")
    void getByTaskId_mapsToView() {
        when(taskMapper.selectById(2104L)).thenReturn(sampleTask());
        givenActiveMemberOfProject200();

        AiAgentTaskView view = service.getByTaskId(2104L, MEMBER);

        assertThat(view.id()).isEqualTo(2104L);
        assertThat(view.projectId()).isEqualTo(200L);
        assertThat(view.status()).isEqualTo(AiAgentTask.STATUS_SUCCEEDED);
        assertThat(view.resultSummary()).contains("AI 草稿已生成");
        assertThat(view.aiDocId()).isEqualTo(9001L);
        assertThat(view.triggerType()).isEqualTo(AiAgentTask.TRIGGER_PASSIVE);
        // record 组件面不含敏感列（组件里根本没有），逐值兜底：VO 对象 toString 不得带出原文
        assertThat(view.toString())
            .doesNotContain("prompt 原文")
            .doesNotContain("sha256:deadbeef")
            .doesNotContain("fillPayload");
        verify(taskMapper).selectById(2104L);
    }

    @Test
    @DisplayName("P2-04：getByTaskId 不存在 → 50001 NOT_FOUND（IpdResources 收口，不触达守卫 DB 读）")
    void getByTaskId_missing_throwsNotFound() {
        when(taskMapper.selectById(4044L)).thenReturn(null);

        assertThatThrownBy(() -> service.getByTaskId(4044L, MEMBER))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verifyNoInteractions(projectMemberMapper);
    }

    @Test
    @DisplayName("守卫：getByTaskId 跨项目（任务属 200，actor 非在职成员）→ FORBIDDEN，不返回视图")
    void getByTaskId_nonMemberOfTaskProject_throwsForbidden() {
        when(taskMapper.selectById(2104L)).thenReturn(sampleTask());
        when(projectMapper.selectById(200L)).thenReturn(project200());
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.getByTaskId(2104L, MEMBER))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ApiV1ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("守卫：getByTaskId 超管豁免——不触达项目/成员 DB 读即放行")
    void getByTaskId_superAdmin_bypassesProjectLookup() {
        when(taskMapper.selectById(2104L)).thenReturn(sampleTask());

        AiAgentTaskView view = service.getByTaskId(2104L, SUPER_ADMIN);

        assertThat(view.id()).isEqualTo(2104L);
        verifyNoInteractions(projectMapper, projectMemberMapper);
    }

    @Test
    @DisplayName("P2-04：listByProject 映射列表；空项目返回空列表")
    void listByProject_mapsAndEmptySafe() {
        givenActiveMemberOfProject200();
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sampleTask()));
        List<AiAgentTaskView> views = service.listByProject(200L, MEMBER);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).actionCode()).isEqualTo("C01");

        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(service.listByProject(200L, MEMBER)).isEmpty();

        // 查询条件经 selectList 进入 mapper（wrapper 内容由 MP 引擎解析，此处只锁调用面）
        ArgumentCaptor<Wrapper<AiAgentTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, org.mockito.Mockito.atLeast(2)).selectList(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
    }

    @Test
    @DisplayName("守卫：listByProject 非在职成员 → FORBIDDEN，且查库前拒绝（taskMapper 零调用）")
    void listByProject_nonMember_throwsForbiddenBeforeQuery() {
        when(projectMapper.selectById(200L)).thenReturn(project200());
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.listByProject(200L, MEMBER))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ApiV1ErrorCode.FORBIDDEN));
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("守卫：listByProject 项目不存在 → FORBIDDEN（与无权限统一文案，不泄漏存在性）")
    void listByProject_projectMissing_throwsForbidden() {
        when(projectMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.listByProject(999L, MEMBER))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ApiV1ErrorCode.FORBIDDEN));
        verify(taskMapper, never()).selectList(any());
        verifyNoInteractions(projectMemberMapper);
    }

    @Test
    @DisplayName("守卫：listByProject 超管豁免——不触达项目/成员 DB 读即可列任务")
    void listByProject_superAdmin_bypassesMemberLookup() {
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sampleTask()));

        assertThat(service.listByProject(200L, SUPER_ADMIN)).hasSize(1);
        verifyNoInteractions(projectMapper, projectMemberMapper);
    }

    @Test
    @DisplayName("守卫：actor 缺失 → UNAUTHORIZED（service 层不信任 controller 必传）")
    void listByProject_nullActor_throwsUnauthorized() {
        assertThatThrownBy(() -> service.listByProject(200L, null))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ApiV1ErrorCode.UNAUTHORIZED));
        assertThatThrownBy(() -> service.getByTaskId(2104L, null))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ApiV1ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(taskMapper, projectMapper, projectMemberMapper);
    }

    @Test
    @DisplayName("守卫：projectId 缺失 → PARAM_INVALID（先于任何 DB 读）")
    void listByProject_nullProjectId_throwsParamInvalid() {
        assertThatThrownBy(() -> service.listByProject(null, MEMBER))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verifyNoInteractions(taskMapper, projectMapper, projectMemberMapper);
    }

    @Test
    @DisplayName("哨兵：查询面零写入——只允许 selectById/selectList，永不 insert/update/delete")
    void querySurfaceIsReadOnly_neverWrites() {
        givenActiveMemberOfProject200();
        when(taskMapper.selectById(2104L)).thenReturn(sampleTask());
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sampleTask()));

        service.getByTaskId(2104L, MEMBER);
        service.listByProject(200L, MEMBER);

        verify(taskMapper, never()).insert(any(AiAgentTask.class));
        verify(taskMapper, never()).updateById(any(AiAgentTask.class));
        verify(taskMapper, never()).deleteById(any());
        verify(projectMapper, never()).updateById(any(Project.class));
        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
        // 单查只针对 2104L，不得探测其他 id（与 never() 写入断言互不矛盾）
        verify(taskMapper).selectById(2104L);
    }
}
