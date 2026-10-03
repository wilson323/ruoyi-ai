package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.ipd.mapper.ProjectKnowledgeFragmentMapper;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库向量失败不能变成空结果；向量不可用时正文仍然带出处命中。
 */
@Tag("dev")
class ProjectKnowledgeVectorSearchTest {

    private static final long PROJECT_ID = 9140005L;

    @Test
    @DisplayName("向量检索抛错时结果里有失败标记，正文仍带出处和原句")
    void vectorFailureStaysVisibleAndTextStillHits() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(any(QueryVectorBo.class), any()))
            .thenThrow(new IllegalStateException("连接 127.0.0.1:28080 被拒绝"));
        ProjectKnowledgeRetriever retriever = retriever(gateAllowing(), retrieval, scope());

        RetrievalContext context = retriever.retrieve(PROJECT_ID, null, "海康威视");

        assertThat(context.block()).contains(ProjectKnowledgeVectorSearch.FAILURE_MARK);
        assertThat(context.block()).contains("知识库向量检索不可用").doesNotContain("28080");
        assertThat(context.block()).contains("海康威视.md");
        assertThat(context.block()).contains("/kb/competitors/海康威视.md");
        assertThat(context.block()).contains("42.13");
        assertThat(context.hits()).isGreaterThan(0);
        assertThat(context.block()).doesNotContain("117.53");
    }

    @Test
    @DisplayName("门禁拒绝时不调用向量检索，失败可见，正文仍命中")
    void gateDenialIsNotAnEmptyMiss() {
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        doThrow(new ServiceException("无权访问该知识库 kid=2097275993856180226"))
            .when(gate).checkRetrievalAccess(anyLong(), any());
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        ProjectKnowledgeRetriever retriever = retriever(gate, retrieval, scope());

        RetrievalContext context = retriever.retrieve(PROJECT_ID, null, "海康威视");

        verify(retrieval, never()).retrieve(any(QueryVectorBo.class), any());
        assertThat(context.block()).contains(ProjectKnowledgeVectorSearch.FAILURE_MARK);
        assertThat(context.block()).contains("无权访问该知识库");
        assertThat(context.block()).contains("海康威视.md");
        assertThat(context.block()).contains("/kb/competitors/海康威视.md");
        assertThat(context.hits()).isGreaterThan(0);
    }

    @Test
    @DisplayName("向量命中带知识库向量标记和出处，与正文命中不是同一段")
    void vectorHitCarriesSourceDistinctFromText() {
        KnowledgeRetrievalVo hit = KnowledgeRetrievalVo.builder()
            .content("研发投入 | 42.13 亿元 (2024，研发费用率 12.83%)")
            .sourceName("海康威视.md")
            .docId("hik-doc")
            .id("77")
            .build();
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(any(QueryVectorBo.class), any())).thenReturn(List.of(hit));
        ProjectKnowledgeRetriever retriever = retriever(gateAllowing(), retrieval, scope());

        RetrievalContext context = retriever.retrieve(PROJECT_ID, null, "海康威视");

        assertThat(context.block()).contains(ProjectKnowledgeVectorSearch.HIT_MARK);
        assertThat(context.block()).contains("海康威视.md");
        assertThat(context.block()).contains("hik-doc");
        assertThat(context.block()).contains("42.13");
        assertThat(context.block()).contains("【产品知识片段");
        assertThat(context.hits()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("运行已有人员时向量检索不报未登录，并把该人员传给门禁和检索")
    void runPersonIsNotTreatedAsLoggedOut() {
        long personId = 900103L;
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        KnowledgeRetrievalVo hit = KnowledgeRetrievalVo.builder()
            .content("研发投入 | 42.13 亿元 (2024，研发费用率 12.83%)")
            .sourceName("海康威视.md")
            .docId("hik-doc")
            .id("77")
            .build();
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(any(QueryVectorBo.class), eq(personId))).thenReturn(List.of(hit));
        ProjectKnowledgeRetriever retriever = retriever(gate, retrieval, scope());

        RetrievalContext context = retriever.retrieve(personId, PROJECT_ID, null, "海康威视");

        verify(gate, never()).checkRetrievalAccess(anyLong(), any());
        verify(gate, never()).checkRetrievalAccess(anyLong());
        verify(retrieval).retrieve(any(QueryVectorBo.class), eq(personId));
        assertThat(context.block()).doesNotContain("未登录或会话已失效");
        assertThat(context.block()).doesNotContain("无权访问该知识库");
        assertThat(context.block()).contains(ProjectKnowledgeVectorSearch.HIT_MARK);
        assertThat(context.block()).contains("42.13");
    }

    @Test
    @DisplayName("正文范围已允许的同一个库，向量不再因归属或分享报无权访问")
    void textScopeAllowsVectorForTheSameLibrary() {
        long personId = 900103L;
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        doThrow(new ServiceException("无权访问该知识库 kid=2097275993856180226"))
            .when(gate).checkRetrievalAccess(anyLong(), eq(personId));
        KnowledgeRetrievalVo hit = KnowledgeRetrievalVo.builder()
            .content("研发投入 | 42.13 亿元 (2024，研发费用率 12.83%)")
            .sourceName("海康威视.md")
            .docId("hik-doc")
            .id("77")
            .build();
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(any(QueryVectorBo.class), eq(personId))).thenReturn(List.of(hit));
        ProjectKnowledgeRetriever retriever = retriever(gate, retrieval, scope());

        RetrievalContext context = retriever.retrieve(personId, PROJECT_ID, null, "海康威视");

        verify(retrieval).retrieve(any(QueryVectorBo.class), eq(personId));
        assertThat(context.block()).doesNotContain("无权访问该知识库");
        assertThat(context.block()).contains(ProjectKnowledgeVectorSearch.HIT_MARK);
        assertThat(context.block()).contains("海康威视.md");
        assertThat(context.block()).contains("42.13");
    }

    @Test
    @DisplayName("没有身份时门禁拒绝且错误可见，不会变成空结果")
    void missingPersonStaysAVisibleDenial() {
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        doThrow(new ServiceException("未登录或会话已失效，无权访问该知识库 kid=2097275993856180226"))
            .when(gate).checkRetrievalAccess(anyLong(), isNull());
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        ProjectKnowledgeRetriever retriever = retriever(gate, retrieval, scope());

        RetrievalContext context = retriever.retrieve(PROJECT_ID, null, "海康威视");

        verify(retrieval, never()).retrieve(any(QueryVectorBo.class), any());
        assertThat(context.block()).contains(ProjectKnowledgeVectorSearch.FAILURE_MARK);
        assertThat(context.block()).contains("未登录或会话已失效");
        assertThat(context.block()).contains("海康威视.md");
        assertThat(context.block()).contains("42.13");
        assertThat(context.hits()).isGreaterThan(0);
    }

    @Test
    void missingVectorSourceIsVisibleButNotCitationEvidence() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(any(QueryVectorBo.class), any())).thenReturn(List.of(
            KnowledgeRetrievalVo.builder().content("无出处数字118.69").docId("orphan")
                .sourceName("未知来源").build()));
        ProjectKnowledgeVectorSearch search = new ProjectKnowledgeVectorSearch(gateAllowing(), retrieval,
            project -> List.of(scope()), (knowledgeId, docId, fragmentKey) -> null);
        RetrievalContext vector = search.search(PROJECT_ID, 900101L, "研发");
        assertThat(vector.hits()).isZero();
        assertThat(vector.block()).contains("原始出处缺失");
        assertThat(vector.citationText()).isEmpty();
        assertThat(ProjectKnowledgeSearchTool.retrievalStatus(vector)).isEqualTo("FAILED");
        RetrievalContext withText = retriever(gateAllowing(), retrieval, scope())
            .retrieve(900101L, PROJECT_ID, null, "海康威视");
        assertThat(ProjectKnowledgeSearchTool.retrievalStatus(withText)).isEqualTo("PARTIAL");
        assertThat(withText.citationText()).contains("海康威视.md", "42.13").doesNotContain("118.69", "出处缺失");
    }

    @Test
    void sourcedAndUnsourcedVectorsKeepOnlySourcedCitation() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(any(QueryVectorBo.class), any())).thenReturn(List.of(
            KnowledgeRetrievalVo.builder().content("118.69").docId("orphan").build(),
            KnowledgeRetrievalVo.builder().content("12.83%").docId("known").id("55")
                .sourceName("正式资料.md").build()));
        RetrievalContext result = new ProjectKnowledgeVectorSearch(gateAllowing(), retrieval,
            project -> List.of(scope()), (knowledgeId, docId, fragmentKey) -> {
                if (!"known".equals(docId)) {
                    return null;
                }
                ProjectKnowledgeFragmentHit live = new ProjectKnowledgeFragmentHit();
                live.setFragmentId(55L);
                live.setKnowledgeId(knowledgeId);
                live.setDocId(docId);
                live.setAttachName("正式资料.md");
                return live;
            }).search(PROJECT_ID, 900101L, "研发");
        assertThat(result.hits()).isEqualTo(1);
        assertThat(ProjectKnowledgeSearchTool.retrievalStatus(result)).isEqualTo("PARTIAL");
        assertThat(result.citationText()).contains("正式资料.md", "12.83%", "fragmentId=55")
            .doesNotContain("118.69", "出处缺失");
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.documentId()).isEqualTo("known");
            assertThat(source.fragmentId()).isEqualTo("55");
            assertThat(source.reviewStatus()).isEqualTo("NOT_PROJECT_DOCUMENT");
            assertThat(source.sourceName()).isEqualTo("正式资料.md");
        });
    }

    @Test
    void vectorLabelWithoutLiveAttachIsNotASource() {
        long knowledgeId = scope().getKnowledgeId();
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(any(QueryVectorBo.class), any())).thenReturn(List.of(
            KnowledgeRetrievalVo.builder().content("抽样118.69").docId(String.valueOf(knowledgeId))
                .sourceName(String.valueOf(knowledgeId)).id("catalog-fid").build(),
            KnowledgeRetrievalVo.builder().content("已删除附件42.13").docId("gone-doc")
                .sourceName("海康威视.md").build()));
        RetrievalContext result = new ProjectKnowledgeVectorSearch(gateAllowing(), retrieval,
            project -> List.of(scope()), (id, docId, fragmentKey) -> null)
            .search(PROJECT_ID, 900101L, "研发");
        assertThat(result.hits()).isZero();
        assertThat(result.citationText()).isEmpty();
        assertThat(result.sources()).isEmpty();
        assertThat(result.block()).contains("原始出处缺失或已失效")
            .doesNotContain("抽样118.69", "已删除附件42.13", "海康威视.md");
        assertThat(ProjectKnowledgeSearchTool.retrievalStatus(result)).isEqualTo("FAILED");
    }

    @Test
    void missingFragmentKeyCannotBorrowAnotherLiveFragmentInTheDocument() {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.retrieve(any(QueryVectorBo.class), any())).thenReturn(List.of(
            KnowledgeRetrievalVo.builder().content("旧片段118.69").docId("known").build()));
        ProjectKnowledgeVectorSearch.LiveIdentityLookup identities = mock(ProjectKnowledgeVectorSearch.LiveIdentityLookup.class);
        RetrievalContext result = new ProjectKnowledgeVectorSearch(gateAllowing(), retrieval,
            project -> List.of(scope()), identities).search(PROJECT_ID, 900101L, "研发");
        org.mockito.Mockito.verifyNoInteractions(identities);
        assertThat(result.hits()).isZero();
        assertThat(result.sources()).isEmpty();
        assertThat(result.citationText()).isEmpty();
        assertThat(result.block()).contains("原始出处缺失或已失效").doesNotContain("118.69");
    }

    private static ProjectKnowledgeRetriever retriever(KnowledgeAccessGate gate,
                                                       KnowledgeRetrievalService retrieval,
                                                       ProjectKnowledgeVectorSearch.Scope scope) {
        ProjectKnowledgeFragmentMapper mapper = mock(ProjectKnowledgeFragmentMapper.class);
        when(mapper.listInScope(anyLong(), anyLong())).thenReturn(List.of(scope));
        when(mapper.search(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(List.of(textHit()));
        when(mapper.findLiveFragment(anyLong(), nullable(String.class), nullable(String.class)))
            .thenAnswer(invocation -> {
                String docId = invocation.getArgument(1);
                if (!"hik-doc".equals(docId)) {
                    return null;
                }
                ProjectKnowledgeFragmentHit live = new ProjectKnowledgeFragmentHit();
                live.setFragmentId(77L);
                live.setKnowledgeId(invocation.getArgument(0));
                live.setDocId(docId);
                live.setAttachName("海康威视.md");
                return live;
            });
        ProjectKnowledgeVectorSearch knowledgeSearch = new ProjectKnowledgeVectorSearch(
            gate, retrieval, projectId -> mapper.listInScope(0L, projectId), mapper::findLiveFragment);
        return new ProjectKnowledgeRetriever(
            (projectId, docType, query) -> new RetrievalContext(0, 0, ""),
            new ProjectKnowledgeFragmentTextSearch(mapper),
            knowledgeSearch::search);
    }

    private static KnowledgeAccessGate gateAllowing() {
        return mock(KnowledgeAccessGate.class);
    }

    private static ProjectKnowledgeVectorSearch.Scope scope() {
        ProjectKnowledgeVectorSearch.Scope scope = new ProjectKnowledgeVectorSearch.Scope();
        scope.setKnowledgeId(2097275993856180226L);
        scope.setEmbeddingModel("");
        scope.setVectorModel("");
        return scope;
    }

    private static ProjectKnowledgeFragmentHit textHit() {
        ProjectKnowledgeFragmentHit hit = new ProjectKnowledgeFragmentHit();
        hit.setFragmentId(1L);
        hit.setKnowledgeId(100L);
        hit.setDocId("hik-doc");
        hit.setAttachName("海康威视.md");
        hit.setContent("研发投入 | 42.13 亿元 (2024，研发费用率 12.83%)");
        hit.setSourceRemark("/kb/competitors/海康威视.md");
        hit.setEmbeddingModel("qwen3-embedding:0.6b");
        return hit;
    }
}
