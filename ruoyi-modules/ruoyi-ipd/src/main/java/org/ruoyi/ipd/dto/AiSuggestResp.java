package org.ruoyi.ipd.dto;

/**
 * R227-C1（AI-FUSION L2，2026-09-26）：域内 AI 建议响应体（方案 §5.1）。
 * <ul>
 *   <li>{@code markdown} AI 输出正文，仅返回给前端展示，**绝不写业务表**（方案 §5.1 强约束）；
 *       用户是否采纳、采纳到哪个字段由前端页面人工决定；</li>
 *   <li>{@code scene} 回显请求场景（前端多入口共用组件时用于分发"采纳"行为）；</li>
 *   <li>{@code aiModel} 实际调用模型名；未启用/失败降级时填 {@code "intent_match"}
 *       （AI-审计三件套规约 §1：白名单占位，门禁知道非真模型）；</li>
 *   <li>{@code degraded} true = 本轮没有真调 AI（模型未配置等降级路径），前端据此展示引导文案。</li>
 * </ul>
 */
public record AiSuggestResp(String scene,
                            String markdown,
                            String aiModel,
                            int promptTokens,
                            int completionTokens,
                            long latencyMs,
                            boolean degraded) {

    /** 降级应答构造（未配置模型 / 空上下文等不调 AI 的路径）。 */
    public static AiSuggestResp degraded(String scene, String markdown, long latencyMs) {
        return new AiSuggestResp(scene, markdown, "intent_match", 0, 0, latencyMs, true);
    }
}
