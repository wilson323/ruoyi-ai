package org.ruoyi.ipd.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentCaptor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.service.IpdAuthService;
import org.ruoyi.ipd.service.StageActionService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实成员守卫与原角色/暂停层次，不以 mock 权限返回值代替成员判断。 */
@Tag("dev")
class IpdActionWriterMemberTest {
    private IpdAuthSession session;
    private IpdAuthService auth;
    private ProjectMapper projects;
    private ProjectMemberMapper members;
    private IpdPermission permission;
    private StageAction action;
    private Person person;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "action-member"), ProjectMember.class);
        session = mock(IpdAuthSession.class);
        auth = mock(IpdAuthService.class);
        projects = mock(ProjectMapper.class);
        members = mock(ProjectMemberMapper.class);
        permission = new IpdPermission(session, auth);
        permission.setActionWriteMappers(projects, members);
        person = Person.builder().id(104L).personType("RD_PM").groupId(901L).build();
        action = StageAction.builder().id(5L).projectId(100L).actionCode("C05")
            .ownerRole("RD_PM").depth("LIGHT").status("NOT_STARTED").build();
        when(session.currentPerson()).thenReturn(person);
        when(auth.scopeOf(person)).thenReturn(IpdAuthService.Scope.FULL);
        when(projects.selectById(100L)).thenReturn(Project.builder().id(100L).mainGroupId(901L).status("TEAMING").build());
    }

    @Test
    void sameGroupSameRoleNonmemberIsForbidden() {
        when(members.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> permission.requireActionWriter(() -> action))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("非项目成员");
    }

    @Test
    void sameRoleCurrentMemberPassesExactProjectAndPersonQuery() {
        when(members.selectCount(any())).thenReturn(1L);
        assertThat(permission.requireActionWriter(() -> action).id()).isEqualTo(104L);
        ArgumentCaptor<LambdaQueryWrapper> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(members).selectCount(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("project_id", "person_id", "exit_date IS NULL");
        assertThat(wrapper.getValue().getParamNameValuePairs().values()).containsExactlyInAnyOrder(100L, 104L);
    }

    @Test
    void bothRoleAlsoRequiresMembership() {
        action.setOwnerRole("BOTH");
        when(members.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> permission.requireActionWriter(() -> action)).isInstanceOf(IpdBusinessException.class);
    }

    @Test
    void wrongResponsibilityKeepsRoleConflictBeforeProjectLookup() {
        person.setPersonType("GROUP_LEADER");
        assertThatThrownBy(() -> permission.requireActionWriter(() -> action)).isInstanceOf(IpdPermissionException.class);
        verifyNoInteractions(projects, members);
    }

    @Test
    void superAdminKeepsOriginalExceptionWithoutMemberLookup() {
        person.setPersonType("SUPER_ADMIN");
        assertThat(permission.requireActionWriter(() -> action).role()).isEqualTo("SUPER_ADMIN");
        verifyNoInteractions(projects, members);
    }

    @Test
    void absentDependenciesFailClosedForOrdinaryWriter() {
        permission = new IpdPermission(session, auth);
        assertThatThrownBy(() -> permission.requireActionWriter(() -> action)).isInstanceOf(IpdPermissionException.class);
    }

    @Test
    void unrelatedProjectCannotBorrowMembership() {
        action.setProjectId(200L);
        assertThatThrownBy(() -> permission.requireActionWriter(() -> action)).isInstanceOf(IpdBusinessException.class);
        verify(projects).selectById(200L);
        verify(projects, never()).selectById(100L);
        verifyNoInteractions(members);
    }

    @Test
    void missingActionFailsWithoutProjectLookup() {
        assertThatThrownBy(() -> permission.requireActionWriter(() -> null)).isInstanceOf(IpdPermissionException.class);
        verifyNoInteractions(projects, members);
    }

    @Test
    void memberPermissionDoesNotBypassRealPausedServiceGuard() {
        when(members.selectCount(any())).thenReturn(1L);
        Project paused = Project.builder().id(100L).mainGroupId(901L).status("SUSPENDED").build();
        when(projects.selectById(100L)).thenReturn(paused);
        StageActionMapper actions = mock(StageActionMapper.class);
        when(actions.selectById(5L)).thenReturn(action);
        StageActionService service = new StageActionService(actions, null, null, null, projects);
        assertThat(permission.requireActionWriter(() -> action).id()).isEqualTo(104L);
        assertThatThrownBy(() -> service.transit(5L, "IN_PROGRESS", null, "104"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("暂停/归档");
        verify(actions, never()).updateById(any(StageAction.class));
    }
}
