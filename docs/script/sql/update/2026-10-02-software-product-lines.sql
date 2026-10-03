-- 五条软件产品线。不新增项目，不改已有项目的产品线。
-- 服务标识与产线知识库清单一一对应。

INSERT INTO product_lines (id, line_code, line_name, mcp_service_id, status, tenant_id, del_flag, create_by, remark)
SELECT 9190011, 'catalog-wanruida-v6600', '万傲瑞达 V6600', 'FastGPT-mcp-693fdd09b24e7762a0dc0880',
       'ACTIVE', '000000', '0', 900101, '软件产品线'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM product_lines WHERE line_code = 'catalog-wanruida-v6600' AND del_flag = '0');

INSERT INTO product_lines (id, line_code, line_name, mcp_service_id, status, tenant_id, del_flag, create_by, remark)
SELECT 9190012, 'catalog-zktime', 'ZKTime', 'FastGPT-mcp-69cce8b996a40120630b1d80',
       'ACTIVE', '000000', '0', 900101, '软件产品线'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM product_lines WHERE line_code = 'catalog-zktime' AND del_flag = '0');

INSERT INTO product_lines (id, line_code, line_name, mcp_service_id, status, tenant_id, del_flag, create_by, remark)
SELECT 9190013, 'catalog-zkaccess35', 'ZKAccess3.5', 'FastGPT-mcp-69cce8e096a40120630b1e68',
       'ACTIVE', '000000', '0', 900101, '软件产品线'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM product_lines WHERE line_code = 'catalog-zkaccess35' AND del_flag = '0');

INSERT INTO product_lines (id, line_code, line_name, mcp_service_id, status, tenant_id, del_flag, create_by, remark)
SELECT 9190014, 'catalog-ezkeco-pro', 'E-ZKEco Pro', 'FastGPT-mcp-693fdcfdb24e7762a0dc07e2',
       'ACTIVE', '000000', '0', 900101, '软件产品线'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM product_lines WHERE line_code = 'catalog-ezkeco-pro' AND del_flag = '0');

INSERT INTO product_lines (id, line_code, line_name, mcp_service_id, status, tenant_id, del_flag, create_by, remark)
SELECT 9190015, 'catalog-zkteco-connect', '熵基互联', 'FastGPT-mcp-69cce91596a40120630b2017',
       'ACTIVE', '000000', '0', 900101, '软件产品线'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM product_lines WHERE line_code = 'catalog-zkteco-connect' AND del_flag = '0');
