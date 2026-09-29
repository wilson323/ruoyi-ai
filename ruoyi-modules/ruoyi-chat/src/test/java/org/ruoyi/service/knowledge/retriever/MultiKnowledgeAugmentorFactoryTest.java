package org.ruoyi.service.knowledge.retriever;

import dev.langchain4j.rag.RetrievalAugmentor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B1 四刀之四（C2 收敛）：MultiKnowledgeAugmentorFactory 行为直测。
 * <p>
 * 收敛前 ChatServiceFacade 与 MpChatWebSocketHandler 各持一份同构
 * buildMultiKnowledgeAugmentor（ws 版为顺序检索、无去重无限界，弱于门面版），
 * 统一为工厂单一实现（并行+去重+限界）。kid 级可见性不在工厂内重复裁决——
 * 由调用方收集 kids 时经 KnowledgeAccessGate（B0）。
 * 空态：无库/库不存在/模型未配置 → 返回 null（调用方按无增强回退原文）。
 */
@Tag("dev")
class MultiKnowledgeAugmentorFactoryTest {

    private final IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
    private final IChatModelService chatModelService = mock(IChatModelService.class);

    private MultiKnowledgeAugmentorFactory factory() {
        return new MultiKnowledgeAugmentorFactory(infoService, chatModelService,
            mock(KnowledgeRetrievalService.class));
    }

    private static KnowledgeInfoVo kb(Long id, String embeddingModel) {
        KnowledgeInfoVo vo = new KnowledgeInfoVo();
        vo.setId(id);
        vo.setEmbeddingModel(embeddingModel);
        return vo;
    }

    @Test
    void nullOrEmptyKidsReturnNull() {
        assertNull(factory().buildMultiKnowledgeAugmentor(null));
        assertNull(factory().buildMultiKnowledgeAugmentor(List.of()));
    }

    @Test
    void missingKnowledgeOrModelDegradesToNull() {
        // 空态：库不存在 / 向量模型未配置 → 无可用检索器 → null（不抛错不增强）
        when(infoService.queryById(1L)).thenReturn(null);
        when(infoService.queryById(2L)).thenReturn(kb(2L, "emb-v3"));
        when(chatModelService.selectModelByName("emb-v3")).thenReturn(null);
        assertNull(factory().buildMultiKnowledgeAugmentor(List.of(1L)));
        assertNull(factory().buildMultiKnowledgeAugmentor(List.of(2L)));
    }

    @Test
    void singleKnowledgeBuildsAugmentor() {
        when(infoService.queryById(1L)).thenReturn(kb(1L, "emb-v3"));
        when(chatModelService.selectModelByName("emb-v3")).thenReturn(new ChatModelVo());
        RetrievalAugmentor augmentor = factory().buildMultiKnowledgeAugmentor(List.of(1L));
        assertNotNull(augmentor, "单库应直接构建 DefaultRetrievalAugmentor+CustomVectorRetriever");
    }

    @Test
    void multipleKnowledgeBuildsCompositeAugmentor() {
        when(infoService.queryById(1L)).thenReturn(kb(1L, "emb-v3"));
        when(infoService.queryById(2L)).thenReturn(kb(2L, "emb-v4"));
        when(chatModelService.selectModelByName("emb-v3")).thenReturn(new ChatModelVo());
        when(chatModelService.selectModelByName("emb-v4")).thenReturn(new ChatModelVo());
        RetrievalAugmentor augmentor = factory().buildMultiKnowledgeAugmentor(List.of(1L, 2L));
        assertNotNull(augmentor, "多库应经 CompositeContentRetriever（并行+去重+限界）构建");
    }

    @Test
    void buildFailureOfOneKidDoesNotAbortOthers() {
        when(infoService.queryById(1L)).thenThrow(new RuntimeException("db down"));
        when(infoService.queryById(2L)).thenReturn(kb(2L, "emb-v3"));
        when(chatModelService.selectModelByName("emb-v3")).thenReturn(new ChatModelVo());
        RetrievalAugmentor augmentor = factory().buildMultiKnowledgeAugmentor(List.of(1L, 2L));
        assertNotNull(augmentor, "单库构建失败只跳过该库，不得中断整体（fail-open 到可用子集）");
    }
}
