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
import org.ruoyi.ipd.domain.CoefficientChangeRequest;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.CoefficientChangeService;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-2：{@code GET /api/v1/coefficient-change-requests/{id}}（按 ID 详情）单测，
 * 验证 controller→service 路由 + actor.id() 透传。{@code @Tag("dev")} 必须。
 *
 * <p>覆盖 3 个维度：
 * <ol>
 *   <li>正常路径：mock {@code requireInternal} 返回 actor → service.getByIdForReview 收到
 *       {@code (id, currentPersonId)} → 透传返回实体 → 包络 code=0 + data 完整</li>
 *   <li>service 抛 ServiceException（申请不存在）→ controller 不接 → 异常透传</li>
 *   <li>鉴权失败：mock requireInternal 抛 IpdPermissionException → 端点不调 service</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CoefficientChangeControllerGetTest {

    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private CoefficientChangeService service;

    @InjectMocks
    private CoefficientChangeController controller;

    @Test
    @DisplayName("P1-2 #1：getById(8001) 透传 actor.id() 到 service，返回包络 code=0 + data 完整")
    void getById_passesIdAndActorId_andReturnsEnvelope() {
        IpdActor actor = new IpdActor(7101L, "carol", "GROUP_LEADER", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        CoefficientChangeRequest entity = new CoefficientChangeRequest();
        entity.setId(8001L);
        entity.setProjectId(100L);
        entity.setStatus(CoefficientChangeRequest.ST_CONFIRMED);
        entity.setProposedCoefficient(new BigDecimal("1.35"));
        entity.setReason("S 级上调至 1.35");
        when(service.getByIdForReview(eq(8001L), any())).thenReturn(entity);

        ApiV1Response<CoefficientChangeRequest> resp = controller.getById(8001L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isNotNull();
        assertThat(resp.getData().getId()).isEqualTo(8001L);
        assertThat(resp.getData().getStatus()).isEqualTo(CoefficientChangeRequest.ST_CONFIRMED);
        assertThat(resp.getData().getProposedCoefficient()).isEqualByComparingTo(new BigDecimal("1.35"));
        assertThat(resp.getData().getReason()).isEqualTo("S 级上调至 1.35");

        // 验证 (id, currentPersonId) 透传到 service
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> personCaptor = ArgumentCaptor.forClass(String.class);
        verify(service).getByIdForReview(idCaptor.capture(), personCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(8001L);
        assertThat(personCaptor.getValue()).isEqualTo("7101");
    }

    @Test
    @DisplayName("P1-2 #2：service 抛 ServiceException「系数定值申请不存在」 → controller 不接，异常透传")
    void getById_serviceThrowsNotFound_propagatesException() {
        IpdActor actor = new IpdActor(7102L, "dave", "SUPER_ADMIN", null);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(service.getByIdForReview(eq(9999L), any()))
            .thenThrow(new org.ruoyi.common.core.exception.ServiceException("系数定值申请不存在: 9999"));

        try {
            controller.getById(9999L);
            org.junit.jupiter.api.Assertions.fail("expected ServiceException to propagate");
        } catch (org.ruoyi.common.core.exception.ServiceException ex) {
            assertThat(ex.getMessage()).contains("系数定值申请不存在");
        }
    }

    @Test
    @DisplayName("P1-2 #3：未登录/非内部角色 → IpdPermissionException 透传，不调 service（鉴权前置）")
    void getById_unauthenticated_propagatesIpdPermissionException() {
        IpdPermissionException denied = new IpdPermissionException(403,
            org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        when(ipdPermission.requireInternal()).thenThrow(denied);

        try {
            controller.getById(8001L);
            org.junit.jupiter.api.Assertions.fail("expected IpdPermissionException to propagate");
        } catch (IpdPermissionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        }
        verify(service, never()).getByIdForReview(any(), any());
    }
}
