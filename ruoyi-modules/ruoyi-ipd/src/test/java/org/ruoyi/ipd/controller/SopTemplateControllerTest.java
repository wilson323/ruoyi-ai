package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.SopTemplate;
import org.ruoyi.ipd.domain.SopTemplateInstance;
import org.ruoyi.ipd.dto.SopTemplateListItem;
import org.ruoyi.ipd.dto.SopTemplateSaveReq;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.SopTemplateService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-3.3 SOP 模板接口（/api/v1/sop-templates）：守门调用顺序、身份透传、
 * 资源不存在 → 404 契约、以及 9 个端点的权限注解必须与实际语义一致（读=list 权限 / 写=edit 权限）。
 *
 * <p>权限注解是本端点真正的执行面（Sa-Token 切面按注解拦截）——注解被摘掉即端点对全员开放，
 * 因此本类对注解做反射钉桩（不是重复 {@code IpdPermission} 单测）。
 */
@Tag("dev")
class SopTemplateControllerTest {

    private SopTemplateService sopTemplateService;
    private IpdPermission ipdPermission;
    private SopTemplateController controller;

    private static final IpdActor ADMIN = new IpdActor(1L, "admin", "SUPER_ADMIN", 100L);
    private static final IpdActor MARKET_PM = new IpdActor(2L, "market", "MARKET_PM", 100L);
    private static final long ID = 100L;
    private static final long PROJECT_ID = 200L;

    @BeforeEach
    void setUp() {
        sopTemplateService = mock(SopTemplateService.class);
        ipdPermission = mock(IpdPermission.class);
        controller = new SopTemplateController(sopTemplateService, ipdPermission);
    }

    private static ApiV1ErrorCode codeOf(Throwable ex) {
        return ((IpdBusinessException) ex).getErrorCode();
    }

    private static SaCheckPermission perm(String method, Class<?>... params) throws Exception {
        return SopTemplateController.class.getMethod(method, params)
            .getAnnotation(SaCheckPermission.class);
    }

    // ========== 读端点 ==========

