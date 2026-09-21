package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.AiDocumentService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-3：{@code GET /api/v1/ai-documents?projectId=X}（按项目列 AI 文档）单测，
 * 验证 controller→service 路由 + actor.id() 透传。{@code @Tag("dev")} 必须，
 * 否则 Surefire 静默跳过（假绿陷阱）。
 *
 * <p>覆盖 3 个维度：
 * <ol>
 *   <li>正常路径：mock {@code requireInternal} 返回 actor → service.listByProject 收到
 *       {@code (projectId, currentPersonId)} → 透传返回列表 → 包络 code=0 + data 完整</li>
 *   <li>项目下无文档：service 返回空列表 → 包络 code=0 + data 为空列表（不是 null、不是 404）</li>
 *   <li>鉴权失败：mock requireInternal 抛 {@code IpdPermissionException(403, FORBIDDEN)} →
 *       端点不调 service（鉴权前置）</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AiDocumentControllerListByProjectTest {

    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private AiDocumentService aiDocumentService;

    @InjectMocks
    private AiDocumentController controller;

    @Test
    @DisplayName("P1-3 #1：listByProject(projectId=200) 透传 actor.id() 到 service，返回包络 code=0 + data 完整")
    void listByProject_passesProjectIdAndActorId_andReturnsEnvelope() {
        IpdActor actor = new IpdActor(9001L, "alice", "MARKET_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        AiDocument doc1 = new AiDocument();
        doc1.setId(1001L);
        doc1.setProjectId(200L);
        doc1.setDocType("PRD");
        doc1.setTitle("PRD 初稿");
        doc1.setVersionNo(1);
        when(aiDocumentService.listByProject(eq(200L), any())).thenReturn(List.of(doc1));

        ApiV1Response<List<AiDocument>> resp = controller.listByProject(200L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getId()).isEqualTo(1001L);
        assertThat(resp.getData().get(0).getProjectId()).isEqualTo(200L);
        assertThat(resp.getData().get(0).getTitle()).isEqualTo("PRD 初稿");
        assertThat(resp.getData().get(0).getVersionNo()).isEqualTo(1);

        // 验证 (projectId, currentPersonId) 透传到 service（currentPersonId = String.valueOf(actor.id())）
        ArgumentCaptor<Long> projectCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> personCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiDocumentService).listByProject(projectCaptor.capture(), personCaptor.capture());
        assertThat(projectCaptor.getValue()).isEqualTo(200L);
        assertThat(personCaptor.getValue()).isEqualTo("9001");
    }

    @Test
    @DisplayName("P1-3 #2：listByProject 项目下无文档 → service 返回空列表 → 包络 code=0 + data 为空列表")
    void listByProject_emptyProject_returnsEmptyList() {
        IpdActor actor = new IpdActor(9002L, "bob", "RD_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(aiDocumentService.listByProject(eq(999L), any())).thenReturn(List.of());

        ApiV1Response<List<AiDocument>> resp = controller.listByProject(999L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isNotNull().isEmpty();

        ArgumentCaptor<Long> projectCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> personCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiDocumentService).listByProject(projectCaptor.capture(), personCaptor.capture());
        assertThat(projectCaptor.getValue()).isEqualTo(999L);
        assertThat(personCaptor.getValue()).isEqualTo("9002");
    }

    @Test
    @DisplayName("P1-3 #3：未登录/非内部角色 → IpdPermissionException(403, FORBIDDEN) 透传，不调 service（鉴权前置）")
    void listByProject_unauthorized_propagatesIpdPermissionException() {
        IpdPermissionException denied = new IpdPermissionException(403,
            org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        when(ipdPermission.requireInternal()).thenThrow(denied);

        try {
            controller.listByProject(200L);
            org.junit.jupiter.api.Assertions.fail("expected IpdPermissionException to propagate");
        } catch (IpdPermissionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        }
        // 鉴权失败 → service 不能被调到（防止「绕过鉴权仍走业务逻辑」侧信道）
        verify(aiDocumentService, never()).listByProject(any(), any());
    }
}
