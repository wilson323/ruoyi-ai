package org.ruoyi.ipd.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R215 权限可配置化（owner 指令 2026-09-24）：catalog DB 覆盖层语义契约。
 *
 * <p>纯 JVM 契约（同 RnewPermissionContractTest 判例）：
 * 有效码集 = Java 默认 ∪ GRANT − REVOKE；无覆盖行必须与纯 Java 目录逐码一致
 * （fail-closed 零漂移兜底）；DB 配置不可造新角色；覆盖快照不可变。
 * 运行时真实链路（表 → service.reload → /auth/me）由 R215 真库端到端探针复核。
 *
 * <p><b>2026-10-03 样本码替换说明</b>：本类原先借用奖金池两码（compute/freeze）当对照样本；
 * 奖金池业务域退役后该两码已删。本类被测对象是「DB 覆盖层合并算法」而非奖金池，故替换为
 * 两个仍然存续的样本码——{@link #ADMIN_ONLY_SAMPLE}（仅超管持有，验证增授非默认码）与
 * {@link #LEADER_DEFAULT_SAMPLE}（组长默认持有，验证收回默认码），各断言语义与替换前逐条等价。
 * 原 javadoc 引用的 BonusPoolApprovalPermissionContractTest 已随该域退役删除，引用改指现行契约测试。
 */
@Tag("dev")
@DisplayName("R215：角色权限目录 DB 覆盖层合并语义")
class IpdRolePermissionDbOverrideContractTest {

    /** 样本码 A：仅 SUPER_ADMIN 默认持有（不在 GROUP_LEADER / MARKET_PM / RD_PM 默认集）——GRANT 增授用例。 */
    private static final String ADMIN_ONLY_SAMPLE = IpdPermissionCode.OPERATION_PERMANENT_DELETE;
    /** 样本码 B：GROUP_LEADER 默认持有（DELETION_LEADER 集合）——REVOKE 收回用例。 */
    private static final String LEADER_DEFAULT_SAMPLE = IpdPermissionCode.OPERATION_CONTRIBUTION_CONFIRM;

    @AfterEach
    void resetOverrides() {
        IpdRolePermissionCatalog.clearDbOverrides();
    }

    @Test
    @DisplayName("① 零覆盖 ⇒ 与 Java 默认逐码一致（fail-closed 兜底）")
    void noOverrides_equalsJavaDefault() {
        for (String role : IpdRolePermissionCatalog.knownRoles()) {
            assertThat(IpdRolePermissionCatalog.permissionsOf(role))
                .as("%s 无覆盖行时不得有任何漂移", role)
                .containsExactlyInAnyOrderElementsOf(IpdRolePermissionCatalog.defaultPermissionsOf(role));
        }
    }

    @Test
    @DisplayName("② GRANT 增授：leader 获得默认集外的码")
    void grant_addsCodeBeyondDefault() {
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", ADMIN_ONLY_SAMPLE)).isFalse();

        IpdRolePermissionCatalog.applyDbOverrides(Map.of("GROUP_LEADER", Set.of(ADMIN_ONLY_SAMPLE)), Map.of());

        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", ADMIN_ONLY_SAMPLE)).isTrue();
        // 增授不传染其他角色
        assertThat(IpdRolePermissionCatalog.has("MARKET_PM", ADMIN_ONLY_SAMPLE)).isFalse();
        assertThat(IpdRolePermissionCatalog.defaultPermissionsOf("GROUP_LEADER"))
            .as("基准线快照不受覆盖影响").doesNotContain(ADMIN_ONLY_SAMPLE);
    }

    @Test
    @DisplayName("③ REVOKE 收回：leader 失去默认码；同码 GRANT+REVOKE 时 REVOKE 胜出")
    void revoke_removesDefaultCode() {
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", LEADER_DEFAULT_SAMPLE)).isTrue();

        IpdRolePermissionCatalog.applyDbOverrides(Map.of(), Map.of("GROUP_LEADER", Set.of(LEADER_DEFAULT_SAMPLE)));
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", LEADER_DEFAULT_SAMPLE)).isFalse();

        IpdRolePermissionCatalog.applyDbOverrides(
            Map.of("GROUP_LEADER", Set.of(LEADER_DEFAULT_SAMPLE)), Map.of("GROUP_LEADER", Set.of(LEADER_DEFAULT_SAMPLE)));
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", LEADER_DEFAULT_SAMPLE))
            .as("REVOKE 语义优先于 GRANT").isFalse();
    }

    @Test
    @DisplayName("④ 覆盖行不可造新角色：未知 personType 仍返回空")
    void unknownRole_staysEmpty() {
        IpdRolePermissionCatalog.applyDbOverrides(Map.of("HACKER_ROLE", Set.of(ADMIN_ONLY_SAMPLE)), Map.of());

        assertThat(IpdRolePermissionCatalog.permissionsOf("HACKER_ROLE")).isEmpty();
        assertThat(IpdRolePermissionCatalog.permissionsOf(null)).isEmpty();
        assertThat(IpdRolePermissionCatalog.permissionsOf("  ")).isEmpty();
    }

    @Test
    @DisplayName("⑤ clearDbOverrides 回退默认 + 覆盖快照不可变")
    void clear_restoresDefaultAndSnapshotsImmutable() {
        IpdRolePermissionCatalog.applyDbOverrides(Map.of("RD_PM", Set.of(ADMIN_ONLY_SAMPLE)), Map.of());
        assertThat(IpdRolePermissionCatalog.currentGrants()).containsKey("RD_PM");
        assertThatThrownBy(() -> IpdRolePermissionCatalog.currentGrants().put("X", Set.of()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> IpdRolePermissionCatalog.currentRevokes().put("X", Set.of()))
            .isInstanceOf(UnsupportedOperationException.class);

        IpdRolePermissionCatalog.clearDbOverrides();

        assertThat(IpdRolePermissionCatalog.currentGrants()).isEmpty();
        assertThat(IpdRolePermissionCatalog.currentRevokes()).isEmpty();
        assertThat(IpdRolePermissionCatalog.has("RD_PM", ADMIN_ONLY_SAMPLE)).isFalse();
    }

    @Test
    @DisplayName("⑥ 元权限码固定归属：query/edit 仅在 SUPER_ADMIN 默认集（自举保护的目录层前提）")
    void metaCodes_pinnedToAdminOnly() {
        for (String code : new String[] {
            IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_QUERY,
            IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_EDIT }) {
            assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", code)).as("超管持有 %s", code).isTrue();
            assertThat(IpdRolePermissionCatalog.defaultPermissionsOf("GROUP_LEADER")).doesNotContain(code);
        }
    }
}
