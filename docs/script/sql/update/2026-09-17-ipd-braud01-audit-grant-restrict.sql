-- IPD BR-AUD-01 audit_logs GRANT 限制（DEF-5 PROPOSAL-01 脚本兜底正式化）
-- --------------------------------------------------------------------
-- 任务卡: DEF-5 卡面 task_id=081fbd58-de76-4222-a7ae-22b59faa464f
-- 治理依据:
--   - 产品圣经 §12.1 BR-AUD-01: ipd_app 对 audit_logs 仅 INSERT 权限
--   - DEF-5-20260907-差集审计与关键表库级REVOKE决策报告.md
--   - PROPOSAL-01-脚本兜底.md §2.1 (owner 2026-09-07 拍板首选 ①)
--   - commit e9e6631d (2026-09-05) Q6 REVOKE UPDATE/DELETE 已 apply 预演
-- 真库表名: audit_logs (DBA 确认, 不是 audit_log)
-- 真库角色: ipd_app@127.0.0.1 / ipd_dev 库 (单企业私有部署, G-07)
--
-- 设计要点:
--   1. 幂等: 多次执行结果一致（库级 INSERT/UPDATE/DELETE 收回是单向收紧）
--   2. 自校验: §4 跑 information_schema 残留查询, 期望 0 行
--   3. 回滚预案: §6 GRANT INSERT, UPDATE, DELETE ... ipd_app 一键复原
--   4. rebuild chain 临时 GRANT: §5 (超管运维期专用, 平时 REVOKE 生效)
--
-- 上线状态 (2026-09-26 owner 拍板后已 apply):
--   - DCL 已正式登记 docs/script/sql/update/ 体系
--   - 静态门禁 scripts/ci/check-braud01-audit-grant.sh 已部署
--   - 真库已 apply (R227-C11, owner 2026-09-26 授权直接应用于 ipd_dev, root@socket)
--   - 与 e9e6631d Q6 配套: Q6 已 REVOKE UPDATE/DELETE (库级 + 表级), 本文件补 INSERT 库级兜底收回
--   - apply 时发现前提偏差: ipd_app 表级 INSERT 原不存在(应用此前靠库级兜底写审计),
--     已按 §6 预案补 GRANT INSERT ×2; 但登录链随后 500 暴露第二缺口: append 三步是
--     selectForUpdate(FOR UPDATE 需 SELECT+DML)→advance **UPDATE 锚行**→insert,
--     chain_heads 的表级 UPDATE 是业务必需而本文件从未建过 → 已补
--     GRANT UPDATE ON audit_log_chain_heads(无 DELETE, G-02 不破);
--     终态: audit_logs=S+I / chain_heads=S+I+U / 两表无 DELETE / 库级兕底=空
--   - 教训: 收权前必须对照业务代码逐语句核权限需求(AuditLogServiceImpl.append:107/122),
--     文档 SQL 的权限模型 ≠ 代码真实需求; 21131def 21 张表级 GRANT 清单未含审计两表
-- --------------------------------------------------------------------

-- ============================================================================
-- 0. 角色前提（真库现状）
-- ============================================================================
-- 应用账号: ipd_app@127.0.0.1 (库 ipd_dev)
-- 超管账号: DBA (root 或迁移专用账号)
-- 表级 GRANT 模型: 21 张业务表已有 SELECT/INSERT/UPDATE/DELETE (commit 21131def 系列)
-- 库级 GRANT 模型: 默认 *.* 含 SELECT/INSERT/UPDATE/DELETE ("库级兜底 DML")
--                  本文件要堵这个兜底, 仅对 audit_logs 收紧

-- ============================================================================
-- 1. 应用账号库级 INSERT 兜底收回 (仅对核心审计表)
-- ============================================================================

-- 1.1 收回 audit_logs 库级 INSERT (应用业务已走表级 GRANT, 仍可写)
--     库级 INSERT 收回后, audit_logs 仅接受来自显式表级 GRANT 的写入
--     攻击者即使有 ipd_app 凭证也无法通过未授权路径植入伪造审计行
REVOKE INSERT ON ipd_dev.audit_logs FROM 'ipd_app'@'127.0.0.1';

