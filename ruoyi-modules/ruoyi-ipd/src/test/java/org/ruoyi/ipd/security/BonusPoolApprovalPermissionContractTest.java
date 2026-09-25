package org.ruoyi.ipd.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R215-N1（owner 拍板 2026-09-24）：奖金池涉钱审批权限矩阵契约。
 *
 * <p>拍板口径「组长可以操作，超管全部完整有权可用」落地为：
 * <ul>
 *   <li>freeze / distribute：GROUP_LEADER + SUPER_ADMIN 持有；MARKET_PM / RD_PM 不持有；</li>
 *   <li>compute：仅 SUPER_ADMIN（未拍板放开，维持 ADMIN_WRITE 现状）；</li>
 *   <li>query：内部四角色可读（READ_SET 全员口径）。</li>
 * </ul>
 *
 * <p>纯 JVM 目录层契约（同 RnewPermissionContractTest 判例）；注解拦截真实拒绝路径
 * 由 R215 真库四角色探针复核（leader freeze 透闸、market/rd 403）。
 */
@Tag("dev")
@DisplayName("R215-N1：奖金池冻结/分配组长+超管、核算仅超管")
class BonusPoolApprovalPermissionContractTest {

    @Test
    @DisplayName("freeze/distribute：组长与超管持有，双 PM 不持有")
    void approvalCodesLeaderAndAdminOnly() {
        for (String code : new String[] {
            IpdPermissionCode.OPERATION_BONUS_POOL_FREEZE,
            IpdPermissionCode.OPERATION_BONUS_POOL_DISTRIBUTE }) {
            assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", code))
                .as("组长应持有涉钱审批码 %s（R215-N1 拍板）", code).isTrue();
            assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", code))
                .as("超管全部完整有权 %s", code).isTrue();
            assertThat(IpdRolePermissionCatalog.has("MARKET_PM", code))
                .as("市场PM 不持有 %s", code).isFalse();
            assertThat(IpdRolePermissionCatalog.has("RD_PM", code))
                .as("研发PM 不持有 %s", code).isFalse();
        }
    }

    @Test
    @DisplayName("compute：仅超管（未拍板放开，组长也不持有）")
    void computeRemainsAdminOnly() {
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", IpdPermissionCode.OPERATION_BONUS_POOL_COMPUTE)).isTrue();
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", IpdPermissionCode.OPERATION_BONUS_POOL_COMPUTE)).isFalse();
    }

    @Test
    @DisplayName("query：内部四角色均可读")
    void queryReadableByAllInternalRoles() {
        for (String role : new String[] { "SUPER_ADMIN", "GROUP_LEADER", "MARKET_PM", "RD_PM" }) {
            assertThat(IpdRolePermissionCatalog.has(role, IpdPermissionCode.OPERATION_BONUS_POOL_QUERY))
                .as("%s 可读奖金池", role).isTrue();
        }
    }
}
