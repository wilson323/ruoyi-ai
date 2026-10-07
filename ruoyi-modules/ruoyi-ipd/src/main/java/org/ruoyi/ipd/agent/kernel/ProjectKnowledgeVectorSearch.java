package org.ruoyi.ipd.agent.kernel;

import lombok.Data;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.knowledge.KnowledgeEmbedEndpoint;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;

import java.util.List;
import java.util.Objects;

/**
 * 同一个项目资料检索入口上的知识库向量检索。
 * 只调用已有 {@link KnowledgeRetrievalService}，不新建 Weaviate 客户端。
 * 有人员时，可见范围与正文 SQL 相同：当前项目的库，或 user_id=0 且未挂项目的系统产品库。
 * 不再套「归属人或 share=1」，否则同一个人读得到正文、向量却无权。
 * 没有人员时仍走 {@link KnowledgeAccessGate}，拒绝必须可见。
 * 门禁拒绝或检索抛错时写入可见失败，不返回空块假装没有资料。
 */
public final class ProjectKnowledgeVectorSearch {

    /** 失败块开头。事件里凭这个和正文命中区分。 */
    public static final String FAILURE_MARK = "【知识库向量检索失败】";

    /** 向量命中开头。与正文的「产品知识片段」不是同一标记。 */
    public static final String HIT_MARK = "【知识库向量｜";

    private static final int MAX_RESULTS = 4;
    private static final int SNIPPET_CHARS = 800;
    private static final String DEFAULT_VECTOR_STORE = "weaviate";

    private final KnowledgeAccessGate gate;
    private final KnowledgeRetrievalService retrieval;
    private final ScopeLookup scopes;
    private final LiveIdentityLookup identities;

    /**
     * @param gate 已有读面门禁，拒绝即失败
     * @param retrieval 已有知识库向量检索
     * @param scopes 当前项目范围内的知识库，不含按配置隐藏的条件
     * @param identities 用当前片段和附件核对向量身份，不能拿知识库 ID 充当文档
     */
    public ProjectKnowledgeVectorSearch(KnowledgeAccessGate gate,
                                        KnowledgeRetrievalService retrieval,
                                        ScopeLookup scopes,
                                        LiveIdentityLookup identities) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /**
     * 对范围内、且门禁放行的知识库做向量检索。
     *
     * @param projectId 绑定项目
     * @param personId 本次运行已经鉴过的人员；空则由门禁按未登录拒绝
     * @param query 检索词
     * @return 向量命中或可见失败；范围内没有库时 hits 为 0 且正文为空
     */
    public RetrievalContext search(Long projectId, Long personId, String query) {
        if (projectId == null || query == null || query.isBlank()) {
            return new RetrievalContext(0, 0, "");
        }
        List<Scope> rows;
        try {
            rows = scopes.list(projectId);
        } catch (RuntimeException ex) {
            return failure(ex);
        }
        if (rows == null || rows.isEmpty()) {
            // 2026-10-07 修：此处**从未向量化库发过请求**，却和「参数无效」(L65)、
            // 「查了但库里确实没有」(L130) 返回**完全相同的空结果**；
            // 而 L359 的 WeaviateVectorStoreStrategy 在真连不上时是会抛
            // 「知识库向量查询不可用」的 —— **被这一层提前短路把报错吃掉了**。
            //
            // 后果：向量库没启动 / 知识库从未导入资料 / 代码真的报错了，
            // 三者在下游**完全无法区分**，用户与 AI 只看到「未取得」。
            // 本仓 CLAUDE.md 记载的「RAG 静默返回空」即由此而来。
            //
            // 修法：让「没有可用知识库」带独立标记，与「查了没有」区分开。
            // 这样即使向量库仍不可用，日志与 AI 回答里也**会说出真实原因**。
            //
            // ⚠️ 该文本会进入 AI 上下文并被渲染给用户，**只说业务事实、不出现表名/实现细节**
            //    （commit 安全审查 2026-10-07 指出；初版写了 "knowledge_info 无记录" 已删）。
            return new RetrievalContext(0, 0, "",
                    "【无可用知识库】该项目尚未导入任何资料，本次未发起检索。"
                            + "若期望智能体能引用项目资料，请先在「资料库」中为该项目创建知识库并上传解析；"
                            + "若资料已导入，则说明检索服务当前不可用，请联系管理员。");
        }
        StringBuilder hits = new StringBuilder();
        StringBuilder failures = new StringBuilder();
        var sources = new java.util.ArrayList<org.ruoyi.ipd.service.AiDocEmbeddingService.CitationSource>();
        int count = 0;
        for (Scope scope : rows) {
            if (scope == null || scope.getKnowledgeId() == null) {
                continue;
            }
            Long knowledgeId = scope.getKnowledgeId();
            if (personId == null) {
                try {
                    // 没有本次运行的人员。双参门禁按未登录拒绝，错误留在结果里。
                    gate.checkRetrievalAccess(knowledgeId, null);
                } catch (RuntimeException denied) {
                    appendFailure(failures, knowledgeId, denied);
                    continue;
                }
            }
            try {
                List<KnowledgeRetrievalVo> found = retrieval.retrieve(queryOf(scope, query.trim()), personId);
                if (found == null) {
                    continue;
                }
                for (KnowledgeRetrievalVo row : found) {
                    if (count >= MAX_RESULTS || row == null || row.getContent() == null || row.getContent().isBlank()) {
                        continue;
                    }
                    String docId = row.getDocId();
                    if (docId == null || docId.isBlank()) {
                        appendMissing(failures, knowledgeId, "");
                        continue;
                    }
                    String fragmentKey = row.getId();
                    if (fragmentKey == null || fragmentKey.isBlank()) {
                        appendMissing(failures, knowledgeId, docId);
                        continue;
                    }
                    ProjectKnowledgeFragmentHit live = identities.find(knowledgeId, docId, fragmentKey);
                    if (!authoritative(live, knowledgeId, docId)) {
                        appendMissing(failures, knowledgeId, docId);
                        continue;
                    }
                    hits.append(formatHit(row, live));
                    sources.add(new org.ruoyi.ipd.service.AiDocEmbeddingService.CitationSource(
                        "KNOWLEDGE_FRAGMENT", live.getDocId(), String.valueOf(live.getKnowledgeId()),
                        String.valueOf(live.getFragmentId()), live.getAttachName().trim(),
                        "NOT_PROJECT_DOCUMENT"));
                    count++;
                }
            } catch (RuntimeException ex) {
                appendFailure(failures, knowledgeId, ex);
            }
        }
        if (count == 0 && failures.isEmpty()) {
            return new RetrievalContext(0, 0, "");
        }
        String block = failures + hits.toString();
        return new RetrievalContext(count, block.length(), block, hits.toString(), sources);
    }

