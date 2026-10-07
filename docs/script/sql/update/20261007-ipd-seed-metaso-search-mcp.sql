-- =====================================================================
-- 秘塔搜索 MCP 工具登记（2026-10-07）
--
-- 【这个工具解决什么】
-- 让智能体默认具备联网搜索能力，无需每次手工配置。登记进 mcp_tool_info 后，
-- 现有装配代码（AgentScopeMcpToolProviderService）会自动发现并挂进工具箱。
--
-- 【为什么 transport 必须是 JSON_RPC，而不是 STREAMABLE_HTTP】
-- 秘塔端点 https://metaso.cn/api/mcp 是「无状态 JSON-RPC over POST」服务：
--   POST → 200 application/json（裸 JSON-RPC 响应）
--   GET  → 405 text/html          ← 对 GET 不提供任何推送通道
-- MCP Java SDK 自带的两种传输层（HttpClientStreamableHttpTransport /
-- HttpClientSseClientTransport）在发 POST 之前都会先发 GET 建通道，
-- 拿到 text/html 后抛 `Unknown media type returned: text/html;charset=utf-8`。
-- 2026-10-07 实测（两种传输层各试一次，均复现同一错误）。
--
-- 因此本仓新增了 org.ruoyi.mcp.transport.JsonRpcHttpClientTransport：
-- 只发 POST、只收 JSON，协议语义仍由 SDK 自己的
-- McpSchema.deserializeJsonRpcMessage 判定，不自行解释报文。
-- openClient 里以 config_json.transport = "JSON_RPC" 分支启用。
--
-- 【密钥为什么不在本文件里】
-- config_json.headers.Authorization 是明文，本文件会被提交进仓库，
-- 密钥绝不能跟它走同一条路。下面的 @metaso_api_key 由执行者预先赋值：
--   mysql --defaults-file=/tmp/my-root.cnf ipd_dev \
--     -e "SET @metaso_api_key='mk-xxxx'; SOURCE 本文件;"
-- 未赋值时头部写成占位符 Bearer ${METASO_MCP_API_KEY}，装配会 401——
-- 这是刻意的：宁可明确失败，也不要看起来装好了其实用不了。
--
-- 【幂等】按固定 id 17 upsert，重复执行无副作用。
-- 【回滚】DELETE FROM mcp_tool_info WHERE id = 17;
-- =====================================================================

SELECT DATABASE() AS selected_catalog;

-- ---- 执行前自检：看清要写的是哪一行 ----
SELECT id, name, type, status, config_json
FROM mcp_tool_info WHERE id = 17;

SET @metaso_api_key := COALESCE(NULLIF(@metaso_api_key, ''), '${METASO_MCP_API_KEY}');

INSERT INTO mcp_tool_info
    (id, name, description, type, status, config_json, tenant_id, create_time, del_flag)
VALUES
    (17,
     'metaso_search',
     '秘塔AI搜索 MCP：metaso_web_search 联网搜索 / metaso_web_reader 读网页 / metaso_chat 智能问答',
     'REMOTE',
     'ENABLED',
     JSON_OBJECT(
         'baseUrl', 'https://metaso.cn/api/mcp',
         'transport', 'JSON_RPC',
         'headers', JSON_OBJECT('Authorization', CONCAT('Bearer ', @metaso_api_key))
     ),
     '000000', NOW(), '0')
ON DUPLICATE KEY UPDATE
    description = VALUES(description),
    config_json = VALUES(config_json),
    status      = 'ENABLED',
    del_flag    = '0',
    update_time = NOW();

-- ---- 回读验证 ----
-- 期望：transport=JSON_RPC、baseUrl 正确、auth_len = 7 + 密钥长度。
--       若 auth_len = 33，说明 @metaso_api_key 没赋值，落进去的是占位符，装配会 401。
SELECT id, name, type, status,
       JSON_VALID(config_json) AS json_ok,
       JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.transport')) AS transport,
       JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.baseUrl')) AS baseUrl,
       CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.headers.Authorization'))) AS auth_len
FROM mcp_tool_info WHERE id = 17;

-- ---- 交叉验证：别和内置工具重名（重名会让整次装配失败）----
-- AgentScopeMcpToolProviderService.rejectDuplicateNames 会因同名直接抛错，
-- 所以这里必须确认 17 号工具名与现有 9 个内置工具无一重合。
SELECT COUNT(*) AS name_collision
FROM mcp_tool_info
WHERE id <> 17 AND del_flag = '0' AND name = 'metaso_search';
-- 期望 name_collision = 0
