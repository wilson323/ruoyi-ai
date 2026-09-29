package org.ruoyi.service.knowledge.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoBo;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 口收敛调用验证：/system/info 的 edit（updateByBo）/remove（deleteWithValidByIds）
 * 按管理面判据（assertManageable，仅 owned + superadmin 豁免）过 Gate。
 * <p>
 * 判据本体在 {@link UserIdShareKnowledgeAccessGateTest} 的 assertManageable 组；
 * 本类验证收敛口确实把 kid 送进 Gate、拒绝向上传播、破坏性动作零触达。
 * <p>
 * 契约（有意收紧，非回归）：现网 admin 与 3 个普通用户均持 system:info:edit/remove 权限码，
 * 收敛后非 owner（且非 superadmin）管理他人库一律拒绝——正是本测试固化的新契约。
 * updateByBo 放行全链路依赖 MapstructUtils（静态初始化取 Spring 容器），纯单测不可达，
 * 故 edit 面只测 Gate 前置与拒绝传播（放行链由 Gate 判据测试 + 后续 HTTP 验收覆盖）。
 * 纯 mock 用例，未覆盖 DDL 合法性。
 */
@Tag("dev")
class KnowledgeInfoManageAccessTest {

    private final KnowledgeInfoMapper baseMapper = mock(KnowledgeInfoMapper.class);
    private final KnowledgeAttachMapper attachMapper = mock(KnowledgeAttachMapper.class);
    private final VectorStoreService vectorStoreService = mock(VectorStoreService.class);
    private final OssService ossService = mock(OssService.class);
    private final KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
    private final KnowledgeInfoServiceImpl service = new KnowledgeInfoServiceImpl(
        baseMapper,
        attachMapper,
        mock(KnowledgeFragmentMapper.class),
        vectorStoreService,
        mock(KnowledgeRetrievalService.class),
        ossService,
        gate);

    // ---------- edit（PUT /system/info → updateByBo） ----------

    @Test
    void updateByBoAssertsManageableBeforeWrite() {
        doThrow(new ServiceException("仅库归属人可管理该知识库 kid=9")).when(gate).assertManageable(9L);
        KnowledgeInfoBo bo = new KnowledgeInfoBo();
        bo.setId(9L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateByBo(bo));
        assertTrue(ex.getMessage().contains("kid=9"));
        verify(gate).assertManageable(9L);
        verify(baseMapper, never()).updateById(any(org.ruoyi.domain.entity.knowledge.KnowledgeInfo.class));
    }

    @Test
    void updateByBoWithoutIdRoutesNullKidToGateFailClosed() {
        // EditGroup 的 id 非空校验只挡 Controller 入参；Service 直调路径靠 Gate 的 null kid 拒绝兜底
        doThrow(new ServiceException("知识库ID为空，无权管理该知识库")).when(gate).assertManageable(null);

        assertThrows(ServiceException.class, () -> service.updateByBo(new KnowledgeInfoBo()));
        verify(gate).assertManageable(null);
        verify(baseMapper, never()).updateById(any(org.ruoyi.domain.entity.knowledge.KnowledgeInfo.class));
    }

    // ---------- remove（DELETE /system/info/{ids} → deleteWithValidByIds） ----------

    @Test
    void removePreChecksAllKidsBeforeAnyDestructiveWork() {
        // 第二个 kid 非归属：全量预检必须发生在任何向量库/OSS/DB 删除动作之前——
        // 循环内中途拒绝虽可回滚 DB，但向量库与 OSS 清理无事务保护
        doThrow(new ServiceException("仅库归属人可管理该知识库 kid=10")).when(gate).assertManageable(10L);
        List<Long> ids = List.of(9L, 10L);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.deleteWithValidByIds(ids, true));
        assertTrue(ex.getMessage().contains("kid=10"));
        verify(gate).assertManageable(9L);
        verify(gate).assertManageable(10L);
        verify(baseMapper, never()).selectById(any(Long.class));
        verify(baseMapper, never()).deleteByIds(anyList());
        verify(vectorStoreService, never()).removeById(anyString(), anyString());
        verify(ossService, never()).deleteFile(any(Long.class));
        verify(attachMapper, never()).delete(any());
    }

    @Test
    void removeHappyPathChecksGateForEachKid() {
        when(attachMapper.selectList(any())).thenReturn(List.of());
        when(baseMapper.deleteByIds(anyList())).thenReturn(1);

        assertDoesNotThrow(() -> service.deleteWithValidByIds(List.of(9L, 10L), true));
        verify(gate).assertManageable(9L);
        verify(gate).assertManageable(10L);
        verify(vectorStoreService).removeById("9", null);
        verify(vectorStoreService).removeById("10", null);
    }
}
