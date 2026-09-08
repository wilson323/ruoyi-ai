-- P3-4.4 前置数据治理：补 projects.level_cofficient NULL
-- 触发：P0-3.5 fresh 验证阻塞（兄弟会话 20:50 段发现）
-- owner 选项 1：SQL 补默认值（S=1.8 / A=1.0 / B=0.7）→ 重做 P0-3.5 fresh
-- 约定依据：BonusPoolService.java:237 BR-INC-05（S 1.5-2.0 / A 1.0 固定 / B 0.6-0.8）+ owner 拍板默认值

USE ipd_dev;

-- 1. 修复前 baseline
SELECT '=== before ===' AS phase;
SELECT id, name, level, level_coefficient, status
FROM projects
WHERE level_coefficient IS NULL AND del_flag='0'
ORDER BY level, id;

-- 2. UPDATE 按 level 默认值（S=1.8 / A=1.0 / B=0.7）
UPDATE projects
SET level_coefficient = CASE level
        WHEN 'S' THEN 1.80
        WHEN 'A' THEN 1.00
        WHEN 'B' THEN 0.70
        ELSE 1.00
    END,
    level_coefficient_reason = CONCAT('P3-4.4 前置数据治理 ', DATE_FORMAT(NOW(), '%Y-%m-%d'), ' 默认值补齐')
WHERE level_coefficient IS NULL AND del_flag='0';

-- 3. 修复后验证
SELECT '=== after ===' AS phase;
SELECT COUNT(*) AS total_null_remaining
FROM projects
WHERE level_coefficient IS NULL AND del_flag='0';

SELECT id, name, level, level_coefficient, status, level_coefficient_reason
FROM projects
WHERE id IN (9140001,9140002,9140003,9140004,9140005,2096235170527993857,2096260014296567810)
ORDER BY id;