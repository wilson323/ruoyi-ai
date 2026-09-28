package org.ruoyi.ipd.approval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.service.StateMachineGuard;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * R33 一期：{@link ApprovalGuardSupport} 冻结 API 语义契约测试（@Tag("dev") 必须——
 * Surefire 按 active profile（dev）过滤 groups，缺 tag=静默跳过假绿）。
 *
 * <p>覆盖：①guard 未装配 preCheck fail-closed 消息逐字；②guard==null 时 registerPostCommit
 * 静默；③有事务同步/无事务两路径 postCommit 参数与 occurredAt（setClock 固定时间断言）；
 * ④requireFromState/assertNoInFlight/requireCasHit 三判定正反例；⑤不同 entityType 隔离
 * （preCheck 传参正确）。
 */
@Tag("dev")
@DisplayName("R33 审批链共享骨架 ApprovalGuardSupport 冻结 API 契约")
@ExtendWith(MockitoExtension.class)
class ApprovalGuardSupportTest {

    /** 固定测试时刻（setClock 摇摆消除） */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-27T08:30:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final Date FIXED_DATE = Date.from(FIXED_INSTANT);

    private static final String LDC = "launch_date_change";
    private static final String COEF = "coefficient_change";

    @Mock
    private StateMachineGuard guard;

