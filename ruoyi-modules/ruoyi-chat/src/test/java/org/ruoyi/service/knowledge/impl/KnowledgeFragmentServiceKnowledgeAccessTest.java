package org.ruoyi.service.knowledge.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.domain.bo.knowledge.KnowledgeFragmentBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B 口收敛调用验证：/system/fragment 的 list/export/{id} 按读面判据过 Gate。
 * <p>
 * 判据本体在 {@link UserIdShareKnowledgeAccessGateTest}（owned/share/不存在/未登录），
 * 本类只验证三个收敛口确实把 kid 送进 Gate、拒绝向上传播且不触库——
 * mock Gate 断言「被调/拒绝传播」模式（对齐 ChatServiceFacadeKnowledgeAccessTest 先例）。
 * <p>
 * mock 合法性（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md 精神）：
 * 片段的 knowledgeId 为库主键外键语义（真实写入路径均来自已存在的库），
 * 不造「片段存在但 knowledgeId=null」之外的真库不可能组合；该防御分支单列为负例。
 * 纯 mock 用例，未覆盖 DDL 合法性。
 */
@Tag("dev")
class KnowledgeFragmentServiceKnowledgeAccessTest {

    private final KnowledgeFragmentMapper baseMapper = mock(KnowledgeFragmentMapper.class);
    private final KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
    private final KnowledgeFragmentServiceImpl service = new KnowledgeFragmentServiceImpl(
        baseMapper,
        mock(IKnowledgeInfoService.class),
        gate,
        mock(IChatModelService.class),
        mock(KnowledgeRetrievalService.class),
        mock(VectorStoreService.class));

    private static KnowledgeFragmentVo fragment(Long id, Long knowledgeId) {
        KnowledgeFragmentVo vo = new KnowledgeFragmentVo();
        vo.setId(id);
        vo.setKnowledgeId(knowledgeId);
        return vo;
    }

    // ---------- /system/fragment/list（queryPageList） ----------

    @Test
    void listPageWithKnowledgeIdRoutesKidThroughGate() {
        when(baseMapper.selectVoPage(any(), any())).thenReturn(new Page<>());
        KnowledgeFragmentBo bo = new KnowledgeFragmentBo();
        bo.setKnowledgeId(9L);

        assertDoesNotThrow(() -> service.queryPageList(bo, new PageQuery(10, 1)));
        verify(gate).checkRetrievalAccess(9L);
    }

    @Test
    void listPageGateDenialPropagatesBeforeQuerying() {
        doThrow(new ServiceException("无权访问该知识库 kid=9")).when(gate).checkRetrievalAccess(9L);
        KnowledgeFragmentBo bo = new KnowledgeFragmentBo();
        bo.setKnowledgeId(9L);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.queryPageList(bo, new PageQuery(10, 1)));
        assertTrue(ex.getMessage().contains("kid=9"));
        verify(baseMapper, never()).selectVoPage(any(), any());
    }

    @Test
    void listPageWithoutKnowledgeIdSkipsGate() {
        // 当前契约固化：kid 为空的裸列查询不过 Gate（该面登记为 B2 片段列表 S2 化待收窄项，
        // 用例在此钉住行为，防止后续改动悄悄改变该边界而无登记）
        when(baseMapper.selectVoPage(any(), any())).thenReturn(new Page<>());

        assertDoesNotThrow(() -> service.queryPageList(new KnowledgeFragmentBo(), new PageQuery(10, 1)));
        verify(gate, never()).checkRetrievalAccess(any());
    }

    // ---------- /system/fragment/export（queryList） ----------

    @Test
    void exportListSharesSameGateContract() {
        when(baseMapper.selectVoList(any())).thenReturn(java.util.List.of());
        KnowledgeFragmentBo bo = new KnowledgeFragmentBo();
        bo.setKnowledgeId(9L);

        assertDoesNotThrow(() -> service.queryList(bo));
        verify(gate).checkRetrievalAccess(9L);
    }

    @Test
    void exportListGateDenialPropagatesBeforeQuerying() {
        doThrow(new ServiceException("无权访问该知识库 kid=9")).when(gate).checkRetrievalAccess(9L);
        KnowledgeFragmentBo bo = new KnowledgeFragmentBo();
        bo.setKnowledgeId(9L);

        assertThrows(ServiceException.class, () -> service.queryList(bo));
        verify(baseMapper, never()).selectVoList(any());
    }

    // ---------- /system/fragment/{id}（queryById，片段主键直读） ----------

    @Test
    void queryByIdRoutesFragmentKidThroughGate() {
        when(baseMapper.selectVoById(5L)).thenReturn(fragment(5L, 9L));

        KnowledgeFragmentVo vo = service.queryById(5L);
        assertNotNull(vo);
        verify(gate).checkRetrievalAccess(9L);
    }

    @Test
    void queryByIdGateDenialPropagates() {
        when(baseMapper.selectVoById(5L)).thenReturn(fragment(5L, 9L));
        doThrow(new ServiceException("无权访问该知识库 kid=9")).when(gate).checkRetrievalAccess(9L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.queryById(5L));
        assertTrue(ex.getMessage().contains("kid=9"));
    }

    @Test
    void queryByIdMissingFragmentSkipsGate() {
        when(baseMapper.selectVoById(404L)).thenReturn(null);

        assertNull(service.queryById(404L));
        verify(gate, never()).checkRetrievalAccess(any());
    }

    @Test
    void queryByIdFragmentWithoutKidIsRejectedFailClosed() {
        // 防御分支：无归属库的片段（真库不可能，外键语义）按 fail-closed 拒绝，不外泄内容
        when(baseMapper.selectVoById(6L)).thenReturn(fragment(6L, null));
        doThrow(new ServiceException("知识库ID为空，无权访问该知识库")).when(gate).checkRetrievalAccess(null);

        assertThrows(ServiceException.class, () -> service.queryById(6L));
        verify(gate).checkRetrievalAccess(null);
    }
}
