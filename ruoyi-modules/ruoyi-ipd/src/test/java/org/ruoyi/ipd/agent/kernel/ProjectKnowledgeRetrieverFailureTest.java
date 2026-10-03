package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("dev")
class ProjectKnowledgeRetrieverFailureTest {
    @Test
    void documentFailurePreservesTextAndReportsPartial() {
        ProjectKnowledgeFragmentTextSearch text = mock(ProjectKnowledgeFragmentTextSearch.class);
        when(text.search(1L, "query")).thenReturn(new RetrievalContext(1, 8, "原始正文资料"));
        ProjectKnowledgeRetriever retriever = new ProjectKnowledgeRetriever(
            (project, type, query) -> { throw new IllegalStateException("private endpoint"); }, text);
        RetrievalContext result = retriever.retrieve(2L, 1L, null, "query");
        assertThat(result.block()).contains("原始正文资料", "已审核文档向量检索失败").doesNotContain("private endpoint");
        assertThat(ProjectKnowledgeSearchTool.retrievalStatus(result)).isEqualTo("PARTIAL");
        assertThat(result.citationText()).isEqualTo("原始正文资料").doesNotContain("失败");
    }

    @Test
    void allSourcesFailedIsNotNoHit() {
        ProjectKnowledgeFragmentTextSearch text = mock(ProjectKnowledgeFragmentTextSearch.class);
        when(text.search(1L, "query")).thenThrow(new IllegalStateException("db"));
        ProjectKnowledgeRetriever retriever = new ProjectKnowledgeRetriever(
            (project, type, query) -> { throw new IllegalStateException("embed"); }, text,
            (project, person, query) -> { throw new IllegalStateException("vector"); });
        assertThat(ProjectKnowledgeSearchTool.retrievalStatus(retriever.retrieve(2L, 1L, null, "query")))
            .isEqualTo("FAILED");
    }
    @Test
    void vectorLinkageFailureDoesNotExposePrivateEndpointOrCredentials() {
        ProjectKnowledgeFragmentTextSearch text = mock(ProjectKnowledgeFragmentTextSearch.class);
        when(text.search(1L, "query")).thenReturn(new RetrievalContext(0, 0, ""));
        ProjectKnowledgeRetriever retriever = new ProjectKnowledgeRetriever(
            (project, type, query) -> new RetrievalContext(0, 0, ""), text,
            (project, person, query) -> { throw new NoClassDefFoundError("private.invalid?token=secret"); });
        RetrievalContext result = retriever.retrieve(2L, 1L, null, "query");
        assertThat(result.block()).contains("知识库向量检索不可用")
            .doesNotContain("private.invalid", "token", "secret");
        assertThat(ProjectKnowledgeSearchTool.retrievalStatus(result)).isEqualTo("FAILED");
    }
}
