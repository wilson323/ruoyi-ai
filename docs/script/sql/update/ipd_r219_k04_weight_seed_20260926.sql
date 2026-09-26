-- =====================================================================
-- R219 台账①（AC-CFG-01）：K04 权重配置化 seed
-- 关联变更：kpi.k04Weight —— KpiSharedCollectionService.readK04Weight() 默认 0.05
-- 消费方：R219 fix（本波后端批 4）；缺行/非法值时 Java 回退 0.05，行为与 seed 前一致
-- 幂等：INSERT IGNORE（uk config_key）；id 段沿用 R149 seed 的 194809xxxx 命名空间外扩
-- apply：人工/DBA 执行（本仓无 Flyway）；执行后以 p1-ddl-apply-check 或直读回验
-- =====================================================================

INSERT IGNORE INTO system_configs (id, config_key, config_value, value_type, default_value, description, tenant_id, create_time)
VALUES (1948092601, 'kpi.k04Weight', '0.05', 'NUMBER', '0.05',
        'R219 台账①（AC-CFG-01）：K04 场景覆盖率权重（合法区间 (0, 0.40]；非法/缺行回退 0.05）',
        '000000', now());
