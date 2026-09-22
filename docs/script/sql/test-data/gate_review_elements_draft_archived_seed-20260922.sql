-- =============================================================================
-- R177-A4 造数据脚本（gate_review_elements DRAFT + ARCHIVED 真活）
-- 日期：2026-09-22
-- 作者：Claude Code (r177-a4-data worktree)
-- 说明：让 button-policy 4 状态 × 9 按钮 = 36 决策点矩阵全状态实测覆盖
--       （DRAFT / PUBLISHED-on / PUBLISHED-off / ARCHIVED）
-- 约束：需主会话 owner 显式授权才能 apply
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 真实表结构（来自 DESCRIBE gate_review_elements）：
--   id, gate_code(varchar(8) NOT NULL),
--   element_code(varchar(16) NOT NULL UNIQUE),
--   element_name(varchar(128) NOT NULL),
--   pass_standard(text NULL), threshold_json(varchar(512) NULL),
--   sign_due_at(datetime NULL), sign_extension_count(int NULL),
--   is_veto(char(1) '0'), veto_dual_required(char(1) '0'),
--   sort_order(int '0'), enabled(char(1) '1'),
--   status(varchar(16) 'published'), version(int '1'),
--   create_dept(bigint NULL), create_by(bigint NULL),
--   create_time(datetime CURRENT_TIMESTAMP),
--   update_by(bigint NULL), update_time(datetime CURRENT_TIMESTAMP ON UPDATE),
--   tenant_id(varchar(20) '000000'),
--   del_flag(char(1) '0'), remark(varchar(500) NULL)
--
-- 基线（apply 前确认）：draft/0=1, draft/1=0, published/0=10, published/1=33+33
-- 目标（apply 后）：   draft/0=3, draft/1=2, archived/0=3, 其余不变
-- -----------------------------------------------------------------------------

-- ===== DRAFT 5 条 =====
INSERT INTO gate_review_elements
  (gate_code, element_code, element_name, status, enabled, veto_dual_required, sort_order, version, tenant_id, del_flag, remark, create_time, update_time)
VALUES
  ('G2', 'G2-DRAFT-01', 'DRAFT 测试 1（disabled）', 'draft',    '0', '0', 90, 1, '000000', '0', '造数据 DRAFT-1 disabled', NOW(), NOW()),
  ('G2', 'G2-DRAFT-02', 'DRAFT 测试 2（enabled）',  'draft',    '1', '0', 91, 1, '000000', '0', '造数据 DRAFT-2 enabled',  NOW(), NOW()),
  ('G2', 'G2-DRAFT-03', 'DRAFT 测试 3（disabled+veto）', 'draft', '0', '1', 92, 1, '000000', '0', '造数据 DRAFT-3 disabled+veto', NOW(), NOW()),
  ('G2', 'G2-DRAFT-04', 'DRAFT 测试 4（enabled+veto）',  'draft', '1', '1', 93, 1, '000000', '0', '造数据 DRAFT-4 enabled+veto',  NOW(), NOW()),
  ('G2', 'G2-DRAFT-05', 'DRAFT 测试 5（disabled）', 'draft',    '0', '0', 94, 1, '000000', '0', '造数据 DRAFT-5 disabled', NOW(), NOW());

-- ===== ARCHIVED 3 条 =====
INSERT INTO gate_review_elements
  (gate_code, element_code, element_name, status, enabled, veto_dual_required, sort_order, version, tenant_id, del_flag, remark, create_time, update_time)
VALUES
  ('G2', 'G2-ARCH-01', 'ARCHIVED 测试 1', 'archived', '0', '0', 95, 1, '000000', '0', '造数据 ARCH-1', NOW(), NOW()),
  ('G2', 'G2-ARCH-02', 'ARCHIVED 测试 2', 'archived', '0', '0', 96, 1, '000000', '0', '造数据 ARCH-2', NOW(), NOW()),
  ('G2', 'G2-ARCH-03', 'ARCHIVED 测试 3（veto）', 'archived', '0', '1', 97, 1, '000000', '0', '造数据 ARCH-3 veto', NOW(), NOW());

-- =============================================================================
-- 预期分布校验（apply 后跑）：
--   SELECT status, enabled, COUNT(*) FROM gate_review_elements GROUP BY status, enabled;
--
-- 期望：
--   draft/0=3, draft/1=2,
--   archived/0=3,
--   published/0=10, published/1=66, PUBLISHED/Y=33（老格式 A3 待迁移）
-- =============================================================================
