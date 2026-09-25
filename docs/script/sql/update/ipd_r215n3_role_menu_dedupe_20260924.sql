-- R215-N3：IPD 平台桥 QA 角色菜单授权越权收敛（owner 指令 2026-09-24「确保权限可配置化」）
-- 背景：sys_role_menu 把框架「系统管理(1)/用户管理(100)/角色管理(101)」授给 IPD 桥角色
--       900201(ipd_pm 普通PM) / 900202(ipd_group_leader 产品组长)，导致 leader/market/rd
--       侧栏外露 admin 专属框架菜单（R215 T-V2 实测）。
-- 性质：纯配置数据修正（RBAC 授权表本身即事实源，可经「角色管理→菜单权限」界面再配置）；
--       不动代码、不动 ipd 域权限目录。
-- 影响：仅 6 行授权关系；页面可达性由后端 sys 接口 RBAC 兜底（本就有二次闸）。
-- 回滚：见文件尾 INSERT。

START TRANSACTION;

DELETE FROM sys_role_menu
WHERE role_id IN (900201, 900202)
  AND menu_id IN (1, 100, 101);
-- 预期 ROW_COUNT = 6

COMMIT;

-- 自检（应返回 0 行）：
-- SELECT rm.role_id, rm.menu_id FROM sys_role_menu rm
--  WHERE rm.role_id IN (900201,900202) AND rm.menu_id IN (1,100,101);

-- 回滚（如需恢复现场）：
-- INSERT INTO sys_role_menu (role_id, menu_id) VALUES
--   (900201,1),(900201,100),(900201,101),
--   (900202,1),(900202,100),(900202,101);
