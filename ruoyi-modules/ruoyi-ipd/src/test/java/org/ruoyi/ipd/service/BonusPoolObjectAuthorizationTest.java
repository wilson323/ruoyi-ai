package org.ruoyi.ipd.service;
import org.junit.jupiter.api.*;
import org.ruoyi.ipd.domain.*;
import org.ruoyi.ipd.mapper.*;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.common.IpdBusinessException;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@Tag("dev")
class BonusPoolObjectAuthorizationTest {
    final BonusPoolMapper pools=mock(BonusPoolMapper.class);
    final ProjectMapper projects=mock(ProjectMapper.class);
    final IAuditLogService audit=mock(IAuditLogService.class);
    final StateMachineGuard guard=mock(StateMachineGuard.class);
    final BonusPoolService service=new BonusPoolService(pools,projects);
    @BeforeEach void init() { service.setAuditLogService(audit);service.setStateMachineGuard(guard); }
    BonusPool pool(String status) {
        var p=new BonusPool();p.setId(1L);p.setProjectId(2L);p.setStatus(status);when(pools.selectById(1L)).thenReturn(p);
        var project=new Project();project.setId(2L);project.setMainGroupId(10L);when(projects.selectById(2L)).thenReturn(project);return p;
    }
    void noWrites() { verify(pools,never()).updateById(any(BonusPool.class));verifyNoInteractions(audit,guard); }
    @Test void nullCrossGroupAndNonLeaderDeniedEvenAtIdempotentStates() {
        for (String status : new String[]{"DRAFT","CONFIRMED","DISTRIBUTED"}) for (IpdActor actor : new IpdActor[]{null,new IpdActor(null,"null","GROUP_LEADER",10L),new IpdActor(3L,"other","GROUP_LEADER",11L),new IpdActor(3L,"pm","MARKET_PM",10L)}) {
            pool(status); assertThrows(IpdBusinessException.class,()->service.freeze(1L,"x",actor));
            assertThrows(IpdBusinessException.class,()->service.distribute(1L,new BigDecimal("0.5"),new BigDecimal("0.5"),actor)); noWrites();
        }
    }
    @Test void groupLeaderDoesNotNeedProjectMembershipAndAdminKeepsExemption() {
        var p=pool("DISTRIBUTED");
        assertSame(p,service.freeze(1L,"same",new IpdActor(4L,"leader","GROUP_LEADER",10L)));
        assertSame(p,service.distribute(1L,null,null,new IpdActor(4L,"leader","GROUP_LEADER",10L)));
        assertSame(p,service.distribute(1L,null,null,new IpdActor(9L,"admin","SUPER_ADMIN",null)));noWrites();
    }
    @Test void authorizedLeaderFreezeKeepsOriginalWrites() {
        pool("DRAFT");assertEquals("CONFIRMED",service.freeze(1L,"approved",new IpdActor(4L,"leader","GROUP_LEADER",10L)).getStatus());
        verify(pools).updateById(any(BonusPool.class));verify(audit).append(any(AuditLog.class));
    }
}