    @AfterEach
    void clearTxSynchronization() {
        // 测试间隔离：清理可能残留的事务同步（initSynchronization 路径异常中断兜底）
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ApprovalGuardSupport support(String entityType) {
        ApprovalGuardSupport s = new ApprovalGuardSupport(entityType);
        s.setStateMachineGuard(guard);
        s.setClock(FIXED_CLOCK);
        return s;
    }

    /* ---------- ① preCheck fail-closed（guard 未装配）与委托 ---------- */

    @Test
    @DisplayName("① guard 未装配 preCheck fail-closed：消息格式逐字（entityType/from/to）")
    void preCheckWithoutGuardFailsClosedWithExactMessage() {
        ApprovalGuardSupport s = new ApprovalGuardSupport(LDC);
        assertThatThrownBy(() -> s.preCheck("DRAFT", "PENDING_SECOND", "LAUNCH_DATE_PROPOSE"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机守卫未装配 entityType=launch_date_change from=DRAFT to=PENDING_SECOND");
    }

    @Test
    @DisplayName("① guard 已装配 preCheck 委托守卫：四参传入逐字")
    void preCheckDelegatesToGuardWithFourArgs() {
        support(LDC).preCheck("DRAFT", "PENDING_SECOND", "LAUNCH_DATE_PROPOSE");
        verify(guard).preCheck(LDC, "DRAFT", "PENDING_SECOND", "LAUNCH_DATE_PROPOSE");
    }

    /* ---------- ② guard==null 时 registerPostCommit 静默 ---------- */

    @Test
    @DisplayName("② guard 未装配 registerPostCommit 静默：无事务路径不抛不调")
    void registerPostCommitSilentWithoutGuardNoTransaction() {
        ApprovalGuardSupport s = new ApprovalGuardSupport(LDC);
        assertThatCode(() -> s.registerPostCommit("DRAFT", "PENDING_SECOND", "LAUNCH_DATE_PROPOSE", 7L, 42L))
            .doesNotThrowAnyException();
        verifyNoInteractions(guard);
    }

    @Test
    @DisplayName("② guard 未装配 registerPostCommit 静默：有事务同步也不注册任何 synchronization")
    void registerPostCommitSilentWithoutGuardWithTransactionActive() {
        ApprovalGuardSupport s = new ApprovalGuardSupport(LDC);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatCode(() -> s.registerPostCommit("DRAFT", "PENDING_SECOND", "LAUNCH_DATE_PROPOSE", 7L, 42L))
                .doesNotThrowAnyException();
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(guard);
    }

    /* ---------- ③ 两路径 postCommit 参数与 occurredAt（setClock 固定时间） ---------- */

    @Test
    @DisplayName("③ 有事务同步：注册 afterCommit 延迟触发，提交后 postCommit 参数与 occurredAt 逐字")
    void registerPostCommitDefersToAfterCommitWhenSynchronizationActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            support(LDC).registerPostCommit("PENDING_SECOND", "CONFIRMED", "LAUNCH_DATE_CONFIRM", 7L, 42L);
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            // afterCommit 前不得触发副作用（事务未提交，回滚窗口内调用=污染审计链）
            verify(guard, never()).postCommit(any(), any(), any(), any(), any(), any(), any());
            syncs.get(0).afterCommit();
            verify(guard).postCommit(LDC, "PENDING_SECOND", "CONFIRMED", "LAUNCH_DATE_CONFIRM",
                7L, 42L, FIXED_DATE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("③ 无事务同步：降级立即触发 postCommit，参数与 occurredAt 逐字")
    void registerPostCommitRunsInlineWithoutTransaction() {
        support(LDC).registerPostCommit("PENDING_LEADER", "CONFIRMED", "COEFFICIENT_CONFIRM", 5L, 99L);
        verify(guard).postCommit(LDC, "PENDING_LEADER", "CONFIRMED", "COEFFICIENT_CONFIRM",
            5L, 99L, FIXED_DATE);
    }

    @Test
    @DisplayName("③ occurredAt 取自注入 Clock：setClock 前后同一调用点时间不同源")
    void registerPostCommitOccurredAtComesFromInjectedClock() {
        Instant otherInstant = Instant.parse("2026-01-01T00:00:00Z");
        ApprovalGuardSupport s = support(LDC);
        s.setClock(Clock.fixed(otherInstant, ZoneOffset.UTC));
        s.registerPostCommit("A", "B", "T", 1L, 2L);
        ArgumentCaptor<Date> occurredAt = ArgumentCaptor.forClass(Date.class);
        verify(guard).postCommit(eq(LDC), eq("A"), eq("B"), eq("T"), eq(1L), eq(2L), occurredAt.capture());
        assertThat(occurredAt.getValue()).isEqualTo(Date.from(otherInstant)).isNotEqualTo(FIXED_DATE);
    }

    @Test
    @DisplayName("③ 未 setClock 默认系统时钟：occurredAt 落在调用窗口内")
    void registerPostCommitDefaultClockFallsBackToSystemClock() {
        ApprovalGuardSupport s = new ApprovalGuardSupport(LDC);
        s.setStateMachineGuard(guard);
        Date before = new Date();
        s.registerPostCommit("A", "B", "T", 1L, 2L);
        Date after = new Date();
        ArgumentCaptor<Date> occurredAt = ArgumentCaptor.forClass(Date.class);
        verify(guard).postCommit(eq(LDC), eq("A"), eq("B"), eq("T"), eq(1L), eq(2L), occurredAt.capture());
        // 闭区间毫秒比较（isBetween 端点开闭语义在同毫秒 before==after 时会空区间假红）
        assertThat(occurredAt.getValue().getTime())
            .isGreaterThanOrEqualTo(before.getTime())
            .isLessThanOrEqualTo(after.getTime());
    }

    /* ---------- ④ requireFromState / assertNoInFlight / requireCasHit 正反例 ---------- */

    @Test
    @DisplayName("④-a requireFromState 正例：actualStatus 与 expectedFrom 相等不抛")
    void requireFromStatePassesOnExactMatch() {
        assertThatCode(() -> support(LDC).requireFromState("PENDING_SECOND", "PENDING_SECOND", "不该出现"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("④-a requireFromState 反例：状态不符/null 抛 ServiceException 且文案逐字由调用方传入")
    void requireFromStateThrowsWithCallerMessageOnMismatch() {
        ApprovalGuardSupport s = support(LDC);
        assertThatThrownBy(() -> s.requireFromState("CONFIRMED", "PENDING_SECOND", "必须处于待第二签状态"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("必须处于待第二签状态");
        assertThatThrownBy(() -> s.requireFromState(null, "PENDING_SECOND", "必须处于待第二签状态"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("必须处于待第二签状态");
    }

    @Test
    @DisplayName("④-b assertNoInFlight 正例：pendingCount 为 null 或 0 不抛")
    void assertNoInFlightPassesOnNullOrZero() {
        ApprovalGuardSupport s = support(LDC);
        assertThatCode(() -> s.assertNoInFlight(null, "不该出现")).doesNotThrowAnyException();
        assertThatCode(() -> s.assertNoInFlight(0L, "不该出现")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("④-b assertNoInFlight 反例：pendingCount>0 抛冲突且文案逐字")
    void assertNoInFlightThrowsOnPending() {
        assertThatThrownBy(() -> support(LDC).assertNoInFlight(1L, "该项目已有待第二签确认的上市日期变更申请"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("该项目已有待第二签确认的上市日期变更申请");
    }

    @Test
    @DisplayName("④-c requireCasHit 正例：updatedRows>=1 返回 true")
    void requireCasHitReturnsTrueOnHit() {
        ApprovalGuardSupport s = support(LDC);
        assertThat(s.requireCasHit(1, "不该出现")).isTrue();
        assertThat(s.requireCasHit(3, "不该出现")).isTrue();
    }

    @Test
    @DisplayName("④-c requireCasHit 反例：updatedRows=0 抛冲突且文案逐字")
    void requireCasHitThrowsOnMiss() {
        assertThatThrownBy(() -> support(LDC).requireCasHit(0, "操作已被他人抢先处理，请刷新后重试"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("操作已被他人抢先处理，请刷新后重试");
    }

    /* ---------- ⑤ 不同 entityType 隔离（preCheck 传参正确） ---------- */

    @Test
    @DisplayName("⑤ entityType 隔离：两个实例各自 preCheck 携带自己的 entityType")
    void entityTypeIsolatedBetweenSupportInstances() {
        ApprovalGuardSupport ldc = support(LDC);
        ApprovalGuardSupport coef = support(COEF);
        ldc.preCheck("DRAFT", "PENDING_SECOND", "LAUNCH_DATE_PROPOSE");
        coef.preCheck("PENDING_LEADER", "CONFIRMED", "COEFFICIENT_CONFIRM");
        verify(guard).preCheck(LDC, "DRAFT", "PENDING_SECOND", "LAUNCH_DATE_PROPOSE");
        verify(guard).preCheck(COEF, "PENDING_LEADER", "CONFIRMED", "COEFFICIENT_CONFIRM");
    }

    @Test
    @DisplayName("⑤ entityType 隔离：guard 未装配 fail-closed 消息各含自身 entityType")
    void entityTypeIsolatedInFailClosedMessage() {
        assertThatThrownBy(() -> new ApprovalGuardSupport(COEF).preCheck("A", "B", "T"))
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机守卫未装配 entityType=coefficient_change from=A to=B");
    }
}
