package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.*;
import org.ruoyi.ipd.security.IpdActor;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GateReviewVisibilityTest {
    private final GateMapper gates = mock(GateMapper.class);
    private final GateReviewMapper reviews = mock(GateReviewMapper.class);
    private final ProjectService visibility = mock(ProjectService.class);
    private final IpdActor actor = new IpdActor(10L, "读取者", "MARKET_PM", 7L);
    private GateReviewService service;
    private Gate gate;
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "visibility"), GateReview.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "visibility-gate"), Gate.class);
        service = new GateReviewService(gates, reviews, mock(ProjectMemberMapper.class), mock(PersonMapper.class),
            mock(GateArbitrationMapper.class), mock(GateReviewObserverMapper.class),
            mock(ISystemConfigService.class), mock(IAuditLogService.class), mock(NotificationService.class));
        service.setProjectVisibility(visibility);
        gate = new Gate(); gate.setId(1L); gate.setProjectId(2L);
        gate.setGateCode("G1"); gate.setStatus("PENDING"); gate.setCurrentRound(1);
        when(gates.selectOne(any())).thenReturn(gate);
        lenient().when(visibility.getVisibleById(2L, actor))
            .thenReturn(Project.builder().id(2L).tenantId("000000").build());
    }
    @Test void invisibleProjectStopsBeforeOpinionsAreRead() {
        when(visibility.getVisibleById(2L, actor)).thenThrow(
            new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该项目"));
        assertThrows(IpdBusinessException.class, () -> service.view(1L, actor));
        verifyNoInteractions(reviews);
    }
    @Test void foreignTenantGateStopsEvenForAdministrator() {
        when(gates.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<Gate> query = inv.getArgument(0);
            assertTrue(query.getSqlSegment().contains("tenant_id"));
            assertTrue(query.getParamNameValuePairs().containsValue("000000"));
            return null;
        });
        assertThrows(IpdBusinessException.class, () -> service.view(1L,
            new IpdActor(10L, "管理者", "SUPER_ADMIN", null)));
        verifyNoInteractions(visibility, reviews);
    }
    @Test void foreignTenantProjectStopsBeforeOpinions() {
        when(visibility.getVisibleById(2L, actor)).thenReturn(Project.builder().id(2L).tenantId("other").build());
        assertThrows(IpdBusinessException.class, () -> service.view(1L, actor));
        verifyNoInteractions(reviews);
    }
    @Test void sameRoleOtherPersonIsNotMyOpinionAndTenantIsExplicit() {
        when(reviews.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<GateReview> query = inv.getArgument(0);
            assertTrue(query.getSqlSegment().contains("tenant_id"));
            assertTrue(query.getParamNameValuePairs().containsValue("000000"));
            return List.of(GateReview.builder().reviewerId(99L).reviewerType("MARKET_PM")
                .decision("REJECT").opinion("别人意见").build());
        });
        Map<String,Object> result = service.view(1L, actor);
        assertNull(result.get("my"));
        assertNull(((Map<?,?>) result.get("other")).get("opinion"));
    }
    @Test void actualSignerSeesOwnOpinionAndVisibleLeaderSeesTerminalEvidence() {
        when(reviews.selectList(any())).thenReturn(List.of(GateReview.builder().reviewerId(10L)
            .reviewerType("RD_PM").decision("APPROVE").opinion("本人意见").build()));
        assertEquals("本人意见", ((Map<?,?>)service.view(1L, actor).get("my")).get("opinion"));
        IpdActor leader = new IpdActor(20L,"负责人","MARKET_PM",9L);
        when(visibility.getVisibleById(2L, leader)).thenReturn(Project.builder().id(2L).tenantId("000000").build());
        gate.setStatus("APPROVED");
        assertEquals("本人意见", ((Map<?,?>)service.view(1L, leader).get("other")).get("opinion"));
    }
    @Test void foreignProjectGateListReadsNothing() {
        when(visibility.getVisibleById(2L, actor)).thenThrow(new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN,"无权访问该项目"));
        assertThrows(IpdBusinessException.class, () -> service.listByProject(2L, actor));
        verify(gates, never()).selectList(any());
    }
    @Test void oldUnauthenticatedListIsClosedAndVisibleListIsTenantScoped() {
        assertThrows(IpdBusinessException.class, () -> service.listByProject(2L));
        when(gates.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<Gate> query = inv.getArgument(0);
            assertTrue(query.getSqlSegment().contains("tenant_id"));
            return List.of(gate);
        });
        assertEquals(1, service.listByProject(2L, actor).size());
    }
    @Test void leaderRoleAloneCannotReadAnotherProjectsObservers() {
        IpdActor leader = new IpdActor(20L,"组长","GROUP_LEADER",9L);
        when(visibility.getVisibleById(2L, leader)).thenThrow(new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN,"无权访问该项目"));
        assertThrows(IpdBusinessException.class, () -> service.listObservers(1L, leader));
    }

}
