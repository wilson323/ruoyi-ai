package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R33 ChainSpec 一期（分片 C3）契约测试：DeletionRequestServiceImpl → ApprovalGuardSupport 骨架替换。
 *
 * <p>钉死三类契约（拆委托即红，见迁移报告「自证能红方案」）：
 * <ol>
 *   <li>六连拷贝收编后接线不漂移：preCheck/registerPostCommit 的 (entityType, from, to, trigger,
 *       operatorId, entityId) 六元组与迁移前逐字一致（含 withdrawIfExistsOrNotFound 的 from
 *       历史缺陷快照——行为零变更保真，勿"顺手修"）。</li>
 *   <li>终态守卫 requireFromState 收敛后异常类型（ServiceException）与文案逐字不变。</li>
 *   <li>setClock 不双投 guardSupport：postCommit occurredAt 沿用墙钟（与迁移前
 *       {@code new java.util.Date()} 逐字等价）。</li>
 * </ol>
 *
 * <p>escalate 批量 CAS miss→静默短路语义另钉一条（故意不收敛 requireCasHit，§3 escalate 零触碰）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DeletionRequestGuardSupportContractTest {

    @Mock
    private DeletionRequestMapper deletionRequestMapper;
    @Mock
    private ISystemConfigService systemConfigService;
    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private DeleteAuditService deleteAuditService;
    @Mock
    private StateMachineGuard stateMachineGuard;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private GateMapper gateMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private PersonMapper personMapper;

    private static final IpdActor ACTOR_REQUESTER = new IpdActor(1L, "市场PM甲", "MARKET_PM", null);
    private static final IpdActor ACTOR_ADMIN = new IpdActor(2L, "超管", "SUPER_ADMIN", null);

    /** 纯 JVM 单测无 MP 运行时：手动初始化 lambda 列缓存（与 DeletionRequestServiceTest 同口径）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DeletionRequest.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
    }

    private DeletionRequestServiceImpl newServiceWithGuard() {
        DeletionRequestServiceImpl service = new DeletionRequestServiceImpl(
            deletionRequestMapper, systemConfigService, auditLogService, deleteAuditService,
            projectMemberMapper, projectMapper, gateMapper, productMapper, personMapper);
        service.setStateMachineGuard(stateMachineGuard);
        return service;
    }

    private DeletionRequest saved(Long id, String status, Date createTime) {
        DeletionRequest request = DeletionRequest.builder()
            .id(id).entityType("projects").entityId(100L).reason("测试删除")
            .requesterId(1L).status(status).build();
        request.setCreateTime(createTime);
        return request;
    }

    // ===== ① 六连收编后的接线契约（六元组逐字） =====

    @Test
    @DisplayName("C3-契约1：submit 接线 preCheck/registerPostCommit 六元组与迁移前逐字一致")
    void submitWiresPreCheckAndPostCommit() {
        when(systemConfigService.getIntValue("deletion.leaderDeadlineDays", 2)).thenReturn(2);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        DeletionRequestServiceImpl service = newServiceWithGuard();

        service.submit(ACTOR_REQUESTER, "projects", 100L, "{}", "测试删除");

        verify(stateMachineGuard).preCheck("deletion_request", "DRAFT", "LEADER_REVIEW", "submit");
        verify(stateMachineGuard).postCommit(eq("deletion_request"), eq("DRAFT"), eq("LEADER_REVIEW"),
            eq("submit"), eq(1L), nullable(Long.class), any(Date.class));
    }

    @Test
    @DisplayName("C3-契约2：adminApprove 时序 preCheck 先于原子软删、postCommit 在后（跨域原子软删零触碰）")
    void adminApprovePreCheckBeforeAtomicSoftDelete() {
        when(deletionRequestMapper.selectById(9L)).thenReturn(saved(9L, "ADMIN_REVIEW", new Date()));
        DeletionRequest executed = saved(9L, "DELETED", new Date());
        when(deleteAuditService.approveAndExecute(9L, 2L)).thenReturn(executed);
        DeletionRequestServiceImpl service = newServiceWithGuard();

        DeletionRequest after = service.adminDecision(ACTOR_ADMIN, 9L, true, "同意删除");

        assertThat(after.getStatus()).isEqualTo("DELETED");
        InOrder inOrder = inOrder(stateMachineGuard, deleteAuditService);
        inOrder.verify(stateMachineGuard).preCheck("deletion_request", "ADMIN_REVIEW", "DELETED", "adminApprove");
        inOrder.verify(deleteAuditService).approveAndExecute(9L, 2L);
        inOrder.verify(stateMachineGuard).postCommit(eq("deletion_request"), eq("ADMIN_REVIEW"), eq("DELETED"),
            eq("adminApprove"), eq(2L), eq(9L), any(Date.class));
    }

    @Test
    @DisplayName("C3-契约3：adminReject 接线 postCommit 六元组（R5 crossDomain 驳回通知路径不回归）")
    void adminRejectWiresPostCommit() {
        when(deletionRequestMapper.selectById(9L)).thenReturn(saved(9L, "ADMIN_REVIEW", new Date()));
        DeletionRequestServiceImpl service = newServiceWithGuard();

        service.adminDecision(ACTOR_ADMIN, 9L, false, "不同意");

        verify(stateMachineGuard).preCheck("deletion_request", "ADMIN_REVIEW", "REJECTED", "adminReject");
        verify(stateMachineGuard).postCommit(eq("deletion_request"), eq("ADMIN_REVIEW"), eq("REJECTED"),
            eq("adminReject"), eq(2L), eq(9L), any(Date.class));
    }

    @Test
    @DisplayName("C3-契约4：withdraw 接线 from=迁移前快照（setStatus 之前捕获）")
    void withdrawWiresGuardWithFromBeforeMutation() {
        when(deletionRequestMapper.selectById(9L)).thenReturn(saved(9L, "LEADER_REVIEW", new Date()));
        when(systemConfigService.getIntValue("deletion.withdrawHours", 24)).thenReturn(24);
        DeletionRequestServiceImpl service = newServiceWithGuard();

        service.withdraw(9L, 1L);

        verify(stateMachineGuard).preCheck("deletion_request", "LEADER_REVIEW", "WITHDRAWN", "withdraw");
        verify(stateMachineGuard).postCommit(eq("deletion_request"), eq("LEADER_REVIEW"), eq("WITHDRAWN"),
            eq("withdraw"), eq(1L), eq(9L), any(Date.class));
    }

    @Test
    @DisplayName("C3-契约5：withdrawIfExistsOrNotFound 的 postCommit from=WITHDRAWN——历史缺陷行为快照（零变更保真）")
    void withdrawIfExistsOrNotFoundPostCommitFromSnapshot() {
        // 历史缺陷（R33 登记，勿"顺手修"）：registerPostCommit 位于 setStatus 之后，
        // from 被污染为 WITHDRAWN（迁移前六连拷贝即如此）。一期红线=行为零变更，故钉快照；
        // 若二期修复该缺陷，须同步更新本断言并在 PR 说明。
        when(deletionRequestMapper.selectById(9L)).thenReturn(saved(9L, "LEADER_REVIEW", new Date()));
        when(systemConfigService.getIntValue("deletion.withdrawHours", 24)).thenReturn(24);
        DeletionRequestServiceImpl service = newServiceWithGuard();

        service.withdrawIfExistsOrNotFound(ACTOR_REQUESTER, 9L);

        verify(stateMachineGuard).preCheck("deletion_request", "LEADER_REVIEW", "WITHDRAWN", "withdraw");
        verify(stateMachineGuard).postCommit(eq("deletion_request"), eq("WITHDRAWN"), eq("WITHDRAWN"),
            eq("withdraw"), eq(1L), eq(9L), any(Date.class));
    }

    // ===== ② requireFromState 收敛：异常类型与文案逐字 =====

    @Test
    @DisplayName("C3-契约6：requireFromState 收敛后 ServiceException 文案逐字不变")
    void requireFromStateViolationMessageVerbatim() {
        when(deletionRequestMapper.selectById(9L)).thenReturn(saved(9L, "REJECTED", new Date()));
        DeletionRequestServiceImpl service = newServiceWithGuard();

        assertThatThrownBy(() -> service.adminDecision(ACTOR_ADMIN, 9L, true, "x"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机不匹配：期望 ADMIN_REVIEW，实际 REJECTED");
    }

    // ===== ③ escalate 语义钉死（§3 零触碰：CAS miss 静默短路不收敛 requireCasHit） =====

    @Test
    @DisplayName("C3-契约7：escalate preCheck 前置于批量 UPDATE（2026-09-09 前移修复不回归）")
    void escalatePreCheckBeforeBatchUpdate() {
        DeletionRequest overdue = saved(11L, "LEADER_REVIEW", new Date());
        overdue.setLeaderDueAt(new Date(System.currentTimeMillis() - 86400_000L));
        when(deletionRequestMapper.selectList(any())).thenReturn(List.of(overdue));
        when(deletionRequestMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(systemConfigService.getIntValue("deletion.adminDeadlineDays", 2)).thenReturn(2);
        DeletionRequestServiceImpl service = newServiceWithGuard();

        int count = service.escalateOverdueLeaderReview();

        assertThat(count).isEqualTo(1);
        InOrder inOrder = inOrder(stateMachineGuard, deletionRequestMapper);
        inOrder.verify(stateMachineGuard).preCheck("deletion_request", "LEADER_REVIEW", "ADMIN_REVIEW", "escalateOverdue");
        inOrder.verify(deletionRequestMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("C3-契约8：escalate CAS miss→静默 return 0（故意不收敛 requireCasHit——其 miss 抛冲突，语义相反）")
    void escalateCasMissSilentlyShortCircuits() {
        // escalate 语义零触碰红线：批量 CAS miss（并发方抢先处置）是「无人受影响」而非「冲突」，
        // 迁移前为静默短路 return 0；requireCasHit 的 miss→抛 ServiceException 与之相反，
        // 收敛即行为变更。此处钉死现状：返回 0、不抛、零审计。
        DeletionRequest overdue = saved(11L, "LEADER_REVIEW", new Date());
        overdue.setLeaderDueAt(new Date(System.currentTimeMillis() - 86400_000L));
        when(deletionRequestMapper.selectList(any())).thenReturn(List.of(overdue));
        when(deletionRequestMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        when(systemConfigService.getIntValue("deletion.adminDeadlineDays", 2)).thenReturn(2);
        DeletionRequestServiceImpl service = newServiceWithGuard();

        int count = service.escalateOverdueLeaderReview();

        assertThat(count).isEqualTo(0);
        verify(auditLogService, never()).append(nullable(Long.class), any(), any(), any(), any());
        verify(stateMachineGuard, never()).postCommit(any(), any(), any(), any(),
            nullable(Long.class), nullable(Long.class), any(Date.class));
    }

    // ===== ④ fail-closed 与 Clock 投递口径 =====

    @Test
    @DisplayName("C3-契约9：守卫未装配 fail-closed（ServiceException 逐字文案，拆委托即红）")
    void preCheckFailClosedWithoutGuard() {
        DeletionRequestServiceImpl bare = new DeletionRequestServiceImpl(
            deletionRequestMapper, systemConfigService, auditLogService, deleteAuditService,
            projectMemberMapper, projectMapper, gateMapper, productMapper, personMapper);
        // 故意不调用 setStateMachineGuard——fail-closed 语义由 ApprovalGuardSupport 冻结保证
        when(deletionRequestMapper.selectById(9L)).thenReturn(saved(9L, "ADMIN_REVIEW", new Date()));

        assertThatThrownBy(() -> bare.adminDecision(ACTOR_ADMIN, 9L, true, "x"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机守卫未装配 entityType=deletion_request from=ADMIN_REVIEW to=DELETED");
    }

    @Test
    @DisplayName("C3-契约10：setClock 故意不双投 guardSupport——postCommit occurredAt 沿用墙钟（迁移前 new Date() 语义）")
    void setClockNotForwardedOccurredAtStaysWallClock() {
        when(systemConfigService.getIntValue("deletion.leaderDeadlineDays", 2)).thenReturn(2);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        DeletionRequestServiceImpl service = newServiceWithGuard();
        // 固定在 2000 年：若有人把 setClock 误改为双投 guardSupport，occurredAt 会变成 2000 → 本条红
        service.setClock(Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneId.systemDefault()));

        service.submit(ACTOR_REQUESTER, "projects", 100L, "{}", "测试删除");

        ArgumentCaptor<Date> occurredAt = ArgumentCaptor.forClass(Date.class);
        verify(stateMachineGuard).postCommit(eq("deletion_request"), eq("DRAFT"), eq("LEADER_REVIEW"),
            eq("submit"), eq(1L), nullable(Long.class), occurredAt.capture());
        // 墙钟（≈2026）而非注入的 2000——与迁移前 registerPostCommit 内 new java.util.Date() 逐字等价
        assertThat(occurredAt.getValue().getTime())
            .isGreaterThan(Date.from(Instant.parse("2010-01-01T00:00:00Z")).getTime());
    }
}
