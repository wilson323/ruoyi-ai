-- =============================================================================
-- R219-U1 owner 待授权三项落地（收尾波）
-- =============================================================================
-- 卡号: 2bef6e0e（①奖金池版本链 DDL）+ a995a9e3（②GATE 存量归一+双 seed 清理）
-- 时间: 2026-09-26 真库 ipd_dev @ 127.0.0.1:13306
-- 授权: owner 2026-09-26 会话拍板「继续」（逐项列示后放行）
-- 前置: 本脚本执行前已建备份表（见 §0），可整段回滚（见文末）
--
-- ① bonus_pools 版本链（lane4 D-2 根因：uk_bp_project(project_id) 单列唯一键
--    令 freeze 后重算 insert 必撞 500；版本链需 (project_id, version) 唯一）：
--    ADD COLUMN version int NOT NULL DEFAULT 1 + uk 改 (project_id, version)
--    现有 174 行 = 174 项目各一池（零重复组），DEFAULT 1 无冲突；
--    BonusPool 实体未映射 version（插入走 DEFAULT 1，行为零变化）
--
-- ② gate_review_elements（卡③遗留）：
--    2a. is_veto / veto_dual_required 存量 'Y'/'N' → '1'/'0' 归一
--        （读侧 isVetoSet 已兼容，本步消除脏域；实测 is_veto Y14+N19=33、
--         veto_dual_required N33，全部位于 zero-pad 新代 seed 行）
--    2b. 旧代 33 行人工 seed（G1-1..7 / G2-1..6 / G3-1..5 / G4-1..8 / G5-1..7，
--        2026-09-05-ipd-p0-seed-elements.sql）软删 del_flag='1'
--        —— 与新代 IpdGateElementSeedInitializer（G1-01.. 等 zero-pad 编码）双套并存，
--        令五 Gate published+enabled 68 项 vs 期望 33 项
--    软删而非物理 DELETE 的理由：33 行被 142 条 gate_element_results 历史评审引用，
--    两代要素语义不同（名称/标准不同）不可迁移改挂；results.element_snapshot 自带
--    快照双保险；MyBatis-Plus 逻辑删除后查询侧即不可见（等效清除）。
--    del_flag=2 验收残留行（QA03-*/D7155/DEF*/Q17* 等）不在本脚本范围（已软删，R214 留库）。
-- =============================================================================

-- §0 备份（幂等：IF NOT EXISTS）
CREATE TABLE IF NOT EXISTS bonus_pools_backup_20260926 AS SELECT * FROM bonus_pools;
CREATE TABLE IF NOT EXISTS gate_review_elements_backup_20260926 AS SELECT * FROM gate_review_elements;

-- §1 PRE-CHECK
SELECT 'PRE-CHECK: bonus_pools 重复组（应为 0）' AS phase;
SELECT project_id, COUNT(*) FROM bonus_pools GROUP BY project_id HAVING COUNT(*) > 1;
SELECT 'PRE-CHECK: is_veto/veto_dual 脏域行数（预期 33/33）' AS phase;
SELECT
  SUM(is_veto IN ('Y','N'))            AS is_veto_dirty,
  SUM(veto_dual_required IN ('Y','N')) AS veto_dual_dirty
FROM gate_review_elements;
SELECT 'PRE-CHECK: 旧代 33 行 seed 及引用数（预期 refs 合计 142）' AS phase;
SELECT COUNT(*) AS legacy_seed_rows,
       (SELECT COUNT(*) FROM gate_element_results ger
          JOIN gate_review_elements ge ON ger.element_id = ge.id
         WHERE ge.element_code REGEXP '^G[1-5]-[1-9]$' AND ge.del_flag = '0') AS result_refs
FROM gate_review_elements
WHERE element_code REGEXP '^G[1-5]-[1-9]$' AND del_flag = '0';

-- §2 ① bonus_pools 版本链 DDL（information_schema 幂等 guard）
SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = 'ipd_dev' AND table_name = 'bonus_pools' AND column_name = 'version');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE bonus_pools ADD COLUMN version int NOT NULL DEFAULT 1 COMMENT ''版本链（重算递增，1=首版）'' AFTER status',
  'SELECT ''SKIP: version 列已存在'' AS notice');
PREPARE s1 FROM @ddl; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @uk_cols := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = 'ipd_dev' AND table_name = 'bonus_pools' AND index_name = 'uk_bp_project'
    AND column_name = 'version');
SET @ddl2 := IF(@uk_cols = 0,
  'ALTER TABLE bonus_pools DROP INDEX uk_bp_project, ADD UNIQUE KEY uk_bp_project (project_id, version)',
  'SELECT ''SKIP: uk_bp_project 已含 version'' AS notice');
PREPARE s2 FROM @ddl2; EXECUTE s2; DEALLOCATE PREPARE s2;

-- §3 ②a is_veto / veto_dual_required 归一（幂等：域值 WHERE）
UPDATE gate_review_elements SET is_veto = '1' WHERE is_veto = 'Y';
UPDATE gate_review_elements SET is_veto = '0' WHERE is_veto = 'N';
UPDATE gate_review_elements SET veto_dual_required = '1' WHERE veto_dual_required = 'Y';
UPDATE gate_review_elements SET veto_dual_required = '0' WHERE veto_dual_required = 'N';

-- §4 ②b 旧代 33 行 seed 软删（幂等：del_flag='0' guard；remark 留痕）
UPDATE gate_review_elements
   SET del_flag = '1',
       remark = CONCAT(COALESCE(remark, ''), ' R219U1软删:旧代seed与新代Initializer双套并存,owner 2026-09-26授权清理')
 WHERE element_code REGEXP '^G[1-5]-[1-9]$' AND del_flag = '0';

-- §5 POST-VERIFY
SELECT 'POST-VERIFY 1: bonus_pools DDL（version 列 + uk 双列）' AS phase;
SHOW CREATE TABLE bonus_pools;
SELECT 'POST-VERIFY 2: 脏域清零（预期 0/0）' AS phase;
SELECT SUM(is_veto IN ('Y','N')) AS is_veto_dirty, SUM(veto_dual_required IN ('Y','N')) AS veto_dual_dirty
FROM gate_review_elements;
SELECT 'POST-VERIFY 3: 业务可见要素数（published+enabled+del_flag=0，预期 33）' AS phase;
SELECT gate_code, COUNT(*) FROM gate_review_elements
 WHERE status = 'published' AND enabled = '1' AND del_flag = '0' GROUP BY gate_code;

-- §6 回滚（如需，按备份表逐行恢复）
-- UPDATE bonus_pools t JOIN bonus_pools_backup_20260926 b ON t.id = b.id SET t.del_flag = b.del_flag;  -- 列回滚:
-- ALTER TABLE bonus_pools DROP INDEX uk_bp_project, ADD UNIQUE KEY uk_bp_project (project_id);
-- ALTER TABLE bonus_pools DROP COLUMN version;
-- UPDATE gate_review_elements t JOIN gate_review_elements_backup_20260926 b ON t.id = b.id
--    SET t.is_veto = b.is_veto, t.veto_dual_required = b.veto_dual_required,
--        t.del_flag = b.del_flag, t.remark = b.remark;
