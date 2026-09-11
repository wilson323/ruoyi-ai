-- =====================================================================
-- ROOT-R2-P0-2 NotificationService 中间件化 — 表结构升级
-- 既有 21 列保持不动，仅追加 2 列（向后兼容；既有数据不破坏）：
--   target_channel : 路由通道（INBOX|EMAIL|WEBSOCKET，三通道分发）
--   locale         : 国际化（zh-CN|en-US，多语言模板渲染）
-- 既有 channel（投递处理器 code，如 MOCK/EMAIL_SENDER）/ deliveryStatus（状态）
-- / retryCount（重试次数）3 列保持原语义复用，dispatcher 走注解+枚举绑定映射。
-- 索引：idx_notify_target 仅为 inbox 列表按通道过滤加速，nullable 不破坏既有
-- 日期：2026-09-06
-- =====================================================================


-- [idem-guard: ALTER notification_events.target_channel]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_events' AND COLUMN_NAME='target_channel');
SET @ddl := IF(@col_exists=0,
  'ALTER TABLE notification_events
    ADD COLUMN target_channel VARCHAR(16) NULL
        COMMENT ''路由通道：INBOX|EMAIL|WEBSOCKET（新增 ROOT-R2-P0-2；既有 NULL=未指定）''
        AFTER channel,
    ADD COLUMN locale VARCHAR(8) NULL
        COMMENT ''国际化区域：zh-CN|en-US（新增 ROOT-R2-P0-2；既有 NULL=默认 zh-CN）''
        AFTER target_channel',
  'SELECT ''notification_events.target_channel exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 可选索引：按 target_channel 过滤的 inbox 查询加速（nullable 列 BTREE 索引有效）

-- [idem-guard: CREATE INDEX idx_notify_target ON notification_events]
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_events' AND INDEX_NAME='idx_notify_target');
SET @ddl := IF(@idx_exists=0,
  'CREATE INDEX idx_notify_target ON notification_events (receiver_id, target_channel, delivery_status)',
  'SELECT ''notification_events.idx_notify_target exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 租户共享登记已在 2026-09-05-ipd-notification-events.sql 备注；本变更不新增共享表
-- 真库最小权限（与 ipd_app@127.0.0.1 表级 GRANT 模型对齐，无需变更）;
