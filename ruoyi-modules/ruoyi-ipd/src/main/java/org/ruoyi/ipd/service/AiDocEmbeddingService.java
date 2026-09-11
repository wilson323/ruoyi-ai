package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.AiDocEmbedding;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.mapper.AiDocEmbeddingMapper;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI-STRAT-1 项目文档向量化与检索（2026-09-11）。
 * <ul>
 *   <li>入库：ai_documents 审核通过（REVIEWED）即 {@link #embedAsync} 异步向量化——
 *       固定窗口切片 + AiGateway.embed（OpenAI 兼容 /embeddings）；失败只 WARN 不阻塞
 *       主流程（增强链路降级语义）；doc 级重建 = 先删后插（同 embedModel）。</li>
 *   <li>检索：{@link #retrieveContext} 同项目（含同 embedModel——向量空间一致性锚，
 *       换 embedding 模型后旧向量自动退出检索）余弦 top-K，预算内拼上下文块；
 *       任何异常返回 {@link RetrievalContext#EMPTY}（生成链 contextHits=0 照常走）。</li>
 *   <li>红线：BR-AI-04——切片原文只进 ai_doc_embeddings（业务库）与生成 prompt，
 *       不进审计（审计只记 contextHits/contextChars）；不进日志。</li>
 *   <li>RAG 开关：AiModelConfig.config_json 的 embedEndpoint/embedModel 两键齐全才启用；
 *       缺省 = RAG 关闭（静默跳过，检索返回 EMPTY）。embedding apiKey 复用主配置密文。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiDocEmbeddingService {

    /** 切片窗口（字符）：IPD 文档短（数 KB），固定窗口无重叠起步；过大片检索粒度差。 */
    static final int CHUNK_SIZE = 800;
    /** 检索 query 截断（生成 prompt 做查询语义锚，无需全文）。 */
    static final int QUERY_MAX_CHARS = 512;
    /** 检索 top-K 上限。 */
    static final int TOP_K = 3;
    /** 上下文预算（字符）：远小于 MAX_PROMPT_LEN(30000) 余量，双保险由 generate 钳制。 */
    static final int CONTEXT_BUDGET_CHARS = 4000;
    /** embedding 调用超时（独立于 chat 的 generateTimeoutMs）。 */
    static final int EMBED_TIMEOUT_MS = 30_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiDocEmbeddingMapper embeddingMapper;
    private final AiModelConfigService modelConfigService;
    private final AiGateway aiGateway;

    /** 单线程守护异步池：向量化串行化（IPD 文档量级足够；失败不阻塞业务线程）。 */
    private final ExecutorService embedExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ipd-ai-doc-embed");
        t.setDaemon(true);
        return t;
    });

    public AiDocEmbeddingService(AiDocEmbeddingMapper embeddingMapper,
                                 AiModelConfigService modelConfigService,
                                 AiGateway aiGateway) {
        this.embeddingMapper = embeddingMapper;
        this.modelConfigService = modelConfigService;
        this.aiGateway = aiGateway;
    }

    /** 检索结果（hits=命中片段数；chars=上下文块字符数；block=拼接好的注入块，EMPTY 时为 ""）。 */
    public record RetrievalContext(int hits, int chars, String block) {
        static final RetrievalContext EMPTY = new RetrievalContext(0, 0, "");
    }

    /**
     * 审核通过后触发异步向量化。RAG 未配置（embed 两键缺一）静默跳过；
     * 任何异常（无生效配置/解密失败/embed 调用失败）只 WARN——主流程不感知。
     */
    public void embedAsync(AiDocument doc) {
        if (doc == null || doc.getId() == null || doc.getContent() == null || doc.getContent().isBlank()) {
            return;
        }
        EmbedEndpoint cfg;
        try {
            cfg = resolveEmbedConfig();
        } catch (Exception e) {
            log.debug("[AI-STRAT-1] 向量化跳过（配置不可用）: docId={} reason={}", doc.getId(), e.getMessage());
            return;
        }
        if (cfg == null) {
            return;
        }
        AiDocument snapshot = doc;
        embedExecutor.submit(() -> {
            try {
                embedSync(snapshot, cfg);
            } catch (Exception e) {
                log.warn("[AI-STRAT-1] 向量化失败降级（不阻塞业务）: docId={} model={} error={}",
                    snapshot.getId(), cfg.embedModel(), e.getMessage());
            }
        });
    }

    /** 同步向量化（package-private 供单测）：切片 → embed → doc 级先删后插。 */
    void embedSync(AiDocument doc, EmbedEndpoint cfg) {
        List<String> chunks = splitChunks(doc.getContent());
        if (chunks.isEmpty()) {
            return;
        }
        List<float[]> vectors = aiGateway.embed(
            new AiTestConfig("openai", cfg.endpoint(), cfg.apiKey(), cfg.embedModel(), EMBED_TIMEOUT_MS), chunks);
        if (vectors == null) {
            log.warn("[AI-STRAT-1] embed 调用失败，本次跳过: docId={} model={}", doc.getId(), cfg.embedModel());
            return;
        }
        embeddingMapper.delete(new LambdaQueryWrapper<AiDocEmbedding>()
            .eq(AiDocEmbedding::getDocId, doc.getId())
            .eq(AiDocEmbedding::getEmbedModel, cfg.embedModel()));
        for (int i = 0; i < chunks.size(); i++) {
            embeddingMapper.insert(AiDocEmbedding.builder()
                .docId(doc.getId())
                .projectId(doc.getProjectId())
                .docType(doc.getDocType())
                .title(doc.getTitle())
                .chunkSeq(i)
                .chunkText(chunks.get(i))
                .embedModel(cfg.embedModel())
                .vectorJson(toJson(vectors.get(i)))
                .build());
        }
        log.info("[AI-STRAT-1] 向量化完成: docId={} projectId={} model={} chunks={}",
            doc.getId(), doc.getProjectId(), cfg.embedModel(), chunks.size());
    }

    /**
     * 同项目检索 top-K 相关片段并拼上下文块。异常一律 EMPTY（调用方 contextHits=0 生成照常）。
     * 块格式（来源标注 + 片段原文），供 generate 拼进 prompt。
     */
    public RetrievalContext retrieveContext(Long projectId, String query) {
        try {
            EmbedEndpoint cfg = resolveEmbedConfig();
            if (cfg == null || query == null || query.isBlank()) {
                return RetrievalContext.EMPTY;
            }
            String q = query.length() > QUERY_MAX_CHARS ? query.substring(0, QUERY_MAX_CHARS) : query;
            List<float[]> queryVec = aiGateway.embed(
                new AiTestConfig("openai", cfg.endpoint(), cfg.apiKey(), cfg.embedModel(), EMBED_TIMEOUT_MS),
                List.of(q));
            if (queryVec == null || queryVec.isEmpty() || queryVec.get(0) == null) {
                return RetrievalContext.EMPTY;
            }
            List<AiDocEmbedding> candidates = embeddingMapper.selectList(new LambdaQueryWrapper<AiDocEmbedding>()
                .eq(AiDocEmbedding::getProjectId, projectId)
                .eq(AiDocEmbedding::getEmbedModel, cfg.embedModel()));
            if (candidates.isEmpty()) {
                return RetrievalContext.EMPTY;
            }
            float[] qv = queryVec.get(0);
            record Scored(AiDocEmbedding emb, double score) { }
            List<Scored> scored = new ArrayList<>(candidates.size());
            for (AiDocEmbedding c : candidates) {
                float[] v = fromJson(c.getVectorJson());
                double s = cosine(qv, v);
                if (s > 0) {
                    scored.add(new Scored(c, s));
                }
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            StringBuilder sb = new StringBuilder();
            int hits = 0;
            for (Scored s : scored) {
                if (hits >= TOP_K) {
                    break;
                }
                String piece = "【相关历史文档片段 " + (hits + 1) + "｜" + orDash(s.emb().getDocType())
                    + "｜" + orDash(s.emb().getTitle()) + "】\n" + s.emb().getChunkText() + "\n";
                if (sb.length() + piece.length() > CONTEXT_BUDGET_CHARS) {
                    break;
                }
                sb.append(piece);
                hits++;
            }
            if (hits == 0) {
                return RetrievalContext.EMPTY;
            }
            return new RetrievalContext(hits, sb.length(), sb.toString());
        } catch (Exception e) {
            log.warn("[AI-STRAT-1] 检索降级（生成照常，contextHits=0）: projectId={} error={}",
                projectId, e.getMessage());
            return RetrievalContext.EMPTY;
        }
    }

    /** 解析生效配置的 embedding 端点（embedEndpoint/embedModel 两键齐全才启用；apiKey 复用主密文）。 */
    EmbedEndpoint resolveEmbedConfig() {
        var config = modelConfigService.currentEnabled();
        String endpoint = readKey(config.getConfigJson(), "embedEndpoint");
        String model = readKey(config.getConfigJson(), "embedModel");
        if (endpoint == null || endpoint.isBlank() || model == null || model.isBlank()) {
            return null;
        }
        // 构造序与 record 声明一致：(endpoint, apiKey, embedModel)——参数序错位会静默交换密钥与模型名
        return new EmbedEndpoint(endpoint.trim(), modelConfigService.decryptApiKey(config), model.trim());
    }

    /** embedding 端点三元组（包内值对象）。 */
    record EmbedEndpoint(String endpoint, String apiKey, String embedModel) { }

    /** 固定窗口切片（无重叠）：尾片不足窗口并入前片太碎，独立保留（语义完整优先）。 */
    static List<String> splitChunks(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (int i = 0; i < content.length(); i += CHUNK_SIZE) {
            int end = Math.min(content.length(), i + CHUNK_SIZE);
            String piece = content.substring(i, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
        }
        return out;
    }

    private static String readKey(String configJson, String key) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(configJson).path(key).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(float[] vector) {
        try {
            return JSON.writeValueAsString(vector);
        } catch (Exception e) {
            throw new IllegalStateException("vector serialize fail", e);
        }
    }

    private static float[] fromJson(String vectorJson) {
        try {
            return JSON.readValue(vectorJson, float[].class);
        } catch (Exception e) {
            return new float[0];
        }
    }

    /** 余弦相似度（维度不一致/零向量返回 -1，即不参与排序）。 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return -1;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return -1;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
