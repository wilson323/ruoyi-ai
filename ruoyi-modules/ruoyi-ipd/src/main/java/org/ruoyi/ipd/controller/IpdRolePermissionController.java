package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.IpdRolePermission;
import org.ruoyi.ipd.dto.RolePermissionReq;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.service.IpdRolePermissionConfigService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * R215 权限可配置化 Controller（owner 指令 2026-09-24「确保权限可配置化」）。
 *
 * <p>5 端点（全部仅 SUPER_ADMIN；元权限不参与 DB 配置自身——自举保护，见 service 契约）：
 * <ul>
 *   <li>{@code GET    /api/v1/role-permissions}            — 覆盖行列表，ipd:role-permission:query</li>
 *   <li>{@code GET    /api/v1/role-permissions/effective}  — 各角色分层快照（默认/GRANT/REVOKE/生效），query</li>
 *   <li>{@code POST   /api/v1/role-permissions}            — 创建覆盖行（即时生效 + 审计），edit</li>
 *   <li>{@code DELETE /api/v1/role-permissions/{id}}       — 删除覆盖行（回退默认 + 审计），edit</li>
 *   <li>{@code POST   /api/v1/role-permissions/reload}     — 手工重建覆盖层（DBA 直改表后同步），edit</li>
 * </ul>
 *
 * <p>权限梯度：query/edit 两码均固定于 ADMIN_WRITE（Java 目录，不可被 DB 配置收回），
 * 注解 + requireAdmin 兜底同严（第六批判例）。
 */
@RestController
@RequestMapping("/api/v1/role-permissions")
@RequiredArgsConstructor
@Validated
class IpdRolePermissionController {

    private final IpdPermission ipdPermission;
    private final IpdRolePermissionConfigService configService;
    private final IAuditLogService auditLogService;

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping
    public ApiV1Response<List<IpdRolePermission>> list() {
        ipdPermission.requireAdmin();
        return ApiV1Response.ok(configService.list());
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/effective")
    public ApiV1Response<Map<String, Map<String, List<String>>>> effective() {
        ipdPermission.requireAdmin();
        return ApiV1Response.ok(configService.effectiveSnapshot());
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_EDIT, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping
    public ApiV1Response<IpdRolePermission> create(@Valid @RequestBody RolePermissionReq req) {
        IpdActor actor = ipdPermission.requireAdmin();
        IpdRolePermission row = configService.create(req);
        auditLogService.append(actor, "ROLE_PERMISSION_GRANT_CREATE", "ipd_role_permission", row.getId(),
            row.getPersonType() + " " + row.getEffect() + " " + row.getPermissionCode() + "；依据：" + row.getRemark());
        return ApiV1Response.ok(row);
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_EDIT, type = IpdAuthSession.LOGIN_TYPE)
    @DeleteMapping("/{id}")
    public ApiV1Response<Void> delete(@PathVariable Long id) {
        IpdActor actor = ipdPermission.requireAdmin();
        configService.delete(id);
        auditLogService.append(actor, "ROLE_PERMISSION_GRANT_DELETE", "ipd_role_permission", id,
            "覆盖行删除，该码回退 Java 默认集");
        return ApiV1Response.ok(null);
    }

    @SaCheckPermission(value = IpdPermissionCode.OPERATION_ROLE_PERMISSION_CONFIG_EDIT, type = IpdAuthSession.LOGIN_TYPE)
    @PostMapping("/reload")
    public ApiV1Response<Map<String, Integer>> reload() {
        IpdActor actor = ipdPermission.requireAdmin();
        configService.reload();
        auditLogService.append(actor, "ROLE_PERMISSION_RELOAD", "ipd_role_permission", null, "覆盖层手工重建");
        return ApiV1Response.ok(Map.of(
            "grantRoles", org.ruoyi.ipd.security.IpdRolePermissionCatalog.currentGrants().size(),
            "revokeRoles", org.ruoyi.ipd.security.IpdRolePermissionCatalog.currentRevokes().size()));
    }
}
