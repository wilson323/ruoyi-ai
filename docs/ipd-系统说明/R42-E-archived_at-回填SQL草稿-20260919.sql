-- R42-E archived_at 批量回填 SQL 草稿(2026-09-19,Loop 50)
-- 真库现查确认 6 行违规 ID(archived_at 全是 NULL):
-- 9140001 入门级门禁产品 ARCHIVED NULL
-- 9140002 熵基互联考勤模块 ARCHIVED NULL
-- 9140003 访客机＋万傲瑞达 ARCHIVED NULL
-- 9140005 熵基互联+智能锁联动 ACTIVE NULL
-- 9150001 P3验收种子-G5上市后项目 LIFECYCLE NULL
-- 2096235170527993857 端到端验收项目-0905 DRAFT NULL

-- DBA apply 前复核(必跑):
SELECT id, name, status, archived_at 
FROM projects 
WHERE id IN ('9140001','9140002','9140003','9140005','9150001','2096235170527993857') 
  OR archived_at IS NULL
LIMIT 20;

-- 回填 SQL(只回填 status=ARCHIVED 的项目,其他状态不处理):
UPDATE projects 
SET archived_at = NOW(), update_by = 1 
WHERE status = 'ARCHIVED' 
  AND archived_at IS NULL;

-- apply 后验证:
SELECT id, name, status, archived_at 
FROM projects 
WHERE status = 'ARCHIVED' 
  AND archived_at IS NULL;
-- 期望:0 行

-- 注意事项:
-- 1. 只回填 status=ARCHIVED 的项目(9140001/9140002/9140003)
-- 2. ACTIVE/LIFECYCLE/DRAFT 状态的项目不处理(archived_at 应该保持 NULL)
-- 3. update_by = 1 表示系统自动回填(不是 -1 表示未知)
-- 4. DBA apply 前备份:mysqldump -S /Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/run/mysql.sock ipd_dev projects > /tmp/projects_backup_$(date +%Y%m%d_%H%M%S).sql
