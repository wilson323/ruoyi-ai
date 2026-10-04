-- IPD 智能体长期记忆（候选区），2026-10-02
--
-- 设计约束（owner 2026-10-02 决策）：
--   作用域 = project_id + person_id（个人记忆，不同人之间不互相召回）
--   内容来源 = 官方 LongTermMemory SPI 的 record()，经 LLM 抽取，只存可复用事实与用户偏好
--   权威性   = 本表**不是业务权威**。docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md §2 规则3「记忆不得自动成为业务权威」：
--              召回文本显式标注为「非权威个人工作笔记」，IPD 权限/审批/Gate 链路一律不查本表。
--   晋升     = status 由 '0'(候选) 变 '1'(已晋升) 表示已并入权威知识库；
--              未晋升的候选仍可召回，但带非权威标注。
--
-- 租户：按 project_id 隔离，登记进 application.yml 的 tenant.excludes（调度线程无会话上下文）

CREATE TABLE IF NOT EXISTS `ipd_agent_memory`
(
    `id`            BIGINT       NOT NULL COMMENT '雪花ID',
    `project_id`    BIGINT       NOT NULL COMMENT '项目（第一维隔离）',
    `person_id`     BIGINT       NOT NULL COMMENT '人员（第二维隔离）',
    `run_id`        BIGINT       NULL COMMENT '来源运行ID，仅追溯用，不参与隔离',
    `kind`          VARCHAR(32)  NOT NULL DEFAULT 'PREFERENCE' COMMENT 'PREFERENCE 用户偏好 / FACT 可复用事实 / OBSERVATION 观察',
    `content`       VARCHAR(2000) NOT NULL COMMENT '记忆正文（已脱敏、非权威）',
    `source_digest` CHAR(64)     NULL COMMENT '来源内容 SHA-256，作用域内去重与幂等',
    `status`        CHAR(1)      NOT NULL DEFAULT '0' COMMENT '0=候选(可召回,带非权威标注) 1=已晋升权威知识 2=废弃',
    `tenant_id`     VARCHAR(20)  DEFAULT NULL COMMENT '租户',
    `del_flag`      CHAR(1)      NOT NULL DEFAULT '0' COMMENT '删除标志 0存在 2删除',
    `create_dept`   BIGINT       DEFAULT NULL,
    `create_by`     BIGINT       DEFAULT NULL,
    `create_time`   DATETIME     DEFAULT NULL,
    `update_by`     BIGINT       DEFAULT NULL,
    `update_time`   DATETIME     DEFAULT NULL,
    `remark`        VARCHAR(500) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scope_digest` (`project_id`, `person_id`, `source_digest`),
    KEY `idx_scope_recall` (`project_id`, `person_id`, `status`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='IPD智能体长期记忆候选区(非业务权威)';

-- 运行账号逐表授权（本库为表级 CRUD 形态，建表后必须补，否则运行时 INSERT 被拒）：
-- 2026-10-03 实测踩坑：只建表不 GRANT，record 报 INSERT command denied。
GRANT SELECT, INSERT, UPDATE, DELETE ON `ipd_dev`.`ipd_agent_memory` TO 'ipd_app'@'127.0.0.1';
FLUSH PRIVILEGES;
