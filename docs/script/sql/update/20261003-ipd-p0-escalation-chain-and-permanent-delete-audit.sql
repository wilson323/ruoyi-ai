-- 补齐迁移目录缺失的两张 IPD 表（2026-10-03）
--
-- 缘起：check-ddl-applied(--static) 报 `p0_escalation_chain` / `permanent_delete_audit`
-- 两个实体在 docs/script/sql/update/ 下没有任何迁移片段。2026-10-03 实测活库：
-- 两张表都存在、列与实体声明一致，但**迁移目录里从来没有建表语句** ——
-- 于是本机无害，而任何一次干净重建（换机器 / 灾备 / 上生产）都会缺这两张表。
-- 三张表被三条独立线索同时指向：数据层（租户归属）、迁移层（本文件）、
-- 调用链（P0 升级链写半边零调用 + 审计链 17 个缺口）。
--
-- 做法：按活库 `SHOW CREATE TABLE` 的现网字节回写，不重新设计字段。
-- 幂等：使用 CREATE TABLE IF NOT EXISTS（check-ddl-idempotent 要求）。
-- 执行前确认：SHOW TABLES LIKE 'p0_escalation_chain' → 空

CREATE TABLE IF NOT EXISTS `p0_escalation_chain` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键（雪花 ID）',
  `project_id` bigint NOT NULL COMMENT '项目 ID',
  `p0_event_id` bigint NOT NULL COMMENT 'P0 事件 ID（业务侧唯一）',
  `escalation_count` int NOT NULL DEFAULT '0' COMMENT '连续未升级次数',
  `last_escalation_at` datetime DEFAULT NULL COMMENT '最近一次「未升级」时间',
  `next_threshold_at` datetime DEFAULT NULL COMMENT '预计下次超期阈值（调度扫描用）',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|ESCALATED|RESOLVED',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注（升级原因 / 处置结果）',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户 ID',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` char(1) DEFAULT '0' COMMENT '软删除标志',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pec_project_event_pending` (`project_id`,`p0_event_id`,`status`),
  KEY `idx_pec_status_count` (`status`,`escalation_count`),
  KEY `idx_pec_project` (`project_id`),
  KEY `idx_pec_last_at` (`last_escalation_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='P0 升级链（R149 batch2b C4 草稿；R150 合入）';

CREATE TABLE IF NOT EXISTS `permanent_delete_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键（雪花 ID）',
  `operator_id` bigint NOT NULL COMMENT '操作人 ID（persons.id；SUPER_ADMIN）',
  `operator_name` varchar(128) DEFAULT NULL COMMENT '操作人姓名（冗余存，便于审计展示）',
  `entity_type` varchar(32) NOT NULL COMMENT '实体类型：person|project|kpi_record',
  `entity_id` bigint NOT NULL COMMENT '被删实体主键',
  `original_data_json` longtext COMMENT '被删实体的完整 JSON 快照（含 BaseEntity 审计字段）',
  `deleted_at` datetime NOT NULL COMMENT '删除时间',
  `ip_address` varchar(64) DEFAULT NULL COMMENT '客户端 IP（X-Forwarded-For 首段）',
  `tenant_id` varchar(20) DEFAULT '000000' COMMENT '租户 ID',
  `create_dept` bigint DEFAULT NULL COMMENT '创建部门',
  `create_by` bigint DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del_flag` char(1) DEFAULT '0' COMMENT '软删除标志（审计本身不允许真删）',
  PRIMARY KEY (`id`),
  KEY `idx_pda_entity` (`entity_type`,`entity_id`),
  KEY `idx_pda_operator` (`operator_id`),
  KEY `idx_pda_deleted_at` (`deleted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='永久清除审计（R149 batch2b C3 草稿；R150 合入）';
