-- V003__business_config_scope_id.sql
-- R149 batch2b A5：ipd_business_config 加 scope_id 列，支持 GROUP/PROJECT scope 维度
-- 表已有 scope 字段（GLOBAL|GROUP|PROJECT 三档），但缺 scope_id；本次只补 scope_id + 新唯一键。
--
-- 与既有 13 行 GLOBAL seed 不冲突：scope_id 默认可空（GLOBAL 行 scope_id=NULL），
-- 新唯一键 (scope, scope_id, config_key) 对 GLOBAL 行等价于 (GLOBAL, NULL, key)——仍唯一。
--
-- ⚠️ 本文件为 R149 batch2b 草稿，未 apply：
--   - 由 R149-batch2b 任务提供 SQL 草稿（不 apply）
--   - 由 DBA 在 R150 合入主树时执行
--   - 业务代码已就绪（BusinessConfigService.getConfig/upsert），DDL apply 后立即生效

-- 1. 添加 scope_id 列（允许 NULL：GLOBAL 行 NULL，GROUP/PROJECT 行填 group_id/project_id）
SET @col_check_v003 := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'ipd_business_config'
     AND COLUMN_NAME = 'scope_id'
);
SET @sql_v003a := IF(@col_check_v003 = 0,
  'ALTER TABLE ipd_business_config ADD COLUMN scope_id VARCHAR(64) NULL AFTER scope',
  'SELECT 1');
PREPARE stmt_v003a FROM @sql_v003a;
EXECUTE stmt_v003a;
DEALLOCATE PREPARE stmt_v003a;

-- 2. 添加索引（scope, scope_id, config_key）三列组合索引，供精确查询走索引
SET @idx_check_v003 := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'ipd_business_config'
     AND INDEX_NAME = 'idx_ibc_scope'
);
SET @sql_v003b := IF(@idx_check_v003 = 0,
  'ALTER TABLE ipd_business_config ADD INDEX idx_ibc_scope (scope, scope_id, config_key)',
  'SELECT 1');
PREPARE stmt_v003b FROM @sql_v003b;
EXECUTE stmt_v003b;
DEALLOCATE PREPARE stmt_v003b;

-- 3. （可选）删除原 uk_ibc_key 唯一键约束，替换为 (scope, scope_id, config_key) 复合唯一键
--    兼容策略：保留 uk_ibc_key，GLOBAL 维度复用之；GROUP/PROJECT 维度由应用层 SELECT 校验唯一。
--    若 DBA 选择替换唯一键，需手动执行：
--    ALTER TABLE ipd_business_config DROP INDEX uk_ibc_key;
--    ALTER TABLE ipd_business_config ADD UNIQUE KEY uk_ibc_scope_key (scope, scope_id, config_key);

-- 4. （草稿）示例 2 行 GROUP scope 配置（kpi.approvalRole / scenario.approvalRole）
--    scope_id = 1001 表示产品组 ID；具体值由 DBA/R149-task-owner 决策后填。
--    注：种子仅作文档示例，apply 时需根据真实 product_group.id 替换 1001。
INSERT IGNORE INTO ipd_business_config
  (config_key, config_value, value_type, scope, scope_id, enabled, version, cache_ttl, description, tenant_id, del_flag)
VALUES
  ('kpi.approvalRole', 'GROUP_LEADER', 'STRING', 'GROUP', '1001', 1, 1, 300, 'KPI 审批人角色（GROUP 维度示例；R149 batch2b A5 草稿）', '000000', '0'),
  ('scenario.approvalRole', 'SUPER_ADMIN', 'STRING', 'GROUP', '1001', 1, 1, 300, '场景审批人角色（GROUP 维度示例；R149 batch2b A5 草稿）', '000000', '0');
