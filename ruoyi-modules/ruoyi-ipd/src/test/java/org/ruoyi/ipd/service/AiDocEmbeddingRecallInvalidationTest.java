package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocEmbedding;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiDocEmbeddingMapper;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalStatus;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤销审核 / 删除文档后，<b>失效向量片段不得仍被召回</b>的反例（对应 AgentScope 审计 A27）。
 *
 * <p>{@code ai_doc_embeddings} 是一张<b>只增不减的快照表</b>：文档被驳回、归档、软删时
 * 旧切片一行都不会清。因此「召回结论是否可信」完全取决于两件事：
 * <ol>
 *   <li>每次召回都以 {@code ai_documents} 的<b>当前</b>状态为准重建「已审核 id 集合」；</li>
 *   <li>切片查询必须同时钉住 {@code project_id}、{@code embed_model}（向量空间一致性锚）
 *       和 {@code doc_id IN 已审核集合}——少任何一条，失效片段就会重新进入模型提示词。</li>
 * </ol>
 *
 * <p>既有 {@code AiDocEmbeddingServiceTest} 已经覆盖了「残留向量不出源」与「归档后不发切片查询」
 * 两条行为。本类补的是它们没钉住的三点：<b>切片查询自身的三段谓词</b>（此前只对文档查询断言过
 * project/status）、<b>同项目内部分撤权时的逐条甄别</b>、以及<b>撤权后重新审核能否恢复召回</b>
 * 与<b>索引不完整必须显式失败而非伪装成无命中</b>。
 */
