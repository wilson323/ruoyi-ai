-- 产品线知识库服务标识。只记已有产品线，不新增产品线，不改项目所属产品线。
-- 名称必须与清单精确相等。考勤产品、门禁、通道产品都不填。

ALTER TABLE product_lines
    ADD COLUMN mcp_service_id VARCHAR(128) NULL COMMENT '产线知识库服务标识' AFTER line_name;

UPDATE product_lines
SET mcp_service_id = 'FastGPT-mcp-693fdceeb24e7762a0dc05de'
WHERE del_flag = '0'
  AND line_name = '考勤'
  AND line_code = 'attendance';
