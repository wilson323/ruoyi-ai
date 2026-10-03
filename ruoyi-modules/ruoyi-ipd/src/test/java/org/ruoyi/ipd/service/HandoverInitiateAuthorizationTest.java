package org.ruoyi.ipd.service;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.ruoyi.ipd.domain.*;
import org.ruoyi.ipd.mapper.*;
import org.ruoyi.ipd.security.*;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.support.NoopTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@Tag("dev")
class HandoverInitiateAuthorizationTest {
    final ProjectMemberMapper members=mock(ProjectMemberMapper.class);final PersonMapper people=mock(PersonMapper.class);
    final ProjectMapper projects=mock(ProjectMapper.class);final HandoverMapper records=mock(HandoverMapper.class);
    final IAuditLogService audit=mock(IAuditLogService.class);final IProjectMemberService memberService=mock(IProjectMemberService.class);
    final IpdAuthSession sessions=mock(IpdAuthSession.class);final NotificationService notices=mock(NotificationService.class);
    final HandoverService service=new HandoverService(members,people,projects,records,audit,memberService,NoopTransactionManager.INSTANCE,sessions,notices);
    @BeforeAll static void tables(){var a=new MapperBuilderAssistant(new MybatisConfiguration(),"");TableInfoHelper.initTableInfo(a,ProjectMember.class);TableInfoHelper.initTableInfo(a,HandoverRecord.class);}
    @BeforeEach void init(){service.setStateMachineGuard(mock(StateMachineGuard.class));var p=new Project();p.setId(2L);p.setMainGroupId(10L);when(projects.selectById(2L)).thenReturn(p);var to=new Person();to.setId(5L);to.setPersonType("MARKET_PM");to.setEmploymentStatus("ACTIVE");to.setAccountStatus("ACTIVE");when(people.selectById(5L)).thenReturn(to);}
    void noWrites(){verify(records,never()).insert(any(HandoverRecord.class));verifyNoInteractions(audit,notices,memberService);}
    @Test void absentIdentityAndCrossGroupWithoutActiveRoleNeverCreateOrAudit(){for(var actor:new IpdActor[]{null,new IpdActor(null,"null","MARKET_PM",10L),new IpdActor(3L,"other","MARKET_PM",11L)}){assertThrows(IpdBusinessException.class,()->service.initiate(2L,"MARKET_PM",5L,"x",actor));noWrites();}}
    @Test void sameGroupWrongRoleOrExitedMemberAndAdminWithoutRoleCannotInventOwnership(){when(members.selectCount(any())).thenReturn(0L);for(var actor:new IpdActor[]{new IpdActor(3L,"wrongRole","RD_PM",10L),new IpdActor(3L,"exited","MARKET_PM",10L),new IpdActor(9L,"admin","SUPER_ADMIN",null)}){assertThrows(IpdBusinessException.class,()->service.initiate(2L,"MARKET_PM",5L,"x",actor));noWrites();}}
    @Test void actualActiveOwnerUsesOriginalDraftAndExactRoleQuery(){when(members.selectCount(any())).thenReturn(1L);var record=service.initiate(2L,"MARKET_PM",5L,"x",new IpdActor(3L,"owner","MARKET_PM",10L));assertEquals(3L,record.getFromPersonId());verify(records).insert(any(HandoverRecord.class));verify(audit).append(any(AuditLog.class));verify(members).selectCount(argThat(q->q.getCustomSqlSegment().contains("exit_date IS NULL")&&q.getCustomSqlSegment().contains("role")&&((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectMember>)q).getParamNameValuePairs().containsValue("MARKET_PM")&&((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectMember>)q).getParamNameValuePairs().containsValue(3L)&&((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectMember>)q).getParamNameValuePairs().containsValue(2L)));}
    @Test void crossGroupActualActiveOwnerMayTransferOwnRole() {
        when(members.selectCount(any())).thenReturn(1L);
        var record=service.initiate(2L,"MARKET_PM",5L,"本人跨空间项目角色交接",new IpdActor(3L,"owner","MARKET_PM",11L));
        assertEquals(3L,record.getFromPersonId());
        assertEquals(2L,record.getProjectId());
        verify(records).insert(any(HandoverRecord.class));
        verify(audit).append(any(AuditLog.class));
    }
    @Test void crossTenantIsStillRejectedBeforeRoleLookupAndAnyWrites() {
        var project=new Project();project.setId(2L);project.setMainGroupId(10L);project.setTenantId("tenant-b");
        when(projects.selectById(2L)).thenReturn(project);
        try (var tenant=mockStatic(org.ruoyi.common.satoken.utils.LoginHelper.class)) {
            tenant.when(org.ruoyi.common.satoken.utils.LoginHelper::getTenantId).thenReturn("tenant-a");
            assertThrows(IpdBusinessException.class,()->service.initiate(2L,"MARKET_PM",5L,"x",new IpdActor(3L,"owner","MARKET_PM",11L)));
            verify(members,never()).selectCount(any());noWrites();
        }
    }
    @Test void anotherPersonsActiveRoleNeverAuthorizesCaller() {
        when(members.selectCount(any())).thenAnswer(invocation -> {
            var query=(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectMember>)invocation.getArgument(0);
            query.getCustomSqlSegment();
            return query.getParamNameValuePairs().containsValue(3L) ? 1L : 0L;
        });
        assertThrows(IpdBusinessException.class,()->service.initiate(2L,"MARKET_PM",5L,"x",new IpdActor(999L,"not-owner","MARKET_PM",10L)));noWrites();
    }
}
