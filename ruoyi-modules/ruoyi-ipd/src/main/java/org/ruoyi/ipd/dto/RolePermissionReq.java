package org.ruoyi.ipd.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * R215 权限可配置化：角色权限码覆盖行创建请求。
 *
 * @param personType     角色（四已知角色之一，服务层二次校验）
 * @param permissionCode 权限码字面量（ipd: 前缀；元权限码服务层拒绝）
 * @param effect         GRANT=增授 / REVOKE=收回
 * @param remark         变更依据（卡号/拍板记录，必填留痕）
 */
public record RolePermissionReq(
    @NotBlank(message = "personType 必填") String personType,
    @NotBlank(message = "permissionCode 必填")
    @Pattern(regexp = "^ipd:[a-z0-9-]+:[a-z0-9-]+$", message = "permissionCode 须为 ipd:xxx:yyy 形态") String permissionCode,
    @NotBlank(message = "effect 必填") @Pattern(regexp = "GRANT|REVOKE", message = "effect 仅允许 GRANT/REVOKE") String effect,
    @NotBlank(message = "remark 必填（变更依据：卡号/拍板记录）") String remark
) {
}
