package org.ruoyi.ipd.service;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.oss.exception.OssException;
import org.ruoyi.ipd.domain.Deliverable;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.system.service.ISysOssService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对象存储失败时，未提交的下载响应要清掉附件头，异常继续抛出。
 */
@Tag("dev")
class DeliverableDownloadEnvelopeTest {

    private static final IpdActor ADMIN = new IpdActor(900101L, "ipd-admin", "SUPER_ADMIN", null);

    @Test
    @DisplayName("存储失败且响应未提交时 reset，异常不吞掉")
    void resetsUncommittedResponse() throws Exception {
        DeliverableMapper deliverables = mock(DeliverableMapper.class);
        ISysOssService oss = mock(ISysOssService.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        Deliverable row = new Deliverable();
        row.setId(7L);
        row.setProjectId(9140005L);
        row.setOssId(8L);
        when(deliverables.selectById(7L)).thenReturn(row);
        when(response.isCommitted()).thenReturn(false);
        doThrow(new OssException("connection refused")).when(oss).download(8L, response);
        DeliverableService service = new DeliverableService(
            deliverables, mock(StageActionMapper.class), mock(ProjectMapper.class),
            mock(ProjectMemberMapper.class), oss, mock(IAuditLogService.class));

        assertThatThrownBy(() -> service.download(7L, ADMIN, response))
            .isInstanceOf(OssException.class);
        verify(response).reset();
    }

    @Test
    @DisplayName("响应已提交时不再 reset")
    void leavesCommittedResponse() throws Exception {
        DeliverableMapper deliverables = mock(DeliverableMapper.class);
        ISysOssService oss = mock(ISysOssService.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        Deliverable row = new Deliverable();
        row.setId(7L);
        row.setProjectId(9140005L);
        row.setOssId(8L);
        when(deliverables.selectById(7L)).thenReturn(row);
        when(response.isCommitted()).thenReturn(true);
        doThrow(new OssException("connection refused")).when(oss).download(8L, response);
        DeliverableService service = new DeliverableService(
            deliverables, mock(StageActionMapper.class), mock(ProjectMapper.class),
            mock(ProjectMemberMapper.class), oss, mock(IAuditLogService.class));

        assertThatThrownBy(() -> service.download(7L, ADMIN, response))
            .isInstanceOf(OssException.class);
        verify(response, never()).reset();
    }
}
