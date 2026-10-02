package org.ruoyi.ipd.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 落草稿前的完成检查。检索被调用却零命中、且正文未写明「未取得」，
 * 或正文宣称 Gate 已通过 / 建议签署时，运行不得标为成功。
 */
public final class ProjectAgentCompletionGate {

    /** ERROR 事件使用的业务码，文案见 {@link ProjectAgentErrorTexts}。 */
    public static final String REJECTED = "COMPLETION_REJECTED";

    /** 固定诊断分类；不携带模型正文、引用或异常信息。 */
    public enum RejectionReason {
        EMPTY_BODY,
        GATE_AUTHORITY_CLAIM,
        SOURCE_IDENTITY_MISMATCH,
        MISSING_RETRIEVAL_DISCLOSURE,
        UNSUPPORTED_MEASUREMENT
    }

    static final String SEARCH_TOOL = "project_knowledge_search";

    /** 小数或百分数。纯整数不核，避免把步骤号和年份当成编造引用。 */
    private static final Pattern MEASUREMENT = Pattern.compile("(?<!\\d)(\\d+\\.\\d+|\\d+%)(?!\\d)");

    /** 只排除 Markdown 行首标题的章节号；标题中的价格等其他数字仍需来源。 */
    private static final Pattern HEADING_SECTION = Pattern.compile(
        "(?m)^([ ]{0,3}#{1,6}[\\t ]+)\\d+(?:\\.\\d+)+(?:[.)])?(?=[\\t ]+\\S)");

    private boolean searchInvoked;
    private boolean anyHit;
    private final StringBuilder quotes = new StringBuilder();
    /** 逐条来源身份。同名文件靠 documentId 区分，不压成标题集合。 */
    private final List<Evidence> evidence = new ArrayList<>();

    /**
     * 一条可引用身份。知识库片段的 reviewStatus 只能是 NOT_PROJECT_DOCUMENT。
     *
     * @param sourceType KNOWLEDGE_FRAGMENT 或 PROJECT_DOCUMENT
     * @param documentId 文档或附件标识
     * @param reviewStatus 审核状态
     * @param sourceName 展示名，可与另一条来源相同
     */
    private record Evidence(String sourceType, String documentId, String reviewStatus, String sourceName) {
    }

    /**
     * 记录一次工具调用。只有知识检索算作「检索已发生」。
     *
     * @param toolName 工具名
     */
    public void noteTool(String toolName) {
        if (SEARCH_TOOL.equals(toolName)) {
            searchInvoked = true;
        }
    }

    /**
     * 记录一条真实检索 SOURCE；本地知识与远端 MCP 共用完成门。
     * 只认数值 hits 和明确的成功/部分成功状态；未知状态不提供命中证据。
     *
     * @param source SOURCE 载荷
     */
    public void noteSource(Map<String, Object> source) {
        if (source == null || !source.containsKey("hits")) {
            return;
        }
        // 本地知识与远端 MCP 均用既有 SOURCE 合同，不依赖各工具的协议名称。
        searchInvoked = true;
        Object raw = source.get("hits");
        int hits = raw instanceof Number number ? number.intValue() : 0;
        String status = source.get("retrievalStatus") == null ? "" : String.valueOf(source.get("retrievalStatus"));
        boolean citable = hits > 0 && ("SUCCESS".equals(status) || "PARTIAL".equals(status));
        if (!citable) {
            return;
        }
        // 只认工具提交的逐条权威身份，旧无结构来源不推测类别。
        if (source.get("sourceEvidence") instanceof Iterable<?> identities) {
            for (Object identity : identities) {
                if (!(identity instanceof Map<?, ?> fields)) {
                    continue;
                }
                Object name = fields.get("sourceName");
                Object id = fields.get("documentId");
                if (!(name instanceof String title) || title.isBlank()
                    || !(id instanceof String documentId) || documentId.isBlank()) {
                    continue;
                }
                if ("KNOWLEDGE_FRAGMENT".equals(fields.get("sourceType"))
                    && fields.get("knowledgeId") instanceof String kid && !kid.isBlank()
                    && "NOT_PROJECT_DOCUMENT".equals(fields.get("reviewStatus"))) {
                    evidence.add(new Evidence("KNOWLEDGE_FRAGMENT", documentId, "NOT_PROJECT_DOCUMENT", title));
                }
                if ("PROJECT_DOCUMENT".equals(fields.get("sourceType"))
                    && "REVIEWED".equals(fields.get("reviewStatus"))) {
                    evidence.add(new Evidence("PROJECT_DOCUMENT", documentId, "REVIEWED", title));
                }
            }
        }
        anyHit = true;
        // 混合失败文本没有可靠的片段边界，不贡献数字引用证据。
        boolean cleanPartial = "PARTIAL".equals(status) && "SUCCESS".equals(source.get("citationStatus"))
            && source.get("citationText") instanceof String citation && !citation.isBlank();
        Object preview = "PARTIAL".equals(status)
            ? (cleanPartial ? source.get("citationText") : null)
            : source.containsKey("citationText") ? source.get("citationText") : source.get("preview");
        if (preview != null) {
            quotes.append(preview).append('\n');
        }
    }

