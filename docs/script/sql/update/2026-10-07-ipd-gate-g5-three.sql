-- =====================================================================
-- G5 三件配套（2026-10-07）—— DO NOT APPLY，等 owner 窗口
--
-- ⚠️ 本文件是**草案**，尚未 apply。DDL 属不可逆副作用，按修复队列六刀 §9 第 8 项
--    「清污执行窗口需 owner 确认后执行」的同一纪律，需 owner 在场时手动执行。
--
-- 三件的现库事实（2026-10-07 实测，非照抄台账）：
--   ① gate_review_observers 无 tenant_id 列（14 列实测清单：id/gate_id/observer_id/
--      role/invited_by/invited_at/attended/opinion/del_flag/create_* /update_*）
--   ② gates 只有 idx_gates_project（非唯一，单列 project_id）+ PRIMARY(id)；
--      无 (project_id, gate_code, current_round) 唯一约束 ⇒ 同一项目同一 Gate 同轮
--      可重复建行，F6 的「在途去重」与 G3 双周复评多轮承载都缺库级兜底
--   ③ gate_review_observers.opinion 为 varchar(1000)，列席意见 1000 字截断
--
-- 幂等：三条均先查 information_schema 再改，重复执行无副作用。
-- 前置自检：② 加唯一约束前必须确认无重复键（见下方 STEP 0，实测 gates=0 行）。
-- 回滚：文件末尾 ROLLBACK 段，三行可单独执行。
-- 验证：执行后跑 SHOW COLUMNS / SHOW INDEX 回读，再跑
--       python3 docs/ipd-系统说明/验收/p1-ddl-apply-check.py --dbs ipd_dev --strict
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

-- ---------------------------------------------------------------------
-- STEP 0 · 前置自检：② 的唯一约束前提（gates 存在重复键则必须先处理，不得强加）
-- ---------------------------------------------------------------------
SELECT 'PRECHECK_gates_duplicate_keys' AS step,
       COUNT(*)                          AS total_rows,
       COUNT(DISTINCT project_id, gate_code, current_round) AS distinct_keys,
       CASE WHEN COUNT(*) = COUNT(DISTINCT project_id, gate_code, current_round)
            THEN 'OK 可加唯一约束'
            ELSE 'BLOCKED 存在重复键，禁止加唯一约束，先人工清理'
       END                              AS verdict
FROM `gates`;

-- ---------------------------------------------------------------------
-- ① gate_review_observers 加 tenant_id
--
-- 口径说明：application.yml 的 tenant.excludes **保持排除**该表（单企业私有部署
-- 语义，与 gates / gate_reviews 兄弟表一致）。加列是为了列结构对齐、且为将来
-- 多租户化留口，不是为了现在就让它参与租户过滤——两件事不要混。
-- ---------------------------------------------------------------------
SET @ddl_observers_tenant := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `gate_review_observers` ADD COLUMN `tenant_id` varchar(20) NULL DEFAULT ''000000'' COMMENT ''租户编号（单企业语义同兄弟表，当前仍排除于 tenant.excludes）'' AFTER `id`',
    'SELECT ''SKIP ① tenant_id 已存在，无需重复执行'' AS step')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gate_review_observers' AND COLUMN_NAME = 'tenant_id'
);
PREPARE s1 FROM @ddl_observers_tenant; EXECUTE s1; DEALLOCATE PREPARE s1;

-- ---------------------------------------------------------------------
-- ② gates 加唯一约束 uk_gates_project_code_round
--
-- 命名沿用仓内 uk_ 前缀惯例（对比 idx_ = 普通索引 / uk_ = 唯一约束）。
-- current_round 参与键，是为 G3 双周复评的「多轮承载」形态预留：同一 project+gate
-- 允许 N 轮，但同一轮不得重复建行。若后续 owner 拍板 G3 不做多轮，可退化为
-- (project_id, gate_code) —— 改唯一键前须先清历史重复轮。
-- ---------------------------------------------------------------------
SET @ddl_gates_uk := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `gates` ADD UNIQUE KEY `uk_gates_project_code_round` (`project_id`, `gate_code`, `current_round`)',
    'SELECT ''SKIP ② uk_gates_project_code_round 已存在，无需重复执行'' AS step')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gates' AND INDEX_NAME = 'uk_gates_project_code_round'
);
PREPARE s2 FROM @ddl_gates_uk; EXECUTE s2; DEALLOCATE PREPARE s2;

-- ---------------------------------------------------------------------
-- ③ opinion 列宽 1000 → 2000
--
-- 用 MODIFY 而非 CHANGE：只放宽宽度，不动 NULL 性、默认值、字符集与 COMMENT。
-- 放宽对存量数据安全（不截断），回滚即收紧——收紧会在超长数据上报错，
-- 故 ROLLBACK 段标注为「需先裁数据」。
-- ---------------------------------------------------------------------
SET @ddl_observers_opinion := (
  SELECT IF(MAX(CHARACTER_MAXIMUM_LENGTH) = 1000,
    'ALTER TABLE `gate_review_observers` MODIFY COLUMN `opinion` varchar(2000) NULL DEFAULT NULL COMMENT ''列席意见''',
    'SELECT ''SKIP ③ opinion 已是 varchar(2000) 或更宽，无需重复执行'' AS step')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gate_review_observers' AND COLUMN_NAME = 'opinion'
);
PREPARE s3 FROM @ddl_observers_opinion; EXECUTE s3; DEALLOCATE PREPARE s3;

-- ---------------------------------------------------------------------
-- 回读验证（apply 后必跑，三条都应返回预期形状）
-- ---------------------------------------------------------------------
SELECT 'VERIFY_①' AS step, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gate_review_observers' AND COLUMN_NAME = 'tenant_id'
UNION ALL
SELECT 'VERIFY_③', COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gate_review_observers' AND COLUMN_NAME = 'opinion';

SELECT 'VERIFY_②' AS step, INDEX_NAME,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, NON_UNIQUE
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gates'
 GROUP BY INDEX_NAME, NON_UNIQUE
 ORDER BY INDEX_NAME;

-- =====================================================================
-- ROLLBACK（三行，独立执行；按需注释其中一行）
--
-- ③ 收紧回 varchar(1000) 会在存在 >1000 字的意见时报错
--    （MySQL 8 严格模式）。执行前先自检：
--      SELECT COUNT(*) FROM gate_review_observers WHERE CHAR_LENGTH(opinion) > 1000;
--    >0 则先裁数据，不要直接跑回滚。
-- =====================================================================
-- ALTER TABLE `gate_review_observers` MODIFY COLUMN `opinion` varchar(1000) NULL DEFAULT NULL COMMENT '列席意见';
-- ALTER TABLE `gates` DROP INDEX `uk_gates_project_code_round`;
-- ALTER TABLE `gate_review_observers` DROP COLUMN `tenant_id`;