    /**
     * 组装已有检索参数。嵌入名为空时用写入侧同一套内置解析，不查对话模型表。
     *
     * @param scope 知识库行
     * @param query 检索词
     * @return 纯向量查询，不打开混合检索
     */
    private static QueryVectorBo queryOf(Scope scope, String query) {
        KnowledgeEmbedEndpoint.Choice choice = KnowledgeEmbedEndpoint.resolve(scope.getEmbeddingModel());
        QueryVectorBo bo = new QueryVectorBo();
        bo.setKid(String.valueOf(scope.getKnowledgeId()));
        bo.setQuery(query);
        bo.setMaxResults(MAX_RESULTS);
        bo.setEnableHybrid(false);
        bo.setEmbeddingModelName(choice.modelName());
        bo.setBaseUrl(choice.baseUrl());
        String vectorStore = scope.getVectorModel();
        bo.setVectorModelName(vectorStore == null || vectorStore.isBlank() ? DEFAULT_VECTOR_STORE : vectorStore.trim());
        return bo;
    }

    /**
     * 向量命中带当前附件身份。文档号、片段号和名称只取实时查询，不用知识库 ID 顶替。
     *
     * @param row 一条向量结果，正文来自这次命中
     * @param live 当前仍连着附件的片段
     * @return 带来源的一块文本
     */
    private static String formatHit(KnowledgeRetrievalVo row, ProjectKnowledgeFragmentHit live) {
        String content = row.getContent();
        if (content.length() > SNIPPET_CHARS) {
            content = content.substring(0, SNIPPET_CHARS);
        }
        return HIT_MARK + "出处：" + live.getAttachName().trim() + "｜文档：" + live.getDocId()
            + "｜sourceType=KNOWLEDGE_FRAGMENT｜knowledgeId=" + live.getKnowledgeId()
            + "｜documentId=" + live.getDocId()
            + "｜fragmentId=" + live.getFragmentId()
            + "｜reviewStatus=NOT_PROJECT_DOCUMENT】\n" + content + "\n";
    }

