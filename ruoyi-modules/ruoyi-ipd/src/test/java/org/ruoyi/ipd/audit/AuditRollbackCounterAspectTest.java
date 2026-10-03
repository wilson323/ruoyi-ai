package org.ruoyi.ipd.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.audit.AuditRollbackCounter.AuditRollbackSnapshot;
import org.ruoyi.ipd.domain.AuditLog;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计回滚率计数器（{@link AuditRollbackCounter} + {@link AuditRollbackCounterAspect}）单测。
 *
 * <p>不启动 Spring 容器：直接驱动 {@code TransactionSynchronizationManager} 的同步注册表
 * 模拟一次宿主业务事务，再把切面 advice 当普通方法调用，断言四条口径——
 * 开关关闭不计数 / 开启时回滚被计数 / 不回滚不计数 / 失败与重复 append 不误计。
 *
 * <p>为什么值得这样测：本测量件的正确性完全押在「探针挂在宿主事务而非 append 自己的
 * {@code REQUIRES_NEW} 上」这一条上。用真实事务管理器跑一遍是唯一能证明「回滚信号拿得到」
 * 的方式；用 mock 的 {@code TransactionSynchronizationManager} 则永远绿。
 */
@Tag("dev")
class AuditRollbackCounterAspectTest {

    private final AuditRollbackCounter onCounter = new AuditRollbackCounter(true);
    private final AuditRollbackCounterAspect onAspect = new AuditRollbackCounterAspect(onCounter);

    private final AuditRollbackCounter offCounter = new AuditRollbackCounter(false);
    private final AuditRollbackCounterAspect offAspect = new AuditRollbackCounterAspect(offCounter);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- 开关关闭 ----------

