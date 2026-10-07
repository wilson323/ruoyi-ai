-- =====================================================================
-- ③刀清污：清除指向已退役动作的技能映射孤儿行（2026-10-07）
--
-- 【为什么需要这条】
-- LC01「上市后销售与回款跟踪」与 LC03「上市后6个月终算：回款达成率 + 奖金池核算」
-- 已于 **2026-10-03** 退役（owner 决定移除「回款台账」「奖金池」两个功能块，
-- 对应 ReceiptLedgerService / ReceiptLedgerController / BonusPoolService /
-- Lc03SettlementReconcile* 已删除），两者**已不在** ActionCatalog 中。
--
-- 但 `ipd_action_skill_map` 里仍有 id=57(LC01) / id=61(LC03) 两行：
-- 指向已退役动作的**孤儿引用**。后果：
--   ① 「已退役动作仍有技能绑定」这一状态持续为真，掩盖退役是否彻底；
--   ② 该表行数与现态动作数对不上（表 69 行 vs 动作 67 个：深管 40 / 轻管 27）。
--
-- 【为什么应用账号做不了】
-- `ipd_app` 对本表**无 DELETE 权限**（实测：ERROR 1142 command denied）。
-- 这与本表的设计一致——`IpdActionSkillMapService` 注释明写「元数据只读、无 Java 写入者；
-- 绑定写入 = owner 审核后的 seed SQL」。故本表变更一律走 SQL 迁移，由具备权限者执行。
--
-- 【幂等】先判存在性再删，重复执行无副作用。
-- 【备份】执行前已导出这两行到 /tmp/skillmap-retired-backup.sql（2026-10-07）。
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

-- ---- 执行前自检：看清要删的是哪两行 ----
SELECT id, action_code, sub_stage_code, skill_names, remark
FROM ipd_action_skill_map
WHERE action_code IN ('LC01', 'LC03');

-- ---- 删除（仅这两个明确 id，且动作码双重限定，防误删同号他行）----
DELETE FROM ipd_action_skill_map
WHERE id IN (57, 61)
  AND action_code IN ('LC01', 'LC03');

-- ---- 回读验证 ----
-- 期望值均为 **2026-10-07 实测**（非文档标称）：
--   删除前：total_rows=69、retired_leftover=2  →  删除后：total_rows=67、retired_leftover=0
SELECT COUNT(*) AS total_rows,
       SUM(action_code IN ('LC01', 'LC03')) AS retired_leftover
FROM ipd_action_skill_map;

-- ---- 交叉验证：退役动作在业务侧确已不存在 ----
-- 实测：stage_actions 共 84 行 / **69 个不同 action_code**（该表按项目展开，一动作多项目即多行，
-- 故不能拿行数当动作数——初稿此处曾按文档写成 67，实跑是 84，已更正为去重口径）。
-- retired_in_catalog 期望 0：退役动作不应再出现在任何项目的阶段动作里。
SELECT COUNT(DISTINCT action_code) AS distinct_actions,
       SUM(action_code IN ('LC01', 'LC03')) AS retired_in_catalog
FROM stage_actions;

-- =====================================================================
-- 配套变更：种子脚本已同步删除这两行
--   docs/script/sql/update/2026-09-28-ipd-action-skill-map-seed.sql
--   69 行 → 67 行，并写明删除原因；否则日后重跑该种子会把孤儿行写回来。
-- =====================================================================
