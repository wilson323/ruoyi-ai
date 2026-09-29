package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.tenant.helper.TenantHelper;
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
 *   <li>端点归一化（2026-09-28 卡 80b0be1f）：embedEndpoint 兼容「全路径 …/v1/embeddings」
 *       与「base URL …/v1」两种配置写法——resolveEmbedConfig 读侧单点归一化（剥 /embeddings
 *       尾缀），Langchain4j 消费时统一按 base URL 拼 POST {base}/embeddings；保存/回显/上送
 *       不做改写（toView 原样），归一化只在消费口生效（单源，禁多处重复判断）。</li>
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
        // IPD 单企业私有部署（2026-09-23）：启动主线程 setDynamic('000000') 让 PlusTenantLineHandler.ignoreTable() 走 excludes 匹配分支。
        // HTTP 线程独立 ThreadLocal，需 @PostConstruct 预热（见 warmupTenantContext）。
        // R184-A：哨兵日志改 log.warn；不再用 System.err（不进 ELK）；try 范围缩到 Exception。
        try {
            org.ruoyi.common.tenant.helper.TenantHelper.setDynamic("000000");
        } catch (Exception e) {
            log.warn("[AI-STRAT-1-SCOPE] 构造器 setDynamic 跳过: {}", e.getMessage());
        }
    }

    @jakarta.annotation.PostConstruct
    void warmupTenantContext() {
        // IPD 单企业私有部署（2026-09-23）：预热租户上下文为 '000000'，使 HTTP 线程（继承启动上下文）
        // 与新启动线程都能拿到正确租户；TenantHelper.setDynamic(tenantId, global=true) 走 SaStorage 跨线程生效。
        try {
            org.ruoyi.common.tenant.helper.TenantHelper.setDynamic("000000");
            log.info("[AI-STRAT-1-SCOPE] PostConstruct warmupTenantContext getTenantId={}",
                org.ruoyi.common.tenant.helper.TenantHelper.getTenantId());
        } catch (Exception e) {
            log.warn("[AI-STRAT-1-SCOPE] PostConstruct 预热失败: {}", e.getMessage());
        }
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
            // IPD 单企业私有部署（2026-09-23）：TenantHelper.getTenantId() 在 IPD 会话中可能为 null，
            // PlusTenantLineHandler 返回 NullValue → SQL 追加 tenant_id IS NULL → ai_model_configs 走不到。
            // 启动时已 setDynamic('000000') 让 PlusTenantLineHandler.ignoreTable() 走 excludes 匹配分支。
            cfg = resolveEmbedConfig();
        } catch (Exception e) {
            // R184-A（2026-09-23）：输出完整堆栈便于定位根因（之前只 warn message 丢真相）
            log.warn("[AI-STRAT-1-SCOPE] 向量化跳过（配置不可用）: docId={} reason={} exClass={} exMsg={}",
                doc.getId(), e.getMessage(), e.getClass().getName(), e.getMessage());
            log.warn("[AI-STRAT-1-SCOPE] 向量化跳过堆栈", e);
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
     *
     * @param projectId 项目 ID（检索范围锚）
     * @param docType 文档类型过滤（nullable；null = 不过滤；非空按 doc_type 等值过滤，索引 idx_emb_doctype）
     * @param query 查询原文（裁 QUERY_MAX_CHARS）
     */
    public RetrievalContext retrieveContext(Long projectId, String docType, String query) {
        try {
            EmbedEndpoint cfg = TenantHelper.ignore(() -> resolveEmbedConfig());
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
            // AI-STRAT-1 Phase 2（2026-09-23）：docType 非空时按类型过滤，索引 idx_emb_doctype 走
            // 普通索引；docType 为空/null 时保留历史「同项目全类型」语义，向后兼容。
            LambdaQueryWrapper<AiDocEmbedding> wrapper = new LambdaQueryWrapper<AiDocEmbedding>()
                .eq(AiDocEmbedding::getProjectId, projectId)
                .eq(AiDocEmbedding::getEmbedModel, cfg.embedModel());
            if (docType != null && !docType.isBlank()) {
                wrapper.eq(AiDocEmbedding::getDocType, docType.trim());
            }
            List<AiDocEmbedding> candidates = embeddingMapper.selectList(wrapper);
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
        // 端点归一化（卡 80b0be1f）：全路径 …/v1/embeddings 与 base URL …/v1 均归一为 base URL，
        // 由 Langchain4j 统一拼 POST {base}/embeddings（全路径直传会拼成 /embeddings/embeddings → 404）。
        // 仅剥子路径不动 host/port——下游 AiGateway.embed 的 SSRF 前置校验语义不变。
        return new EmbedEndpoint(normalizeEmbedBaseUrl(endpoint), modelConfigService.decryptApiKey(config), model.trim());
    }

    /** embedding 端点三元组（包内值对象）。 */
    record EmbedEndpoint(String endpoint, String apiKey, String embedModel) { }

    /**
     * embedEndpoint 端点归一化（单源；卡 80b0be1f）：兼容「全路径 …/v1/embeddings」与
     * 「base URL …/v1」两种写法，产出 Langchain4j OpenAiEmbeddingModel 所需 base URL
     * （其内部固定拼 POST {base}/embeddings，见 DefaultOpenAiClient#embedding）。
     * <ul>
     *   <li>规则：trim → 剥全部尾部 / → 剥尾部 /embeddings（忽略大小写，幂等循环）→
     *       再剥尾部 /；base URL 形态原样透传（Langchain4j 自拼子路径，无需补挂）。</li>
     *   <li>SSRF 语义：仅剥 path 子段，host/port 不变——下游 AiGateway.embed 的
     *       ssrfCheck 前置校验等价（host 级）且更贴近实际请求目标。</li>
     *   <li>范围：仅消费口归一化；入库 config_json 与回显（toView）保持用户原样，
     *       避免编辑回显与保存值不一致的困惑（表单三态 merge 语义不受影响）。</li>
     * </ul>
     */
    static String normalizeEmbedBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String out = raw.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        String lower = out.toLowerCase(java.util.Locale.ROOT);
        // 幂等剥 /embeddings 尾缀（双写 …/embeddings/embeddings 一并归一）；每轮剥后重剥尾斜杠
        while (lower.endsWith("/embeddings")) {
            out = out.substring(0, out.length() - "/embeddings".length());
            while (out.endsWith("/")) {
                out = out.substring(0, out.length() - 1);
            }
            lower = out.toLowerCase(java.util.Locale.ROOT);
        }
        return out;
    }

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
