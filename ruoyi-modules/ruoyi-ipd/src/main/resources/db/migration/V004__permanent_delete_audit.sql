-- V004__permanent_delete_audit.sql
-- R149 batch2b C3：永久清除审计表
--
-- AC-C3 决策：最终清除要二次确认 + 审计永久保留。
-- 实体被永久删除前，先把整行 JSON 序列化写入本表，供事后审计/合规追溯。
--
-- ⚠️ 本文件为 R149 batch2b 草稿，未 apply：
--   - 由 R149-batch2b 任务提供 SQL 草稿（不 apply）
--   - 由 DBA 在 R150 合入主树时执行
--   - 业务代码已就绪（PermanentDeleteService + AdminPermanentDeleteController），DDL apply 后立即生效

CREATE TABLE IF NOT EXISTS permanent_delete_audit (
    id                    BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键（雪花 ID）',
    operator_id           BIGINT          NOT NULL                COMMENT '操作人 ID（persons.id；SUPER_ADMIN）',
    operator_name         VARCHAR(128)    NULL                    COMMENT '操作人姓名（冗余存，便于审计展示）',
    entity_type           VARCHAR(32)     NOT NULL                COMMENT '实体类型：person|project|kpi_record',
    entity_id             BIGINT          NOT NULL                COMMENT '被删实体主键',
    original_data_json    LONGTEXT        NULL                    COMMENT '被删实体的完整 JSON 快照（含 BaseEntity 审计字段）',
    deleted_at            DATETIME        NOT NULL                COMMENT '删除时间',
    ip_address            VARCHAR(64)     NULL                    COMMENT '客户端 IP（X-Forwarded-For 首段）',
    tenant_id             VARCHAR(20)     NULL     DEFAULT '000000' COMMENT '租户 ID',
    create_dept           BIGINT          NULL                    COMMENT '创建部门',
    create_by             BIGINT          NULL                    COMMENT '创建人',
    create_time           DATETIME        NULL                    COMMENT '创建时间',
    update_by             BIGINT          NULL                    COMMENT '更新人',
    update_time           DATETIME        NULL                    COMMENT '更新时间',
    del_flag              CHAR(1)         NULL     DEFAULT '0'     COMMENT '软删除标志（审计本身不允许真删）',
    PRIMARY KEY (id),
    KEY idx_pda_entity (entity_type, entity_id),
    KEY idx_pda_operator (operator_id),
    KEY idx_pda_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '永久清除审计（R149 batch2b C3 草稿；R150 合入）';
