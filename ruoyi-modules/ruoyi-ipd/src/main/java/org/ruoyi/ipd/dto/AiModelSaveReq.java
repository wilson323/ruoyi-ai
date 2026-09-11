package org.ruoyi.ipd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * P4-2.1：AI 模型配置保存白名单（CODE-01：id/enabled 不可注入；update 时 apiKey=null 表示不改密钥）。
 * AI-STRAT-1（2026-09-11）：embedEndpoint/embedModel 两键可选——RAG 向量化端点（OpenAI 兼容
 * /embeddings），落 config_json；update 时 null=不动、blank=显式清除（关闭 RAG 的运营途径）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiModelSaveReq(String provider, String endpoint, String apiKey,
                             String model, BigDecimal temperature, Integer maxTokens,
                             String embedEndpoint, String embedModel) {
}
