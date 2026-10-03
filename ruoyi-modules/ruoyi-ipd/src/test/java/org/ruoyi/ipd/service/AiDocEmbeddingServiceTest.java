package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.ruoyi.ipd.domain.AiDocEmbedding;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiDocEmbeddingMapper;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.ruoyi.ipd.service.ai.BuiltinEmbeddingModel;
import org.springframework.core.env.Environment;

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

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "ai-doc-embedding-test");
        TableInfoHelper.initTableInfo(assistant, AiDocument.class);
        TableInfoHelper.initTableInfo(assistant, AiDocEmbedding.class);
    }

    private static final String EMBED_CFG =
        "{\"embedEndpoint\":\"http://embed.example.com/v1\",\"embedModel\":\"emb-1\"}";

    private AiDocEmbeddingMapper embeddingMapper;
    private AiDocumentMapper documentMapper;
    private AiModelConfigService modelConfigService;
    private AiGateway aiGateway;
    private AiDocEmbeddingService service;

    @BeforeEach
    void setUp() {
        embeddingMapper = mock(AiDocEmbeddingMapper.class);
        documentMapper = mock(AiDocumentMapper.class);
        modelConfigService = mock(AiModelConfigService.class);
        aiGateway = mock(AiGateway.class);
        service = new AiDocEmbeddingService(embeddingMapper, documentMapper, modelConfigService, aiGateway);
        service.completeEmbedOnCallerForTest();
        when(documentMapper.selectVersionTenant(101L)).thenReturn("000000");
        when(documentMapper.lockVersion(101L)).thenReturn(doc("正文"));
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        service.setIndexTransactionManager(manager);
        when(embeddingMapper.insert(any(AiDocEmbedding.class))).thenReturn(1);
    }


    private static boolean hasProjectScope(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
        // MyBatis-Plus materializes lambda parameters when building the SQL segment.
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values().stream().anyMatch(v -> "9".equals(String.valueOf(v)));
    }

    private static boolean hasReviewedStatus(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().containsValue(AiDocumentService.STATUS_REVIEWED);
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
            .title("需求文档").content(content).status(AiDocumentService.STATUS_REVIEWED)
            .versionNo(1).contentSha256(AiDocumentService.sha256Hex(content)).build();
    }

    @Test
    void rebuildRejectsInvalidVectorsBeforeOpeningTransaction() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        service.setIndexTransactionManager(manager);
        stubEmbedEnabled("{}");
        AiDocument document = reviewed("正文");
        for (List<float[]> vectors : List.of(List.<float[]>of(), List.of(new float[]{1}),
            List.of(new float[BuiltinEmbeddingModel.DIMENSION]))) {
            when(aiGateway.embed(any(), anyList())).thenReturn(vectors);
            assertThrows(IllegalStateException.class, () -> service.rebuildIndex(document, () -> "000000"));
        }
        when(aiGateway.embed(any(), anyList())).thenReturn(java.util.Collections.singletonList(null));
        assertThrows(IllegalStateException.class, () -> service.rebuildIndex(document, () -> "000000"));
        float[] nonFinite = healthyVector(); nonFinite[3] = Float.NaN;
        when(aiGateway.embed(any(), anyList())).thenReturn(List.of(nonFinite));
        assertThrows(IllegalStateException.class, () -> service.rebuildIndex(document, () -> "000000"));
        verifyNoInteractions(manager);
        verify(embeddingMapper, never()).delete(any());
        verify(embeddingMapper, never()).insert(any(AiDocEmbedding.class));
    }

    @Test
    void rebuildGeneratesOutsideTransactionAndRollsBackOnChangedDocument() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var tx = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(tx);
        service.setIndexTransactionManager(manager);
        stubEmbedEnabled("{}");
        AiDocument original = reviewed("正文");
        AiDocument changed = reviewed("被修改");
        when(documentMapper.lockVersion(101L)).thenReturn(changed);
        when(aiGateway.embed(any(), anyList())).thenAnswer(inv -> {
            verifyNoInteractions(manager);
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(healthyVector());
        });
        assertThrows(IpdBusinessException.class, () -> service.rebuildIndex(original, () -> "000000"));
        verify(manager).rollback(tx);
        verifyNoInteractions(embeddingMapper);
    }

    @Test
    void rebuildLocksAndAtomicallyReplacesAllOldModelChunksWithoutUpdatingDocument() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var tx = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(tx);
        service.setIndexTransactionManager(manager);
        stubEmbedEnabled("{}");
        AiDocument original = reviewed("字".repeat(900));
        when(documentMapper.lockVersion(101L)).thenReturn(original);
        when(aiGateway.embed(any(), anyList())).thenReturn(List.of(healthyVector(), healthyVector()));
        when(embeddingMapper.insert(any(AiDocEmbedding.class))).thenReturn(1);
        assertEquals(2, service.rebuildIndex(original, () -> "000000"));
        InOrder order = inOrder(aiGateway, manager, documentMapper, embeddingMapper);
        order.verify(aiGateway).embed(any(), anyList());
        order.verify(manager).getTransaction(any());
        order.verify(documentMapper).lockVersion(101L);
        order.verify(embeddingMapper).delete(org.mockito.ArgumentMatchers.argThat(w -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) w;
            String sql = wrapper.getSqlSegment();
            return sql.contains("doc_id") && !sql.contains("embed_model");
        }));
        order.verify(embeddingMapper, times(2)).insert(any(AiDocEmbedding.class));
        order.verify(manager).commit(tx);
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void rebuildRollbackRestoresOldCacheAfterSecondInsertFailureWithRealTransactionBoundary() {
        List<String> cache = new java.util.ArrayList<>(List.of("old-display-model-cache"));
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            private List<String> before;
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) {
                before = List.copyOf(cache);
            }
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
                cache.clear(); cache.addAll(before);
            }
        };
        service.setIndexTransactionManager(manager);
        stubEmbedEnabled("{}");
        AiDocument document = reviewed("字".repeat(900));
        when(documentMapper.lockVersion(101L)).thenReturn(document);
        when(aiGateway.embed(any(), anyList())).thenAnswer(inv -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(healthyVector(), healthyVector());
        });
        when(embeddingMapper.delete(any())).thenAnswer(inv -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            cache.clear(); return 1;
        });
        when(embeddingMapper.insert(any(AiDocEmbedding.class))).thenAnswer(inv -> {
            if (!cache.isEmpty()) throw new IllegalStateException("second insert failure");
            cache.add("new-chunk"); return 1;
        });
        assertThrows(IllegalStateException.class, () -> service.rebuildIndex(document, () -> "000000"));
        assertEquals(List.of("old-display-model-cache"), cache);
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void rebuildFailsClosedForRevokedPermissionStatusVersionTenantAndConfigDrift() {
        stubEmbedEnabled("{}");
        when(aiGateway.embed(any(), anyList())).thenReturn(List.of(healthyVector()));
        AiDocument original = reviewed("正文");
        for (AiDocument changed : List.of(reviewed("正文").setStatus("REJECTED"),
            reviewed("正文").setVersionNo(2), reviewed("正文").setProjectId(77L))) {
            when(documentMapper.lockVersion(101L)).thenReturn(changed);
            assertThrows(IpdBusinessException.class, () -> service.rebuildIndex(original, () -> "000000"));
        }
        when(documentMapper.lockVersion(101L)).thenReturn(original);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IpdBusinessException.class, () -> service.rebuildIndex(original, () -> {
            if (calls.incrementAndGet() > 1) throw new IpdBusinessException(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
            return "000000";
        }));
        when(documentMapper.selectVersionTenant(101L)).thenReturn("other");
        assertThrows(IpdBusinessException.class, () -> service.rebuildIndex(original, () -> "000000"));
        when(documentMapper.selectVersionTenant(101L)).thenReturn("000000");
        when(aiGateway.embed(any(), anyList())).thenAnswer(inv -> {
            stubEmbedEnabled(EMBED_CFG); return List.of(healthyVector());
        });
        assertThrows(IpdBusinessException.class, () -> service.rebuildIndex(original, () -> "000000"));
        verify(embeddingMapper, never()).delete(any());
        verify(embeddingMapper, never()).insert(any(AiDocEmbedding.class));
    }

    @Test
    void originalAsyncPathCannotReplaceCacheWhenAuthoritativeVersionIsRejected() {
        stubEmbedEnabled("{}");
        when(aiGateway.embed(any(), anyList())).thenReturn(List.of(healthyVector()));
        when(documentMapper.lockVersion(101L)).thenReturn(reviewed("正文").setStatus("REJECTED"));
        assertDoesNotThrow(() -> service.embedAsync(reviewed("正文")));
        verify(documentMapper).lockVersion(101L);
        verify(embeddingMapper, never()).delete(any());
        verify(embeddingMapper, never()).insert(any(AiDocEmbedding.class));
    }

    @Test
    void rebuildRejectsTamperedHashMissingDocumentAndExistingTransaction() {
        stubEmbedEnabled("{}");
        AiDocument doc = reviewed("正文"); doc.setContentSha256("tampered");
        assertThrows(IpdBusinessException.class, () -> service.rebuildIndex(doc, () -> "000000"));
        verifyNoInteractions(aiGateway);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IpdBusinessException.class,
                () -> service.rebuildIndex(reviewed("正文"), () -> "000000"));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        when(aiGateway.embed(any(), anyList())).thenReturn(List.of(healthyVector()));
        when(documentMapper.lockVersion(101L)).thenReturn(null);
        assertThrows(IpdBusinessException.class, () -> service.rebuildIndex(reviewed("正文"), () -> "000000"));
        verify(embeddingMapper, never()).delete(any());
        verify(embeddingMapper, never()).insert(any(AiDocEmbedding.class));
    }

    @Test
    void versionTenantQueryRequiresUndeletedDocumentAndProjectWithSameTenant() throws Exception {
        String sql = String.join(" ", AiDocumentMapper.class.getMethod("selectVersionTenant", Long.class)
            .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertTrue(sql.contains("p.tenant_id = d.tenant_id"));
        assertTrue(sql.contains("d.del_flag = '0'"));
        assertTrue(sql.contains("p.del_flag = '0'"));
    }

    private static float[] healthyVector() {
        float[] vector = new float[BuiltinEmbeddingModel.DIMENSION]; vector[0] = 1; return vector;
    }

    private static AiDocument reviewed(String content) {
        AiDocument doc = doc(content); doc.setStatus(AiDocumentService.STATUS_REVIEWED);
        doc.setVersionNo(1);
        doc.setContentSha256(AiDocumentService.sha256Hex(content)); return doc;
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
    }

    @Test
    @DisplayName("官方对话模型未填向量键时走内置向量，且不复用对话密钥")
    void resolveEmbedConfigFallsBackToBuiltinWithoutChatKey() {
        stubEmbedEnabled("{}");
        AiDocEmbeddingService.EmbedEndpoint cfg = service.resolveEmbedConfig();
        assertNotNull(cfg);
        assertEquals(BuiltinEmbeddingModel.BASE_URL, cfg.endpoint());
        assertEquals(BuiltinEmbeddingModel.MODEL_NAME, cfg.embedModel());
        assertEquals(BuiltinEmbeddingModel.API_KEY, cfg.apiKey());
        verify(modelConfigService, never()).decryptApiKey(any());
    }

    @Test
    @DisplayName("内置向量开关关闭且官方行未填向量键时 RAG 关闭")
    void resolveEmbedConfigBuiltinDisabled() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty(BuiltinEmbeddingModel.ENABLED_PROPERTY, Boolean.class, Boolean.TRUE))
            .thenReturn(Boolean.FALSE);
        service = new AiDocEmbeddingService(embeddingMapper, documentMapper, modelConfigService, aiGateway, environment);
        stubEmbedEnabled("{}");
        assertNull(service.resolveEmbedConfig());
        verify(modelConfigService, never()).decryptApiKey(any());
    }

    // ---- 端点归一化（卡 80b0be1f：全路径 / base URL 两形态兼容） ----

    @Test
    @DisplayName("归一化：全路径 …/v1/embeddings 剥子路径为 base URL；base URL 原样透传")
    void normalizeEmbedBaseUrlTwoForms() {
        // base URL 形态：嵌入客户端自拼 /embeddings，原样透传（真库 id=1 现行写法）
        assertEquals("http://embed.example.com/v1",
            AiDocEmbeddingService.normalizeEmbedBaseUrl("http://embed.example.com/v1"));
        // 全路径形态：剥 /embeddings 尾缀（直传会拼成 /embeddings/embeddings → 404）
        assertEquals("http://embed.example.com/v1",
            AiDocEmbeddingService.normalizeEmbedBaseUrl("http://embed.example.com/v1/embeddings"));
        // 尾斜杠（两形态各自带尾斜杠）
        assertEquals("http://embed.example.com/v1",
            AiDocEmbeddingService.normalizeEmbedBaseUrl("http://embed.example.com/v1/"));
        assertEquals("http://embed.example.com/v1",
            AiDocEmbeddingService.normalizeEmbedBaseUrl("http://embed.example.com/v1/embeddings/"));
        // 大小写宽容（path 大小写敏感但该写法必 404，剥离是纯增益）；双写幂等归一
        assertEquals("http://embed.example.com/v1",
            AiDocEmbeddingService.normalizeEmbedBaseUrl("http://embed.example.com/v1/EMBEDDINGS"));
        assertEquals("http://embed.example.com/v1",
            AiDocEmbeddingService.normalizeEmbedBaseUrl("http://embed.example.com/v1/embeddings/embeddings"));
        // 无 /v1 前缀的根路径全路径与带端口形态；null/空白防御
        assertEquals("http://h:9997",
            AiDocEmbeddingService.normalizeEmbedBaseUrl("http://h:9997/embeddings"));
        assertEquals("http://h:9997/v1",
            AiDocEmbeddingService.normalizeEmbedBaseUrl("  http://h:9997/v1/embeddings  "));
        assertEquals("", AiDocEmbeddingService.normalizeEmbedBaseUrl(null));
        assertEquals("", AiDocEmbeddingService.normalizeEmbedBaseUrl("   "));
    }

    @Test
    @DisplayName("resolveEmbedConfig：全路径配置归一后才交给 gateway（消费口单源，回显/入库不改写）")
    void resolveEmbedConfigNormalizesFullPath() {
        // 全路径写法入库（运营从 OpenAI 文档整段复制）：读侧归一为 base URL
        stubEmbedEnabled("{\"embedEndpoint\":\"http://embed.example.com/v1/embeddings\",\"embedModel\":\"emb-1\"}");
        AiDocEmbeddingService.EmbedEndpoint cfg = service.resolveEmbedConfig();
        assertNotNull(cfg);
        assertEquals("http://embed.example.com/v1", cfg.endpoint(), "全路径 → 归一为 base URL");
        assertEquals("emb-1", cfg.embedModel());

        // 归一化后经 embedSync 传递给 AiGateway 的 AiTestConfig.baseUrl 即为 base URL
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(healthyVector()));
        org.mockito.ArgumentCaptor<AiTestConfig> cap =
            org.mockito.ArgumentCaptor.forClass(AiTestConfig.class);
        service.embedSync(doc("正文"), cfg);
        verify(aiGateway).embed(cap.capture(), anyList());
        assertEquals("http://embed.example.com/v1", cap.getValue().baseUrl(),
            "客户端收到 base URL → 自拼 POST {base}/embeddings 命中真实端点");
    }

    // ---- embedAsync 降级（不出队不抛错） ----

    @Test
    @DisplayName("embedAsync：两键都缺走内置向量，调用线程内完成嵌入并写入")
    void embedAsyncSkipsWhenRagOff() {
        stubEmbedEnabled("{}");
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(healthyVector()));
        service.embedAsync(doc("正文"));
        var captor = org.mockito.ArgumentCaptor.forClass(AiTestConfig.class);
        verify(aiGateway).embed(captor.capture(), anyList());
        assertEquals(BuiltinEmbeddingModel.BASE_URL, captor.getValue().baseUrl());
        assertEquals(BuiltinEmbeddingModel.MODEL_NAME, captor.getValue().modelName());
        assertEquals(BuiltinEmbeddingModel.API_KEY, captor.getValue().apiKey());
        verify(embeddingMapper).insert(any(AiDocEmbedding.class));
        verify(modelConfigService, never()).decryptApiKey(any());
    }

    @Test
    @DisplayName("embedAsync：只填一键才关闭，不调用嵌入")
    void embedAsyncSkipsWhenOnlyOneEmbedKey() {
        stubEmbedEnabled("{\"embedModel\":\"emb-1\"}");
        assertNull(service.resolveEmbedConfig());
        service.embedAsync(doc("正文"));
        verifyNoInteractions(aiGateway);
        verifyNoInteractions(embeddingMapper);
    }

    @Test
    @DisplayName("embedAsync：null/空白内容直接跳过；currentEnabled 抛错改走内置且不外抛")
    void embedAsyncGuards() {
        stubEmbedEnabled(EMBED_CFG);
        service.embedAsync(null);
        service.embedAsync(doc("  "));
        verifyNoInteractions(aiGateway);
        when(modelConfigService.currentEnabled())
            .thenThrow(new IllegalStateException("no config"));
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(healthyVector()));
        assertDoesNotThrow(() -> service.embedAsync(doc("正文")));
        var captor = org.mockito.ArgumentCaptor.forClass(AiTestConfig.class);
        verify(aiGateway).embed(captor.capture(), anyList());
        assertEquals(BuiltinEmbeddingModel.MODEL_NAME, captor.getValue().modelName());
        assertEquals(BuiltinEmbeddingModel.API_KEY, captor.getValue().apiKey());
    }

    // ---- embedSync：先删后插 / 失败降级 ----

    @Test
    @DisplayName("embedSync：gateway 失败返回 null → 不删旧片不插新片（保留现状）")
    void embedSyncFailureKeepsExisting() {
        when(aiGateway.embed(any(AiTestConfig.class), anyList())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.embedSync(doc("正文内容"),
            new AiDocEmbeddingService.EmbedEndpoint("http://embed.example.com/v1", "sk-embed-key", "emb-1")));
        verify(embeddingMapper, never()).delete(any());
        verify(embeddingMapper, never()).insert(any(AiDocEmbedding.class));
    }

    @Test
    @DisplayName("embedSync：成功 → doc 级先删后插（同 embedModel），片序 chunkSeq 从 0 连续")
    void embedSyncDeleteThenInsert() {
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(healthyVector(), healthyVector()));
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.lockVersion(101L)).thenReturn(doc("字".repeat(900)));
        service.embedSync(doc("字".repeat(900)), new AiDocEmbeddingService.EmbedEndpoint(
            "http://embed.example.com/v1", "sk-embed-key", "emb-1"));
        InOrder order = inOrder(embeddingMapper);
        order.verify(embeddingMapper).delete(any());
        var cap = org.mockito.ArgumentCaptor.forClass(AiDocEmbedding.class);
        verify(embeddingMapper, times(2)).insert(cap.capture());
        assertEquals(0, cap.getAllValues().get(0).getChunkSeq());
        assertEquals(1, cap.getAllValues().get(1).getChunkSeq());
        assertEquals("emb-1", cap.getAllValues().get(0).getEmbedModel());
        assertEquals("[" + "1.0," + "0.0,".repeat(BuiltinEmbeddingModel.DIMENSION - 2) + "0.0]",
            cap.getAllValues().get(0).getVectorJson());
    }

    @Test
    void strictRetrievalPropagatesDocumentDatabaseFailure() {
        when(documentMapper.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> service.retrieveContextStrict(9L, null, "查询"));
        var failure = org.junit.jupiter.api.Assertions.assertThrows(org.ruoyi.ipd.common.IpdBusinessException.class,
            () -> service.retrieveContext(9L, null, "查询"));
        assertEquals(org.ruoyi.ipd.common.ApiV1ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("项目文档检索失败"));
        assertFalse(failure.getMessage().contains("database unavailable"));
    }

    @Test
    void emptyEmbeddingResponseIsFailureRatherThanNoResults() {
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.selectList(any())).thenReturn(List.of(AiDocument.builder().id(1L).build()));
        when(aiGateway.embed(any(AiTestConfig.class), anyList())).thenReturn(List.of());
        var failure = assertThrows(org.ruoyi.ipd.common.IpdBusinessException.class,
            () -> service.retrieveContext(9L, null, "查询"));
        assertEquals(org.ruoyi.ipd.common.ApiV1ErrorCode.INTERNAL_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage().contains("项目文档检索失败"));
        verifyNoInteractions(embeddingMapper);
    }

    // ---- retrieveContext ----

    @Test
    @DisplayName("检索：空查询与无审核文档返回 EMPTY，数据库故障返回业务错误")
    void retrieveContextDegradations() {
        stubEmbedEnabled(EMBED_CFG);
        AiDocEmbeddingService.RetrievalContext empty = service.retrieveContext(9L, null, "  ");
        assertEquals(AiDocEmbeddingService.RetrievalContext.EMPTY, empty);
        assertEquals(AiDocEmbeddingService.RetrievalContext.EMPTY, service.retrieveContext(9L, null, null));
        assertEquals(AiDocEmbeddingService.RetrievalContext.EMPTY, service.retrieveContext(9L, null, "查询"),
            "没有审核文档，不发起向量调用");

        stubEmbedEnabled("{}");
        AiDocEmbeddingService.RetrievalContext ctx = service.retrieveContext(9L, null, "查询");
        assertEquals(0, ctx.hits());
        assertEquals("", ctx.block());
    }

    @Test
    @DisplayName("检索：余弦 top-K 拼块（正交片不入选），块含来源标注；候选空 EMPTY")
    void retrieveContextTopK() {
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.selectList(any())).thenReturn(List.of(
            AiDocument.builder().id(1L).build(), AiDocument.builder().id(2L).build()));
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{1, 0}));

        AiDocEmbedding similar = AiDocEmbedding.builder().docId(1L).projectId(9L).docType("PRD")
            .title("旧需求").chunkSeq(0).chunkText("相似片段正文")
            .embedModel("emb-1").vectorJson("[1.0,0.0]").build();
        AiDocEmbedding orthogonal = AiDocEmbedding.builder().docId(2L).projectId(9L).docType("MRD")
            .title("无关").chunkSeq(0).chunkText("无关片段")
            .embedModel("emb-1").vectorJson("[0.0,1.0]").build();
        when(embeddingMapper.selectList(any())).thenReturn(List.of(similar, orthogonal));

        AiDocEmbeddingService.RetrievalContext ctx = service.retrieveContext(9L, null, "查询");
        assertEquals(1, ctx.hits(), "正交片（cos=0）不入选");
        verify(documentMapper).selectList(argThat(w ->
            w instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> actual
                && hasProjectScope(actual)
                && hasReviewedStatus(actual)));
        assertTrue(ctx.block().contains("旧需求"), "块含来源标注（docType/title）");
        assertTrue(ctx.block().contains("相似片段正文"));
        assertEquals(1, ctx.sources().size());
        assertEquals("PROJECT_DOCUMENT", ctx.sources().get(0).sourceType());
        assertEquals("REVIEWED", ctx.sources().get(0).reviewStatus());
        assertEquals("1", ctx.sources().get(0).documentId());
        assertNull(ctx.sources().get(0).knowledgeId());
        assertTrue(ctx.chars() > 0);

        when(embeddingMapper.selectList(any())).thenReturn(List.of());
        assertThrows(org.ruoyi.ipd.common.IpdBusinessException.class,
            () -> service.retrieveContext(9L, null, "查询"), "已审核但当前模型未索引必须可见失败");
    }

    @Test
    @DisplayName("检索：已不在当前已审核名单里的残留向量不能出源")
    void staleEmbeddingIsNotACitation() {
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.selectList(any())).thenReturn(List.of(AiDocument.builder().id(1L).build()));
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{1, 0}));
        AiDocEmbedding current = AiDocEmbedding.builder().docId(1L).projectId(9L).docType("PRD")
            .title("现行需求").chunkSeq(0).chunkText("现行正文")
            .embedModel("emb-1").vectorJson("[1.0,0.0]").build();
        AiDocEmbedding stale = AiDocEmbedding.builder().docId(99L).projectId(9L).docType("PRD")
            .title("过期向量").chunkSeq(0).chunkText("过期正文不应引用")
            .embedModel("emb-1").vectorJson("[1.0,0.0]").build();
        when(embeddingMapper.selectList(any())).thenReturn(List.of(stale, current));

        AiDocEmbeddingService.RetrievalContext ctx = service.retrieveContextStrict(9L, null, "查询");

        assertEquals(1, ctx.hits());
        assertEquals("1", ctx.sources().get(0).documentId());
        assertEquals("REVIEWED", ctx.sources().get(0).reviewStatus());
        assertFalse(ctx.block().contains("过期正文不应引用"));
        assertFalse(ctx.citationText().contains("99"));
    }

    @Test
    @DisplayName("检索：已归档文档的残留向量不得进入提示词")
    void retrieveContextExcludesArchivedDocument() {
        stubEmbedEnabled(EMBED_CFG);
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{1, 0}));
        AiDocEmbedding archived = AiDocEmbedding.builder().docId(101L).projectId(9L).docType("PRD")
            .title("已归档资料").chunkSeq(0).chunkText("归档后不得出现的正文")
            .embedModel("emb-1").vectorJson("[1.0,0.0]").build();
        when(embeddingMapper.selectList(any())).thenReturn(List.of(archived));
        // 数据库当前行已归档：REVIEWED + MP 逻辑删除过滤的 ID 查询返回空。
        when(documentMapper.selectList(any())).thenReturn(List.of());

        AiDocEmbeddingService.RetrievalContext ctx = service.retrieveContext(9L, "PRD", "查询");

        assertEquals(0, ctx.hits());
        assertEquals("", ctx.block());
        verify(documentMapper).selectList(argThat(w ->
            w instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> actual
                && hasReviewedStatus(actual)));
        verifyNoInteractions(aiGateway, embeddingMapper);
    }

    @Test
    @DisplayName("检索：候选异常（反序列化坏向量）不炸——cos=-1 过滤后仍可命中其他片")
    void retrieveContextBadVectorTolerated() {
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.selectList(any())).thenReturn(List.of(
            AiDocument.builder().id(3L).build(), AiDocument.builder().id(4L).build()));
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{1, 0}));
        AiDocEmbedding good = AiDocEmbedding.builder().docId(3L).projectId(9L).docType("PRD")
            .title("好片").chunkSeq(0).chunkText("正文").embedModel("emb-1").vectorJson("[1.0,0.0]").build();
        AiDocEmbedding bad = AiDocEmbedding.builder().docId(4L).projectId(9L).docType("PRD")
            .title("坏片").chunkSeq(0).chunkText("坏").embedModel("emb-1").vectorJson("not-json").build();
        when(embeddingMapper.selectList(any())).thenReturn(List.of(bad, good));
        var result = service.retrieveContextStrict(9L, null, "查询");
        assertEquals(1, result.hits());
        assertEquals(AiDocEmbeddingService.RetrievalStatus.PARTIAL, result.status());
        assertEquals(java.util.Map.of("MALFORMED_VECTOR", 1), result.issueCounts());
        assertEquals(List.of("3"), result.sources().stream().map(AiDocEmbeddingService.CitationSource::documentId).toList());
        assertFalse(result.citationText().contains("坏片"));
        assertThrows(org.ruoyi.ipd.common.IpdBusinessException.class,
            () -> service.retrieveContext(9L, null, "查询"));
    }

    @Test
    void invalidQueryVectorsCannotBecomeNoHit() {
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.selectList(any())).thenReturn(List.of(AiDocument.builder().id(3L).build()));
        for (float[] query : List.of(new float[0], new float[]{0, 0},
                new float[]{Float.NaN, 1}, new float[]{Float.POSITIVE_INFINITY, 1})) {
            when(aiGateway.embed(any(AiTestConfig.class), anyList())).thenReturn(List.of(query));
            assertThrows(IllegalStateException.class, () -> service.retrieveContextStrict(9L, null, "查询"));
        }
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{1, 0}, new float[]{1, 0}));
        assertThrows(IllegalStateException.class, () -> service.retrieveContextStrict(9L, null, "查询"));
        verifyNoInteractions(embeddingMapper);
    }

    @Test
    void damagedCandidatesAndMissingIndexesRemainVisible() {
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.selectList(any())).thenReturn(List.of(
            AiDocument.builder().id(3L).build(), AiDocument.builder().id(4L).build()));
        when(aiGateway.embed(any(AiTestConfig.class), anyList())).thenReturn(List.of(new float[]{1, 0}));
        var good = AiDocEmbedding.builder().docId(3L).chunkText("有效原句")
            .vectorJson("[1,0]").build();
        var reasons = java.util.Map.of("not-json", "MALFORMED_VECTOR", "[]", "EMPTY_VECTOR",
            "null", "EMPTY_VECTOR", "[1]", "DIMENSION_MISMATCH", "[0,0]", "ZERO_NORM",
            "[1e100,0]", "NON_FINITE_VECTOR");
        reasons.forEach((json, reason) -> {
            var bad = AiDocEmbedding.builder().docId(4L).chunkText("损坏内容").vectorJson(json).build();
            when(embeddingMapper.selectList(any())).thenReturn(List.of(good, bad));
            var partial = service.retrieveContextStrict(9L, null, "查询");
            assertEquals(AiDocEmbeddingService.RetrievalStatus.PARTIAL, partial.status());
            assertEquals(java.util.Map.of(reason, 1), partial.issueCounts());
            assertFalse(partial.citationText().contains("损坏内容"));
            when(embeddingMapper.selectList(any())).thenReturn(List.of(bad));
            assertThrows(IllegalStateException.class, () -> service.retrieveContextStrict(9L, null, "查询"));
        });
        when(embeddingMapper.selectList(any())).thenReturn(List.of(good));
        assertEquals(java.util.Map.of("INDEX_NOT_READY", 1),
            service.retrieveContextStrict(9L, null, "查询").issueCounts());
        when(documentMapper.selectList(any())).thenReturn(List.of(AiDocument.builder().id(3L).build()));
        good.setVectorJson("[0,1]");
        assertEquals(AiDocEmbeddingService.RetrievalStatus.NO_HIT,
            service.retrieveContextStrict(9L, null, "查询").status());
    }

    @Test
    void emptyCandidateContentNeverBecomesCitationOrSuccess() {
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.selectList(any())).thenReturn(List.of(AiDocument.builder().id(3L).build()));
        when(aiGateway.embed(any(AiTestConfig.class), anyList())).thenReturn(List.of(new float[]{1, 0}));
        var good = AiDocEmbedding.builder().docId(3L).chunkText("有效原句").vectorJson("[1,0]").build();
        for (String content : java.util.Arrays.asList(null, "", " \n\t")) {
            var empty = AiDocEmbedding.builder().docId(3L).chunkText(content).vectorJson("[1,0]").build();
            when(embeddingMapper.selectList(any())).thenReturn(List.of(empty));
            assertThrows(IllegalStateException.class, () -> service.retrieveContextStrict(9L, null, "查询"));
            assertThrows(org.ruoyi.ipd.common.IpdBusinessException.class,
                () -> service.retrieveContext(9L, null, "查询"));
            when(embeddingMapper.selectList(any())).thenReturn(List.of(empty, good));
            var partial = service.retrieveContextStrict(9L, null, "查询");
            assertEquals(1, partial.hits());
            assertEquals(AiDocEmbeddingService.RetrievalStatus.PARTIAL, partial.status());
            assertEquals(java.util.Map.of("EMPTY_CONTENT", 1), partial.issueCounts());
            assertEquals(1, partial.sources().size());
            assertTrue(partial.citationText().contains("有效原句"));
            assertFalse(partial.citationText().contains("null"));
        }
    }

    @Test
    void finiteFloatMaximumVectorsRemainFiniteAndRetrievable() {
        assertEquals(1.0, AiDocEmbeddingService.cosine(
            new float[]{Float.MAX_VALUE, Float.MAX_VALUE},
            new float[]{Float.MAX_VALUE, Float.MAX_VALUE}), 1e-12);
        stubEmbedEnabled(EMBED_CFG);
        when(documentMapper.selectList(any())).thenReturn(List.of(AiDocument.builder().id(3L).build()));
        when(aiGateway.embed(any(AiTestConfig.class), anyList()))
            .thenReturn(List.of(new float[]{Float.MAX_VALUE, Float.MAX_VALUE}));
        var candidate = AiDocEmbedding.builder().docId(3L).chunkText("极值有效原句")
            .vectorJson("[3.4028235e38,3.4028235e38]").build();
        when(embeddingMapper.selectList(any())).thenReturn(List.of(candidate));
        var result = service.retrieveContextStrict(9L, null, "查询");
        assertEquals(AiDocEmbeddingService.RetrievalStatus.SUCCESS, result.status());
        assertEquals(1, result.hits());
        assertTrue(result.citationText().contains("极值有效原句"));
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
