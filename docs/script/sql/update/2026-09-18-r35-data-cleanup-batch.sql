-- =====================================================================
-- [数据治理] R35 脏数据批量清理 — owner 决策 2026-09-18(loop 自动执行)
-- 承接:R34 系统性扫描报告 Pattern A P0-2/P0-3/P0-5 + R34 大白话反思版 §6.2
-- 范围:
--   1) products: 27 条 create_by=-1 的 ACTIVE 测试夹具 → 软删
--   2) projects: 11 条 create_by=-1 的 QA03/R30/R31/HARDWARE 探针 → 软删
--   3) ZK-GATE-TEST 跨表双写:products(900001+9130004) + projects(9140004) → 软删
-- 操作:全为软删(del_flag=1 + status 改 RETIRED/ARCHIVED),不物理删除
-- 幂等:WHERE 条件含 create_by=-1 / ID 锁定 + status='ACTIVE' → 重放零副作用
-- 撞车风险:0(纯 DB 写,不动 src/main;兄弟 worktree 不在改这 3 张表)
-- 防呆:apply 前人工核对 COUNT(*)=27 / 11 / 3;若不一致立即停手回报 owner
-- =====================================================================

-- 前置快照(apply 前人工核对:apply 后应全为 0)
select 'products_probe' AS tbl, COUNT(*) AS cnt
  from products
  where create_by=-1 AND status='ACTIVE' AND product_name REGEXP '^(P[0-9]|QA[0-9]|E2E|R3)'
UNION ALL
select 'projects_probe', COUNT(*)
  from projects
  where create_by=-1 AND name REGEXP 'QA03|R30|R31|矩阵|HARDWARE|探针'
UNION ALL
select 'zk_gate_products', COUNT(*)
  from products
  where id IN (900001, 9130004)
UNION ALL
select 'zk_gate_projects', COUNT(*)
  from projects
  where id IN (9140004);

-- 1) products 27 条测试夹具软删
update products
set del_flag='1',
    status='RETIRED',
    retired_at=now(),
    remark=concat('R35 脏数据清理 20260918: create_by=-1 测试夹具,业务下拉污染。原 remark:',
                  case when remark is null or remark='' then '(空)' else remark end),
    update_by=-1,
    update_time=now()
where create_by=-1
  AND status='ACTIVE'
  AND product_name REGEXP '^(P[0-9]|QA[0-9]|E2E|R3)';

-- 2) projects 11 条探针软删
update projects
set del_flag='1',
    status='ARCHIVED',
    update_by=-1,
    update_time=now()
where create_by=-1
  AND name REGEXP 'QA03|R30|R31|矩阵|HARDWARE|探针';

-- 3) ZK-GATE-TEST 跨表双写软删
update products
set del_flag='1',
    status='RETIRED',
    retired_at=now(),
    remark=concat('R35 ZK-GATE-TEST 跨表双写清理 20260918。原 remark:',
                  case when remark is null or remark='' then '(空)' else remark end),
    update_by=-1,
    update_time=now()
where id IN (900001, 9130004);

update projects
set del_flag='1',
    status='ARCHIVED',
    update_by=-1,
    update_time=now()
where id IN (9140004);

-- 回读校验1:apply 后所有 4 项计数应为 0
select 'products_probe' AS tbl, COUNT(*) AS cnt
  from products
  where create_by=-1 AND status='ACTIVE' AND product_name REGEXP '^(P[0-9]|QA[0-9]|E2E|R3)'
UNION ALL
select 'projects_probe', COUNT(*)
  from projects
  where create_by=-1 AND name REGEXP 'QA03|R30|R31|矩阵|HARDWARE|探针'
UNION ALL
select 'zk_gate_products', COUNT(*)
  from products
  where id IN (900001, 9130004) AND del_flag='0'
UNION ALL
select 'zk_gate_projects', COUNT(*)
  from projects
  where id IN (9140004) AND del_flag='0';
