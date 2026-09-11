-- P1-10.2：AI 文档审核→归档门禁 + 版本对比（AC-AI-03 / AC-AI-05，BR-AI-02 / BR-AI-06）
-- 适用库：ipd_dev（本地隔离环境；禁止迁移生产，由 DBA 按发布窗口执行）
-- 前置：ai_documents 表已存在（P0-2 基线 + P1-10.1 增列），当前 0 行，本 ALTER 无损。

-- 1) 拒绝原因——BR-AI-03 审核拒绝落 audit；与 review/reject 同步落 review_comment

-- [idem-guard: ALTER ai_documents.review_comment]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_documents' AND COLUMN_NAME='review_comment');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE ai_documents ADD COLUMN review_comment VARCHAR(1000) NULL COMMENT ''审核/拒绝备注（P1-10.2）'' AFTER reviewed_at',
  'SELECT ''ai_documents.review_comment exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- 2) 归档落名——archived_at / archived_by 仅 ARCHIVED 行非空

-- [idem-guard: ALTER ai_documents.archived_at]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_documents' AND COLUMN_NAME='archived_at');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE ai_documents ADD COLUMN archived_at DATETIME NULL COMMENT ''归档时间（P1-10.2，AC-AI-03）'' AFTER review_comment',
  'SELECT ''ai_documents.archived_at exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER ai_documents.archived_by]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_documents' AND COLUMN_NAME='archived_by');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE ai_documents ADD COLUMN archived_by BIGINT NULL COMMENT ''归档操作者 ID（P1-10.2，BR-AI-06 审计身份可信）'' AFTER archived_at',
  'SELECT ''ai_documents.archived_by exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- 3) 状态索引——运营侧按状态筛选（如查全部 ARCHIVED 行）

-- [idem-guard: ADD INDEX idx_ai_doc_status ON ai_documents]
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ai_documents' AND INDEX_NAME='idx_ai_doc_status');

SET @ddl := IF(@idx_exists=0,
  'ALTER TABLE ai_documents ADD INDEX idx_ai_doc_status (status)',
  'SELECT ''ai_documents.idx_ai_doc_status exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- 回滚（仅本地隔离环境验证用）：
-- ALTER TABLE ai_documents
--     DROP INDEX idx_ai_doc_status,
--     DROP COLUMN archived_by,
--     DROP COLUMN archived_at,
--     DROP COLUMN review_comment;
