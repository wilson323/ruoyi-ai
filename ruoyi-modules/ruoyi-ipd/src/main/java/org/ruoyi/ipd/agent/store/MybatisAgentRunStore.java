package org.ruoyi.ipd.agent.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.mapper.IpdAgentRunEventMapper;
import org.ruoyi.ipd.agent.mapper.IpdAgentRunMapper;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link AgentRunStore} 的 MyBatis-Plus 实现。
 *
 * <p>状态迁移一律条件 UPDATE（WHERE status IN 合法来源），以影响行数判定 CAS 胜负，
 * 不走 updateById（避免覆盖并发写入的状态）；唯一键冲突翻译为 false 返回值。
 */
@Repository
@RequiredArgsConstructor
public class MybatisAgentRunStore implements AgentRunStore {

    /** 与项目运行列表现有中文状态标签一致；不改变数据库状态枚举。 */
    private static final Map<String, String> STATUS_LABELS = Map.of(
        "PENDING", "排队中", "RUNNING", "运行中", "WAITING_APPROVAL", "等待审批",
        "CANCEL_REQUESTED", "取消中", "SUCCEEDED", "已完成", "FAILED", "失败", "CANCELLED", "已取消");

    private final IpdAgentRunMapper runMapper;
    private final IpdAgentRunEventMapper eventMapper;

