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
 * - docType 可空（R184 阶段 3，2026-09-23）：AI 副驾 RAG 检索限定文档类型。
 *   非空时仅检索该类型的项目历史文档（按 idx_emb_doctype 走）；null/blank = 不过滤（向后兼容历史语义）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiCopilotReq(Long projectId,
                           @NotBlank @Size(max = 2000) String message,
                           List<CopilotTurn> history,
                           String docType) {

    /** 兼容 3 参历史调用（docType 默认 null = 不过滤）。 */
    public AiCopilotReq(Long projectId, String message, List<CopilotTurn> history) {
        this(projectId, message, history, null);
    }

    /** 多轮上下文条目（role=user|assistant；历史最大 8 轮，超出由前端分页）。 */
    public record CopilotTurn(String role, String content) {}
}
