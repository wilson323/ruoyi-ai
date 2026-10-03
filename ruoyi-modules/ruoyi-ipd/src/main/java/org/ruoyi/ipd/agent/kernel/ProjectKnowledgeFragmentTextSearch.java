package org.ruoyi.ipd.agent.kernel;

import org.ruoyi.ipd.mapper.ProjectKnowledgeFragmentMapper;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 产品知识库正文检索。向量库不可用时仍按标题或正文关键词命中。
 * 不调用嵌入，不改附件状态，不把索引进度写成已完成。
 * 嵌入模型或向量库已经填写的行同样可读。查询失败向上抛出，不伪装成没有资料。
 */
public final class ProjectKnowledgeFragmentTextSearch {

    /** 与 {@code IpdPublicKnowledgeService} 相同的单企业知识表租户。 */
    public static final long KNOWLEDGE_TENANT_ID = 0L;

    private static final int MAX_QUERY_CHARS = 80;
    private static final int MIN_QUERY_CHARS = 2;
    private static final int LIMIT = 4;
    private static final int CANDIDATE_LIMIT = 24;
    private static final int SNIPPET_CHARS = 2200;
    private static final int MAX_KEYWORDS = 12;
    private static final int WINDOW_CHARS = 4;

    /** 整句问题里切掉的虚词。不包含竞品名用字。长词在前。 */
    private static final String[] STOP_WORDS = {
        "技术方案", "知识库", "请问", "一下", "什么", "怎么", "如何", "哪些", "哪个", "多少",
        "以及", "还有", "我们", "你们", "他们", "这个", "那个", "一个", "关于", "对比", "比较",
        "分析", "介绍", "说明", "情况", "分别", "其中", "如果", "因为", "所以", "可以", "能够",
        "是否", "还是", "并且", "而且", "或者", "不是", "没有", "已经", "正在", "根据", "产品",
        "的", "了", "是", "在", "和", "与", "及", "或", "吗", "呢", "把", "被", "对", "从",
        "为", "就", "都", "也", "很", "更", "最", "这", "那", "请", "问", "谁", "哪", "有", "要", "会"
    };

    private final ProjectKnowledgeFragmentMapper mapper;

