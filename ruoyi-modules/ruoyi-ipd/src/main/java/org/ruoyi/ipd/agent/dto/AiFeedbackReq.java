package org.ruoyi.ipd.agent.dto;

/**
 * PUT /api/v1/ai-feedback/{targetType}/{targetId} 请求体（合同 #6）。
 *
 * @param rating UP / DOWN
 * @param reason 原因（可空，≤500 字）
 */
public record AiFeedbackReq(String rating, String reason) {
}
