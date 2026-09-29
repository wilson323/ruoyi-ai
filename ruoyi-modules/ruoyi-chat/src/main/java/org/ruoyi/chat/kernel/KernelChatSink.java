package org.ruoyi.chat.kernel;

/**
 * W1 内核事件出口缝（2026-09-28，ADR-0075 矩阵 #7「包装」）。
 *
 * <p>AgentScope {@code streamEvents} 事件流经 {@link KernelEventFrames} 映射为本接口回调，
 * 由各传输适配器翻译为既有前端契约帧（契约零改动硬约束）：
 * <ul>
 *   <li>SSE（POST /chat/send，{@code SseMessageUtils}）：{@code content} / {@code reasoning} /
 *       {@code mcp_tool} / {@code error} / {@code done} 事件；</li>
 *   <li>WS（/chat/ws）：增量 {@code {"content":"..."}}、结束 {@code [DONE]}、
 *       错误 {@code {"data":"错误:..."}}。</li>
 * </ul>
 *
 * <p>语义约定（与既有 {@code StreamingChatResponseHandler} 生命周期对齐）：
 * 流正常收尾回调 {@link #onComplete()} 恰一次；失败回调 {@link #onError(String, String)} 后
 * 不再回调 {@code onComplete}；{@code onError} 之后适配器负责连接收尾（complete/close）。
 */
public interface KernelChatSink {

    /** 正文增量（对应 TextBlockDeltaEvent.getDelta()）。 */
    void onContent(String delta);

    /** 推理增量（对应 ThinkingBlockDeltaEvent.getDelta()；WS 契约无推理帧，适配器可丢弃）。 */
    void onReasoning(String delta);

    /**
     * 工具调用裁决帧（对应 mcp_tool 契约事件）。
     *
     * @param toolName 工具名
     * @param status   裁决映射：{@code allowed} / {@code approval_required} / {@code denied}
     *                 （{@code PolicyDecision} 三态，见 {@link KernelEventFrames#statusOf}）
     * @param result   裁决理由（ToolPolicyEvaluation.reason）
     */
    void onMcpTool(String toolName, String status, String result);

    /**
     * 流失败（错误帧）。适配器须完成连接收尾。
     *
     * @param code    机器可读错误码（SCOPE_REJECTED / KERNEL_ERROR / KERNEL_STREAM_ERROR）
     * @param message 错误描述（对外展示前须由适配器脱敏）
     */
    void onError(String code, String message);

    /** 流正常结束（done 帧 + 连接收尾）。 */
    void onComplete();
}
