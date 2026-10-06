package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文档版本链必须属于同一项目和类型，diff 只能比较同链版本。 */
@Tag("dev")
class AiDocumentServiceChainAccessTest {

    private final AiDocumentMapper mapper = mock(AiDocumentMapper.class);
    private final AiDocumentService service = new AiDocumentService(mapper);

    @Test
    void indexMaintenanceUsesSameVersionAndRechecksRealProjectPermissionInsideCommitCallback() {
        AiDocument doc = document(10L, 100L, "PRD", null, 1);
        doc.setStatus("REVIEWED");
        when(mapper.selectById(10L)).thenReturn(doc);
        IpdActor actor = new IpdActor(9L, "alice", "MARKET_PM", 1L);
        IpdCopilotAccess access = mock(IpdCopilotAccess.class);
        service.setProjectAccess(access);
        ProjectService projectRead = mock(ProjectService.class);
        service.setProjectReadAccess(projectRead);
        when(access.requireVisible(actor, null)).thenReturn("000000");
        org.ruoyi.ipd.domain.Project project = new org.ruoyi.ipd.domain.Project();
        project.setId(100L);
        project.setTenantId("000000");
        when(projectRead.getVisibleById(100L, actor)).thenReturn(project)
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN));
        AiDocEmbeddingService embedding = mock(AiDocEmbeddingService.class);
        service.setDocEmbeddingService(embedding);
        org.mockito.Mockito.when(embedding.rebuildIndex(org.mockito.ArgumentMatchers.eq(doc), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        assertThatThrownBy(() -> service.rebuildIndexAuthorized(actor, 10L)).isInstanceOf(IpdBusinessException.class);
        verify(mapper, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(AiDocument.class));
    }

    @Test
    void historyRejectsCrossProjectParentLinkEvenWhenVersionNumbersAreConsecutive() {
        AiDocument root = document(10L, 100L, "PRD", null, 1);
        AiDocument foreignChild = document(11L, 200L, "PRD", 10L, 2);
        when(mapper.selectChain(10L)).thenReturn(List.of(root, foreignChild));

        assertThatThrownBy(() -> service.history(10L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
    }

    @Test
    void historyRejectsCrossTypeParentLinkInSameProject() {
        AiDocument root = document(10L, 100L, "PRD", null, 1);
        AiDocument wrongType = document(11L, 100L, "MRD", 10L, 2);
        when(mapper.selectChain(10L)).thenReturn(List.of(root, wrongType));

        assertThatThrownBy(() -> service.history(10L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
    }

    @Test
    void authorizedDiffRejectsVersionsFromDifferentChainsInSameProject() {
        AiDocument first = document(10L, 100L, "PRD", null, 1);
        IpdActor actor = new IpdActor(9L, "alice", "MARKET_PM", 1L);
        IpdCopilotAccess access = mock(IpdCopilotAccess.class);
        service.setProjectAccess(access);
        ProjectService projectRead = mock(ProjectService.class);
        service.setProjectReadAccess(projectRead);
        org.ruoyi.ipd.domain.Project project = new org.ruoyi.ipd.domain.Project();
        project.setTenantId("000000");
        when(access.requireVisible(actor, null)).thenReturn("000000");
        when(projectRead.getVisibleById(100L, actor)).thenReturn(project);
        when(mapper.selectChain(10L)).thenReturn(List.of(first));

        assertThatThrownBy(() -> service.diffAuthorized(10L, 10L, 20L, actor))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
    }

    @Test
    void diffRejectsVersionsFromDifferentChainsInSameProject() {
        AiDocument first = document(10L, 100L, "PRD", null, 1);
        AiDocument second = document(20L, 100L, "PRD", null, 1);
        when(mapper.selectById(10L)).thenReturn(first);
        when(mapper.selectById(20L)).thenReturn(second);
        when(mapper.selectChain(10L)).thenReturn(List.of(first));

        assertThatThrownBy(() -> service.diff(10L, 20L))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
    }

    @Test
    void generatedInsertRejectsMembershipRevokedAfterPreflight() {
        IpdActor actor = new IpdActor(9L, "alice", "MARKET_PM", 1L);
        IpdCopilotAccess access = mock(IpdCopilotAccess.class);
        service.setProjectAccess(access);
        ProjectService projectRead = mock(ProjectService.class);
        service.setProjectReadAccess(projectRead);
        when(access.requireVisible(actor, null)).thenReturn("000000");
        org.ruoyi.ipd.domain.Project project = new org.ruoyi.ipd.domain.Project();
        project.setId(100L);
        project.setTenantId("000000");
        when(projectRead.getVisibleById(100L, actor)).thenReturn(project)
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN));

        service.requireProjectVisible(actor, 100L);

        assertThatThrownBy(() -> service.createGeneratedAuthorized(actor, 100L, "PRD",
            "标题", "正文", "model", 10, 20))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(AiDocument.class));
    }

    private static AiDocument document(Long id, Long projectId, String docType,
                                       Long parentId, int versionNo) {
        AiDocument row = new AiDocument();
        row.setId(id);
        row.setProjectId(projectId);
        row.setDocType(docType);
        row.setParentVersionId(parentId);
        row.setVersionNo(versionNo);
        row.setStatus(AiDocumentService.STATUS_GENERATED);
        row.setTitle("标题");
        row.setContent("正文");
        row.setContentSha256("hash");
        return row;
    }
}
