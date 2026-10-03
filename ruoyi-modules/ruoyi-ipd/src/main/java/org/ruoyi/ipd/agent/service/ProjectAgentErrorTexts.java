package org.ruoyi.ipd.agent.service;

import java.util.Map;

/**
 * 运行错误码 → 对外安全文案（ERROR 事件 payload.message）。只暴露固定文案，
 * 不透传内部异常信息；未知码统一为通用失败文案。
 */
public final class ProjectAgentErrorTexts {

    private static final String DEFAULT_TEXT = "智能体执行失败，请稍后重试";

    private static final Map<String, String> TEXTS = Map.of(
        "SCOPE_REJECTED", "运行身份校验失败",
        "MODEL_UNAVAILABLE", "所选模型当前不可用",
        "KERNEL_ERROR", "智能体装配失败，请稍后重试",
        "STREAM_ERROR", "模型输出中断，请稍后重试",
        "RUN_TIMEOUT", "运行超时，已终止",
        "AGENT_BUSY", "智能体繁忙，请稍后重试",
        "ARTIFACT_PERSIST", "产物没有保存下来，请稍后重试",
        ProjectAgentCompletionGate.REJECTED, "没有取得可交付正文、检索依据不足或结论越权，产物未生成");

    private ProjectAgentErrorTexts() {
    }

    /**
     * 取安全文案。
     *
     * @param errorCode 错误码
     * @return 文案
     */
    public static String textOf(String errorCode) {
        return errorCode == null ? DEFAULT_TEXT : TEXTS.getOrDefault(errorCode, DEFAULT_TEXT);
    }
}
