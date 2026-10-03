-- 产品和项目是一对多。只改列注释，不改索引、不改数据。
-- products.project_id 只记首个项目，uk_products_project 保留。
-- projects.product_id 可重复，uk_projects_product 已在 2026-10-01-product-line-start.sql 删除。

ALTER TABLE products
  MODIFY COLUMN project_id bigint NULL
  COMMENT '首个项目指针。一个产品可有多个项目，列表以 projects.product_id 为准';

ALTER TABLE projects
  MODIFY COLUMN product_id bigint NULL
  COMMENT '所属产品。多个项目可指向同一产品。空表示尚未挂产品';