@Tag("dev")
@DisplayName("失效向量召回反例：撤权后不得召回、切片查询三段谓词、索引不完整必须显式失败")
class AiDocEmbeddingRecallInvalidationTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
            new MapperBuilderAssistant(new MybatisConfiguration(), "ai-doc-embedding-invalidation-test");
        TableInfoHelper.initTableInfo(assistant, AiDocument.class);
        TableInfoHelper.initTableInfo(assistant, AiDocEmbedding.class);
    }

    private static final String EMBED_CFG =
        "{\"embedEndpoint\":\"http://embed.example.com/v1\",\"embedModel\":\"emb-1\"}";
    private static final Long PROJECT = 9L;

    private AiDocEmbeddingMapper embeddingMapper;
    private AiDocumentMapper documentMapper;
    private AiDocEmbeddingService service;

    @BeforeEach
    void setUp() {
        embeddingMapper = mock(AiDocEmbeddingMapper.class);
        documentMapper = mock(AiDocumentMapper.class);
        AiModelConfigService modelConfigService = mock(AiModelConfigService.class);
        AiGateway aiGateway = mock(AiGateway.class);
        service = new AiDocEmbeddingService(embeddingMapper, documentMapper, modelConfigService, aiGateway);
        service.completeEmbedOnCallerForTest();
        AiModelConfig cfg = AiModelConfig.builder().id(1L).provider("openai")
            .endpointUrl("https://chat.example.com/v1").apiKeyEncrypted("cipher")
            .modelName("gpt-x").configJson(EMBED_CFG).isActive(true).build();
        when(modelConfigService.currentEnabled()).thenReturn(cfg);
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn("sk-embed-key");
        when(aiGateway.embed(any(AiTestConfig.class), anyList())).thenReturn(List.of(new float[]{1, 0}));
    }

    /** 仍处于已审核状态的文档（召回时由 ai_documents 当前行决定）。 */
    private static AiDocument approved(Long id) {
        return AiDocument.builder().id(id).projectId(PROJECT).build();
    }

    private static AiDocEmbedding chunk(Long docId, String title, String text) {
        return AiDocEmbedding.builder().docId(docId).projectId(PROJECT).docType("PRD").title(title)
            .chunkSeq(0).chunkText(text).embedModel("emb-1").vectorJson("[1.0,0.0]").build();
    }

    // ------------------------------------------------------------------
    // 执行点：切片查询必须同时钉住 project_id / embed_model / doc_id∈已审核集合
    // ------------------------------------------------------------------

    @Test
    @DisplayName("切片查询必须同时带 project_id、embed_model 与 doc_id IN 已审核集合——缺一条即失效片段复活")
    void embeddingQueryCarriesProjectModelAndApprovedIdSet() {
        when(documentMapper.selectList(any())).thenReturn(List.of(approved(1L)));
        when(embeddingMapper.selectList(any())).thenReturn(List.of(chunk(1L, "现行需求", "现行正文")));

        service.retrieveContextStrict(PROJECT, null, "查询");

        verify(embeddingMapper).selectList(org.mockito.ArgumentMatchers.argThat(wrapper -> {
            if (!(wrapper instanceof AbstractWrapper<?, ?, ?> actual)) {
                return false;
            }
            String sql = actual.getSqlSegment();
            return sql.contains("project_id")
                && sql.contains("embed_model")
                && sql.contains("doc_id IN")
                && !actual.getParamNameValuePairs().isEmpty();
        }));
    }

    @Test
    @DisplayName("指定 docType 时切片查询必须再钉一层 doc_type——否则同类文档互相越界召回")
    void embeddingQueryAddsDocTypePredicateWhenFiltered() {
        when(documentMapper.selectList(any())).thenReturn(List.of(approved(1L)));
        when(embeddingMapper.selectList(any())).thenReturn(List.of(chunk(1L, "现行需求", "现行正文")));

        service.retrieveContextStrict(PROJECT, "PRD", "查询");

        verify(embeddingMapper).selectList(org.mockito.ArgumentMatchers.argThat(wrapper ->
            wrapper instanceof AbstractWrapper<?, ?, ?> actual
                && actual.getSqlSegment().contains("doc_type")));
    }

    // ------------------------------------------------------------------
    // 行为：同项目内部分撤权 —— 逐条甄别，撤掉的不召回，留着的照常召回
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同项目内部分撤权：被撤文档的残留片段不出源，仍审核的那份照常命中")
    void partiallyRevokedProjectOnlyRecallsStillApprovedDocument() {
        // ai_documents 当前行只剩 id=1 是 REVIEWED；id=99 已被驳回/归档/软删
        when(documentMapper.selectList(any())).thenReturn(List.of(approved(1L)));
        // 但快照表里 99 的切片还在，且向量同样相似——必须靠 doc_id∈已审核集合 挡掉
        when(embeddingMapper.selectList(any()))
            .thenReturn(List.of(chunk(99L, "已撤文档", "撤权后不得出现的正文"),
                chunk(1L, "现行需求", "现行正文")));

        var context = service.retrieveContextStrict(PROJECT, null, "查询");

        assertThat(context.status()).isEqualTo(RetrievalStatus.SUCCESS);
        assertThat(context.hits()).isEqualTo(1);
        assertThat(context.sources()).extracting(AiDocEmbeddingService.CitationSource::documentId)
            .containsExactly("1");
        assertThat(context.block()).contains("现行正文");
        assertThat(context.citationText()).doesNotContain("撤权后不得出现的正文");
        assertThat(context.issueCounts()).as("残留片段被跳过不算索引损坏，不得报 INDEX_NOT_READY").isEmpty();
    }

    @Test
    @DisplayName("整个项目的文档都被撤权后：连切片查询都不发起，直接空结果")
    void fullyRevokedProjectNeverQueriesEmbeddings() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(embeddingMapper.selectList(any()))
            .thenReturn(List.of(chunk(99L, "已撤文档", "撤权后不得出现的正文")));

        var context = service.retrieveContext(PROJECT, null, "查询");

        assertThat(context.hits()).isZero();
        assertThat(context.block()).isEmpty();
        assertThat(context.sources()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(embeddingMapper);
    }

    // ------------------------------------------------------------------
    // 行为：撤权 → 重新审核，召回必须随之恢复（证明空结果来自当前状态，不是索引被删）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("撤权期间读不到、重新审核后又能读到——召回结论跟随当前审核状态而非历史")
    void recallFollowsCurrentReviewStateBothWays() {
        when(documentMapper.selectList(any()))
            .thenReturn(List.of(), List.of(approved(1L)));
        when(embeddingMapper.selectList(any())).thenReturn(List.of(chunk(1L, "现行需求", "现行正文")));

        var revoked = service.retrieveContext(PROJECT, null, "查询");
        assertThat(revoked.hits()).as("撤权期间不得召回").isZero();

        var reApproved = service.retrieveContext(PROJECT, null, "查询");
        assertThat(reApproved.hits()).as("重新审核通过后必须恢复召回").isEqualTo(1);
        assertThat(reApproved.block()).contains("现行正文");
    }

    // ------------------------------------------------------------------
    // 行为：索引不完整必须显式失败，不得伪装成「无命中」继续生成
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已审核文档一条索引都没有：严格入口抛错、生产入口转业务错误，绝不降级成无命中")
    void missingIndexForApprovedDocumentNeverBecomesNoHit() {
        when(documentMapper.selectList(any())).thenReturn(List.of(approved(1L)));
        when(embeddingMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.retrieveContextStrict(PROJECT, null, "查询"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("索引不可用或不完整");

        assertThatThrownBy(() -> service.retrieveContext(PROJECT, null, "查询"))
            .as("生产入口必须把「索引没建好」说成失败，否则生成会拿着空上下文当「查过了没有」")
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("检索");
    }

    @Test
    @DisplayName("残留片段向量维度与当前模型不符：不得冒充命中，也不得静默丢弃为无命中")
    void dimensionMismatchedResidualChunkCannotBecomeACitation() {
        when(documentMapper.selectList(any())).thenReturn(List.of(approved(1L)));
        var wrongSpace = AiDocEmbedding.builder().docId(1L).projectId(PROJECT).docType("PRD")
            .title("旧模型切片").chunkSeq(0).chunkText("旧向量空间正文")
            .embedModel("emb-1").vectorJson("[1.0,0.0,0.0]").build();
        when(embeddingMapper.selectList(any())).thenReturn(List.of(wrongSpace));

        assertThatThrownBy(() -> service.retrieveContextStrict(PROJECT, null, "查询"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("索引不可用或不完整");
    }
}
