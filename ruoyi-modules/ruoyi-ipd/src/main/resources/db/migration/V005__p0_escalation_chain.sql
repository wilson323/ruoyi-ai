-- V005__p0_escalation_chain.sql
-- R149 batch2b C4：P0 升级链表（AC-C4 决策：同一个 P0 连续两次都没升级，就要升级到双方组长）
--
-- 每条记录对应一个 (projectId, p0EventId) 的「升级计数」：
--   - escalation_count：连续未升级次数（每次 P0 超期未升级 +1）
--   - last_escalation_at：最近一次「未升级」时间
--   - next_threshold_at：预计下次超期阈值
--   - status：PENDING|ESCALATED|RESOLVED
--
-- 触发逻辑：业务代码 P0EscalationService.checkEscalation() 扫描 count >= 2 的 PENDING 记录，
-- 给所有 GROUP_LEADER 发通知（NotificationEvent.content 标识 receiver_role='BOTH_LEADERS'）。
--
-- ⚠️ 本文件为 R149 batch2b 草稿，未 apply：
--   - 由 R149-batch2b 任务提供 SQL 草稿（不 apply）
--   - 由 DBA 在 R150 合入主树时执行
--   - 业务代码已就绪（P0EscalationService + P0EscalationController），DDL apply 后立即生效

CREATE TABLE IF NOT EXISTS p0_escalation_chain (
    id                    BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键（雪花 ID）',
    project_id            BIGINT          NOT NULL                COMMENT '项目 ID',
    p0_event_id           BIGINT          NOT NULL                COMMENT 'P0 事件 ID（业务侧唯一）',
    escalation_count      INT             NOT NULL DEFAULT 0      COMMENT '连续未升级次数',
    last_escalation_at    DATETIME        NULL                    COMMENT '最近一次「未升级」时间',
    next_threshold_at     DATETIME        NULL                    COMMENT '预计下次超期阈值（调度扫描用）',
    status                VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|ESCALATED|RESOLVED',
    remark                VARCHAR(500)    NULL                    COMMENT '备注（升级原因 / 处置结果）',
    tenant_id             VARCHAR(20)     NULL     DEFAULT '000000' COMMENT '租户 ID',
    create_dept           BIGINT          NULL                    COMMENT '创建部门',
    create_by             BIGINT          NULL                    COMMENT '创建人',
    create_time           DATETIME        NULL                    COMMENT '创建时间',
    update_by             BIGINT          NULL                    COMMENT '更新人',
    update_time           DATETIME        NULL                    COMMENT '更新时间',
    del_flag              CHAR(1)         NULL     DEFAULT '0'     COMMENT '软删除标志',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pec_project_event_pending (project_id, p0_event_id, status),
    KEY idx_pec_status_count (status, escalation_count),
    KEY idx_pec_project (project_id),
    KEY idx_pec_last_at (last_escalation_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'P0 升级链（R149 batch2b C4 草稿；R150 合入）';
