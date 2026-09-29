-- 2026-09-29 任务A/A1 真实链路验收暴露的授权缺口（ruoyi-ipd-web 产品线空间端到端探针实证）：
-- product_lines / product_line_members 由 2026-09-29-ipd-product-line-space.sql 建表并 seed，
-- 但未登记表级 CRUD 授权 ⇒ 读链（库级 SELECT）全通、写链被拒：
--   POST /ipd/product-lines/{id}/join-applications 返回 HTTP 500 code=90001，
--   sys-error.log 原始异常：SELECT with locking clause command denied to user 'ipd_app'@'localhost'
--   for table 'product_lines'（SQL: ... FROM product_lines WHERE ... FOR UPDATE）。
-- 与 gate_arbitrations（2026-09-08 先例）同款修复路径。
-- GRANT 幂等（重复执行无害）；账号 host 模式为 127.0.0.1（mysql.user 实查）。
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.product_lines TO 'ipd_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.product_line_members TO 'ipd_app'@'127.0.0.1';
-- 回读校验（应各输出 1 行表级 GRANT）：
-- SHOW GRANTS FOR 'ipd_app'@'127.0.0.1';
