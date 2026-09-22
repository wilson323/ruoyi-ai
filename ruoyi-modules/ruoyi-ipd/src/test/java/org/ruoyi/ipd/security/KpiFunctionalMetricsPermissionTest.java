package org.ruoyi.ipd.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2-KPI2A：功能指标量表权限码目录契约（A2 P1）。
 *
 * <p>授权口径（OWNER-拍板登记-20260921.md → R148.1 §2.2 A2 P1）：
 * <ul>
 *   <li>写码 {@code ipd:kpi:config}：SUPER_ADMIN / MARKET_PM / RD_PM 可写</li>
 *   <li>读码 {@code ipd:kpi:config:query}：内部四角色（含 GROUP_LEADER）可读</li>
 *   <li>未登记角色一律不持有（防 2026-09-06「连超管都被注解拒」的 30001 类漏登记）</li>
 * </ul>
 */
@Tag("dev")
@DisplayName("W2-KPI2A：功能指标量表权限码目录契约")
class KpiFunctionalMetricsPermissionTest {

    private static final List<String> INTERNAL_ROLES =
        List.of("SUPER_ADMIN", "GROUP_LEADER", "MARKET_PM", "RD_PM");

    @Test
    @DisplayName("读码：内部四角色均可读 ipd:kpi:config:query")
    void readAllowedForAllInternalRoles() {
        for (String role : INTERNAL_ROLES) {
            assertThat(IpdRolePermissionCatalog.has(role, IpdPermissionCode.OPERATION_KPI_CONFIG_QUERY))
                .as("%s 应持有 %s", role, IpdPermissionCode.OPERATION_KPI_CONFIG_QUERY)
                .isTrue();
        }
    }

    @Test
    @DisplayName("写码：SUPER_ADMIN / MARKET_PM / RD_PM 可写；GROUP_LEADER 不可写（授权口径）")
    void writeAllowedForAdminAndDualPmsOnly() {
        for (String role : List.of("SUPER_ADMIN", "MARKET_PM", "RD_PM")) {
            assertThat(IpdRolePermissionCatalog.has(role, IpdPermissionCode.OPERATION_KPI_CONFIG))
                .as("%s 应持有 %s", role, IpdPermissionCode.OPERATION_KPI_CONFIG)
                .isTrue();
        }
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", IpdPermissionCode.OPERATION_KPI_CONFIG))
            .as("GROUP_LEADER 不应持有写码（授权口径：超管 + 双 PM 可写）")
            .isFalse();
    }

    @Test
    @DisplayName("未登记角色（EXTERNAL_AUDITOR / 空串 / null）一律不持有读写码")
    void unknownRolesHoldNothing() {
        for (String code : List.of(IpdPermissionCode.OPERATION_KPI_CONFIG,
            IpdPermissionCode.OPERATION_KPI_CONFIG_QUERY)) {
            assertThat(IpdRolePermissionCatalog.has("EXTERNAL_AUDITOR", code)).isFalse();
            assertThat(IpdRolePermissionCatalog.has("", code)).isFalse();
            assertThat(IpdRolePermissionCatalog.has(null, code)).isFalse();
        }
    }
}
