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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-2：{@code GET /api/v1/launch-date-change-requests}（按 projectId 列表）单测。
 * {@code @Tag("dev")} 必须。
 *
 * <p>覆盖 3 个维度：
 * <ol>
 *   <li>正常路径：mock requireInternal 返回 actor → service.listByProject 收到
 *       {@code (projectId, currentPersonId)} → 透传返回列表 → 包络 code=0 + data 完整</li>
 *   <li>空列表：项目无任何变更单 → 返回空数组，包络仍 code=0</li>
 *   <li>鉴权失败：mock requireInternal 抛 IpdPermissionException → 端点不调 service</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class LaunchDateChangeControllerListTest {

    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private LaunchDateChangeService service;

    @InjectMocks
    private LaunchDateChangeController controller;

    @Test
    @DisplayName("P1-2 #1：listByProject(projectId=200) 透传 actor.id() 到 service，返回包络 code=0 + data 完整")
    void listByProject_passesProjectIdAndActorId_andReturnsEnvelope() {
        IpdActor actor = new IpdActor(7201L, "ellen", "MARKET_PM", 200L);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        LaunchDateChangeRequest r1 = new LaunchDateChangeRequest();
        r1.setId(201L);
        r1.setProjectId(200L);
        r1.setStatus(LaunchDateChangeRequest.ST_PENDING_SECOND);
        r1.setProposedLaunchDate(new Date());
        when(service.listByProject(eq(200L), any())).thenReturn(List.of(r1));

        ApiV1Response<List<LaunchDateChangeRequest>> resp = controller.listByProject(200L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getId()).isEqualTo(201L);
        assertThat(resp.getData().get(0).getStatus()).isEqualTo(LaunchDateChangeRequest.ST_PENDING_SECOND);

        ArgumentCaptor<Long> projectCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> personCaptor = ArgumentCaptor.forClass(String.class);
        verify(service).listByProject(projectCaptor.capture(), personCaptor.capture());
        assertThat(projectCaptor.getValue()).isEqualTo(200L);
        assertThat(personCaptor.getValue()).isEqualTo("7201");
    }

    @Test
    @DisplayName("P1-2 #2：项目无任何上市日期变更单 → 返回空数组 + code=0")
    void listByProject_emptyProject_returnsEmptyList() {
        IpdActor actor = new IpdActor(7202L, "frank", "RD_PM", 200L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(service.listByProject(eq(200L), any())).thenReturn(List.of());

        ApiV1Response<List<LaunchDateChangeRequest>> resp = controller.listByProject(200L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isEmpty();
    }

    @Test
    @DisplayName("P1-2 #3：未登录/非内部角色 → IpdPermissionException 透传，不调 service（鉴权前置）")
    void listByProject_unauthenticated_propagatesIpdPermissionException() {
        IpdPermissionException denied = new IpdPermissionException(401,
            org.ruoyi.ipd.common.ApiV1ErrorCode.UNAUTHORIZED);
        when(ipdPermission.requireInternal()).thenThrow(denied);

        try {
            controller.listByProject(200L);
            org.junit.jupiter.api.Assertions.fail("expected IpdPermissionException to propagate");
        } catch (IpdPermissionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.UNAUTHORIZED);
        }
        verify(service, never()).listByProject(any(), any());
    }
}
