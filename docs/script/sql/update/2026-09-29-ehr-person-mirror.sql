-- ============================================================================
-- E-HR 对接·HR 人员档案镜像表 hr_person_mirror（D10 评审落地）
-- 日期: 2026-09-29
-- 设计依据: docs/ipd-系统说明/HR-SYNC-P0-设计+实现-20260921.md §3.3-D10（预留评审口）
--          + 《IPD系统_熵基EHR项目接口需求-2026.9.22》「产品IPD」Sheet 字段清单
-- 对接文档: 信息集成平台接口文档-EHR.docx（zkteco.ehr.getUserInfo §3.2.1）
-- 配套交付: docs/ipd-系统说明/EHR对接-IPD侧字段清单-20260929.md（回复集成平台）
--
-- 全局一致性边界（沿用 R149 防双轨原则，owner 2026-09-29 拍板新增本表）:
--   * persons           → 不 ALTER（27 列已饱和；登录/权限体系不双轨）
--   * hr_person_mirror  → 本表只承载 HR 档案镜像（非登录体系），pernr 软引用 persons.employee_no
--   * hr_organizations  → 组织字段已在该表，本表 orgeh/deptname 为冗余快照（查询便利）
--   * person_sync_jobs  → 同步台账不变（不建 hr_sync_runs）
--
-- 口径（需求表「传参X标识会议记录」IPD 行）:
--   * DEL_FLAG=×（不消费）——本表不设删除标识列
--   * LEAVE_FLAG=√——离职判定只看 LEAVE_FLAG（+STAT2），leave_flag 列保留原始值
--   * 员工类型过滤在应用层（HrSyncRules）：仅 Regular/Probation/Relationship 入库
--
-- 幂等保护（R180-P0 规约）：CREATE TABLE IF NOT EXISTS，严禁 DROP TABLE IF EXISTS。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `hr_person_mirror` (
  `id`                  bigint        NOT NULL                COMMENT '主键（雪花）',
  `pernr`               varchar(64)   COLLATE utf8mb4_general_ci NOT NULL COMMENT '工号 PERNR（业务键；软引用 persons.employee_no）',
  `nachn`               varchar(128)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '员工姓名 NACHN',
  `rufnm`               varchar(128)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '英文名 RUFNM',
  `gesch`               varchar(8)    COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '性别 GESCH（1=男 2=女，HR 原始值）',
  `natio`               varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '国籍 NATIO（国家编码，如 CN）',
  `hire_date`           varchar(10)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '入职日期 HIRE_DATE（yyyy-MM-dd，HR 原始字符串）',
  `leave_date`          varchar(10)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '离职日期 LEAVE_DATE（yyyy-MM-dd）',
  `phone`               varchar(32)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号 PHONE',
  `email_com`           varchar(128)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '企业邮箱 EMAIL_COM',
  `orgeh`               varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '部门编码 ORGEH（关联 hr_organizations.orgeh）',
  `deptname`            varchar(255)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '部门名称 DEPTNAME',
  `plans`               varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '岗位代码 PLANS',
  `positionname`        varchar(128)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '标准岗位 POSITIONNAME',
  `positiongrade`       varchar(32)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '职级 POSITIONGRADE（职位图谱等级）',
  `qualificationlevel`  varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '任职资格等级 QUALIFICATIONLEVEL',
  `supervisorno`        varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '直接上级工号 SUPERVISORNO',
  `supervisorname`      varchar(128)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '直接上级姓名 SUPERVISORNAME',
  `certificate`         varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最高学历 CERTIFICATE（仅主学历；字典 Undergraduate/Master/...）',
  `insitute`            varchar(255)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '毕业院校 INSITUTE',
  `line_of_study`       varchar(255)  COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '专业 LINE_OF_STUDY',
  `empcategory`         varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '员工类型 EMPCATEGORY（Regular/Probation/Relationship，入库前已过滤）',
  `stat2`               varchar(8)    COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '员工状态 STAT2（3=在职 0=离职）',
  `leave_flag`          varchar(8)    COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '离职标识 LEAVE_FLAG（X=离职生效；IPD 口径 √）',
  `last_sync_at`        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近同步时间（HR→本地）',
  `trigger_by`          varchar(64)   COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '同步触发者 CRON_DAILY/MANUAL_xxx/INITIAL_seed',
  `tenant_id`           varchar(20)   COLLATE utf8mb4_general_ci NOT NULL DEFAULT '000000' COMMENT '租户ID',
  `create_dept`         bigint        DEFAULT NULL            COMMENT '创建部门',
  `create_by`           bigint        DEFAULT NULL            COMMENT '创建人',
  `create_time`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`           bigint        DEFAULT NULL            COMMENT '更新人',
  `update_time`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `del_flag`            char(1)       COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_hr_person_pernr` (`pernr`),
  KEY `idx_hr_person_orgeh` (`orgeh`),
  KEY `idx_hr_person_supervisorno` (`supervisorno`),
  KEY `idx_hr_person_last_sync_at` (`last_sync_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR 人员档案镜像（EHR 真源字段快照；非登录体系，不双轨）';


-- ──────────────────────── tenant.excludes 配套 ────────────────────────────────
-- hr_person_mirror 不参与多租户过滤（AGENTS.md 多租户默认开启，未登记会被自动追加租户条件）；
-- 由实施会话同步登记到 ruoyi-admin/src/main/resources/application.yml 的 tenant.excludes。
--
-- ──────────────────────── 验收脚本（apply 后跑） ───────────────────────────────
-- 1) SELECT COUNT(*) AS cnt FROM hr_person_mirror;                    -- 期望 ≥ 0
-- 2) SHOW INDEX FROM hr_person_mirror WHERE Key_name='uk_hr_person_pernr';  -- 期望 1 行
-- 3) SHOW CREATE TABLE hr_person_mirror\G  -- 期望 CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
-- 4) 不期望出现表：hr_sync_runs / hr_person_profiles（防双轨红线不变）
