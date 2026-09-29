package org.ruoyi.service.knowledge.retriever;

import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 多知识库复合检索增强器工厂（B1 C2 收敛点）。
 * <p>
 * B0 前置：ChatServiceFacade 与 MpChatWebSocketHandler 各持一份同构
 * {@code buildMultiKnowledgeAugmentor} 私有实现，检索链改造（B1 过滤通道、B2 桥注入）
 * 需双点同步、易漂移。本工厂收敛为单一实现，两个调用方统一委托；
 * kid 级可见性不在工厂内重复裁决——调用方收集 knowledgeIds 时必须先经
 * {@code KnowledgeAccessGate}（B0 已收敛四处直传口），工厂只做组装。
 * <p>
 * 组装规则（原 ChatServiceFacade 版本为准，功能较 ws 顺序版增强）：
 * 单知识库直接用 {@link CustomVectorRetriever}；多知识库用
 * {@link CompositeContentRetriever} 并发查询各库、按 kid|docId|fid 去重、
 * 并施加 20 条 / 24000 字符合并限界。
 *
 * @author ageer
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MultiKnowledgeAugmentorFactory {

    private final IKnowledgeInfoService knowledgeInfoService;

    private final IChatModelService chatModelService;

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    /**
     * 构建多知识库复合检索增强器。
     *
     * @param knowledgeIds 已过 KnowledgeAccessGate 的知识库 id 列表
     * @return 增强器；knowledgeIds 为空或无任何可用检索器时返回 null（调用方按无增强处理）
     */
    public RetrievalAugmentor buildMultiKnowledgeAugmentor(List<Long> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return null;
        }
        List<ContentRetriever> retrievers = new ArrayList<>();
        for (Long kid : knowledgeIds) {
            try {
                KnowledgeInfoVo kb = knowledgeInfoService.queryById(kid);
                if (kb == null) {
                    continue;
                }
                ChatModelVo embModel = chatModelService.selectModelByName(kb.getEmbeddingModel());
                if (embModel == null) {
                    log.warn("knowledge_retriever status=SKIPPED reason=EMBEDDING_MODEL_UNAVAILABLE kid={}", kid);
                    continue;
                }
                retrievers.add(new CustomVectorRetriever(knowledgeRetrievalService, kb, embModel));
            } catch (Exception e) {
                log.warn("knowledge_retriever operation=BUILD status=FAILED kid={} errorType={}", kid, errorType(e));
            }
        }
        if (retrievers.isEmpty()) {
            return null;
        }
        // 单库直接返回；多库用复合检索器
        ContentRetriever composite = retrievers.size() == 1
            ? retrievers.get(0)
            : new CompositeContentRetriever(retrievers);
        return DefaultRetrievalAugmentor.builder()
            .contentRetriever(composite)
            .build();
    }

    /**
     * 复合内容检索器：对多个知识库检索器并发查询并合并结果。
     * 包内可见以支持单测（不暴露到工厂 API 面）。
     */
    static final class CompositeContentRetriever implements ContentRetriever {
        private final List<ContentRetriever> delegates;

        CompositeContentRetriever(List<ContentRetriever> delegates) {
            this.delegates = delegates;
        }

        @Override
        public List<Content> retrieve(Query query) {
            List<CompletableFuture<List<Content>>> futures = delegates.stream()
                    .map(r -> CompletableFuture.supplyAsync(() -> {
                        try {
                            List<Content> part = r.retrieve(query);
                            return part == null ? List.<Content>of() : part;
                        } catch (Exception e) {
                            log.warn("knowledge_retriever operation=RETRIEVE status=FAILED errorType={}",
                                errorType(e));
                            return List.<Content>of();
                        }
                    })).toList();
            Map<String, Content> unique = new LinkedHashMap<>();
            for (CompletableFuture<List<Content>> future : futures) {
                for (Content content : future.join()) {
                    String key = content.textSegment().metadata().getString("kid") + "|"
                            + content.textSegment().metadata().getString("docId") + "|"
                            + content.textSegment().metadata().getString("fid");
                    if (key.endsWith("null|null|null")) key = content.textSegment().text();
                    unique.putIfAbsent(key, content);
                }
            }
            List<Content> bounded = new ArrayList<>();
            int chars = 0;
            for (Content content : unique.values()) {
                int next = content.textSegment().text().length();
                if (bounded.size() >= 20 || chars + next > 24000) break;
                bounded.add(content);
                chars += next;
            }
            return bounded;
        }
    }

    static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getName();
    }
}
