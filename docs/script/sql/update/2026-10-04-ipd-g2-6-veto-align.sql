-- =====================================================================
-- 文件：2026-10-04-ipd-g2-6-veto-align.sql
-- 日期：2026-10-04
-- 状态：待 owner / DBA apply。文首「预检」段只读，可直接跑；UPDATE 段须 owner 授权。
-- =====================================================================
--
-- 【本脚本解决什么】
--   gate_review_elements 存在两份互相矛盾的种子，G2-6 的否决位不同：
--     · SQL  seed  docs/script/sql/update/2026-09-05-ipd-p0-seed-elements.sql
--         G2-6 is_veto='1'（否决），通过标准写「缺失或周期冲突=否决」→ 全表 15 否决位
--     · Java seed  org.ruoyi.ipd.config.IpdGateElementSeedInitializer（DOC-05 口径）
--         G2-6 is_veto='0'（非否决）→ 全表 14 否决位
--   实测差异全貌（2026-10-04，两份种子逐项对拍）：
--     · pass_standard  33/33 全不同
--     · element_name    2/33 不同（G2-1「PRD 完整性」vs「PRD完整性」、G4-8 同理带空格差异）
--     · is_veto         1/33 不同（只有 G2-6）
--   即：两份种子在「行为」上唯一的差别就是 G2-6；其余都是文本。本脚本只处理这一处行为差异。
--
-- 【裁决依据 · 四处独立同向，均已复核】
--   ① docs/ipd-系统说明/工程合同/DOC-05.md
--        G2-6 行 isVeto=否，来源列直接写「要素原稿:103被决策1覆盖」——
--        DOC-05 自述覆盖规格原稿的冲突部分，并给出确定否决集合 14 项。
--   ② 建表 DDL  docs/script/sql/update/2026-09-04-ipd-p0-tables.sql
--        is_veto 列注释「是否否决项（14 项，命中无法提交通过）」；
--        表注释「IPD Gate 评审要素定义（33 项+14 否决项）」。
--   ③ docs/ipd-系统说明/外部资源/IPD系统_六阶段标准动作清单_v3.md 文首
--        「每个 Gate 具体评审什么（33 项要素 / 14 项否决项）」。
--   ④ 验收基线 docs/ipd-系统说明/外部资源/IPD系统_验收清单.md
--        AC-GLB-12「33 项要素全部可判定，**14 项否决项**全部生效」。
--        注：2026-09-06 曾提出把该基线「复核为 15」，但承接复核的 P1-6.2 卡
--        （开发计划-看板镜像.md）在 2026-09-08 回写时仍按 **14** 记录，
--        并保留「G2 规划放行不豁免 G4 实际结果」——即「改 15」的建议未被采纳。
--   结论：以 Java（DOC-05）为准，SQL seed 的 G2-6 否决位属过期口径，对齐为「非否决」。
--
--   ⚠️ 跑错的方向是「错误否决」，不是「放行」：
--      GateElementResultService.submit 命中否决位会抛「命中否决项无法提交通过」，
--      G2 计划评审会被错误硬卡（DOC-05 决策1 明确 G2-6 清单未完成/周期冲突仍可通过 G2）。
--
-- 【为什么连带改 pass_standard，而不只改 is_veto】
--   G2-6 原文标准写的是「缺失或周期冲突=否决」。若只把 is_veto 改成 '0' 而不动文本，
--   该行会**自相矛盾**：展示给评审人的通过标准说「=否决」，系统却不再否决。
--   故本脚本把 is_veto 与 pass_standard 一并对齐到 DOC-05。范围仍只有 G2-6 这一行
--   （跨 tenant，见下方「影响面」）。
--   注意：决定「评审判定」的是 is_veto，不是文本。本仓实测（排归档）：
--   `GateElementResultService` 走 `GateElementService.isVetoSet(getIsVeto())` 判否决；
--   `pass_standard` 出现在另外三类分支里，都**不影响某次评审的通过/不通过**：
--     · 种子一致性比对（IpdGateElementSeedInitializer 预检）；
--     · 定义编辑校验（已发布改定义判 409、长度上限 STANDARD_MAX）；
--     · 展示与提示词组装（GateElementResultService 展示行、GatePrepExecutor）。
--   改文本是为了消除「显示与行为不一致」，不改变任何评审判定逻辑。
--
-- 【为什么不改写原 SQL seed】
--   按本仓纪律，**已应用的迁移不改写正文**——改写会造成「新装库没有、老库还有」的分叉
--   （LC01/LC03 退役用的是同一处理方式，见 2026-10-03-ipd-retire-lc01-lc03-draft.sql）。
--   历史遗留行只能靠本**新增**清理脚本修正。
--
-- 【库侧真实历史 · 实测（不是推测）】
--   本机 ipd_dev（127.0.0.1:13306，MySQL 8.0.46）2026-10-04 实测：
--   · gate_review_elements 共 97 行；33 项规范编号行的 id 恰为 **1948090500–1948090532**，
--     即本文件上方那份 SQL seed 自己的 id 段 ⇒ **那份 SQL seed 确实 apply 过这个库**。
--   · 这 33 行的 update_time 全部是 **2026-10-03 20:00**（一次性批量改写），
--     现值已是 DOC-05 口径：G2-6 is_veto='0'、14 否决位、通过标准为 DOC-05 文本。
--   · 因此 `pass_standard LIKE '✅%' OR LIKE '%=否决%'`（SQL seed 文本特征）当前命中 **0 行**。
--   ⇒ 本脚本在该库 **0 行受影响**（正好可作「幂等 + 不误伤」的现成验证）。
--   ⚠️ 但那次 2026-10-03 的批量改写在仓库里**没有对应的可重放迁移**（已实测：全仓除归档外
--      没有任何已提交脚本把这些行的 pass_standard 改成 DOC-05 文本）。
--      ⇒ 新库若照迁移顺序 apply 那份 SQL seed，会**重新**得到 G2-6='1'、15 否决位、
--        以及「=否决」文本。这正是本脚本存在的理由：给这个修正留一条可重放的路径。
--
-- 【⚠️ 方向对立的脚本：apply 顺序会决定结果】
--   docs/script/sql/update/2026-09-06-ipd-p161-gate-element-lifecycle.sql 第 3 段写的是**反方向**：
--       UPDATE gate_review_elements SET is_veto = '1' WHERE element_code = 'G2-6' AND is_veto = '0';
--   它的注释自述「G2-6 语义按 DOC-05（…五大Gate评审要素_v1.md L103）翻正 … 翻正后全库否决位 14→15」。
--   **这个自述与 DOC-05 原文相反**：DOC-05 的 G2-6 行 isVeto=**否**，其来源列写的是
--   「要素原稿:103**被决策1覆盖**」——「103」指的是被覆盖的原稿位置，不是 DOC-05 的主张。
--   且该脚本的 WHERE 带 `AND is_veto = '0'`，看起来幂等，实际是**单向翻转**：
--   对一个已正确的库（G2-6='0'）重复 apply 就会把它改回错的 15。
--   ⇒ **apply 顺序会决定结果**：本脚本之后若再跑 P161 那一段，G2-6 会被翻回 '1'。
--   ⇒ 已登记为需 owner 处置的相邻项（处理 P161 那一段需要单独授权，本卡未动它）。
--   方向裁定见 §「裁决依据」：DOC-05 明文 14 项 + 建表注释 14 + 动作清单 v3 文首 14 +
--   AC-GLB-12 基线「14 项否决项全部生效」——四处同向。
--
-- 【影响面】
--   两条 UPDATE 都以 element_code='G2-6' 起手，**不做 tenant 过滤**——若一个库里多个
--   tenant 各有自己的 G2-6 行，会被一并处理（这是有意的：DOC-05 对所有 tenant 同一口径）。
--   执行前请先跑文首「预检」，逐行看清将要命中的 tenant / status / is_veto。
--
-- 【幂等】
--   · is_veto 段：WHERE 带 `is_veto = '1'`，第二次起 0 行。
--   · 文本段：WHERE 带完整原文比对，改完文本不再等于原文，第二次起 0 行。
--   两段都可安全重跑。
--
-- 【回滚】
--   改回原值即可，见文末「回滚」段（原值 is_veto='1' + 原通过标准文本）。
--   UPDATE 本身不可撤销，若要「执行前整表可回滚」，apply 前先做快照：
--     CREATE TABLE gate_review_elements_bak_YYYYMMDD AS SELECT * FROM gate_review_elements;
--   （注意：表内已有的 gate_review_elements_backup_20260922 是某轮的局部备份，不覆盖全表。）
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

