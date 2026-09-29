-- 产品线空间结构（一个产品线可归属多个产品；Product↔Project 1:1 保留）
-- 仅在已核对目标库为本机 ipd_dev、完成备份后执行。
-- 不做存量产品自动归属：组织 product_groups 不等于产品线。

CREATE TABLE IF NOT EXISTS product_lines (
    id               BIGINT       NOT NULL COMMENT '雪花主键；稳定产品线空间身份',
    line_code        VARCHAR(64)  NOT NULL COMMENT '产品线编码',
    line_name        VARCHAR(128) NOT NULL COMMENT '产品线名称',
    leader_person_id BIGINT       NULL COMMENT '管理员指定的产品线组长；须为在职空间成员',
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|INACTIVE',
    create_dept      BIGINT       NULL,
    create_by        BIGINT       NULL,
    create_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    update_by        BIGINT       NULL,
    update_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    tenant_id        VARCHAR(20)  NOT NULL DEFAULT '000000',
    del_flag         CHAR(1)      NOT NULL DEFAULT '0',
    remark           VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_lines_tenant_code (tenant_id, line_code, del_flag),
    KEY idx_product_lines_leader (leader_person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='IPD 产品线空间；与组织产品组分离';

CREATE TABLE IF NOT EXISTS product_line_members (
    id              BIGINT      NOT NULL COMMENT '雪花主键',
    product_line_id BIGINT      NOT NULL COMMENT '产品线空间 ID',
    person_id       BIGINT      NOT NULL COMMENT 'Person ID',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|ACTIVE|EXITED|REJECTED',
    joined_at       DATETIME    NULL,
    exited_at       DATETIME    NULL,
    reviewed_by     BIGINT      NULL COMMENT '加入申请的审核人',
    reviewed_at     DATETIME    NULL,
    create_dept     BIGINT      NULL,
    create_by       BIGINT      NULL,
    create_time     DATETIME    NULL DEFAULT CURRENT_TIMESTAMP,
    update_by       BIGINT      NULL,
    update_time     DATETIME    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    tenant_id       VARCHAR(20) NOT NULL DEFAULT '000000',
    del_flag        CHAR(1)     NOT NULL DEFAULT '0',
    remark          VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_line_member (product_line_id, person_id),
    KEY idx_product_line_members_person (person_id, status, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='IPD 产品线空间成员；组长身份由 product_lines.leader_person_id 唯一标识';

SET @has_product_line_id := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'products'
      AND column_name = 'product_line_id'
);
SET @sql := IF(@has_product_line_id = 0,
    'ALTER TABLE products ADD COLUMN product_line_id BIGINT NULL COMMENT ''归属产品线空间；一个产品线可有多个产品'' AFTER group_id',
    'SELECT ''products.product_line_id 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_line_index := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'products'
      AND index_name = 'idx_products_product_line'
);
SET @sql := IF(@has_line_index = 0,
    'ALTER TABLE products ADD KEY idx_products_product_line (product_line_id, tenant_id, del_flag)',
    'SELECT ''idx_products_product_line 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回读：产品线 ID 非唯一；现有 Product↔Project 两个唯一键必须仍在。
SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND ((table_name = 'products' AND index_name IN
       ('idx_products_product_line', 'uk_products_project'))
    OR (table_name = 'projects' AND index_name = 'uk_projects_product'))
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;
