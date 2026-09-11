-- [SEC-FIX-HIGH-1.1] Gate 强制输出物 DDL 增量——评审材料 + 会议纪要
-- 业务规则（IPD系统_五大Gate评审要素_v1.md 行 50-55）：
--   每个 Gate 强制输出物三项缺一不可：
--     1. 评审材料（会前 2 工作日发出）
--     2. 评审要素逐项判定表（系统内打勾，已就位）
--     3. 会议纪要（含遗留项清单、责任人、期限）
-- 提交 Gate 时必须有评审材料 URL 与会议纪要 URL，否则阻断。

-- 兼容已有 gates 表结构（idempotent ADD COLUMN via IF NOT EXISTS）

-- [idem-guard: ALTER gates.materials_url]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gates' AND COLUMN_NAME='materials_url');
SET @ddl := IF(@col_exists=0,
  'ALTER TABLE gates ADD COLUMN materials_url varchar(500) null comment ''[SEC-FIX-HIGH-1.1] 评审材料 URL（会前 2 工作日发出）''',
  'SELECT ''gates.materials_url exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER gates.meeting_minutes_url]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gates' AND COLUMN_NAME='meeting_minutes_url');
SET @ddl := IF(@col_exists=0,
  'ALTER TABLE gates ADD COLUMN meeting_minutes_url varchar(500) null comment ''[SEC-FIX-HIGH-1.1] 会议纪要 URL（含遗留项清单）''',
  'SELECT ''gates.meeting_minutes_url exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