    @Test
    @DisplayName("开关关闭：回滚不入账、维度表为空、探针不注册")
    void disabled_rollsBackIsNotCountedAndNoProbeRegistered() throws Throwable {
        beginHostTransaction();
        int before = TransactionSynchronizationManager.getSynchronizations().size();

        offAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action("WITHDRAW").build()));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(before);
        completeHostTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        AuditRollbackSnapshot s = offCounter.snapshot();
        assertThat(s.enabled()).isFalse();
        assertThat(s.observedHostTxCount()).isZero();
        assertThat(s.rolledBackCount()).isZero();
        assertThat(s.rate()).isZero();
        assertThat(s.byAction()).isEmpty();
        assertThat(s.byCaller()).isEmpty();
    }

    @Test
    @DisplayName("开关关闭：切面原样放行业务返回值，不吞不抛")
    void disabled_passesThroughBusinessResult() throws Throwable {
        Object sentinel = new Object();
        assertThat(offAspect.aroundAppend(joinPointReturning(sentinel))).isSameAs(sentinel);
    }

    // ---------- 开启：回滚被计数 ----------

    @Test
    @DisplayName("开启：宿主事务回滚且审计已落库 ⇒ 计一次幽灵审计")
    void enabled_rolledBackHostTransactionIsCountedAsPhantomAudit() throws Throwable {
        beginHostTransaction();
        onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action("WITHDRAW").build()));
        completeHostTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        AuditRollbackSnapshot s = onCounter.snapshot();
        assertThat(s.enabled()).isTrue();
        assertThat(s.observedHostTxCount()).isEqualTo(1);
        assertThat(s.rolledBackCount()).isEqualTo(1);
        assertThat(s.committedCount()).isZero();
        assertThat(s.rate()).isEqualTo(1.0d);
        assertThat(s.byAction()).containsEntry("WITHDRAW", 1L);
    }

    @Test
    @DisplayName("开启：按 action 与调用方两个维度分别可查")
    void enabled_phantomIsBrokenDownByActionAndByCaller() throws Throwable {
        appendThenRollback("WITHDRAW");
        appendThenRollback("APPROVE");
        appendThenRollback("APPROVE");

        AuditRollbackSnapshot s = onCounter.snapshot();
        assertThat(s.observedHostTxCount()).isEqualTo(3);
        assertThat(s.rolledBackCount()).isEqualTo(3);
        assertThat(s.byAction()).containsEntry("WITHDRAW", 1L).containsEntry("APPROVE", 2L);
        // 调用方维度来自栈回溯；本测试类自身位于 org.ruoyi.ipd.audit 包（被切面显式排除），
        // 故单测里必然退化为 "?"。真机上这里会是 Controller/Service 的 Class#method。
        assertThat(s.byCaller()).containsEntry("?", 3L);
    }

    // ---------- 开启：不回滚不计数为幽灵 ----------

    @Test
    @DisplayName("开启：宿主事务提交 ⇒ 不算幽灵审计")
    void enabled_committedHostTransactionIsNotCountedAsPhantom() throws Throwable {
        beginHostTransaction();
        onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action("CREATE").build()));
        completeHostTransaction(TransactionSynchronization.STATUS_COMMITTED);

        AuditRollbackSnapshot s = onCounter.snapshot();
        assertThat(s.observedHostTxCount()).isEqualTo(1);
        assertThat(s.rolledBackCount()).isZero();
        assertThat(s.committedCount()).isEqualTo(1);
        assertThat(s.rate()).isZero();
        assertThat(s.byAction()).isEmpty();
    }

    @Test
    @DisplayName("开启：混合结局下 rate = 回滚数 / 观察数")
    void enabled_mixedOutcomeReportsRateAsFractionOfObserved() throws Throwable {
        appendThenCommit("A");
        appendThenRollback("B");
        appendThenRollback("C");

        AuditRollbackSnapshot s = onCounter.snapshot();
        assertThat(s.observedHostTxCount()).isEqualTo(3);
        assertThat(s.rolledBackCount()).isEqualTo(2);
        assertThat(s.committedCount()).isEqualTo(1);
        assertThat(s.rate()).isCloseTo(2.0d / 3.0d, offset(1e-9));
    }

    // ---------- 边界一：append 自己失败 ⇒ 审计行没落库 ⇒ 回滚不算幽灵审计 ----------

    @Test
    @DisplayName("开启：append 抛异常后宿主回滚，不计幽灵审计")
    void enabled_failedAppendFollowedByRollbackIsNotCounted() throws Throwable {
        beginHostTransaction();
        assertThatThrownBy(() -> onAspect.aroundAppend(joinPointThrowing(new IllegalStateException("链锚被占"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("链锚被占");
        completeHostTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        AuditRollbackSnapshot s = onCounter.snapshot();
        assertThat(s.observedHostTxCount()).isZero();
        assertThat(s.rolledBackCount()).isZero();
    }

    // ---------- 边界二：同一宿主事务多次 append ⇒ 只挂一个探针 ⇒ 回滚计一次 ----------

    @Test
    @DisplayName("开启：同一事务内多次 append 不重复计数（一次回滚 = 一次幽灵审计）")
    void enabled_multipleAppendsInOneHostTransactionCountRollbackOnce() throws Throwable {
        beginHostTransaction();
        onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action("APPROVE").build()));
        onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action("SUBMIT").build()));
        onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action("SUBMIT").build()));

        assertThat(TransactionSynchronizationManager.getSynchronizations())
            .as("同一宿主事务只应挂一个探针")
            .hasSize(1);

        completeHostTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        AuditRollbackSnapshot s = onCounter.snapshot();
        assertThat(s.observedHostTxCount()).isEqualTo(1);
        assertThat(s.rolledBackCount()).isEqualTo(1);
    }

    // ---------- 边界三：无宿主事务 ⇒ 不注册探针（审计提交是合法的） ----------

    @Test
    @DisplayName("开启：无宿主事务时 audit 提交不算幽灵，不注册探针")
    void enabled_noHostTransactionRegistersNoProbe() throws Throwable {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action("LOGIN").build())))
            .isNull();

        assertThat(onCounter.snapshot().observedHostTxCount()).isZero();
    }

    // ---------- 边界四：两种便捷重载的 action 提取 ----------

    @Test
    @DisplayName("开启：从便捷重载实参里也能取到 action 码")
    void enabled_extractsActionFromConvenienceOverloadArgs() throws Throwable {
        beginHostTransaction();
        // append(IpdActor, String action, String entityType, Long entityId, String reason)
        onAspect.aroundAppend(joinPointSucceedingWithArgs(
            new Object[]{null, "SYNC_PULL", "hr_sync", 1L, null}));
        completeHostTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(onCounter.snapshot().byAction()).containsEntry("SYNC_PULL", 1L);
    }

    // ---------- 边界二之一：前一次成功、后一次失败 ⇒ 仍算幽灵审计（前几条行确实留在库里） ----------

    @Test
    @DisplayName("开启：同事务内先成功后失败，仍计一次幽灵审计（已落库的那几行不会消失）")
    void enabled_failedAppendAfterSucceededOneStillCountsAsPhantom() throws Throwable {
        beginHostTransaction();
        onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action("APPROVE").build()));
        assertThatThrownBy(() -> onAspect.aroundAppend(joinPointThrowing(new IllegalStateException("链锚被占"))))
            .isInstanceOf(IllegalStateException.class);
        completeHostTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        AuditRollbackSnapshot s = onCounter.snapshot();
        assertThat(s.observedHostTxCount()).isEqualTo(1);
        assertThat(s.rolledBackCount()).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private void appendThenRollback(String action) throws Throwable {
        beginHostTransaction();
        onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action(action).build()));
        completeHostTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    private void appendThenCommit(String action) throws Throwable {
        beginHostTransaction();
        onAspect.aroundAppend(joinPointSucceeding(AuditLog.builder().action(action).build()));
        completeHostTransaction(TransactionSynchronization.STATUS_COMMITTED);
    }

    private static void beginHostTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    /** 结束宿主事务并把结果回调给所有已注册探针（模拟 Spring 事务完成语义）。 */
    private static void completeHostTransaction(int status) {
        List<TransactionSynchronization> syncs =
            List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        syncs.forEach(s -> s.afterCompletion(status));
    }

    private static ProceedingJoinPoint joinPointSucceeding(AuditLog draft) throws Throwable {
        return joinPointSucceedingWithArgs(new Object[]{draft});
    }

    private static ProceedingJoinPoint joinPointSucceedingWithArgs(Object[] args) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn(null);
        return pjp;
    }

    private static ProceedingJoinPoint joinPointReturning(Object value) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{AuditLog.builder().action("X").build()});
        when(pjp.proceed()).thenReturn(value);
        return pjp;
    }

    private static ProceedingJoinPoint joinPointThrowing(RuntimeException e) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[]{AuditLog.builder().action("X").build()});
        when(pjp.proceed()).thenThrow(e);
        return pjp;
    }
}
