package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 只读工具：检索本项目已审核文档片段（包装 {@code AiDocEmbeddingService.retrieveContext}）。
 *
 * <p><b>项目硬隔离</b>：项目 ID 在构造时由运行绑定（来自已授权的运行行），参数 schema 不暴露
 * projectId，模型传入的任何 projectId/tenantId 字段一律忽略。执行前必须经
 * {@code KernelGovernedTool} 的原生 checkPermissions → {@code ToolPolicyEngine} 裁决；
 * 本类不做授权判断，只承担检索执行。
 */
public final class ProjectKnowledgeSearchTool implements AgentTool {

    /** 检索端口（生产 = AiDocEmbeddingService::retrieveContext）。 */
    @FunctionalInterface
    public interface KnowledgeRetriever {
        /**
         * @param projectId 绑定项目
         * @param docType 文档类型过滤（可空）
         * @param query 查询
         * @return 检索上下文
         */
        RetrievalContext retrieve(Long projectId, String docType, String query);
    }

    /** 来源摘要预览上限（字符）。 */
    static final int PREVIEW_MAX_CHARS = 1000;
    /** 未命中时返回给模型的文本。 */
    static final String NO_HIT_TEXT = "未检索到本次权限范围内的项目文档或产品知识资料。请在输出中将相关项标注为“未取得”。";
    static final String DESCRIPTION = "只读检索当前项目已审核文档，以及本次权限内的项目知识库和系统产品知识库。"
        + "按返回标记区分文档与知识库出处，不能将知识库片段说成项目已审核文档；"
        + "其他产品的资料不代表当前项目产品的事实。每次只检索一个主题，如“竞品价格”。";

    private final Long boundProjectId;
    private final KnowledgeRetriever retriever;
    private final Consumer<Map<String, Object>> sourceListener;

    /**
     * @param boundProjectId 运行绑定的项目 ID（唯一检索范围）
     * @param retriever 检索端口
     * @param sourceListener 真实检索结果上报（写 SOURCE 事件）
     */
    public ProjectKnowledgeSearchTool(Long boundProjectId, KnowledgeRetriever retriever,
                                      Consumer<Map<String, Object>> sourceListener) {
        this.boundProjectId = Objects.requireNonNull(boundProjectId, "boundProjectId");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.sourceListener = sourceListener == null ? s -> { } : sourceListener;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH;
    }

