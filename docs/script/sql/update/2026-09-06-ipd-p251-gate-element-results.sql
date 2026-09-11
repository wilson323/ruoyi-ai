-- P2-5.1 Gate 提交与逐项判定附件快照（激活 gate_element_results 死表，QA-04-D1）
-- apply 前：p1-ddl-apply-check.py 复核；仅在隔离开发环境执行
-- 1) gates.element_snapshot 提交时冻结的要素定义快照（longtext 与审计载荷同口径，DEF-6 备注沿用）

-- [idem-guard: ALTER gates.element_snapshot]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gates' AND COLUMN_NAME='element_snapshot');
SET @ddl := IF(@col_exists=0,
  'ALTER TABLE gates add column element_snapshot longtext null comment ''P2-5.1 提交时冻结的要素定义快照 JSON（后续编辑/停用不影响在途评审）'' after gate_coefficient',
  'SELECT ''gates.element_snapshot exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) gate_element_results.evidence_ref 判定证据附件引用（FAIL/否决必填，AC-GATE-02）

-- [idem-guard: ALTER gate_element_results.evidence_ref]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gate_element_results' AND COLUMN_NAME='evidence_ref');
SET @ddl := IF(@col_exists=0,
  'ALTER TABLE gate_element_results add column evidence_ref varchar(500) null comment ''判定证据附件引用（FAIL 必填 AC-GATE-02）'' after condition_note',
  'SELECT ''gate_element_results.evidence_ref exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
