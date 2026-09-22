-- 2026-09-21 paiban-03 单数表名整改 —— 3 表 in-place RENAME（单数 → 复数）
-- 授权：docs/ipd-系统说明/拍板决策包/OWNER-拍板登记-20260921.md §九项#4（P3 全部完整执行）
--        + §例外 3（DDL 对象为本机开发库 ipd_dev@127.0.0.1:13306，须先备份 + 反向回滚 SOP）
-- 影响面：Entity @TableName ×3 / application.yml tenant.excludes ×3 / DDL 存档表名 / TenantExcludes 断言
--          / GRANT 登记件（check-ipd-grant-sql.py TABLES + p1-ddl-apply-check.py GRANT_RULES）
-- 备份：/tmp/paiban03-backup/{requirement_pool,receipt_ledger,switching_acceptance}-{schema,count}.sql
-- 前提：NONE——本文件对应「单数表名」态；已 apply 复数名后请勿重放（源表不存在会报错）。
--
-- 【重要】RENAME TABLE 不会迁移表级权限（mysql.tables_priv 仍挂旧名）：
--   ipd_app 对 ipd_dev 仅库级 SELECT，写权靠逐表 GRANT。故 RENAME 后必须补授新名并回收旧名孤儿授权，
--   否则 UPDATE/DELETE 被拒 → HTTP 500（本仓已多次实证的塌缩模式）。

-- 1) 三表 in-place RENAME
RENAME TABLE ipd_dev.requirement_pool   TO ipd_dev.requirement_pools;
RENAME TABLE ipd_dev.receipt_ledger     TO ipd_dev.receipt_ledgers;
RENAME TABLE ipd_dev.switching_acceptance TO ipd_dev.switching_acceptances;

-- 2) 表级 GRANT 随名迁移（补授新名）
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.requirement_pools     TO 'ipd_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.receipt_ledgers       TO 'ipd_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.switching_acceptances TO 'ipd_app'@'127.0.0.1';
FLUSH PRIVILEGES;

-- 3) 回收旧名孤儿授权（RENAME 不会自动清理 mysql.tables_priv 旧名行）
REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.requirement_pool     FROM 'ipd_app'@'127.0.0.1';
REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.receipt_ledger       FROM 'ipd_app'@'127.0.0.1';
REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.switching_acceptance FROM 'ipd_app'@'127.0.0.1';
FLUSH PRIVILEGES;

-- 4) 执行后校验（期望：3 行表名 + tables_priv 3 行 FULL，且旧名 0 行）
-- SELECT table_name FROM information_schema.tables
--  WHERE table_schema='ipd_dev' AND (table_name LIKE 'requirement_pool%' OR table_name LIKE 'receipt_ledger%' OR table_name LIKE 'switching_acceptance%');
-- SELECT table_name, table_priv FROM mysql.tables_priv
--  WHERE db='ipd_dev' AND user='ipd_app'
--    AND (table_name LIKE 'requirement_pool%' OR table_name LIKE 'receipt_ledger%' OR table_name LIKE 'switching_acceptance%');

-- 反向回滚 SOP（等价于 git revert + 反向 DDL）：
-- RENAME TABLE ipd_dev.requirement_pools   TO ipd_dev.requirement_pool;
-- RENAME TABLE ipd_dev.receipt_ledgers     TO ipd_dev.receipt_ledger;
-- RENAME TABLE ipd_dev.switching_acceptances TO ipd_dev.switching_acceptance;
-- GRANT  SELECT, INSERT, UPDATE, DELETE ON ipd_dev.requirement_pool     TO 'ipd_app'@'127.0.0.1';
-- GRANT  SELECT, INSERT, UPDATE, DELETE ON ipd_dev.receipt_ledger       TO 'ipd_app'@'127.0.0.1';
-- GRANT  SELECT, INSERT, UPDATE, DELETE ON ipd_dev.switching_acceptance TO 'ipd_app'@'127.0.0.1';
-- REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.requirement_pools     FROM 'ipd_app'@'127.0.0.1';
-- REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.receipt_ledgers       FROM 'ipd_app'@'127.0.0.1';
-- REVOKE SELECT, INSERT, UPDATE, DELETE ON ipd_dev.switching_acceptances FROM 'ipd_app'@'127.0.0.1';
