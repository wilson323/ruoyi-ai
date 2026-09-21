-- =====================================================================
-- R149 batch2a — A4 落地场景登记表（landed_scenarios）
-- 日期：2026-09-20
-- 状态：DO NOT APPLY — 待 owner apply
--
-- 背景：落地场景完全无登记入口。
--
-- 用户拍板简化（2026-09-20）：
--   * 仅做登记界面 + 导入入口，不做双认定（dual-cert）验证
--   * 不做销售报备表 JOIN / 交付验收表 JOIN
--   * 唯一性：(project_id, scenario_code, del_flag) → 重复录入由 service 抛 STATE_CONFLICT
-- =====================================================================

CREATE TABLE IF NOT EXISTS landed_scenarios (
    id            bigint        NOT NULL                  COMMENT '主键（雪花算法）',
    project_id    bigint        NOT NULL                  COMMENT '项目ID',
    scenario_code varchar(64)   NOT NULL                  COMMENT '场景编码',
    scenario_name varchar(200)  NOT NULL                  COMMENT '场景名',
    landed_date   date          NOT NULL                  COMMENT '落地日期',
    landed_amount decimal(14,2) DEFAULT NULL              COMMENT '落地金额（可空）',
    recorded_by   bigint        DEFAULT NULL              COMMENT '录入人',
    remark        varchar(500)  DEFAULT NULL              COMMENT '备注',
    tenant_id     varchar(20)   DEFAULT '000000'          COMMENT '租户ID',
    del_flag      tinyint       NOT NULL DEFAULT 0        COMMENT '删除标志（0正常 1已删）',
    create_by     bigint        DEFAULT NULL              COMMENT '创建者',
    create_time   datetime      DEFAULT NULL              COMMENT '创建时间',
    update_by     bigint        DEFAULT NULL              COMMENT '更新者',
    update_time   datetime      DEFAULT NULL              COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_landed_project_code (project_id, scenario_code, del_flag),
    KEY idx_landed_project_date (project_id, landed_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='落地场景登记表（A4，用户拍板简化版）';


-- ---------------------------------------------------------------------
-- apply 核验（人工执行后）：
--   SHOW TABLES LIKE 'landed_scenarios';
--   SHOW COLUMNS FROM landed_scenarios;
--
-- 真库只读探针结论：尚无 landed_scenarios 表 → 安全 CREATE。
-- ---------------------------------------------------------------------
