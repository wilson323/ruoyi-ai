-- R36-1 archived_at 一致性回填
-- 触发:R35-数据治理实操报告 §4 残留瑕疵 — projects.id=9140004 (ZK-GATE-TEST) archived_at=NULL
-- 背景:R35 cleanup SQL 给 zk_gate_projects 写了 status='ARCHIVED' + del_flag=1,漏写 archived_at=now()
-- 范围:仅 1 条记录(id=9140004),幂等 WHERE archived_at IS NULL 守护
-- 期望:apply 后 archived_at 不为 NULL,1 row affected

UPDATE projects
SET archived_at = NOW(),
    remark = CONCAT('R36 archived_at 一致性回填(原 R35 cleanup 漏 archived_at);',
                    COALESCE(remark, ''))
WHERE id = 9140004
  AND archived_at IS NULL;