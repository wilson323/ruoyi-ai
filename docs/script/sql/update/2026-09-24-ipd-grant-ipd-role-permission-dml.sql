-- R215 权限可配置化（2026-09-24）：ipd_app 应用账号表级授权登记件
-- 惯例对齐 2026-09-09-ipd-grant-c-batch-4-dml.sql：ipd_app 仅表级 Select/Insert/Update/Delete，
-- 新建表不 GRANT 则应用侧读空（本表表现为启动加载降级 warn + 覆盖层永远为空）。
-- 已于 2026-09-24 apply 到 ipd_dev@127.0.0.1:13306 并经 mysql.tables_priv 回读核验。
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.ipd_role_permission TO 'ipd_app'@'127.0.0.1';
FLUSH PRIVILEGES;
-- 回滚：REVOKE ALL ON ipd_dev.ipd_role_permission FROM 'ipd_app'@'127.0.0.1'; FLUSH PRIVILEGES;
