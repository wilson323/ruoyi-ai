package org.ruoyi.ipd.agent.kernel;

import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;

import java.util.Objects;

/**
 * 同一个项目资料检索入口。已审核文档向量、知识库向量和产品知识库正文都走这里。
 * 知识库向量失败写成可见失败，正文检索仍然执行。不另开第二个检索工具。
 */
public final class ProjectKnowledgeRetriever implements ProjectKnowledgeSearchTool.KnowledgeRetriever {

    private final ProjectKnowledgeSearchTool.KnowledgeRetriever vectors;
    private final ProjectKnowledgeFragmentTextSearch textSearch;
    private final ProjectKnowledgeVectorSearch.Search knowledgeVectors;

    /**
     * 只接已审核文档向量和正文。知识库向量不参与。供已有正文测试使用。
     *
     * @param vectors 已审核文档向量检索，即 {@code AiDocEmbeddingService#retrieveContext}
     * @param textSearch 产品知识库正文检索，不因向量失败而跳过
     */
    public ProjectKnowledgeRetriever(ProjectKnowledgeSearchTool.KnowledgeRetriever vectors,
                                     ProjectKnowledgeFragmentTextSearch textSearch) {
        this(vectors, textSearch, (projectId, personId, query) -> new RetrievalContext(0, 0, ""));
    }

    /**
     * @param vectors 已审核文档向量检索
     * @param textSearch 产品知识库正文检索
     * @param knowledgeVectors 已有知识库向量检索；失败必须可见
     */
    public ProjectKnowledgeRetriever(ProjectKnowledgeSearchTool.KnowledgeRetriever vectors,
                                     ProjectKnowledgeFragmentTextSearch textSearch,
                                     ProjectKnowledgeVectorSearch.Search knowledgeVectors) {
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.textSearch = Objects.requireNonNull(textSearch, "textSearch");
        this.knowledgeVectors = Objects.requireNonNull(knowledgeVectors, "knowledgeVectors");
    }

    /**
     * 带上本次运行已经鉴过的人员。工具接口只有项目和查询，由内核在绑定运行时传入人员。
     * 知识库向量抛错或被门禁拒绝时保留失败文本，正文仍然返回。每个来源失败都保留安全失败标记，不丢弃其他来源的成功资料。
     *
     * @param personId 运行发起人；空表示这次调用没有身份
     * @param projectId 绑定项目
     * @param docType 文档类型过滤，只作用于已审核文档向量
     * @param query 检索词
     * @return 合并后的检索块
     */
    public RetrievalContext retrieve(Long personId, Long projectId, String docType, String query) {
        RetrievalContext knowledge;
        try {
            knowledge = knowledgeVectors.search(projectId, personId, query);
        } catch (Throwable ex) {
            if (ex instanceof VirtualMachineError) {
                throw (VirtualMachineError) ex;
            }
            // LinkageError 不是 RuntimeException。漏掉它时 Reactor 会当成致命错误丢掉，
            // 工具体没有 onError，整轮就空转到超时。
            String message = ProjectKnowledgeVectorSearch.safeFailureMessage(ex);
            String block = ProjectKnowledgeVectorSearch.FAILURE_MARK + message;
            knowledge = new RetrievalContext(0, block.length(), block);
        }
        RetrievalContext text;
        try {
            text = textSearch.search(projectId, query);
        } catch (RuntimeException failure) {
            text = failed("产品知识库正文检索失败");
        }
        RetrievalContext docs;
        try {
            docs = vectors.retrieve(projectId, docType, query);
        } catch (RuntimeException failure) {
            docs = failed("已审核文档向量检索失败");
        }
        return merge(merge(knowledge, text), docs);
    }

    private static RetrievalContext failed(String message) {
        String block = ProjectKnowledgeVectorSearch.FAILURE_MARK + message + "\n";
        return new RetrievalContext(0, block.length(), block);
    }

    /**
     * 没有人员时仍走检索，向量门禁按空身份拒绝。
     *
     * @param projectId 绑定项目
     * @param docType 文档类型过滤，只作用于已审核文档向量
     * @param query 检索词
     * @return 合并后的检索块
     */
    @Override
    public RetrievalContext retrieve(Long projectId, String docType, String query) {
        return retrieve(null, projectId, docType, query);
    }

    /**
     * 前一段放前面。失败块即使 hits 为 0 也保留，避免把检索故障写成没有资料。
     *
     * @param first 知识库向量或已合并块
     * @param second 正文或已审核文档向量
     * @return 合并结果
     */
    static RetrievalContext merge(RetrievalContext first, RetrievalContext second) {
        boolean keepFirst = keep(first);
        boolean keepSecond = keep(second);
        if (!keepFirst && !keepSecond) {
            return new RetrievalContext(0, 0, "");
        }
        if (!keepSecond) {
            return first;
        }
        if (!keepFirst) {
            return second;
        }
        String block = first.block() + second.block();
        var sources = new java.util.ArrayList<>(first.sources());
        sources.addAll(second.sources());
        return new RetrievalContext(first.hits() + second.hits(), block.length(), block,
            first.citationText() + second.citationText(), sources);
    }

    /**
     * 有命中，或块里写了知识库向量失败，都要留下。
     *
     * @param context 一段检索结果
     * @return 是否进入合并结果
     */
    private static boolean keep(RetrievalContext context) {
        if (context == null || context.block() == null || context.block().isEmpty()) {
            return false;
        }
        return context.hits() > 0 || context.block().contains(ProjectKnowledgeVectorSearch.FAILURE_MARK);
    }
}
