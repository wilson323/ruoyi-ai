package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdRolePermission;
import org.ruoyi.ipd.dto.RolePermissionReq;
import org.ruoyi.ipd.mapper.IpdRolePermissionMapper;
import org.ruoyi.ipd.security.IpdRolePermissionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R215 权限可配置化服务（owner 指令 2026-09-24）：角色→权限码 DB 覆盖层。
 *
 * <p>行为契约（与 {@link IpdRolePermissionCatalog} 注释一致）：
 * <ul>
 *   <li>有效码集 = Java 默认 ∪ GRANT − REVOKE；表空 ⇒ 纯 Java 行为（零漂移兜底）；</li>
 *   <li>启动加载 fail-open 降级：表不存在/查询异常 ⇒ 记 warn 并清空覆盖层，绝不阻断应用启动；</li>
 *   <li>写路径三重拒绝：未知角色、非 ipd: 码、元权限码（ipd:role-permission:*，防管理员自锁）；</li>
 *   <li>create/delete 成功后自动 reload（即时生效，无需重启）；DBA 手工改表走 reload 端点。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpdRolePermissionConfigService {

    /** 元权限码前缀：不允许经 DB 配置增删（自举保护）。 */
    static final String META_CODE_PREFIX = "ipd:role-permission:";

    private final IpdRolePermissionMapper mapper;

    /** 启动加载：表缺失/异常时静默降级 Java 默认（首日本地库未 apply SQL 也可正常启动）。 */
    @PostConstruct
    void loadOnStartup() {
        try {
            reload();
            log.info("[R215] ipd 权限覆盖层启动加载完成：grants={} revokes={}",
                IpdRolePermissionCatalog.currentGrants().size(), IpdRolePermissionCatalog.currentRevokes().size());
        } catch (Exception e) {
            IpdRolePermissionCatalog.clearDbOverrides();
            log.warn("[R215] ipd_role_permission 表不可用，权限目录回退纯 Java 默认（不阻断启动）: {}", e.getMessage());
        }
    }

    /** 从表重建运行时覆盖层并原子替换。 */
    public void reload() {
        Map<String, Set<String>> grants = new HashMap<>();
        Map<String, Set<String>> revokes = new HashMap<>();
        for (IpdRolePermission row : mapper.selectList(new LambdaQueryWrapper<>())) {
            if (!IpdRolePermissionCatalog.knownRoles().contains(row.getPersonType())) {
                log.warn("[R215] 忽略未知角色的权限覆盖行: id={} role={}", row.getId(), row.getPersonType());
                continue;
            }
            if (row.getPermissionCode() != null && row.getPermissionCode().startsWith(META_CODE_PREFIX)) {
                log.warn("[R215] 忽略元权限覆盖行（自举保护）: id={} code={}", row.getId(), row.getPermissionCode());
                continue;
            }
            Map<String, Set<String>> target =
                IpdRolePermission.EFFECT_REVOKE.equals(row.getEffect()) ? revokes : grants;
            target.computeIfAbsent(row.getPersonType(), k -> new java.util.HashSet<>())
                .add(row.getPermissionCode());
        }
        IpdRolePermissionCatalog.applyDbOverrides(grants, revokes);
    }

    /** 覆盖行列表（按角色+码排序）。 */
    public List<IpdRolePermission> list() {
        return mapper.selectList(new LambdaQueryWrapper<IpdRolePermission>()
            .orderByAsc(IpdRolePermission::getPersonType)
            .orderByAsc(IpdRolePermission::getPermissionCode));
    }

    /** 各角色有效快照：Java 默认 / DB GRANT / DB REVOKE / 最终生效集（配置界面数据源）。 */
    public Map<String, Map<String, List<String>>> effectiveSnapshot() {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (String role : List.of("SUPER_ADMIN", "GROUP_LEADER", "MARKET_PM", "RD_PM")) {
            Map<String, List<String>> byLayer = new LinkedHashMap<>();
            byLayer.put("javaDefault", IpdRolePermissionCatalog.defaultPermissionsOf(role));
            byLayer.put("dbGrant", List.copyOf(IpdRolePermissionCatalog.currentGrants().getOrDefault(role, Set.of())));
            byLayer.put("dbRevoke", List.copyOf(IpdRolePermissionCatalog.currentRevokes().getOrDefault(role, Set.of())));
            byLayer.put("effective", IpdRolePermissionCatalog.permissionsOf(role));
            out.put(role, byLayer);
        }
        return out;
    }

    /** 创建覆盖行（校验 + 查重 + 落库 + 即时 reload）。 */
    @Transactional(rollbackFor = Exception.class)
    public IpdRolePermission create(RolePermissionReq req) {
        if (!IpdRolePermissionCatalog.knownRoles().contains(req.personType())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "未知角色：" + req.personType() + "（仅支持 " + IpdRolePermissionCatalog.knownRoles() + "）");
        }
        if (req.permissionCode().startsWith(META_CODE_PREFIX)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "元权限码不参与 DB 配置（防止管理员授权通道被自身配置锁死）");
        }
        Long dup = mapper.selectCount(new LambdaQueryWrapper<IpdRolePermission>()
            .eq(IpdRolePermission::getPersonType, req.personType())
            .eq(IpdRolePermission::getPermissionCode, req.permissionCode()));
        if (dup != null && dup > 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "该角色+权限码已有覆盖行（改语义请先删后建）");
        }
        IpdRolePermission row = IpdRolePermission.builder()
            .personType(req.personType())
            .permissionCode(req.permissionCode())
            .effect(req.effect())
            .remark(req.remark())
            .build();
        mapper.insert(row);
        reload();
        return row;
    }

    /** 删除覆盖行（物理删；该码回退 Java 默认；即时 reload）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        IpdRolePermission row = mapper.selectById(id);
        if (row == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "覆盖行不存在: " + id);
        }
        mapper.deleteById(id);
        reload();
    }
}
