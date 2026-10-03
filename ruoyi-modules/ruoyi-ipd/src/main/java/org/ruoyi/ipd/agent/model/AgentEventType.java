package org.ruoyi.ipd.agent.model;

/**
 * 运行事件类型（W1 合同枚举，按 seq 持久化）。
 *
 * <p>{@link #RUN_FINISHED} 与 {@link #ERROR} 互斥且每个运行恰有一个：
 * SUCCEEDED/CANCELLED 以 RUN_FINISHED 收尾，FAILED 以 ERROR 收尾。
 */
public enum AgentEventType {
    RUN_STARTED,
    STEP,
    TOOL_CALL,
    TOOL_RESULT,
    SOURCE,
    TEXT_DELTA,
    ARTIFACT,
    /**
     * 长期记忆写入回执（2026-10-03 新增）。回答交付之后的后台副作用结果，
     * 与 {@link #ERROR} 分离：记忆写失败不再改写业务终态，改由本事件如实记账并可按它补写。
     * 非终态事件。
     */
    MEMORY_RECEIPT,
    ERROR,
    RUN_FINISHED;

    /**
     * 是否终态事件。
     *
     * @return true 表示 RUN_FINISHED 或 ERROR
     */
    public boolean isTerminal() {
        return this == RUN_FINISHED || this == ERROR;
    }
}
