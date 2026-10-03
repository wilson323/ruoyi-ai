package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.model.*;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 取得无人持有的成熟锁后fence旧epoch，再在原运行/事件事务内收口；不重放输入。 */
public final class ProjectAgentRunRecovery {
    private final AgentRunStore store;
    private final ProjectAgentRunOwnership ownership;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private io.agentscope.core.state.AgentStateStore stateStore;
    public ProjectAgentRunRecovery(AgentRunStore store, ProjectAgentRunOwnership ownership,
                                   TransactionTemplate transaction, ObjectMapper mapper) {
        this.store = store; this.ownership = ownership; this.transaction = transaction; this.mapper = mapper;
    }
    public void setStateStore(io.agentscope.core.state.AgentStateStore stateStore) { this.stateStore = stateStore; }
    private java.util.function.Predicate<IpdAgentRun> resumeRecovery;
    public void setResumeRecovery(java.util.function.Predicate<IpdAgentRun> recovery) { resumeRecovery=Objects.requireNonNull(recovery); }
    public boolean recover(IpdAgentRun candidate) {
        if (!recoverable(candidate) || !store.hasExecutionOwner(candidate.getId())) return false;
        // 扫描页可能已过时；已收口记录不再申请租约，仍在锁内再次读取防竞态。
        if (!recoverable(store.findRun(candidate.getId()).orElse(null))) return false;
        if("RUNNING".equals(candidate.getStatus()) && resumeRecovery!=null) {
            try { if(resumeRecovery.test(candidate)) return true; }
            catch(ProjectAgentRunOwnership.OwnershipLost | ProjectAgentRunExecutor.ResumeDeferred busy) { return false; }
            catch(org.ruoyi.ipd.common.IpdBusinessException denied) {
                if(denied.getErrorCode()==org.ruoyi.ipd.common.ApiV1ErrorCode.RATE_LIMITED) return false;
                // 不可安全恢复按原失联终态留证，不重放工具。
            } catch(IllegalStateException|IllegalArgumentException|SecurityException unsafe) { }
        }
        var acquired = ownership.acquire(candidate.getId());
        if (acquired.isEmpty()) return false;
        try (var lease = acquired.get()) {
            return Boolean.TRUE.equals(transaction.execute(tx -> {
                var current = store.findRun(candidate.getId()).orElse(null);
                if (!recoverable(current) || !store.hasExecutionOwner(candidate.getId()) || !lease.held()) return false;
                var epoch = store.claimEpoch(current.getId(), current.getVersion(), EnumSet.of(AgentRunStatus.PENDING, AgentRunStatus.RUNNING, AgentRunStatus.CANCEL_REQUESTED));
                if (epoch.isEmpty()) return false;
                if (!store.lockEpoch(current.getId(), epoch.get()) || !lease.held())
                    throw new ProjectAgentRunOwnership.OwnershipLost();
                AgentRunStatus before = AgentRunStatus.valueOf(current.getStatus());
                AgentRunStatus after = before == AgentRunStatus.CANCEL_REQUESTED ? AgentRunStatus.CANCELLED : AgentRunStatus.FAILED;
                Date now = new Date();
                if (!store.transition(current.getId(), EnumSet.of(before), after, after == AgentRunStatus.FAILED ? "INTERRUPTED" : null, now))
                    throw new IllegalStateException("recovery state CAS rejected");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("recoveryEpoch", epoch.get());
                payload.put("fenceToken", lease.token());
                payload.put("retryAsNewRun", true);
                if (after == AgentRunStatus.FAILED) {
                    payload.put("errorCode", "INTERRUPTED");
                    payload.put("message", "运行已中断，请重新发起；已有产物和记录已保留");
                } else payload.put("status", after.name());
                try {
                    if (!store.appendEvent(ProjectAgentRunEvents.of(current.getId(), current.getTenantId(), current.getPersonId(),
                        store.maxSeq(current.getId()) + 1, after == AgentRunStatus.FAILED ? AgentEventType.ERROR : AgentEventType.RUN_FINISHED,
                        mapper.writeValueAsString(payload), now))) throw new IllegalStateException("recovery terminal event rejected");
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
                // 失联收口不证明 SDK 消费或外部效果已完成，不能在可回滚事务内删除恢复证据。
                // 原根/子检查点留给已授权的恢复对账；终态事件不冒充成功清理回执。
                return true;
            }));
        }
    }
    private static boolean recoverable(IpdAgentRun run) {
        return run != null && ("PENDING".equals(run.getStatus()) || "RUNNING".equals(run.getStatus()) || "CANCEL_REQUESTED".equals(run.getStatus()));
    }
}
