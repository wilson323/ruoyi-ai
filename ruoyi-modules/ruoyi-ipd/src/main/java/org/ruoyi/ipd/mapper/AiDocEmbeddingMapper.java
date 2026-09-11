package org.ruoyi.ipd.mapper;

import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.AiDocEmbedding;

/**
 * AI 文档向量化切片 Mapper（AI-STRAT-1）。
 * 写通道纪律：doc 级重导向量化 = 先 delete(doc_id) 再 insert（{@code AiDocEmbeddingService}
 * 受控），切片行不支持业务 update；检索读按 (project_id, embed_model) 走 idx_emb_project。
 */
public interface AiDocEmbeddingMapper extends BaseMapperPlus<AiDocEmbedding, AiDocEmbedding> {
}
