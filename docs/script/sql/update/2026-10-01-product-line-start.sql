-- 产品线开工：一产品多项目、未指定产品线、需求所属产品线。
-- projects.product_id 不再唯一；products.project_id 仍只指向首个项目。

ALTER TABLE projects ADD COLUMN product_line_id BIGINT NULL COMMENT '立项产品线';
ALTER TABLE requirements ADD COLUMN product_line_id BIGINT NULL COMMENT '需求所属产品线';
ALTER TABLE projects DROP INDEX uk_projects_product;

INSERT INTO product_lines (id, line_code, line_name, status, tenant_id, del_flag, create_by, remark)
VALUES (9190001, 'unspecified', '未指定产品线', 'ACTIVE', '000000', '0', 900101,
        '尚未归属具体产品线、且已有未删除项目的产品');

UPDATE products p
JOIN projects j ON j.product_id = p.id AND j.del_flag = '0'
SET p.product_line_id = 9190001
WHERE p.del_flag = '0' AND p.product_line_id IS NULL;

UPDATE projects j
JOIN products p ON p.id = j.product_id
SET j.product_line_id = p.product_line_id
WHERE j.del_flag = '0' AND j.product_line_id IS NULL AND p.product_line_id IS NOT NULL;

INSERT INTO products (id, product_name, product_code, status, source, product_line_id, tenant_id, del_flag, create_by)
VALUES (9190002, '未指定产品线需求分拣', 'UNSPEC-TRIAGE', 'IN_RD', 'PM_NEW', 9190001, '000000', '0', 900101);

INSERT INTO projects (id, code, name, product_id, template_type, level, status, current_stage, source, tenant_id, del_flag, create_by, product_line_id)
VALUES (9190003, 'PRJ-2026-900', '未指定产品线需求分拣', 9190002, 'SOLUTION', 'A', 'TEAMING', 'CONCEPT', 'NEW', '000000', '0', 900101, 9190001);

UPDATE products SET project_id = 9190003 WHERE id = 9190002 AND project_id IS NULL;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2099010200000000041, '产品线空间', parent_id, 4, 'product-lines', 'ipd/product-lines/index', is_frame, is_cache, menu_type, '0', '0', 'ipd:product-line:list', icon, 1, NOW(), '产品线空间'
FROM sys_menu WHERE menu_id=2099010200000000014;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 2099010200000000041 FROM sys_role_menu WHERE menu_id=2099010200000000014;
