package org.ruoyi.ipd.service.ai;

/**
 * 内置默认 embedding 模型（用户指令 2026-09-30「向量模型内置写死」）——Java 侧唯一定义点。
 * <ul>
 *   <li>用户原文端点 {@code http://171.43.138.237:9997/v1/embeddings}；Langchain4j
 *       {@code OpenAiEmbeddingModel} 固定按 {@code POST {baseUrl}/embeddings} 拼接，故这里存
 *       base URL {@link #BASE_URL}，二者拼接后即用户原文（直存全路径会拼成
 *       {@code /v1/embeddings/embeddings}，实测 404）。</li>
 *   <li>无鉴权（xinference，密码为空）：apiKey 取空串，与
 *       {@code AiModelConfigService#decryptApiKey} 对「未配置密文」返回空串的既有做法一致；
 *       绝不复用生效 chat 配置的密钥（避免把 chat 厂商密钥发往本端点）。</li>
 *   <li>维度 {@link #DIMENSION} 为实测值（2026-09-30 curl 返回 1024）。</li>
 *   <li>安装种子 {@code docs/script/sql/update/2026-09-30-ipd-builtin-embedding-model-seed.sql}
 *       与本类取值一致，由 {@code BuiltinEmbeddingModelTest} 防漂移。</li>
 * </ul>
 */
public final class BuiltinEmbeddingModel {

    /** OpenAI 兼容 base URL（消费方自拼 /embeddings）。 */
    public static final String BASE_URL = "http://171.43.138.237:9997/v1";
    /** 模型名（亦是 ai_doc_embeddings.embed_model 向量空间锚）。 */
    public static final String MODEL_NAME = "Qwen3-Embedding-0.6B";
    /** 无鉴权：空串（非伪造密钥）。 */
    public static final String API_KEY = "";
    /** 实测向量维度。 */
    public static final int DIMENSION = 1024;
    /** 配置来源标识（日志/调用方可见）。 */
    public static final String SOURCE = "builtin-default";
    /** 运维关闭开关（默认 true；私有部署禁止出公网时置 false，RAG 在无管理员配置时即关闭）。 */
    public static final String ENABLED_PROPERTY = "ipd.ai.builtin-embedding.enabled";

    private BuiltinEmbeddingModel() {
    }
}
