package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.CoefficientChangeRequest;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.math.BigDecimal;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R33 一期契约（分片 B / C2 系数定值）：CoefficientChangeService 与
 * {@code org.ruoyi.ipd.approval.ApprovalGuardSupport} 的组合接线钉死。
 *
 * <p>每条用例都可作「自证能红」锚点：
 * <ul>
 *   <li>把 {@code setStateMachineGuard} 的 guardSupport 委托拆掉（守卫回 null）→
 *       C2-1/C2-2/C2-7 必红（preCheck fail-closed 抛「状态机守卫未装配」）；</li>
 *   <li>把 propose 的 {@code guardSupport.preCheck} 调用点删掉 → C2-3 必红；</li>
 *   <li>leaderDecision 的 CAS 命中判定若被拆掉（恒判命中）→ C2-6 必红；</li>
 *   <li>在途预检/终态守卫/CAS 文案漂移 → C2-4/C2-5/C2-6 必红（逐字断言）。</li>
 * </ul>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("R33 一期契约：C2 系数定值 × ApprovalGuardSupport 组合接线")
class CoefficientChangeServiceApprovalGuardContractTest {

    @Mock private CoefficientChangeRequestMapper requestMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private StateMachineGuard stateMachineGuard;

    private CoefficientChangeService service;

    @BeforeEach
    void setUp() {
        service = new CoefficientChangeService(requestMapper, projectMapper, auditLogService);
    }

    /** LambdaUpdateWrapper 列名缓存引导（同 CoefficientChangeServiceTest 惯例）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CoefficientChangeRequest.class);
    }

    private IpdActor proposerActor() {
        return new IpdActor(101L, "M", "MARKET_PM", 7L);
    }

    private IpdActor leaderActor() {
        return new IpdActor(900L, "L", "GROUP_LEADER", 7L);
    }

    private Project sProject() {
        Project p = new Project();
        p.setId(10L);
        p.setLevel("S");
        p.setLevelCoefficient(new BigDecimal("1.5"));
        p.setMainGroupId(7L);
        p.setDelFlag("0");
        return p;
    }

    private CoefficientChangeRequest pending(String status) {
        return CoefficientChangeRequest.builder()
            .id(99L).projectId(10L).proposedCoefficient(new BigDecimal("1.8"))
            .reason("旗舰").marketPmId(1L).rdPmId(2L).proposerId(1L)
            .status(status).build();
    }

    private void stubProposeHappy() {
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(requestMapper.insert(any(CoefficientChangeRequest.class))).thenAnswer(inv -> {
            inv.getArgument(0, CoefficientChangeRequest.class).setId(99L);
            return 1;
        });
    }

    private void propose() {
        service.propose(10L, new BigDecimal("1.8"), "旗舰溢价", 101L, 102L, 101L, proposerActor());
    }

    @Test
    @DisplayName("C2-1) propose 的 preCheck 经 guardSupport 委托且 entityType=coefficient_change")
    void preCheckDelegatedOnPropose() {
        service.setStateMachineGuard(stateMachineGuard);
        stubProposeHappy();

        propose();

        verify(stateMachineGuard).preCheck("coefficient_change", null, "PENDING_LEADER", "propose");
    }

    @Test
    @DisplayName("C2-2) registerPostCommit 经 guardSupport 委托（无事务降级立即 postCommit）")
    void postCommitDelegatedOnPropose() {
        service.setStateMachineGuard(stateMachineGuard);
        stubProposeHappy();

        propose();

        verify(stateMachineGuard).postCommit(eq("coefficient_change"), isNull(), eq("PENDING_LEADER"),
            eq("propose"), eq(101L), eq(99L), any(Date.class));
    }

    @Test
    @DisplayName("C2-3) 未注入守卫时 preCheck fail-closed，文案逐字钉死")
    void nullGuardFailsClosedOnPropose() {
        // 自证能红锚点：guardSupport.preCheck 调用点若被删/绕开，本用例必红
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        when(requestMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(this::propose)
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机守卫未装配 entityType=coefficient_change from=null to=PENDING_LEADER");
    }

    @Test
    @DisplayName("C2-4) 在途单唯一预检走 assertNoInFlight，文案逐字保留且先于 preCheck")
    void inFlightPrecheckKeepsOriginalMessage() {
        service.setStateMachineGuard(stateMachineGuard);
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        when(requestMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(this::propose)
            .isInstanceOf(ServiceException.class)
            .hasMessage("该项目已有待组长确认的系数定值申请");

        verify(requestMapper, never()).insert(any(CoefficientChangeRequest.class));
        verifyNoInteractions(stateMachineGuard);
    }

    @Test
    @DisplayName("C2-5) leaderDecision 终态守卫前置走 requireFromState，文案逐字保留")
    void requireFromStateKeepsOriginalMessage() {
        when(requestMapper.selectById(99L))
            .thenReturn(pending(CoefficientChangeRequest.ST_CONFIRMED));

        assertThatThrownBy(() -> service.leaderDecision(99L, 900L, true, "同意", leaderActor()))
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机不匹配：期望 PENDING_LEADER，实际 CONFIRMED");
    }

    @Test
    @DisplayName("C2-6) CAS 未命中走 requireCasHit，文案逐字保留且不写项目档案")
    void casMissKeepsOriginalMessage() {
        // 自证能红锚点：requireCasHit 命中判定若被拆掉（恒判命中），本用例必红
        service.setStateMachineGuard(stateMachineGuard);
        when(requestMapper.selectById(99L))
            .thenReturn(pending(CoefficientChangeRequest.ST_PENDING_LEADER));
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        when(requestMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.leaderDecision(99L, 900L, true, "同意", leaderActor()))
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机不匹配：申请已被并发处理（期望 PENDING_LEADER）");

        verify(projectMapper, never()).updateById(any(Project.class));
    }

    @Test
    @DisplayName("C2-7) leaderDecision 的 preCheck/postCommit 经 guardSupport 委托（leaderApprove 触发器）")
    void guardDelegatedOnLeaderDecision() {
        service.setStateMachineGuard(stateMachineGuard);
        when(requestMapper.selectById(99L))
            .thenReturn(pending(CoefficientChangeRequest.ST_PENDING_LEADER));
        when(projectMapper.selectById(10L)).thenReturn(sProject());
        when(requestMapper.update(isNull(), any())).thenReturn(1);
        when(projectMapper.updateById(any(Project.class))).thenReturn(1);

        service.leaderDecision(99L, 900L, true, "同意", leaderActor());

        verify(stateMachineGuard).preCheck("coefficient_change", "PENDING_LEADER", "CONFIRMED", "leaderApprove");
        verify(stateMachineGuard).postCommit(eq("coefficient_change"), eq("PENDING_LEADER"), eq("CONFIRMED"),
            eq("leaderApprove"), eq(900L), eq(99L), any(Date.class));
    }
}