    /**
     * 当前片段必须仍属于这次查询的知识库，文档号一致，并且附件有名称。
     *
     * @param live 实时身份，没有行时为 null
     * @param knowledgeId 本次查询的知识库
     * @param docId 向量载荷里的文档号
     * @return true 表示可以引用
     */
    private static boolean authoritative(ProjectKnowledgeFragmentHit live, Long knowledgeId, String docId) {
        return live != null
            && live.getFragmentId() != null
            && knowledgeId.equals(live.getKnowledgeId())
            && docId.equals(live.getDocId())
            && live.getAttachName() != null
            && !live.getAttachName().isBlank()
            && !"未知来源".equals(live.getAttachName().trim());
    }

    /**
     * 附件不存在、片段已删除，或向量只带回知识库 ID 时，不能编一个出处。
     *
     * @param failures 已收集的失败文本
     * @param knowledgeId 知识库
     * @param docId 向量文档号，可空
     */
    private static void appendMissing(StringBuilder failures, Long knowledgeId, String docId) {
        if (failures.isEmpty()) {
            failures.append(FAILURE_MARK);
        }
        failures.append("知识库 ").append(knowledgeId).append("：文档 ").append(docId)
            .append(" 的原始出处缺失或已失效，不能作为引用证据\n");
    }

    /**
     * 把门禁拒绝或检索异常写成可见失败，不当成没有命中。
     *
     * @param failures 已收集的失败文本
     * @param knowledgeId 知识库
     * @param ex 门禁或检索异常
     */
    private static void appendFailure(StringBuilder failures, Long knowledgeId, RuntimeException ex) {
        if (failures.isEmpty()) {
            failures.append(FAILURE_MARK);
        }
        String message = safeFailureMessage(ex);
        failures.append("知识库 ").append(knowledgeId).append("：").append(message).append('\n');
    }

    /**
     * 列库本身失败。
     *
     * @param ex 查询异常
     * @return 可见失败块
     */
    private static RetrievalContext failure(RuntimeException ex) {
        String message = safeFailureMessage(ex);
        String block = FAILURE_MARK + message;
        return new RetrievalContext(0, block.length(), block);
    }

    /** 业务可见错误仅保留权限类别，不透出连接地址、鉴权或底层响应。 */
    static String safeFailureMessage(Throwable ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (message.contains("未登录")) {
            return "未登录或会话已失效，无权访问该知识库";
        }
        if (message.contains("无权") || message.contains("没有权限")
            || message.contains("禁止访问") || message.contains("FORBIDDEN")) {
            return "无权访问该知识库";
        }
        return "知识库向量检索不可用";
    }

    /**
     * 知识库向量检索端口。失败时返回带失败标记的块，不抛成空结果。
     */
    @FunctionalInterface
    public interface Search {
        /**
         * @param projectId 绑定项目
         * @param personId 本次运行已经鉴过的人员；空则由门禁按未登录拒绝
         * @param query 检索词
         * @return 向量命中或可见失败
         */
        RetrievalContext search(Long projectId, Long personId, String query);
    }

    /**
     * 按知识库、文档号和片段键核对当前仍有附件的片段。
     * 没有行表示向量来源已过期或缺出处，调用方必须记为失败，不能补名称。
     */
    @FunctionalInterface
    public interface LiveIdentityLookup {
        /**
         * @param knowledgeId 本次向量查询的知识库
         * @param docId 向量载荷里的文档标识
         * @param fragmentKey 向量片段标识，可空
         * @return 当前附件仍在的片段；没有行时返回 null
         */
        ProjectKnowledgeFragmentHit find(long knowledgeId, String docId, String fragmentKey);
    }

    /**
     * 按项目列出候选知识库。
     */
    @FunctionalInterface
    public interface ScopeLookup {
        /**
         * @param projectId 当前项目
         * @return 范围内的知识库
         */
        List<Scope> list(long projectId);
    }

    /**
     * 范围内的一条知识库。嵌入模型和向量库名允许为空。
     */
    @Data
    public static class Scope {
        /** 知识库主键。 */
        private Long knowledgeId;
        /** 嵌入模型名，空则走内置解析。 */
        private String embeddingModel;
        /** 向量库名，空则使用已配置的 weaviate。 */
        private String vectorModel;
    }
}
