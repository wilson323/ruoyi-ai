package org.ruoyi.ipd.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.AiDocEmbedding;

/**
 * AI 文档向量化切片 Mapper（AI-STRAT-1）。
 * 写通道纪律：doc 级重导向量化 = 先 delete(doc_id) 再 insert（{@code AiDocEmbeddingService}
 * 受控），切片行不支持业务 update；检索读按 (project_id, embed_model) 走 idx_emb_project。
 * <p>
 * R184-A 加固（2026-09-23）：ai_doc_embeddings 已登记在 {@code tenant.excludes}，
 * 但异步 embedExecutor 线程 / IPD StpLogic 上下文同样可能在 MP 拦截器拼接
 * {@code tenant_id IS NULL} 时误过滤本表行；类级 {@code @InterceptorIgnore}
 * 兜底，避免全链生产调用者核验失稳。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AiDocEmbeddingMapper extends BaseMapperPlus<AiDocEmbedding, AiDocEmbedding> {
}