    /** {@inheritDoc} */
    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> query = Map.of("type", "string", "description", "检索主题关键词或问题");
        Map<String, Object> docType = Map.of("type", "string", "description", "可选：文档类型过滤");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("query", query, "docType", docType));
        schema.put("required", List.of("query"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * 执行检索（仅在治理层已裁决 ALLOW 后可达）。
     *
     * @param param 原生调用参数
     * @return 检索文本结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        String query = stringArg(input, "query");
        String docType = stringArg(input, "docType");
        String toolCallId = param == null || param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
        if (query == null) {
            return Mono.just(ToolResultBlock.error("query 为必填参数"));
        }
        return Mono.fromCallable(() -> search(query, docType, toolCallId))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock search(String query, String docType, String toolCallId) {
        RetrievalContext context = retriever.retrieve(boundProjectId, docType, query);
        int hits = context == null ? 0 : context.hits();
        String block = context == null || context.block() == null ? "" : context.block();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("toolCallId", toolCallId);
        source.put("tool", getName());
        source.put("projectId", String.valueOf(boundProjectId));
        source.put("query", query);
        source.put("hits", hits);
        source.put("retrievalStatus", retrievalStatus(context));
        source.put("chars", context == null ? 0 : context.chars());
        source.put("preview", block.length() > PREVIEW_MAX_CHARS ? block.substring(0, PREVIEW_MAX_CHARS) : block);
        source.put("citationText", context == null ? "" : context.citationText());
        source.put("citationStatus", context != null && !context.citationText().isBlank() ? "SUCCESS" : "NONE");
        source.put("sourceEvidence", sourceEvidence(context));
        sourceListener.accept(source);
        return result(context);
    }

    /**
     * 原生及同步适配器共用同一逐条来源合同。
     * 知识库命中必须带真实片段号，并且不能标成 REVIEWED。缺字段的行不进入证据。
     *
     * @param context 检索结果
     * @return SOURCE 的 sourceEvidence
     */
    static List<Map<String, Object>> sourceEvidence(RetrievalContext context) {
        if (context == null) {
            return List.of();
        }
        List<Map<String, Object>> identities = new ArrayList<>();
        for (var item : context.sources()) {
            if (item == null || item.sourceType() == null || item.sourceType().isBlank()
                || item.documentId() == null || item.documentId().isBlank()
                || item.reviewStatus() == null || item.reviewStatus().isBlank()
                || item.sourceName() == null || item.sourceName().isBlank()) {
                continue;
            }
            if ("KNOWLEDGE_FRAGMENT".equals(item.sourceType())
                && (item.knowledgeId() == null || item.knowledgeId().isBlank()
                || item.fragmentId() == null || item.fragmentId().isBlank()
                || "REVIEWED".equals(item.reviewStatus()))) {
                continue;
            }
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("sourceType", item.sourceType());
            identity.put("documentId", item.documentId());
            identity.put("knowledgeId", item.knowledgeId());
            identity.put("fragmentId", item.fragmentId());
            identity.put("sourceName", item.sourceName());
            identity.put("reviewStatus", item.reviewStatus());
            identities.add(identity);
        }
        return identities;
    }

    /**
     * 区分可引用命中、没有资料、检索失败和无权。
     * 有命中又带失败标记时仍是部分成功。
     *
     * @param context 检索结果
     * @return SUCCESS、PARTIAL、NO_HIT、FAILED 或 UNAUTHORIZED
     */
    static String retrievalStatus(RetrievalContext context) {
        String block = context == null || context.block() == null ? "" : context.block();
        boolean failed = block.contains(ProjectKnowledgeVectorSearch.FAILURE_MARK);
        boolean hasHits = context != null && context.hits() > 0;
        if (failed && !hasHits) {
            return isDenied(block) ? "UNAUTHORIZED" : "FAILED";
        }
        if (!hasHits) {
            return "NO_HIT";
        }
        return failed ? "PARTIAL" : "SUCCESS";
    }

    /**
     * 同一结果映射供原生工具及内核同步工具使用。
     * 四类结果分开写：命中原句、未取得、失败、无权。
     *
     * @param context 检索结果
     * @return 给模型的工具结果
     */
    static ToolResultBlock result(RetrievalContext context) {
        String status = retrievalStatus(context);
        String block = context == null || context.block() == null ? "" : context.block();
        if ("FAILED".equals(status)) {
            return ToolResultBlock.error("失败：" + block);
        }
        if ("UNAUTHORIZED".equals(status)) {
            return ToolResultBlock.error("无权：" + block);
        }
        if ("NO_HIT".equals(status)) {
            return ToolResultBlock.text("未取得：\n" + NO_HIT_TEXT);
        }
        StringBuilder text = new StringBuilder();
        if ("PARTIAL".equals(status)) {
            text.append("部分成功：已有命中；限制仅影响对应来源，不代表所有资料缺少出处。\n")
                .append("检索限制（不作引用）：\n").append(failureDescriptions(block)).append('\n');
        }
        String citation = context == null ? "" : context.citationText();
        if (citation.isBlank()) {
            text.append("未取得可独立引用的原句；不得从混合结果推断引用证据。");
        } else {
            text.append("命中原句：\n").append(citation);
        }
        return ToolResultBlock.text(text.toString());
    }

    /** 仅显示失败段；真实来源正文只从独立 citationText 输出一次。 */
    private static String failureDescriptions(String block) {
        Pattern failures = Pattern.compile(Pattern.quote(ProjectKnowledgeVectorSearch.FAILURE_MARK)
            + "[\\s\\S]*?(?=" + Pattern.quote(ProjectKnowledgeVectorSearch.FAILURE_MARK)
            + "|【(?:知识库向量｜|产品知识片段 |相关历史文档片段(?: |｜))|\\z)");
        var matcher = failures.matcher(block);
        StringBuilder descriptions = new StringBuilder();
        while (matcher.find()) {
            if (!descriptions.isEmpty()) {
                descriptions.append('\n');
            }
            descriptions.append(matcher.group().trim());
        }
        return descriptions.toString();
    }

    /**
     * 无权和检索失败不是同一件事。只认明确的无权说法。
     *
     * @param text 失败块
     * @return true 表示无权
     */
    private static boolean isDenied(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("无权")
            || text.contains("未登录")
            || text.contains("没有权限")
            || text.contains("禁止访问")
            || text.contains("FORBIDDEN");
    }

    private static String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
