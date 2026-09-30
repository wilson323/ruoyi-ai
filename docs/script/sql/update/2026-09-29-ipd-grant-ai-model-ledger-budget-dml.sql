-- 2026-09-29 C2-3f 真模型出站链探针暴露的授权缺口（同款第三撞，先例：gate_arbitrations 2026-09-08、product_lines 2026-09-29 任务A）。
-- ai_model_usage_ledger / ai_model_budget 由 2026-09-29-ai-model-usage-budget.sql 建表，
-- 但建表脚本未登记表级 CRUD 授权块 ⇒ ipd_app@127.0.0.1 只有库级 SELECT+INSERT：
--   实测 POST /api/v1/ai/suggest 链路 recordUsage INSERT 被拒静默失败（ledger 0 落账），
--   预算预占 UPDATE（CAS）同样被拒；S6 预算拒绝路径仍能 PASS 是因拒绝判定只读预算行（库级 SELECT 即可）。
-- 证据：SHOW GRANTS FOR 'ipd_app'@'127.0.0.1' 全 dump（/private/tmp/grants.txt，2026-09-29 21:4x）——
--   ai_model 前缀表级权限唯一命中 ai_model_configs。
-- GRANT 幂等（重复执行无害）；账号 host 模式为 127.0.0.1（mysql.user 实查）。
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.ai_model_usage_ledger TO 'ipd_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.ai_model_budget TO 'ipd_app'@'127.0.0.1';
-- 同步登记 E2 门禁规则：p1-ddl-apply-check.py GRANT_RULES 已补两表（下次治理轮自动巡检防再漏）。
-- 回读校验（应各输出 1 行含 Select,Insert,Update,Delete）：
--   SELECT table_name, table_priv FROM mysql.tables_priv
--   WHERE user='ipd_app' AND host='127.0.0.1' AND db='ipd_dev' AND table_name LIKE 'ai_model%';
-- 复验门禁：
--   python3 docs/ipd-系统说明/验收/p1-ddl-apply-check.py --dbs ipd_dev --strict
