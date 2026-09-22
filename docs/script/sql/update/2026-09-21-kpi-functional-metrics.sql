-- =====================================================================
-- KPI A2 P1 — 功能指标量表（kpi_functional_metrics）
-- 日期：2026-09-21
-- 授权：OWNER-拍板登记-20260921.md（P3 九项全部完整执行 → 第 2 项 KPI 8 项功能指标）
-- 方案：R148.1 §2.2 A2 选项 ② P1 期（建表 + 录入入口）
--
-- 背景：KpiScoreCalculator 当前只有 deviationDays / windowHitRate 两个 compute 方法；
--       其余 6 项功能指标无录入入口、无目标值、无量表版本。本表提供 8 项功能指标的
--       人工录入/量表载体（P1 仅数据采集 + 配置入口，计算逻辑属 P2）。
--
-- 8 项功能指标编码（与 KpiScoreCalculator 字段名一一对应；DOC-01 §4 功能指标表）：
--   市场侧 MARKET_PM_FUNCTIONAL_FIELDS：
--     MKT_REQUIREMENT_ACCURACY     需求准确率      (requirementAccuracy)
--     MKT_WINDOW_HIT_RATE          窗口命中率      (windowHitRate)
--     MKT_SCENARIO_COMPETITIVENESS 场景方案竞争力  (scenarioCompetitiveness)
--     MKT_COMPETITOR_INTELLIGENCE  竞品情报质量    (competitorIntelligence)
--   研发侧 RD_PM_FUNCTIONAL_FIELDS：
--     RD_LAUNCH_ON_TIME_RATE       上市准时率      (launchOnTimeRate)
--     RD_QUALITY_DEFECT_RATE       质量缺陷率(PPM) (qualityDefectRate)
--     RD_TECH_INNOVATION           技术创新度      (techInnovation)
--     RD_FIRST_PASS_YIELD          需求一次性实现率 (firstPassYield)
--
-- 命名约定：IPD 标配（id/bigint + tenant_id/varchar(20) + del_flag/char(1)
--            + BaseEntity 字段 create_dept/create_by/create_time/update_by/update_time）。
--
-- R-A2 约束：scale_version 用 VARCHAR(50) **不加 FK**——kpi_rule_snapshots 表当前
--            0 行，加 FK 会与 R139 P0 #3 互锁阻塞；待 R139 P0 #3 解决后另行补 FK。
-- =====================================================================

CREATE TABLE IF NOT EXISTS kpi_functional_metrics (
    id            bigint        NOT NULL                  COMMENT '主键（雪花算法）',
    project_id    bigint        NOT NULL                  COMMENT '项目 ID',
    metric_code   varchar(50)   NOT NULL                  COMMENT '指标编码（8 项，见文件头注释）',
    period        varchar(20)   NOT NULL                  COMMENT '期间（如 2026-09 或 上市后 6 个月）',
    metric_value  decimal(18,4) DEFAULT NULL              COMMENT '指标值（人工录入；NULL = 待补充，不等同 0 分）',
    target_value  decimal(18,4) DEFAULT NULL              COMMENT '目标值（如 PPM 目标）',
    scale_version varchar(50)   DEFAULT NULL              COMMENT '量表版本（VARCHAR(50)，不加 FK：R-A2 约束）',
    remark        varchar(500)  DEFAULT NULL              COMMENT '备注',
    tenant_id     varchar(20)   DEFAULT '000000'          COMMENT '租户 ID（单企业私有部署，无多租户语义）',
    del_flag      char(1)       NOT NULL DEFAULT '0'      COMMENT '删除标志（0 正常 1 已删）',
    create_by     bigint        DEFAULT NULL              COMMENT '创建者',
    create_dept   bigint        DEFAULT NULL              COMMENT '创建部门',
    create_time   datetime      DEFAULT NULL              COMMENT '创建时间',
    update_by     bigint        DEFAULT NULL              COMMENT '更新者',
    update_time   datetime      DEFAULT NULL              COMMENT '更新时间',
    PRIMARY KEY (id),
    -- upsert 幂等键（PUT 同 project+metric+period 覆盖而非追加）
    UNIQUE KEY uk_kfm_project_metric_period (project_id, metric_code, period),
    KEY idx_kfm_project_period (project_id, period),
    KEY idx_kfm_metric (metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='KPI 功能指标量表（人工录入，A2 P1）';

-- ---------------------------------------------------------------------
-- apply 后核验（人工执行后）：
--   SHOW CREATE TABLE ipd_dev.kpi_functional_metrics;
--   SELECT COUNT(*) FROM ipd_dev.kpi_functional_metrics;   -- 预期 0
--
-- 真库只读探针结论（2026-09-21）：
--   SHOW TABLES FROM ipd_dev LIKE 'kpi_functional%'  → 空集 ⇒ 安全 CREATE
--   同域表 kpi_raw_records / kpi_records / kpi_rule_snapshots / kpi_shared_confirms 已存在
--
-- 回滚 SOP（反向）：
--   DROP TABLE IF EXISTS ipd_dev.kpi_functional_metrics;   -- apply 前为不存在 ⇒ 反向即 DROP
--   并回退 ruoyi-admin/src/main/resources/application.yml 的 tenant.excludes 登记行。
-- ---------------------------------------------------------------------
