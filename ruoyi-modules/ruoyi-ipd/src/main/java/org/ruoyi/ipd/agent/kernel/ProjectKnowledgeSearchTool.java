package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
    static final String NO_HIT_TEXT = "未检索到本项目相关的已审核资料。请在输出中将相关项标注为“未取得”。";

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
        return "检索当前项目已审核的文档片段（仅本项目，只读）。每次只检索一个主题，如“竞品价格”。";
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
        String block = context == null ? "" : context.block();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("toolCallId", toolCallId);
        source.put("tool", getName());
        source.put("projectId", String.valueOf(boundProjectId));
        source.put("query", query);
        source.put("hits", hits);
        source.put("chars", context == null ? 0 : context.chars());
        source.put("preview", block.length() > PREVIEW_MAX_CHARS ? block.substring(0, PREVIEW_MAX_CHARS) : block);
        sourceListener.accept(source);
        return ToolResultBlock.text(hits == 0 ? NO_HIT_TEXT : block);
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
