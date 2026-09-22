-- =============================================================================
-- W2-CHARSET 字符集整改 — 4 批 in-place ALTER（paiban-04 决策包 A 方案）
-- =============================================================================
-- 卡号: W2-CHARSET (字符集整改) →  paiban-04 (571 字拍板)
-- 时间: 2026-09-21 21:11 真库 ipd_dev @ 13306
-- 授权: owner 完整执行授权 (2026-09-21 21:09)
-- 备份: /tmp/w2-charset-backup-20260921/ 65 张 SHOW CREATE TABLE 基线
-- 范围: 66 张表 (1 sys_ + 10 project_ + 4 person_/kpi_ + 51 other)
-- 工作量: DDL 5-10 min / 重启 13s / HTTP 验证 5min
-- 风险: 表级锁 DML / 应用 prepared statement stale (需重启) / 索引重建
-- 回滚: 文件末尾 4 批反向 ALTER (从 /tmp/w2-charset-backup-20260921 重建)
-- 五必现查: 13306 实证 / 91 0900_ai_ci / 66 非 0900_ai_ci = 91+66=157 总表
-- =============================================================================

SELECT 'PRE-CHECK 1: 全部非 0900_ai_ci 表清单' AS phase;
SELECT table_name, table_collation FROM information_schema.tables
WHERE table_schema='ipd_dev' AND table_collation NOT LIKE 'utf8mb4\\_0900\\_ai\\_ci'
ORDER BY table_name;

SELECT '===== BATCH 1: sys_* 框架表 (1 张) =====' AS phase;

-- 批 1: sys_menu (1 张, utf8mb4_general_ci → utf8mb4_0900_ai_ci)
ALTER TABLE ipd_dev.sys_menu
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SELECT 'BATCH 1 POST-VERIFY: sys_menu' AS phase;
SHOW CREATE TABLE ipd_dev.sys_menu\\G

SELECT '===== BATCH 2: project_* 业务表 (10 张) =====' AS phase;

-- 批 2: project_cert_items / project_circle_comments / project_circle_posts /
--       project_followers / project_members / project_score_records /
--       project_score_tasks / project_scores / project_stages / projects
ALTER TABLE ipd_dev.project_cert_items CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.project_circle_comments CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.project_circle_posts CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.project_followers CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.project_members CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.project_score_records CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.project_score_tasks CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.project_scores CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.project_stages CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.projects CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SELECT 'BATCH 2 POST-VERIFY: 10 张 project_/projects' AS phase;
SELECT table_name, table_collation FROM information_schema.tables
WHERE table_schema='ipd_dev' AND (table_name LIKE 'project\\_%' OR table_name='projects')
ORDER BY table_name;

SELECT '===== BATCH 3: person_* + kpi_* 业务表 (4 张) =====' AS phase;

-- 批 3: kpi_records / kpi_rule_snapshots / kpi_shared_confirms / person_sync_jobs
ALTER TABLE ipd_dev.kpi_records CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.kpi_rule_snapshots CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.kpi_shared_confirms CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.person_sync_jobs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SELECT 'BATCH 3 POST-VERIFY: 4 张 person_/kpi_' AS phase;
SELECT table_name, table_collation FROM information_schema.tables
WHERE table_schema='ipd_dev' AND (table_name LIKE 'person\\_%' OR table_name LIKE 'kpi\\_%' OR table_name='persons' OR table_name='kpi_records')
ORDER BY table_name;

SELECT '===== BATCH 4: other 业务表 (51 张) =====' AS phase;

-- 批 4: 其余 51 张: _ipd_schema_history + ai_ + allowance_ledgers + audit_* +
--   bid_ + bonus_ + cert_templates + coefficient_change_requests + contributions +
--   correction_logs + deletion_requests + deliverables + gate_* + handover_records +
--   ipd_business_config + launch_date_change_requests + legacy_imports + mcp_* +
--   multi_project_capacity_approvals + negative_feedbacks + notification_events +
--   persons_bk_b3_20260919 + post_launch_reviews + product_* + rd_replacement_* +
--   receipt_ledgers + requirement_* + sop_* + stage_actions + switching_acceptances +
--   system_config_*
ALTER TABLE ipd_dev._ipd_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.ai_doc_embeddings CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.ai_documents CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.ai_model_configs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.allowance_ledgers CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.audit_log_chain_heads CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.audit_logs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.bid_invitations CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.bid_responses CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.bonus_allocations CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.bonus_pools CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.cert_templates CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.coefficient_change_requests CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.contributions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.correction_logs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.deletion_requests CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.deliverables CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.gate_element_results CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.gate_review_elements CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.gate_review_observers CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.gate_reviews CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.gate_waivers CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.gates CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.handover_records CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.ipd_business_config CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.ipd_business_config_versions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.launch_date_change_requests CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.legacy_imports CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.mcp_market_info CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.mcp_market_tool CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.mcp_tool_info CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.multi_project_capacity_approvals CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.negative_feedbacks CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.notification_events CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.persons_bk_b3_20260919 CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.post_launch_reviews CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.product_groups CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.product_retirements CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.products CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.rd_replacement_approvals CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.rd_replacements CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.receipt_ledgers CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.requirement_changes CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.requirement_pools CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.requirements CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.sop_template_instances CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.sop_templates CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.stage_actions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.switching_acceptances CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.system_config_versions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE ipd_dev.system_configs CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SELECT '===== POST-VERIFY: 全表字符集目标达成 =====' AS phase;
SELECT table_collation, COUNT(*) AS table_count FROM information_schema.tables
WHERE table_schema='ipd_dev' GROUP BY table_collation ORDER BY table_count DESC;

-- =============================================================================
-- 回滚 SOP（紧急 ROLLBACK 用）
-- 从 /tmp/w2-charset-backup-20260921/<table>.sql 提取原始 CREATE TABLE 替换
-- 或用 mysql --defaults-file=... < /tmp/w2-charset-backup-20260921/<table>.sql
-- 重启后端: kill -9 PID && nohup java -jar ruoyi-admin.jar ... &
-- =============================================================================
