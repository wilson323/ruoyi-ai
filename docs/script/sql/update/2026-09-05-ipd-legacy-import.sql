-- P1-9.1 / BR-PROD-03：存量导入 LEGACY 元数据 + 动作「历史缺失」标记（不伪造 DONE）
-- 运行账号需 ALTER 权限（mysql-migrator / root）；随后 GRANT 给 ipd_app 若缺列权限。


-- [idem-guard: ALTER projects.declared_stage]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='projects' AND COLUMN_NAME='declared_stage');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE projects ADD COLUMN declared_stage VARCHAR(16) NULL COMMENT ''存量申报当前阶段（展示/补齐目标）'' AFTER current_stage',
  'SELECT ''projects.declared_stage exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER projects.legacy_effective_at]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='projects' AND COLUMN_NAME='legacy_effective_at');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE projects ADD COLUMN legacy_effective_at DATETIME NULL COMMENT ''存量导入生效日'' AFTER source',
  'SELECT ''projects.legacy_effective_at exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER projects.missing_history_ack]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='projects' AND COLUMN_NAME='missing_history_ack');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE projects ADD COLUMN missing_history_ack CHAR(1) NULL DEFAULT ''0'' COMMENT ''历史缺失声明 1=已确认'' AFTER legacy_effective_at',
  'SELECT ''projects.missing_history_ack exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER projects.catchup_status]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='projects' AND COLUMN_NAME='catchup_status');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE projects ADD COLUMN catchup_status VARCHAR(16) NULL COMMENT ''IN_PROGRESS|COMPLETE 补齐状态'' AFTER missing_history_ack',
  'SELECT ''projects.catchup_status exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER stage_actions.history_mark]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stage_actions' AND COLUMN_NAME='history_mark');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE stage_actions ADD COLUMN history_mark VARCHAR(32) NULL COMMENT ''HISTORICAL_MISSING=历史缺失（不伪造DONE）'' AFTER status',
  'SELECT ''stage_actions.history_mark exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;