    /** {@inheritDoc} */
    @Override
    public boolean insertRun(IpdAgentRun run) {
        try {
            return runMapper.insert(run) == 1;
        } catch (DuplicateKeyException duplicated) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IpdAgentRun> findRun(Long runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(runMapper.selectById(runId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IpdAgentRun> findByIdempotencyKey(String tenantId, Long personId, String idempotencyKey) {
        return Optional.ofNullable(runMapper.selectOne(new LambdaQueryWrapper<IpdAgentRun>()
            .eq(IpdAgentRun::getTenantId, tenantId)
            .eq(IpdAgentRun::getPersonId, personId)
            .eq(IpdAgentRun::getIdempotencyKey, idempotencyKey)));
    }

    /** {@inheritDoc} */
    @Override
    public boolean transition(Long runId, Set<AgentRunStatus> expected, AgentRunStatus target,
                              String errorCode, Date at) {
        Set<AgentRunStatus> sources = AgentRunStatus.sourcesOf(target);
        if (expected != null) {
            sources.retainAll(expected);
        }
        if (runId == null || expected == null || sources.isEmpty()) {
            return false;
        }
        LambdaUpdateWrapper<IpdAgentRun> update = new LambdaUpdateWrapper<IpdAgentRun>()
            .eq(IpdAgentRun::getId, runId)
            .in(IpdAgentRun::getStatus, sources.stream().map(Enum::name).toList())
            .set(IpdAgentRun::getStatus, target.name());
        if (target == AgentRunStatus.RUNNING) {
            update.set(IpdAgentRun::getStartedAt, at);
        }
        if (target.isTerminal()) {
            update.set(IpdAgentRun::getFinishedAt, at);
            update.set(IpdAgentRun::getErrorCode, errorCode);
        }
        return runMapper.update(null, update) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean appendEvent(IpdAgentRunEvent event) {
        try {
            return eventMapper.insert(event) == 1;
        } catch (DuplicateKeyException duplicated) {
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<IpdAgentRunEvent> listEvents(Long runId, long afterSeq, int limit) {
        return eventMapper.selectList(new LambdaQueryWrapper<IpdAgentRunEvent>()
            .eq(IpdAgentRunEvent::getRunId, runId)
            .gt(IpdAgentRunEvent::getSeq, afterSeq)
            .orderByAsc(IpdAgentRunEvent::getSeq)
            .last("LIMIT " + Math.max(1, limit)));
    }

    /** {@inheritDoc} */
    @Override
    public long maxSeq(Long runId) {
        IpdAgentRunEvent last = eventMapper.selectOne(new LambdaQueryWrapper<IpdAgentRunEvent>()
            .eq(IpdAgentRunEvent::getRunId, runId)
            .orderByDesc(IpdAgentRunEvent::getSeq)
            .last("LIMIT 1"));
        return last == null || last.getSeq() == null ? 0L : last.getSeq();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Long> terminalSeq(Long runId) {
        IpdAgentRunEvent terminal = eventMapper.selectOne(new LambdaQueryWrapper<IpdAgentRunEvent>()
            .eq(IpdAgentRunEvent::getRunId, runId)
            .in(IpdAgentRunEvent::getEventType,
                List.of(AgentEventType.RUN_FINISHED.name(), AgentEventType.ERROR.name()))
            .orderByAsc(IpdAgentRunEvent::getSeq)
            .last("LIMIT 1"));
        return terminal == null ? Optional.empty() : Optional.ofNullable(terminal.getSeq());
    }

    @Override
    public Optional<Integer> claimEpoch(Long runId, Integer expectedVersion, Set<AgentRunStatus> statuses) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("epoch claim requires a transaction");
        if (expectedVersion != null && expectedVersion == Integer.MAX_VALUE)
            throw new IllegalStateException("execution epoch exhausted");
        int next = expectedVersion == null ? 1 : expectedVersion + 1;
        var update = new LambdaUpdateWrapper<IpdAgentRun>().eq(IpdAgentRun::getId, runId)
            .in(IpdAgentRun::getStatus, statuses.stream().map(Enum::name).toList())
            .set(IpdAgentRun::getVersion, next);
        if (expectedVersion == null) update.isNull(IpdAgentRun::getVersion);
        else update.eq(IpdAgentRun::getVersion, expectedVersion);
        // null实体不会触发MP乐观锁插件；epoch只在此显式CAS递增，普通状态迁移不夺权。
        return runMapper.update(null, update) == 1 ? Optional.of(next) : Optional.empty();
    }

    @Override
    public boolean lockEpoch(Long runId, int epoch) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("epoch guard requires a transaction");
        IpdAgentRun run = runMapper.selectOne(new LambdaQueryWrapper<IpdAgentRun>()
            .eq(IpdAgentRun::getId, runId).last("FOR UPDATE"));
        return run != null && java.util.Objects.equals(run.getVersion(), epoch);
    }

    @Override
    public List<IpdAgentRun> listRecoveryCandidates(Long afterId, int limit) {
        var filter = new LambdaQueryWrapper<IpdAgentRun>()
            .in(IpdAgentRun::getStatus, List.of("PENDING", "RUNNING", "CANCEL_REQUESTED"));
        if (afterId != null) filter.gt(IpdAgentRun::getId, afterId);
        return runMapper.selectList(filter.orderByAsc(IpdAgentRun::getId).last("LIMIT " + Math.min(50, Math.max(1, limit))));
    }

    @Override
    public boolean hasExecutionOwner(Long runId) {
        return eventMapper.selectCount(new LambdaQueryWrapper<IpdAgentRunEvent>()
            .eq(IpdAgentRunEvent::getRunId, runId).eq(IpdAgentRunEvent::getEventType, AgentEventType.STEP.name())
            .apply("JSON_UNQUOTE(JSON_EXTRACT(payload, '$.kind')) = {0}", "EXECUTION_OWNER")) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public List<IpdAgentRun> listOwnRuns(OwnRunQuery query) {
        LambdaQueryWrapper<IpdAgentRun> filter = new LambdaQueryWrapper<IpdAgentRun>()
            .eq(IpdAgentRun::getTenantId, query.tenantId())
            .eq(IpdAgentRun::getProjectId, query.projectId())
            .eq(IpdAgentRun::getPersonId, query.personId());
        if (query.status() != null && !query.status().isBlank()) {
            filter.eq(IpdAgentRun::getStatus, query.status());
        }
        if (query.actionCode() != null && !query.actionCode().isBlank()) {
            filter.eq(IpdAgentRun::getActionCode, query.actionCode());
        }
        if (query.beforeId() != null) {
            filter.lt(IpdAgentRun::getId, query.beforeId());
        }
        String text = query.text() == null ? "" : query.text().trim();
        if (!text.isEmpty()) {
            String pattern = LikePatterns.containsPattern(text);
            Set<Long> hits = query.artifactRunIds() == null ? Set.of() : query.artifactRunIds();
            List<String> labelStatuses = STATUS_LABELS.entrySet().stream()
                .filter(entry -> entry.getValue().contains(text)).map(Map.Entry::getKey).toList();
            filter.and(nested -> {
                nested.apply("action_code LIKE {0} ESCAPE '\\\\'", pattern)
                    .or()
                    .apply("status LIKE {0} ESCAPE '\\\\'", pattern);
                if (!labelStatuses.isEmpty()) {
                    nested.or().in(IpdAgentRun::getStatus, labelStatuses);
                }
                if (!hits.isEmpty()) {
                    nested.or().in(IpdAgentRun::getId, hits);
                }
            });
        }
        int limit = Math.min(50, Math.max(1, query.limit()));
        filter.orderByDesc(IpdAgentRun::getId).last("LIMIT " + limit);
        return runMapper.selectList(filter);
    }

    /** {@inheritDoc} */
    @Override
    public List<IpdAgentRun> listInterruptedCandidates(Date createdBefore, int limit) {
        if (createdBefore == null) {
            return List.of();
        }
        int page = Math.min(50, Math.max(1, limit));
        List<String> open = List.of(
            AgentRunStatus.PENDING.name(),
            AgentRunStatus.RUNNING.name(),
            AgentRunStatus.WAITING_APPROVAL.name(),
            AgentRunStatus.CANCEL_REQUESTED.name());
        return runMapper.selectList(new LambdaQueryWrapper<IpdAgentRun>()
            .in(IpdAgentRun::getStatus, open)
            .and(nested -> nested.lt(IpdAgentRun::getCreateTime, createdBefore)
                .or()
                .isNull(IpdAgentRun::getCreateTime))
            .orderByAsc(IpdAgentRun::getId)
            .last("LIMIT " + page));
    }
}
