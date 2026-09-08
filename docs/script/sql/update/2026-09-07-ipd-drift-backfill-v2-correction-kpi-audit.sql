-- =====================================================================
-- IPD drift 补丁（v2-correction-kpi-audit）：correction_logs /
-- kpi_rule_snapshots 两张整表 + 9 张新建表的 RuoYi audit 列
-- （create_dept / create_by / create_time / update_by / update_time）。
--
-- 背景（2026-09-07 蜂群根治轮）：
--   1. 兄弟会话 2026-09-07-ipd-drift-backfill-entity-gap.sql 已 apply
--      了 correction_logs / kpi_rule_snapshots 两张整表的 CREATE。
--   2. 兄弟会话 2026-09-07-ipd-drift-backfill-round2-20entities.sql 已
--      apply 了 7 张新建表（含 9 张 audit 列所在的另外 7 张）。
--   3. 本文件原计划以 v2 主文件形式覆盖 9 整表 + 76 缺列，发现与兄弟
--      重叠后收口为补丁；删除 DELIMITER/存储过程，改为与主文件一致
--      的 prepare stmt + information_schema 守卫风格，保证 pymysql 可
--      单条 execute 重放。
-- 现状（2026-09-07 真库核对）：
--   - 2 整表都在
--   - 9 张表 × 5 个 audit 列 = 45 列全部就位
--   → 本 SQL 重复 apply 是 no-op（每条都 information_schema 判存在后
--     才 ALTER），作为留档/复盘/重放模板保留。
-- 幂等：information_schema 判存在守卫 + CREATE TABLE IF NOT EXISTS。
-- 真库：ipd_dev @ 13306，凭证走 cnf。
-- =====================================================================

-- ---------------------------------------------------------------------
-- (1) 整表缺失 2 张（兄弟 entity-gap.sql 已建，本节 apply no-op）
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `correction_logs` (
  `id` bigint NOT NULL COMMENT '主键（雪花）',
  `entity_type` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '实体类型',
  `entity_id` bigint NOT NULL COMMENT '实体ID',
  `field_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '字段名',
  `old_value` text COLLATE utf8mb4_general_ci COMMENT '旧值',
  `new_value` text COLLATE utf8mb4_general_ci COMMENT '新值',
  `reason` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '修正原因',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作人姓名',
  `operated_at` datetime DEFAULT NULL COMMENT '操作时间',
  `tenant_id` varchar(20) COLLATE utf8mb4_general_ci DEFAULT '000000' COMMENT '租户',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_cl_entity` (`entity_type`,`entity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='勘误日志（CorrectionLog）';

CREATE TABLE IF NOT EXISTS `kpi_rule_snapshots` (
  `id` bigint NOT NULL COMMENT '主键（雪花）',
  `version` bigint DEFAULT NULL COMMENT '规则版本号',
  `effective_from` datetime DEFAULT NULL COMMENT '生效起',
  `effective_to` datetime DEFAULT NULL COMMENT '生效止',
  `rule_json` text COLLATE utf8mb4_general_ci COMMENT '规则 JSON 快照',
  `created_by` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '创建人',
  `created_at` datetime DEFAULT NULL COMMENT '创建时间',
  `tenant_id` varchar(20) COLLATE utf8mb4_general_ci DEFAULT '000000' COMMENT '租户',
  `del_flag` char(1) COLLATE utf8mb4_general_ci DEFAULT '0' COMMENT '删除标志',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_krs_version` (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='KPI 规则快照（KpiRuleSnapshot）';

-- ---------------------------------------------------------------------
-- (2) 9 张表 × 5 audit 列 = 45 列（prepare stmt + info_schema 守卫）
--     已全部就位，本节 apply no-op。
-- ---------------------------------------------------------------------

-- correction_logs
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='correction_logs' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table correction_logs add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='correction_logs' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table correction_logs add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='correction_logs' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table correction_logs add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='correction_logs' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table correction_logs add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='correction_logs' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table correction_logs add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- kpi_rule_snapshots
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_rule_snapshots' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table kpi_rule_snapshots add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_rule_snapshots' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table kpi_rule_snapshots add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_rule_snapshots' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table kpi_rule_snapshots add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_rule_snapshots' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table kpi_rule_snapshots add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_rule_snapshots' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table kpi_rule_snapshots add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- gate_review_observers
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='gate_review_observers' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table gate_review_observers add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='gate_review_observers' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table gate_review_observers add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='gate_review_observers' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table gate_review_observers add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='gate_review_observers' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table gate_review_observers add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='gate_review_observers' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table gate_review_observers add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- ipd_business_config_versions
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config_versions' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table ipd_business_config_versions add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config_versions' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table ipd_business_config_versions add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config_versions' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table ipd_business_config_versions add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config_versions' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table ipd_business_config_versions add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config_versions' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table ipd_business_config_versions add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- kpi_shared_confirms
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_shared_confirms' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table kpi_shared_confirms add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_shared_confirms' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table kpi_shared_confirms add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_shared_confirms' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table kpi_shared_confirms add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_shared_confirms' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table kpi_shared_confirms add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='kpi_shared_confirms' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table kpi_shared_confirms add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- project_score_records
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_records' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table project_score_records add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_records' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table project_score_records add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_records' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table project_score_records add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_records' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table project_score_records add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_records' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table project_score_records add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- project_score_tasks
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_tasks' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table project_score_tasks add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_tasks' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table project_score_tasks add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_tasks' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table project_score_tasks add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_tasks' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table project_score_tasks add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='project_score_tasks' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table project_score_tasks add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- switching_acceptance
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='switching_acceptance' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table switching_acceptance add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='switching_acceptance' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table switching_acceptance add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='switching_acceptance' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table switching_acceptance add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='switching_acceptance' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table switching_acceptance add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='switching_acceptance' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table switching_acceptance add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- ipd_business_config（兄弟会话建时已带 audit 列，列在这里只为防御性幂等）
set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config' and column_name='create_dept');
set @ddl := if(@has=0,
  'alter table ipd_business_config add column create_dept bigint null comment ''创建部门'' after del_flag',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config' and column_name='create_by');
set @ddl := if(@has=0,
  'alter table ipd_business_config add column create_by bigint null comment ''创建人'' after create_dept',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config' and column_name='create_time');
set @ddl := if(@has=0,
  'alter table ipd_business_config add column create_time datetime null comment ''创建时间'' after create_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config' and column_name='update_by');
set @ddl := if(@has=0,
  'alter table ipd_business_config add column update_by bigint null comment ''更新人'' after create_time',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

set @has := (select count(*) from information_schema.columns
  where table_schema=database() and table_name='ipd_business_config' and column_name='update_time');
set @ddl := if(@has=0,
  'alter table ipd_business_config add column update_time datetime null comment ''更新时间'' after update_by',
  'select 1');
prepare stmt from @ddl; execute stmt; deallocate prepare stmt;

-- ---------------------------------------------------------------------
-- 回读校验（应全部=1/45）
-- ---------------------------------------------------------------------
select 'correction_logs' as obj, count(*) as v from information_schema.tables
  where table_schema=database() and table_name='correction_logs'
union all
select 'kpi_rule_snapshots', count(*) from information_schema.tables
  where table_schema=database() and table_name='kpi_rule_snapshots'
union all
select 'audit.45cols', count(*) from information_schema.columns
  where table_schema=database() and column_name in ('create_dept','create_by','create_time','update_by','update_time')
    and table_name in ('correction_logs','gate_review_observers','ipd_business_config_versions','kpi_rule_snapshots','kpi_shared_confirms','project_score_records','project_score_tasks','switching_acceptance','ipd_business_config');