    /** 完整输出中只保留思考块以外正文；未闭合思考块的尾部不交付。 */
    static String deliverableBody(String text) {
        String input = text == null ? "" : text;
        StringBuilder body = new StringBuilder();
        int from = 0;
        while (from < input.length()) {
            int open = input.indexOf("<think>", from);
            if (open < 0) {
                body.append(input.substring(from));
                break;
            }
            body.append(input, from, open);
            int close = input.indexOf("</think>", open + 7);
            if (close < 0) {
                break;
            }
            from = close + 8;
        }
        return body.toString().trim();
    }

    /**
     * 判断正文能否作为成功产物留下。
     *
     * @param text 已刷出的全文
     * @return 拒绝码；允许落草稿时返回 null
     */
    public String reject(String text) {
        return rejectionReason(text) == null ? null : REJECTED;
    }

    /** 按原完成门顺序返回首个固定原因；允许落草稿时返回 null。 */
    public RejectionReason rejectionReason(String text) {
        String body = deliverableBody(text);
        if (body.isBlank()) {
            return RejectionReason.EMPTY_BODY;
        }
        if (claimsGate(body)) {
            return RejectionReason.GATE_AUTHORITY_CLAIM;
        }
        if (mislabelsKnownKnowledge(body)) return RejectionReason.SOURCE_IDENTITY_MISMATCH;
        if (searchInvoked && !anyHit && !body.contains("未取得")) {
            return RejectionReason.MISSING_RETRIEVAL_DISCLOSURE;
        }
        if (searchInvoked && !measurementsMatchQuotes(body)) {
            return RejectionReason.UNSUPPORTED_MEASUREMENT;
        }
        return null;
    }

    /** 先按 documentId 核对，同名标题不能互相借用身份。 */
    private boolean mislabelsKnownKnowledge(String body) {
        if (explicitIdentityContradiction(body)) {
            return true;
        }
        return uniqueTitleMislabel(body);
    }

    /** 每条引用独立核对完整标识，不让同行的其他来源借用类型或审核状态。 */
    private boolean explicitIdentityContradiction(String body) {
        for (String part : body.split("[\\r\\n；;。]")) {
            Matcher fields = Pattern.compile(
                "(documentId|sourceType|reviewStatus)=([^\\s|｜，,；;。】]+)").matcher(part);
            Map<String, String> reference = new java.util.LinkedHashMap<>();
            while (fields.find()) {
                String key = fields.group(1);
                // 一条引用每个键只有一个值；重复键意味着下一条引用开始。
                if (reference.containsKey(key)) {
                    if (identityContradiction(reference, part)) return true;
                    reference.clear();
                }
                reference.put(key, fields.group(2));
            }
            if (identityContradiction(reference, part)) return true;
        }
        return false;
    }

    /** 字段顺序可变，但每条引用只对照自己明确写出的 documentId。 */
    private boolean identityContradiction(Map<String, String> reference, String part) {
        String documentId = reference.get("documentId");
        if (documentId == null) return false;
        for (Evidence item : evidence) {
            if (!documentId.equals(item.documentId)) continue;
            String claimedType = reference.get("sourceType");
            String claimedReview = reference.get("reviewStatus");
            if (claimedType != null && !claimedType.equals(item.sourceType)) return true;
            if (claimedReview != null && !claimedReview.equals(item.reviewStatus)) return true;
            if ("KNOWLEDGE_FRAGMENT".equals(item.sourceType)
                && claimsReviewedRole(part) && !specificallyDeniesReviewed(part)) return true;
        }
        return false;
    }

