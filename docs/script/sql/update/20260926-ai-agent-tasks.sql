-- =====================================================================
-- R221（2026-09-26）：AI 代理执行任务队列 ai_agent_tasks（闭环设计 §2.2）
-- 关联变更：AiExecutionTrigger（唯一写入方）/ AiExecutionEngine（抢占+路由+收尾）
-- 语义：单企业私有部署——含 tenant_id 列默认 000000，但调度线程无会话上下文，
--       已按仓内规约补登父 application.yml 的 tenant.excludes（否则拦截器追加过滤）
-- 幂等：CREATE TABLE IF NOT EXISTS；重复 apply 不炸
-- apply：人工/DBA 执行（本仓无 Flyway）；执行后以 SHOW CREATE TABLE 回验三索引
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
  attempt         INT          DEFAULT 0,
  next_retry_at   DATETIME     NULL COMMENT '指数退避 30s/2m/10m，>3 次翻 DEAD',
  input_digest    VARCHAR(64)  NULL COMMENT '入参指纹（不存 prompt/敏感原文）',
  fill_payload    JSON         NULL COMMENT 'CHAT 触发的结构化填表载荷（§3.5）',
  result_summary  VARCHAR(1024) NULL COMMENT '业务写入摘要，供通知/审计消费',
  ai_doc_id       BIGINT       NULL COMMENT 'AI_GENERATE 产物挂 ai_documents',
  error_msg       VARCHAR(512) NULL,
  triggered_by    BIGINT       NULL COMMENT '被动/对话触发的真人 ID；主动触发 NULL',
  version         INT          DEFAULT 0 COMMENT '乐观锁（条件 UPDATE 抢占）',
  create_time     DATETIME, update_time DATETIME,
  del_flag        CHAR(1) DEFAULT '0',
  KEY idx_scan (status, next_retry_at),
  KEY idx_dedup (dedup_key, status),
  KEY idx_project (project_id, action_code)
) COMMENT='AI 代理执行任务队列（R221 闭环设计）';

-- 表级 GRANT（漏 GRANT 会读链全通写链炸 500；audit_logs 仅 INSERT 红线不受影响）
-- 应用数据源用户 ipd_app@127.0.0.1（现查 mysql.user 核实，非 '%'）
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.ai_agent_tasks TO 'ipd_app'@'127.0.0.1';
FLUSH PRIVILEGES;
