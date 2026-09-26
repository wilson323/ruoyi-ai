package org.ruoyi.ipd.dto;

import java.util.List;
import java.util.Map;

/**
 * AI-P2-3（2026-09-11）：副驾问答响应。
 * - intent：TASKS / ADVANCE / FILL_PAGE / CHITCHAT（命中即返回结构化数据；CHITCHAT 留 answer 给模型填空）；
 * - answer：模型回答（CHITCHAT 主路径；TASKS/ADVANCE 也附模型解释文本）；
 * - sources：本次注入的项目上下文摘要（不重复 prompt 原文，BR-AI-04）；
 * - token/latency：审计同口径，方便前端做 token 计数展示；
 * - fillPayload（R221 对话即填表，2026-09-26，spec §3.5）：仅 FILL_PAGE 意图非空，
 *   形如 {@code {scene, fields:{...}, mode:"suggest"}}，前端据此回填表单（首切片永远 suggest，需用户确认）。
 */
public record AiCopilotResp(String intent,
                            String answer,
                            List<CopilotDataItem> data,
                            List<String> sources,
                            int tokenPrompt,
                            int tokenCompletion,
                            long latencyMs,
                            Map<String, Object> fillPayload) {

    /** 兼容 7 参历史调用（fillPayload 默认 null = 非填表意图）。 */
    public AiCopilotResp(String intent, String answer, List<CopilotDataItem> data,
                         List<String> sources, int tokenPrompt, int tokenCompletion, long latencyMs) {
        this(intent, answer, data, sources, tokenPrompt, tokenCompletion, latencyMs, null);
    }

    /** 意图兜底返回的结构化数据项（待办条目 / 当前推进项 / 闲聊留空）。 */
    public record CopilotDataItem(String type, String title, String hint, String url) {}
}