-- ---------------------------------------------------------------------
-- 预检（只读，无副作用）。期望：若本库 apply 过 2026-09-05 SQL seed，
-- 则应看到 G2-6 一行且 is_veto='1'；若看到 0 行或 is_veto 已是 '0'，
-- 说明本库已是 DOC-05 口径，本脚本无需执行（或已执行过）。
-- ---------------------------------------------------------------------
SELECT 'G2-6 现状（全 tenant、含各种 status/del_flag）' AS phase,
       id, tenant_id, status, enabled, del_flag, is_veto, pass_standard
FROM gate_review_elements
WHERE element_code = 'G2-6'
ORDER BY tenant_id, id;

-- 全库否决位计数：DOC-05 口径应为 14（若本库 apply 过 SQL seed 会是 15）。
SELECT '否决位计数' AS phase, is_veto, COUNT(*) AS cnt
FROM gate_review_elements
WHERE element_code LIKE 'G_-_' AND del_flag = '0' AND status = 'published' AND enabled = '1'
GROUP BY is_veto ORDER BY is_veto;

-- ---------------------------------------------------------------------
-- 对齐 1/2：否决位 '1' → '0'（DOC-05 决策1：G2-6 保留要素但非否决）
-- ---------------------------------------------------------------------
UPDATE gate_review_elements
SET is_veto = '0',
    update_time = NOW()
