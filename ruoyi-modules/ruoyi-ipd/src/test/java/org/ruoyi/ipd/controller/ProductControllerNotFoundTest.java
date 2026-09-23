package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.ProductService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * R179-P1 治理轮（2026-09-23）：资源不存在 → NOT_FOUND 契约单测。
 *
 * <p><b>病根回放</b>：修复前 {@code ProductController.get(id)} 直接 {@code ProductVO.from(productService.getById(id))}，
 * 当 id 不存在时 service 返回 null → {@code ProductVO.from(null)} NPE → 落 INTERNAL_ERROR(90001) HTTP 500。
 * 前端拿到「系统内部错误」而非「资源不存在」，监控告警被污染（5xx 应是系统故障，4xx 才是客户端问题）。
 *
 * <p><b>本测试自证能红</b>：故意把 controller 改回原样（去掉 {@code IpdResources.requireOrNotFound} 包装）
 * → 本测试必须红（NPE 而不是 IpdBusinessException）→ 证明本测试真能抓到 bug，不是假绿。
 *
 * <p>{@code @Tag("dev")} 必须，否则 Surefire 按 {@code <groups>${profiles.active}</groups>} 静默跳过。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProductControllerNotFoundTest {

    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private ProductService productService;

    @InjectMocks
    private ProductController controller;

    @Test
    @DisplayName("R179-P1 #1：getById 不存在 id=999 必须抛 IpdBusinessException(NOT_FOUND)，禁止 NPE→500")
    void get_nonExistentId_throwsNotFound_notNpe() {
        IpdActor actor = new IpdActor(9001L, "alice", "MARKET_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(productService.getById(999L)).thenReturn(null);

        assertThatThrownBy(() -> controller.get(999L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("产品不存在")
            .hasMessageContaining("999")
            .satisfies(ex -> {
                IpdBusinessException be = (IpdBusinessException) ex;
                assertThat(be.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND);
                assertThat(be.getErrorCode().getHttpStatus()).isEqualTo(404);
            });
    }

    @Test
    @DisplayName("R179-P1 #2：getById 存在 id=1 必须正常返回 VO（回归保护，防止过度防御误伤正常路径）")
    void get_existingId_returnsVo() {
        IpdActor actor = new IpdActor(9001L, "alice", "MARKET_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);

        org.ruoyi.ipd.domain.Product p = new org.ruoyi.ipd.domain.Product();
        p.setId(1L);
        p.setProductCode("PRD-001");
        p.setProductName("测试产品");
        when(productService.getById(1L)).thenReturn(p);

        var resp = controller.get(1L);

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getData()).isNotNull();
        assertThat(resp.getData().id()).isEqualTo(1L);
        assertThat(resp.getData().productName()).isEqualTo("测试产品");
    }
}