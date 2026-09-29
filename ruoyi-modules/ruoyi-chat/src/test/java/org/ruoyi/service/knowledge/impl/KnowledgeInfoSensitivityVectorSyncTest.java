package org.ruoyi.service.knowledge.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.domain.entity.knowledge.KnowledgeInfo;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B2 P1-1（实施方案 §3.4）：sensitivity 变更随动已入库向量 payload 同名键——
 * service 侧触发判据直测（updateByBo 依赖 MapstructUtils（Spring 上下文）无法纯单测，
 * 与 {@link KnowledgeInfoSensitivityMirrorTest} 同做法反射直测私有方法）。
 * <p>
 * 判据：仅在「显式携带 sensitivity 且与库内旧值不同（含旧值 null 的 B1 前老数据——
 * 顺带补标）」时触发 {@code VectorStoreService#updatePayloadSensitivity}；
 * embeddingModel 取库内旧值（部分更新 Bo 不带模型名的补偿）。
 * 失败语义 fail-noisy：随动异常向上传播（updateByBo @Transactional 使 MySQL 随事务回滚，
 * DB 侧不脱钩；向量 PATCH 非事务资源，残留由重试收敛），禁静默吞掉——吞掉即复活
 * B1 Validator 登记的「update 不随动=隔离穿透」。
 * Weaviate 侧 merge PATCH 的单元素数组拆标量坑（assignSingleOrArray）由
 * {@code WeaviateAccessFilterAssemblyTest#parseBatchObjectIds*} 覆盖解析半边。
 */
@Tag("dev")
class KnowledgeInfoSensitivityVectorSyncTest {

    private final VectorStoreService vectorStoreService = mock(VectorStoreService.class);

    private KnowledgeInfoServiceImpl newService() {
        return new KnowledgeInfoServiceImpl(
            mock(KnowledgeInfoMapper.class),
            mock(KnowledgeAttachMapper.class),
            mock(KnowledgeFragmentMapper.class),
            vectorStoreService,
            mock(KnowledgeRetrievalService.class),
            mock(OssService.class),
            mock(KnowledgeAccessGate.class));
    }

    private void sync(Long kid, KnowledgeInfo before, KnowledgeInfo update) throws Exception {
        Method method = KnowledgeInfoServiceImpl.class.getDeclaredMethod(
            "syncVectorPayloadSensitivity", Long.class, KnowledgeInfo.class, KnowledgeInfo.class);
        method.setAccessible(true);
        method.invoke(newService(), kid, before, update);
    }

    private static KnowledgeInfo info(String sensitivity, String embeddingModel) {
        KnowledgeInfo entity = new KnowledgeInfo();
        entity.setId(1L);
        entity.setSensitivity(sensitivity);
        entity.setEmbeddingModel(embeddingModel);
        return entity;
    }

    @Test
    void sensitivityChangeTriggersVectorPayloadSyncWithLegacyModel() throws Exception {
        // 变更 INTERNAL→SECRET 触发随动；模型名取库内旧值（部分更新 Bo 不带模型名的补偿，
        // 不得因 Bo 未带而传 null 导致 Weaviate 侧 class 定位漂移）
        sync(1L, info("INTERNAL", "text-embedding-v3"), info("SECRET", null));
        verify(vectorStoreService).updatePayloadSensitivity("1", "SECRET", "text-embedding-v3");
    }

    @Test
    void legacyNullSensitivityStillSyncsForBackfill() throws Exception {
        // 老库行 sensitivity 为 null（B1 之前写入的数据）：首次显式定级视为变更——
        // 顺带为向量侧缺 sensitivity 键的老对象补标
        sync(1L, info(null, "text-embedding-v3"), info("INTERNAL", null));
        verify(vectorStoreService).updatePayloadSensitivity("1", "INTERNAL", "text-embedding-v3");
    }

    @Test
    void unchangedSensitivitySkipsVectorSync() throws Exception {
        // 同值更新零动作（低频管理面不做无谓全量 PATCH）
        sync(1L, info("SECRET", "m3"), info("SECRET", null));
        verify(vectorStoreService, never()).updatePayloadSensitivity(anyString(), anyString(), anyString());
    }

    @Test
    void absentSensitivitySkipsVectorSync() throws Exception {
        // update 未携带 sensitivity（updateByBo 传 before=null 契约）：不触发随动
        //（sensitivity 不进 SET 子句，库内两键原值不动）
        sync(1L, null, info(null, null));
        verify(vectorStoreService, never()).updatePayloadSensitivity(anyString(), anyString(), anyString());
    }

    @Test
    void vectorSyncFailurePropagatesFailNoisy() {
        // fail-noisy：向量侧随动失败必须向上抛（同事务回滚），静默吞掉即两侧脱钩
        doThrow(new ServiceException("Weaviate 敏感级随动失败")).when(vectorStoreService)
            .updatePayloadSensitivity(anyString(), anyString(), anyString());
        Exception thrown = assertThrows(Exception.class,
            () -> sync(1L, info("INTERNAL", "m3"), info("SECRET", null)));
        assertTrue(thrown.getCause() instanceof ServiceException, "随动失败语义应为业务异常");
    }
}
