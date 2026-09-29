-- =====================================================================
-- IPD 六阶段小阶段化（Track A1，2026-09-28）
-- 状态：DO NOT APPLY — 待 owner apply（AI 产出 SQL、owner 人工执行；
--       DDL 未 apply 前严禁启动带实体映射的写路径，禁跑 seed）
-- 依据：docs/ipd-系统说明/开发计划-分节草稿/§6-数据模型与DDL.md §6.2/§6.4
-- 语法：MySQL 8.0（真库 8.0.46）；幂等：CREATE TABLE IF NOT EXISTS / INSERT 带显式 id
-- =====================================================================

-- ===== ipd_sub_stage（DDL 逐字取自 §6.2.1；§6.2.1 块内旧文件名注释由本统一头部取代，见 §6.4.1 落盘命名）=====
CREATE TABLE IF NOT EXISTS ipd_sub_stage (
    id           bigint       NOT NULL                     COMMENT '主键（应用侧雪花 ASSIGN_ID）',
    code         varchar(32)  NOT NULL                     COMMENT '小阶段编码（CONCEPT-S1..KPI-S1，全局唯一）',
    name         varchar(64)  NOT NULL                     COMMENT '小阶段名称（主计划 §2 表名）',
    stage_code   varchar(16)  NOT NULL                     COMMENT '所属大阶段 CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE|KPI（KPI=常驻跨阶段）',
    sort_order   int          NOT NULL DEFAULT 0           COMMENT '阶段内排序（六阶段从 1 连续；KPI-S1=99 常驻豁免）',
    is_gate      char(1)      NOT NULL DEFAULT '0'         COMMENT '是否承载大阶段 Gate（1=是）',
    gate_code    varchar(8)   DEFAULT NULL                 COMMENT 'Gate 编码 G1..G5（is_gate=1 必填，唯一）',
    skill_hint   varchar(255) DEFAULT NULL                 COMMENT 'pm-skills 插件级引导提示（skill/command 级待 §3 定稿）',
    is_resident  char(1)      NOT NULL DEFAULT '0'         COMMENT '常驻小阶段（1=跨阶段 KPI 归集，不参与顺序推进门禁）',
    owner_role   varchar(16)  DEFAULT NULL                 COMMENT '主导角色 MARKET_PM|RD_PM|BOTH|GROUP_LEADER',
    remark       varchar(500) DEFAULT NULL                 COMMENT '备注',
    tenant_id    varchar(20)  DEFAULT '000000'             COMMENT '租户ID（单企业部署恒 000000，登记 tenant.excludes）',
    del_flag     char(1)      DEFAULT '0'                   COMMENT '删除标志（0正常 1已删）',
    create_by    bigint       DEFAULT NULL                 COMMENT '创建者',
    create_dept  bigint       DEFAULT NULL                 COMMENT '创建部门',
    create_time  datetime     DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
    update_by    bigint       DEFAULT NULL                 COMMENT '更新者',
    update_time  datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_stage_code (code),
    UNIQUE KEY uk_sub_stage_stage_sort (stage_code, sort_order),
    CONSTRAINT chk_sub_stage_code CHECK (code REGEXP '^(CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE|KPI)-S[0-9]{1,2}$'),
    CONSTRAINT chk_sub_stage_flag CHECK (is_gate IN ('0','1') AND is_resident IN ('0','1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IPD 小阶段目录（22 行元数据，主计划 §2 SSOT 落库）';

-- ===== ipd_action_skill_map（DDL 逐字取自 §6.2.2；§6.2.2 块内旧文件名注释由本统一头部取代，见 §6.4.1 落盘命名）=====
CREATE TABLE IF NOT EXISTS ipd_action_skill_map (
    id             bigint       NOT NULL                     COMMENT '主键（应用侧雪花 ASSIGN_ID）',
    action_code    varchar(8)   NOT NULL                     COMMENT '动作编码（69 真动作；A01/A02/A1/A2 由 CHECK 排除）',
    sub_stage_code varchar(32)  NOT NULL                     COMMENT '归属小阶段（→ ipd_sub_stage.code）',
    skill_names    json         DEFAULT NULL                 COMMENT 'pm-skills 技能/命令 JSON 数组（§3 定稿后补齐；NULL=未定稿）',
    sort_order     int          NOT NULL DEFAULT 0           COMMENT '小阶段内动作排序（从 1 连续）',
    remark         varchar(500) DEFAULT NULL                 COMMENT '备注（seed 标注待 §3）',
    tenant_id      varchar(20)  DEFAULT '000000'             COMMENT '租户ID（登记 tenant.excludes）',
    del_flag       char(1)      DEFAULT '0'                   COMMENT '删除标志（0正常 1已删）',
    create_by      bigint       DEFAULT NULL                 COMMENT '创建者',
    create_dept    bigint       DEFAULT NULL                 COMMENT '创建部门',
    create_time    datetime     DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
    update_by      bigint       DEFAULT NULL                 COMMENT '更新者',
    update_time    datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_map_action_code (action_code),
    KEY idx_map_sub_stage (sub_stage_code),
    CONSTRAINT chk_map_action_code CHECK (action_code REGEXP '^(LC[0-9]{2}|[CPDVK][0-9]{2}|L[0-9]{2})$'),
    CONSTRAINT chk_map_sub_stage_code CHECK (sub_stage_code REGEXP '^(CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE|KPI)-S[0-9]{1,2}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IPD 动作×小阶段×pm-skill 映射（69 行元数据）';