WHERE element_code = 'G2-6'
  AND is_veto = '1';

-- ---------------------------------------------------------------------
-- 对齐 2/2：通过标准文本 → DOC-05 口径
--   WHERE 用完整原文比对：只改「仍是 SQL seed 原文」的行，
--   若该行曾被人工改写过，本段不命中，绝不覆盖人工内容。
-- ---------------------------------------------------------------------
UPDATE gate_review_elements
SET pass_standard = '目标市场认证清单及周期进行中也可通过G2；保留当前缺口、责任人、计划和后续结果检查关联',
    update_time = NOW()
WHERE element_code = 'G2-6'
  AND pass_standard = '目标市场强制认证全部列入且周期匹配；缺失或周期冲突=否决';

-- ---------------------------------------------------------------------
-- 回读核验（执行后跑）：期望 G2-6 is_veto='0' 且文本为 DOC-05 口径；
-- 全库 published+enabled 的否决位应为 14（不是 15）。
-- ---------------------------------------------------------------------
SELECT 'after' AS phase, id, tenant_id, status, enabled, del_flag, is_veto, pass_standard
FROM gate_review_elements
WHERE element_code = 'G2-6'
ORDER BY tenant_id, id;

SELECT 'after 否决位计数' AS phase, is_veto, COUNT(*) AS cnt
FROM gate_review_elements
WHERE element_code LIKE 'G_-_' AND del_flag = '0' AND status = 'published' AND enabled = '1'
GROUP BY is_veto ORDER BY is_veto;

-- =====================================================================
-- 回滚（仅在确需撤销本脚本时执行；会把 G2-6 退回「否决」的过期口径）
-- =====================================================================
-- UPDATE gate_review_elements
-- SET is_veto = '1',
--     pass_standard = '目标市场强制认证全部列入且周期匹配；缺失或周期冲突=否决',
--     update_time = NOW()
-- WHERE element_code = 'G2-6';
--
-- ⚠️ 回滚会把 G2 计划评审重新变成「认证清单未完成即硬卡」，与 DOC-05 决策1 相反。
--    除非 owner 明确改回该口径，否则不要执行本段。
-- =====================================================================
