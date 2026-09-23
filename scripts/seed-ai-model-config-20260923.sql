-- ============================================================================
-- seed-ai-model-config-20260923.sql
-- 用途:L0-1 模型配置启用行参考 SQL(L0-1 验收 / 兄弟会话误删后恢复用)
-- 默认 NOT executed: 文件头加 USE 注释作为 owner run 入口,管道需 --force 或人工确认
--
-- 真活现状(2026-09-23 fresh):
--   SELECT id, model_name, provider, endpoint_url, is_active, tenant_id
--   FROM ai_model_configs WHERE is_active=1 AND del_flag='0';
--   +----+-------------------+----------+-----------------------+-----------+-----------+
--   | id | model_name        | provider | endpoint_url          | is_active | tenant_id |
--   +----+-------------------+----------+-----------------------+-----------+-----------+
--   |  1 | test-rag-20260923 | TEST     | http://127.0.0.1:8765 |         1 | 000000    |
--   +----+-------------------+----------+-----------------------+-----------+-----------+
--
-- 唯一约束(对齐 uk_model_name):
--   UNIQUE KEY `uk_model_name` (`model_name`,`tenant_id`,`del_flag`)
-- 故同 model_name + tenant_id + del_flag=0 二次 INSERT 会撞唯一键 —— 必须改 model_name 或先 UPDATE existing
--
-- 字段对齐:
--   model_name       VARCHAR(64)  NOT NULL  -- uk_model_name 维度
--   provider         VARCHAR(32)  NOT NULL  -- OPENAI/AZURE/LOCAL/TEST
--   api_key_encrypted VARCHAR(500) DEFAULT  -- AES base64 密文(IPD_AIMODEL_ENCRYPT_KEY 加密)
--   endpoint_url     VARCHAR(500) DEFAULT  -- OpenAI 兼容 chat/embed base
--   config_json      JSON          DEFAULT  -- embedEndpoint/embedModel 两键齐全才启用 RAG
--   is_active        TINYINT(1)    NOT NULL DEFAULT 1  -- 全局至多一条 true
--   tenant_id        VARCHAR(20)   DEFAULT '000000'  -- 多租户维度
--   del_flag         CHAR(1)       DEFAULT '0'  -- 0 正常 / 1 已删
--   + BaseEntity: create_dept / create_by / create_time / update_by / update_time
--
-- ⚠ owner run 前必读:
--   1) 主密钥:环境变量 IPD_AIMODEL_ENCRYPT_KEY 已注入(AiModelConfigService 启动 fail-fast 必须)
--   2) SSRF 黑名单:loopback / 169.254.* / RFC1918 / CGN 不应在生产配置里(provider=TEST 仅测试豁免)
--   3) 本 SQL 用 ON DUPLICATE KEY UPDATE 幂等,可重跑不报错
--   4) 触发 RAG 真活:本 SQL 不自动触发 ai_doc_embeddings 写入 —— 见 docs/ipd-系统说明/L0-1模型配置启用行-触发方法学-20260923.md
--   5) 回滚:同 SQL 改 is_active=0 即失活;
--          真删走 UPDATE ai_model_configs SET del_flag='1' WHERE id=? (软删除)
-- ============================================================================

-- 启用 USE ipd_dev;
USE ipd_dev;

-- ===========================================
-- 启用行 INSERT(幂等,UPSERT 语义)
-- ===========================================
INSERT INTO ai_model_configs
  (model_name, provider, endpoint_url, config_json, is_active, tenant_id, del_flag)
VALUES
  ('test-rag-20260923',
   'TEST',
   'http://127.0.0.1:8765',
   JSON_OBJECT(
     'embedModel',    'test-embed',
     'embedEndpoint', 'http://127.0.0.1:8765'
   ),
   1,
   '000000',
   '0')
ON DUPLICATE KEY UPDATE
  provider     = VALUES(provider),
  endpoint_url = VALUES(endpoint_url),
  config_json  = VALUES(config_json),
  is_active    = 1,
  update_time  = CURRENT_TIMESTAMP;

-- ===========================================
-- 回滚(失活,但保留行供 re-enable)
-- ===========================================
-- UPDATE ai_model_configs
-- SET is_active=0, update_time=CURRENT_TIMESTAMP
-- WHERE model_name='test-rag-20260923' AND tenant_id='000000' AND del_flag='0';

-- ===========================================
-- 真删(软删除;要走审计)
-- ===========================================
-- UPDATE ai_model_configs
-- SET del_flag='1', update_time=CURRENT_TIMESTAMP
-- WHERE model_name='test-rag-20260923' AND tenant_id='000000' AND del_flag='0';

-- ===========================================
-- 验收 SELECT(EXPECT:1 行)
-- ===========================================
SELECT id, model_name, provider, endpoint_url, is_active, del_flag, tenant_id, config_json
FROM ai_model_configs
WHERE is_active=1 AND del_flag='0';
