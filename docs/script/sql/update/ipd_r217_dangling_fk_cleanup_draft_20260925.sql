-- =====================================================================
-- [数据治理] R217 悬空外键僵尸行清洗方案（草稿）
-- 状态: ⚠️ 待 owner 拍板不 apply ⚠️
-- 日期: 2026-09-25
-- 调查人: IPD 工具/数据治理专员
-- 关联卡: 8a088a71 (R215-DATA) / 5d5c4fcc (DATA-CLEAN-9140004)
-- 调查报告: docs/ipd-系统说明/验收/R217-工具与数据调查-20260925/dangling-fk-survey.md
--
-- 硬约束:
--   - 本文件为草稿，绝对不要直接执行
--   - 所有 UPDATE 均为软删 (del_flag='1')，不做物理 DELETE
--   - 每段附带 SELECT 定位 + 回滚语句 + 影响行数预估
--   - 执行前必须人工核对 SELECT 输出与预期一致
-- =====================================================================

-- #####################################################################
-- 第一类: deletion_requests 僵尸行（entity_id 指向物理不存在的实体）
-- 影响行数: 5
-- 成因: SUPER_ADMIN 测试探针，entity_id=1/2/3/999 从未实际存在
-- 建议处置: 软删 (del_flag='1') + remark 标注
-- #####################################################################

-- >>> 定位 SELECT（执行前核对，预期 5 行）
SELECT dr.id, dr.entity_type, dr.entity_id, dr.status, dr.create_time, dr.requester_id, dr.reason
FROM deletion_requests dr
WHERE dr.del_flag = '0'
  AND (
    (dr.entity_type = 'projects' AND NOT EXISTS (SELECT 1 FROM projects p WHERE p.id = dr.entity_id))
    OR (dr.entity_type = 'products' AND NOT EXISTS (SELECT 1 FROM products p WHERE p.id = dr.entity_id))
    OR (dr.entity_type = 'cert_templates' AND NOT EXISTS (SELECT 1 FROM cert_templates c WHERE c.id = dr.entity_id))
    OR (dr.entity_type = 'persons' AND NOT EXISTS (SELECT 1 FROM persons pe WHERE pe.id = dr.entity_id))
    OR (dr.entity_type = 'gates' AND NOT EXISTS (SELECT 1 FROM gates g WHERE g.id = dr.entity_id))
  )
ORDER BY dr.create_time;

-- >>> 清洗 UPDATE（待 owner 拍板）
-- UPDATE deletion_requests
-- SET del_flag = '1',
--     remark = CONCAT('R217 悬空外键清理 20260925: entity_id 物理不存在(SUPER_ADMIN 测试探针)。原 remark:',
--                     CASE WHEN remark IS NULL OR remark = '' THEN '(空)' ELSE remark END),
--     update_by = -1,
--     update_time = NOW()
-- WHERE del_flag = '0'
--   AND id IN (
--     2096381708617248770,  -- products/2 LEADER_REVIEW
--     2096373346072539138,  -- products/999 REJECTED
--     2096381707870662657,  -- products/1 WITHDRAWN
--     2096381708998930433,  -- products/3 ADMIN_REVIEW
--     2101930187108151297   -- projects/1 LEADER_REVIEW (R152-B4 acceptance)
--   );
-- 预期影响: 5 rows

-- >>> 回滚段
-- UPDATE deletion_requests
-- SET del_flag = '0',
--     remark = NULL,
--     update_by = NULL,
--     update_time = update_time  -- 保持原值
-- WHERE id IN (
--     2096381708617248770,
--     2096373346072539138,
--     2096381707870662657,
--     2096381708998930433,
--     2101930187108151297
-- );
-- 注: 回滚会丢失原 remark（原值均为 NULL，无损失）

-- #####################################################################
-- 第二类: bonus_pools 真悬空（project_id=9140004 物理不存在）
-- 影响行数: 1
-- 成因: 项目被 R35 软删后被物理 purge，pool 未级联处理
-- 特殊注意: 该 pool 状态为 DISTRIBUTED（已产生财务分配事实）
-- 建议处置: 软删 + remark 标注（保留 distributions JSON 作审计追溯）
--           或 owner 决定保留作回归样本（则本段跳过）
-- #####################################################################

-- >>> 定位 SELECT（预期 1 行）
SELECT bp.id, bp.project_id, bp.status, bp.del_flag, bp.final_pool, bp.distributions,
       bp.create_time, bp.update_time
FROM bonus_pools bp
WHERE bp.del_flag = '0'
  AND NOT EXISTS (SELECT 1 FROM projects p WHERE p.id = bp.project_id);

-- >>> 清洗 UPDATE（待 owner 拍板）
-- UPDATE bonus_pools
-- SET del_flag = '1',
--     remark = CONCAT('R217 悬空外键清理 20260925: project_id=9140004 已物理不存在',
--                     '(R35 软删后被 purge)。状态 DISTRIBUTED，分配事实保留于 distributions JSON。',
--                     '原 remark:', CASE WHEN remark IS NULL OR remark = '' THEN '(空)' ELSE remark END),
--     update_by = -1,
--     update_time = NOW()
-- WHERE del_flag = '0'
--   AND id = 2097169984949182465;
-- 预期影响: 1 row

