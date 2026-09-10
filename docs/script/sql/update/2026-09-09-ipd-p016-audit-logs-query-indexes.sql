-- P0-16（R27 治理轮 17 项 P0 基线第 16 项）：audit_logs 补两个查询索引
-- 背景：audit_logs 是全业务写路径的审计事实表（当前 seq=1401..2576，未来 10w+ 行）。
--   实体溯源（entity_type + entity_id 等值）与时间线查询（entity_type + create_time 范围）
--   均无索引支撑，行数增长后按实体/按月审计导出会退化为全表扫。
-- 幂等：重复执行安全（显式 information_schema 存在性检查，同 2026-09-09-ipd-perf04 模式）。
-- 生效条件：须人工/DBA apply 到真库 ipd_dev（13306）后本脚本才算落地；
--   「SQL 已 commit」不等于「索引已生效」（AGENTS.md 构建/测试节既有教训）。

SET @idx_entity_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_logs'
      AND index_name = 'idx_audit_entity'
);
SET @sql := IF(@idx_entity_exists = 0,
    'CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_time_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_logs'
      AND index_name = 'idx_audit_entity_time'
);
SET @sql := IF(@idx_time_exists = 0,
    'CREATE INDEX idx_audit_entity_time ON audit_logs(entity_type, create_time)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
