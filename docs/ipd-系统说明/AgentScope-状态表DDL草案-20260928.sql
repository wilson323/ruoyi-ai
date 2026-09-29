-- AgentScope Java 2.0.3 状态表草案。来源：2026-09-28 只读 SHOW CREATE TABLE
-- ipd_poc.agentscope_sessions。仅供评审，未执行；不得放入自动迁移队列。
-- 执行前：确认 DataSource 实际 catalog 与 chat.kernel.agentscope.state-database 相同；
-- 确认目标库不存在同名表，核对权限、字符集、备份与回滚窗口。
-- 本脚本不含 USE，须在已核准的目标 catalog 上执行；禁止拿 PoC 库替代正式库。

SELECT DATABASE() AS selected_catalog;

CREATE TABLE `agentscope_sessions` (
  `session_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `state_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `item_index` int NOT NULL DEFAULT '0',
  `state_data` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`session_id`,`state_key`,`item_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 执行后只读核验：SHOW CREATE TABLE agentscope_sessions;
-- 业务验收：真实 SSE/WS 写入、重启回读、同键/异键隔离和回滚数据一致性。
-- 若建表后尚未产生数据且须回滚，可在核准窗口删除新表；已有数据不得直接 DROP。
