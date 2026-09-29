-- B2 项目级小阶段推进状态。仅交付迁移脚本；本任务不执行 DDL。
-- 既有项目游标保持 NULL，不猜测已完成小阶段；应用可按 current_stage 首节点启动。
-- DBA 在停写窗口执行。应用部署前须回验三列；可安全重放已成功的完整脚本。

-- 前置版本断言：projects 基础表与本切片依赖的旧列必须存在。
SET @b2_base_columns := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects'
      AND COLUMN_NAME IN ('id', 'current_stage', 'tenant_id', 'del_flag', 'status'));
SET @b2_preflight := IF(@b2_base_columns = 5,
    'SELECT ''B2 base schema ready'' AS status',
    'SELECT b2_missing_base_schema FROM projects LIMIT 0');
PREPARE b2_stmt FROM @b2_preflight;
EXECUTE b2_stmt;
DEALLOCATE PREPARE b2_stmt;

-- 幂等加列；若某次执行中断，重跑只补缺失列。
SET @b2_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects'
      AND COLUMN_NAME = 'current_sub_stage_code');
SET @b2_ddl := IF(@b2_exists = 0,
    'ALTER TABLE projects ADD COLUMN current_sub_stage_code varchar(32) NULL COMMENT ''当前已进入的小阶段码；NULL=未启动'' AFTER current_stage',
    'SELECT ''current_sub_stage_code exists, skip'' AS status');
PREPARE b2_stmt FROM @b2_ddl;
EXECUTE b2_stmt;
DEALLOCATE PREPARE b2_stmt;

SET @b2_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects'
      AND COLUMN_NAME = 'sub_stage_version');
SET @b2_ddl := IF(@b2_exists = 0,
    'ALTER TABLE projects ADD COLUMN sub_stage_version bigint NOT NULL DEFAULT 0 COMMENT ''小阶段推进乐观版本'' AFTER current_sub_stage_code',
    'SELECT ''sub_stage_version exists, skip'' AS status');
PREPARE b2_stmt FROM @b2_ddl;
EXECUTE b2_stmt;
DEALLOCATE PREPARE b2_stmt;

SET @b2_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects'
      AND COLUMN_NAME = 'last_sub_stage_gate_result');
SET @b2_ddl := IF(@b2_exists = 0,
    'ALTER TABLE projects ADD COLUMN last_sub_stage_gate_result varchar(16) NULL COMMENT ''最近成功推进的门禁结果 PASSED'' AFTER sub_stage_version',
    'SELECT ''last_sub_stage_gate_result exists, skip'' AS status');
PREPARE b2_stmt FROM @b2_ddl;
EXECUTE b2_stmt;
DEALLOCATE PREPARE b2_stmt;

-- 后置版本断言：防已有同名列但类型、空值或默认值不兼容时静默跳过。
SET @b2_valid_columns := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects'
      AND ((COLUMN_NAME = 'current_sub_stage_code' AND DATA_TYPE = 'varchar'
            AND CHARACTER_MAXIMUM_LENGTH = 32 AND IS_NULLABLE = 'YES')
        OR (COLUMN_NAME = 'sub_stage_version' AND DATA_TYPE = 'bigint'
            AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = '0')
        OR (COLUMN_NAME = 'last_sub_stage_gate_result' AND DATA_TYPE = 'varchar'
            AND CHARACTER_MAXIMUM_LENGTH = 16 AND IS_NULLABLE = 'YES')));
SET @b2_postflight := IF(@b2_valid_columns = 3,
    'SELECT ''B2 progress schema verified'' AS status',
    'SELECT b2_progress_schema_mismatch FROM projects LIMIT 0');
PREPARE b2_stmt FROM @b2_postflight;
EXECUTE b2_stmt;
DEALLOCATE PREPARE b2_stmt;

-- 只读回验：新列形态与游标/版本异常行。
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects'
  AND COLUMN_NAME IN ('current_sub_stage_code', 'sub_stage_version', 'last_sub_stage_gate_result')
ORDER BY ORDINAL_POSITION;
SELECT COUNT(*) AS invalid_progress_rows FROM projects
WHERE (sub_stage_version <> 0 AND current_sub_stage_code IS NULL)
   OR (sub_stage_version = 0 AND current_sub_stage_code IS NOT NULL);
