-- =====================================================================
-- R-2② qa04 缺列回补（live-form）：gate_review_elements.sign_due_at / sign_extension_count
--   定义来源：ipd_dev 线上库 SHOW CREATE TABLE（2026-09-29 实测，只读会话），按线上真实
--     形态逐字回写；docs/script/sql/schema/schema-snapshot-2026-09-09.sql 的
--     gate_review_elements 建表块交叉印证一致（`datetime DEFAULT NULL COMMENT '签署到期'`
--     / `int DEFAULT NULL COMMENT '签署延期次数'`）。
--   为何不直接重放既有 2026-09-06-ipd-qa04-real-issues-ddl.sql：
--     ① 该脚本涉及 8 张表，其中 ipd_business_config_versions / project_scores /
--        switching_acceptances / negative_feedbacks 在 ipd_qa04（29 表的部分库）不存在，
--        整份重放必在第 5 条 ERROR 1146 中断，后面真正缺的列也补不上；
--     ② 它给 gate_review_elements.sign_extension_count 的定义是
--        `int NOT NULL DEFAULT 0 COMMENT '签署期限已延长次数（副本列）'`，实为 gates 表形态
--        （snapshot 中 `CREATE TABLE gates` 段为 `int NOT NULL DEFAULT '0' COMMENT
--        '签署期限已延长次数（AC-GATE-21 上限 3）'`），套到本表会与 ipd_dev 实况
--        （`int DEFAULT NULL COMMENT '签署延期次数'`）产生新的跨库漂移。
--   其余 4 列（status / version / veto_dual_required / threshold_json）与唯一键
--     uk_gate_element_code 在 2026-09-06-ipd-p161-gate-element-lifecycle.sql 中的定义
--     与 ipd_dev 逐字一致，直接重放该脚本即可，本脚本不重复定义（避免第二事实源）。
--   执行顺序有依赖：p161 必须先跑——本脚本两列的 AFTER 锚点 threshold_json / sign_due_at
--     由 p161 与本脚本第 1 条分别建立。
-- 幂等：每列先查 information_schema.COLUMNS，存在即跳过
--   （范式同 2026-09-05-ipd-qa04d2-live-drift-backport.sql）。
-- 范围：仅 DDL。只在 ipd_qa04 执行；ipd_dev 已含两列，重放为零变更 no-op。
-- 已知残留差异（不在本卡范围，已登记）：ipd_qa04 库默认 collation=utf8mb4_general_ci，
--   ipd_dev=utf8mb4_0900_ai_ci——2026-09-21-w2-charset-4batches-in-place-alter.sql
--   显式只针对 ipd_dev，属库级字符集议题，需另开卡裁决。
-- 日期：2026-09-29
-- =====================================================================

-- 1) sign_due_at（AFTER threshold_json，与 ipd_dev 列位一致）
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gate_review_elements'
        AND COLUMN_NAME = 'sign_due_at') = 0,
    'ALTER TABLE gate_review_elements ADD COLUMN sign_due_at datetime DEFAULT NULL COMMENT ''签署到期'' AFTER threshold_json',
    'SELECT ''gate_review_elements.sign_due_at exists, skip'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) sign_extension_count（AFTER sign_due_at）
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'gate_review_elements'
        AND COLUMN_NAME = 'sign_extension_count') = 0,
    'ALTER TABLE gate_review_elements ADD COLUMN sign_extension_count int DEFAULT NULL COMMENT ''签署延期次数'' AFTER sign_due_at',
    'SELECT ''gate_review_elements.sign_extension_count exists, skip'' AS msg');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
