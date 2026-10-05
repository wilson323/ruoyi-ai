package org.ruoyi.service.knowledge.retriever;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Mono;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.KnowledgeEmbedEndpoint;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;

import java.util.List;
import java.util.Objects;

/**
 * 自定义检索器：适配 AgentScope Knowledge 只读接口
 * 桥接统一的 KnowledgeRetrievalService，支持配置化的混合检索、阈值过滤等功能
 *
 * @author RobustH
 */
@Slf4j
@RequiredArgsConstructor
public class CustomVectorRetriever implements Knowledge {

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final KnowledgeInfoVo knowledgeInfoVo;
    private final ChatModelVo chatModelVo;

    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(new UnsupportedOperationException("知识写入须走已有业务写入入口"));
    }

    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        return Mono.fromCallable(() -> retrieveBound(query));
    }

    private List<Document> retrieveBound(String query) {

        // 构建增强后的查询参数
        QueryVectorBo queryVectorBo = new QueryVectorBo();
        queryVectorBo.setQuery(query);
        queryVectorBo.setKid(String.valueOf(knowledgeInfoVo.getId()));
        KnowledgeEmbedEndpoint.Choice choice = KnowledgeEmbedEndpoint.resolve(knowledgeInfoVo.getEmbeddingModel());
        if (choice.builtin()) {
            queryVectorBo.setBaseUrl(choice.baseUrl());
            queryVectorBo.setEmbeddingModelName(choice.modelName());
        } else {
            queryVectorBo.setBaseUrl(chatModelVo.getApiHost());
            queryVectorBo.setEmbeddingModelName(knowledgeInfoVo.getEmbeddingModel());
        }
        queryVectorBo.setVectorModelName(knowledgeInfoVo.getVectorModel());
        
        // 应用知识库配置参数
        queryVectorBo.setMaxResults(knowledgeInfoVo.getRetrieveLimit());
        queryVectorBo.setSimilarityThreshold(knowledgeInfoVo.getSimilarityThreshold());
        queryVectorBo.setEnableHybrid(Objects.equals(knowledgeInfoVo.getEnableHybrid(), 1));
        queryVectorBo.setHybridAlpha(knowledgeInfoVo.getHybridAlpha());

        // 设置重排序参数 (如果 retriever 阶段也想做初步重排，可以在此设置)
        queryVectorBo.setEnableRerank(Objects.equals(knowledgeInfoVo.getEnableRerank(), 1));
        queryVectorBo.setRerankModelName(knowledgeInfoVo.getRerankModel());
        queryVectorBo.setRerankTopN(knowledgeInfoVo.getRerankTopN());
        queryVectorBo.setRerankScoreThreshold(knowledgeInfoVo.getRerankScoreThreshold());

        // 通过统一服务执行检索
        var nearestList = knowledgeRetrievalService.retrieve(queryVectorBo);

        if (nearestList == null) {
            throw new IllegalStateException("知识库检索未返回有效结果");
        }
        return nearestList.stream()
            .filter(vo -> vo != null && vo.getContent() != null && !vo.getContent().isBlank())
            .map(vo -> {
                var payload = Map.<String, Object>of(
                    "kid", String.valueOf(knowledgeInfoVo.getId()),
                    "sourceName", Objects.toString(vo.getSourceName(), "未知来源"));
                Document document = new Document(new DocumentMetadata(TextBlock.builder().text(vo.getContent()).build(),
                    Objects.toString(vo.getDocId(), ""), Objects.toString(vo.getId(), ""), payload));
                document.setScore(vo.getScore());
                return document;
            }).toList();
    }
}
