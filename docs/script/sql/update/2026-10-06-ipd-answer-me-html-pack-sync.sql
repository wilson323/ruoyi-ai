-- =====================================================================
-- answer-me-with-html 能力包增量同步（2026-10-06）
-- 根因：classpath ipd-skills/capability-packs.json 新增 Skill answer-me-with-html-ipd
--       与工具 render_html_page（上游 QingYunA/answer-me-with-html @f3082c9，MIT，
--       vendor 于 ruoyi-ipd resources/ipd-am/，宿主 node 渲染单文件 HTML），
--       登记进全部 7 个能力包按需勾选；DB 种子与清单同源同步（W2 起 DB 可覆盖/扩展）。
-- 口径：SKILL sha256/version 逐字节同源自清单实算（SKILL.md 原始字节 SHA-256）；
--       TOOL 行不带版本/摘要（与既有 TOOL 条目同型）。不改 ipd_capability_pack 包行、
--       不触碰 ipd_action_skill_map（按需勾选，非动作绑定）。
-- 前置：2026-10-06-ipd-capability-pack-db-sync.sql 已执行（7 包 + 既有条目在库）。
-- 幂等：显式 ID + INSERT IGNORE，重复执行无副作用。
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

START TRANSACTION;

-- 7 个包各追加 1 条 SKILL + 1 条 TOOL（追加在既有 sort_order 之后）
INSERT IGNORE INTO `ipd_capability_pack_item`
  (`id`, `tenant_id`, `pack_id`, `item_type`, `item_ref`, `item_version`, `sha256`, `required`, `sort_order`,
   `del_flag`, `create_by`, `create_time`)
VALUES
  -- market-research（id=…001，既有 sort 至 37）
  (93000000000001101, '000000', 930000000000000001, 'SKILL', 'answer-me-with-html-ipd', '1.0.0',
   '0296e9c79afd781314ac460f73687bfa62abcad4dfd0a89a00a41c24d72359da', 1, 38, '0', -1, NOW()),
  (93000000000001102, '000000', 930000000000000001, 'TOOL', 'render_html_page', NULL,
   NULL, 1, 39, '0', -1, NOW()),
  -- ipd-concept（id=…002，既有 sort 至 27）
  (93000000000001103, '000000', 930000000000000002, 'SKILL', 'answer-me-with-html-ipd', '1.0.0',
   '0296e9c79afd781314ac460f73687bfa62abcad4dfd0a89a00a41c24d72359da', 1, 28, '0', -1, NOW()),
  (93000000000001104, '000000', 930000000000000002, 'TOOL', 'render_html_page', NULL,
   NULL, 1, 29, '0', -1, NOW()),
  -- ipd-plan（id=…003，既有 sort 至 23）
  (93000000000001105, '000000', 930000000000000003, 'SKILL', 'answer-me-with-html-ipd', '1.0.0',
   '0296e9c79afd781314ac460f73687bfa62abcad4dfd0a89a00a41c24d72359da', 1, 24, '0', -1, NOW()),
  (93000000000001106, '000000', 930000000000000003, 'TOOL', 'render_html_page', NULL,
   NULL, 1, 25, '0', -1, NOW()),
  -- ipd-launch（id=…004，既有 sort 至 25）
  (93000000000001107, '000000', 930000000000000004, 'SKILL', 'answer-me-with-html-ipd', '1.0.0',
   '0296e9c79afd781314ac460f73687bfa62abcad4dfd0a89a00a41c24d72359da', 1, 26, '0', -1, NOW()),
  (93000000000001108, '000000', 930000000000000004, 'TOOL', 'render_html_page', NULL,
   NULL, 1, 27, '0', -1, NOW()),
  -- ipd-lifecycle（id=…005，既有 sort 至 28）
  (93000000000001109, '000000', 930000000000000005, 'SKILL', 'answer-me-with-html-ipd', '1.0.0',
   '0296e9c79afd781314ac460f73687bfa62abcad4dfd0a89a00a41c24d72359da', 1, 29, '0', -1, NOW()),
  (93000000000001110, '000000', 930000000000000005, 'TOOL', 'render_html_page', NULL,
   NULL, 1, 30, '0', -1, NOW()),
  -- ipd-dev（id=…006，既有 sort 至 21）
  (93000000000001111, '000000', 930000000000000006, 'SKILL', 'answer-me-with-html-ipd', '1.0.0',
   '0296e9c79afd781314ac460f73687bfa62abcad4dfd0a89a00a41c24d72359da', 1, 22, '0', -1, NOW()),
  (93000000000001112, '000000', 930000000000000006, 'TOOL', 'render_html_page', NULL,
   NULL, 1, 23, '0', -1, NOW()),
  -- ipd-valid（id=…007，既有 sort 至 26）
  (93000000000001113, '000000', 930000000000000007, 'SKILL', 'answer-me-with-html-ipd', '1.0.0',
   '0296e9c79afd781314ac460f73687bfa62abcad4dfd0a89a00a41c24d72359da', 1, 27, '0', -1, NOW()),
  (93000000000001114, '000000', 930000000000000007, 'TOOL', 'render_html_page', NULL,
   NULL, 1, 28, '0', -1, NOW());

COMMIT;

-- 执行后只读核验（应各为 7 行）：
-- SELECT COUNT(*) skill_rows FROM ipd_capability_pack_item WHERE item_type='SKILL' AND item_ref='answer-me-with-html-ipd' AND del_flag='0';
-- SELECT COUNT(*) tool_rows FROM ipd_capability_pack_item WHERE item_type='TOOL' AND item_ref='render_html_page' AND del_flag='0';
-- 回滚（仅本种子写入的行）：
-- DELETE FROM ipd_capability_pack_item WHERE id BETWEEN 93000000000001101 AND 93000000000001114;
