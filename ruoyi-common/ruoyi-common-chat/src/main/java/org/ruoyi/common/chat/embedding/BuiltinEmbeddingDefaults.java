package org.ruoyi.common.chat.embedding;

/**
 * 文档嵌入与知识库嵌入共用的内置向量模型。
 * 取值与 {@code org.ruoyi.ipd.service.ai.BuiltinEmbeddingModel} 是同一组常量，不另建模型。
 */
public final class BuiltinEmbeddingDefaults {

    /**
     * OpenAI 兼容 base URL，调用方自行拼接 /embeddings。
     * 2026-10-01 改到本机 Ollama（127.0.0.1:11434 在听）。短文本探测 HTTP 200，维度 1024。
     */
    public static final String BASE_URL = "http://127.0.0.1:11434/v1";

    /** 本机 ollama list 的实际模型名，不是展示名 Qwen3-Embedding-0.6B。 */
    public static final String MODEL_NAME = "qwen3-embedding:0.6b";

    /** 无鉴权，空串。不复用对话模型密钥。 */
    public static final String API_KEY = "";

    /** 实测维度。 */
    public static final int DIMENSION = 1024;

    private BuiltinEmbeddingDefaults() {
    }
}
