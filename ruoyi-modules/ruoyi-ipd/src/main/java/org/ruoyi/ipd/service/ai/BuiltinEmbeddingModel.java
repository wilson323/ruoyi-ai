package org.ruoyi.ipd.service.ai;

import org.ruoyi.common.chat.embedding.BuiltinEmbeddingDefaults;

/**
 * 内置默认 embedding 模型（用户指令 2026-09-30「向量模型内置写死」）。
 * 字面量只定义在 {@link BuiltinEmbeddingDefaults}，本类保持原有常量名供文档嵌入使用。
 * <ul>
 *   <li>配置地址 {@link #BASE_URL} 指向本机 Ollama。
 *       由共享 {@code EmbeddingModels} 装配 AgentScope 原生 {@code OllamaTextEmbedding}，
 *       装配时去掉兼容地址尾部的 {@code /v1}，调用 Ollama 原生嵌入接口。</li>
 *   <li>模型名是 ollama list 的 {@code qwen3-embedding:0.6b}。无鉴权：apiKey 取空串，与
 *       {@code AiModelConfigService#decryptApiKey} 对「未配置密文」返回空串的既有做法一致；
 *       绝不复用生效 chat 配置的密钥（避免把对话密钥发往本端点）。</li>
 *   <li>维度 {@link #DIMENSION} 为短文本实测值（2026-10-01 HTTP 200，1024）。</li>
 *   <li>安装种子 {@code docs/script/sql/update/2026-09-30-ipd-builtin-embedding-model-seed.sql}
 *       与本类取值一致，由 {@code BuiltinEmbeddingModelTest} 防漂移。</li>
 * </ul>
 */
public final class BuiltinEmbeddingModel {

    /** 内置 Ollama 的兼容配置地址（共享 AgentScope 装配层规范化）。 */
    public static final String BASE_URL = BuiltinEmbeddingDefaults.BASE_URL;
    /** 模型名（亦是 ai_doc_embeddings.embed_model 向量空间锚）。 */
    public static final String MODEL_NAME = BuiltinEmbeddingDefaults.MODEL_NAME;
    /** 无鉴权：空串（非伪造密钥）。 */
    public static final String API_KEY = BuiltinEmbeddingDefaults.API_KEY;
    /** 实测向量维度。 */
    public static final int DIMENSION = BuiltinEmbeddingDefaults.DIMENSION;
    /** 配置来源标识（日志/调用方可见）。 */
    public static final String SOURCE = "builtin-default";
    /** 运维关闭开关（默认 true；私有部署禁止出公网时置 false，RAG 在无管理员配置时即关闭）。 */
    public static final String ENABLED_PROPERTY = "ipd.ai.builtin-embedding.enabled";

    private BuiltinEmbeddingModel() {
    }
}
