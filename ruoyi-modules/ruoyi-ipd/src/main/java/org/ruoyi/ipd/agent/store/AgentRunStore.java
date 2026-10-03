package org.ruoyi.ipd.agent.store;

import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentRunStatus;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 运行与事件持久化端口。语义约束（实现必须满足，测试替身同构）：
 * <ul>
 *   <li>{@link #insertRun}：幂等键 (tenant_id, person_id, idempotency_key) 冲突返回 false，不抛；</li>
 *   <li>{@link #transition}：CAS——仅当当前状态为 target 的合法来源时迁移，返回是否迁移成功；</li>
 *   <li>{@link #appendEvent}：(run_id, seq) 冲突返回 false（去重），不抛。</li>
 * </ul>
 */
public interface AgentRunStore {

    /**
     * 插入新运行。
     *
     * @param run 待插入运行（id 可由实现分配）
     * @return true 插入成功；false 幂等键已存在
     */
    boolean insertRun(IpdAgentRun run);

    /**
     * 按 ID 查运行。
     *
     * @param runId 运行 ID
     * @return 运行（不存在为空）
     */
    Optional<IpdAgentRun> findRun(Long runId);

    /**
     * 按幂等键查运行。
     *
     * @param tenantId 可信租户
     * @param personId 发起人
     * @param idempotencyKey 幂等键
     * @return 已存在的运行
     */
    Optional<IpdAgentRun> findByIdempotencyKey(String tenantId, Long personId, String idempotencyKey);

    /**
     * CAS 状态迁移：仅当当前状态 ∈ expected 时迁移。expected 中不能合法迁移到 target 的状态
     * （按 {@link AgentRunStatus#canTransitTo}）被剔除；剔除后为空直接返回 false。
     *
     * @param runId 运行 ID
     * @param expected 期望的当前状态（调用方读到的现状，精确匹配）
     * @param target 目标状态
     * @param errorCode 失败码（非 FAILED 传 null）
     * @param at 迁移时刻（RUNNING 记 started_at，终态记 finished_at）
     * @return true 本次调用赢得迁移
     */
    boolean transition(Long runId, Set<AgentRunStatus> expected, AgentRunStatus target, String errorCode, Date at);

    /**
     * 追加事件。
     *
     * @param event 事件（runId/seq/eventType/payload 必填）
     * @return true 新写入；false (run_id, seq) 已存在
     */
    boolean appendEvent(IpdAgentRunEvent event);

    /**
     * 列出 seq &gt; afterSeq 的事件（升序，最多 limit 条）。
     *
     * @param runId 运行 ID
     * @param afterSeq 游标（不含）
     * @param limit 上限
     * @return 事件列表
     */
    List<IpdAgentRunEvent> listEvents(Long runId, long afterSeq, int limit);

    /**
     * 当前最大 seq（无事件为 0）。
     *
     * @param runId 运行 ID
     * @return 最大序号
     */
    long maxSeq(Long runId);

    /**
     * 终态事件（RUN_FINISHED / ERROR）的 seq。
     *
     * @param runId 运行 ID
     * @return 终态事件序号（未终结为空）
     */
    Optional<Long> terminalSeq(Long runId);

    /**
     * 本人在某项目下的运行。按 id 倒序，cursor 为上一页最后的 runId（不含）。
     * text 非空时只匹配 actionCode、status，或 {@code artifactRunIds}；不查 inputDigest，也没有 input 列。
     *
     * @param query 过滤条件
     * @return 本页运行
     */
    List<IpdAgentRun> listOwnRuns(OwnRunQuery query);

    /**
     * 列出创建时间早于给定时刻、且仍停在非终态的运行。
     * 仅供旧无owner记录只读诊断；日期不能证明执行者已停止。
     *
     * @param createdBefore 本次进程启动时刻（不含）
     * @param limit 本页条数，实现限制在 1～50
     * @return 待收口运行
     */
    List<IpdAgentRun> listInterruptedCandidates(Date createdBefore, int limit);

    /** 在原运行行上领取新执行epoch；不得更改业务状态。 */
    default Optional<Integer> claimEpoch(Long runId, Integer expectedVersion, Set<AgentRunStatus> statuses) {
        throw new UnsupportedOperationException("execution epoch is not supported");
    }

    /** 必须在事务内锁原run行，再校验epoch，锁保持到所有业务写入提交。 */
    default boolean lockEpoch(Long runId, int epoch) {
        throw new UnsupportedOperationException("execution epoch is not supported");
    }

    /** 在校验驻留态事务中读取并锁定当前运行行，不返回普通查询缓存中的快照。 */
    default Optional<IpdAgentRun> lockRunForVerification(Long runId) {
        throw new UnsupportedOperationException("verification row locking is not supported");
    }

    /** 按runId分页扫描运行中的候选，日期不是死亡依据。 */
    default List<IpdAgentRun> listRecoveryCandidates(Long afterId, int limit) {
        throw new UnsupportedOperationException("recovery cursor is not supported");
    }

    /** 本方案写入过的owner标记；无标记的旧运行不能按租约缺失自动关闭。 */
    default boolean hasExecutionOwner(Long runId) { return false; }

    /**
     * 列表查询。text 为已去空白的搜索词，空串表示不按词过滤。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param personId 发起人
     * @param status 精确状态，可空
     * @param actionCode 精确动作，可空
     * @param text 搜索词，可空
     * @param artifactRunIds 产物标题或正文已命中的运行；仅 text 非空时使用
     * @param beforeId 上一页最后一个 runId，可空
     * @param limit 本页条数，实现再限制在 1～50
     */
    record OwnRunQuery(String tenantId, Long projectId, Long personId, String status, String actionCode,
                       String text, Set<Long> artifactRunIds, Long beforeId, int limit) {
    }
}