    /** 标题只属于知识库时才按表和肯定句拒绝；同名正式文档不凭标题拒绝。 */
    private boolean uniqueTitleMislabel(String body) {
        Set<String> knowledgeNames = new LinkedHashSet<>();
        Set<String> reviewedNames = new LinkedHashSet<>();
        for (Evidence item : evidence) {
            if (item.sourceName == null || item.sourceName.isBlank()) {
                continue;
            }
            if ("KNOWLEDGE_FRAGMENT".equals(item.sourceType)) {
                knowledgeNames.add(item.sourceName);
            }
            if ("PROJECT_DOCUMENT".equals(item.sourceType)) {
                reviewedNames.add(item.sourceName);
            }
        }
        boolean reviewedSection = false;
        for (String line : body.split("[\\r\\n；;。]")) {
            String trimmed = line.stripLeading();
            if (trimmed.matches("#{1,6}\\s+.*")) {
                reviewedSection = trimmed.contains("项目已审核文档") || trimmed.contains("已审核项目文档");
            }
            if (line.contains("documentId=")) continue;
            for (String name : knowledgeNames) {
                if (!line.contains(name) || reviewedNames.contains(name) || specificallyDeniesReviewed(line)) {
                    continue;
                }
                if ((reviewedSection && trimmed.startsWith("|"))
                    || line.contains("sourceType=PROJECT_DOCUMENT") || line.contains("reviewStatus=REVIEWED")
                    || claimsReviewedRole(line)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 只认针对审核归属的否定。句子里别的「不是」不算。
     *
     * @param line 一行正文
     * @return true 表示这行在否定「已审核项目文档」这个归属
     */
    private static boolean specificallyDeniesReviewed(String line) {
        return line.contains("不是项目已审核") || line.contains("不是已审核")
            || line.contains("不属于已审核") || line.contains("不属于项目已审核")
            || line.contains("不能作为已审核") || line.contains("不能作为项目已审核")
            || line.contains("不代表已审核") || line.contains("不代表项目已审核")
            || line.contains("sourceType=KNOWLEDGE_FRAGMENT")
            || line.contains("reviewStatus=NOT_PROJECT_DOCUMENT")
            || line.contains("未取得") || line.contains("未命中");
    }

    /**
     * 肯定地把材料说成已审核项目文档。
     *
     * @param line 一行正文
     * @return true 表示有审核归属宣称
     */
    private static boolean claimsReviewedRole(String line) {
        return line.contains("属于已审核文档") || line.contains("属于项目已审核文档")
            || line.contains("作为项目已审核文档") || line.contains("来自项目已审核文档")
            || line.contains("来自已审核文档") || line.contains("是项目已审核文档");
    }

    /**
     * 正文里的小数和百分数必须在可引用原文里出现过。
     * 失败、无权和未取得的预览不能当出处。
     *
     * @param body 已刷出的全文
     * @return true 表示数字都能对上出处
     */
    private boolean measurementsMatchQuotes(String body) {
        Set<String> cited = measurementCores(quotes.toString());
        for (String core : measurementCores(body)) {
            if (!cited.contains(core)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 抽出小数和百分数的数值部分。12.83% 与 12.83 视为同一个数。
     *
     * @param text 正文或原文
     * @return 数值集合
     */
    private static Set<String> measurementCores(String text) {
        Set<String> cores = new LinkedHashSet<>();
        String values = HEADING_SECTION.matcher(text == null ? "" : text).replaceAll("$1");
        Matcher matcher = MEASUREMENT.matcher(values);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.endsWith("%")) {
                token = token.substring(0, token.length() - 1);
            }
            cores.add(token);
        }
        return cores;
    }

    /**
     * 只把分句内的肯定宣称当成越权。
     * 同一分句在短语之前出现不、未、没、别、勿、禁时，视为否定，不拒绝。
     *
     * @param body 已刷出的全文
     * @return true 表示正文在肯定句里宣称 Gate 已通过或建议签署
     */
    private static boolean claimsGate(String body) {
        String[] phrases = {
            "评审已通过",
            "评审通过",
            "Gate已通过",
            "Gate 已通过",
            "建议Gate签署",
            "建议 Gate 签署"
        };
        for (String phrase : phrases) {
            int from = 0;
            while (from < body.length()) {
                int at = body.indexOf(phrase, from);
                if (at < 0) {
                    break;
                }
                if (!negatedBefore(body, at) && !conditionalAfter(body, at, phrase.length())) {
                    return true;
                }
                from = at + phrase.length();
            }
        }
        return false;
    }

    /**
     * 「评审通过后才能」这类流程条件不是已经通过的宣称。
     *
     * @param body 全文
     * @param phraseAt 短语起始下标
     * @param phraseLength 短语长度
     * @return true 表示后面接的是进入下一阶段的条件
     */
    private static boolean conditionalAfter(String body, int phraseAt, int phraseLength) {
        String rest = body.substring(Math.min(body.length(), phraseAt + phraseLength));
        return rest.startsWith("后才") || rest.startsWith("后方可")
            || rest.startsWith("之后才") || rest.startsWith("之后方可");
    }

    /**
     * 短语所在分句的前缀是否已否定该短语。
     * 分句边界是句号、叹号、问号、分号、逗号和换行。
     *
     * @param body 全文
     * @param phraseAt 短语起始下标
     * @return true 表示该处是在否定，不是宣称
     */
    private static boolean negatedBefore(String body, int phraseAt) {
        int start = phraseAt;
        while (start > 0) {
            char previous = body.charAt(start - 1);
            if (previous == '。' || previous == '！' || previous == '？'
                || previous == '\n' || previous == '；' || previous == ';'
                || previous == '，' || previous == ',') {
                break;
            }
            start--;
        }
        String prefix = body.substring(start, phraseAt);
        return prefix.contains("不")
            || prefix.contains("未")
            || prefix.contains("没")
            || prefix.contains("别")
            || prefix.contains("勿")
            || prefix.contains("禁");
    }
}
