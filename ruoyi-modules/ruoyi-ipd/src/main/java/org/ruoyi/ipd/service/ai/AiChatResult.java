package org.ruoyi.ipd.service.ai;

/**
 * AI 单轮生成统一结果（AI-STRAT-2 起为顶层类型，旧链曾嵌套于 AiChatClient）。
 * <p>错误码白名单（与 P4-2.1 Tester / AiGateway 同源）：AUTH_FAILED / HTTP_n / TIMEOUT /
 * UNREACHABLE / EMPTY_RESPONSE / UNSUPPORTED_PROTOCOL；成功时 errorCode/errorMessage 为 null。
 *
 * @param content          模型全文（原样透传，BR-AI-04）
 * @param promptTokens     usage.prompt_tokens（响应缺失记 0）
 * @param completionTokens usage.completion_tokens（响应缺失记 0）
 * @param errorCode        失败类别（成功时 null）
 */
public record AiChatResult(boolean success, String content, int promptTokens, int completionTokens,
                           long latencyMs, String errorCode, String errorMessage) {

    public static AiChatResult ok(String content, int promptTokens, int completionTokens, long latencyMs) {
        return new AiChatResult(true, content, Math.max(0, promptTokens), Math.max(0, completionTokens),
            Math.max(0, latencyMs), null, null);
    }

    public static AiChatResult fail(String code, String message, long latencyMs) {
        return new AiChatResult(false, null, 0, 0, Math.max(0, latencyMs), code, message);
    }
}