    @Test
    @DisplayName("list：先验内部角色，再委派 service，包络 code=0")
    void list_checksInternalThenDelegates() {
        when(ipdPermission.requireInternal()).thenReturn(MARKET_PM);
        SopTemplateListItem item = new SopTemplateListItem(1L, "C01", "标题", 2L, "PUBLISHED", 30L);
        when(sopTemplateService.listByActionCode("C01")).thenReturn(List.of(item));

        var resp = controller.list("C01");

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).actionCode()).isEqualTo("C01");

        InOrder order = inOrder(ipdPermission, sopTemplateService);
        order.verify(ipdPermission).requireInternal();
        order.verify(sopTemplateService).listByActionCode("C01");
    }

    @Test
    @DisplayName("list：内部角色校验失败 → 异常上抛，service 零调用")
    void list_internalDeniedStopsBeforeService() {
        when(ipdPermission.requireInternal())
            .thenThrow(new IpdPermissionException(403, ApiV1ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> controller.list("C01"))
            .isInstanceOf(IpdPermissionException.class);
        verify(sopTemplateService, never()).listByActionCode(any());
    }

    @Test
    @DisplayName("current：委派 actionCode 并返回生效版本")
    void current_delegates() {
        when(ipdPermission.requireInternal()).thenReturn(MARKET_PM);
        SopTemplate live = SopTemplate.builder().id(5L).actionCode("V10")
            .status(SopTemplate.Status.PUBLISHED).content("正文").build();
        when(sopTemplateService.currentForAction("V10")).thenReturn(live);

        var resp = controller.current("V10");

        assertThat(resp.getData().getId()).isEqualTo(5L);
        verify(sopTemplateService).currentForAction("V10");
    }

    @Test
    @DisplayName("get：存在 → 正常返回")
    void get_present_returnsOk() {
        when(ipdPermission.requireInternal()).thenReturn(MARKET_PM);
        when(sopTemplateService.getById(ID)).thenReturn(
            SopTemplate.builder().id(ID).title("标题").build());

        assertThat(controller.get(ID).getData().getId()).isEqualTo(ID);
    }

    @Test
    @DisplayName("get：不存在 → IpdBusinessException(NOT_FOUND/404)，禁止 NPE 落 500")
    void get_missing_throwsNotFound() {
        when(ipdPermission.requireInternal()).thenReturn(MARKET_PM);
        when(sopTemplateService.getById(999L)).thenReturn(null);

        assertThatThrownBy(() -> controller.get(999L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("SOP模板")
            .hasMessageContaining("999")
            .satisfies(ex -> {
                assertThat(codeOf(ex)).isEqualTo(ApiV1ErrorCode.NOT_FOUND);
                assertThat(codeOf(ex).getHttpStatus()).isEqualTo(404);
            });
    }

    // ========== 写端点：仅超管 ==========

    @Test
    @DisplayName("copy：把 requireAdmin 的 actor 透传给 service（不得用 requireInternal 的宽松身份）")
    void copy_usesAdminActor() {
        when(ipdPermission.requireAdmin()).thenReturn(ADMIN);
        when(sopTemplateService.copyToDraft(ID, ADMIN))
            .thenReturn(SopTemplate.builder().id(ID).status(SopTemplate.Status.DRAFT).build());

        assertThat(controller.copy(ID).getData().getStatus()).isEqualTo(SopTemplate.Status.DRAFT);

        verify(ipdPermission).requireAdmin();
        verify(ipdPermission, never()).requireInternal();
        verify(sopTemplateService).copyToDraft(ID, ADMIN);
    }

    @Test
    @DisplayName("copy：超管校验失败 → 异常上抛，service 零调用")
    void copy_adminDeniedPropagates() {
        when(ipdPermission.requireAdmin())
            .thenThrow(new IpdPermissionException(403, ApiV1ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> controller.copy(ID))
            .isInstanceOf(IpdPermissionException.class);
        verify(sopTemplateService, never()).copyToDraft(anyLong(), any());
    }

    @Test
    @DisplayName("update：请求体与超管身份一并透传")
    void update_passesRequestAndActor() {
        SopTemplateSaveReq req = new SopTemplateSaveReq("新标题", "新正文");
        when(ipdPermission.requireAdmin()).thenReturn(ADMIN);
        when(sopTemplateService.updateDraft(ID, req, ADMIN))
            .thenReturn(SopTemplate.builder().id(ID).title("新标题").build());

        assertThat(controller.update(ID, req).getData().getTitle()).isEqualTo("新标题");
        verify(sopTemplateService).updateDraft(ID, req, ADMIN);
    }

    @Test
    @DisplayName("publish / revert：均要求超管身份并透传")
    void publishAndRevert_requireAdmin() {
        when(ipdPermission.requireAdmin()).thenReturn(ADMIN);
        when(sopTemplateService.publishDraft(ID, ADMIN))
            .thenReturn(SopTemplate.builder().id(ID).status(SopTemplate.Status.PUBLISHED).build());
        when(sopTemplateService.revertToDraft(ID, ADMIN))
            .thenReturn(SopTemplate.builder().id(ID).status(SopTemplate.Status.DRAFT).build());

        assertThat(controller.publish(ID).getData().getStatus())
            .isEqualTo(SopTemplate.Status.PUBLISHED);
        assertThat(controller.revert(ID).getData().getStatus())
            .isEqualTo(SopTemplate.Status.DRAFT);

        verify(sopTemplateService).publishDraft(ID, ADMIN);
        verify(sopTemplateService).revertToDraft(ID, ADMIN);
    }

    // ========== 实例化端点 ==========

    @Test
    @DisplayName("instantiate：透传 templateId/projectId 与 requireInternal 的 actor")
    void instantiate_usesInternalActor() {
        when(ipdPermission.requireInternal()).thenReturn(MARKET_PM);
        SopTemplateInstance inst = SopTemplateInstance.builder()
            .id(7L).projectId(PROJECT_ID).instanceVersion(1L)
            .status(SopTemplateInstance.Status.ACTIVE).build();
        when(sopTemplateService.instantiate(ID, PROJECT_ID, MARKET_PM)).thenReturn(inst);

        var resp = controller.instantiate(ID, PROJECT_ID);

        assertThat(resp.getData().getInstanceVersion()).isEqualTo(1L);
        verify(sopTemplateService).instantiate(ID, PROJECT_ID, MARKET_PM);
    }

    @Test
    @DisplayName("listInstances：透传 projectId 与 requireInternal 的 actor")
    void listInstances_usesInternalActor() {
        when(ipdPermission.requireInternal()).thenReturn(MARKET_PM);
        when(sopTemplateService.listInstancesByProject(PROJECT_ID, MARKET_PM))
            .thenReturn(List.of(SopTemplateInstance.builder().id(9L).build()));

        assertThat(controller.listInstances(PROJECT_ID).getData()).hasSize(1);
        verify(sopTemplateService).listInstancesByProject(PROJECT_ID, MARKET_PM);
    }

    // ========== 权限注解契约（端点真正的执行面） ==========

    @Test
    @DisplayName("读端点 5 个：list/current/get/instantiate/listInstances 均带 ipd:sop-template:list")
    void readEndpointsCarryListPermission() throws Exception {
        SaCheckPermission[] annotations = {
            perm("list", String.class),
            perm("current", String.class),
            perm("get", Long.class),
            perm("instantiate", Long.class, Long.class),
            perm("listInstances", Long.class),
        };

        for (SaCheckPermission p : annotations) {
            assertThat(p).as("读端点必须标注 @SaCheckPermission，否则对全员开放").isNotNull();
            assertThat(p.value()).containsExactly(IpdPermissionCode.OPERATION_SOP_TEMPLATE);
            assertThat(p.type()).isEqualTo(IpdAuthSession.LOGIN_TYPE);
        }
    }

    @Test
    @DisplayName("写端点 4 个：copy/update/publish/revert 均带 ipd:sop-template:edit")
    void writeEndpointsCarryEditPermission() throws Exception {
        SaCheckPermission[] annotations = {
            perm("copy", Long.class),
            perm("update", Long.class, SopTemplateSaveReq.class),
            perm("publish", Long.class),
            perm("revert", Long.class),
        };

        for (SaCheckPermission p : annotations) {
            assertThat(p).as("写端点必须标注 @SaCheckPermission，否则草稿可被任意角色改写").isNotNull();
            assertThat(p.value()).containsExactly(IpdPermissionCode.OPERATION_SOP_TEMPLATE_EDIT);
            assertThat(p.type()).isEqualTo(IpdAuthSession.LOGIN_TYPE);
        }
    }

    @Test
    @DisplayName("读权限码与写权限码必须是两个不同字符串（写不能被读权限顶替）")
    void readAndWritePermissionCodesAreDistinct() {
        assertThat(IpdPermissionCode.OPERATION_SOP_TEMPLATE)
            .isNotEqualTo(IpdPermissionCode.OPERATION_SOP_TEMPLATE_EDIT);
    }
}
