-- ============================================================================
-- IPD Gate 评审要素：取值域归一迁移（is_veto / veto_dual_required 的 Y/N → 1/0）
-- ============================================================================
-- 背景 / 根因：
--   历史版本的 IpdGateElementSeedInitializer 直接把种子数组里的 "Y"/"N" 字面量
--   写入 is_veto 列（未做归一），导致 gate_review_elements 表存在取值域污染：
--   is_veto ∈ {0,1,Y,N}、veto_dual_required ∈ {0,1,N}。
--   代码侧已于 2026-09-22 修复（initializer 改为 "Y".equals(def[4]) ? "1" : "0"），
--   新库初始化不再产生脏值；本迁移用于一次性归一【存量历史脏行】。
--
-- 实测污染量（2026-09-22，ipd_dev@13306，共 88 行）：
--   is_veto:            0=36, 1=19, N=19, Y=14   → 脏 33 行
--   veto_dual_required: 0=48, 1=7,  N=33         → 脏 33 行
--   （status / enabled 取值域已干净，无需处理）
--
-- 幂等性：所有 UPDATE 的 WHERE 只匹配 'Y'/'N'（默认 CI 排序规则同时覆盖 'y'/'n'），
--         重复执行第二次起 0 行受影响，可安全重跑。
--
-- ⚠️ 待 owner / DBA apply：本文件仅入库，不自动执行（批量数据更新属副作用操作）。
--   apply 前建议先做整表快照备份（注意：现存 gate_review_elements_backup_20260922
--   是兄弟会话 R177 计划仅含 status='PUBLISHED' 行的局部备份，不足以覆盖全表回滚）。
--
-- 回滚：UPDATE 不可逆；如需回滚须依赖 apply 前的全表快照。建议 apply 前执行：
--   CREATE TABLE gate_review_elements_bak_YYYYMMDD AS SELECT * FROM gate_review_elements;
-- ============================================================================

-- ---- 执行前核查（应见 Y/N 计数 > 0）----
SELECT 'before' AS phase,
       SUM(is_veto = 'Y' OR is_veto = 'y')            AS is_veto_Y,
       SUM(is_veto = 'N' OR is_veto = 'n')            AS is_veto_N,
       SUM(veto_dual_required = 'Y' OR veto_dual_required = 'y') AS vdr_Y,
       SUM(veto_dual_required = 'N' OR veto_dual_required = 'n') AS vdr_N
FROM gate_review_elements;

-- ---- 归一：is_veto ----
UPDATE gate_review_elements SET is_veto = '1' WHERE is_veto IN ('Y', 'y');
UPDATE gate_review_elements SET is_veto = '0' WHERE is_veto IN ('N', 'n');

-- ---- 归一：veto_dual_required ----
UPDATE gate_review_elements SET veto_dual_required = '1' WHERE veto_dual_required IN ('Y', 'y');
UPDATE gate_review_elements SET veto_dual_required = '0' WHERE veto_dual_required IN ('N', 'n');

-- ---- 执行后核查（Y/N 计数应全为 0；取值域应仅 {0,1}）----
SELECT 'after' AS phase,
       SUM(is_veto = 'Y' OR is_veto = 'y')            AS is_veto_Y,
       SUM(is_veto = 'N' OR is_veto = 'n')            AS is_veto_N,
       SUM(veto_dual_required = 'Y' OR veto_dual_required = 'y') AS vdr_Y,
       SUM(veto_dual_required = 'N' OR veto_dual_required = 'n') AS vdr_N
FROM gate_review_elements;

SELECT is_veto, COUNT(*) AS cnt FROM gate_review_elements GROUP BY is_veto ORDER BY is_veto;
SELECT veto_dual_required, COUNT(*) AS cnt FROM gate_review_elements GROUP BY veto_dual_required ORDER BY veto_dual_required;
