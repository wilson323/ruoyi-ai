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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-2：{@code GET /api/v1/coefficient-change-requests}（按 projectId 列表）单测，
 * 验证 controller→service 路由 + actor.id() 透传。{@code @Tag("dev")} 必须，否则被
 * Surefire 静默跳过（假绿陷阱）。
 *
 * <p>覆盖 3 个维度：
 * <ol>
 *   <li>正常路径：mock {@code requireInternal} 返回 actor → service.listByProject 收到
 *       {@code (projectId, currentPersonId)} → 透传返回列表 → 包络 code=0 + data 完整</li>
 *   <li>projectId 缺省：service 收到 {@code null} projectId + 非空 personId，控制器无过滤（业务约定）</li>
 *   <li>鉴权失败：mock requireInternal 抛 {@code IpdPermissionException} → 端点不调 service</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class CoefficientChangeControllerListTest {

    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private CoefficientChangeService service;

    @InjectMocks
    private CoefficientChangeController controller;

    @Test
    @DisplayName("P1-2 #1：listByProject(projectId=100) 透传 actor.id() 到 service，返回包络 code=0 + data 完整")
    void listByProject_passesProjectIdAndActorId_andReturnsEnvelope() {
        IpdActor actor = new IpdActor(7001L, "alice", "MARKET_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        CoefficientChangeRequest r1 = new CoefficientChangeRequest();
        r1.setId(101L);
        r1.setProjectId(100L);
        r1.setStatus(CoefficientChangeRequest.ST_PENDING_LEADER);
        r1.setProposedCoefficient(new java.math.BigDecimal("1.20"));
        when(service.listByProject(eq(100L), any())).thenReturn(List.of(r1));

        ApiV1Response<List<CoefficientChangeRequest>> resp = controller.listByProject(100L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getId()).isEqualTo(101L);
        assertThat(resp.getData().get(0).getStatus()).isEqualTo(CoefficientChangeRequest.ST_PENDING_LEADER);

        // 验证 (projectId, currentPersonId) 透传到 service（currentPersonId = String.valueOf(actor.id())）
        ArgumentCaptor<Long> projectCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> personCaptor = ArgumentCaptor.forClass(String.class);
        verify(service).listByProject(projectCaptor.capture(), personCaptor.capture());
        assertThat(projectCaptor.getValue()).isEqualTo(100L);
        assertThat(personCaptor.getValue()).isEqualTo("7001");
    }

    @Test
    @DisplayName("P1-2 #2：listByProject 缺省 projectId → service 收到 null projectId + 非空 personId")
    void listByProject_nullProjectId_passesNullToService() {
        IpdActor actor = new IpdActor(7002L, "bob", "RD_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(service.listByProject(eq(null), any())).thenReturn(List.of());

        ApiV1Response<List<CoefficientChangeRequest>> resp = controller.listByProject(null);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isEmpty();

        ArgumentCaptor<Long> projectCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> personCaptor = ArgumentCaptor.forClass(String.class);
        verify(service).listByProject(projectCaptor.capture(), personCaptor.capture());
        assertThat(projectCaptor.getValue()).isNull();
        assertThat(personCaptor.getValue()).isEqualTo("7002");
    }

    @Test
    @DisplayName("P1-2 #3：未登录/非内部角色 → IpdPermissionException 透传，不调 service（鉴权前置）")
    void listByProject_unauthenticated_propagatesIpdPermissionException() {
        IpdPermissionException denied = new IpdPermissionException(401,
            org.ruoyi.ipd.common.ApiV1ErrorCode.UNAUTHORIZED);
        when(ipdPermission.requireInternal()).thenThrow(denied);

        try {
            controller.listByProject(100L);
            org.junit.jupiter.api.Assertions.fail("expected IpdPermissionException to propagate");
        } catch (IpdPermissionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.UNAUTHORIZED);
        }
        // 鉴权失败 → service 不能被调到（防止「绕过鉴权仍走业务逻辑」侧信道）
        verify(service, never()).listByProject(any(), any());
    }
}
