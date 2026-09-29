package org.ruoyi.service.knowledge.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.core.service.OssService;
import org.ruoyi.domain.bo.knowledge.KnowledgeInfoUploadBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.domain.vo.knowledge.KnowledgeReparseVo;
import org.ruoyi.factory.ResourceLoaderFactory;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.service.vector.VectorStoreService;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D 口收敛调用验证：/system/attach 的 upload/reparse 按管理面判据（assertManageable）
 * 过 Gate。判据本体在 {@link UserIdShareKnowledgeAccessGateTest} 的 assertManageable 组。
 * <p>
 * upload 拒绝的 fail-fast 语义是重点：不读文件流（getInputStream）、不查重（exists）、
 * 不触 OSS（uploadFile）、不落 attach 表（insert）——一切副作用之前拒绝。
 * mock 合法性：OssDTO 取真实上传返回字段（ossId/originalName/fileSuffix），
 * attach 状态走 KnowledgeAttachStatus.WAITING（真实写入路径产生的组合）。
 * 纯 mock 用例，未覆盖 DDL 合法性。
 */
@Tag("dev")
class KnowledgeAttachManageAccessTest {

    private final KnowledgeAttachMapper baseMapper = mock(KnowledgeAttachMapper.class);
    private final KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
    private final OssService ossService = mock(OssService.class);
    private final KnowledgeAttachServiceImpl service = new KnowledgeAttachServiceImpl(
        baseMapper,
        mock(IKnowledgeInfoService.class),
        mock(KnowledgeFragmentMapper.class),
        mock(IChatModelService.class),
        mock(ResourceLoaderFactory.class),
        mock(VectorStoreService.class),
        ossService,
        mock(KnowledgeRetrievalService.class),
        gate);

    // ---------- POST /system/attach/upload → upload ----------

    @Test
    void uploadDenialFailsFastBeforeAnySideEffect() throws java.io.IOException {
        doThrow(new ServiceException("仅库归属人可管理该知识库 kid=9")).when(gate).assertManageable(9L);
        MultipartFile file = mock(MultipartFile.class);
        KnowledgeInfoUploadBo bo = new KnowledgeInfoUploadBo();
        bo.setKnowledgeId(9L);
        bo.setFile(file);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.upload(bo));
        assertTrue(ex.getMessage().contains("kid=9"));
        verify(file, never()).getInputStream();
        verify(baseMapper, never()).exists(any());
        verify(ossService, never()).uploadFile(any(MultipartFile.class));
        verify(baseMapper, never()).insert(any(KnowledgeAttach.class));
    }

    @Test
    void uploadHappyPathStoresAttachRowAfterGate() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(baseMapper.exists(any())).thenReturn(false);
        OssDTO ossDTO = new OssDTO();
        ossDTO.setOssId(1L);
        ossDTO.setOriginalName("a.txt");
        ossDTO.setFileSuffix("txt");
        when(ossService.uploadFile(any(MultipartFile.class))).thenReturn(ossDTO);
        when(baseMapper.insert(any(KnowledgeAttach.class))).thenReturn(1);
        KnowledgeInfoUploadBo bo = new KnowledgeInfoUploadBo();
        bo.setKnowledgeId(9L);
        bo.setFile(file);
        bo.setAutoParse(null);

        assertDoesNotThrow(() -> service.upload(bo));
        verify(gate).assertManageable(9L);
        verify(baseMapper).insert(any(KnowledgeAttach.class));
    }

    // ---------- POST /system/attach/reparse/knowledge/{knowledgeId} → reparseKnowledge ----------

    @Test
    void reparseDenialPropagatesBeforeQueryingAttachments() {
        doThrow(new ServiceException("仅库归属人可管理该知识库 kid=9")).when(gate).assertManageable(9L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.reparseKnowledge(9L));
        assertTrue(ex.getMessage().contains("kid=9"));
        verify(baseMapper, never()).selectList(any());
    }

    @Test
    void reparseHappyPathChecksGateAndCounts() {
        when(baseMapper.selectList(any())).thenReturn(List.of());
        // reparseKnowledge 经 SpringUtils.getBean 取自代理（确保 @Async 生效）；
        // getBean 声明在 hutool SpringUtil（SpringUtils 继承之），mock 需落在父类
        try (var mockedSpring = mockStatic(cn.hutool.extra.spring.SpringUtil.class)) {
            mockedSpring.when(() -> cn.hutool.extra.spring.SpringUtil
                    .getBean(org.ruoyi.service.knowledge.IKnowledgeAttachService.class))
                .thenReturn(mock(org.ruoyi.service.knowledge.IKnowledgeAttachService.class));

            KnowledgeReparseVo vo = service.reparseKnowledge(9L);
            verify(gate).assertManageable(9L);
            assertEquals(0, vo.submitted());
            assertEquals(0, vo.skipped());
            assertEquals(0, vo.total());
        }
    }
}
