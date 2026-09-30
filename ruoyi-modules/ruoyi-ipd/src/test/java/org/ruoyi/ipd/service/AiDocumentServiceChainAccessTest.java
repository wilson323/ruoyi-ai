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
        when(access.requireVisible(actor, 100L)).thenReturn("000000");
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
        when(access.requireVisible(actor, 100L))
            .thenReturn("000000")
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见"));

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
