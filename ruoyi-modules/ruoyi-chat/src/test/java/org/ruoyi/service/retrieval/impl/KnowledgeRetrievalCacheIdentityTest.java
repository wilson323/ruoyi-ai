package org.ruoyi.service.retrieval.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.trace.config.TraceProperties;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.factory.RerankModelFactory;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.vector.VectorStoreService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3 检索缓存身份段单测：
 * 1) 身份段插在 kid 段之后、query 段之前，kid 必须保持第一段（invalidateKnowledge 前缀清除语义）；
 * 2) 同 kid 同 query 不同身份产出不同缓存键（身份隔离）；
 * 3) invalidateKnowledge(kid) 清除该 kid 全部身份的缓存条目，其他 kid 不受影响（前缀清除语义不回归）；
 * 4) 同身份同参数下缓存命中行为不回归（向量检索只执行一次）。
 * <p>
 * cacheKey 为私有方法，与同包 KnowledgeRetrievalServiceRegressionTest 一致采用反射直测；
 * invalidate 为公开行为直测。纯 mock 用例，未覆盖 DDL 合法性。
 */
@Tag("dev")
class KnowledgeRetrievalCacheIdentityTest {

    private static KnowledgeRetrievalServiceImpl newService() {
        return new KnowledgeRetrievalServiceImpl(
            mock(VectorStoreService.class), mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
            mock(TraceRecordService.class), new TraceProperties());
    }

    private static String invokeCacheKey(KnowledgeRetrievalServiceImpl service, QueryVectorBo bo) throws Exception {
        Method method = KnowledgeRetrievalServiceImpl.class.getDeclaredMethod("cacheKey", QueryVectorBo.class);
        method.setAccessible(true);
        return (String) method.invoke(service, bo);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> retrievalCacheOf(KnowledgeRetrievalServiceImpl service) throws Exception {
        Field field = KnowledgeRetrievalServiceImpl.class.getDeclaredField("retrievalCache");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(service);
    }

    private static Object newCacheEntry() throws Exception {
        Class<?> entryClass = Class.forName(
            KnowledgeRetrievalServiceImpl.class.getName() + "$CacheEntry");
        Constructor<?> constructor = entryClass.getDeclaredConstructor(long.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(System.currentTimeMillis(), List.of());
    }

    private static QueryVectorBo bo(String kid, String query) {
        QueryVectorBo bo = new QueryVectorBo();
        bo.setKid(kid);
        bo.setQuery(query);
        bo.setMaxResults(5);
        bo.setEnableHybrid(false);
        bo.setEnableRerank(false);
        return bo;
    }

    @Test
    void identitySegmentSitsBetweenKidAndQuery() throws Exception {
        KnowledgeRetrievalServiceImpl service = newService();
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            String key = invokeCacheKey(service, bo("1", "问题"));
            // kid 保持第一段，身份第二段，query 第三段
            assertTrue(key.startsWith("1|100|问题|"), "key 应为 kid|identity|query 前缀，实际=" + key);
        }
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(null);
            String key = invokeCacheKey(service, bo("1", "问题"));
            assertTrue(key.startsWith("1|anon|问题|"), "未登录身份段应为 anon，实际=" + key);
        }
    }

    @Test
    void sameKidAndQueryDifferentIdentitiesProduceDifferentKeys() throws Exception {
        KnowledgeRetrievalServiceImpl service = newService();
        String keyUserA;
        String keyAnon;
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            keyUserA = invokeCacheKey(service, bo("1", "问题"));
        }
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(200L);
            keyAnon = invokeCacheKey(service, bo("1", "问题"));
        }
        assertNotEquals(keyUserA, keyAnon, "不同身份不得共享缓存条目");
    }

    @Test
    void invalidateKnowledgeClearsAllIdentitiesForKid() throws Exception {
        KnowledgeRetrievalServiceImpl service = newService();
        String keyUserA;
        String keyUserB;
        String keyOtherKid;
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            keyUserA = invokeCacheKey(service, bo("1", "问题"));
            keyOtherKid = invokeCacheKey(service, bo("2", "问题"));
        }
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(200L);
            keyUserB = invokeCacheKey(service, bo("1", "问题"));
        }
        Map<String, Object> cache = retrievalCacheOf(service);
        Object entry = newCacheEntry();
        cache.put(keyUserA, entry);
        cache.put(keyUserB, entry);
        cache.put(keyOtherKid, entry);
        assertEquals(3, cache.size());

        service.invalidateKnowledge("1");

        assertFalse(cache.containsKey(keyUserA), "kid=1 的身份100缓存应被前缀清除");
        assertFalse(cache.containsKey(keyUserB), "kid=1 的身份200缓存应被前缀清除");
        assertTrue(cache.containsKey(keyOtherKid), "kid=2 的缓存不应受影响");
    }

    @Test
    void sameIdentityStillHitsCache() {
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        when(vectorStore.search(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(KnowledgeRetrievalVo.builder().id("fid-a").content("A").score(0.9).build()));
        KnowledgeRetrievalServiceImpl service = new KnowledgeRetrievalServiceImpl(
            vectorStore, mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
            mock(TraceRecordService.class), new TraceProperties());
        // 测试环境无 SaToken 会话上下文，LoginHelper.getUserId() 归 null → 身份段统一为 anon，两次同键
        QueryVectorBo query = bo("1", "问题");

        List<KnowledgeRetrievalVo> first = service.retrieve(query);
        List<KnowledgeRetrievalVo> second = service.retrieve(query);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        verify(vectorStore, times(1)).search(org.mockito.ArgumentMatchers.any());
    }
}
