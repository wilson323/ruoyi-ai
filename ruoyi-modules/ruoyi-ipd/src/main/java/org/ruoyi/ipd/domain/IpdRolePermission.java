package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * IPD 角色权限码运行时配置（R215 权限可配置化，owner 指令 2026-09-24）。
 *
 * <p>覆盖 {@code IpdRolePermissionCatalog} 的 Java 默认矩阵：
 * 有效码集 = Java 默认 ∪ GRANT 行 − REVOKE 行；表空 ⇒ 纯 Java 行为（fail-closed 兜底）。
 *
 * <p>配置行是授权关系而非业务数据，走物理删除（不接 SoftDeletable 删除流）；
 * 元权限码（ipd:role-permission:*）由 service 层拒绝入库，防止管理员被自身配置锁在门外。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("ipd_role_permission")
public class IpdRolePermission extends BaseEntity {

    /** GRANT：在 Java 默认集之外增授。 */
    public static final String EFFECT_GRANT = "GRANT";
    /** REVOKE：从 Java 默认集中收回。 */
    public static final String EFFECT_REVOKE = "REVOKE";

    /**
     * 主键（雪花算法）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色：SUPER_ADMIN / GROUP_LEADER / MARKET_PM / RD_PM（不接受未知角色）。 */
    private String personType;

    /** 权限码字面量（ipd:xxx:yyy）。 */
    private String permissionCode;

    /** GRANT / REVOKE。 */
    private String effect;

    /** 变更依据（卡号/拍板记录）。 */
    private String remark;
}
