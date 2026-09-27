-- E1-① 工作流三控制器权限码登记（方案乙：分级最小补洞）
-- 拍板记录：docs/ipd-系统说明/E1权限码方案对比-20260927.md 方案乙，owner 2026-09-27「按推荐完整执行」。
-- 背景：FlwTaskController / FlwInstanceController / FlwDefinitionController 零权限注解，
--       任何登录用户可 completeTask / 删实例 / 全量待办越权；本脚本与 Java 注解同批交付（只挂注解不配 SQL = 非超管 403 熔断审批链）。
-- 口径（方案乙，8 码契约固定，与前端仓同名，不得改名/增删/自造）：
--   管理/越权面挂码；个人自助面（startWorkFlow/completeTask/backProcess/cancelProcessApply/urgeTask/taskOperation/pageByTaskWait 等）保持仅登录、零注解。
--   workflow:definition:add    -> add, copy
--   workflow:definition:edit   -> edit, publish, unPublish, active
--   workflow:definition:remove -> remove
--   workflow:definition:import -> importDef, exportDef（POST 导出类写端点）
--   workflow:instance:remove   -> deleteByBusinessIds, deleteByInstanceIds, deleteHisByInstanceIds
--   workflow:instance:edit     -> active, updateVariable, invalid
--   workflow:task:edit         -> updateAssignee, terminationTask
--   workflow:task:queryAll     -> pageByAllTaskWait, pageByAllTaskFinish
-- menu_id 选段 11900-11907：已查 docs/script/sql/ruoyi-ai.sql 既有登记段与 ipd_dev 库 sys_menu，零碰撞。
-- parent_id 惯例（对齐 workflow:category:* F 行挂 C 菜单）：definition 码挂 11620 流程定义；instance 码挂 11621 流程实例；task 码挂 11631 待办任务(allTaskWaiting，pageByAll* 与任务干预按钮消费页)。
-- ★ 生产环境 apply 需 DBA 窗口（本脚本 2026-09-27 已在 ipd_dev@127.0.0.1:13306 验证执行）。
-- 性质：纯配置数据登记（RBAC 授权表即事实源，可经「菜单管理→角色管理」界面再配置）；不动业务表。
-- 幂等：DELETE-first + INSERT IGNORE（重复执行不报 1062，语义不变）。
-- 回滚：见文件尾。

START TRANSACTION;

-- ----------------------------
-- 1. sys_menu 8 条 F 类按钮行（perms 契约码）
-- ----------------------------
DELETE FROM sys_role_menu WHERE menu_id BETWEEN 11900 AND 11907;
DELETE FROM sys_menu WHERE menu_id BETWEEN 11900 AND 11907;

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param,
   is_frame, is_cache, menu_type, visible, status, perms, icon,
   create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (11900, '工作流定义新增', 11620, 1, '#', '', '', 1, 0, 'F', '0', '0', 'workflow:definition:add',    '#', 103, 1, NOW(), NULL, NULL, 'E1-①方案乙：FlwDefinitionController add/copy'),
  (11901, '工作流定义修改', 11620, 2, '#', '', '', 1, 0, 'F', '0', '0', 'workflow:definition:edit',   '#', 103, 1, NOW(), NULL, NULL, 'E1-①方案乙：FlwDefinitionController edit/publish/unPublish/active'),
  (11902, '工作流定义删除', 11620, 3, '#', '', '', 1, 0, 'F', '0', '0', 'workflow:definition:remove', '#', 103, 1, NOW(), NULL, NULL, 'E1-①方案乙：FlwDefinitionController remove'),
  (11903, '工作流定义导入', 11620, 4, '#', '', '', 1, 0, 'F', '0', '0', 'workflow:definition:import', '#', 103, 1, NOW(), NULL, NULL, 'E1-①方案乙：FlwDefinitionController importDef/exportDef(POST)'),
  (11904, '工作流实例删除', 11621, 1, '#', '', '', 1, 0, 'F', '0', '0', 'workflow:instance:remove',   '#', 103, 1, NOW(), NULL, NULL, 'E1-①方案乙：FlwInstanceController deleteByBusinessIds/deleteByInstanceIds/deleteHisByInstanceIds'),
  (11905, '工作流实例修改', 11621, 2, '#', '', '', 1, 0, 'F', '0', '0', 'workflow:instance:edit',     '#', 103, 1, NOW(), NULL, NULL, 'E1-①方案乙：FlwInstanceController active/updateVariable/invalid'),
  (11906, '工作流任务干预', 11631, 1, '#', '', '', 1, 0, 'F', '0', '0', 'workflow:task:edit',         '#', 103, 1, NOW(), NULL, NULL, 'E1-①方案乙：FlwTaskController updateAssignee/terminationTask'),
  (11907, '工作流任务全量查询', 11631, 2, '#', '', '', 1, 0, 'F', '0', '0', 'workflow:task:queryAll',     '#', 103, 1, NOW(), NULL, NULL, 'E1-①方案乙：FlwTaskController pageByAllTaskWait/pageByAllTaskFinish');

