-- ============================================================================
-- R149·FA-HR-Sync·HR 同步真源表（草稿，待 owner apply）
-- 日期: 2026-09-21
-- 设计依据: docs/ipd-系统说明/HR-SYNC-P0-设计+实现-20260921.md
-- 对接文档: 信息集成平台接口文档-EHR.docx (zkteco.ehr.getUserInfo /
--           zkteco.ehr.getOrganizationInfo)
-- 上游表:   persons (现有；不动)
--           product_groups (现有；不动，与 hr_organizations 弱关联)
-- 应用范围: 仅 dev profile；prod apply 由 owner 操作（AGENTS.md OPS-09/残留三件）
-- ============================================================================

-- ─────────────────────────── hr_organizations（组织表） ─────────────────────────
-- 设计依据:
--   * HR 推过来的是"全公司组织树"，远大于产品组（product_groups）；
--   * 保留 HR 原始组织层级（orgeh + orgeh_pup），不强耦合产品组；
--   * 产品组↔HR 部门映射关系由后续 HrOrgMappingService 维护（不在本 DDL）。
--
-- 主键: BIGINT 雪花（沿用 persons 主键策略，便于 MyBatis-Plus ASSIGN_ID）
-- 唯一键: (orgeh, begda, endda) 复合：HR 同组织多次有效段时允许多行
DROP TABLE IF EXISTS `hr_organizations`;
CREATE TABLE `hr_organizations` (
  `id`                bigint        NOT NULL                COMMENT '主键（雪花）',
  `orgeh`             varchar(64)   COLLATE utf8mb4_general_ci NOT NULL COMMENT 'HR 组织编码（业务键）',
  `stext`             varchar(255)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组织全称',
  `short_name`        varchar(128)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组织简称',
  `orgeh_pup`         varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '上级组织编码（根节点为空字符串）',
  `zbmcj`             varchar(8)    COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '层级（1=总部 N=末级）',
  `begda`             date          NOT NULL                COMMENT '有效起 yyyy-MM-dd',
  `endda`             date          NOT NULL DEFAULT '2099-12-31' COMMENT '有效止 yyyy-MM-dd（HR 默认 2099-12-31）',
  `bmfzr`             varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '部门负责人 PERNR（外键回指 persons.employee_no）',
  `del_flag`          char(1)       COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT 'HR 失效标志（X=失效）',
  `expiration_flag`   varchar(8)    COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'HR 失效细分（备用）',
  `last_synced_at`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近同步时间（HR→本地）',
  `tenant_id`         varchar(20)   COLLATE utf8mb4_general_ci NOT NULL DEFAULT '000000' COMMENT '租户ID',
  `del_flag_local`    char(1)       COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '本地逻辑删除（MyBatis-Plus @TableLogic）',
  `create_dept`       bigint        DEFAULT NULL            COMMENT '创建部门',
  `create_by`         bigint        DEFAULT NULL            COMMENT '创建人',
  `create_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`         bigint        DEFAULT NULL            COMMENT '更新人',
  `update_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hr_org_orgeh_begda_endda` (`orgeh`, `begda`, `endda`),
  KEY `idx_hr_org_orgeh_pup` (`orgeh_pup`),
  KEY `idx_hr_org_bmfzr` (`bmfzr`),
  KEY `idx_hr_org_last_synced` (`last_synced_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR 组织树（HR 真源，不动 persons/product_groups）';


-- ─────────────────────────── hr_sync_runs（同步任务台账） ──────────────────────
-- 设计依据:
--   * 已有 person_sync_jobs（P2-2.3 落库，单人级任务），无法承载：
--       - 全量同步一轮（一次同步 N 个 PERNR）的批次摘要
--       - 组织同步批次摘要
--       - HR 同步失败的 HR 端异常码（区别于 PersonSyncService.FailureKind）
--   * 本表只记录"批次"，细粒度任务仍走 person_sync_jobs。
--
-- 主键: BIGINT 雪花；run_id 业务键（同步批次号）
DROP TABLE IF EXISTS `hr_sync_runs`;
CREATE TABLE `hr_sync_runs` (
  `id`               bigint        NOT NULL                COMMENT '主键（雪花）',
  `run_id`           varchar(64)   COLLATE utf8mb4_general_ci NOT NULL COMMENT '同步批次号（业务键）',
  `scope`            varchar(16)   COLLATE utf8mb4_general_ci NOT NULL COMMENT 'USER|ORG|USER_AND_ORG',
  `trigger_type`     varchar(16)   COLLATE utf8mb4_general_ci NOT NULL COMMENT 'CRON|MANUAL|STARTUP',
  `trigger_by`       varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '触发者（cron=null；manual=operatorId；startup=system）',
  `status`           varchar(16)   COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING|SUCCESS|PARTIAL|FAILED',
  `total_rows`       int           NOT NULL DEFAULT 0      COMMENT 'HR 推过来行数',
  `upserted_rows`    int           NOT NULL DEFAULT 0      COMMENT '成功 upsert 行数',
  `skipped_rows`     int           NOT NULL DEFAULT 0      COMMENT '跳过（DTO 解析失败/已离职）行数',
  `failed_rows`      int           NOT NULL DEFAULT 0      COMMENT '失败行数',
  `hr_error_code`    int           DEFAULT NULL            COMMENT 'HR 业务异常码（仅在 status=FAILED 时有值）',
  `hr_error_msg`     varchar(512)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'HR 业务异常 msg',
  `started_at`       datetime      NOT NULL                COMMENT '同步开始时间',
  `finished_at`      datetime      DEFAULT NULL            COMMENT '同步结束时间（=started_at + duration）',
  `duration_ms`      bigint        DEFAULT NULL            COMMENT '耗时（毫秒）',
  `tenant_id`        varchar(20)   COLLATE utf8mb4_general_ci NOT NULL DEFAULT '000000' COMMENT '租户ID',
  `del_flag`         char(1)       COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '本地逻辑删除（不删，保留审计）',
  `create_dept`      bigint        DEFAULT NULL            COMMENT '创建部门',
  `create_by`        bigint        DEFAULT NULL            COMMENT '创建人',
  `create_time`      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`        bigint        DEFAULT NULL            COMMENT '更新人',
  `update_time`      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hr_sync_runs_run_id` (`run_id`),
  KEY `idx_hr_sync_runs_status_started` (`status`, `started_at`),
  KEY `idx_hr_sync_runs_scope_started` (`scope`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR 同步批次台账（区别于 person_sync_jobs 单人级）';


-- ──────────────────────── persons 表：不 ALTER（保持现状） ────────────────────
-- 理由：
--   1) persons 表字段数已饱和（27 列）；R93-B-2 已收口"不再扩展业务字段"
--   2) HR 业务字段（phone/email_com/hire_date/positionname 等）已有等价的本地列
--      （name/username），其余字段差异较大，通过 hr_person_profiles 补充（如下）
--   3) 本期最小实现只需 hr_organizations + hr_sync_runs；hr_person_profiles 留 v1 后续

-- ──────────────────────── hr_person_profiles（HR 画像扩展，可选） ─────────────
-- 注: 本表**草稿**，不在本期 apply 范围；FA-HR-Sync-v1 收口后单独评审
-- DROP TABLE IF EXISTS `hr_person_profiles`;
-- CREATE TABLE `hr_person_profiles` (
--   `id`             bigint       NOT NULL COMMENT '主键（雪花）',
--   `employee_no`    varchar(64)  COLLATE utf8mb4_general_ci NOT NULL COMMENT 'PERNR（外键回 persons.employee_no）',
--   `email_com`      varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '企业邮箱（HR BASIC_INFO.EMAIL_COM）',
--   `phone`          varchar(32)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机（HR BASIC_INFO.PHONE）',
--   `hire_date`      date         DEFAULT NULL COMMENT '入职日期（HR BASIC_INFO.HIRE_DATE）',
--   `stat2`          varchar(8)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'HR 在职标志 3=在职 / 0=离职',
--   `orgeh`          varchar(64)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'HR 部门编码（冗余，便于查询）',
--   `positionname`   varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '职位名称',
--   `raw_json`       json         DEFAULT NULL COMMENT 'HR BASIC_INFO 全量 JSON（兜底，回查字段用）',
--   `last_synced_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近同步时间',
--   ... 通用审计字段
--   PRIMARY KEY (`id`),
--   UNIQUE KEY `uk_hr_person_profiles_employee_no` (`employee_no`)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR 人员画像扩展（persons 27 列不扩展，本表兜底）';


-- ──────────────────────── tenant.excludes 配套登记 ─────────────────────────────
-- 设计依据：
--   * hr_organizations / hr_sync_runs 不参与多租户过滤（AGENTS.md "多租户默认开启"）
--   * 注册到父 application.yml 的 tenant.excludes
--   * 但**不在本 SQL 做**——本 SQL 只做表 DDL，配置变更随 FA-HR-Sync-v1 服务代码一起
--     在主协调会话做（避免多人改 yml 漂号，AGENTS.md "5必现查"段）

-- ──────────────────────── 验收脚本（owner apply 后由 .codex/ipd-dev 跑） ────────
-- 1) SELECT COUNT(*) FROM hr_organizations;        -- 期望 ≥ 0
-- 2) SELECT COUNT(*) FROM hr_sync_runs;           -- 期望 ≥ 0
-- 3) SHOW INDEX FROM hr_organizations WHERE Key_name='uk_hr_org_orgeh_begda_endda';
--    -- 期望 1 行（HR 同一组织允许多次有效段）
-- 4) SHOW CREATE TABLE hr_organizations\G         -- 期望字符集 = utf8mb4 / 排序 = utf8mb4_general_ci（与 persons 对齐）
