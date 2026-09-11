-- ============================================================
-- person_sync_jobs 人员同步任务台账（P2-2.3 落库改造）
-- 状态：【待 owner 拍板，未 apply】—— apply 授权后由执行会话跑本文件并回读核验。
-- 背景：PersonSyncService 任务表原为内存 ConcurrentHashMap，后端重启即丢全部同步任务；
--       本表承接落库（write-through + 重启后 DB 惰性回读），与同卡 Java 改造成对收口。
-- 执行前确认：SHOW TABLES LIKE 'person_sync_jobs' → 空
-- ============================================================

CREATE TABLE IF NOT EXISTS `person_sync_jobs` (
  `id`              bigint       NOT NULL                  COMMENT '雪花 ID（ASSIGN_ID）',
  `job_id`          varchar(40)  NOT NULL                  COMMENT '任务号 sync-xxxxxxxx-N',
  `employee_no`     varchar(64)  NOT NULL                  COMMENT '工号（HR 同步目标）',
  `idempotency_key` varchar(128) DEFAULT NULL             COMMENT '幂等键（可空；复合幂等键=operator_id+group_id+本列）',
  `operator_id`     bigint       NOT NULL                  COMMENT '提交人 person.id',
  `group_id`        bigint       DEFAULT NULL              COMMENT '提交人组 ID（admin 为 NULL）',
  `status`          varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|SUCCESS|FAILED|RETRYING',
  `attempts`        int          NOT NULL DEFAULT 0        COMMENT '已尝试次数',
  `max_attempts`    int          NOT NULL DEFAULT 3        COMMENT '最大尝试次数',
  `failure_kind`    varchar(16)  DEFAULT NULL              COMMENT 'TRANSIENT|PERMANENT',
  `failure_reason`  varchar(500) DEFAULT NULL              COMMENT '最近一次失败原因',
  `next_retry_at`   datetime     DEFAULT NULL              COMMENT '下次自动重试时间（指数退避）',
  `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建时间',
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '状态变更时间',
  `tenant_id`       varchar(20)  NOT NULL DEFAULT '000000',
  `del_flag`        char(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sync_job_id` (`job_id`),
  KEY `idx_sync_idem` (`idempotency_key`, `operator_id`, `group_id`),
  KEY `idx_sync_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='人员同步任务台账（P2-2.3 落库；重启不丢；单企业私有部署无多租户语义，已登记 tenant.excludes）';

-- apply 后核验（执行会话负责）：
--   1. SHOW COLUMNS FROM person_sync_jobs;  → 16 列齐全
--   2. SHOW INDEX FROM person_sync_jobs;    → uk_sync_job_id / idx_sync_idem / idx_sync_status 在位
--   3. 表级 GRANT（必须！否则 ipd_app UPDATE 被拒 → 提交同步 500，实测已踩）：
--      GRANT SELECT,INSERT,UPDATE,DELETE ON ipd_dev.person_sync_jobs TO 'ipd_app'@'127.0.0.1';
--      核验：SELECT table_priv FROM mysql.tables_priv
--            WHERE user='ipd_app' AND db='ipd_dev' AND table_name='person_sync_jobs';
--            → table_priv 含 Select,Insert,Update,Delete 四权
