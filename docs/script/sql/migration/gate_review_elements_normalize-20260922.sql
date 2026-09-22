-- =============================================================================
-- R177-A3 / R175-B 修复：gate_review_elements 老格式数据归一化
-- 日期：2026-09-22
-- 适用范围：真库 MySQL 8.0.46 @ 127.0.0.1:13306/ipd_dev
-- 触发条件：R175-A 真活验证附带发现 #1
--   真库 33 条 PUBLISHED/Y 老格式要素被前端 normalize 误识别为「已停用」
--   （前端 status 仅匹配 lowercase，enabled 仅识别 '1'，老格式 PUBLISHED/Y 不识别）
--
-- ⚠️ 此脚本必须由 owner 显式授权后才可 apply
--   当前状态：未 apply（pending owner approval）
--
-- 执行顺序：
--   1) 备份（保留原 33 条 PUBLISHED 行）
--   2) 迁移（status UPPERCASE → lowercase；enabled Y/N → 1/0）
--   3) 验证（按 status+enabled GROUP BY，确认分布正确）
-- =============================================================================

-- 1) 备份：仅备份需要迁移的 PUBLISHED 行
CREATE TABLE IF NOT EXISTS gate_review_elements_backup_20260922 AS
SELECT * FROM gate_review_elements WHERE status = 'PUBLISHED';

-- 2) 迁移 status UPPERCASE → lowercase（仅 draft/published/archived 三个合法值）
UPDATE gate_review_elements SET status = 'published' WHERE status = 'PUBLISHED';
UPDATE gate_review_elements SET status = 'draft'     WHERE status = 'DRAFT';
UPDATE gate_review_elements SET status = 'archived'  WHERE status = 'ARCHIVED';

-- 3) 迁移 enabled Y/N → 1/0（兼容 MySQL 8 默认 utf8mb4_0900_ai_ci 大小写不敏感）
UPDATE gate_review_elements SET enabled = '1' WHERE enabled = 'Y';
UPDATE gate_review_elements SET enabled = '0' WHERE enabled = 'N';

-- 4) 验证：迁移完成后预期分布
--    status=published + enabled='1' 应有 66 条（33 老格式 + 33 新格式）
--    status=draft + enabled='0' 应有 1 条
--    status=published + enabled='0'  = 真停用数（待 owner apply 后统计复核）
SELECT status, enabled, COUNT(*) AS cnt
FROM gate_review_elements
GROUP BY status, enabled
ORDER BY status, enabled;

-- 5) 兜底校验：任何 status 不在白名单的行
SELECT id, element_code, status, enabled
FROM gate_review_elements
WHERE status NOT IN ('draft', 'published', 'archived');

-- 6) 兜底校验：任何 enabled 不在 '0'/'1' 的行
SELECT id, element_code, status, enabled
FROM gate_review_elements
WHERE enabled NOT IN ('0', '1');
