package org.ruoyi.ipd.agent.kernel;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.mapper.ProjectKnowledgeFragmentMapper;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 产品知识库正文检索：配置已填写仍要命中，查询失败不能变成空结果。
 */
@Tag("dev")
class ProjectKnowledgeFragmentTextSearchTest {

    @Test
    void missingAuthorityIdsAreNotCitableAndKnowledgeNeverBorrowsReviewStatus() {
        var row = hit("资料.md", 2, "", "竞品资料正文", "原文件");
        var known = ProjectKnowledgeFragmentTextSearch.format(List.of(row), List.of("竞品"));
        assertThat(known.sources()).singleElement().satisfies(source -> {
            assertThat(source.sourceType()).isEqualTo("KNOWLEDGE_FRAGMENT");
            assertThat(source.reviewStatus()).isEqualTo("NOT_PROJECT_DOCUMENT");
            assertThat(source.knowledgeId()).isEqualTo("100");
            assertThat(source.fragmentId()).isEqualTo("101");
        });
        row.setDocId(null);
        var missing = ProjectKnowledgeFragmentTextSearch.format(List.of(row), List.of("竞品"));
        assertThat(missing.hits()).isZero();
        assertThat(missing.sources()).isEmpty();
        assertThat(missing.citationText()).isEmpty();
        assertThat(missing.block()).contains("来源身份缺失");
    }

    private static final long PROJECT_ID = 9140005L;

    @Test
    @DisplayName("嵌入模型已填写的行仍要命中，海康威视和捷顺科技分文件带出处")
    void configuredEmbeddingStillHitsTitleOrBodyWithSource() {
        ProjectKnowledgeFragmentMapper mapper = mock(ProjectKnowledgeFragmentMapper.class);
        when(mapper.search(anyLong(), anyLong(), anyList(), anyInt())).thenReturn(List.of(
            hit("海康威视.md", 0, "qwen3-embedding:0.6b", "研发投入 42.13 亿元。海康威视。", "/kb/海康威视.md"),
            hit("捷顺科技.md", 0, "qwen3-embedding:0.6b", "捷顺科技。", "/kb/捷顺科技.md"),
            hit("02-DOMESTIC.md", 0, "weaviate", "海康威视研发 117.53 亿。", "/kb/02-DOMESTIC.md"),
            hit("已嵌入.md", 0, "some-embed-model", "海康威视向量行不该出现", "/kb/embedded.md")));
        ProjectKnowledgeRetriever retriever = new ProjectKnowledgeRetriever(
            (projectId, docType, query) -> new RetrievalContext(0, 0, ""),
            new ProjectKnowledgeFragmentTextSearch(mapper));

        RetrievalContext hik = retriever.retrieve(PROJECT_ID, null, "海康威视");
        RetrievalContext jieshun = retriever.retrieve(PROJECT_ID, null, "捷顺科技");

        assertThat(hik.hits()).isGreaterThan(0);
        assertThat(hik.block()).contains("海康威视.md");
        assertThat(hik.block()).contains("/kb/海康威视.md");
        assertThat(hik.block()).contains("42.13");
        assertThat(hik.block()).contains("向量行不该出现");
        assertThat(hik.block()).contains("/kb/embedded.md");
        assertThat(blockOf(hik.block(), "海康威视.md")).doesNotContain("117.53");
        assertThat(blockOf(hik.block(), "02-DOMESTIC.md")).contains("117.53");
        assertThat(jieshun.block()).contains("捷顺科技.md");
        assertThat(jieshun.block()).contains("/kb/捷顺科技.md");
    }

    @Test
    @DisplayName("整句问题抽出竞品名，不把整句做成一条 LIKE")
    void questionKeywordsHitCompetitorName() {
        assertThat(ProjectKnowledgeFragmentTextSearch.keywords("请对比一下海康威视单卡收入是多少"))
            .contains("海康威视")
            .doesNotContain("请对比一下海康威视单卡收入是多少");
        assertThat(ProjectKnowledgeFragmentTextSearch.keywords("捷顺科技 2024"))
            .contains("捷顺科技");
    }

    @Test
    @DisplayName("查询异常不是空结果")
    void queryFailureIsNotEmptyResult() {
        ProjectKnowledgeFragmentMapper mapper = mock(ProjectKnowledgeFragmentMapper.class);
        when(mapper.search(anyLong(), anyLong(), anyList(), anyInt()))
            .thenThrow(new RuntimeException("db"));
        ProjectKnowledgeFragmentTextSearch search = new ProjectKnowledgeFragmentTextSearch(mapper);

        assertThatThrownBy(() -> search.search(PROJECT_ID, "海康威视"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("产品知识库正文检索失败");
    }

    @Test
    @DisplayName("正文 SQL 不再因嵌入模型或向量库配置把行藏掉，权限范围仍在")
    void sqlDoesNotHideRowsWhenEmbeddingConfigured() throws Exception {
        Method method = ProjectKnowledgeFragmentMapper.class.getMethod(
            "search", long.class, long.class, List.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql).doesNotContain("embedding_model IS NULL");
        assertThat(sql).doesNotContain("vector_model IS NULL");
        assertThat(sql).doesNotContain("embedding_model = ''");
        assertThat(sql).contains("ki.tenant_id = #{tenantId}");
        assertThat(sql).contains("ki.scope_type = 'PROJECT'");
        assertThat(sql).contains("ki.project_id = #{projectId}");
        assertThat(sql).contains("ki.user_id = 0");
        assertThat(sql).contains("ki.project_id IS NULL");
        assertThat(sql).doesNotContain("share");
    }

    @Test
    @DisplayName("向量身份必须连到当前附件，片段键对不上不能改挂")
    void liveFragmentSqlRequiresCurrentAttach() throws Exception {
        Method method = ProjectKnowledgeFragmentMapper.class.getMethod(
            "findLiveFragment", long.class, String.class, String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());
        assertThat(sql).contains("JOIN knowledge_attach");
        assertThat(sql).contains("kf.doc_id = #{docId}");
        assertThat(sql).contains("kf.fid = #{fragmentKey}");
        assertThat(sql).contains("CHAR_LENGTH(TRIM(ka.name)) > 0");
        assertThat(sql).doesNotContain("catalog-sample");
    }

    private static String blockOf(String text, String attachName) {
        int start = text.indexOf("｜" + attachName + "｜");
        assertThat(start).isGreaterThanOrEqualTo(0);
        int next = text.indexOf("【产品知识片段 ", start + 1);
        return next < 0 ? text.substring(start) : text.substring(start, next);
    }

    private static ProjectKnowledgeFragmentHit hit(String name, int status, String embeddingModel,
                                                   String content, String remark) {
        ProjectKnowledgeFragmentHit hit = new ProjectKnowledgeFragmentHit();
        hit.setFragmentId(101L);
        hit.setKnowledgeId(100L);
        hit.setDocId("fixture-doc-" + name);
        hit.setAttachName(name);
        hit.setAttachStatus(status);
        hit.setEmbeddingModel(embeddingModel);
        hit.setContent(content);
        hit.setSourceRemark(remark);
        return hit;
    }
}
