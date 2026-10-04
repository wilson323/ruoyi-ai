package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.BidResponse;
import org.ruoyi.ipd.domain.NegativeFeedback;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.dto.GuestDemandUpdateReq;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.mapper.BidResponseMapper;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementChangeMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-1 批次剩余 6 台状态机 Guard 接线行为锁（补遗 §5-2 二波）。
 *
 * <p>锁的是「接线契约」而非业务：每个迁移动作必须携带
 * (entityType, from, to, trigger) 四元组过 {@link StateMachineGuard#preCheck}，
 * 并在落库后登记 {@link StateMachineGuard#postCommit}（无事务上下文直接执行）；
 * 守卫未装配时迁移必须 fail-closed（抛「状态机守卫未装配」），防 state-machine-bypass。
 *
 * <p>entityType/trigger 词表与 DefaultStateMachineGuard 种子规则一一对应
 * （契约行见 StateMachineGuardContractTest 表驱动），词表漂移双侧必红。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StateMachineGuardD1WiringTest {

    private static final IpdActor ADMIN = new IpdActor(1L, "admin", "SUPER_ADMIN", 7L);

    @Mock private StateMachineGuard guard;

    // ---- project ----
    @Mock private ProjectMapper projectMapper;
    @Mock private ProductMapper productMapper;
    @Mock private StageActionMapper stageActionMapper;
    @Mock private KpiRecordMapper kpiRecordMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private GateEngine gateEngine;
    @Mock private ProjectBootstrapService projectBootstrapService;
    @Mock private IProjectCertService projectCertService;
    @Mock private PlatformTransactionManager txManager;
    @Mock private RequirementChangeService requirementChangeService;

    // ---- requirement_v2 ----
    @Mock private RequirementMapper requirementMapper;
    @Mock private RequirementChangeMapper requirementChangeMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;

    // ---- bid ----
    @Mock private BidInvitationMapper bidInvitationMapper;
    @Mock private BidResponseMapper bidResponseMapper;
    @Mock private NotificationService notificationService;

    // ---- guest_demand ----
    @Mock private PersonMapper personMapper;

    // ---- negative_feedback ----
    @Mock private NegativeFeedbackMapper negativeFeedbackMapper;

    /* ====================== project：changeStatus ====================== */

    private ProjectService projectService(boolean withGuard) {
        ProjectService s = new ProjectService(projectMapper, productMapper, stageActionMapper,
            kpiRecordMapper, auditLogService, gateEngine, txManager, requirementChangeService);
        if (withGuard) {
            s.setStateMachineGuard(guard);
        }
        return s;
    }

    @Test
    @DisplayName("project/changeStatus：preCheck(project,DRAFT,TEAMING,changeStatus)+postCommit 登记")
    void projectChangeStatusWired() {
        Project p = new Project();
        p.setStatus("DRAFT");
        p.setDelFlag("0");
        p.setMainGroupId(7L);
        when(projectMapper.selectById(42L)).thenReturn(p);
        projectService(true).changeStatus(42L, "TEAMING", 1L, 7L, "SUPER_ADMIN");
        verify(guard).preCheck("project", "DRAFT", "TEAMING", "changeStatus");
        verify(guard).postCommit(eq("project"), eq("DRAFT"), eq("TEAMING"), eq("changeStatus"),
            eq(1L), eq(42L), any(Date.class));
    }

    @Test
    @DisplayName("project/changeStatus：守卫未装配 fail-closed")
    void projectChangeStatusFailClosed() {
        Project p = new Project();
        p.setStatus("DRAFT");
        p.setDelFlag("0");
        p.setMainGroupId(7L);
        when(projectMapper.selectById(42L)).thenReturn(p);
        assertThatThrownBy(() -> projectService(false).changeStatus(42L, "TEAMING", 1L, 7L, "SUPER_ADMIN"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机守卫未装配");
    }

    /* ====================== requirement_v2：transition ====================== */

    private RequirementStateMachine requirementSm(boolean withGuard) {
        RequirementStateMachine sm = new RequirementStateMachine(requirementMapper,
            requirementChangeMapper, projectMapper, projectMemberMapper, auditLogService);
        if (withGuard) {
            sm.setStateMachineGuard(guard);
        }
        return sm;
    }

    @Test
    @DisplayName("requirement_v2/transition：preCheck(requirement_v2,DRAFT,SUBMITTED,transition)+postCommit 登记")
    void requirementTransitionWired() {
        Requirement req = new Requirement();
        req.setStatus("DRAFT");
        req.setProjectId(10L);
        when(requirementMapper.selectById(42L)).thenReturn(req);
        Project proj = new Project();
        proj.setMainGroupId(7L);
        when(projectMapper.selectById(10L)).thenReturn(proj);
        requirementSm(true).transition(42L, "SUBMITTED", "r", ADMIN);
        verify(guard).preCheck("requirement_v2", "DRAFT", "SUBMITTED", "transition");
        verify(guard).postCommit(eq("requirement_v2"), eq("DRAFT"), eq("SUBMITTED"), eq("transition"),
            eq(1L), eq(42L), any(Date.class));
    }

    @Test
    @DisplayName("requirement_v2/transition：守卫未装配 fail-closed")
    void requirementTransitionFailClosed() {
        Requirement req = new Requirement();
        req.setStatus("DRAFT");
        req.setProjectId(10L);
        when(requirementMapper.selectById(42L)).thenReturn(req);
        Project proj = new Project();
        proj.setMainGroupId(7L);
        when(projectMapper.selectById(10L)).thenReturn(proj);
        assertThatThrownBy(() -> requirementSm(false).transition(42L, "SUBMITTED", "r", ADMIN))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机守卫未装配");
    }

    /* ====================== bid_invitation：create + close ====================== */

    private BidInvitationService bidInvitationService(boolean withGuard) {
        BidInvitationService s = new BidInvitationService(bidInvitationMapper, bidResponseMapper,
            auditLogService, notificationService);
        if (withGuard) {
            s.setStateMachineGuard(guard);
        }
        return s;
    }

    @Test
    @DisplayName("bid_invitation/create：preCheck(bid_invitation,null→INITIAL,OPEN,create)")
    void bidInvitationCreateWired() {
        BidInvitation inv = new BidInvitation();
        bidInvitationService(true).create(inv);
        verify(guard).preCheck("bid_invitation", null, "OPEN", "create");
        verify(guard).postCommit(eq("bid_invitation"), isNull(), eq("OPEN"), eq("create"),
            isNull(), isNull(), any(Date.class));
    }

    @Test
    @DisplayName("bid_invitation/close：preCheck(bid_invitation,OPEN,CLOSED,close) 宽进通配边")
    void bidInvitationCloseWired() {
        BidInvitation inv = new BidInvitation();
        inv.setStatus("OPEN");
        when(bidInvitationMapper.selectById(42L)).thenReturn(inv);
        bidInvitationService(true).close(42L);
        verify(guard).preCheck("bid_invitation", "OPEN", "CLOSED", "close");
        verify(guard).postCommit(eq("bid_invitation"), eq("OPEN"), eq("CLOSED"), eq("close"),
            any(), eq(42L), any(Date.class));
    }

    @Test
    @DisplayName("bid_invitation/close：守卫未装配 fail-closed")
    void bidInvitationCloseFailClosed() {
        BidInvitation inv = new BidInvitation();
        inv.setStatus("OPEN");
        when(bidInvitationMapper.selectById(42L)).thenReturn(inv);
        assertThatThrownBy(() -> bidInvitationService(false).close(42L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机守卫未装配");
    }

    /* ====================== bid_response：withdraw ====================== */

    private BidResponseService bidResponseService(boolean withGuard) {
        BidResponseService s = new BidResponseService(bidResponseMapper, bidInvitationMapper,
            projectMemberMapper, auditLogService);
        if (withGuard) {
            s.setStateMachineGuard(guard);
        }
        return s;
    }

    @Test
    @DisplayName("bid_response/withdraw：preCheck(bid_response,PENDING,WITHDRAWN,withdraw)")
    void bidResponseWithdrawWired() {
        BidResponse resp = new BidResponse();
        resp.setRdPmId(7L);
        resp.setStatus("PENDING");
        when(bidResponseMapper.selectById(100L)).thenReturn(resp);
        bidResponseService(true).withdraw(new IpdActor(7L, "rd", "RD_PM", 7L), 100L);
        verify(guard).preCheck("bid_response", "PENDING", "WITHDRAWN", "withdraw");
        verify(guard).postCommit(eq("bid_response"), eq("PENDING"), eq("WITHDRAWN"), eq("withdraw"),
            eq(7L), eq(100L), any(Date.class));
    }

    @Test
    @DisplayName("bid_response/withdraw：守卫未装配 fail-closed")
    void bidResponseWithdrawFailClosed() {
        BidResponse resp = new BidResponse();
        resp.setRdPmId(7L);
        resp.setStatus("PENDING");
        when(bidResponseMapper.selectById(100L)).thenReturn(resp);
        assertThatThrownBy(() -> bidResponseService(false)
                .withdraw(new IpdActor(7L, "rd", "RD_PM", 7L), 100L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机守卫未装配");
    }

    /* ====================== guest_demand：withdraw ====================== */

    private GuestDemandService guestDemandService(boolean withGuard) {
        GuestDemandService s = new GuestDemandService(requirementMapper, productMapper,
            projectMemberMapper, auditLogService, GuestRateLimiterAllowAll.INSTANCE);
        if (withGuard) {
            s.setStateMachineGuard(guard);
        }
        return s;
    }

    /** 测试用放行限流器。 */
    enum GuestRateLimiterAllowAll implements GuestDemandService.GuestRateLimiter {
        INSTANCE;
        @Override
        public boolean tryAcquire(String ipHash) {
            return true;
        }
    }

    @Test
    @DisplayName("guest_demand/withdraw：preCheck(guest_demand,SUBMITTED,WITHDRAWN,withdraw)")
    void guestDemandWithdrawWired() {
        Requirement r = new Requirement();
        r.setStatus("SUBMITTED");
        r.setQueryCode("ABCD2345");
        r.setTitle("t");
        when(requirementMapper.selectOne(any())).thenReturn(r);
        guestDemandService(true).withdraw("ABCD2345", new GuestDemandUpdateReq("WITHDRAW", null, null),
            "1.2.3.4", "junit");
        verify(guard).preCheck("guest_demand", "SUBMITTED", "WITHDRAWN", "withdraw");
        verify(guard).postCommit(eq("guest_demand"), eq("SUBMITTED"), eq("WITHDRAWN"), eq("withdraw"),
            any(), any(), any(Date.class));
    }

    @Test
    @DisplayName("guest_demand/withdraw：守卫未装配 fail-closed")
    void guestDemandWithdrawFailClosed() {
        Requirement r = new Requirement();
        r.setStatus("SUBMITTED");
        r.setQueryCode("ABCD2345");
        r.setTitle("t");
        when(requirementMapper.selectOne(any())).thenReturn(r);
        assertThatThrownBy(() -> guestDemandService(false)
                .withdraw("ABCD2345", new GuestDemandUpdateReq("WITHDRAW", null, null), "1.2.3.4", "junit"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机守卫未装配");
    }

    /* ====================== negative_feedback：submit ====================== */

    private NegativeFeedbackService negativeFeedbackService(boolean withGuard) {
        NegativeFeedbackService s = new NegativeFeedbackService(negativeFeedbackMapper);
        if (withGuard) {
            s.setStateMachineGuard(guard);
        }
        return s;
    }

    @Test
    @DisplayName("negative_feedback/submit：preCheck(negative_feedback,DRAFT,PENDING_DECISION,submit)")
    void negativeFeedbackSubmitWired() {
        NegativeFeedback row = NegativeFeedback.builder().id(9L).status("DRAFT").build();
        when(negativeFeedbackMapper.selectById(9L)).thenReturn(row);
        when(negativeFeedbackMapper.updateById(row)).thenReturn(1);
        NegativeFeedback in = NegativeFeedback.builder().id(9L).build();
        assertThat(negativeFeedbackService(true).submit(in)).isTrue();
        verify(guard).preCheck("negative_feedback", "DRAFT", "PENDING_DECISION", "submit");
        verify(guard).postCommit(eq("negative_feedback"), eq("DRAFT"), eq("PENDING_DECISION"), eq("submit"),
            any(), eq(9L), any(Date.class));
    }

    @Test
    @DisplayName("negative_feedback/submit：守卫未装配 fail-closed")
    void negativeFeedbackSubmitFailClosed() {
        NegativeFeedback row = NegativeFeedback.builder().id(9L).status("DRAFT").build();
        when(negativeFeedbackMapper.selectById(9L)).thenReturn(row);
        NegativeFeedback in = NegativeFeedback.builder().id(9L).build();
        assertThatThrownBy(() -> negativeFeedbackService(false).submit(in))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机守卫未装配");
    }
}
