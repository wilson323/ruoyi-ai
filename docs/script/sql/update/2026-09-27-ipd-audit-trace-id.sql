-- OPS-06：为已建库追加请求链路定位列；历史审计行保持 NULL，不回填、不重算哈希。
-- 新建库使用 2026-09-04-ipd-p0-tables.sql，已包含此列，不重复执行本迁移。
-- 本文件由迁移账号在确认 audit_logs.trace_id 不存在后执行。
ALTER TABLE audit_logs
    ADD COLUMN trace_id varchar(64) NULL COMMENT 'OPS-06 请求链路 ID（定位元数据，不入审计哈希）';
