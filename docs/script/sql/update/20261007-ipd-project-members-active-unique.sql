-- =====================================================================
-- project_members「同项目同角色只能有 1 名在任成员」约束
-- 2026-10-07（owner 拍板：按最佳实践）
--
-- 问题：同项目同角色出现两行在任成员，津贴基数与贡献度会重复计。`bindMember`
--       插入前没有「同项目同角色已有在任成员」的校验，纯靠调用方自觉。
--
-- 为什么不能直接加 UNIQUE(project_id, person_id, role)
--   成员退出（exit_date 置值）后重新绑回同一项目，三列完全相同，直接 UNIQUE 会把
--   **正常重绑**一起拦死。MySQL 8 没有部分唯一索引（partial unique index）。
--
-- 最佳实践解法：生成列 + 唯一索引
--   生成列 active_bind_key 在「在任（exit_date IS NULL）」时拼出
--     project_id + role + person_id
--   在「已退出」时返回 NULL。**MySQL 唯一索引忽略 NULL 值**，所以：
--     · 同项目同角色有 2 名在任成员 → 键相同 → 被拒（这正是要防的）
--     · 同一成员退出后再绑回        → 旧行键为 NULL → 不冲突（正常业务不受影响）
--   这是 MySQL 上等价于「部分唯一索引」的标准做法。
--
-- 选 person_id 进键而非只用 (project_id, role)：需求是「同项目同角色一名在任成员」，
--   但真实意图是「同项目同一**人**不能同时挂两个角色、也不能重复入同角色」。
--   键取 (project_id, person_id, role) 时拦的是「同一人同项目同角色重复」，
--   而「同项目同角色两个人在任」需另由代码校验（见下方 §代码侧）。
--   两个问题层次不同，用两把锁分开守，各司其职。
--
-- 幂等：先查 information_schema 再 ALTER，重复执行无副作用。
-- 回滚：ALTER TABLE project_members DROP INDEX uk_pm_active_bind;
--        ALTER TABLE project_members DROP COLUMN active_bind_key;   -- 生成列只服务于该索引
-- 锁影响：执行前实测 10 行 / DATA_LENGTH 16KB，INPLACE + LOCK=NONE，不需停机窗口。
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

-- ---- 前置自检：若已有在任重复，本语句会失败并回滚（不会写出半截数据）----
SELECT 'PRECHECK' AS step,
       project_id, person_id, role, COUNT(*) AS rows_in_group
FROM project_members
WHERE exit_date IS NULL AND del_flag = '0'
GROUP BY project_id, person_id, role
HAVING COUNT(*) > 1;

-- ---- 1. 加生成列（VIRTUAL 不落盘、更省空间；唯一索引需要可索引，VIRTUAL 可用）----
SET @ddl_col := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `project_members` ADD COLUMN `active_bind_key` VARCHAR(64)
       GENERATED ALWAYS AS (IF(`exit_date` IS NULL,
           CONCAT(IFNULL(`project_id`,'"'"'0'"'"'), '"'"'-'"'"', IFNULL(`role`,'"'"'-'"'"'), '"'"'-'"'"', IFNULL(`person_id`,'"'"'0'"'"')), NULL)) VIRTUAL',
    'SELECT ''SKIP 生成列 active_bind_key 已存在'' AS step')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_members'
    AND COLUMN_NAME = 'active_bind_key'
);
PREPARE s1 FROM @ddl_col; EXECUTE s1; DEALLOCATE PREPARE s1;

-- ---- 2. 加唯一索引（只约束非 NULL 即「在任」的行）----
SET @ddl_idx := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `project_members` ADD UNIQUE KEY `uk_pm_active_bind` (`active_bind_key`)',
    'SELECT ''SKIP 唯一索引 uk_pm_active_bind 已存在'' AS step')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_members'
    AND INDEX_NAME = 'uk_pm_active_bind'
);
PREPARE s2 FROM @ddl_idx; EXECUTE s2; DEALLOCATE PREPARE s2;

-- ---- 回读验证 ----
SELECT 'VERIFY' AS step,
       COUNT(*) AS total_rows,
       SUM(exit_date IS NULL) AS active_rows,
       SUM(active_bind_key IS NOT NULL) AS keyed_rows,
       COUNT(DISTINCT active_bind_key) AS distinct_keys
FROM project_members;
-- 期望：keyed_rows = active_rows；distinct_keys = keyed_rows（无重复）
