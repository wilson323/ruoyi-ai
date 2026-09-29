-- =====================================================================
-- IPD 六阶段小阶段化（Track A1，2026-09-28）
-- 状态：DO NOT APPLY — 待 owner apply（AI 产出 SQL、owner 人工执行；
--       DDL 未 apply 前严禁启动带实体映射的写路径，禁跑 seed）
-- 依据：docs/ipd-系统说明/开发计划-分节草稿/§6-数据模型与DDL.md §6.2/§6.4
-- 语法：MySQL 8.0（真库 8.0.46）；幂等：CREATE TABLE IF NOT EXISTS / INSERT 带显式 id
-- =====================================================================
-- 2026-09-28-ipd-sub-stage-seed.sql — 状态：待 owner apply（DDL 未 apply 前禁止执行）
-- 正文 = §6.4.2 的 22 行 INSERT（逐行转录主计划 §2.1-2.7）；
-- 与 §6.4.2 原稿差异（冲突裁定：以主计划 §2 现文为准，详见交付差异登记）：
--   ① DEV-S2 名称回正「迭代开发与样机」、DEV-S3 名称回正「变更·成本与阶段评审」（主计划 §2.3）；
--   ② G3 由 DEV-S2 移至 DEV-S3（主计划 §2.8：Gate 行=含评审动作的小阶段，G3↔D05@DEV-S3）：
--      DEV-S2 is_gate='0'/gate_code=NULL，DEV-S3 is_gate='1'/gate_code='G3'，remark 同步。
INSERT INTO ipd_sub_stage (id, code, name, stage_code, sort_order, is_gate, gate_code, skill_hint, is_resident, owner_role, remark) VALUES
(1, 'CONCEPT-S1', '市场洞察', 'CONCEPT', 1, '0', NULL, 'pm-product-discovery + pm-market-research', '0', 'MARKET_PM', 'seed：主计划 §2.1'),
(2, 'CONCEPT-S2', '竞争与客群', 'CONCEPT', 2, '0', NULL, 'pm-market-research', '0', 'MARKET_PM', 'seed：主计划 §2.1'),
(3, 'CONCEPT-S3', '商业论证', 'CONCEPT', 3, '0', NULL, 'pm-product-strategy', '0', 'MARKET_PM', 'seed：主计划 §2.1'),
(4, 'CONCEPT-S4', '合规与立项', 'CONCEPT', 4, '1', 'G1', 'pm-toolkit', '0', 'BOTH', 'seed：主计划 §2.1，承载 G1 立项 Go/No-Go'),
(5, 'PLAN-S1', '需求定义', 'PLAN', 1, '0', NULL, 'pm-product-strategy', '0', 'BOTH', 'seed：主计划 §2.2'),
(6, 'PLAN-S2', '技术方案', 'PLAN', 2, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.2'),
(7, 'PLAN-S3', '计划与资源', 'PLAN', 3, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.2'),
(8, 'PLAN-S4', '合规与差异化确认', 'PLAN', 4, '1', 'G2', 'pm-product-strategy', '0', 'BOTH', 'seed：主计划 §2.2，承载 G2 差异化确认'),
(9, 'DEV-S1', '设计与联调准备', 'DEV', 1, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.3'),
(10, 'DEV-S2', '迭代开发与样机', 'DEV', 2, '0', NULL, 'pm-execution + pm-ai-shipping', '0', 'RD_PM', 'seed：主计划 §2.3'),
(11, 'DEV-S3', '变更·成本与阶段评审', 'DEV', 3, '1', 'G3', 'pm-execution', '0', 'BOTH', 'seed：主计划 §2.3，承载 G3 开发双周评审'),
(12, 'VALID-S1', '设计验证', 'VALID', 1, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.4'),
(13, 'VALID-S2', '认证与适配', 'VALID', 2, '0', NULL, 'pm-execution + pm-toolkit', '0', 'MARKET_PM', 'seed：主计划 §2.4'),
(14, 'VALID-S3', '试产与量产准入', 'VALID', 3, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.4'),
(15, 'VALID-S4', '客户与交付', 'VALID', 4, '0', NULL, 'pm-go-to-market', '0', 'MARKET_PM', 'seed：主计划 §2.4'),
(16, 'LAUNCH-S1', 'GTM 策略', 'LAUNCH', 1, '0', NULL, 'pm-go-to-market', '0', 'MARKET_PM', 'seed：主计划 §2.5'),
(17, 'LAUNCH-S2', '销售赋能', 'LAUNCH', 2, '0', NULL, 'pm-go-to-market + pm-marketing-growth', '0', 'MARKET_PM', 'seed：主计划 §2.5'),
(18, 'LAUNCH-S3', '上市执行', 'LAUNCH', 3, '1', 'G4', 'pm-go-to-market', '0', 'BOTH', 'seed：主计划 §2.5，承载 G4 GTM 就绪'),
(19, 'LIFECYCLE-S1', '上市追踪', 'LIFECYCLE', 1, '0', NULL, 'pm-data-analytics', '0', 'MARKET_PM', 'seed：主计划 §2.6'),
(20, 'LIFECYCLE-S2', '复盘与结算', 'LIFECYCLE', 2, '1', 'G5', 'pm-data-analytics + pm-toolkit', '0', 'BOTH', 'seed：主计划 §2.6，承载 G5 上市后 90 天复盘'),
(21, 'LIFECYCLE-S3', '状态与退出', 'LIFECYCLE', 3, '0', NULL, 'pm-toolkit', '0', 'MARKET_PM', 'seed：主计划 §2.6，与 GROUP_LEADER 共担 LC09'),
(22, 'KPI-S1', '共担KPI归集（常驻）', 'KPI', 99, '0', NULL, 'pm-data-analytics', '1', 'GROUP_LEADER', 'seed：主计划 §2.7 常驻跨阶段');
