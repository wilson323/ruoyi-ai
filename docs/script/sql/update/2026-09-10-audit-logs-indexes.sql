-- ============================================================
-- audit_logs 补两个核心查询路径索引（R29 · 2026-09-10）
-- 状态：已于 2026-09-10 apply 到本机 dev 库 ipd_dev（13306）并 EXPLAIN 回读验证；
--       生产 apply 仍待 DBA 维护窗口。
-- 背景：审计接口 /api/v1/audit-logs 主查询路径 (entity_type, entity_id) 原走全表扫，
--       10w+ 行后秒级退化；草稿与论证见 docs/ipd-系统说明/验收/audit-logs-索引DDL草稿-20260910.md。
-- 幂等性：CREATE INDEX 无 IF NOT EXISTS，重放前先查
--       SHOW INDEX FROM audit_logs WHERE Key_name IN ('idx_al_entity_type_id','idx_al_entity_type_time');
-- ============================================================

-- 索引 1：业务审计查询主路径（按实体查全历史）

-- [idem-guard: CREATE INDEX idx_al_entity_type_id ON audit_logs]
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='audit_logs' AND INDEX_NAME='idx_al_entity_type_id');
SET @ddl := IF(@idx_exists=0,
  'CREATE INDEX idx_al_entity_type_id
    ON audit_logs (entity_type, entity_id)',
  'SELECT ''audit_logs.idx_al_entity_type_id exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 索引 2：时间窗审计扫描（按实体+时间范围查）

-- [idem-guard: CREATE INDEX idx_al_entity_type_time ON audit_logs]
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='audit_logs' AND INDEX_NAME='idx_al_entity_type_time');
SET @ddl := IF(@idx_exists=0,
  'CREATE INDEX idx_al_entity_type_time
    ON audit_logs (entity_type, create_time)',
  'SELECT ''audit_logs.idx_al_entity_type_time exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回滚（如需）：
-- DROP INDEX idx_al_entity_type_id ON audit_logs;
-- DROP INDEX idx_al_entity_type_time ON audit_logs;
