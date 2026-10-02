package org.ruoyi.service.knowledge.retriever;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeEmbedEndpoint;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Facade 与 WebSocket 共用的 AgentScope 知识检索装配；库级 gate 仍由调用者裁决。 */
@Component
@RequiredArgsConstructor
public class MultiKnowledgeAugmentorFactory {
    private final IKnowledgeInfoService knowledgeInfoService;
    private final IChatModelService chatModelService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    /**
     * knowledgeIds 已通过 KnowledgeAccessGate；sessionId 不用于推断身份或授予权限。
     * 在当前线程检索，保留现有 Person 会话，避免公共线程池丢失权限上下文。
     */
    public String augment(List<Long> knowledgeIds, String text, Object sessionId) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) { return text; }
        Map<String, Document> unique = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int succeeded = 0;
        for (Long kid : knowledgeIds.stream().distinct().toList()) {
            try {
                var kb = knowledgeInfoService.queryById(kid);
                if (kb == null) { throw new IllegalStateException("知识库不存在"); }
                var choice = KnowledgeEmbedEndpoint.resolve(kb.getEmbeddingModel());
                ChatModelVo embedding;
                if (choice.builtin()) {
                    embedding = new ChatModelVo();
                    embedding.setModelName(choice.modelName());
                    embedding.setApiHost(choice.baseUrl());
                } else {
                    embedding = chatModelService.selectModelByName(choice.modelName());
                    if (embedding == null) { throw new IllegalStateException("嵌入模型不可用"); }
                }
                var docs = new CustomVectorRetriever(knowledgeRetrievalService, kb, embedding)
                    .retrieve(text, RetrieveConfig.builder().build()).block(Duration.ofSeconds(60));
                if (docs == null) { throw new IllegalStateException("知识检索未返回结果"); }
                succeeded++;
                for (Document document : docs) {
                    var metadata = document.getMetadata();
                    String doc = metadata.getDocId();
                    String chunk = metadata.getChunkId();
                    String key = kid + "|" + doc + "|" + chunk;
                    if ((doc == null || doc.isBlank()) && (chunk == null || chunk.isBlank())) {
                        key = kid + "|" + metadata.getContentText();
                    }
                    unique.putIfAbsent(key, document);
                }
            } catch (Exception error) {
                failures.add("知识库 " + kid + " 检索失败（" + error.getClass().getSimpleName() + "）");
            }
        }
        if (succeeded == 0) { throw new ServiceException("知识检索不可用：" + String.join("；", failures)); }
        StringBuilder context = new StringBuilder();
        if (!failures.isEmpty()) {
            context.append("【知识检索部分失败】\n").append(String.join("\n", failures)).append('\n');
        }
        int chars = 0;
        int count = 0;
        for (Document doc : unique.values()) {
            String content = doc.getMetadata().getContentText();
            if (count >= 20 || chars + content.length() > 24000) { break; }
            context.append("【知识片段｜库：").append(doc.getPayloadValue("kid"))
                .append("｜出处：").append(doc.getPayloadValue("sourceName"))
                .append("｜文档：").append(doc.getMetadata().getDocId())
                .append("｜片段：").append(doc.getMetadata().getChunkId()).append("】\n")
                .append(content).append('\n');
            chars += content.length();
            count++;
        }
        if (context.isEmpty()) { return text; }
        return text + "\n\n以下是知识检索结果，保留出处；失败的知识库未取得内容，不得编造。\n" + context;
    }
}
