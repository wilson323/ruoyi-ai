package org.ruoyi.ipd.security;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.controller.ProductLineSpaceController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class ProductLinePermissionContractTest {
    private static final List<String> INTERNAL_ROLES =
        List.of("SUPER_ADMIN", "GROUP_LEADER", "MARKET_PM", "RD_PM");

    @AfterEach
    void clearOverrides() {
        IpdRolePermissionCatalog.clearDbOverrides();
    }

    @Test
    void internalRolesCanDiscoverApplyLeaveAndReviewButOnlyAdminCanManage() {
        for (String role : INTERNAL_ROLES) {
            for (String code : List.of(
                IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST,
                IpdPermissionCode.OPERATION_PRODUCT_LINE_APPLY,
                IpdPermissionCode.OPERATION_PRODUCT_LINE_LEAVE,
                IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW)) {
                assertThat(IpdRolePermissionCatalog.has(role, code)).as(role + " " + code).isTrue();
            }
            assertThat(IpdRolePermissionCatalog.has(role, IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE))
                .isEqualTo("SUPER_ADMIN".equals(role));
        }
        for (String code : List.of(
            IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST,
            IpdPermissionCode.OPERATION_PRODUCT_LINE_APPLY,
            IpdPermissionCode.OPERATION_PRODUCT_LINE_LEAVE,
            IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW,
            IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE)) {
            assertThat(IpdRolePermissionCatalog.has("EXTERNAL_AUDITOR", code)).isFalse();
        }
    }

    @Test
    void dbRevokeRemovesOnlyTheSelectedRoleAndCode() {
        IpdRolePermissionCatalog.applyDbOverrides(Map.of(),
            Map.of("MARKET_PM", Set.of(IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW),
                "SUPER_ADMIN", Set.of(IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE)));

        assertThat(IpdRolePermissionCatalog.has("MARKET_PM", IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW))
            .isFalse();
        assertThat(IpdRolePermissionCatalog.has("RD_PM", IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW))
            .isTrue();
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE))
            .isFalse();
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST))
            .isTrue();
    }

    @Test
    void everyProductLineEndpointUsesItsOwnIpdPermission() {
        assertEndpoint("visibleLines", IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST);
        assertEndpoint("discoverableLines", IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST);
        assertEndpoint("create", IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE,
            ProductLineSpaceController.CreateRequest.class);
        assertEndpoint("rename", IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE,
            Long.class, ProductLineSpaceController.RenameRequest.class);
        assertEndpoint("deactivate", IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, Long.class);
        assertEndpoint("apply", IpdPermissionCode.OPERATION_PRODUCT_LINE_APPLY, Long.class);
        assertEndpoint("leave", IpdPermissionCode.OPERATION_PRODUCT_LINE_LEAVE, Long.class);
        assertEndpoint("removeMember", IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, Long.class, Long.class);
        assertEndpoint("pending", IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW, Long.class);
        assertEndpoint("review", IpdPermissionCode.OPERATION_PRODUCT_LINE_REVIEW,
            Long.class, Long.class, ProductLineSpaceController.ReviewRequest.class);
        assertEndpoint("appointLeader", IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, Long.class, Long.class);
        assertEndpoint("assignProduct", IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, Long.class, Long.class);
        assertEndpoint("unassignProduct", IpdPermissionCode.OPERATION_PRODUCT_LINE_MANAGE, Long.class, Long.class);
        assertEndpoint("products", IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST, Long.class);
        assertEndpoint("projects", IpdPermissionCode.OPERATION_PRODUCT_LINE_LIST, Long.class);
    }

    private static void assertEndpoint(String name, String code, Class<?>... parameterTypes) {
        try {
            Method method = ProductLineSpaceController.class.getDeclaredMethod(name, parameterTypes);
            SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);
            assertThat(permission).as(name).isNotNull();
            assertThat(permission.value()).as(name).containsExactly(code);
            assertThat(permission.type()).as(name).isEqualTo(IpdAuthSession.LOGIN_TYPE);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("产品线权限端点签名已变化: " + name, ex);
        }
    }
}
