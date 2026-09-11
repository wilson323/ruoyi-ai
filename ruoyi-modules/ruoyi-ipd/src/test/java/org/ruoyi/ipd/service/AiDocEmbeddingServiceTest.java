package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.ruoyi.ipd.domain.AiDocEmbedding;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiDocEmbeddingMapper;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AI-STRAT-1 RAG 资料库验收：切片/余弦/配置解析/降级语义/检索 top-K/先删后插。
 * 红线对齐 BR-AI-04：切片原文只入库与 prompt，不入审计/日志。
 */
@Tag("dev")
@DisplayName("AI-STRAT-1：文档向量化与检索")
class AiDocEmbeddingServiceTest {

    private static final String EMBED_CFG =
        "{\"embedEndpoint\":\"http://embed.example.com/v1\",\"embedModel\":\"emb-1\"}";

    private AiDocEmbeddingMapper embeddingMapper;
    private AiModelConfigService modelConfigService;
    private AiGateway aiGateway;
    private AiDocEmbeddingService service;

    @BeforeEach
    void setUp() {
        embeddingMapper = mock(AiDocEmbeddingMapper.class);
        modelConfigService = mock(AiModelConfigService.class);
        aiGateway = mock(AiGateway.class);
        service = new AiDocEmbeddingService(embeddingMapper, modelConfigService, aiGateway);
    }

    private void stubEmbedEnabled(String configJson) {
        AiModelConfig cfg = AiModelConfig.builder().id(1L).provider("openai")
            .endpointUrl("https://chat.example.com/v1").apiKeyEncrypted("cipher")
            .modelName("gpt-x").configJson(configJson).isActive(true).build();
        when(modelConfigService.currentEnabled()).thenReturn(cfg);
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn("sk-embed-key");
    }

    private static AiDocument doc(String content) {
        return AiDocument.builder().id(101L).projectId(9L).docType("PRD")
            .title("需求文档").content(content).build();
    }

    // ---- 切片 ----

    @Test
    @DisplayName("切片：固定 800 窗口无重叠，尾片独立保留；空白输入空列表")
    void splitChunksFixedWindow() {
        assertEquals(0, AiDocEmbeddingService.splitChunks(null).size());
        assertEquals(0, AiDocEmbeddingService.splitChunks("   ").size());
        assertEquals(1, AiDocEmbeddingService.splitChunks("短文").size());
        List<String> two = AiDocEmbeddingService.splitChunks("字".repeat(800) + "尾巴");
        assertEquals(2, two.size(), "尾片独立保留");
        assertEquals(800, two.get(0).length());
        assertEquals("尾巴", two.get(1));
    }

    // ---- 余弦 ----

