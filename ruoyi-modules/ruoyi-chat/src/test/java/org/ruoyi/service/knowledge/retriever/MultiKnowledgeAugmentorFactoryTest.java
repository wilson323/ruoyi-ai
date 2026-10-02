package org.ruoyi.service.knowledge.retriever;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeEmbedEndpoint;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class MultiKnowledgeAugmentorFactoryTest {
    private final IKnowledgeInfoService info = mock(IKnowledgeInfoService.class);
    private final IChatModelService models = mock(IChatModelService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final MultiKnowledgeAugmentorFactory factory = new MultiKnowledgeAugmentorFactory(info, models, retrieval);

    private KnowledgeInfoVo kb(long id) {
        KnowledgeInfoVo kb = new KnowledgeInfoVo();
        kb.setId(id);
        return kb;
    }

    @Test
    void noSelectionPreservesInput() {
        assertEquals("input", factory.augment(null, "input", null));
        assertEquals("input", factory.augment(List.of(), "input", null));
        verifyNoInteractions(info, models, retrieval);
    }

    @Test
    void missingLibraryOrModelIsFailureNotNoHit() {
        assertThrows(ServiceException.class, () -> factory.augment(List.of(1L), "input", null));
        var kb = kb(2L);
        kb.setEmbeddingModel("missing");
        when(info.queryById(2L)).thenReturn(kb);
        assertThrows(ServiceException.class, () -> factory.augment(List.of(2L), "input", null));
    }

    @Test
    void builtinPreservesSourceAndQueryConfigurationAndDeduplicates() {
        var kb = kb(1L);
        kb.setRetrieveLimit(7);
        when(info.queryById(1L)).thenReturn(kb);
        var hit = KnowledgeRetrievalVo.builder().id("fragment").docId("document").sourceName("original.pdf")
            .content("事实原文").score(0.8).build();
        when(retrieval.retrieve(any(QueryVectorBo.class))).thenReturn(List.of(hit, hit));
        String result = factory.augment(List.of(1L, 1L), "input", "session");
        assertTrue(result.contains("original.pdf"));
        assertTrue(result.contains("document"));
        assertEquals(1, result.split("事实原文", -1).length - 1);
        verify(models, never()).selectModelByName(anyString());
        verify(retrieval).retrieve(argThat(bo -> bo.getMaxResults() == 7
            && KnowledgeEmbedEndpoint.MODEL_NAME.equals(bo.getEmbeddingModelName())
            && KnowledgeEmbedEndpoint.BASE_URL.equals(bo.getBaseUrl())));
    }

    @Test
    void mixedFailureIsVisibleAndSuccessfulNoHitRemainsNoHit() {
        when(info.queryById(1L)).thenThrow(new IllegalStateException("db"));
        when(info.queryById(2L)).thenReturn(kb(2L));
        when(retrieval.retrieve(any(QueryVectorBo.class))).thenReturn(List.of());
        assertTrue(factory.augment(List.of(1L, 2L), "input", null).contains("知识检索部分失败"));
        assertEquals("input", factory.augment(List.of(2L), "input", null));
        when(retrieval.retrieve(any(QueryVectorBo.class))).thenThrow(new IllegalStateException("vector"));
        assertThrows(ServiceException.class, () -> factory.augment(List.of(2L), "input", null));
    }

    @Test
    void boundedAtTwentyDocumentsAndWriteContractRejected() {
        var kb = kb(1L);
        when(info.queryById(1L)).thenReturn(kb);
        var rows = java.util.stream.IntStream.range(0, 30).mapToObj(i -> KnowledgeRetrievalVo.builder()
            .id("f" + i).docId("d").content("entry " + i).sourceName("source").build()).toList();
        when(retrieval.retrieve(any(QueryVectorBo.class))).thenReturn(rows);
        String result = factory.augment(List.of(1L), "input", null);
        assertEquals(20, result.split("【知识片段", -1).length - 1);
        var reader = new CustomVectorRetriever(retrieval, kb, new org.ruoyi.common.chat.domain.vo.chat.ChatModelVo());
        assertThrows(UnsupportedOperationException.class, () -> reader.addDocuments(List.of()).block());
    }
}
