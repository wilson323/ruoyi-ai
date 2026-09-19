-- 2026-09-18 PM Directory Mock 排除（P1-1）
-- 现象：项目移交接任人下拉出现 Mock-QA-SYNC-20260910B（实测 ACTIVE 状态被 /pm-directory 返回）
-- 根因：pm-directory 接口仅过滤 accountStatus='ACTIVE'，未排除 Mock 种子数据
-- 修法：①后端 PmDirectoryController 加 ne(accountStatus, 'MOCK') 过滤；②种子 Mock 账号打 MOCK 标
-- 真库改库（单纯测试种子数据，非结构性 DDL，AGENTS.md 允许直接改）：
USE ipd_dev;

UPDATE persons
SET account_status = 'MOCK'
WHERE name LIKE 'Mock-%'
   OR employee_no LIKE 'Mock-%'
   OR employee_no LIKE 'QA-SYNC-%'
   OR name LIKE '%qa-sync%';

SELECT id, name, employee_no, account_status FROM persons WHERE account_status = 'MOCK';
