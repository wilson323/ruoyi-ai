package org.ruoyi.service.knowledge.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.mybatis.core.page.PageQuery;
import org.ruoyi.common.mybatis.core.page.TableDataInfo;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.bo.knowledge.KnowledgeFragmentBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.KnowledgeFragmentVo;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
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
 * B2 S2 化（2026-09-28）：kid 为空的裸列查询已收窄到会话可见库（判据单源复用
 * Info.queryList 同构判据），未登录 fail-closed 空结果——原「裸列不过 Gate」
 * 契约随之翻转，用例见下「裸列收窄」节。
 * <p>
 * mock 合法性（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md 精神）：
 * 片段的 knowledgeId 为库主键外键语义（真实写入路径均来自已存在的库），
 * 不造「片段存在但 knowledgeId=null」之外的真库不可能组合；该防御分支单列为负例。
 * 纯 mock 用例，未覆盖 DDL 合法性。
 */
@Tag("dev")
class KnowledgeFragmentServiceKnowledgeAccessTest {

    /** S2 收窄的 wrapper 会挂 knowledge_id IN（lambda 列解析需列缓存，同 WrapperTest 先例） */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfigUtils.setGlobalConfig(configuration, GlobalConfigUtils.defaults());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), KnowledgeFragment.class);
    }

    private final KnowledgeFragmentMapper baseMapper = mock(KnowledgeFragmentMapper.class);
    private final KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
    private final IKnowledgeInfoService knowledgeInfoService = mock(IKnowledgeInfoService.class);
    private final KnowledgeFragmentServiceImpl service = new KnowledgeFragmentServiceImpl(
        baseMapper,
        knowledgeInfoService,
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
    void listPageWithoutKnowledgeIdAnonymousYieldsEmptyFailClosed() {
        // B2 S2 化翻转：kid 空的裸列查询不再是「无归属谓词放行」——未登录 fail-closed
        // 直接空结果，不触片段表（此前全租户片段明文列就此关闭）
        when(baseMapper.selectVoPage(any(), any())).thenReturn(new Page<>());

        TableDataInfo<KnowledgeFragmentVo> page;
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(null);
            page = service.queryPageList(new KnowledgeFragmentBo(), new PageQuery(10, 1));
        }
        assertTrue(page.getRows().isEmpty(), "匿名裸列必须空结果");
        verify(baseMapper, never()).selectVoPage(any(), any());
        verify(gate, never()).checkRetrievalAccess(any());
    }

    @Test
    void listPageWithoutKnowledgeIdNarrowsToVisibleKnowledgeUnderLogin() {
        // 登录态：裸列收窄到「会话可见库集合」（判据单源 Info.queryList），wrapper 挂
        // knowledge_id IN（可见库 id 为绑定参数）；不经单库 Gate——S2 化只收窄可见面，
        // 不改变「带 kid 走 Gate」契约
        when(baseMapper.selectVoPage(any(), any())).thenReturn(new Page<>());
        KnowledgeInfoVo mine = new KnowledgeInfoVo();
        mine.setId(9L);
        KnowledgeInfoVo shared = new KnowledgeInfoVo();
        shared.setId(11L);
        when(knowledgeInfoService.queryList(any())).thenReturn(java.util.List.of(mine, shared));

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            assertDoesNotThrow(() -> service.queryPageList(new KnowledgeFragmentBo(), new PageQuery(10, 1)));
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeFragment>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectVoPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("knowledge_id") && sql.contains("IN"), "裸列须挂可见库 IN 条件，实际=" + sql);
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue(9L) && params.containsValue(11L),
            "IN 绑定值须为可见库 id 集合，实际=" + params);
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
    void exportBareListNarrowsWithSameS2Contract() {
        // export 与分页同构收窄：裸列导出不再输出全租户片段明文（Excel 导出面同 S2 判据）
        when(baseMapper.selectVoList(any())).thenReturn(java.util.List.of());
        KnowledgeInfoVo visible = new KnowledgeInfoVo();
        visible.setId(9L);
        when(knowledgeInfoService.queryList(any())).thenReturn(java.util.List.of(visible));

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            assertDoesNotThrow(() -> service.queryList(new KnowledgeFragmentBo()));
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeFragment>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(baseMapper).selectVoList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("knowledge_id"),
            "导出裸列同样须挂可见库 IN 条件，实际=" + captor.getValue().getSqlSegment());
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