-- >>> 回滚段
-- UPDATE bonus_pools
-- SET del_flag = '0',
--     remark = NULL,
--     update_by = NULL
-- WHERE id = 2097169984949182465;

-- #####################################################################
-- 第三类: bonus_pools 逻辑孤儿（project 已软删 del_flag='1'）
-- 影响行数: 1
-- 成因: R35 批量软删项目时 ProjectSoftDeleteExecutor 不级联 bonus_pools
-- 建议处置: 软删 (del_flag='1') + remark 标注
-- #####################################################################

-- >>> 定位 SELECT（预期 1 行）
SELECT bp.id, bp.project_id, bp.status, bp.del_flag AS pool_del_flag,
       p.del_flag AS proj_del_flag, p.status AS proj_status, p.update_time AS proj_update_time
FROM bonus_pools bp
JOIN projects p ON p.id = bp.project_id
WHERE bp.del_flag = '0'
  AND p.del_flag != '0';

-- >>> 清洗 UPDATE（待 owner 拍板）
-- UPDATE bonus_pools
-- SET del_flag = '1',
--     remark = CONCAT('R217 悬空外键清理 20260925: 关联项目已软删(ARCHIVED)，',
--                     'pool 状态 DRAFT 无财务事实，级联软删。',
--                     '原 remark:', CASE WHEN remark IS NULL OR remark = '' THEN '(空)' ELSE remark END),
--     update_by = -1,
--     update_time = NOW()
-- WHERE del_flag = '0'
--   AND id = 2098460715223445505;
-- 预期影响: 1 row

-- >>> 回滚段
-- UPDATE bonus_pools
-- SET del_flag = '0',
--     remark = NULL,
--     update_by = NULL
-- WHERE id = 2098460715223445505;

-- #####################################################################
-- 第四类（附带发现）: 幻影 DELETED — deletion_request 标记 DELETED 但实体仍 live
-- 影响行数: 1
-- 成因: R215-P1 验收时 @TableLogic bug 导致 product 软删静默失败（R216 已修复）
-- 建议处置: 二选一（owner 定）
--   方案 A: 补执行 product 软删 (del_flag='1') — 让实体状态与申请一致
--   方案 B: 回退 dr.status 为 REJECTED + remark 标注 — 让申请状态与实体一致
-- #####################################################################

-- >>> 定位 SELECT（预期 1 行）
SELECT dr.id AS dr_id, dr.entity_id, dr.status AS dr_status, dr.executed_at,
       p.id AS prod_id, p.product_name, p.del_flag AS prod_del_flag, p.status AS prod_status
FROM deletion_requests dr
JOIN products p ON p.id = dr.entity_id
WHERE dr.entity_type = 'products'
  AND dr.status = 'DELETED'
  AND dr.del_flag = '0'
  AND p.del_flag = '0';

-- >>> 方案 A: 补 product 软删（待 owner 拍板）
-- UPDATE products
-- SET del_flag = '1',
--     status = 'RETIRED',
--     retired_at = NOW(),
--     remark = CONCAT('R217 补执行: deletion_request ', '2103430048831811585',
--                     ' 标记 DELETED 但 R215 @TableLogic bug 致软删未落地。',
--                     '原 remark:', CASE WHEN remark IS NULL OR remark = '' THEN '(空)' ELSE remark END),
--     update_by = -1,
--     update_time = NOW()
-- WHERE id = 2103429777619726337
--   AND del_flag = '0';
-- 预期影响: 1 row

-- >>> 方案 A 回滚
-- UPDATE products
-- SET del_flag = '0',
--     status = 'ON_SALE',
--     retired_at = NULL,
--     remark = NULL,
--     update_by = NULL
-- WHERE id = 2103429777619726337;

-- >>> 方案 B: 回退 dr.status（待 owner 拍板，与方案 A 互斥）
-- UPDATE deletion_requests
-- SET status = 'REJECTED',
--     executed_at = NULL,
--     remark = CONCAT('R217 修正: 原 DELETED 为 @TableLogic bug 幻影执行，实体未实际软删。',
--                     '回退为 REJECTED。原 remark:',
--                     CASE WHEN remark IS NULL OR remark = '' THEN '(空)' ELSE remark END),
--     update_by = -1,
--     update_time = NOW()
-- WHERE id = 2103430048831811585
--   AND status = 'DELETED';
-- 预期影响: 1 row

-- >>> 方案 B 回滚
-- UPDATE deletion_requests
-- SET status = 'DELETED',
--     executed_at = '2026-09-25 18:23:07',
--     remark = NULL,
--     update_by = NULL
-- WHERE id = 2103430048831811585;

-- =====================================================================
-- 影响行数汇总
-- =====================================================================
-- | 类别                          | 行数 | 处置       |
-- |-------------------------------|------|------------|
-- | deletion_requests 僵尸行      |   5  | 软删       |
-- | bonus_pools 真悬空 (9140004)  |   1  | 软删       |
-- | bonus_pools 逻辑孤儿          |   1  | 软删       |
-- | 幻影 DELETED (方案 A 或 B)    |   1  | 二选一     |
-- | 合计最大影响                  |   8  |            |
-- =====================================================================
-- ⚠️ 再次强调: 本文件为草稿，所有 UPDATE 已注释。
--    执行前须: 1) owner 拍板  2) 逐段跑 SELECT 核对  3) 备份  4) 单段执行+回读
-- =====================================================================
