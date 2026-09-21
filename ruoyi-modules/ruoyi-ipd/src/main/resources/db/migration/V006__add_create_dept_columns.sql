-- =====================================================================
-- R152 B3 — 补齐 R149 batch2a DDL 漏建的 create_dept 列
-- 日期：2026-09-20
--
-- 背景：R152 B2 真活 e2e 测试发现 5 端点返 500，根因 batch2a 三表
--       (kpi_raw_records / landed_scenarios / recovery_warnings)
--       漏建 RuoYi 框架 BaseEntity 强制列 create_dept。
--       与 R149 batch1 的 ipd_business_config 一致：需 AFTER create_by。
--
-- 影响：T4/T5/T6/T7/T9 共 5 端点（MyBatis-Plus BaseEntity.insertFill 报
--       Unknown column 'create_dept' in 'field list'）。
--
-- 回滚：ALTER TABLE xxx DROP COLUMN create_dept;
-- =====================================================================

ALTER TABLE kpi_raw_records   ADD COLUMN create_dept bigint NULL AFTER create_by;
ALTER TABLE landed_scenarios  ADD COLUMN create_dept bigint NULL AFTER create_by;
ALTER TABLE recovery_warnings ADD COLUMN create_dept bigint NULL AFTER create_by;
