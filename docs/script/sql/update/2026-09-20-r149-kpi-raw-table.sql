-- =====================================================================
-- R149 batch2a — A2 KPI 原始数据表（kpi_raw_records）
-- 日期：2026-09-20
-- 状态：DO NOT APPLY — 待 owner apply
--
-- 背景：8 项 KPI 中只有窗口命中率有算法，其他 7 项无录入入口。
--       新增 kpi_raw_records 用于补齐原始数据录入路径。
--
-- 命名约定：IPD 标配（id/bigint auto + tenant_id/varchar(20) + del_flag/tinyint
--            + BaseEntity 字段 create_by/create_time/update_by/update_time）。
--
-- 唯一性：(kpi_type, project_id, record_period, del_flag) → 重复录入由
--         service 层抛 STATE_CONFLICT。
-- =====================================================================

CREATE TABLE IF NOT EXISTS kpi_raw_records (
    id            bigint        NOT NULL                  COMMENT '主键（雪花算法）',
    kpi_type      varchar(32)   NOT NULL                  COMMENT 'KPI 类型：WINDOW_HIT_RATE / REQUIREMENT_ACCURACY / SCENE_COMPETITIVENESS / PPM_DEFECT_RATE / RELEASE_FREQUENCY / CHANGE_LEAD_TIME / CHANGE_FAILURE_RATE / MTTR',
    project_id    bigint        NOT NULL                  COMMENT '项目ID',
    record_period date          NOT NULL                  COMMENT '录入周期（YYYY-MM-01，按月）',
    raw_value     decimal(10,4) NOT NULL                  COMMENT '原始值（统一 decimal(10,4)，容纳 ppm/百分比/时长/频率）',
    recorded_by   bigint        DEFAULT NULL              COMMENT '录入人',
    recorded_at   datetime      DEFAULT NULL              COMMENT '录入时间',
    tenant_id     varchar(20)   DEFAULT '000000'          COMMENT '租户ID',
    del_flag      tinyint       NOT NULL DEFAULT 0        COMMENT '删除标志（0正常 1已删）',
    create_by     bigint        DEFAULT NULL              COMMENT '创建者',
    create_dept   bigint        DEFAULT NULL              COMMENT '创建部门（R152-B3 补齐）',
    create_time   datetime      DEFAULT NULL              COMMENT '创建时间',
    update_by     bigint        DEFAULT NULL              COMMENT '更新者',
    update_time   datetime      DEFAULT NULL              COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kpi_raw_type_proj_period (kpi_type, project_id, record_period, del_flag),
    KEY idx_kpi_raw_project (project_id, record_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='KPI 原始数据录入表（A2 P1 期补口）';


-- ---------------------------------------------------------------------
-- apply 核验（人工执行后）：
--   SHOW TABLES LIKE 'kpi_raw_records';
--   SHOW COLUMNS FROM kpi_raw_records;
--
-- 真库只读探针结论：尚无 kpi_raw_records 表 → 安全 CREATE。
-- ---------------------------------------------------------------------
