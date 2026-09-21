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
import org.ruoyi.ipd.domain.LaunchDateChangeRequest;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.LaunchDateChangeService;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-2：{@code GET /api/v1/launch-date-change-requests/{id}}（按 ID 详情）单测。
 * {@code @Tag("dev")} 必须。
 *
 * <p>覆盖 3 个维度：
 * <ol>
 *   <li>正常路径：mock requireInternal 返回 actor → service.getByIdForReview 收到
 *       {@code (id, currentPersonId)} → 透传返回实体 → 包络 code=0 + data 完整</li>
 *   <li>service 抛 ServiceException（申请不存在）→ 异常透传</li>
 *   <li>鉴权失败：mock requireInternal 抛 IpdPermissionException → 端点不调 service</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class LaunchDateChangeControllerGetTest {

    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private LaunchDateChangeService service;

    @InjectMocks
    private LaunchDateChangeController controller;

    @Test
    @DisplayName("P1-2 #1：getById(8201) 透传 actor.id() 到 service，返回包络 code=0 + data 完整")
    void getById_passesIdAndActorId_andReturnsEnvelope() {
        IpdActor actor = new IpdActor(7301L, "grace", "MARKET_PM", 200L);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        LaunchDateChangeRequest entity = new LaunchDateChangeRequest();
        entity.setId(8201L);
        entity.setProjectId(200L);
        entity.setStatus(LaunchDateChangeRequest.ST_CONFIRMED);
        entity.setProposedLaunchDate(new Date());
        entity.setDecision("APPROVE");
        entity.setReason("渠道节奏调整至下季度");
        when(service.getByIdForReview(eq(8201L), any())).thenReturn(entity);

        ApiV1Response<LaunchDateChangeRequest> resp = controller.getById(8201L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isNotNull();
        assertThat(resp.getData().getId()).isEqualTo(8201L);
        assertThat(resp.getData().getStatus()).isEqualTo(LaunchDateChangeRequest.ST_CONFIRMED);
        assertThat(resp.getData().getDecision()).isEqualTo("APPROVE");
        assertThat(resp.getData().getReason()).isEqualTo("渠道节奏调整至下季度");

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> personCaptor = ArgumentCaptor.forClass(String.class);
        verify(service).getByIdForReview(idCaptor.capture(), personCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(8201L);
        assertThat(personCaptor.getValue()).isEqualTo("7301");
    }

    @Test
    @DisplayName("P1-2 #2：service 抛 ServiceException「上市日期变更申请不存在」 → controller 不接，异常透传")
    void getById_serviceThrowsNotFound_propagatesException() {
        IpdActor actor = new IpdActor(7302L, "henry", "SUPER_ADMIN", null);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(service.getByIdForReview(eq(9999L), any()))
            .thenThrow(new org.ruoyi.common.core.exception.ServiceException("上市日期变更申请不存在: 9999"));

        try {
            controller.getById(9999L);
            org.junit.jupiter.api.Assertions.fail("expected ServiceException to propagate");
        } catch (org.ruoyi.common.core.exception.ServiceException ex) {
            assertThat(ex.getMessage()).contains("上市日期变更申请不存在");
        }
    }

    @Test
    @DisplayName("P1-2 #3：未登录/非内部角色 → IpdPermissionException 透传，不调 service（鉴权前置）")
    void getById_unauthenticated_propagatesIpdPermissionException() {
        IpdPermissionException denied = new IpdPermissionException(403,
            org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        when(ipdPermission.requireInternal()).thenThrow(denied);

        try {
            controller.getById(8201L);
            org.junit.jupiter.api.Assertions.fail("expected IpdPermissionException to propagate");
        } catch (IpdPermissionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        }
        verify(service, never()).getByIdForReview(any(), any());
    }
}
