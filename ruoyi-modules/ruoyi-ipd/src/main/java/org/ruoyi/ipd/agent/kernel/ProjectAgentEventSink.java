package org.ruoyi.ipd.agent.kernel;

import java.util.Map;

/**
 * 内核 → 运行执行器的事件出口。实现方负责按 seq 持久化与终态收口；
 * {@link #onError} 与 {@link #onComplete} 对同一运行至多生效一次（由执行器 CAS 保证）。
 */
public interface ProjectAgentEventSink {

    /** 在模型或工具实际开始前验证当前执行所有权；生产sink绑定原run epoch。 */
    default void requireActiveOwnership() { }

    /** 生产运行原租约的执行版本；未绑定的 sink 不提供猜测值。 */
    default long executionEpoch() {
        throw new IllegalStateException("execution epoch is not bound");
    }

    /** SDK临时状态也在原run epoch事务保护下访问，不另建权限或状态源。 */
    default <T> T withActiveOwnership(java.util.function.Supplier<T> action) {
        requireActiveOwnership();
        return action.get();
    }

    /** 注册该运行SDK临时checkpoint清理；终态在释放所有权之前执行。 */
    default void registerTemporaryStateCleanup(Runnable cleanup) { }

    /** 原终态事务已成功提交时的服务器回执；不是模型完成事件。 */
    default void registerTerminalSuccessReceipt(Runnable receipt) {
        throw new IllegalStateException("terminal commit receipt is not configured");
    }

    /** SDK资源finalizer在调用真实完成/错误之后、业务终态之前回收临时checkpoint。 */
    default void releaseTemporaryState() { }

    /** 官方中断必须持久化并暂停原运行，未装配时明确失败。 */
    default void onAguiInterrupt(Map<String, io.agentscope.core.agui.event.AguiEvent.Interrupt> pending,
                                 long checkpointVersion) {
        throw new IllegalStateException("AG-UI pause handler is not configured");
    }

    /** Persist trusted child receipts and both checkpoints in the original run epoch transaction before WAIT CAS. */
    default void onChildInterrupt(java.util.List<ProjectAgentChildLineageRegistry.ChildApproval> approvals,
                                  long rootCheckpointVersion) {
        throw new IllegalStateException("Original run child approval pause consumer is not configured");
    }

    /** 子恢复必须匹配原持久审批消费事件；默认实现不能授信。 */
    default void requireChildResumeConsumed(ProjectAgentChildLineageRegistry.ChildApproval approval) {
        throw new IllegalStateException("child consumed receipt guard is not configured");
    }

    /** 暂停保留服务器 checkpoint；不能按终态删除。 */
    default boolean isPaused() { return false; }

    /**
     * 执行步骤（如 SKILL_SELECTED / MODEL_CALL）。
     *
     * @param kind 步骤类型
     * @param detail 步骤明细（不含凭据与推理原文）
     */
    void onStep(String kind, Map<String, Object> detail);

    /**
     * 工具调用开始（来自原生 ToolCallStartEvent）。
     *
     * @param toolCallId 原生调用 ID
     * @param toolName 工具名
     */
    void onToolCall(String toolCallId, String toolName);

    /**
     * 工具调用结果（来自原生 ToolResultEndEvent）。
     *
     * @param toolCallId 原生调用 ID
     * @param toolName 工具名
     * @param state 结果状态
     */
    void onToolResult(String toolCallId, String toolName, String state);

    /**
     * 检索来源（由只读工具在真实检索后上报）。
     *
     * @param source 来源摘要
     */
    void onSource(Map<String, Object> source);

    /**
     * 模型文本增量。
     *
     * @param delta 文本片段
     */
    void onText(String delta);

    /** 原生最终消息的权威正文；实现沿同一TEXT_DELTA事件替换，默认兼容旧测试sink。 */
    default void onFinalText(String fullText) { }

    /**
     * 产物草稿已落库（契约 ARTIFACT 事件）。
     *
     * @param artifactId 逻辑产物 ID（字符串）
     * @param title 标题
     * @param contentHash 正文 SHA-256
     * @param version 版本号
     */
    void onArtifact(String artifactId, String title, String contentHash, int version);

    /**
     * 执行失败（安全错误码，不含内部异常原文）。
     *
     * @param errorCode 错误码
     */
    void onError(String errorCode);

    /** 执行正常结束。 */
    void onComplete();
}
