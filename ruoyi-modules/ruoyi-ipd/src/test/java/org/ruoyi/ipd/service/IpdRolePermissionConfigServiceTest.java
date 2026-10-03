package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdRolePermission;
import org.ruoyi.ipd.dto.RolePermissionReq;
import org.ruoyi.ipd.mapper.IpdRolePermissionMapper;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdRolePermissionCatalog;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R215 权限可配置化服务单测（mock mapper，不触 DB）。
 *
 * <p>覆盖：写路径三重拒绝（未知角色 / 元权限码 / 查重）、删除 NOT_FOUND、
 * reload 脏行过滤（未知角色 + 元码忽略）、启动加载异常降级不阻断、有效快照分层。
 * catalog 合并语义另见 IpdRolePermissionDbOverrideContractTest（纯 JVM 契约）。
 *
 * <p><b>2026-10-03 样本码替换说明</b>：本类原先借用奖金池两码当对照样本（compute / distribute）；
 * 奖金池业务域退役后该两码已删。本类测的是「配置服务校验与降级」，不依赖具体业务码，故替换为
 * 两个仍然存续的样本码（{@link #ADMIN_ONLY_SAMPLE} 非默认码 / {@link #LEADER_DEFAULT_SAMPLE}
 * 组长默认码），断言语义与替换前逐条等价。
 */
@Tag("dev")
@DisplayName("R215：角色权限配置服务校验与降级")
class IpdRolePermissionConfigServiceTest {

    /** 样本码 A：仅 SUPER_ADMIN 默认持有（非 GROUP_LEADER 默认集）——增授/查重/降级用例。 */
    private static final String ADMIN_ONLY_SAMPLE = IpdPermissionCode.OPERATION_PERMANENT_DELETE;
    /** 样本码 B：GROUP_LEADER 默认持有（DELETION_LEADER 集合）——收回/快照基准线用例。 */
    private static final String LEADER_DEFAULT_SAMPLE = IpdPermissionCode.OPERATION_CONTRIBUTION_CONFIRM;

    private IpdRolePermissionMapper mapper;
    private IpdRolePermissionConfigService service;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "r215-role-perm-test"),
            IpdRolePermission.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(IpdRolePermissionMapper.class);
        service = new IpdRolePermissionConfigService(mapper);
        IpdRolePermissionCatalog.clearDbOverrides();
    }

    private IpdRolePermission row(Long id, String role, String code, String effect) {
        IpdRolePermission r = IpdRolePermission.builder()
            .personType(role).permissionCode(code).effect(effect).remark("test").build();
        r.setId(id);
        return r;
    }

    @Test
    @DisplayName("① create 拒绝未知角色（DB 配置不可造新角色）")
    void create_rejectsUnknownRole() {
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(
            new RolePermissionReq("HACKER", ADMIN_ONLY_SAMPLE, "GRANT", "R215")))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("未知角色");
        verify(mapper, never()).insert(any(IpdRolePermission.class));
    }

    @Test
    @DisplayName("② create 拒绝元权限码（自举保护：防管理员被自身配置锁死）")
    void create_rejectsMetaCode() {
        assertThatThrownBy(() -> service.create(new RolePermissionReq(
            "SUPER_ADMIN", IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_EDIT, "REVOKE", "R215")))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        verify(mapper, never()).insert(any(IpdRolePermission.class));
    }

    @Test
    @DisplayName("③ create 查重：同角色+同码已有覆盖行 → STATE_CONFLICT")
    void create_rejectsDuplicate() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(new RolePermissionReq(
            "GROUP_LEADER", ADMIN_ONLY_SAMPLE, "GRANT", "R215")))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT));
        verify(mapper, never()).insert(any(IpdRolePermission.class));
    }

    @Test
    @DisplayName("④ create 成功：落库 + 即时 reload 生效（GRANT 码立即可见）")
    void create_grantsAndReloads() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(List.of(
            row(9L, "GROUP_LEADER", ADMIN_ONLY_SAMPLE, "GRANT")));

        IpdRolePermission created = service.create(new RolePermissionReq(
            "GROUP_LEADER", ADMIN_ONLY_SAMPLE, "GRANT", "R215 端到端"));

        verify(mapper).insert(any(IpdRolePermission.class));
        assertThat(created.getEffect()).isEqualTo("GRANT");
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", ADMIN_ONLY_SAMPLE))
            .as("create 后无需重启即生效").isTrue();
    }

    @Test
    @DisplayName("⑤ delete：行不存在 → NOT_FOUND；存在 → 物理删 + reload 回退默认")
    void delete_removesAndReloads() {
        assertThatThrownBy(() -> service.delete(404L))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.NOT_FOUND));

        when(mapper.selectById(9L)).thenReturn(
            row(9L, "GROUP_LEADER", ADMIN_ONLY_SAMPLE, "GRANT"));
        when(mapper.selectList(any())).thenReturn(List.of());

        service.delete(9L);

        verify(mapper).deleteById(9L);
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", ADMIN_ONLY_SAMPLE))
            .as("删覆盖行后回退 Java 默认（leader 默认集不含该码）").isFalse();
    }

    @Test
    @DisplayName("⑥ reload 过滤脏行：未知角色、元权限码进不了覆盖层")
    void reload_filtersDirtyRows() {
        when(mapper.selectList(any())).thenReturn(List.of(
            row(1L, "GROUP_LEADER", ADMIN_ONLY_SAMPLE, "GRANT"),
            row(2L, "HACKER_ROLE", ADMIN_ONLY_SAMPLE, "GRANT"),
            row(3L, "SUPER_ADMIN", IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_EDIT, "REVOKE")));

        service.reload();

        Map<String, java.util.Set<String>> grants = IpdRolePermissionCatalog.currentGrants();
        assertThat(grants.keySet()).containsExactly("GROUP_LEADER");
        assertThat(IpdRolePermissionCatalog.currentRevokes()).isEmpty();
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_EDIT))
            .as("DB 里的元码 REVOKE 脏行被忽略，超管元权限不丢").isTrue();
    }

    @Test
    @DisplayName("⑦ 启动加载降级：表不可用（查询抛错）→ 不阻断启动 + 覆盖层清空")
    void loadOnStartup_degradesOnMissingTable() {
        when(mapper.selectList(any())).thenThrow(new RuntimeException("Table 'ipd_role_permission' doesn't exist"));

        assertThatCode(() -> service.loadOnStartup()).doesNotThrowAnyException();

        assertThat(IpdRolePermissionCatalog.currentGrants()).isEmpty();
        assertThat(IpdRolePermissionCatalog.permissionsOf("GROUP_LEADER"))
            .as("降级后回纯 Java 默认")
            .containsExactlyInAnyOrderElementsOf(IpdRolePermissionCatalog.defaultPermissionsOf("GROUP_LEADER"));
    }

    @Test
    @DisplayName("⑧ 有效快照：四层结构齐全且 effective=默认∪GRANT−REVOKE")
    void effectiveSnapshot_layers() {
        when(mapper.selectList(any())).thenReturn(List.of(
            row(1L, "GROUP_LEADER", ADMIN_ONLY_SAMPLE, "GRANT"),
            row(2L, "GROUP_LEADER", LEADER_DEFAULT_SAMPLE, "REVOKE")));
        service.reload();

        Map<String, Map<String, List<String>>> snap = service.effectiveSnapshot();

        assertThat(snap.keySet()).containsExactlyInAnyOrder("SUPER_ADMIN", "GROUP_LEADER", "MARKET_PM", "RD_PM");
        Map<String, List<String>> leader = snap.get("GROUP_LEADER");
        assertThat(leader.keySet()).containsExactly("javaDefault", "dbGrant", "dbRevoke", "effective");
        assertThat(leader.get("javaDefault"))
            .contains(LEADER_DEFAULT_SAMPLE)
            .doesNotContain(ADMIN_ONLY_SAMPLE);
        assertThat(leader.get("effective"))
            .contains(ADMIN_ONLY_SAMPLE)
            .doesNotContain(LEADER_DEFAULT_SAMPLE);
    }
}
