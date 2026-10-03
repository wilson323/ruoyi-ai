package org.ruoyi.ipd.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SEC-FIX-CATALOG-113] 90 日回款预警扫描权限码的角色归属契约。
 *
 * <p><b>缺陷</b>：{@code ipd:recovery:check-90d} 原先登记在 {@code BUSINESS_WRITE}
 * ——那是「内部四角色全员」集合，与该行自己写的注释「（组长/超管）」直接矛盾。
 * 后果是注解层对双 PM 也放行，真正拦住他们的只有方法体里的
 * {@code requireLeaderOrAdmin()}。两层口径不一致有两个具体危害：
 * <ol>
 *   <li>注解层形同虚设——白名单式的权限目录成了摆设，后续审计看到「已登记」就以为收口了；</li>
 *   <li>方法体那一道门是<b>单点</b>：任何把它挪走、内联或删掉的重构都会立刻开出一个
 *       「任意内部角色触发全库回款扫描 + 批量写 recovery_warnings」的洞，且无测试报警。</li>
 * </ol>
 *
 * <p><b>本测试锁三件事</b>：
 * <ol>
 *   <li>注解层口径 = 组长/超管（与 RecoveryWarningController javadoc 声明逐字对齐）；</li>
 *   <li>双 PM <b>不</b>持有该码——这正是本轮修正的行为，回归即红；</li>
 *   <li>与方法体层 {@code requireLeaderOrAdmin()}（GROUP_LEADER / SUPER_ADMIN）<b>不冲突</b>：
 *       注解层放行的角色集合与方法体放行的集合必须完全相等，
 *       否则又是「两层口径不一致」这个反复出现的老问题。</li>
 * </ol>
 */
@Tag("dev")
@DisplayName("权限码归类：90 日回款扫描仅组长/超管，注解层与方法体层同口径")
class RecoveryCheck90dCatalogTest {

    private static final String CODE = IpdPermissionCode.OPERATION_RECOVERY_CHECK_90D;

    /** RecoveryWarningController#check90d 方法体里 requireLeaderOrAdmin() 的角色集合。 */
    private static final String[] METHOD_BODY_ROLES = {"GROUP_LEADER", "SUPER_ADMIN"};

    @Test
    @DisplayName("1) 注解层：组长/超管持有，双 PM 不持有（回归本轮修正）")
    void annotationLayer_allowsLeaderAndAdminOnly() {
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", CODE)).isTrue();
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", CODE)).isTrue();
        assertThat(IpdRolePermissionCatalog.has("MARKET_PM", CODE)).isFalse();
        assertThat(IpdRolePermissionCatalog.has("RD_PM", CODE)).isFalse();
    }

    @Test
    @DisplayName("2) 注解层放行角色集合 == 方法体 requireLeaderOrAdmin 放行角色集合（两层不冲突）")
    void annotationAndMethodBodyAgreeOnSameRoleSet() {
        for (String role : METHOD_BODY_ROLES) {
            assertThat(IpdRolePermissionCatalog.has(role, CODE))
                .as("方法体 requireLeaderOrAdmin() 放行 %s，注解层必须同样放行", role)
                .isTrue();
        }
        for (String role : new String[]{"MARKET_PM", "RD_PM"}) {
            assertThat(IpdRolePermissionCatalog.has(role, CODE))
                .as("方法体 requireLeaderOrAdmin() 拒绝 %s，注解层也必须拒绝", role)
                .isFalse();
        }
    }

    @Test
    @DisplayName("3) 该码不得回到「内部四角色全员」集合（防无声复活）")
    void codeNotInBusinessWriteAnyMore() {
        // 四角色并集口径：若退回 BUSINESS_WRITE，双 PM 会重新拿到该码
        assertThat(IpdRolePermissionCatalog.has("MARKET_PM", CODE)).isFalse();
        assertThat(IpdRolePermissionCatalog.has("RD_PM", CODE)).isFalse();
        // 且必须仍被登记（未被误删导致全员 403 —— 2026-09-06「连超管都被注解拒」的历史教训）
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", CODE)).isTrue();
    }

    @Test
    @DisplayName("4) 读码 ipd:recovery:warnings:query 仍是四角色可读（本轮不得连坐收紧）")
    void queryCodeStaysReadOnlyFourRoles() {
        String query = IpdPermissionCode.OPERATION_RECOVERY_WARNINGS_QUERY;
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", query)).isTrue();
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", query)).isTrue();
        assertThat(IpdRolePermissionCatalog.has("MARKET_PM", query)).isTrue();
        assertThat(IpdRolePermissionCatalog.has("RD_PM", query)).isTrue();
    }
}
