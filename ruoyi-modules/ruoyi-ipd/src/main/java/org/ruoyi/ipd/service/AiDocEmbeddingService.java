package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocEmbedding;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiDocEmbeddingMapper;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.ruoyi.ipd.service.ai.BuiltinEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI-STRAT-1 项目文档向量化与检索（2026-09-11）。
 * <ul>
 *   <li>入库：ai_documents 审核通过（REVIEWED）即 {@link #embedAsync} 异步向量化——
 *       固定窗口切片 + AiGateway.embed，经 EmbeddingModels 装配 AgentScope 原生嵌入客户端；失败只 WARN 不阻塞
 *       主流程（增强链路降级语义）；doc 级重建 = 先删后插（同 embedModel）。</li>
 *   <li>检索：{@link #retrieveContext} 同项目且当前仍为 REVIEWED 的未删除文档
 *       （含同 embedModel——向量空间一致性锚，
 *       换 embedding 模型后旧向量自动退出检索）余弦 top-K，预算内拼上下文块；
 *       生成和副驾入口将故障映射为可见业务错误；项目智能体使用严格入口保留原始故障。</li>
 *   <li>红线：BR-AI-04——切片原文只进 ai_doc_embeddings（业务库）与生成 prompt，
 *       不进审计（审计只记 contextHits/contextChars）；不进日志。</li>
 *   <li>RAG 配置：显式嵌入端点与模型两键齐全时保留已有配置兼容；两键全缺走
 *       {@link BuiltinEmbeddingModel}，不复用官方对话密钥；只填一键则 RAG 关闭。</li>
 *   <li>端点归一化（2026-09-28 卡 80b0be1f）：embedEndpoint 兼容「全路径 …/v1/embeddings」
 *       与「base URL …/v1」两种配置写法——resolveEmbedConfig 读侧单点归一化（剥 /embeddings
 *       尾缀），AgentScope 客户端按选定协议消费 base URL；本机内置模型使用 Ollama 原生客户端。保存/回显/上送
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
    private final AiDocumentMapper documentMapper;
    private final AiModelConfigService modelConfigService;
    private final AiGateway aiGateway;
    private final Environment environment;
    /** 内置向量首次生效只打一条 INFO。 */
    private final AtomicBoolean builtinLogged = new AtomicBoolean(false);

    /** 单线程守护异步池：向量化串行化（IPD 文档量级足够；失败不阻塞业务线程）。 */
    private final ExecutorService embedExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ipd-ai-doc-embed");
        t.setDaemon(true);
        return t;
    });
    /** 单测改为调用线程执行，避免任务尚未运行就断言。生产构造保持 false。 */
    private volatile boolean embedOnCaller;

    /** 单测入口：不注入 Environment 时内置向量默认开启。 */
    AiDocEmbeddingService(AiDocEmbeddingMapper embeddingMapper,
                          AiDocumentMapper documentMapper,
                          AiModelConfigService modelConfigService,
                          AiGateway aiGateway) {
        this(embeddingMapper, documentMapper, modelConfigService, aiGateway, null);
    }

    /**
     * 生产构造：对话模型与向量模型分开。官方对话行未填向量两键时走内置向量，空密钥。
     */
    @Autowired
    public AiDocEmbeddingService(AiDocEmbeddingMapper embeddingMapper,
                                 AiDocumentMapper documentMapper,
                                 AiModelConfigService modelConfigService,
                                 AiGateway aiGateway,
                                 Environment environment) {
        this.embeddingMapper = embeddingMapper;
        this.documentMapper = documentMapper;
        this.modelConfigService = modelConfigService;
        this.aiGateway = aiGateway;
        this.environment = environment;
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
    /** 来源身份取自当前查询权威，知识库片段不能借用项目文档审核状态。 */
    public record CitationSource(String sourceType, String documentId, String knowledgeId,
                                 String fragmentId, String sourceName, String reviewStatus) { }

    public record RetrievalContext(int hits, int chars, String block, String citationText,
                                   List<CitationSource> sources) {
        public RetrievalContext(int hits, int chars, String block, String citationText) {
            this(hits, chars, block, citationText, List.of());
        }
        /** 兼容旧成功来源；混有失败标记的旧结果不推断可引用正文。 */
        public RetrievalContext(int hits, int chars, String block) {
            this(hits, chars, block, hits > 0 && block != null
                && !block.contains("【知识库向量检索失败】") ? block : "");
        }

        public RetrievalContext {
            citationText = hits > 0 && citationText != null ? citationText : "";
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
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
        if (embedOnCaller) {
            try {
                embedSync(snapshot, cfg);
            } catch (Exception e) {
                log.warn("[AI-STRAT-1] 向量化失败降级（不阻塞业务）: docId={} model={} error={}",
                    snapshot.getId(), cfg.embedModel(), e.getMessage());
            }
            return;
        }
        embedExecutor.submit(() -> {
            try {
                embedSync(snapshot, cfg);
            } catch (Exception e) {
                log.warn("[AI-STRAT-1] 向量化失败降级（不阻塞业务）: docId={} model={} error={}",
                    snapshot.getId(), cfg.embedModel(), e.getMessage());
            }
        });
    }

    /**
     * 单测把异步向量化留在当前线程。两键都缺时会走内置向量，必须等这次调用结束再断言。
     */
    void completeEmbedOnCallerForTest() {
        embedOnCaller = true;
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
     * 同项目且当前仍为 REVIEWED 的文档检索 top-K 相关片段并拼上下文块。
     * 无命中返回 EMPTY；检索故障转换为业务错误，禁止冒充无命中继续生成。
     * 块格式（来源标注 + 片段原文），供 generate 拼进 prompt。
     *
     * @param projectId 项目 ID（检索范围锚）
     * @param docType 文档类型过滤（nullable；null = 不过滤；非空按 doc_type 等值过滤，索引 idx_emb_doctype）
     * @param query 查询原文（裁 QUERY_MAX_CHARS）
     */
    public RetrievalContext retrieveContext(Long projectId, String docType, String query) {
        try {
            return retrieveContextStrict(projectId, docType, query);
        } catch (IpdBusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[AI-STRAT-1] 检索失败: projectId={} errorType={}",
                projectId, e.getClass().getSimpleName());
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "项目文档检索失败，请稍后重试或联系管理员检查检索服务");
        }
    }


    /** 项目智能体严格检索入口：故障向上传递，不把故障当作未命中。 */
    public RetrievalContext retrieveContextStrict(Long projectId, String docType, String query) {
        if (projectId == null || query == null || query.isBlank()) {
            return RetrievalContext.EMPTY;
        }
        // ai_doc_embeddings 是快照，归档/拒绝/软删不会同步清理旧切片。
        // 必须以 ai_documents 当前 REVIEWED 行为准；MP @TableLogic 隐式排除 del_flag=1。
        LambdaQueryWrapper<AiDocument> documents = new LambdaQueryWrapper<AiDocument>()
            .select(AiDocument::getId)
            .eq(AiDocument::getProjectId, projectId)
            .eq(AiDocument::getStatus, AiDocumentService.STATUS_REVIEWED);
        if (docType != null && !docType.isBlank()) {
            documents.eq(AiDocument::getDocType, docType.trim());
        }
        List<AiDocument> approved = documentMapper.selectList(documents);
        if (approved == null || approved.isEmpty()) {
            return RetrievalContext.EMPTY;
        }
        Set<Long> approvedIds = approved.stream().map(AiDocument::getId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (approvedIds.isEmpty()) {
            return RetrievalContext.EMPTY;
        }
        EmbedEndpoint cfg = TenantHelper.ignore(() -> resolveEmbedConfig());
        if (cfg == null) {
            throw new IllegalStateException("文档向量配置不可用");
        }
        String q = query.length() > QUERY_MAX_CHARS ? query.substring(0, QUERY_MAX_CHARS) : query;
        List<float[]> queryVec = aiGateway.embed(
            new AiTestConfig("openai", cfg.endpoint(), cfg.apiKey(), cfg.embedModel(), EMBED_TIMEOUT_MS),
            List.of(q));
        if (queryVec == null || queryVec.isEmpty() || queryVec.get(0) == null) {
            throw new IllegalStateException("文档向量返回为空");
        }
        // AI-STRAT-1 Phase 2（2026-09-23）：docType 非空时按类型过滤，索引 idx_emb_doctype 走
        // 普通索引；docType 为空/null 时保留历史「同项目全类型」语义，向后兼容。
        LambdaQueryWrapper<AiDocEmbedding> wrapper = new LambdaQueryWrapper<AiDocEmbedding>()
            .eq(AiDocEmbedding::getProjectId, projectId)
            .eq(AiDocEmbedding::getEmbedModel, cfg.embedModel())
            .in(AiDocEmbedding::getDocId, approvedIds);
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
        List<CitationSource> sources = new ArrayList<>();
        int hits = 0;
        for (Scored s : scored) {
            if (hits >= TOP_K) {
                break;
            }
            if (s.emb().getDocId() == null || !approvedIds.contains(s.emb().getDocId())) continue;
            String piece = "【相关历史文档片段 " + (hits + 1) + "｜" + orDash(s.emb().getDocType())
                + "｜" + orDash(s.emb().getTitle()) + "｜sourceType=PROJECT_DOCUMENT｜documentId="
                + s.emb().getDocId() + "｜reviewStatus=REVIEWED】\n" + s.emb().getChunkText() + "\n";
            if (sb.length() + piece.length() > CONTEXT_BUDGET_CHARS) {
                break;
            }
            sb.append(piece);
            sources.add(new CitationSource("PROJECT_DOCUMENT", String.valueOf(s.emb().getDocId()),
                null, null, s.emb().getTitle(), AiDocumentService.STATUS_REVIEWED));
            hits++;
        }
        if (hits == 0) {
            return RetrievalContext.EMPTY;
        }
        return new RetrievalContext(hits, sb.length(), sb.toString(), sb.toString(), sources);
    }

    /**
     * 解析向量端点。官方对话行两键齐全才复用该行密钥；两键全缺走内置向量且密钥为空；
     * 只填一键视为配置不完整，RAG 关闭。
     */
    EmbedEndpoint resolveEmbedConfig() {
        AiModelConfig config;
        try {
            config = modelConfigService.currentEnabled();
        } catch (RuntimeException e) {
            log.warn("[AI-STRAT-1] 无生效对话配置，向量改走内置默认: {}", e.getMessage());
            return builtinEmbedEndpoint();
        }
        String endpoint = readKey(config.getConfigJson(), "embedEndpoint");
        String model = readKey(config.getConfigJson(), "embedModel");
        boolean endpointBlank = endpoint == null || endpoint.isBlank();
        boolean modelBlank = model == null || model.isBlank();
        if (endpointBlank && modelBlank) {
            return builtinEmbedEndpoint();
        }
        if (endpointBlank || modelBlank) {
            log.warn("[AI-STRAT-1] 向量两键只填了一键，RAG 关闭");
            return null;
        }
        // 构造序与 record 声明一致：(endpoint, apiKey, embedModel)
        // 端点归一化：全路径 …/v1/embeddings 与 base URL …/v1 均归一为 base URL。
        return new EmbedEndpoint(normalizeEmbedBaseUrl(endpoint), modelConfigService.decryptApiKey(config), model.trim());
    }

    /** 内置向量：空密钥，不把官方对话模型的密钥发往向量端点。开关关闭时返回 null。 */
    private EmbedEndpoint builtinEmbedEndpoint() {
        if (environment != null && !Boolean.TRUE.equals(environment.getProperty(
            BuiltinEmbeddingModel.ENABLED_PROPERTY, Boolean.class, Boolean.TRUE))) {
            return null;
        }
        if (builtinLogged.compareAndSet(false, true)) {
            log.info("[AI-STRAT-1] 官方对话模型未配置向量键，使用内置向量 source={} model={}",
                BuiltinEmbeddingModel.SOURCE, BuiltinEmbeddingModel.MODEL_NAME);
        }
        return new EmbedEndpoint(BuiltinEmbeddingModel.BASE_URL, BuiltinEmbeddingModel.API_KEY,
            BuiltinEmbeddingModel.MODEL_NAME);
    }

    /** embedding 端点三元组（包内值对象）。 */
    record EmbedEndpoint(String endpoint, String apiKey, String embedModel) { }

    /**
     * embedEndpoint 端点归一化（单源；卡 80b0be1f）：兼容「全路径 …/v1/embeddings」与
     * 「base URL …/v1」两种写法，产出 AgentScope EmbeddingModels 消费的 base URL。
     * 内置 Qwen3 由同一装配口选择 OllamaTextEmbedding；其他兼容端点使用 OpenAITextEmbedding。
     * <ul>
     *   <li>规则：trim → 剥全部尾部 / → 剥尾部 /embeddings（忽略大小写，幂等循环）→
     *       再剥尾部 /；base URL 形态原样透传，由对应 AgentScope 客户端处理协议子路径。</li>
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
