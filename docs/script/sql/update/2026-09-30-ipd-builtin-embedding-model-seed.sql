-- =====================================================================
-- 2026-09-30 内置默认向量（embedding）模型种子
-- 用户指令：向量模型地址默认 http://171.43.138.237:9997/v1/embeddings，
--           模型名称 Qwen3-Embedding-0.6B，密码为空，作为内置写死。
--
-- 落点：chat_model（平台模型登记表，category='vector' 即「用途」列；知识库/RAG
--       EmbeddingModelFactory 按 model_name 读取）。
--   api_host 存 base URL http://171.43.138.237:9997/v1：Langchain4j OpenAiEmbeddingModel
--   固定拼 POST {api_host}/embeddings，拼接后即用户原文；存全路径会拼成
--   /v1/embeddings/embeddings（实测 404）。
--   api_key = NULL：无鉴权；openai 供应商读取侧 NULL → 不发 Authorization 头；
--   空串会被 ChatModelSecretReference 判为非法遗留明文，故不用 ''，也不写任何占位密钥。
--   model_dimension = 1024（2026-09-30 实测接口返回维度）。
--
-- 不写 ai_model_configs：该表每行是一套 chat 配置（embed 为生效行 config_json 子键），
--   无用途列；插入 embedding 行会混入页48 chat 列表且一旦启用将把对话路由到向量模型；
--   给现有行补子键属 UPDATE（不覆盖管理员修改原则禁止）。IPD 文档 RAG 在生效行未配
--   embed 两键时由代码内置默认兜底（org.ruoyi.ipd.service.ai.BuiltinEmbeddingModel，
--   与本文件取值一致，BuiltinEmbeddingModelTest 防漂移）。
--
-- 幂等：同租户已存在 category='vector' 且同名 model_name（或同主键）即跳过，
--       不覆盖管理员对既有行的修改。无 UPDATE / DELETE。
-- apply：人工/DBA 执行（本仓无 Flyway）；执行后按文末回读 SQL 核验。
-- 回滚：DELETE FROM chat_model WHERE id = 2096618258030407681
--        AND category = 'vector' AND model_name = 'Qwen3-Embedding-0.6B';
--       （仅当该行由本种子插入时执行；本机 ipd_dev 该行为 2026-09-06 既有行，勿删。）
-- =====================================================================

INSERT INTO chat_model (id, category, model_name, provider_code, model_describe, model_dimension,
                        model_show, api_host, api_key, create_dept, create_by, create_time,
                        update_by, update_time, remark, tenant_id)
SELECT 2096618258030407681, 'vector', 'Qwen3-Embedding-0.6B', 'openai', 'Qwen3-Embedding-0.6B', 1024,
       'N', 'http://171.43.138.237:9997/v1', NULL, 103, 1, NOW(),
       1, NOW(), '内置默认向量模型（无鉴权，2026-09-30 种子）', 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM chat_model
    WHERE (category = 'vector' AND model_name = 'Qwen3-Embedding-0.6B' AND tenant_id = 0)
       OR id = 2096618258030407681
);

-- 回读核验：
-- SELECT id, category, model_name, provider_code, model_dimension, api_host, api_key IS NULL AS key_null, tenant_id
--   FROM chat_model WHERE category = 'vector' AND model_name = 'Qwen3-Embedding-0.6B';
