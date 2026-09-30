-- =====================================================================
-- 项目智能体 C02 持久层 DDL（2026-09-30；W2 产物版本表同文件追加）
-- 状态：已于本机 ipd_dev 执行，重复执行必须幂等（CREATE TABLE IF NOT EXISTS）
-- 依据：docs/ipd-系统说明/项目智能体运行合同-v1-20260929.md
-- 看板：65d3ad11-f52e-4498-8716-535d5cad321d（marker project-agent-c02-w1-20260929）
-- 语法：MySQL 8.0（本机 8.0.46）；幂等：CREATE TABLE IF NOT EXISTS
-- 前置：
--   1) 确认目标 catalog（ipd_dev 或正式库）与应用数据源一致，本脚本不含 USE；
--   2) 同批在 ruoyi-admin application.yml 的 tenant.excludes 登记下列表（Mapper 已
--      @InterceptorIgnore(tenantLine="true")，store 显式按 tenant_id 限定，excludes 为双保险）；
--   3) 生产默认 ipd.project-agent.enabled=false；本机可覆盖开启。
-- 回滚：表内无数据时可在核准窗口 DROP TABLE（逆序）；已有数据不得直接 DROP。
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

-- 1) 运行：一次项目智能体运行的配置快照与状态机（业务完成权威仍为 ai_agent_tasks，task_id 可空关联）
CREATE TABLE IF NOT EXISTS `ipd_agent_run` (
  `id`                      BIGINT       NOT NULL COMMENT '雪花 ID',
  `tenant_id`               VARCHAR(20)  NOT NULL COMMENT '可信租户（服务端重读 Person）',
  `project_id`              BIGINT       NOT NULL COMMENT '已授权项目',
  `person_id`               BIGINT       NOT NULL COMMENT '发起人 Person ID（会话身份）',
  `agent_id`                VARCHAR(64)  NOT NULL DEFAULT 'ipd_project_agent' COMMENT '固定 ipd_project_agent',
  `status`                  VARCHAR(24)  NOT NULL COMMENT 'PENDING/RUNNING/WAITING_APPROVAL/CANCEL_REQUESTED/SUCCEEDED/FAILED/CANCELLED',
  `action_code`             VARCHAR(16)  NULL COMMENT 'IPD 动作编码（如 C02）',
  `capability_pack_code`    VARCHAR(64)  NOT NULL COMMENT '能力包编码',
  `capability_pack_version` VARCHAR(32)  NOT NULL COMMENT '能力包版本',
  `model_config_id`         BIGINT       NOT NULL COMMENT 'ai_model_configs.id',
  `config_snapshot`         JSON         NOT NULL COMMENT '冻结配置：能力包/模型/Skill 名与 sha256/工具 ID',
  `idempotency_key`         VARCHAR(64)  NOT NULL COMMENT '幂等键',
  `request_digest`          CHAR(64)     NOT NULL COMMENT '规范化请求 SHA-256（同键不同请求判冲突）',
  `input_digest`            CHAR(64)     NOT NULL COMMENT '用户输入 SHA-256（不存原文，L0-5）',
  `input_chars`             INT          NOT NULL DEFAULT 0 COMMENT '用户输入字符数',
  `task_id`                 BIGINT       NULL COMMENT '可空：ai_agent_tasks.id（W2 接入）',
  `error_code`              VARCHAR(64)  NULL COMMENT 'FAILED 时的安全错误码',
  `started_at`              DATETIME     NULL COMMENT '进入 RUNNING 时间',
  `finished_at`             DATETIME     NULL COMMENT '进入终态时间',
  `version`                 INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
  `del_flag`                CHAR(1)      NOT NULL DEFAULT '0' COMMENT '0 存在 1 删除',
  `create_dept`             BIGINT       NULL,
  `create_by`               BIGINT       NULL,
  `create_time`             DATETIME     NULL,
  `update_by`               BIGINT       NULL,
  `update_time`             DATETIME     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_run_idem` (`tenant_id`, `person_id`, `idempotency_key`),
  KEY `idx_agent_run_project_person` (`project_id`, `person_id`, `create_time`),
  KEY `idx_agent_run_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目智能体运行';

-- 2) 运行事件：按 seq 持久化，(run_id, seq) 唯一去重
CREATE TABLE IF NOT EXISTS `ipd_agent_run_event` (
  `id`          BIGINT      NOT NULL COMMENT '雪花 ID',
  `tenant_id`   VARCHAR(20) NOT NULL,
  `run_id`      BIGINT      NOT NULL COMMENT 'ipd_agent_run.id',
  `seq`         BIGINT      NOT NULL COMMENT '运行内单调序号，从 1 开始',
  `event_type`  VARCHAR(24) NOT NULL COMMENT 'RUN_STARTED/STEP/TOOL_CALL/TOOL_RESULT/SOURCE/TEXT_DELTA/ARTIFACT/ERROR/RUN_FINISHED',
  `payload`     JSON        NOT NULL COMMENT '事件载荷（不含凭据与推理原文）',
  `del_flag`    CHAR(1)     NOT NULL DEFAULT '0',
  `create_dept` BIGINT      NULL,
  `create_by`   BIGINT      NULL,
  `create_time` DATETIME    NULL,
  `update_by`   BIGINT      NULL,
  `update_time` DATETIME    NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_run_event_seq` (`run_id`, `seq`),
  KEY `idx_agent_run_event_type` (`run_id`, `event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目智能体运行事件';

-- 3) AI 反馈：同一人对同一目标唯一，再次提交为本人更新（与激励域 negative_feedback 无关）
CREATE TABLE IF NOT EXISTS `ipd_ai_feedback` (
  `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
  `tenant_id`   VARCHAR(20)  NOT NULL,
  `target_type` VARCHAR(32)  NOT NULL COMMENT 'RUN_MESSAGE / ARTIFACT_VERSION',
  `target_id`   BIGINT       NOT NULL COMMENT 'RUN_MESSAGE=run_id；ARTIFACT_VERSION=产物版本 ID（W2）',
  `project_id`  BIGINT       NOT NULL COMMENT '目标所属项目（服务端解析）',
  `person_id`   BIGINT       NOT NULL,
  `rating`      VARCHAR(8)   NOT NULL COMMENT 'UP / DOWN',
  `reason`      VARCHAR(500) NULL,
  `version`     INT          NOT NULL DEFAULT 0,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  `create_dept` BIGINT       NULL,
  `create_by`   BIGINT       NULL,
  `create_time` DATETIME     NULL,
  `update_by`   BIGINT       NULL,
  `update_time` DATETIME     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_feedback_target_person` (`target_type`, `target_id`, `person_id`),
  KEY `idx_ai_feedback_project` (`project_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 点赞点踩';

-- 4) 能力包（W1 权威为 JAR 内 ipd-skills/capability-packs.json；本表为 W2 管理员扩展/覆盖承载，种子同源）
CREATE TABLE IF NOT EXISTS `ipd_capability_pack` (
  `id`              BIGINT       NOT NULL COMMENT 'ID',
  `tenant_id`       VARCHAR(20)  NOT NULL DEFAULT '000000',
  `code`            VARCHAR(64)  NOT NULL COMMENT '能力包编码',
  `version`         VARCHAR(32)  NOT NULL COMMENT '能力包版本',
  `name`            VARCHAR(128) NOT NULL,
  `description`     VARCHAR(500) NULL,
  `stages`          JSON         NOT NULL COMMENT '适用阶段数组',
  `action_codes`    JSON         NOT NULL COMMENT '适用动作数组',
  `source`          VARCHAR(16)  NOT NULL DEFAULT 'BUILTIN' COMMENT 'BUILTIN / ADMIN',
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `manifest_sha256` CHAR(64)     NULL COMMENT '来源清单摘要（BUILTIN）',
  `del_flag`        CHAR(1)      NOT NULL DEFAULT '0',
  `create_dept`     BIGINT       NULL,
  `create_by`       BIGINT       NULL,
  `create_time`     DATETIME     NULL,
  `update_by`       BIGINT       NULL,
  `update_time`     DATETIME     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_capability_pack_code_ver` (`tenant_id`, `code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目智能体能力包';

-- 5) 能力包条目：Skill（锁定版本与 sha256）/ 工具
CREATE TABLE IF NOT EXISTS `ipd_capability_pack_item` (
  `id`           BIGINT      NOT NULL COMMENT 'ID',
  `tenant_id`    VARCHAR(20) NOT NULL DEFAULT '000000',
  `pack_id`      BIGINT      NOT NULL COMMENT 'ipd_capability_pack.id',
  `item_type`    VARCHAR(16) NOT NULL COMMENT 'SKILL / TOOL',
  `item_ref`     VARCHAR(64) NOT NULL COMMENT 'Skill 名或工具 ID',
  `item_version` VARCHAR(32) NULL,
  `sha256`       CHAR(64)    NULL COMMENT 'Skill 正文 SHA-256',
  `required`     TINYINT     NOT NULL DEFAULT 1,
  `sort_order`   INT         NOT NULL DEFAULT 0,
  `del_flag`     CHAR(1)     NOT NULL DEFAULT '0',
  `create_dept`  BIGINT      NULL,
  `create_by`    BIGINT      NULL,
  `create_time`  DATETIME    NULL,
  `update_by`    BIGINT      NULL,
  `update_time`  DATETIME    NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_capability_pack_item` (`pack_id`, `item_type`, `item_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目智能体能力包条目';

-- 6) 产物版本：运行产出的可应用草稿；反馈 ARTIFACT_VERSION 的 targetId = 本表 id
CREATE TABLE IF NOT EXISTS `ipd_agent_artifact_version` (
  `id`              BIGINT       NOT NULL COMMENT '雪花 ID（反馈 targetId）',
  `tenant_id`       VARCHAR(20)  NOT NULL,
  `run_id`          BIGINT       NOT NULL COMMENT 'ipd_agent_run.id',
  `artifact_id`     VARCHAR(64)  NOT NULL COMMENT '产物逻辑 ID（字符串，事件 payload.artifactId）',
  `version_no`      INT          NOT NULL COMMENT '版本号，从 1 起',
  `title`           VARCHAR(200) NOT NULL COMMENT '产物标题',
  `content`         MEDIUMTEXT   NOT NULL COMMENT '产物正文（apply 时写入 ai_documents）',
  `content_sha256`  CHAR(64)     NOT NULL COMMENT '正文 SHA-256',
  `status`          VARCHAR(16)  NOT NULL COMMENT 'DRAFT|APPLIED',
  `document_id`     BIGINT       NULL COMMENT 'apply 成功后 ai_documents.id',
  `del_flag`        CHAR(1)      NOT NULL DEFAULT '0',
  `create_dept`     BIGINT       NULL,
  `create_by`       BIGINT       NULL,
  `create_time`     DATETIME     NULL,
  `update_by`       BIGINT       NULL,
  `update_time`     DATETIME     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_artifact_run_ver` (`run_id`, `artifact_id`, `version_no`),
  KEY `idx_agent_artifact_id` (`artifact_id`),
  KEY `idx_agent_artifact_doc` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目智能体产物版本';

-- 表级 GRANT（漏 GRANT 会读链通、写链炸；与 ai_agent_tasks / 既有 agent 表同一用户）
-- 应用数据源用户 ipd_app@127.0.0.1（现查 mysql.user；非 '%'）
GRANT SELECT, INSERT, UPDATE, DELETE ON ipd_dev.ipd_agent_artifact_version TO 'ipd_app'@'127.0.0.1';

-- 执行后只读核验：
-- SHOW CREATE TABLE ipd_agent_run; SHOW CREATE TABLE ipd_agent_run_event; SHOW CREATE TABLE ipd_ai_feedback;
-- SHOW CREATE TABLE ipd_capability_pack; SHOW CREATE TABLE ipd_capability_pack_item;
-- SHOW CREATE TABLE ipd_agent_artifact_version;
-- SELECT GRANTEE,TABLE_NAME,PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES
--   WHERE TABLE_SCHEMA='ipd_dev' AND TABLE_NAME='ipd_agent_artifact_version';
-- 另：agentscope_sessions 状态表不在本脚本；多轮会话/恢复需要时按
-- docs/ipd-系统说明/AgentScope-状态表DDL草案-20260928.sql 另行审批，本切片不依赖该表。
