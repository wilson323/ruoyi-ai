-- =====================================================================
-- 文件：2026-10-03-ipd-retire-lc01-lc03-draft.sql
-- 日期：2026-10-03
-- 状态：DO NOT APPLY — 待 owner 拍板，未 apply，禁止执行本条 SQL
-- =====================================================================
--
-- 【背景】owner 已决定彻底退役三个业务域：回款台账 + 奖金池 + 业绩窗口（含系数变更）。
--   · 动作清单真源：docs/ipd-系统说明/外部资源/IPD系统_六阶段标准动作清单_v3.md
--     文首「v4 退役标注」节 —— 有效动作 67 条（原 v3 为 69 条）。
--   · LC01「上市后销售与回款跟踪」与 LC03「上市后6个月终算(回款达成率+奖金池)」
--     已于 2026-10-03 退役；代码侧 ActionCatalog / GuideScriptCatalog / 执行器码集
--     / AI 场景白名单 已同步删除（69 → 67）。
--
-- 【本脚本解决什么】两份 seed 是**已应用的迁移**，按退役纪律**禁止改写其正文**
--   （改写会造成「新装库没有、老库还有」的分叉）。因此历史遗留的 LC01/LC03 种子行
--   只能靠本**新增**清理脚本删除。本脚本尚未 apply。
--
-- 【owner 明确规定的三条纪律】
--   ① 本轮禁止写库、禁止任何 DDL —— 本文件仅登记，执行须 owner 单独授权。
--   ② 只 DELETE 种子/映射行，**不 DROP 任何表**。receipt_ledgers / bonus_pools /
--      coefficient_change_requests 等表按 owner 决定**保留不删**。
--   ③ 执行前先跑文末「预检」确认命中行数；执行后跑「回读核验」。
--
-- 【不在本脚本范围内的相邻事项（需 owner 另行裁决，勿顺手删）】
--   · 已开工项目 stage_actions 表内历史实例化的 LC01/LC03 动作行（运行时数据，
--     非种子）。是否清理、如何处理已完成/在途动作，属业务裁决，不在本脚本内。
--   · system_configs / ipd_business_config 中 bonus.* 存量配置行（代码已零引用，
--     但按纪律不删）。见 BusinessConfigKeys / SystemConfigServiceImpl 的登记注释。
--   · audit_logs 中 entity_type='coefficient_change_requests' / 'receipt_ledger'
--     的历史审计行：进哈希链（canonicalOf），删除会让链断裂，**绝对不动**。
--     见 org.ruoyi.ipd.audit.IpdEntityType 的常量注释。
--
-- 依据登记：docs/ipd-系统说明/外部资源/IPD系统_六阶段标准动作清单_v3.md（v4 退役标注节）
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

-- ---------------------------------------------------------------------
-- 预检（执行前先跑，确认命中行数与预期一致；本段只读，无副作用）
-- ---------------------------------------------------------------------
-- 预期：@lc01_agent_rows = 1（IPD-LC01 节点智能体建行）
SELECT COUNT(*) AS expect_1_lc01_agent_row
  FROM agent_info
 WHERE agent_name = 'IPD-LC01';

-- 预期：@lc_map_rows = 2（LC01 id=57 / LC03 id=61）
SELECT COUNT(*) AS expect_2_map_rows
  FROM ipd_action_skill_map
 WHERE action_code IN ('LC01', 'LC03');

-- ---------------------------------------------------------------------
-- 实施 SQL（⚠ 待 owner 拍板；owner 授权后由人工/DBA 执行）
-- ---------------------------------------------------------------------

-- 1) R236 节点智能体种子（20260927-ipd-node-agents.sql）遗留的 IPD-LC01 建行。
--    该动作已从 ActionCatalog 退役，AgentEvidenceExecutor 亦已让出 LC01 码，
--    行若保留即为「指向不存在动作」的孤儿智能体配置。
DELETE FROM agent_info
 WHERE agent_name = 'IPD-LC01';

-- 2) 动作-技能映射种子（2026-09-28-ipd-action-skill-map-seed.sql）中 LC01 / LC03 两行。
--    LIFECYCLE-S1 / LIFECYCLE-S2 小阶段本身保留（LC05/LC06/LC02/LC04 仍有效），
--    仅删除指向已退役动作的映射行；删除后该小阶段内 sort_order 可能出现空档
--    （LIFECYCLE-S1 剩 LC05=2/LC06=3；LIFECYCLE-S2 剩 LC04=3）——空档不影响读取，
--    如 owner 要求连续，请另行裁决是否重排 sort_order（本脚本不动序号）。
DELETE FROM ipd_action_skill_map
 WHERE action_code IN ('LC01', 'LC03');

-- ---------------------------------------------------------------------
-- 回读核验（执行后跑；两行都应返回 0）
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS verify_0_lc01_agent_row
  FROM agent_info
 WHERE agent_name = 'IPD-LC01';

SELECT COUNT(*) AS verify_0_map_rows
  FROM ipd_action_skill_map
 WHERE action_code IN ('LC01', 'LC03');

-- =====================================================================
-- 回滚语句（执行本脚本后如需恢复）
-- =====================================================================
-- 回滚方式一（推荐，逐字回到迁移原文语义）：
--   重新执行源迁移文件即可，两条 INSERT 都带幂等守卫 / 显式主键：
--     docs/script/sql/update/20260927-ipd-node-agents.sql
--       —— INSERT ... WHERE NOT EXISTS (SELECT 1 FROM agent_info WHERE agent_name='IPD-LC01')
--     docs/script/sql/update/2026-09-28-ipd-action-skill-map-seed.sql
--       —— 若该文件为 INSERT IGNORE 语义，直接重跑；若为裸 INSERT，见方式二
--
-- 回滚方式二（手工等价恢复两条映射行，幂等）：
--   INSERT IGNORE INTO ipd_action_skill_map (id, action_code, sub_stage_code, skill_names, sort_order, remark)
--   VALUES (57, 'LC01', 'LIFECYCLE-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
--          (61, 'LC03', 'LIFECYCLE-S2', NULL, 2, '待 §3 定稿后补齐 skill_names');
--
-- 回滚方式三（节点智能体行）：重跑 20260927-ipd-node-agents.sql 即可由幂等守卫重建 IPD-LC01 行。
-- =====================================================================
