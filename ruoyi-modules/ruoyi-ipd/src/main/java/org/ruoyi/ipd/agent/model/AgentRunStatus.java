package org.ruoyi.ipd.agent.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 项目智能体运行状态机（W1 合同）。
 *
 * <pre>
 * PENDING ──▶ RUNNING ──▶ SUCCEEDED
 *    │           │  ▲ ├──▶ VERIFYING ──▶ SUCCEEDED
 *    │           │  │ │       └──▶ CANCELLED
 *    │           │  │ └──▶ FAILED
 *    │           ▼  │
 *    │     WAITING_APPROVAL
 *    │           │
 *    ▼           ▼
 * CANCELLED ◀── CANCEL_REQUESTED ──▶ FAILED
 * </pre>
 *
 * <p>终态三选一：SUCCEEDED / FAILED / CANCELLED；一旦进入终态不可再迁移。
 * 取消先落 {@link #CANCEL_REQUESTED}，此后迟到的模型帧不再写入业务事件，
 * 最终只由 CAS 胜者写入唯一终态事件（RUN_FINISHED 或 ERROR）。
 *
 * <p>VERIFYING 是产物校验驻留态（Quality 域 V-2）：产物已落库但机器校验有
 * BLOCK 级缺口，等待补证据后复检；不属 ACTIVE（无执行器写入权，重启恢复不收口），
 * 取消走直接 VERIFYING→CANCELLED（不经 CANCEL_REQUESTED）。
 */
public enum AgentRunStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    CANCEL_REQUESTED,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** 终态集合。 */
    public static final Set<AgentRunStatus> TERMINAL = EnumSet.of(SUCCEEDED, FAILED, CANCELLED);

    /** 可被请求取消的非终态集合。 */
    public static final Set<AgentRunStatus> CANCELLABLE = EnumSet.of(RUNNING, WAITING_APPROVAL);

    /** 运行中（执行器持有写入权）的集合。 */
    public static final Set<AgentRunStatus> ACTIVE = EnumSet.of(RUNNING, WAITING_APPROVAL, CANCEL_REQUESTED);

    /**
     * 是否终态。
     *
     * @return true 表示不可再迁移
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 迁移合法性判定（纯函数，store 的 CAS 更新以此为唯一规则来源）。
     *
     * @param target 目标状态
     * @return true 表示 this → target 合法
     */
    public boolean canTransitTo(AgentRunStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == RUNNING || target == CANCELLED || target == FAILED;
            case RUNNING -> target == WAITING_APPROVAL || target == CANCEL_REQUESTED
                || target == VERIFYING || target == SUCCEEDED || target == FAILED;
            case WAITING_APPROVAL -> target == RUNNING || target == CANCEL_REQUESTED || target == FAILED;
            case CANCEL_REQUESTED -> target == CANCELLED || target == FAILED;
            case VERIFYING -> target == SUCCEEDED || target == CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    /**
     * 可迁移到 target 的全部来源状态（用于构造 CAS 的 expected 集）。
     *
     * @param target 目标状态
     * @return 合法来源集合（可能为空）
     */
    public static Set<AgentRunStatus> sourcesOf(AgentRunStatus target) {
        Set<AgentRunStatus> sources = EnumSet.noneOf(AgentRunStatus.class);
        for (AgentRunStatus s : values()) {
            if (s.canTransitTo(target)) {
                sources.add(s);
            }
        }
        return sources;
    }
}
