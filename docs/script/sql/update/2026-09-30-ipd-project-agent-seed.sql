-- =====================================================================
-- 项目智能体 C02 W1 种子（2026-09-30）
-- 状态：已于本机 ipd_dev 执行，重复执行必须幂等（INSERT IGNORE / skill_names IS NULL）
-- 内容：① 能力包 market-research@v1（与 JAR 内 ipd-skills/capability-packs.json 同源）
--       ② C02 动作绑定 Skill：ipd_action_skill_map.skill_names（仅 NULL 时写入，不覆盖管理员定制）
-- 幂等：显式 ID + INSERT IGNORE；UPDATE 带 skill_names IS NULL 条件，重复执行无副作用。
-- Skill 锁定：competitor-analysis-ipd@1.0.0
--   sha256 = 28d695be2c9e69fb2fb4daab59f2d4edde644d2cec1d3b44fecd514af40203fa
--   （= ruoyi-modules/ruoyi-ipd/src/main/resources/ipd-skills/competitor-analysis-ipd/SKILL.md 原始字节）
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

INSERT IGNORE INTO `ipd_capability_pack`
  (`id`, `tenant_id`, `code`, `version`, `name`, `description`, `stages`, `action_codes`, `source`, `status`,
   `manifest_sha256`, `del_flag`, `create_by`, `create_time`)
VALUES
  (930000000000000001, '000000', 'market-research', 'v1', '商业/市场/竞品研究',
   '基于本项目已审核资料与用户输入开展竞品分析（功能/价格/渠道/技术路线），输出可追溯对比与缺项清单。',
   JSON_ARRAY('CONCEPT'), JSON_ARRAY('C02'), 'BUILTIN', 'ACTIVE', NULL, '0', -1, NOW());

INSERT IGNORE INTO `ipd_capability_pack_item`
  (`id`, `tenant_id`, `pack_id`, `item_type`, `item_ref`, `item_version`, `sha256`, `required`, `sort_order`,
   `del_flag`, `create_by`, `create_time`)
VALUES
  (930000000000000011, '000000', 930000000000000001, 'SKILL', 'competitor-analysis-ipd', '1.0.0',
   '28d695be2c9e69fb2fb4daab59f2d4edde644d2cec1d3b44fecd514af40203fa', 1, 1, '0', -1, NOW()),
  (930000000000000012, '000000', 930000000000000001, 'TOOL', 'project_knowledge_search', NULL,
   NULL, 1, 2, '0', -1, NOW());

-- C02 动作 → Skill 绑定（ipd_action_skill_map 已由 2026-09-28 种子建立 69 行，skill_names 均为 NULL）
UPDATE `ipd_action_skill_map`
   SET `skill_names` = JSON_ARRAY('competitor-analysis-ipd')
 WHERE `action_code` = 'C02'
   AND `sub_stage_code` = 'CONCEPT-S2'
   AND `skill_names` IS NULL;

-- 执行后只读核验：
-- SELECT code, version, status FROM ipd_capability_pack WHERE code = 'market-research';
-- SELECT item_type, item_ref, sha256 FROM ipd_capability_pack_item WHERE pack_id = 930000000000000001;
-- SELECT action_code, sub_stage_code, skill_names FROM ipd_action_skill_map WHERE action_code = 'C02';
-- 回滚（仅本种子写入的行）：
-- DELETE FROM ipd_capability_pack_item WHERE id IN (930000000000000011, 930000000000000012);
-- DELETE FROM ipd_capability_pack WHERE id = 930000000000000001;
-- UPDATE ipd_action_skill_map SET skill_names = NULL
--  WHERE action_code = 'C02' AND sub_stage_code = 'CONCEPT-S2'
--    AND JSON_CONTAINS(skill_names, JSON_QUOTE('competitor-analysis-ipd')) AND JSON_LENGTH(skill_names) = 1;
