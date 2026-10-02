package org.ruoyi.service.retrieval.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.trace.config.TraceProperties;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.config.KnowledgeRetrievalAccessFilterProperties;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.factory.RerankModelFactory;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.knowledge.RetrievalAccessProfile;
import org.ruoyi.service.vector.VectorStoreService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2 检索接线装配测试：KnowledgeRetrievalServiceImpl 检索入口按开关 + 身份经
 * KnowledgeAccessGate#retrievalAccessProfile 装配访问过滤参数（QueryVectorBo
 * 唯一写入口 applyBackendAccessFilters 的上游装配点）。
 * <p>
 * 业务语义断言（谁能看到什么）：
 * <ul>
 *   <li>开关关（默认，实施方案 §5 回滚态）：不装配、不触桥——检索行为与 B1 前一致；</li>
 *   <li>开关开：桥 profile 的三档上限（SECRET/INTERNAL/PUBLIC）逐档落到向量检索
 *       收到的 Bo（三档全矩阵，配合 WeaviateAccessFilterAssemblyTest 的
 *       cap×库值集合矩阵，覆盖「谁能看到什么」端到端）；</li>
 *   <li>E anon：无会话（LoginHelper null）走单参桥（anon 最严档），身份不凭空捏造；</li>
 *   <li>非 HTTP 线程显式身份（显式 userId 先例）：走双参桥，
 *       装配结果与 HTTP 线程同构；</li>
 *   <li>桥解析异常：fail-closed 按 PUBLIC 装配（不得退化成「无闸门」）。</li>
 * </ul>
 * 纯 Mockito 单测；与 KnowledgeRetrievalCacheFilterKeyTest 同做法 mockStatic(LoginHelper)。
 */
@Tag("dev")
class KnowledgeRetrievalBridgeAssemblyTest {

    private static final KnowledgeRetrievalVo HIT =
        KnowledgeRetrievalVo.builder().id("fid-a").content("A").score(0.9).build();

    private static QueryVectorBo plainBo() {
        QueryVectorBo bo = new QueryVectorBo();
        bo.setKid("9");
        bo.setQuery("问题");
        bo.setMaxResults(5);
        bo.setEnableHybrid(false);
        bo.setEnableRerank(false);
        return bo;
    }

    /** 捕获向量检索实际收到的 Bo（copyOf 透传后的装配证据）。 */
    private static QueryVectorBo boReceivedByVectorSearch(VectorStoreService vectorStore) {
        ArgumentCaptor<QueryVectorBo> captor = ArgumentCaptor.forClass(QueryVectorBo.class);
        verify(vectorStore).search(captor.capture());
        return captor.getValue();
    }

    @Test
    void disabledSwitchKeepsLegacyBehaviorWithoutTouchingBridge() {
        // 回滚态（默认）：过滤参数不装配（null=不加谓词），桥 profile 零调用
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        when(vectorStore.search(any())).thenReturn(List.of(HIT));
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        KnowledgeRetrievalAccessFilterProperties properties =
            new KnowledgeRetrievalAccessFilterProperties();
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
            vectorStore, mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
            mock(TraceRecordService.class), new TraceProperties(), gate, properties);

        List<KnowledgeRetrievalVo> results = service.retrieve(plainBo());

