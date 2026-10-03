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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateArbitration;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.GateArbitrationMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.GateReviewObserverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R33 一期分片D：GateReviewService → ApprovalGuardSupport 组合迁移契约测试（自证能红）。
 *
 * <p>迁移映射与「拆委托必红」对照（把对应委托点拆掉/绕过 guardSupport，下列用例必红）：
 * <ul>
 *   <li>{@code guardSupport.preCheck} 委托（settle/reopen/settleTimeout）→
 *       {@link #sign_dualApprove_delegatesPreCheckAndPostCommitWithFrozenEntityType}、
 *       {@link #sign_reject_delegatesPreCheckAndPostCommitRejected}、
 *       {@link #reopen_casHit_keepsRoundPlusOneAndGuardWiring} 必红</li>
 *   <li>{@code guardSupport.registerPostCommit} 委托（无事务降级立即触发）→ 同上两 sign 用例必红</li>
 *   <li>{@code registerPostCommit} 事务同步 afterCommit 延迟车道 →
 *       {@link #registerPostCommit_defersToAfterCommit_whenTransactionSynchronized} 必红</li>
 *   <li>fail-closed（guard 未装配不放行）→ {@link #preCheck_failClosedWhenGuardNotWired} 必红</li>
 *   <li>{@code setClock} 同步转发 guardSupport（postCommit occurredAt 与 now() 同源）→
 *       {@link #setClock_singleInjectionPointDrivesSignedAtAuditAndPostCommitTime} 必红</li>
 *   <li>{@code requireCasHit} 判定收敛（reopen CAS）→ {@link #reopen_casMiss_conflictMessageVerbatim} 必红</li>
 *   <li>{@code requireFromState} 判定收敛（settle 终态守卫前置）→
 *       {@link #settle_nonPendingFromState_failClosedWithVerbatimMessage} 必红（防御死分支无公开入口，
 *       以反射钉死——见该用例注释）</li>
 * </ul>
 *
 * <p>R-3 边界哨兵：{@link #sign_singleApproveInFlight_doesNotTouchGuard} 钉死盲签在途
 * （一方未签 ⇒ 零 guard 触达、零终态推进），防骨架迁移顺手改写签署语义。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateReviewGuardSupportContractTest {

    @Mock
    private GateMapper gateMapper;
    @Mock
    private GateReviewMapper reviewMapper;
    @Mock
    private ProjectMemberMapper memberMapper;
    @Mock
    private PersonMapper personMapper;
    @Mock
    private GateArbitrationMapper arbitrationMapper;
    @Mock
    private GateReviewObserverMapper observerMapper;
    @Mock
    private ISystemConfigService systemConfigService;
    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private NotificationService notificationService;
    /** sign() 的 gate→项目→组 归属断言所需（R212-④ 同款注入点）。 */
    @Mock
    private ProjectMapper projectMapper;

    private StateMachineGuard guard;
    private GateReviewService service;

    private static final IpdActor MARKET = new IpdActor(301L, "陈市场", "MARKET_PM", 7L);
    private static final IpdActor RD = new IpdActor(302L, "刘研发", "RD_PM", 7L);

    private Gate gate;
    /** 签名簿：insert 落行、roundRows 动态读取，支撑 sign→advance→settle 全链 */
    private final List<GateReview> signedRows = new ArrayList<>();

    @BeforeAll
    static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "GRGSC-gr"), GateReview.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "GRGSC-gate"), Gate.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "GRGSC-pm"), ProjectMember.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "GRGSC-ga"), GateArbitration.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "GRGSC-person"), Person.class);
    }

    @BeforeEach
    void setUp() {
        service = new GateReviewService(gateMapper, reviewMapper, memberMapper,
            personMapper, arbitrationMapper, observerMapper, systemConfigService, auditLogService, notificationService);
        // 归属断言 fail-closed：未装配 ProjectMapper 一律「无权操作」，故必须注入。
        service.setProjectMapper(projectMapper);
        lenient().when(projectMapper.selectById(11L))
            .thenReturn(Project.builder().id(11L).mainGroupId(MARKET.groupId())
                .status("ACTIVE").delFlag("0").build());
        guard = mock(StateMachineGuard.class);
        service.setStateMachineGuard(guard);
        gate = new Gate();
        gate.setId(701L);
        gate.setProjectId(11L);
        gate.setGateCode("G1");
        gate.setStatus("PENDING");
        gate.setCurrentRound(1);
        gate.setStartedAt(new Date());
        gate.setSignDueAt(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3)));
        signedRows.clear();

        lenient().when(gateMapper.selectById(701L)).thenReturn(gate);
        lenient().when(reviewMapper.insert(any(GateReview.class))).thenAnswer(inv -> {
            signedRows.add(inv.getArgument(0));
            return 1;
        });
        lenient().when(reviewMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(signedRows));
        lenient().when(memberMapper.selectList(any())).thenReturn(List.of());
        lenient().when(systemConfigService.getIntValue(eq("gate.signDeadlineDays"), eq(3))).thenReturn(3);
    }

    // ---- ① preCheck / registerPostCommit 委托（冻结 entityType="gate_review"） ----

    @Test
    @DisplayName("①双 APPROVE 终态：preCheck/postCommit 委托 guardSupport，entityType/迁移/trigger 逐字冻结")
    void sign_dualApprove_delegatesPreCheckAndPostCommitWithFrozenEntityType() {
        service.sign(701L, "APPROVE", "市场侧同意", MARKET);

        service.sign(701L, "APPROVE", "研发侧同意", RD);

        verify(guard).preCheck("gate_review", "PENDING", "APPROVED", "sign");
        verify(guard).postCommit(eq("gate_review"), eq("PENDING"), eq("APPROVED"), eq("sign"),
            eq(302L), eq(701L), any(Date.class));
    }

    @Test
    @DisplayName("①REJECTED 终态：PENDING→REJECTED|sign 同样走 guardSupport 委托")
    void sign_reject_delegatesPreCheckAndPostCommitRejected() {
        service.sign(701L, "APPROVE", null, MARKET);

        service.sign(701L, "REJECT", "基准值缺失", RD);

        verify(guard).preCheck("gate_review", "PENDING", "REJECTED", "sign");
        verify(guard).postCommit(eq("gate_review"), eq("PENDING"), eq("REJECTED"), eq("sign"),
            eq(302L), eq(701L), any(Date.class));
    }

    @Test
    @DisplayName("①fail-closed：guard 未装配 ⇒ 第二签落终态被拦（不放行），文案含 entityType")
    void preCheck_failClosedWhenGuardNotWired() {
        GateReviewService raw = new GateReviewService(gateMapper, reviewMapper, memberMapper,
            personMapper, arbitrationMapper, observerMapper, systemConfigService, auditLogService, notificationService);
        // 归属断言照常装配：本用例只验「状态机守卫未装配」这一条 fail-closed 路径，
        // 不应被 sign() 的项目归属守卫先行拦下而假绿。
        raw.setProjectMapper(projectMapper);
        raw.sign(701L, "APPROVE", null, MARKET);

        assertThatThrownBy(() -> raw.sign(701L, "APPROVE", null, RD))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("状态机守卫未装配")
            .hasMessageContaining("gate_review");
    }

    // ---- ② 事务车道保留（六连拷贝「无事务降级」双路径） ----

    @Test
    @DisplayName("②事务同步激活时 postCommit 延迟到 afterCommit，不落事务中即时触发")
    void registerPostCommit_defersToAfterCommit_whenTransactionSynchronized() {
        gate.setGateCode("G2"); // 单签主导 Gate：一次 sign 即 settle

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.sign(701L, "APPROVE", null, MARKET);

            verify(guard, never()).postCommit(any(), any(), any(), any(), any(), any(), any());
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
            verify(guard).postCommit(eq("gate_review"), eq("PENDING"), eq("APPROVED"), eq("sign"),
                eq(301L), eq(701L), any(Date.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---- ③ setClock 注入点兼容 + 同源时刻 ----

    @Test
    @DisplayName("③setClock 单注入点：signedAt/审计 createTime/postCommit occurredAt 同一固定时刻")
    void setClock_singleInjectionPointDrivesSignedAtAuditAndPostCommitTime() {
        gate.setGateCode("G2");
        Instant fixedInstant = Instant.parse("2026-09-27T08:00:00Z");
        service.setClock(Clock.fixed(fixedInstant, ZoneId.systemDefault()));
        Date expected = Date.from(fixedInstant);

        service.sign(701L, "APPROVE", "同意", MARKET);

        ArgumentCaptor<GateReview> rowCap = ArgumentCaptor.forClass(GateReview.class);
        verify(reviewMapper).insert(rowCap.capture());
        assertThat(rowCap.getValue().getSignedAt()).isEqualTo(expected);

        ArgumentCaptor<AuditLog> auditCap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, atLeastOnce()).append(auditCap.capture());
        assertThat(auditCap.getAllValues()).allSatisfy(a -> assertThat(a.getCreateTime()).isEqualTo(expected));

        ArgumentCaptor<Date> occurredCap = ArgumentCaptor.forClass(Date.class);
        verify(guard).postCommit(any(), any(), any(), any(), any(), any(), occurredCap.capture());
        assertThat(occurredCap.getValue())
            .as("occurredAt 必须来自 setClock 注入时刻（guardSupport.setClock 委托拆掉即红）")
            .isEqualTo(expected);
    }

    // ---- ④ reopen CAS 判定收敛 requireCasHit（文案逐字保真） ----

    @Test
    @DisplayName("④reopen CAS 未命中：requireCasHit 抛冲突，文案逐字=起点守卫原文")
    void reopen_casMiss_conflictMessageVerbatim() {
        gate.setStatus("REJECTED");
        when(gateMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reopen(701L, MARKET))
            .isInstanceOf(ServiceException.class)
            .hasMessage("仅被驳回或双弃权超时的 Gate 可重新发起，当前：REJECTED");
    }

    @Test
    @DisplayName("④reopen CAS 命中：round+1 语义零变更（R-3 重评轮次），preCheck/postCommit 接线钉死")
    void reopen_casHit_keepsRoundPlusOneAndGuardWiring() {
        gate.setStatus("REJECTED");
        when(gateMapper.update(any(), any())).thenReturn(1);

        service.reopen(701L, MARKET);

        verify(guard).preCheck("gate_review", "REJECTED", "PENDING", "reopen");
        verify(guard).postCommit(eq("gate_review"), eq("REJECTED"), eq("PENDING"), eq("reopen"),
            eq(301L), eq(701L), any(Date.class));
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, atLeastOnce()).append(cap.capture());
        AuditLog reopenAudit = cap.getAllValues().stream()
            .filter(a -> "GATE_REOPEN".equals(a.getAction())).findFirst().orElseThrow();
        assertThat(reopenAudit.getAfterData()).contains("\"round\":2");
    }

    // ---- ⑤ settle 终态守卫前置收敛 requireFromState（防御死分支） ----

    @Test
    @DisplayName("⑤settle 非 PENDING 起点：requireFromState fail-closed，文案逐字=终态守卫原文")
    void settle_nonPendingFromState_failClosedWithVerbatimMessage() throws Exception {
        // 说明：settle 为私有终态收口，唯一调用链 sign→advance 前置 requireSubmitted 恒保证 PENDING，
        // 非 PENDING 分支是防御死路径，无公开入口可构造——以反射钉死该分支的收敛形态与文案，
        // 防止后续重构把 requireFromState 悄悄回退成静默 if-skip（回退即红）。
        gate.setStatus("REJECTED");
        Method settle = GateReviewService.class.getDeclaredMethod("settle",
            Gate.class, String.class, IpdActor.class, List.class);
        settle.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
            () -> settle.invoke(service, gate, "APPROVED", MARKET, List.<GateReview>of()));

        assertThat(ex.getCause())
            .isInstanceOf(ServiceException.class)
            .hasMessage("Gate 已终态（REJECTED），不可签署");
        verify(guard, never()).preCheck(any(), any(), any(), any());
    }

    // ---- ⑥ R-3 边界哨兵：盲签在途零触达 ----

    @Test
    @DisplayName("⑥R-3 盲签保持：双签 Gate 一方在途 ⇒ 零 guard 触达、零终态推进（防迁移顺手改签署语义）")
    void sign_singleApproveInFlight_doesNotTouchGuard() {
        service.sign(701L, "APPROVE", null, MARKET);

        verify(guard, never()).preCheck(any(), any(), any(), any());
        verify(guard, never()).postCommit(any(), any(), any(), any(), any(), any(), any());
        verify(gateMapper, never()).updateById(any(Gate.class));
        assertThat(gate.getStatus()).isEqualTo("PENDING");
    }
}
