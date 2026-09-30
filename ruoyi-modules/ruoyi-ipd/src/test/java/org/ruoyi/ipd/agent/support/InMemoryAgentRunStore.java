package org.ruoyi.ipd.agent.support;

import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.AgentRunStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试替身：与 {@link AgentRunStore} 合同同构的内存实现（非运行态证据）。
 *
 * <p>忠实模拟数据库约束：幂等唯一键 (tenant_id, person_id, idempotency_key)、
 * 事件唯一键 (run_id, seq)、CAS 条件更新（expected ∩ 合法来源）。另提供写入计数缝，
 * 供“拒绝场景零写入”断言使用。
 */
public final class InMemoryAgentRunStore implements AgentRunStore {

    private final Map<Long, IpdAgentRun> runs = new LinkedHashMap<>();
    private final List<IpdAgentRunEvent> events = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1_000L);
    public final AtomicInteger runInserts = new AtomicInteger();
    public final AtomicInteger eventInserts = new AtomicInteger();
    public final AtomicInteger duplicateEvents = new AtomicInteger();
    /** 在 transition 真正执行前回调（模拟并发穿插），可为 null。 */
    public volatile Runnable beforeTransition;

    /** {@inheritDoc} */
    @Override
    public synchronized boolean insertRun(IpdAgentRun run) {
        boolean duplicated = runs.values().stream().anyMatch(r -> Objects.equals(r.getTenantId(), run.getTenantId())
            && Objects.equals(r.getPersonId(), run.getPersonId())
            && Objects.equals(r.getIdempotencyKey(), run.getIdempotencyKey()));
        if (duplicated) {
            return false;
        }
        if (run.getId() == null) {
            run.setId(ids.incrementAndGet());
        }
        runs.put(run.getId(), copy(run));
        runInserts.incrementAndGet();
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<IpdAgentRun> findRun(Long runId) {
        IpdAgentRun run = runs.get(runId);
        return Optional.ofNullable(run == null ? null : copy(run));
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<IpdAgentRun> findByIdempotencyKey(String tenantId, Long personId, String key) {
        return runs.values().stream().filter(r -> Objects.equals(r.getTenantId(), tenantId)
                && Objects.equals(r.getPersonId(), personId) && Objects.equals(r.getIdempotencyKey(), key))
            .findFirst().map(InMemoryAgentRunStore::copy);
    }

    /** {@inheritDoc} */
    @Override
    public boolean transition(Long runId, Set<AgentRunStatus> expected, AgentRunStatus target,
                              String errorCode, Date at) {
        Runnable hook = beforeTransition;
        if (hook != null) {
            beforeTransition = null;
            hook.run();
        }
        synchronized (this) {
            IpdAgentRun run = runs.get(runId);
            if (run == null || expected == null) {
                return false;
            }
            AgentRunStatus current = AgentRunStatus.valueOf(run.getStatus());
            if (!expected.contains(current) || !current.canTransitTo(target)) {
                return false;
            }
            run.setStatus(target.name());
            if (target == AgentRunStatus.RUNNING) {
                run.setStartedAt(at);
            }
            if (target.isTerminal()) {
                run.setFinishedAt(at);
                run.setErrorCode(errorCode);
            }
            return true;
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean appendEvent(IpdAgentRunEvent event) {
        boolean duplicated = events.stream().anyMatch(e -> Objects.equals(e.getRunId(), event.getRunId())
            && Objects.equals(e.getSeq(), event.getSeq()));
        if (duplicated) {
            duplicateEvents.incrementAndGet();
            return false;
        }
        events.add(event);
        eventInserts.incrementAndGet();
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<IpdAgentRunEvent> listEvents(Long runId, long afterSeq, int limit) {
        return events.stream().filter(e -> Objects.equals(e.getRunId(), runId) && e.getSeq() > afterSeq)
            .sorted(Comparator.comparing(IpdAgentRunEvent::getSeq)).limit(limit).toList();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized long maxSeq(Long runId) {
        return events.stream().filter(e -> Objects.equals(e.getRunId(), runId))
            .mapToLong(IpdAgentRunEvent::getSeq).max().orElse(0L);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<Long> terminalSeq(Long runId) {
        return events.stream().filter(e -> Objects.equals(e.getRunId(), runId)
                && AgentEventType.valueOf(e.getEventType()).isTerminal())
            .map(IpdAgentRunEvent::getSeq).min(Long::compare);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<IpdAgentRun> listOwnRuns(AgentRunStore.OwnRunQuery query) {
        String text = query.text() == null ? "" : query.text().trim();
        return runs.values().stream()
            .filter(run -> Objects.equals(run.getTenantId(), query.tenantId()))
            .filter(run -> Objects.equals(run.getProjectId(), query.projectId()))
            .filter(run -> Objects.equals(run.getPersonId(), query.personId()))
            .filter(run -> query.status() == null || query.status().isBlank()
                || query.status().equals(run.getStatus()))
            .filter(run -> query.actionCode() == null || query.actionCode().isBlank()
                || query.actionCode().equals(run.getActionCode()))
            .filter(run -> query.beforeId() == null || run.getId() < query.beforeId())
            .filter(run -> text.isEmpty() || matchesText(run, text, query.artifactRunIds()))
            .sorted(Comparator.comparing(IpdAgentRun::getId).reversed())
            .limit(Math.min(50, Math.max(1, query.limit())))
            .map(InMemoryAgentRunStore::copy)
            .toList();
    }

    /** 搜索只看动作、状态和产物命中集合，不读摘要。 */
    private static boolean matchesText(IpdAgentRun run, String text, Set<Long> artifactRunIds) {
        if (contains(run.getActionCode(), text) || contains(run.getStatus(), text)) {
            return true;
        }
        return artifactRunIds != null && artifactRunIds.contains(run.getId());
    }

    private static boolean contains(String field, String text) {
        return field != null && field.toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 测试缝：全部事件（按 seq）。
     *
     * @param runId 运行
     * @return 事件
     */
    public synchronized List<IpdAgentRunEvent> events(Long runId) {
        return listEvents(runId, 0L, Integer.MAX_VALUE);
    }

    /**
     * 测试缝：直接改状态（模拟他节点写入）。
     *
     * @param runId 运行
     * @param status 状态
     */
    public synchronized void forceStatus(Long runId, AgentRunStatus status) {
        runs.get(runId).setStatus(status.name());
    }

    /** @return 运行总数 */
    public synchronized int runCount() {
        return runs.size();
    }

    private static IpdAgentRun copy(IpdAgentRun source) {
        IpdAgentRun run = source.toBuilder().build();
        run.setCreateTime(source.getCreateTime());
        run.setCreateBy(source.getCreateBy());
        return run;
    }
}
