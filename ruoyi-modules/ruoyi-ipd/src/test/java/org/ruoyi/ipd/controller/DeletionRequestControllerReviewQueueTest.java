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
import org.ruoyi.ipd.service.IDeletionRequestService;

import org.ruoyi.ipd.service.DeletionRequestServiceImpl;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-1（R25 真白屏修复）：「待我审核列表」{@code GET /api/v1/deletion-requests/review-queue}
 * 单测，验证 controller→service 路由 + actor 角色分流。{@code @Tag("dev")} 必须。
 *
 * <p>覆盖 4 个维度：
 * <ol>
 *   <li>{@code GROUP_LEADER} 角色 → service.listForReview(actor) → 包络 code=0 + data 完整</li>
 *   <li>{@code SUPER_ADMIN} 角色 → service.listForReview(actor) → 包络 code=0 + data 完整</li>
 *   <li>{@code MARKET_PM} 不持审核权 → service 仍被调（service 内返回空集），端点包络 code=0 + 空 data</li>
 *   <li>未登录 → IpdPermissionException 透传，不调 service</li>
 * </ol>
 *
 * <p>角色→状态分流在 {@link IDeletionRequestService#listForReview} 内部做，本端点只负责
 * 透传 actor；service 单测（DeletionRequestServiceListForReviewTest 等）独立覆盖
 * 分流逻辑，本类仅校验 controller 不丢 actor、不绕鉴权。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DeletionRequestControllerReviewQueueTest {

    @Mock
    private DeletionArchiveService archiveService;
    @Mock
    private IDeletionRequestService deletionRequestService;
    @Mock
    private IpdPermission ipdPermission;

    @InjectMocks
    private DeletionRequestController controller;

    @Test
    @DisplayName("P1-1 #1：GROUP_LEADER 走 review-queue → listForReview(actor) → code=0 + 非空 data")
    void reviewQueue_asGroupLeader_passesActorToService() {
        IpdActor actor = new IpdActor(9001L, "leader-lee", "GROUP_LEADER", 7L);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        DeletionRequest r = new DeletionRequest();
        r.setId(11L);
        r.setEntityType("products");
        r.setEntityId(33L);
        r.setStatus(DeletionRequestServiceImpl.ST_LEADER_REVIEW);
        when(deletionRequestService.listForReview(actor)).thenReturn(List.of(r));

        ApiV1Response<List<DeletionRequest>> resp = controller.reviewQueue();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getStatus()).isEqualTo(DeletionRequestServiceImpl.ST_LEADER_REVIEW);

        // 验证 actor 完整透传（id/name/role/groupId 都不丢；防止 controller 漏字段导致 service 分流失败）
        ArgumentCaptor<IpdActor> actorCaptor = ArgumentCaptor.forClass(IpdActor.class);
        verify(deletionRequestService).listForReview(actorCaptor.capture());
        IpdActor passed = actorCaptor.getValue();
        assertThat(passed.id()).isEqualTo(9001L);
        assertThat(passed.role()).isEqualTo("GROUP_LEADER");
        assertThat(passed.groupId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("P1-1 #2：SUPER_ADMIN 走 review-queue → listForReview(actor) → code=0 + 非空 data")
    void reviewQueue_asSuperAdmin_passesActorToService() {
        IpdActor actor = new IpdActor(9002L, "admin-anna", "SUPER_ADMIN", null);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        DeletionRequest r = new DeletionRequest();
        r.setId(22L);
        r.setEntityType("projects");
        r.setEntityId(77L);
        r.setStatus(DeletionRequestServiceImpl.ST_ADMIN_REVIEW);
        when(deletionRequestService.listForReview(actor)).thenReturn(List.of(r));

        ApiV1Response<List<DeletionRequest>> resp = controller.reviewQueue();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getStatus()).isEqualTo(DeletionRequestServiceImpl.ST_ADMIN_REVIEW);

        ArgumentCaptor<IpdActor> actorCaptor = ArgumentCaptor.forClass(IpdActor.class);
        verify(deletionRequestService).listForReview(actorCaptor.capture());
        assertThat(actorCaptor.getValue().role()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("P1-1 #3：MARKET_PM 不持审核权 → service 仍被调（service 内返空集），端点 code=0 + 空 data")
    void reviewQueue_asMarketPm_serviceReturnsEmptyEnvelope() {
        IpdActor actor = new IpdActor(9003L, "pm-pat", "MARKET_PM", 5L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        // service 内部按角色返空集（与 controller 无关）
        when(deletionRequestService.listForReview(actor)).thenReturn(List.of());

        ApiV1Response<List<DeletionRequest>> resp = controller.reviewQueue();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isEmpty();
    }

    @Test
    @DisplayName("P1-1 #4：未登录 → IpdPermissionException 透传，不调 service（鉴权前置）")
    void reviewQueue_unauthenticated_propagatesIpdPermissionException() {
        org.ruoyi.ipd.security.IpdPermissionException denied =
            new org.ruoyi.ipd.security.IpdPermissionException(403,
                org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        when(ipdPermission.requireInternal()).thenThrow(denied);

        try {
            controller.reviewQueue();
            org.junit.jupiter.api.Assertions.fail("expected IpdPermissionException to propagate");
        } catch (org.ruoyi.ipd.security.IpdPermissionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        }
        // 鉴权失败 → 不能调到业务 service（防「绕过鉴权仍走 service」侧信道）
        verify(deletionRequestService, org.mockito.Mockito.never()).listForReview(org.mockito.ArgumentMatchers.any());
    }
}
