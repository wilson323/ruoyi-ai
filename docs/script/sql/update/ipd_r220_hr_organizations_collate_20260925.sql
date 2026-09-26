-- ipd_r220_hr_organizations_collate_20260925.sql
-- 来源：R220 门禁实跑定性（docs/ipd-系统说明/log.md，marker r220-orphan-gate-live-run）
--
-- 问题：scripts/check-charset-consistency.sh 实测 RC=1，报 ipd_dev 157 表 / 1164 字符列中
--       14 处与期望（utf8mb4 + utf8mb4_0900_ai_ci）不一致。明细全部集中在 hr_organizations
--       一张表：13 个字符列（orgeh / stext / short_name / parent_orgeh / zbmcj / begda / endda /
--       bmfzr / hr_del_flag / expiration_flag / trigger_by / tenant_id / del_flag）+ 1 个表级
--       default collation = 14。字符集本身已是 utf8mb4，只有排序规则是 utf8mb4_general_ci。
--       该表此前已在 log.md 登记为「charset 待归入下次清扫（ops 待办）」，本轮按 owner 拍板 A
--       路线（先清存量红再接线）处理。
--
-- 影响：hr_organizations 实测 0 行（SELECT COUNT(*)=0，information_schema.TABLE_ROWS=0），
--       故 CONVERT TO 属空表元数据变更，无数据重写、无锁等待风险。
--       回退方式：把 COLLATE 换回 utf8mb4_general_ci 再执行一次同样的语句。
--
-- 已 apply：2026-09-25 本机 ipd_dev（MySQL 8.0.46 @ 127.0.0.1:13306）。执行后回读
--       TABLE_COLLATION = utf8mb4_0900_ai_ci；information_schema 不一致字符列计数 = 0；
--       scripts/check-charset-consistency.sh 由 RC=1 转 RC=0
--       （概览 tables=157 char_cols=1164 inconsistent=0，输出「✅ PASS: 字符集全一致」）。
--
-- 待 apply：隔离库 ipd_qa04 及其他环境若存在同表同问题，需各自执行本文件（本仓无 Flyway/
--       Liquibase，docs/script/sql/update/** 靠人工 apply，SQL 已 commit 不等于约束已生效）。

ALTER TABLE hr_organizations
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 验证（两条应分别返回 utf8mb4_0900_ai_ci 与 0）：
-- SELECT TABLE_COLLATION FROM information_schema.TABLES
--  WHERE TABLE_SCHEMA = 'ipd_dev' AND TABLE_NAME = 'hr_organizations';
-- SELECT COUNT(*) FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = 'ipd_dev'
--    AND CHARACTER_SET_NAME IS NOT NULL AND CHARACTER_SET_NAME != ''
--    AND (CHARACTER_SET_NAME != 'utf8mb4' OR COLLATION_NAME != 'utf8mb4_0900_ai_ci');
