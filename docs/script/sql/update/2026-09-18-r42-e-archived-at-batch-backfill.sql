-- R42-E archived_at 批量回填 — projects 表 status='ARCHIVED' 但 archived_at IS NULL 全部行
-- 触发:R42-D merge gate 第 8 项自证能红 — 实测真库发现 6 行违规:
--       9140001, 9140002, 9140003 (R34 P0-3 报告另外 3 条, R36 C1 只回填 9140004 漏了这 3 条)
--       2096325036506877954, 2096325111970795521 (字符串型雪玢 ID)
--       + 1 行未列在 sample 前 5 (需重跑 R42-D 拿到完整 ID 列表)
-- 背景:R35 cleanup SQL 给 zk_gate_projects 写了 status='ARCHIVED' + del_flag=1,漏写 archived_at=now()
--       R36 C1.1 archived_at-backfill-9140004.sql 只回了 1 行,其他 5 行被遗漏
--       R42-D 自证能红:真库 status='ARCHIVED' AND archived_at IS NULL → 6 行
-- 范围:projects 表所有 status='ARCHIVED' AND archived_at IS NULL 的行(幂等 WHERE 守护)
-- 期望:apply 后 archived_at 不为 NULL,~6 row affected
--
-- === 应用前必须先重跑 R42-D 拿到完整 ID 清单 ===
-- bash scripts/check-merge-gate-archived-at.sh --strict > /tmp/r42-e-id-list.txt
-- 重跑后确认 ID 列表无新增, 再 apply 本 SQL
--
-- === 不在 scripts/ 自动跑 ===
-- 本文件仅供 DBA / owner apply;不在 CI 中自动执行(避免破坏生产数据)
-- 参考: docs/ipd-系统说明/R42-D-merge-gate-archived-at扫描-20260918.md

-- 第一步:扫描当前违规行(apply 前必跑,确认范围)
SELECT id,
       project_name,
       status,
       archived_at,
       del_flag,
       create_time,
       update_time,
       remark
FROM projects
WHERE status = 'ARCHIVED'
  AND archived_at IS NULL
ORDER BY id;

-- 第二步:批量回填 archived_at = NOW()(幂等 WHERE archived_at IS NULL 守护)
-- 说明:NOW() 统一回填到当前时间,实际归档时间可能略晚于真实归档时点;
--      如需精确还原,根据 create_time / update_time / 历史日志补回
UPDATE projects
SET archived_at = NOW(),
    remark = CONCAT(
        'R42-E archived_at 批量回填(R36 C1.1 漏 5 行跟进,门禁 R42-D 自证能红发现);',
        COALESCE(remark, '')
    )
WHERE status = 'ARCHIVED'
  AND archived_at IS NULL;

-- 第三步:apply 后复核(期望 0 row)
SELECT COUNT(*) AS remaining_violations
FROM projects
WHERE status = 'ARCHIVED'
  AND archived_at IS NULL;