        assertEquals(1, results.size());
        QueryVectorBo received = boReceivedByVectorSearch(vectorStore);
        assertNull(received.getMaxSensitivity(), "开关关闭不得装配敏感级上限（回旧行为）");
        assertNull(received.getPersonId());
        assertNull(received.getScopeTypes());
        verify(gate, never()).retrievalAccessProfile();
        verify(gate, never()).retrievalAccessProfile(anyLong());
    }

    @Test
    void enabledSwitchAssemblesAllThreeTiersFromBridgeProfile() {
        // 三档全矩阵：桥给什么档，向量检索 Bo 就带什么档（含归属键）——
        // cap 值是「谁能看到什么」的上游权威，逐档断言防错位/防丢档
        for (String tier : List.of("SECRET", "INTERNAL", "PUBLIC")) {
            VectorStoreService vectorStore = mock(VectorStoreService.class);
            when(vectorStore.search(any())).thenReturn(List.of(HIT));
            KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
            when(gate.retrievalAccessProfile(anyLong()))
                .thenReturn(new RetrievalAccessProfile(tier, 7L, null, null, null, null));
            KnowledgeRetrievalAccessFilterProperties properties =
                new KnowledgeRetrievalAccessFilterProperties();
            properties.setEnabled(true);
            KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
                vectorStore, mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
                mock(TraceRecordService.class), new TraceProperties(), gate, properties);

            try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
                login.when(LoginHelper::getUserId).thenReturn(100L);
                service.retrieve(plainBo());
            }

            QueryVectorBo received = boReceivedByVectorSearch(vectorStore);
            assertEquals(tier, received.getMaxSensitivity(), "桥 profile 的上限档必须落到检索 Bo");
            assertEquals(7L, received.getPersonId(), "归属 personId 须随 profile 装配");
            verify(gate).retrievalAccessProfile(100L);
        }
    }

    @Test
    void anonymousSessionUsesSingleArgBridgeAndKeepsStrictestTier() {
        // E anon：无会话（LoginHelper null）→ 单参桥（非 HTTP/HTTP 统一的匿名路径），
        // 桥给 anon 的档位（现态 PUBLIC 最严档）原样装配，不因匿名而跳过闸门
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        when(vectorStore.search(any())).thenReturn(List.of(HIT));
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        when(gate.retrievalAccessProfile())
            .thenReturn(RetrievalAccessProfile.FAIL_CLOSED_PUBLIC);
        KnowledgeRetrievalAccessFilterProperties properties =
            new KnowledgeRetrievalAccessFilterProperties();
        properties.setEnabled(true);
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
            vectorStore, mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
            mock(TraceRecordService.class), new TraceProperties(), gate, properties);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(null);
            service.retrieve(plainBo());
        }

        QueryVectorBo received = boReceivedByVectorSearch(vectorStore);
        assertEquals("PUBLIC", received.getMaxSensitivity(), "匿名必须按最严档装配，不得无闸门");
        assertNull(received.getPersonId(), "匿名无归属键，不得捏造 personId");
        verify(gate).retrievalAccessProfile();
        verify(gate, never()).retrievalAccessProfile(anyLong());
    }

    @Test
    void explicitIdentityVariantRoutesToTwoArgBridge() {
        // 非 HTTP 线程（显式 userId 先例）：显式身份走双参桥，
        // 不读会话上下文（@Async 线程无 Sa-Token ThreadLocal）
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        when(vectorStore.search(any())).thenReturn(List.of(HIT));
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        when(gate.retrievalAccessProfile(100L))
            .thenReturn(new RetrievalAccessProfile("INTERNAL", 100L, null, null, null, null));
        KnowledgeRetrievalAccessFilterProperties properties =
            new KnowledgeRetrievalAccessFilterProperties();
        properties.setEnabled(true);
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
            vectorStore, mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
            mock(TraceRecordService.class), new TraceProperties(), gate, properties);

        // 不 mockStatic(LoginHelper)：双参路径不得触碰会话上下文（触碰即裸线程炸/匿名化）
        service.retrieve(plainBo(), 100L);

        QueryVectorBo received = boReceivedByVectorSearch(vectorStore);
        assertEquals("INTERNAL", received.getMaxSensitivity());
        assertEquals(100L, received.getPersonId());
        verify(gate).retrievalAccessProfile(100L);
        verify(gate, never()).retrievalAccessProfile();
    }

    @Test
    void bridgeFailureDegradesToPublicGateFailClosed() {
        // fail-closed：桥装配异常不得退化成「无闸门」（无闸门=对全部敏感级放开）
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        when(vectorStore.search(any())).thenReturn(List.of(HIT));
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        when(gate.retrievalAccessProfile(anyLong()))
            .thenThrow(new RuntimeException("session store down"));
        KnowledgeRetrievalAccessFilterProperties properties =
            new KnowledgeRetrievalAccessFilterProperties();
        properties.setEnabled(true);
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
            vectorStore, mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
            mock(TraceRecordService.class), new TraceProperties(), gate, properties);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            service.retrieve(plainBo());
        }

        QueryVectorBo received = boReceivedByVectorSearch(vectorStore);
        assertEquals("PUBLIC", received.getMaxSensitivity(),
            "桥异常必须按 PUBLIC 最严档装配（fail-closed），不得无闸门");
    }

    @Test
    void assembledFiltersParticipateInCacheKeyTierIsolation() {
        // 装配与缓存联动：同 kid+query 不同档 profile 两次检索 → 键尾档位不同
        //（键级隔离本体由 KnowledgeRetrievalCacheFilterKeyTest 覆盖，此处断言
        // 装配发生在 cacheKey 之前——第二次低档检索不会命中第一次高档的缓存）
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        when(vectorStore.search(any())).thenReturn(List.of(HIT));
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        when(gate.retrievalAccessProfile(anyLong()))
            .thenReturn(new RetrievalAccessProfile("SECRET", null, null, null, null, null),
                new RetrievalAccessProfile("PUBLIC", null, null, null, null, null));
        KnowledgeRetrievalAccessFilterProperties properties =
            new KnowledgeRetrievalAccessFilterProperties();
        properties.setEnabled(true);
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
            vectorStore, mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
            mock(TraceRecordService.class), new TraceProperties(), gate, properties);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            service.retrieve(plainBo());
            service.retrieve(plainBo());
        }
        // 两次都真实触达向量检索（未互相命中缓存）——若装配晚于 cacheKey，
        // 第二次 PUBLIC 会命中第一次 SECRET 的缓存条目，times(2) 即失败
        verify(vectorStore, org.mockito.Mockito.times(2)).search(any());
    }
}
