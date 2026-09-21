-- =====================================================================
-- R149 batch2a — C1 90 日回款预警表（recovery_warnings）+ 阈值种子
-- 日期：2026-09-20
-- 状态：DO NOT APPLY — 待 owner apply
--
-- 背景：90 日回款预警完全没实现。
--
-- 业务规则：
--   * 扫描所有上市后未满 90 日的项目
--   * 回款比例 = SUM(窗口内 receipt_amount - refund_amount) / target_sales_amount
--   * 阈值 = system_configs.config_key = 'recovery.warning90d.threshold'
--           默认 0.25（本 SQL 末尾用 INSERT IGNORE 落种子，避免硬编码）
--   * 低于阈值 → 写入 recovery_warnings（status='PENDING'）
--
-- 幂等：同日 (project_id, warning_date) 已存在则跳过（service 层去重）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS recovery_warnings (
    id                bigint        NOT NULL                COMMENT '主键（雪花算法）',
    project_id        bigint        NOT NULL                COMMENT '项目ID',
    warning_date      date          NOT NULL                COMMENT '预警日期（扫描当日）',
    days_since_launch int           DEFAULT NULL            COMMENT '距上市日数（扫描当日 - 上市日）',
    recovery_rate     decimal(5,4)  DEFAULT NULL            COMMENT '回款比例（0~1）',
    threshold         decimal(5,4)  DEFAULT NULL            COMMENT '触发阈值（默认 0.25）',
    status            varchar(20)   NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING|HANDLED|IGNORED',
    tenant_id         varchar(20)   DEFAULT '000000'        COMMENT '租户ID',
    del_flag          tinyint       NOT NULL DEFAULT 0      COMMENT '删除标志（0正常 1已删）',
    create_by         bigint        DEFAULT NULL            COMMENT '创建者',
    create_dept        bigint        DEFAULT NULL            COMMENT '创建部门（R152-B3 补齐）',
    create_time       datetime      DEFAULT NULL            COMMENT '创建时间',
    update_by         bigint        DEFAULT NULL            COMMENT '更新者',
    update_time       datetime      DEFAULT NULL            COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recovery_proj_date (project_id, warning_date, del_flag),
    KEY idx_recovery_status (status, warning_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='90 日回款预警表（C1）';


-- ---------------------------------------------------------------------
-- 阈值种子（system_configs 新键）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO system_configs
    (id, config_key, config_value, value_type, default_value, description, tenant_id, create_time)
VALUES
    (1948090920,
     'recovery.warning90d.threshold',
     '0.25',
     'NUMBER',
     '0.25',
     '90 日回款预警阈值（回款比例 < 该值触发预警；用户拍板默认 0.25）',
     '000000',
     now());


-- ---------------------------------------------------------------------
-- apply 核验（人工执行后）：
--   SHOW TABLES LIKE 'recovery_warnings';
--   SHOW COLUMNS FROM recovery_warnings;
--   SELECT config_key, config_value FROM system_configs
--     WHERE config_key = 'recovery.warning90d.threshold';
--
-- 真库只读探针结论：
--   * 尚无 recovery_warnings 表 → 安全 CREATE
--   * SELECT 现有 system_configs 中无 recovery.warning90d.threshold 键 → 无冲突
-- ---------------------------------------------------------------------