    /**
     * @param mapper 只读查询
     */
    public ProjectKnowledgeFragmentTextSearch(ProjectKnowledgeFragmentMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 检索当前项目允许查看的产品知识库片段。
     *
     * @param projectId 绑定项目
     * @param query 用户问题或主题；按关键词命中，不是整句语义检索
     * @return 带来源的检索块；没有命中时 hits 为 0
     * @throws IllegalStateException 查询失败时抛出，不返回空列表
     */
    public RetrievalContext search(Long projectId, String query) {
        List<String> terms = keywords(query);
        if (projectId == null || terms.isEmpty()) {
            return empty();
        }
        List<String> patterns = terms.stream().map(ProjectKnowledgeFragmentTextSearch::likePattern).toList();
        List<ProjectKnowledgeFragmentHit> rows;
        try {
            rows = mapper.search(KNOWLEDGE_TENANT_ID, projectId, patterns, CANDIDATE_LIMIT);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("产品知识库正文检索失败", ex);
        }
        return format(rows, terms);
    }

    /**
     * 从问题里抽出关键词。2 到 6 字的整词保留；更长的片段按 4 字滑窗，
     * 使「请对比一下海康威视单卡收入是多少」能抽出「海康威视」。
     * 不把整句问题当成一条 LIKE。
     *
     * @param query 原始问题
     * @return 关键词，可能为空
     */
    static List<String> keywords(String query) {
        String term = normalize(query);
        if (term == null) {
            return List.of();
        }
        String spaced = term.replaceAll("[\\p{P}\\p{S}]+", " ");
        for (String stop : STOP_WORDS) {
            spaced = spaced.replace(stop, " ");
        }
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String piece : spaced.trim().split("\\s+")) {
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.length() >= MIN_QUERY_CHARS && piece.length() <= 6) {
                found.add(piece);
            } else if (piece.length() > 6) {
                for (int i = 0; i + WINDOW_CHARS <= piece.length() && found.size() < MAX_KEYWORDS; i++) {
                    found.add(piece.substring(i, i + WINDOW_CHARS));
                }
            }
            if (found.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return List.copyOf(found);
    }

    /**
     * 把命中行拼成带来源的正文块。嵌入模型非空的行同样保留。
     * 同一附件只留最长关键词命中的一行，避免把两处数字拼进同一段。
     *
     * @param rows 查询结果，允许为 null
     * @param terms 关键词
     * @return 检索块
     */
    static RetrievalContext format(List<ProjectKnowledgeFragmentHit> rows, List<String> terms) {
        if (rows == null || rows.isEmpty() || terms == null || terms.isEmpty()) {
            return empty();
        }
        List<Scored> scored = new ArrayList<>();
        for (ProjectKnowledgeFragmentHit row : rows) {
            if (row == null) {
                continue;
            }
            String name = row.getAttachName() == null ? "" : row.getAttachName().trim();
            String content = row.getContent() == null ? "" : row.getContent();
            String matched = longestContained(name, content, terms);
            if (matched == null) {
                continue;
            }
            scored.add(new Scored(row, name, content, matched, name.contains(matched)));
        }
        scored.sort(Comparator.comparingInt((Scored item) -> item.matched.length()).reversed()
            .thenComparing(item -> item.title ? 0 : 1));
        StringBuilder block = new StringBuilder();
        StringBuilder failures = new StringBuilder();
        var sources = new ArrayList<org.ruoyi.ipd.service.AiDocEmbeddingService.CitationSource>();
        Set<String> seen = new LinkedHashSet<>();
        int hits = 0;
        for (Scored item : scored) {
            if (hits >= LIMIT) {
                break;
            }
            if (item.row.getKnowledgeId() == null || item.row.getFragmentId() == null
                || item.row.getDocId() == null || item.row.getDocId().isBlank() || item.name.isBlank()) {
                failures.append(ProjectKnowledgeVectorSearch.FAILURE_MARK)
                    .append("知识片段来源身份缺失，不能作为引用证据\n");
                continue;
            }
            String key = item.name.isEmpty() ? "fragment:" + item.row.getFragmentId() : item.name;
            if (!seen.add(key)) {
                continue;
            }
            String source = sourceOf(item.row.getSourceRemark(), item.name);
            String snippet = item.title ? snippetFromStart(item.content) : snippetAround(item.content, item.matched);
            block.append("【产品知识片段 ").append(hits + 1).append("｜").append(dash(item.name))
                .append("｜出处：").append(source).append("｜sourceType=KNOWLEDGE_FRAGMENT｜knowledgeId=")
                .append(item.row.getKnowledgeId()).append("｜documentId=").append(item.row.getDocId())
                .append("｜fragmentId=").append(item.row.getFragmentId())
                .append("｜reviewStatus=NOT_PROJECT_DOCUMENT】\n").append(snippet).append('\n');
            sources.add(new org.ruoyi.ipd.service.AiDocEmbeddingService.CitationSource("KNOWLEDGE_FRAGMENT",
                item.row.getDocId(), String.valueOf(item.row.getKnowledgeId()),
                String.valueOf(item.row.getFragmentId()), item.name, "NOT_PROJECT_DOCUMENT"));
            hits++;
        }
        if (hits == 0 && failures.isEmpty()) {
            return empty();
        }
        String all = failures.toString() + block;
        return new RetrievalContext(hits, all.length(), all, block.toString(), sources);
    }

    private static String longestContained(String name, String content, List<String> terms) {
        String best = null;
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            if ((name.contains(term) || content.contains(term))
                && (best == null || term.length() > best.length())) {
                best = term;
            }
        }
        return best;
    }

    private static RetrievalContext empty() {
        return new RetrievalContext(0, 0, "");
    }

    private static String normalize(String query) {
        if (query == null) {
            return null;
        }
        String term = query.trim();
        if (term.length() < MIN_QUERY_CHARS) {
            return null;
        }
        if (term.length() > MAX_QUERY_CHARS) {
            term = term.substring(0, MAX_QUERY_CHARS);
        }
        return term;
    }

    /**
     * 把一个关键词转成 LIKE 模式，转义通配符。
     *
     * @param term 关键词
     * @return 带首尾百分号的模式
     */
    static String likePattern(String term) {
        String escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static String sourceOf(String remark, String attachName) {
        if (remark != null && !remark.isBlank()) {
            return remark.trim();
        }
        return dash(attachName);
    }

    private static String snippetFromStart(String content) {
        if (content.length() <= SNIPPET_CHARS) {
            return content;
        }
        return content.substring(0, SNIPPET_CHARS);
    }

    private static String snippetAround(String content, String term) {
        int at = content.indexOf(term);
        if (at < 0) {
            return snippetFromStart(content);
        }
        int start = Math.max(0, at - 200);
        int end = Math.min(content.length(), start + SNIPPET_CHARS);
        return content.substring(start, end);
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record Scored(ProjectKnowledgeFragmentHit row, String name, String content,
                          String matched, boolean title) {
    }
}
