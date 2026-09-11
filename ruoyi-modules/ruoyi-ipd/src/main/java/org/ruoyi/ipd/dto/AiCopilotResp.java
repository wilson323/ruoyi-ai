package org.ruoyi.ipd.dto;

import java.util.List;

/**
 * AI-P2-3（2026-09-11）：副驾问答响应。
 * - intent：TASKS / ADVANCE / CHITCHAT（命中即返回结构化数据；CHITCHAT 留 answer 给模型填空）；
 * - answer：模型回答（CHITCHAT 主路径；TASKS/ADVANCE 也附模型解释文本）；
 * - sources：本次注入的项目上下文摘要（不重复 prompt 原文，BR-AI-04）；
 * - token/latency：审计同口径，方便前端做 token 计数展示。
 */
public record AiCopilotResp(String intent,
                            String answer,
                            List<CopilotDataItem> data,
                            List<String> sources,
                            int tokenPrompt,
                            int tokenCompletion,
                            long latencyMs) {

    /** 意图兜底返回的结构化数据项（待办条目 / 当前推进项 / 闲聊留空）。 */
    public record CopilotDataItem(String type, String title, String hint, String url) {}
}
