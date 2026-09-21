-- ============================================================================
-- R149·FA-HR-Sync·HR 真源新增表（草稿，待 owner apply）
-- 日期: 2026-09-21
-- 设计依据: docs/ipd-系统说明/HR-SYNC-P0-设计+实现-20260921.md
-- 对接文档: 信息集成平台接口文档-EHR.docx (zkteco.ehr.getUserInfo /
--           zkteco.ehr.getOrganizationInfo)
--
-- 全局一致性边界（按 owner 2026-09-21 「避免冗余避免双轨」指令锁定）:
--   * persons           → 不 ALTER（27 列已饱和，R93-B-2 收口）
--   * product_groups    → 不 ALTER（语义=IPD 产品组，与 HR 全公司组织树不重叠）
--   * person_sync_jobs  → 复用（既有单人级同步台账，HR 同步走 submit 接口落同一张表）
--   * sys_user / roles  → 不动（基线框架表，HR 同步不入 sys_user 体系）
--
-- 本 SQL 只新增 1 张表（hr_organizations）——HR 真源组织树，与 product_groups 弱关联。
-- 不创建 hr_sync_runs（与 person_sync_jobs 双轨，已删）；
-- 不创建 hr_person_profiles（草稿阶段，避免冗余 DDL 污染）。
-- ============================================================================


-- ─────────────────────────── hr_organizations（HR 组织树） ────────────────────
-- 业务语义: HR 推过来的"全公司组织树"，远大于 IPD 产品组（product_groups）；
--           保留 HR 原始组织层级（orgeh + orgeh_pup），不强耦合产品组。
--           产品组↔HR 部门映射由 HrOrgMappingService 维护（不在本 DDL）。
--
-- 主键: BIGINT 雪花（与 persons 主键策略一致，MyBatis-Plus ASSIGN_ID）
-- 唯一键: (orgeh) 单列 —— 一份组织一个现行记录（同 org 多版由 is_current+有效段标识）
DROP TABLE IF EXISTS `hr_organizations`;
CREATE TABLE `hr_organizations` (
  `id`                bigint        NOT NULL                COMMENT '主键（雪花）',
  `orgeh`             varchar(64)   COLLATE utf8mb4_general_ci NOT NULL COMMENT 'HR 组织编码（业务键）',
  `stext`             varchar(255)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组织全称',
  `short_name`        varchar(128)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '组织简称',
  `parent_orgeh`      varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '上级组织编码（根节点为空字符串）',
  `zbmcj`             varchar(8)    COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '层级 1=总部 N=末级',
  `begda`             varchar(10)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '有效起 yyyy-MM-dd（HR 原始字符串）',
  `endda`             varchar(10)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '有效止 yyyy-MM-dd（HR 原始字符串）',
  `bmfzr`             varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '部门负责人 PERNR（软引用 persons.employee_no）',
  `hr_del_flag`       varchar(8)    COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'HR 失效 X=失效',
  `expiration_flag`   varchar(8)    COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'HR 失效细分（备用）',
  `last_sync_at`      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近同步时间（HR→本地）',
  `trigger_by`        varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '同步触发者 CRON_DAILY/MANUAL_xxx/INITIAL_seed',
  `tenant_id`         varchar(20)   COLLATE utf8mb4_general_ci NOT NULL DEFAULT '000000' COMMENT '租户ID',
  `create_dept`       bigint        DEFAULT NULL            COMMENT '创建部门',
  `create_by`         bigint        DEFAULT NULL            COMMENT '创建人',
  `create_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`         bigint        DEFAULT NULL            COMMENT '更新人',
  `update_time`       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `del_flag`          char(1)       COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hr_org_orgeh` (`orgeh`),
  KEY `idx_hr_org_parent_orgeh` (`parent_orgeh`),
  KEY `idx_hr_org_bmfzr` (`bmfzr`),
  KEY `idx_hr_org_last_sync_at` (`last_sync_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR 组织树（HR 真源，与 product_groups 弱关联，不双轨）';


-- ──────────────────────── persons / person_sync_jobs 不动 ─────────────────────
-- persons: 27 列已饱和（R93-B-2），不再扩展业务字段；HR 业务字段（phone/email/hire_date/
--          positionname/email_com 等）由既有 name/username + 后续 HrProfileMirror 按需落。
--          若必须存 HR 扩展字段，由 FA-HR-Sync-v1 评审后新增独立 hr_person_mirror 表（不双轨）。
-- person_sync_jobs: 既有单人级同步台账（P2-2.3 落库），HR 同步走 PersonSyncService.submit
--                   走同一张表（jobId=前缀 HR-...，employeeNo=PERNR）。
-- product_groups: IPD 产品组（leader_person_id/parent_id/description），与 HR 组织树语义不重叠，
--                 不引入 product_groups.orgEh 列（避免双轨）；关联映射由 HrOrgMappingService 维护。
-- sys_user / sys_role 等基线表: HR 同步不入此体系（IPD 模块走 ipd StpLogic，不复用基线 sys_user）。


-- ──────────────────────── tenant.excludes 配套（FA-HR-Sync-v1 处理）─────────
-- hr_organizations 不参与多租户过滤（AGENTS.md 多租户默认开启）；
-- 由 FA-HR-Sync-v1 在主协调会话同步登记到 application.yml 的 tenant.excludes
-- （不在本 SQL 做，避免多人改 yml 漂号，AGENTS.md 五必现查）。


-- ──────────────────────── 验收脚本（owner apply 后跑） ────────────────────────
-- 1) SELECT COUNT(*) AS cnt FROM hr_organizations;          -- 期望 ≥ 0
-- 2) SHOW INDEX FROM hr_organizations WHERE Key_name='uk_hr_org_orgeh';
--    -- 期望 1 行（HR 组织编码唯一；同 org 多版由 begda/endda 区分，不进 DDL 唯一约束）
-- 3) SHOW CREATE TABLE hr_organizations\G
--    -- 期望 CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci（与 persons 对齐）
-- 4) 不期望出现表：hr_sync_runs / hr_person_profiles / hr_person_mirror（避免冗余）
