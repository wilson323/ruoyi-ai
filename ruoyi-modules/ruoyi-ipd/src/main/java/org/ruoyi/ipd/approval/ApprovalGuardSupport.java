package org.ruoyi.ipd.approval;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.service.StateMachineGuard;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Date;

/**
 * R33 一期：审批链共享守卫骨架（纯抽取，行为零变更）。
 *
 * <p>收编 7 链 Service 中 preCheckGuard/registerPostCommit 接线六连拷贝
 * （LaunchDateChangeService / CoefficientChangeService / GateReviewService / HandoverService /
 * DeletionRequestServiceImpl / RequirementChangeService）+ 终态守卫前置检查 + 在途单唯一预检 +
 * 决策 CAS 谓词翻转判定 + 可注入 Clock（测试摇摆消除）。
 *
 * <p>冻结 API（签名与语义与既有拷贝逐字等价，禁止改动）：
 * <ul>
 *   <li>{@link #preCheck}：guard 未装配 fail-closed 抛 {@link ServiceException}；
 *       装配后委托 {@link StateMachineGuard#preCheck}</li>
 *   <li>{@link #registerPostCommit}：guard 未装配静默 return；有事务同步注册 afterCommit
 *       延迟触发 {@link StateMachineGuard#postCommit}，无事务同步降级为立即触发</li>
 *   <li>{@link #requireFromState}：起点状态不符即抛（文案由调用方传入，
 *       保证既有异常消息逐字不变）</li>
 *   <li>{@link #assertNoInFlight}：存在在途单（pendingCount &gt; 0）即抛冲突</li>
 *   <li>{@link #requireCasHit}：CAS 更新命中（updatedRows &gt;= 1）返回 true，否则抛冲突</li>
 * </ul>
 */
public class ApprovalGuardSupport {

    /** 实体类型（与 DefaultStateMachineGuard.registerRule 约定一致） */
    private final String entityType;

    /** 跨状态机守卫（nullable 兼容旧测试；与旧 @Autowired(required=false) setter 语义一致） */
    private StateMachineGuard stateMachineGuard;

    /** 可注入 Clock（测试摇摆消除）；默认系统时钟 */
    private Clock clock = Clock.systemDefaultZone();

    public ApprovalGuardSupport(String entityType) {
        this.entityType = entityType;
    }

    /** nullable 容忍（与旧 @Autowired(required=false) setter 语义一致） */
    public void setStateMachineGuard(StateMachineGuard stateMachineGuard) {
        this.stateMachineGuard = stateMachineGuard;
    }

    /** 可注入 Clock（测试摇摆消除）；默认 {@link Clock#systemDefaultZone()} */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 守卫 preCheck 包装（fail-closed）：guard 未装配直接抛错，不放行。
     */
    public void preCheck(String fromState, String toState, String trigger) {
        if (stateMachineGuard == null) {
            throw new ServiceException(String.format("状态机守卫未装配 entityType=%s from=%s to=%s",
                entityType, fromState, toState));
        }
        stateMachineGuard.preCheck(entityType, fromState, toState, trigger);
    }

    /**
     * 注册 postCommit 副作用：guard 未装配静默 return；有事务同步注册 afterCommit
     * （事务提交成功后触发守卫 postCommit），无事务同步降级为立即触发。
     */
    public void registerPostCommit(String fromState, String toState, String trigger,
                                   Long operatorId, Long entityId) {
        if (stateMachineGuard == null) {
            return;
        }
        Date occurredAt = Date.from(clock.instant());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 事务提交成功后调用守卫 postCommit，登记状态机副作用。 */
                @Override
                public void afterCommit() {
                    stateMachineGuard.postCommit(entityType, fromState, toState, trigger,
                        operatorId, entityId, occurredAt);
                }
            });
        } else {
            stateMachineGuard.postCommit(entityType, fromState, toState, trigger,
                operatorId, entityId, occurredAt);
        }
    }

    /**
     * 终态/起点守卫：actualStatus 与 expectedFrom 不符即抛。
     * 文案由调用方传入，保证既有异常消息逐字不变。
     */
    public void requireFromState(String actualStatus, String expectedFrom, String violationMessage) {
        if (!expectedFrom.equals(actualStatus)) {
            throw new ServiceException(violationMessage);
        }
    }

    /**
     * 在途单唯一预检：存在在途（pendingCount != null 且 pendingCount &gt; 0）即抛冲突。
     */
    public void assertNoInFlight(Long pendingCount, String conflictMessage) {
        if (pendingCount != null && pendingCount > 0) {
            throw new ServiceException(conflictMessage);
        }
    }

    /**
     * 决策 CAS 谓词翻转判定：updatedRows &gt;= 1 命中返回 true；0 行 = 并发冲突抛出。
     */
    public boolean requireCasHit(int updatedRows, String conflictMessage) {
        if (updatedRows >= 1) {
            return true;
        }
        throw new ServiceException(conflictMessage);
    }
}
