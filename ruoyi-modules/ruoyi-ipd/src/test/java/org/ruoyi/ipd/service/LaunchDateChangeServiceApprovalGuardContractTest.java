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
import org.ruoyi.ipd.domain.LaunchDateChangeRequest;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;

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
 * R33 一期契约（分片 B / C1 上市日期变更）：LaunchDateChangeService 与
 * {@code org.ruoyi.ipd.approval.ApprovalGuardSupport} 的组合接线钉死。
 *
 * <p>每条用例都可作「自证能红」锚点：
 * <ul>
 *   <li>把 {@code setStateMachineGuard} 的 guardSupport 委托拆掉（守卫回 null）→
 *       C1-1/C1-2/C1-6 必红（preCheck fail-closed 抛「状态机守卫未装配」）；</li>
 *   <li>把 propose 的 {@code guardSupport.preCheck} 调用点删掉 → C1-3 必红（不再抛 fail-closed）；</li>
 *   <li>在途预检/终态守卫文案漂移 → C1-4/C1-5 必红（逐字断言）。</li>
 * </ul>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("R33 一期契约：C1 上市日期变更 × ApprovalGuardSupport 组合接线")
class LaunchDateChangeServiceApprovalGuardContractTest {

    @Mock private LaunchDateChangeRequestMapper requestMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private StateMachineGuard stateMachineGuard;

    private LaunchDateChangeService service;

    private static final Date DAY = new Date(1_700_000_000_000L);

    @BeforeEach
    void setUp() {
        service = new LaunchDateChangeService(requestMapper, projectMapper, auditLogService);
    }

    /** Lambda wrapper 列名缓存引导（同 P256AcceptanceTest 惯例）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LaunchDateChangeRequest.class);
    }

    /** 主组=70 的在途项目；双签人同组才能过 R8X-2 横向越权防护。 */
    private Project project() {
        return Project.builder().id(70L).name("GUARD").status("ACTIVE").delFlag("0")
            .currentStage("VALID").mainGroupId(70L).build();
    }

    private LaunchDateChangeRequest pending() {
        return LaunchDateChangeRequest.builder()
            .id(501L).projectId(70L).proposedLaunchDate(DAY).previousLaunchDate(null)
            .reason("GTM 定档").proposerId(11L).proposerRole("MARKET_PM")
            .confirmerId(22L).confirmerRole("RD_PM")
            .status(LaunchDateChangeRequest.ST_PENDING_SECOND)
            .tenantId("000000").delFlag("0").version(0).build();
    }

    private void stubProposeHappy() {
        when(projectMapper.selectById(70L)).thenReturn(project());
        when(requestMapper.selectCount(any())).thenReturn(0L);
        when(requestMapper.insert(any(LaunchDateChangeRequest.class))).thenAnswer(inv -> {
            inv.getArgument(0, LaunchDateChangeRequest.class).setId(501L);
            return 1;
        });
    }

    private void propose() {
        service.propose(70L, DAY, "GTM 定档", 11L, "MARKET_PM", 70L, 22L, "RD_PM", 70L);
    }

    @Test
    @DisplayName("C1-1) propose 的 preCheck 经 guardSupport 委托且 entityType=launch_date_change")
    void preCheckDelegatedOnPropose() {
        service.setStateMachineGuard(stateMachineGuard);
        stubProposeHappy();

        propose();

        verify(stateMachineGuard).preCheck("launch_date_change", null, "PENDING_SECOND", "propose");
    }

    @Test
    @DisplayName("C1-2) registerPostCommit 经 guardSupport 委托（无事务降级立即 postCommit）")
    void postCommitDelegatedOnPropose() {
        service.setStateMachineGuard(stateMachineGuard);
        stubProposeHappy();

        propose();

        verify(stateMachineGuard).postCommit(eq("launch_date_change"), isNull(), eq("PENDING_SECOND"),
            eq("propose"), eq(11L), eq(501L), any(Date.class));
    }

    @Test
    @DisplayName("C1-3) 未注入守卫时 preCheck fail-closed，文案逐字钉死")
    void nullGuardFailsClosedOnPropose() {
        // 自证能红锚点：guardSupport.preCheck 调用点若被删/绕开，本用例必红
        when(projectMapper.selectById(70L)).thenReturn(project());
        when(requestMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(this::propose)
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机守卫未装配 entityType=launch_date_change from=null to=PENDING_SECOND");
    }

    @Test
    @DisplayName("C1-4) 在途单唯一预检走 assertNoInFlight，文案逐字保留且先于 preCheck")
    void inFlightPrecheckKeepsOriginalMessage() {
        service.setStateMachineGuard(stateMachineGuard);
        when(projectMapper.selectById(70L)).thenReturn(project());
        when(requestMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(this::propose)
            .isInstanceOf(ServiceException.class)
            .hasMessage("该项目已有待第二签确认的上市日期变更申请");

        verify(requestMapper, never()).insert(any(LaunchDateChangeRequest.class));
        verifyNoInteractions(stateMachineGuard);
    }

    @Test
    @DisplayName("C1-5) secondDecision 终态守卫前置走 requireFromState，文案逐字保留")
    void requireFromStateKeepsOriginalMessage() {
        LaunchDateChangeRequest done = LaunchDateChangeRequest.builder()
            .id(501L).projectId(70L).proposerId(11L).proposerRole("MARKET_PM")
            .confirmerId(22L).confirmerRole("RD_PM")
            .status(LaunchDateChangeRequest.ST_CONFIRMED)
            .tenantId("000000").delFlag("0").build();
        when(requestMapper.selectById(501L)).thenReturn(done);

        assertThatThrownBy(() -> service.secondDecision(501L, 22L, "RD_PM", 70L, true, "同意"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机不匹配：期望 PENDING_SECOND，实际 CONFIRMED");
    }

    @Test
    @DisplayName("C1-6) secondDecision 的 preCheck/postCommit 经 guardSupport 委托（secondSign 触发器）")
    void guardDelegatedOnSecondDecision() {
        service.setStateMachineGuard(stateMachineGuard);
        when(requestMapper.selectById(501L)).thenReturn(pending());
        when(projectMapper.selectById(70L)).thenReturn(project());
        when(requestMapper.updateById(any(LaunchDateChangeRequest.class))).thenReturn(1);
        when(projectMapper.updateById(any(Project.class))).thenReturn(1);

        service.secondDecision(501L, 22L, "RD_PM", 70L, true, "同意");

        verify(stateMachineGuard).preCheck("launch_date_change", "PENDING_SECOND", "CONFIRMED", "secondSign");
        verify(stateMachineGuard).postCommit(eq("launch_date_change"), eq("PENDING_SECOND"), eq("CONFIRMED"),
            eq("secondSign"), eq(22L), eq(501L), any(Date.class));
    }
}
