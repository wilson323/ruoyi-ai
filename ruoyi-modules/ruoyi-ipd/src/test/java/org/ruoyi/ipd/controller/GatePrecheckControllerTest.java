package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.GatePrecheckService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI-P2-1：预审端点 controller→service 路由锁（Mock 层单测，非真实 HTTP——
 * Sa-Token 注解拦截与 ApiV1 包络序列化由全局 advice 保障，本测只锁委托关系）。
 * {@code @Tag("dev")} 必须，否则 Surefire 按 active group 静默跳过（假绿陷阱）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("AI-P2-1：POST /gates/{gateId}/precheck 路由委托")
class GatePrecheckControllerTest {

    @Mock
    private IpdPermission permission;
    @Mock
    private GatePrecheckService service;

    @InjectMocks
    private GatePrecheckController controller;

    @Test
    @DisplayName("正常：requireInternal 的 actor + gateId 透传 service，包络 code=0")
    void precheckDelegatesToService() {
        IpdActor actor = new IpdActor(9L, "pm", "MARKET_PM", 100L);
        when(permission.requireInternal()).thenReturn(actor);
        Map<String, Object> result = Map.of("gateId", "50", "blocking", false);
        when(service.precheck(50L, actor)).thenReturn(result);

        ApiV1Response<Map<String, Object>> resp = controller.precheck(50L);

        assertEquals(ApiV1Response.CODE_SUCCESS, resp.getCode());
        assertEquals(result, resp.getData());
        verify(service).precheck(50L, actor);
    }

    @Test
    @DisplayName("角色门未过：requireInternal 抛 403 → 异常原样上抛，不触达 service")
    void roleGateShortCircuits() {
        when(permission.requireInternal())
            .thenThrow(new IpdPermissionException(403, ApiV1ErrorCode.FORBIDDEN));

        assertThrows(IpdPermissionException.class, () -> controller.precheck(50L));
    }

    @Test
    @DisplayName("service 校验失败（材料为空 400）→ 异常透传给全局 advice，controller 不吞")
    void serviceErrorPropagates() {
        IpdActor actor = new IpdActor(9L, "pm", "MARKET_PM", 100L);
        when(permission.requireInternal()).thenReturn(actor);
        when(service.precheck(any(), any()))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "材料为空"));

        IpdBusinessException ex = assertThrows(IpdBusinessException.class, () -> controller.precheck(50L));
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }
}
