-- B1 本机开发库产品线初始空间。显式核对目标为 ipd_dev 并备份后执行。
-- 不给 368 条存量产品推断归属；组织 product_groups 与产品线无映射关系。
-- 固定 ID 使重复执行保持同一空间；编码已存在时保留现有管理员修改结果。
-- 固定 ID 或编码若指向不同空间，利用 NOT NULL 约束使整条 INSERT 失败，不静默跳过。

INSERT INTO product_lines (id, line_code, line_name, status, tenant_id, del_flag, create_time)
VALUES
    (2026092900000000001, 'attendance', '考勤', 'ACTIVE', '000000', '0', NOW()),
    (2026092900000000002, 'access-control', '门禁', 'ACTIVE', '000000', '0', NOW()),
    (2026092900000000003, 'video', '视频', 'ACTIVE', '000000', '0', NOW())
ON DUPLICATE KEY UPDATE
    id = IF(id = VALUES(id) AND line_code = VALUES(line_code)
            AND tenant_id = VALUES(tenant_id), id, NULL);

-- 复核编码和 ID，异常冲突须停止，不能以覆盖存量行“修复”。
SELECT id, line_code, line_name, status, tenant_id
FROM product_lines
WHERE tenant_id = '000000' AND line_code IN ('attendance', 'access-control', 'video')
ORDER BY line_code;

SELECT COUNT(*) AS unassigned_products
FROM products WHERE tenant_id = '000000' AND product_line_id IS NULL;
