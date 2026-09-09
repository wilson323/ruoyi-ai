-- PERF-P0-4：bid_responses 加 (rd_pm_id, create_time) 联合索引
-- 配套 BidResponseService.listByRdPm（按研发 PM 查全部应标）与 BidInvitationService.listResponses
--   的 rd_pm_id 等值过滤；消除按人查询全表扫。2026-09-09 契约轮代码侧同步补 LIMIT 500 硬上限
--   （listResponses 生产路径 + listByRdPm 防接线踩坑）。
-- 幂等：重复执行安全（显式 information_schema 存在性检查，同 2026-09-06-ipd-perf01 模式）。

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'bid_responses'
      AND index_name = 'idx_br_rd_pm'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_br_rd_pm ON bid_responses(rd_pm_id, create_time)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 执行后校验（期望返回 idx_br_rd_pm 两列）
SELECT index_name, column_name, seq_in_index
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'bid_responses'
  AND index_name = 'idx_br_rd_pm'
ORDER BY seq_in_index;

-- ROLLBACK（环境异常回滚用）
-- DROP INDEX idx_br_rd_pm ON bid_responses;