-- ============================================================================
-- 2. 应用账号库级 UPDATE/DELETE 兜底收回 (与 commit e9e6631d Q6 一致)
-- ============================================================================
-- 已 commit e9e6631d (Q6) 执行过 REVOKE UPDATE,DELETE, 本文件作为完整兜底再列一次 (幂等可重跑)

REVOKE UPDATE, DELETE ON ipd_dev.audit_logs FROM 'ipd_app'@'127.0.0.1';

-- ============================================================================
-- 3. audit_log_chain_heads (audit 链锚点表) 同步收敛
-- ============================================================================
-- 表级 GRANT 是 S/I/U (commit 21131def), 业务可任意 INSERT, 攻击者植入伪造
-- GLOBAL 锚行 → 整条审计链锚点失守
-- 收回库级 INSERT 后, chain_heads 仅接受应用代码路径显式写入 (AuditLogService.append)

REVOKE INSERT ON ipd_dev.audit_log_chain_heads FROM 'ipd_app'@'127.0.0.1';
REVOKE UPDATE, DELETE ON ipd_dev.audit_log_chain_heads FROM 'ipd_app'@'127.0.0.1';

-- ============================================================================
-- 4. 自校验 (执行 §1-3 后跑, 确认残留 0)
-- ============================================================================
-- 用 ipd_app 身份应无 INSERT/UPDATE/DELETE 在 audit_logs / audit_log_chain_heads 上
-- (表级 GRANT 的 INSERT 是允许的, 库级 GRANT 的 INSERT/UPDATE/DELETE 已收回)
-- SELECT GRANTEE, TABLE_NAME, PRIVILEGE_TYPE
-- FROM information_schema.SCHEMA_PRIVILEGES  -- 库级
-- WHERE TABLE_SCHEMA = 'ipd_dev'
--   AND GRANTEE = "'ipd_app'@'127.0.0.1'"
--   AND PRIVILEGE_TYPE IN ('INSERT', 'UPDATE', 'DELETE');
-- 期望: 0 行 (audit_logs/chain_heads 库级 INSERT/UPDATE/DELETE 全部已收回)
--
-- 表级 INSERT 保留 (业务必须): SELECT * FROM information_schema.TABLE_PRIVILEGES
-- WHERE TABLE_SCHEMA='ipd_dev' AND TABLE_NAME IN ('audit_logs','audit_log_chain_heads')
--   AND GRANTEE="'ipd_app'@'127.0.0.1'" AND PRIVILEGE_TYPE='INSERT';
-- 期望: 2 行 (audit_logs + audit_log_chain_heads 都有表级 INSERT)

-- ============================================================================
-- 5. rebuild-chain 端点临时 GRANT UPDATE (超管 rebuild 时使用, 平时 REVOKE 生效)
-- ============================================================================
-- AuditLogService.rebuildChain() 是 audit_logs 上唯一 UPDATE 调用方
-- 超管 rebuild chain 时临时 GRANT UPDATE, rebuild 后立即 REVOKE
-- 推荐用 ops 脚本 scripts/audit-log-revoke.sh (本文件仅约束常态 REVOKE)

-- GRANT UPDATE ON ipd_dev.audit_logs TO 'ipd_app'@'127.0.0.1';  -- rebuild 窗口临时
-- (rebuild 后立即执行 ↓)
-- REVOKE UPDATE ON ipd_dev.audit_logs FROM 'ipd_app'@'127.0.0.1';

-- ============================================================================
-- 6. 回滚预案 (生产事故一键复原)
-- ============================================================================
-- 如本文件 §1-3 引发不可预期的兼容性问题, 立即回滚:
--
-- GRANT INSERT ON ipd_dev.audit_logs TO 'ipd_app'@'127.0.0.1';
-- GRANT INSERT ON ipd_dev.audit_log_chain_heads TO 'ipd_app'@'127.0.0.1';
-- (UPDATE/DELETE 不回滚, 由 commit e9e6631d Q6 维持)

-- ============================================================================
-- 7. 与 IPD 硬约束对齐
-- ============================================================================
-- G-02 强只追加         ✅ 数据库 GRANT 天然支持: 禁止 UPDATE/DELETE 即实现只追加
-- G-04 业务高于文档惯例  ✅ 纵深防御是 IPD 最高约束的体现
-- G-07 单企业私有部署    ✅ 单租户脚本无需多租户隔离
-- G-11 审计链原子性     ✅ AuditLogService 写仍走应用层事务, 原子性不变