-- ----------------------------
-- 2. 角色授权（仅管理角色；普通员工零配置变化、零业务中断）
--    免授权主体：user_id=1（SysPermissionServiceImpl.getLoginMenuPermission -> LoginHelper.isSuperAdmin(userId)=SUPER_ADMIN_ID=1L 得 *:*:*，
--    实测 role_id=1(superadmin) 仅 user_id=1 持有）。
--    管理角色（2026-09-27 现查 ipd_dev.sys_role / sys_user_role 实存）：
--      900202 产品组长(ipd_group_leader，用户 910102)。
--    按方案乙「仅管理角色授权（组长/超管）；普通员工零配置变化」：900201 普通PM(ipd_pm，用户 910103/910104)
--    属普通员工，不授予管理码（2026-09-27 主会话复核撤回过授）。
--    注意事实漂移：*:*:* 直通按 user_id 判定而非 role_key，故 role_key='admin'(2018858143199662082 管理员) 不免授权；
--    该角色当前 0 用户持有，未纳入下方授权（见文尾注释模板，未来挂人前须先补授权）。
-- ----------------------------
DELETE FROM sys_role_menu
 WHERE menu_id BETWEEN 11900 AND 11907;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM sys_role r
  JOIN sys_menu m
    ON m.perms IN (
       'workflow:definition:add',    'workflow:definition:edit',
       'workflow:definition:remove', 'workflow:definition:import',
       'workflow:instance:remove',   'workflow:instance:edit',
       'workflow:task:edit',         'workflow:task:queryAll')
 WHERE r.role_id IN (900202)
   AND r.del_flag = '0';

-- （备查）管理角色 role_key='admin'(2018858143199662082) 授权模板——当前 0 用户持有暂不启用；
-- 若未来向该角色挂用户，先解除注释执行，否则新挂用户将对上述 8 类端点 403：
-- DELETE FROM sys_role_menu WHERE menu_id BETWEEN 11900 AND 11907 AND role_id = 2018858143199662082;
-- INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
-- SELECT 2018858143199662082, menu_id FROM sys_menu
--  WHERE perms IN ('workflow:definition:add','workflow:definition:edit','workflow:definition:remove','workflow:definition:import',
--                  'workflow:instance:remove','workflow:instance:edit','workflow:task:edit','workflow:task:queryAll');

COMMIT;

-- 自检（应返回 8 行 / 8 行，后者仅 900202）：
-- SELECT menu_id, menu_name, parent_id, perms FROM sys_menu WHERE menu_id BETWEEN 11900 AND 11907 ORDER BY menu_id;
-- SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id BETWEEN 11900 AND 11907 ORDER BY role_id, menu_id;

-- 回滚：
-- DELETE FROM sys_role_menu WHERE menu_id BETWEEN 11900 AND 11907;
-- DELETE FROM sys_menu WHERE menu_id BETWEEN 11900 AND 11907;
