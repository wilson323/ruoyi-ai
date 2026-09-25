-- R215-RPFE 侧栏入口登记：角色权限配置（超管配置页，owner 2026-09-24「确保权限可配置化」收尾）
-- 背景：R215 第三层「角色→权限码矩阵」超管配置页前端已交付（ruoyi-ipd-web 3764830，
--       页面静态路由 /ipd/admin/role-permission 可达，但侧栏无入口）。
--       侧栏菜单唯一事实源 = 后端 getRouters（sys_menu + sys_role_menu，ipd 模块 MenuController，
--       前端 access.ts generatePlatformAccess「站内唯一生效的菜单/路由构建入口」）。
--       遵循既有惯例（产品目录 …030 / 人员同步 …031 / 超级管理 …032）：
--       在 IPD 工作台(…001)下新增 C 型行，component 指到真实视图文件 ipd/admin/role-permission.vue。
-- 性质：纯配置数据登记（RBAC 授权表即事实源，可经「角色管理→菜单权限」界面再配置）；
--       不动代码、不动业务表、不动其他菜单行。
-- 影响：新增 1 行 sys_menu + 1 行 sys_role_menu；仅超级管理员(role_id=1)可见。
-- 幂等：先 DELETE 同 id + INSERT IGNORE（20260925 清理轮经官方工具 scripts/make_sql_idempotent.py 模板 G 转换，
--       check-ddl-idempotent.sh 由 FAIL 转 OK；语义不变——原已 DELETE-first，IGNORE 仅使重复执行不报 1062）。
-- 回滚：见文件尾。

START TRANSACTION;

DELETE FROM sys_role_menu WHERE menu_id = 2099010200000000033;

DELETE FROM sys_menu WHERE menu_id = 2099010200000000033;

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param,
   is_frame, is_cache, menu_type, visible, status, perms, icon,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (2099010200000000033, '角色权限配置', 2099010200000000001, 17,
   'role-permission', 'ipd/admin/role-permission', NULL,
   1, 0, 'C', 0, 0, 'ipd:role-permission:view', 'lucide:shield-check',
   103, 1, NOW(), NULL, NULL,
   '角色权限配置 - 角色→权限码 GRANT/REVOKE 覆盖层（超管；R215-RPFE，前端 3764830）');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 2099010200000000033);

COMMIT;

-- 自检（应各返回 1 行）：
-- SELECT menu_id, menu_name, path, component FROM sys_menu WHERE menu_id = 2099010200000000033;
-- SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id = 2099010200000000033;

-- 回滚：
-- DELETE FROM sys_role_menu WHERE menu_id = 2099010200000000033;
-- DELETE FROM sys_menu WHERE menu_id = 2099010200000000033;
