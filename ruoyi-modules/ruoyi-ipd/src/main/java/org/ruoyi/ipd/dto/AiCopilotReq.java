package org.ruoyi.ipd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI-P2-3（2026-09-11）：副驾问答请求体。
 * - projectId 可空（闲聊/全局问题）；非空时按可见项目过滤（不教 AI 编数据）；
 * - history 多轮上下文（只取角色+内容，长度受控避免 prompt 爆炸）；
 * - message 必填，≤2000 字符（与 MAX_PROMPT_LEN=30000 解耦——副驾问答短交互）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiCopilotReq(Long projectId,
                           @NotBlank @Size(max = 2000) String message,
                           List<CopilotTurn> history) {

    /** 多轮上下文条目（role=user|assistant；历史最大 8 轮，超出由前端分页）。 */
    public record CopilotTurn(String role, String content) {}
}
