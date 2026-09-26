-- =====================================================================
-- R221（2026-09-26）：AI 代理执行任务队列 ai_agent_tasks（闭环设计 §2.2）
-- 关联变更：AiExecutionTrigger（唯一写入方）/ AiExecutionEngine（抢占+路由+收尾）
-- 语义：单企业私有部署——含 tenant_id 列默认 000000，但调度线程无会话上下文，
--       已按仓内规约补登父 application.yml 的 tenant.excludes（否则拦截器追加过滤）
-- 幂等：CREATE TABLE IF NOT EXISTS；重复 apply 不炸
-- apply：人工/DBA 执行（本仓无 Flyway）；执行后以 SHOW CREATE TABLE 回验索引
-- 修订（2026-09-26 R221 fix）：
--   ①CRITICAL——补 BaseEntity 必需列 create_dept/create_by/update_by（AiAgentTask extends
--     BaseEntity，MyBatis-Plus 自动填充把三列带进 INSERT/UPDATE，缺列则真库首触发即
--     Unknown column；单测 mock mapper 不暴露，与 notification_events 等既有表同规约）；
--   ②WARNING——加 active_dedup 生成列 + 唯一索引，给 trigger 的 SELECT-then-INSERT 去重
--     补 DB 级兜底（并发双插会被唯一键挡下；终态置 NULL 允许同 dedup_key 后续复用）。
--   已 apply 旧版的环境走文末「幂等 ALTER 补丁」补齐（ipd_dev 已于 2026-09-26 补齐并回验）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS ai_agent_tasks (
  id              BIGINT       NOT NULL PRIMARY KEY,
  tenant_id       VARCHAR(20)  DEFAULT '000000',
  project_id      BIGINT       NOT NULL,
  action_code     VARCHAR(10)  NOT NULL COMMENT '对应 ActionCatalog',
  stage_action_id BIGINT       NULL COMMENT '挂接动作实例（被动触发必填）',
  trigger_type    VARCHAR(16)  NOT NULL COMMENT 'PASSIVE/CHAT/EVENT/SCHEDULE',
  exec_mode       VARCHAR(16)  NOT NULL COMMENT '触发时矩阵快照，防目录变更语义漂移',
  status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/DEAD',
  dedup_key       VARCHAR(128) NOT NULL COMMENT 'actionCode:stageActionId:triggerType',
  active_dedup    VARCHAR(128) AS (CASE WHEN status IN ('PENDING','RUNNING') THEN dedup_key ELSE NULL END) STORED
                                 COMMENT '在途去重投影（DB 兜底并发双插；终态置 NULL 允许复用 dedup_key）',
  attempt         INT          DEFAULT 0,
  next_retry_at   DATETIME     NULL COMMENT '指数退避 30s/2m/10m，>3 次翻 DEAD',
  input_digest    VARCHAR(64)  NULL COMMENT '入参指纹（不存 prompt/敏感原文）',
  fill_payload    JSON         NULL COMMENT 'CHAT 触发的结构化填表载荷（§3.5）',
  result_summary  VARCHAR(1024) NULL COMMENT '业务写入摘要，供通知/审计消费',
  ai_doc_id       BIGINT       NULL COMMENT 'AI_GENERATE 产物挂 ai_documents',
  error_msg       VARCHAR(512) NULL,
  triggered_by    BIGINT       NULL COMMENT '被动/对话触发的真人 ID；主动触发 NULL',
  version         INT          DEFAULT 0 COMMENT '乐观锁（条件 UPDATE 抢占）',
  create_dept     BIGINT       NULL COMMENT '创建部门（BaseEntity）',
  create_by       BIGINT       NULL COMMENT '创建者（BaseEntity；系统线程置 0）',
  create_time     DATETIME     NULL COMMENT '创建时间（BaseEntity）',
  update_by       BIGINT       NULL COMMENT '更新者（BaseEntity；系统线程置 0）',
  update_time     DATETIME     NULL COMMENT '更新时间（BaseEntity）',
  del_flag        CHAR(1) DEFAULT '0',
  KEY idx_scan (status, next_retry_at),
  KEY idx_dedup (dedup_key, status),
  KEY idx_project (project_id, action_code),
  UNIQUE KEY uk_active_dedup (active_dedup)
) COMMENT='AI 代理执行任务队列（R221 闭环设计）';

-- 表级 GRANT（漏 GRANT 会读链全通写链炸 500；audit_logs 仅 INSERT 红线不受影响）
-- 应用数据源用户 ipd_app@127.0.0.1（现查 mysql.user 核实，非 '%'）
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.ai_agent_tasks TO 'ipd_app'@'127.0.0.1';
FLUSH PRIVILEGES;

-- =====================================================================
-- 幂等 ALTER 补丁：仅用于已 apply 旧版（缺三列 + 无 active_dedup）的环境。
-- MySQL 8 无 ADD COLUMN IF NOT EXISTS，故用 information_schema 守卫 + PREPARE 幂等。
-- 新建库走上面 CREATE TABLE 已含全部列/索引，本段重复执行均为 no-op（SELECT 1）。
-- 执行前需先 USE 目标库（使 DATABASE() 生效）。
-- =====================================================================
SET @db := DATABASE();

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_agent_tasks' AND column_name='create_dept');
SET @s := IF(@c=0, 'ALTER TABLE ai_agent_tasks ADD COLUMN create_dept BIGINT NULL COMMENT ''创建部门（BaseEntity）'' AFTER version', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_agent_tasks' AND column_name='create_by');
SET @s := IF(@c=0, 'ALTER TABLE ai_agent_tasks ADD COLUMN create_by BIGINT NULL COMMENT ''创建者（BaseEntity）'' AFTER create_dept', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_agent_tasks' AND column_name='update_by');
SET @s := IF(@c=0, 'ALTER TABLE ai_agent_tasks ADD COLUMN update_by BIGINT NULL COMMENT ''更新者（BaseEntity）'' AFTER create_time', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=@db AND table_name='ai_agent_tasks' AND column_name='active_dedup');
SET @s := IF(@c=0, 'ALTER TABLE ai_agent_tasks ADD COLUMN active_dedup VARCHAR(128) AS (CASE WHEN status IN (''PENDING'',''RUNNING'') THEN dedup_key ELSE NULL END) STORED COMMENT ''在途去重投影'' AFTER dedup_key', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=@db AND table_name='ai_agent_tasks' AND index_name='uk_active_dedup');
SET @s := IF(@c=0, 'ALTER TABLE ai_agent_tasks ADD UNIQUE KEY uk_active_dedup (active_dedup)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
