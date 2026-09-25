package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.service.StageActionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R215-GAP-B2：stage-actions/instantiate 触发方收口单测。
 *
 * <p>验证 Controller 层权限守卫从 requireInternal() 收口为 requireAdmin()：
 * <ul>
 *   <li>SUPER_ADMIN 调用 → 正常透传到 service.instantiate</li>
 *   <li>非 SUPER_ADMIN（如 MARKET_PM）→ IpdPermissionException 403，service 不被调用</li>
 * </ul>
 *
 * <p>Hermetic 单元测试（无 Spring 启动、无 DB 连接）：纯 Mockito mock。
 * <p>已知限制（R215 收口轮登记）：requireAdmin() 被 mock，本测试验证的是 Controller 对
 * requireAdmin 的调用约定，不覆盖真实鉴权链（若有人改回 requireInternal 本测试仍绿）；
 * 真实链路覆盖归 e2e-http 四角色冒烟（ba766d26 T-V2 已验 900102-905 非超管 403）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("R215-GAP-B2 stage-actions/instantiate 触发方收口")
class StageActionControllerInstantiateGuardTest {

    @Mock
    private StageActionService stageActionService;
    @Mock
    private IpdPermission ipdPermission;

    @InjectMocks
    private StageActionController controller;

    private static final IpdActor ADMIN = new IpdActor(1L, "超管", "SUPER_ADMIN", 100L);
    private static final IpdActor MARKET_PM = new IpdActor(2L, "市场PM", "MARKET_PM", 100L);

    @Test
    @DisplayName("[B2-正例] SUPER_ADMIN 调用 instantiate → 正常透传 service，返回新建数量")
    void instantiate_superAdmin_passesThrough() {
        when(ipdPermission.requireAdmin()).thenReturn(ADMIN);
        when(stageActionService.instantiate(eq(10L), eq(20L), eq("CONCEPT"), eq(ADMIN))).thenReturn(12);

        ApiV1Response<Integer> resp = controller.instantiate(10L, 20L, "CONCEPT");

        assertThat(resp.getData()).isEqualTo(12);
        verify(stageActionService).instantiate(10L, 20L, "CONCEPT", ADMIN);
    }

    @Test
    @DisplayName("[B2-负例] 非 SUPER_ADMIN 调用 → requireAdmin 抛 403，service 不被调用")
    void instantiate_nonAdmin_rejected403() {
        // requireAdmin 内部调 requireRoles("SUPER_ADMIN")，MARKET_PM 不在白名单
        when(ipdPermission.requireAdmin()).thenThrow(
            new IpdPermissionException(403, ApiV1ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> controller.instantiate(10L, 20L, "CONCEPT"))
            .isInstanceOf(IpdPermissionException.class);

        verify(stageActionService, never()).instantiate(any(), any(), any(), any());
    }
}
