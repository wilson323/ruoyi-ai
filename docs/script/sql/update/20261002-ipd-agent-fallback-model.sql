-- 20261002 MiniMax 官方回退模型行（AgentScope 2.0.3 fallbackModel 扩展点取数）
-- 背景：owner 指令（2026-10-02）回退模型接 MiniMax 备用型号；主模型 ai_model_configs
--   id=2104885081318375426（MiniMax-M3，https://api.minimax.cn/v1，is_active=1）。
-- 备用型号 MiniMax-M2.7-highspeed 经平台 GET /v1/models 与 /v1/chat/completions
--   max_tokens=1 最小试呼双重实证可用（HTTP 200）。
-- 约定：config_json.fallbackFor = 主模型 model_name，装配逻辑按同 provider + 该约定定位回退行。
-- is_active=0 说明：ai_model_configs 的 is_active 语义为「全局唯一生效主模型」
--   （AiModelConfigService.enable 互斥清位；ProjectAgentModelCatalog.resolve 拒绝非 active 行），
--   回退行不参与主选型与互斥，故保持 0；回退取数走 fallbackFor 约定，不读 is_active。
-- 密钥：api_key_encrypted 与主模型行同值（同 key 同算法 AES/ECB/PKCS5-base64，密文复制，
--   全程不出现明文）；uk_model_name 含 del_flag，新行 del_flag='0'。
INSERT INTO ai_model_configs
    (id, model_name, provider, api_key_encrypted, endpoint_url, config_json, is_active,
     create_time, update_time, tenant_id, del_flag)
SELECT 2104900000000000001, 'MiniMax-M2.7-highspeed', provider, api_key_encrypted, endpoint_url,
       JSON_OBJECT('fallbackFor', model_name), 0, NOW(), NOW(), tenant_id, '0'
FROM ai_model_configs
WHERE id = 2104885081318375426 AND del_flag = '0';

-- 回滚（如需）：DELETE FROM ai_model_configs WHERE id = 2104900000000000001;
