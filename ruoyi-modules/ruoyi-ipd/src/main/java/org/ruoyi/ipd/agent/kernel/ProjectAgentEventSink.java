package org.ruoyi.ipd.agent.kernel;

import java.util.Map;

/**
 * 内核 → 运行执行器的事件出口。实现方负责按 seq 持久化与终态收口；
 * {@link #onError} 与 {@link #onComplete} 对同一运行至多生效一次（由执行器 CAS 保证）。
 */
public interface ProjectAgentEventSink {

    /**
     * 执行步骤（如 SKILL_LOADED / MODEL_CALL）。
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
