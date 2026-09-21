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
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.DeletionArchiveService;
import org.ruoyi.ipd.service.DeletionRequestService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-1（R25 真白屏修复）：「我的申请列表」{@code GET /api/v1/deletion-requests/my-requests}
 * 单测，验证 controller→service 路由 + actor.id() 透传。{@code @Tag("dev")} 必须，否则被
 * Surefire 静默跳过（假绿陷阱）。
 *
 * <p>覆盖 3 个维度：
 * <ol>
 *   <li>正常路径：mock {@code requireInternal} 返回 actor → service.listByApplicant 收到
 *       {@code actor.id()} → 透传返回列表 → 包络 code=0 + data 完整</li>
 *   <li>空列表：申请人无申请 → 返回空数组，包络仍 code=0</li>
 *   <li>拒绝越权：mock requireInternal 抛 {@code IpdPermissionException} → 端点不调 service</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DeletionRequestControllerMyRequestsTest {

    @Mock
    private DeletionArchiveService archiveService;
    @Mock
    private DeletionRequestService deletionRequestService;
    @Mock
    private IpdPermission ipdPermission;

    @InjectMocks
    private DeletionRequestController controller;

    @Test
    @DisplayName("P1-1 #1：my-requests 透传 actor.id() 到 service.listByApplicant，返回包络 code=0 + data 完整")
    void myRequests_passesActorId_andReturnsEnvelope() {
        IpdActor actor = new IpdActor(8001L, "alice", "MARKET_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        DeletionRequest r1 = new DeletionRequest();
        r1.setId(1L);
        r1.setEntityType("products");
        r1.setEntityId(42L);
        r1.setStatus(DeletionRequestService.ST_LEADER_REVIEW);
        DeletionRequest r2 = new DeletionRequest();
        r2.setId(2L);
        r2.setEntityType("projects");
        r2.setEntityId(99L);
        r2.setStatus(DeletionRequestService.ST_DELETED);
        when(deletionRequestService.listByApplicant(8001L)).thenReturn(List.of(r1, r2));

        ApiV1Response<List<DeletionRequest>> resp = controller.myRequests();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).hasSize(2);
        assertThat(resp.getData().get(0).getId()).isEqualTo(1L);
        assertThat(resp.getData().get(1).getStatus()).isEqualTo(DeletionRequestService.ST_DELETED);

        // 验证 actor.id 透传到 service（不允许漏 actor.id → null 透传）
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(deletionRequestService).listByApplicant(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(8001L);
    }

    @Test
    @DisplayName("P1-1 #2：申请人无任何申请 → 返回空数组 + code=0")
    void myRequests_emptyApplicant_returnsEmptyList() {
        IpdActor actor = new IpdActor(8002L, "bob", "RD_PM", null);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(deletionRequestService.listByApplicant(8002L)).thenReturn(List.of());

        ApiV1Response<List<DeletionRequest>> resp = controller.myRequests();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isEmpty();
    }

    @Test
    @DisplayName("P1-1 #3：未登录/非内部角色 → IpdPermissionException 透传，不调 service（鉴权前置）")
    void myRequests_unauthenticated_propagatesIpdPermissionException() {
        org.ruoyi.ipd.security.IpdPermissionException denied =
            new org.ruoyi.ipd.security.IpdPermissionException(401,
                org.ruoyi.ipd.common.ApiV1ErrorCode.UNAUTHORIZED);
        when(ipdPermission.requireInternal()).thenThrow(denied);

        try {
            controller.myRequests();
            org.junit.jupiter.api.Assertions.fail("expected IpdPermissionException to propagate");
        } catch (org.ruoyi.ipd.security.IpdPermissionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.UNAUTHORIZED);
        }
        // 鉴权失败 → service 不能被调到（防止「绕过鉴权仍走业务逻辑」侧信道）
        verify(deletionRequestService, org.mockito.Mockito.never()).listByApplicant(org.mockito.ArgumentMatchers.any());
    }
}
