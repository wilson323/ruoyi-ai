-- =============================================================================
-- P3-LOW 字符集治理决策包 — sys_user.user_name vs persons.username COLLATE 不一致
-- =============================================================================
-- 卡号:P3-LOW(sys_user↔persons 字符集不一致隐患)
-- UUID:639de2c8-a910-456c-b550-7c0248f48972
-- 现状(2026-09-18 fresh 现查真库 ipd_dev @ 13306 socket):
--   sys_user.user_name   COLLATE = utf8mb4_0900_ai_ci
--   persons.username     COLLATE = utf8mb4_general_ci
-- 触发场景:跨表 JOIN(SELECT su.user_name, p.username FROM sys_user su JOIN persons p ON su.user_name = p.username)
--   → ERROR 1267 (HY000): Illegal mix of collations (utf8mb4_0900_ai_ci,IMPLICIT) and (utf8mb4_general_ci,IMPLICIT) for operation '='
-- 暂不阻塞:IPD 走独立 Sa-Token loginType=ipd + persons 自有认证,业务代码不 JOIN 这两张表
-- 阻塞场景:框架基线用户管理(RuoYi admin)与 IPD 人员互通时(sys_user 同步人员、统一权限视图)
--
-- 重要:本 SQL 草稿为决策包,**DO NOT APPLY WITHOUT OWNER APPROVAL**
-- 撞车 0 红线:本会话不擅自 apply,等 owner 拍板窗口
-- 五必现查(R13)证据时间戳:2026-09-18 09:45 PDT 真库 ipd_dev @ 13306
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 方案 A1(推荐):改 persons.username → utf8mb4_0900_ai_ci(与 sys_user 对齐)
-- 选型依据:
--   ① IPD 业务核心表是 persons,基线 sys_user 是 RuoYi 框架表;改 IPD 表影响面更可控
--   ② persons 表规模 27 行(实测),索引重建瞬时,不影响生产
--   ③ utf8mb4_0900_ai_ci 是 MySQL 8 默认(更新 Unicode 9.0 规则,与基线 sys_user 对齐)
-- 风险:
--   - IPD 业务 SELECT WHERE username=? 查询路径需复核,MyBatis 不会自动加 COLLATE,需要 SQL 层显式 COLLATE
--   - 唯一索引 uk_persons_username 重建,瞬时锁表(27 行,实测 <1s)
--   - 应用连接池缓存 prepared statement 可能 stale,需重启服务
-- -----------------------------------------------------------------------------
-- 实施 SQL(待 owner 拍板):
ALTER TABLE persons
  MODIFY COLUMN username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '登录账号(默认手机号)';

-- 回滚 SQL(如有需要):
-- ALTER TABLE persons
--   MODIFY COLUMN username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '登录账号(默认手机号)';


-- -----------------------------------------------------------------------------
-- 方案 A2:改 sys_user.user_name → utf8mb4_general_ci(与 persons 对齐)
-- 选型依据:
--   ① sys_user 是基线 RuoYi 框架表,改它可能影响其他模块
--   ② 数据规模 4 行(实测),影响小
-- 风险:
--   - RuoYi 基线 sys_user 可能被其他业务模块依赖(用户管理 / 角色分配 / 部门树)
--   - 改动需要基线团队确认,owner 拍板需更高层级
--   - MySQL 8 默认 utf8mb4_0900_ai_ci,改回 utf8mb4_general_ci 等于降级
-- -----------------------------------------------------------------------------
-- 实施 SQL(待 owner 拍板):
-- ALTER TABLE sys_user
--   MODIFY COLUMN user_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录账号';

-- 回滚 SQL(如有需要):
-- ALTER TABLE sys_user
--   MODIFY COLUMN user_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '登录账号';


-- -----------------------------------------------------------------------------
-- 方案 A3:统一到 utf8mb4_0900_ai_ci(MySQL 8 默认)
-- 与 A1 等价,但强调"前进式升级"语义
-- 适用场景:owner 倾向接受基线升级,且 sys_user 已对齐 — A3 = A1
-- -----------------------------------------------------------------------------
-- (同 A1 SQL)


-- -----------------------------------------------------------------------------
-- 验证 SQL(任意方案 ALTER 后跑)
-- -----------------------------------------------------------------------------
-- 1. 验证 JOIN 不再报 1267:
SELECT su.user_name, p.username
FROM sys_user su JOIN persons p ON su.user_name = p.username
LIMIT 1;

-- 2. 验证 persons 表字符集已更新:
SELECT TABLE_NAME, COLUMN_NAME, COLLATION_NAME, CHARACTER_SET_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='ipd_dev'
  AND TABLE_NAME IN ('sys_user','persons')
  AND COLUMN_NAME IN ('user_name','username');

-- 3. 验证 IPD 业务查询路径(IPD 走 persons 表,基线走 sys_user):
SELECT id, username, person_type, status
FROM persons WHERE username = 'ipd-admin';

-- 4. 验证 uk_persons_username 唯一索引仍生效:
SHOW INDEX FROM persons WHERE Key_name='uk_persons_username';


-- -----------------------------------------------------------------------------
-- 撞车 0 守则严守
-- -----------------------------------------------------------------------------
-- - 本 SQL 草稿仅供决策,**撞车 0 红线:不擅自 apply**
-- - owner 拍板前,真库 ipd_dev 保持原状(sys_user=utf8mb4_0900_ai_ci / persons=utf8mb4_general_ci)
-- - 拍板后,选执行窗口:
--   - 凌晨低峰(00:00-05:00)
--   - 应用连接池先清空(停服 5 分钟)
--   - ALTER + 验证 SQL 在 5 分钟内完成
--   - 回滚预案:ALTER 失败立即执行回滚 SQL
--   - 应用层 prepared statement 缓存:ALTER 后重启服务

-- =============================================================================
-- 决策包登记(撞车 0 + 等 owner 拍板)
-- =============================================================================
-- 选项        | 改动范围     | 影响面          | 推荐度 | owner 拍板状态
-- ----------- | ------------ | --------------- | ------ | ----------------
-- A1 (推荐)   | persons 表   | IPD 业务核心    | ★★★★★ | 待 owner
-- A2          | sys_user 表  | 基线 RuoYi 框架 | ★★     | 待 owner
-- A3          | 同 A1        | 同 A1           | ★★★★★ | 待 owner
-- 维持现状    | 无           | 1267 持续       | ★      | 暂不阻塞(独立认证)
-- =============================================================================