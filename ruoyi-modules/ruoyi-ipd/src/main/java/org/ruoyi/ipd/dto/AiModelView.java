package org.ruoyi.ipd.dto;

import java.math.BigDecimal;

/**
 * P4-2.1：AI 模型配置回显视图——**不含 api_key 明文/密文**，仅脱敏掩码（BR：密钥不回显）。
 * AI-STRAT-1：embedEndpoint/embedModel 从 config_json 展开（RAG 开关可见性，两键缺一=RAG 关闭）。
 */
public record AiModelView(Long id, String provider, String endpoint, String model,
                          BigDecimal temperature, Integer maxTokens, String enabled,
                          String maskedKey, String embedEndpoint, String embedModel) {
}
