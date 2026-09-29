package org.ruoyi.service.retrieval.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.trace.config.TraceProperties;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.factory.RerankModelFactory;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.vector.VectorStoreService;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * B1 缓存键过滤段测试：cacheKey 末尾并入 6 个仅后端装配过滤参数
 * （S3 的 B2 预告项提前落位）——同 kid+query+身份、不同 maxSensitivity/scope
 * 不得互相命中缓存（否则高权限会话检索结果泄漏给低权限键）。
 * 同时验证：kid 保持首段（invalidateKnowledge 前缀清除语义不回归）、
 * 全 null 过滤 = 空态键形（现网无片段无公开库时键与 B1 前行为一致）、
 * copyOf 混合检索子通道透传过滤参数（§8.4 盘点缺口）。
 * 与 KnowledgeRetrievalCacheIdentityTest 同做法：反射直测私有方法 + mockStatic(LoginHelper)。
 */
@Tag("dev")
class KnowledgeRetrievalCacheFilterKeyTest {

    private static KnowledgeRetrievalServiceImpl newService() {
        return new KnowledgeRetrievalServiceImpl(
            mock(VectorStoreService.class), mock(RerankModelFactory.class), mock(KnowledgeFragmentMapper.class),
            mock(TraceRecordService.class), new TraceProperties(),
            mock(org.ruoyi.service.knowledge.KnowledgeAccessGate.class),
            new org.ruoyi.config.KnowledgeRetrievalAccessFilterProperties());
    }

    private static String invokeCacheKey(KnowledgeRetrievalServiceImpl service, QueryVectorBo bo) throws Exception {
        Method method = KnowledgeRetrievalServiceImpl.class.getDeclaredMethod("cacheKey", QueryVectorBo.class);
        method.setAccessible(true);
        return (String) method.invoke(service, bo);
    }

    private static QueryVectorBo bo(String kid, String query, String maxSensitivity) {
        QueryVectorBo bo = new QueryVectorBo();
        bo.setKid(kid);
        bo.setQuery(query);
        bo.setMaxResults(5);
        bo.setEnableHybrid(false);
        bo.setEnableRerank(false);
        bo.applyBackendAccessFilters(maxSensitivity, null, null, null, null, null);
        return bo;
    }

    @Test
    void differentMaxSensitivityProducesDifferentKey() throws Exception {
        KnowledgeRetrievalServiceImpl service = newService();
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            String internalKey = invokeCacheKey(service, bo("1", "问题", "INTERNAL"));
            String secretKey = invokeCacheKey(service, bo("1", "问题", "SECRET"));
            String sameInternal = invokeCacheKey(service, bo("1", "问题", "INTERNAL"));
            assertNotEquals(internalKey, secretKey, "同 kid+query+身份不同敏感级上限不得共享缓存");
            assertEquals(internalKey, sameInternal, "同参数须命中同键（缓存有效性）");
            // kid 首段 + 身份次序不因过滤段追加而变化（invalidateKnowledge 前缀语义）
            assertTrue(internalKey.startsWith("1|100|问题|"), "kid|identity|query 前缀不得漂移，实际=" + internalKey);
            assertEquals("SECRET", secretKey.split("\\|", -1)[13],
                "maxSensitivity 须为第 14 段（13 老段之后首位，后随 5 个归属空段），实际=" + secretKey);
        }
    }

    @Test
    void scopeAndOwnershipAlsoParticipateInKey() throws Exception {
        KnowledgeRetrievalServiceImpl service = newService();
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            QueryVectorBo base = bo("1", "问题", null);
            String baseKey = invokeCacheKey(service, base);
            QueryVectorBo scoped = bo("1", "问题", null);
            scoped.applyBackendAccessFilters(null, 7L, List.of("PERSON"), 3L, 4L, List.of(9L));
            String scopedKey = invokeCacheKey(service, scoped);
            assertNotEquals(baseKey, scopedKey, "作用域/归属差异须产生不同键");
            assertTrue(scopedKey.contains("PERSON") && scopedKey.contains("9"), "过滤值应入键，实际=" + scopedKey);
        }
    }

    @Test
    void nullFiltersKeepLegacyKeyShape() throws Exception {
        // 空态：6 过滤参数全 null → 键形 = B1 前 13 段 + 6 个空尾段（行为零变化的键级证据）
        KnowledgeRetrievalServiceImpl service = newService();
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            String key = invokeCacheKey(service, bo("1", "问题", null));
            String[] segments = key.split("\\|", -1);
            assertEquals(19, segments.length, "13 老段 + 6 新空段，实际=" + key);
            assertEquals("", segments[13], "maxSensitivity 空段位置保持");
        }
    }

    @Test
    void copyOfPropagatesAccessFiltersToHybridSubChannel() throws Exception {
        // §8.4 盘点缺口：手工拷贝漏过滤字段会让混合检索向量子通道丢过滤
        KnowledgeRetrievalServiceImpl service = newService();
        QueryVectorBo original = bo("1", "问题", "INTERNAL");
        original.applyBackendAccessFilters("INTERNAL", 7L, List.of("GROUP"), 3L, 4L, List.of(9L));
        Method copyOf = KnowledgeRetrievalServiceImpl.class
            .getDeclaredMethod("copyOf", QueryVectorBo.class, int.class);
        copyOf.setAccessible(true);
        QueryVectorBo copy = (QueryVectorBo) copyOf.invoke(service, original, 100);

        assertEquals(100, copy.getMaxResults());
        assertEquals(original.getQuery(), copy.getQuery());
        assertEquals(original.getKid(), copy.getKid());
        assertEquals("INTERNAL", copy.getMaxSensitivity(), "copyOf 须透传敏感级上限");
        assertEquals(7L, copy.getPersonId());
        assertEquals(List.of("GROUP"), copy.getScopeTypes());
        assertEquals(3L, copy.getGroupId());
        assertEquals(4L, copy.getProjectId());
        assertEquals(List.of(9L), copy.getOwnerAgentIds());
    }
}
