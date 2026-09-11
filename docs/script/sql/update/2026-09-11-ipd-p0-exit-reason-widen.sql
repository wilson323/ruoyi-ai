-- 2026-09-11 R30 生产就绪 E2E 抓获 P0：移交撤销真库 Data truncation
--
-- 现象：POST /api/v1/handovers/{id}/cancel 在真库 100% 返回 500，
--       MysqlDataTruncation: Data too long for column 'exit_reason'。
-- 根因：HandoverService.restoreForRollback（HIGH-3.1 撤销副作用反转）把接手人
--       project_members 行的 exit_reason 写为 'HANDOVER_ROLLBACK'（17 字符），
--       超出 exit_reason varchar(16)；accept 路径的 'HANDOVER'（9 字符）侥幸通过。
--       单测 mock Mapper 无列宽校验，全绿掩盖；R30 浏览器 E2E 真库首测即抓出。
-- 修复：列宽 16 → 32，注释枚举补齐代码实际写入值（HANDOVER / HANDOVER_ROLLBACK）。
-- 风险：纯加宽 online DDL（MySQL 8 instant/inplace），无数据迁移，存量值不变。
--
-- apply 核验（人工/DBA 执行后）：
--   SHOW COLUMNS FROM project_members WHERE Field='exit_reason';
--   预期 Type=varchar(32)。

ALTER TABLE project_members
  MODIFY COLUMN exit_reason varchar(32) DEFAULT NULL COMMENT '退出原因 TRANSFER|VOLUNTARY|LOW_PERF|HANDOVER|HANDOVER_ROLLBACK';
