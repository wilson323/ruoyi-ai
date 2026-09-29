package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.ruoyi.ipd.domain.ProjectMember;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("dev")
class IpdCopilotAccessTest {

    @BeforeAll
    static void initLambdaColumns() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            ProjectMember.class);
    }

    private final PersonMapper personMapper = mock(PersonMapper.class);
    private final IpdAuthService authService = mock(IpdAuthService.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
    private final IpdCopilotAccess access =
        new IpdCopilotAccess(personMapper, authService, projectMapper, projectMemberMapper);

    private final IpdActor actor = new IpdActor(2L, "研发", "RD_PM", 100L);
    private Person person;

    @BeforeEach
    void setUp() {
        person = Person.builder().id(2L).name("研发").personType("RD_PM").groupId(100L)
            .accountStatus("ACTIVE").employmentStatus("ACTIVE").tenantId("tenant-a").build();
        when(personMapper.selectById(2L)).thenReturn(person);
        when(authService.scopeOf(person)).thenReturn(IpdAuthService.Scope.FULL);
    }

    @Test
    void globalQuestionRequiresCurrentPersonButNoProject() {
        assertEquals("tenant-a", access.requireVisible(actor, null));

        verify(personMapper).selectById(2L);
        verifyNoInteractions(projectMapper, projectMemberMapper);
    }

    @Test
    void currentMemberCanReadSameTenantProject() {
        when(projectMapper.selectById(10L)).thenReturn(project("tenant-a"));
        when(projectMemberMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);

        assertEquals("tenant-a", access.requireVisible(actor, 10L));

        verify(projectMemberMapper).selectCount(argThat(query ->
            query.getSqlSegment().contains("exit_date") && query.getSqlSegment().contains("IS NULL")));
    }

    @Test
    void exitedMemberCannotReadProject() {
        when(projectMapper.selectById(10L)).thenReturn(project("tenant-a"));
        when(projectMemberMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);

        IpdBusinessException error = assertThrows(IpdBusinessException.class,
            () -> access.requireVisible(actor, 10L));

        assertEquals(ApiV1ErrorCode.NOT_FOUND, error.getErrorCode());
        verify(projectMemberMapper).selectCount(argThat(query ->
            query.getSqlSegment().contains("exit_date") && query.getSqlSegment().contains("IS NULL")));
    }

    @Test
    void crossTenantProjectIsInvisibleBeforeMemberLookup() {
        when(projectMapper.selectById(10L)).thenReturn(project("tenant-b"));

        IpdBusinessException error = assertThrows(IpdBusinessException.class,
            () -> access.requireVisible(actor, 10L));

        assertEquals(ApiV1ErrorCode.NOT_FOUND, error.getErrorCode());
        verifyNoInteractions(projectMemberMapper);
    }

    @Test
    void superAdminKeepsMemberExemptionOnlyWithinTenant() {
        person.setPersonType("SUPER_ADMIN");
        person.setGroupId(null);
        IpdActor admin = new IpdActor(2L, "管理员", "SUPER_ADMIN", null);
        when(projectMapper.selectById(10L)).thenReturn(project("tenant-a"));

        assertEquals("tenant-a", access.requireVisible(admin, 10L));

        verifyNoInteractions(projectMemberMapper);
        when(projectMapper.selectById(11L)).thenReturn(project("tenant-b"));
        IpdBusinessException error = assertThrows(IpdBusinessException.class,
            () -> access.requireVisible(admin, 11L));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, error.getErrorCode());
        verify(projectMemberMapper, never()).selectCount(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleOrInactivePersonCannotReadEvenGlobalQuestion() {
        person.setAccountStatus("FROZEN_PENDING_HANDOVER");
        IpdBusinessException error = assertThrows(IpdBusinessException.class,
            () -> access.requireVisible(actor, null));
        assertEquals(ApiV1ErrorCode.FORBIDDEN, error.getErrorCode());
        verifyNoInteractions(projectMapper, projectMemberMapper);

        person.setAccountStatus("ACTIVE");
        person.setTenantId(" ");
        error = assertThrows(IpdBusinessException.class, () -> access.requireVisible(actor, null));
        assertEquals(ApiV1ErrorCode.FORBIDDEN, error.getErrorCode());
    }

    private static Project project(String tenantId) {
        return Project.builder().id(10L).tenantId(tenantId).build();
    }
}