    @Test
    @DisplayName("余弦：同向=1，正交=0，维度不一致/零向量=-1（不参与排序）")
    void cosineBasics() {
        assertEquals(1.0, AiDocEmbeddingService.cosine(new float[]{1, 0}, new float[]{2, 0}), 1e-9);
        assertEquals(0.0, AiDocEmbeddingService.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
        assertEquals(-1, AiDocEmbeddingService.cosine(new float[]{1, 0}, new float[]{1, 0, 0}));
        assertEquals(-1, AiDocEmbeddingService.cosine(new float[]{0, 0}, new float[]{1, 0}));
        assertEquals(-1, AiDocEmbeddingService.cosine(null, new float[]{1}));
    }

    // ---- 配置解析（RAG 开关） ----

    @Test
    @DisplayName("RAG 开关：embedEndpoint/embedModel 两键齐全才启用，缺任一返回 null")
    void resolveEmbedConfigRequiresBothKeys() {
        stubEmbedEnabled(EMBED_CFG);
        AiDocEmbeddingService.EmbedEndpoint cfg = service.resolveEmbedConfig();
        assertNotNull(cfg);
        assertEquals("http://embed.example.com/v1", cfg.endpoint());
        assertEquals("emb-1", cfg.embedModel());
        assertEquals("sk-embed-key", cfg.apiKey());

        stubEmbedEnabled("{\"embedEndpoint\":\"http://embed.example.com/v1\"}");
        assertNull(service.resolveEmbedConfig(), "缺 embedModel → RAG 关");
        stubEmbedEnabled("{\"embedModel\":\"emb-1\"}");
        assertNull(service.resolveEmbedConfig(), "缺 embedEndpoint → RAG 关");
        stubEmbedEnabled("{}");
        assertNull(service.resolveEmbedConfig(), "两键全缺 → RAG 关");
    }

    // ---- embedAsync 降级（不出队不抛错） ----

    @Test
    @DisplayName("embedAsync：RAG 未配置静默跳过（不提交任务不触 embed）")
    void embedAsyncSkipsWhenRagOff() {
        stubEmbedEnabled("{}");
        service.embedAsync(doc("正文"));
        verifyNoInteractions(aiGateway);
        verifyNoInteractions(embeddingMapper);
    }

    @Test
    @DisplayName("embedAsync：null/空白内容直接跳过；currentEnabled 抛错不外抛")
    void embedAsyncGuards() {
        stubEmbedEnabled(EMBED_CFG);
        service.embedAsync(null);
        service.embedAsync(doc("  "));
        when(modelConfigService.currentEnabled())
            .thenThrow(new IllegalStateException("no config"));
        assertDoesNotThrow(() -> service.embedAsync(doc("正文")));
        verifyNoInteractions(aiGateway);
    }

    // ---- embedSync：先删后插 / 失败降级 ----

    @Test
    @DisplayName("embedSync：gateway 失败返回 null → 不删旧片不插新片（保留现状）")
    void embedSyncFailureKeepsExisting() {
        when(aiGateway.embed(any(AiTestConfig.class), anyList())).thenReturn(null);
        service.embedSync(doc("正文内容"), new AiDocEmbeddingService.EmbedEndpoint(
            "http://embed.example.com/v1", "sk", "emb-1"));
        verify(embeddingMapper, never()).delete(any());
        verify(embeddingMapper, never()).insert(any(AiDocEmbedding.class));
    }

    @Test
    @DisplayName("embedSync：成功 → doc 级先删后插（同 embedModel），片序 chunkSeq 从 0 连续")
    void embedSyncDeleteThenInsert() {
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{1, 0}, new float[]{0, 1}));
        service.embedSync(doc("字".repeat(900)), new AiDocEmbeddingService.EmbedEndpoint(
            "http://embed.example.com/v1", "sk", "emb-1"));
        InOrder order = inOrder(embeddingMapper);
        order.verify(embeddingMapper).delete(any());
        var cap = org.mockito.ArgumentCaptor.forClass(AiDocEmbedding.class);
        verify(embeddingMapper, times(2)).insert(cap.capture());
        assertEquals(0, cap.getAllValues().get(0).getChunkSeq());
        assertEquals(1, cap.getAllValues().get(1).getChunkSeq());
        assertEquals("emb-1", cap.getAllValues().get(0).getEmbedModel());
        assertEquals("[1.0,0.0]", cap.getAllValues().get(0).getVectorJson());
    }

    // ---- retrieveContext ----

    @Test
    @DisplayName("检索：query 空白/RAG 关/embed 失败 → EMPTY（生成照常）")
    void retrieveContextDegradations() {
        stubEmbedEnabled(EMBED_CFG);
        AiDocEmbeddingService.RetrievalContext empty = service.retrieveContext(9L, "  ");
        assertEquals(AiDocEmbeddingService.RetrievalContext.EMPTY, empty);
        assertEquals(AiDocEmbeddingService.RetrievalContext.EMPTY, service.retrieveContext(9L, null));
        assertEquals(AiDocEmbeddingService.RetrievalContext.EMPTY, service.retrieveContext(9L, "查询"),
            "mock 默认 embed 返回 null → EMPTY（不抛）");

        stubEmbedEnabled("{}");
        AiDocEmbeddingService.RetrievalContext ctx = service.retrieveContext(9L, "查询");
        assertEquals(0, ctx.hits());
        assertEquals("", ctx.block());
    }

    @Test
    @DisplayName("检索：余弦 top-K 拼块（正交片不入选），块含来源标注；候选空 EMPTY")
    void retrieveContextTopK() {
        stubEmbedEnabled(EMBED_CFG);
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{1, 0}));

        AiDocEmbedding similar = AiDocEmbedding.builder().docId(1L).projectId(9L).docType("PRD")
            .title("旧需求").chunkSeq(0).chunkText("相似片段正文")
            .embedModel("emb-1").vectorJson("[1.0,0.0]").build();
        AiDocEmbedding orthogonal = AiDocEmbedding.builder().docId(2L).projectId(9L).docType("MRD")
            .title("无关").chunkSeq(0).chunkText("无关片段")
            .embedModel("emb-1").vectorJson("[0.0,1.0]").build();
        when(embeddingMapper.selectList(any())).thenReturn(List.of(similar, orthogonal));

        AiDocEmbeddingService.RetrievalContext ctx = service.retrieveContext(9L, "查询");
        assertEquals(1, ctx.hits(), "正交片（cos=0）不入选");
        assertTrue(ctx.block().contains("旧需求"), "块含来源标注（docType/title）");
        assertTrue(ctx.block().contains("相似片段正文"));
        assertTrue(ctx.chars() > 0);

        when(embeddingMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, service.retrieveContext(9L, "查询").hits(), "候选空 → EMPTY");
    }

    @Test
    @DisplayName("检索：候选异常（反序列化坏向量）不炸——cos=-1 过滤后仍可命中其他片")
    void retrieveContextBadVectorTolerated() {
        stubEmbedEnabled(EMBED_CFG);
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{1, 0}));
        AiDocEmbedding good = AiDocEmbedding.builder().docId(3L).projectId(9L).docType("PRD")
            .title("好片").chunkSeq(0).chunkText("正文").embedModel("emb-1").vectorJson("[1.0,0.0]").build();
        AiDocEmbedding bad = AiDocEmbedding.builder().docId(4L).projectId(9L).docType("PRD")
            .title("坏片").chunkSeq(0).chunkText("坏").embedModel("emb-1").vectorJson("not-json").build();
        when(embeddingMapper.selectList(any())).thenReturn(List.of(bad, good));
        assertEquals(1, service.retrieveContext(9L, "查询").hits());
    }

    // ---- composePrompt（AiGenerationService 静态拼装） ----

    @Test
    @DisplayName("composePrompt：EMPTY 原样；有块=块前+原文后；超预算裁块保原文")
    void composePromptRules() {
        assertEquals("需求", AiGenerationService.composePrompt("需求",
            AiDocEmbeddingService.RetrievalContext.EMPTY));
        assertEquals("需求", AiGenerationService.composePrompt("需求", null));

        String block = "【相关历史文档片段 1｜PRD｜t】\n正文";
        String joined = AiGenerationService.composePrompt("需求",
            new AiDocEmbeddingService.RetrievalContext(1, block.length(), block));
        assertTrue(joined.startsWith(block));
        assertTrue(joined.endsWith("需求"));
        assertTrue(joined.contains("以下为本次需求"));

        // 原文接近 MAX_PROMPT_LEN：块被裁到只留头几个字符；room<=0 时干脆不注入
        String fatPrompt = "需".repeat(29_995);
        String clipped = AiGenerationService.composePrompt(fatPrompt,
            new AiDocEmbeddingService.RetrievalContext(1, block.length(), block));
        assertTrue(clipped.endsWith(fatPrompt), "原文完整保底");
        assertTrue(clipped.length() <= AiGenerationService.MAX_PROMPT_LEN, "总长钳 MAX_PROMPT_LEN");
    }
}
