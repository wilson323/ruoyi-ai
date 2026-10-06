-- =====================================================================
-- 回滚：ipd_action_skill_map 项目维度绑定（对应 2026-10-06-ipd-action-skill-map-project-dimension.sql）
-- 前提：先人工确认并清掉 project_id>0 的项目级绑定行（否则 uk_map_action_code 会撞唯一键）：
--   DELETE FROM ipd_action_skill_map WHERE project_id > 0;
-- =====================================================================
ALTER TABLE ipd_action_skill_map
    DROP INDEX uk_map_action_project,
    DROP INDEX idx_map_project,
    ADD UNIQUE KEY uk_map_action_code (action_code),
    DROP COLUMN project_id;
