-- =====================================================================
-- IPD 六阶段小阶段化（Track A1，2026-09-28）
-- 状态：DO NOT APPLY — 待 owner apply（AI 产出 SQL、owner 人工执行；
--       DDL 未 apply 前严禁启动带实体映射的写路径，禁跑 seed）
-- 依据：docs/ipd-系统说明/开发计划-分节草稿/§6-数据模型与DDL.md §6.2/§6.4
-- 语法：MySQL 8.0（真库 8.0.46）；幂等：CREATE TABLE IF NOT EXISTS / INSERT 带显式 id
-- =====================================================================
-- 2026-09-28-ipd-action-skill-map-seed.sql — 状态：待 owner apply（DDL 未 apply 前禁止执行）
-- skill_names 一律 NULL：§3-pm-skills映射.md §3.2 已列每动作绑定候选，定稿后按行 UPDATE 为 JSON 数组
-- 正文 = §6.4.3 的 **67 行** INSERT（skill_names 一律 NULL，remark='待 §3 定稿后补齐 skill_names'）；
--   ⚠️ 2026-10-07 ③刀清污：原为 69 行，删去 id=57(LC01) / id=61(LC03) 两行——
--      LC01「上市后销售与回款跟踪」与 LC03「上市后6个月终算」已于 **2026-10-03** 退役
--      （owner 决定移除「回款台账」「奖金池」两个功能块，对应 Service/Controller 已删除），
--      两者已不在 ActionCatalog 中，本表的这两行是指向已退役动作的**孤儿引用**。
--      保留会让「已退役动作仍有技能绑定」持续成立，也与现态 67 动作（深管 40 / 轻管 27）对不上。
--      删除前已导出备份；重跑本脚本不会再把它们写回来。
-- 与 §6.4.3 原稿差异（冲突裁定：以主计划 §2 现文为准，详见交付差异登记）：
--   ① DEV 归属回正（主计划 §2.3 + ActionCatalog D-1 回正后口径）：
--      DEV-S1=D01,D10；DEV-S2=D02,D03,D04,D07,D09,D11；DEV-S3=D06,D08,D05
--      （§6.4.3 原稿 DEV-S1=D01,D02,D03,D10 / DEV-S2=D04,D07,D11,D09,D05 / DEV-S3=D06,D08 已废）；
--   ② 小阶段内 sort_order 行序 = 主计划 §2「含动作」列序（DEV-S3：D06=1、D08=2、D05=3，G3 评审动作收尾）。
INSERT INTO ipd_action_skill_map (id, action_code, sub_stage_code, skill_names, sort_order, remark) VALUES
(1, 'C01', 'CONCEPT-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(2, 'C04', 'CONCEPT-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(3, 'C02', 'CONCEPT-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(4, 'C03', 'CONCEPT-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(5, 'C06', 'CONCEPT-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(6, 'C07', 'CONCEPT-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(7, 'C08', 'CONCEPT-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(8, 'C09', 'CONCEPT-S3', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(9, 'C05', 'CONCEPT-S4', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(10, 'C10', 'CONCEPT-S4', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(11, 'C12', 'CONCEPT-S4', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(12, 'C11', 'CONCEPT-S4', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(13, 'P01', 'PLAN-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(14, 'P02', 'PLAN-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(15, 'P03', 'PLAN-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(16, 'P04', 'PLAN-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(17, 'P05', 'PLAN-S2', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(18, 'P06', 'PLAN-S2', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(19, 'P07', 'PLAN-S2', NULL, 5, '待 §3 定稿后补齐 skill_names'),
(20, 'P08', 'PLAN-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(21, 'P09', 'PLAN-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(22, 'P11', 'PLAN-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(23, 'P10', 'PLAN-S4', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(24, 'P12', 'PLAN-S4', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(25, 'P13', 'PLAN-S4', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(26, 'D01', 'DEV-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(27, 'D10', 'DEV-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(28, 'D02', 'DEV-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(29, 'D03', 'DEV-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(30, 'D04', 'DEV-S2', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(31, 'D07', 'DEV-S2', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(32, 'D09', 'DEV-S2', NULL, 5, '待 §3 定稿后补齐 skill_names'),
(33, 'D11', 'DEV-S2', NULL, 6, '待 §3 定稿后补齐 skill_names'),
(34, 'D06', 'DEV-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(35, 'D08', 'DEV-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(36, 'D05', 'DEV-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(37, 'V01', 'VALID-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(38, 'V04', 'VALID-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(39, 'V11', 'VALID-S1', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(40, 'V02', 'VALID-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(41, 'V10', 'VALID-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(42, 'V12', 'VALID-S2', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(43, 'V05', 'VALID-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(44, 'V06', 'VALID-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(45, 'V03', 'VALID-S4', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(46, 'V09', 'VALID-S4', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(47, 'V07', 'VALID-S4', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(48, 'V08', 'VALID-S4', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(49, 'L01', 'LAUNCH-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(50, 'L02', 'LAUNCH-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(51, 'L03', 'LAUNCH-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(52, 'L04', 'LAUNCH-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(53, 'L05', 'LAUNCH-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(54, 'L06', 'LAUNCH-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(55, 'L07', 'LAUNCH-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(56, 'L08', 'LAUNCH-S3', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(58, 'LC05', 'LIFECYCLE-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(59, 'LC06', 'LIFECYCLE-S1', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(60, 'LC02', 'LIFECYCLE-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(62, 'LC04', 'LIFECYCLE-S2', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(63, 'LC07', 'LIFECYCLE-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(64, 'LC08', 'LIFECYCLE-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(65, 'LC09', 'LIFECYCLE-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(66, 'K01', 'KPI-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(67, 'K02', 'KPI-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(68, 'K03', 'KPI-S1', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(69, 'K04', 'KPI-S1', NULL, 4, '待 §3 定稿后补齐 skill_names');
