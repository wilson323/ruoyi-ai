-- 2026-09-09 HIGH-5 治理轮裁决落地：config_value 列型漂移修齐
-- 范围：3 张参数表（system_configs / ipd_business_config / ipd_business_config_versions）的 config_value 列
--       从仓库历史 DDL 的 varchar(500) 统一为不限长 text（真库 2026-09-09 实测已为 text，本次幂等回填）
-- 变更类型：DDL（列类型变更）
-- 风险：低。text 与 varchar(500) 对 MySQL 8.0 行存格式等价（均溢出页外存储 768B+），无数据迁移成本。
--        行为差异：text 不强制 DEFAULT ''（默认 NULL），读取方需容忍 null——Service 层 @TableField 已映射 String，
--        与 null 兼容。v_history: HIGH-5 config_value 列型漂移→text
-- 日期：2026-09-09

-- ===== system_configs.config_value =====
SET @col_type := (
    SELECT DATA_TYPE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'system_configs'
      AND COLUMN_NAME = 'config_value'
);
SET @stmt := IF(@col_type = 'varchar',
    'ALTER TABLE system_configs MODIFY COLUMN config_value text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT ''参数键值''',
    'SELECT ''system_configs.config_value 已是 '' || @col_type || ''，跳过'' AS info'
);
PREPARE s1 FROM @stmt; EXECUTE s1; DEALLOCATE PREPARE s1;

-- ===== ipd_business_config.config_value =====
SET @col_type := (
    SELECT DATA_TYPE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ipd_business_config'
      AND COLUMN_NAME = 'config_value'
);
SET @stmt := IF(@col_type = 'varchar',
    'ALTER TABLE ipd_business_config MODIFY COLUMN config_value text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''参数值（字符串持久化，读取方按 value_type 解析）''',
    'SELECT ''ipd_business_config.config_value 已是 '' || @col_type || ''，跳过'' AS info'
);
PREPARE s2 FROM @stmt; EXECUTE s2; DEALLOCATE PREPARE s2;

-- ===== ipd_business_config_versions.config_value =====
SET @col_type := (
    SELECT DATA_TYPE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ipd_business_config_versions'
      AND COLUMN_NAME = 'config_value'
);
SET @stmt := IF(@col_type = 'varchar',
    'ALTER TABLE ipd_business_config_versions MODIFY COLUMN config_value text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT ''历史参数值''',
    'SELECT ''ipd_business_config_versions.config_value 已是 '' || @col_type || ''，跳过'' AS info'
);
PREPARE s3 FROM @stmt; EXECUTE s3; DEALLOCATE PREPARE s3;

-- 验证：3 张表 config_value 应均为 text
SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('system_configs', 'ipd_business_config', 'ipd_business_config_versions')
  AND COLUMN_NAME = 'config_value'
ORDER BY TABLE_NAME;
