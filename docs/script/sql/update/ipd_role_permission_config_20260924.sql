-- R215 权限可配置化（owner 指令 2026-09-24「确保权限可配置化」）
-- ipd 域角色→权限码授予配置表：运行时覆盖 IpdRolePermissionCatalog 的 Java 默认矩阵。
-- 语义：某 person_type 在本表有生效行时，其有效码集 = Java 默认集 ∪ GRANT 行 − REVOKE 行；
--       表为空/缺行 ⇒ 纯 Java 默认（fail-closed 兜底，零漂移）。
-- 消费链：@SaCheckPermission(ipd) → IpdStpInterfaceBridge.permissionsOf → catalog（实时，无会话缓存）
--         /auth/me permissionCodes 同源 ⇒ 改配置 + POST /api/v1/role-permissions/reload 即时生效（无需重启）。
-- 回滚：DROP TABLE ipd_role_permission;（行为退回纯 Java 目录）

CREATE TABLE IF NOT EXISTS ipd_role_permission (
    id              BIGINT       NOT NULL COMMENT '雪花ID',
    person_type     VARCHAR(32)  NOT NULL COMMENT '角色：SUPER_ADMIN/GROUP_LEADER/MARKET_PM/RD_PM',
    permission_code VARCHAR(128) NOT NULL COMMENT '权限码字面量（ipd:xxx:yyy）',
    effect          VARCHAR(8)   NOT NULL DEFAULT 'GRANT' COMMENT 'GRANT=增授 / REVOKE=收回（对 Java 默认集做差）',
    remark          VARCHAR(256) NULL COMMENT '变更依据（卡号/拍板记录）',
    create_dept     BIGINT       NULL,
    create_by       BIGINT       NULL,
    create_time     DATETIME     NULL,
    update_by       BIGINT       NULL,
    update_time     DATETIME     NULL,
    tenant_id       VARCHAR(20)  NULL DEFAULT '000000',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ipd_role_perm (person_type, permission_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'IPD 角色权限码运行时配置（覆盖 Java 目录默认，R215 可配置化）';

-- 表须登记父 application.yml tenant.excludes（已在同轮代码提交中登记）
