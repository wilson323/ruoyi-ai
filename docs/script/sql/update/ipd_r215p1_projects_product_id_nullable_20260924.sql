-- R215-P1（看板卡 44fda61a）：projects.product_id 改为可空
-- 根因：ProductSoftDeleteExecutor 软删产品时级联释放项目指针（product_id 置 NULL），
--       撞 projects.product_id NOT NULL 约束 → DataIntegrityViolation → 双审链 admin 终审 500/90001。
-- 业务依据（product_id 必须可空的三条既有契约）：
--   1. GuestDemandService「其他/不确定」→ product_id=NULL 进入待指派池（AC-PROD-08）；
--   2. P411AcceptanceTest 断言「其他/不确定 product_id 必须为 NULL」；
--   3. P1-1.1 双向 1:1 绑定「解绑后两端指针为 NULL 可重建」。
-- 变更：仅放开 NOT NULL；唯一索引 uk_projects_product 保留（MySQL 唯一索引允许多个 NULL，
--       既维持 1:1 指针约束，又不阻塞待指派池/解绑/软删释放）。
-- apply 状态：已 apply 本机 ipd_dev（2026-09-24，apply 后 SHOW COLUMNS 回读核验 NO→YES）；其他环境待 owner apply。

ALTER TABLE projects MODIFY COLUMN product_id BIGINT NULL COMMENT '关联产品ID（待指派池/解绑/软删释放时为NULL）';

-- 自检：apply 后 Null 列必须为 YES
SHOW COLUMNS FROM projects WHERE Field = 'product_id';

-- 回滚（如需还原约束，必须先确认无 NULL 行）
-- UPDATE projects SET product_id = 0 WHERE product_id IS NULL;  -- 视业务另行处置
-- ALTER TABLE projects MODIFY COLUMN product_id BIGINT NOT NULL;
