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
        UNSUPPORTED_MEASUREMENT,
        SKILL_CONTRACT_MISMATCH
    }

    static final String SEARCH_TOOL = "project_knowledge_search";

    /** 小数、百分数及明确金额；无单位整数仍不核，避免把步骤号和年份当成引用。 */
    private static final Pattern MEASUREMENT = Pattern.compile(
        "(?<![\\d.])(\\d+(?:\\.\\d+)?)(?:[\\t ]*(%|％|[亿万千百]?(?:人民币元|美元|港元|欧元|元)))?(?![\\d.])");

    /** 只排除 Markdown 行首标题的章节号；标题中的价格等其他数字仍需来源。 */
    private static final Pattern HEADING_SECTION = Pattern.compile(
        "(?m)^([ ]{0,3}#{1,6}[\\t ]+)\\d+(?:\\.\\d+)+(?:[.)])?(?=[\\t ]+\\S)");

    private final String actionCode;

    public ProjectAgentCompletionGate() { this(null); }

    public ProjectAgentCompletionGate(String actionCode) { this.actionCode = actionCode; }

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
        if ("C02".equals(actionCode) && blocksOptionalPurpose(body)) {
            return RejectionReason.SKILL_CONTRACT_MISMATCH;
        }
        return null;
    }

    /** 只核用途本身的肯定阻塞声明，不把竞品名单等其他缺项借作用途阻塞。 */
    private static boolean blocksOptionalPurpose(String body) {
        for (String raw : body.split("\\R")) {
            String line = raw.strip().replace("**", "").replace("`", "");
            if (line.startsWith(">")) continue;
            if (line.startsWith("|")) {
                String[] cells = line.split("\\|", -1);
                // 缺项表：编号 / 缺项 / 影响 / 处置。只看紧邻缺项的影响列。
                if (cells.length >= 5 && cells[1].strip().matches("[A-Za-z]+[-－]?\\d+")) {
                    String subject = cells[2].strip();
                    if (subject.matches("^(?:目的裁剪|用途(?:声明)?|目的声明)(?:[（(：:].*|$)" )
                        && (subject.contains("用户声明时") || line.contains("未声明")
                            || line.contains("默认四维") || subject.contains("可选"))
                        && affirmativePurposeBlock(cells[3].strip())) return true;
                }
            } else if (line.matches("^(?:[-*] |\\d+[.、] )?(?:用途未声明|未声明用途|目的裁剪.{0,20}可选|用途声明.{0,20}可选|目的声明.{0,20}可选).{0,60}")) {
                if (affirmativePurposeBlock(line)) return true;
            }
        }
        return false;
    }

    private static boolean affirmativePurposeBlock(String statement) {
        for (String segment : statement.split("(?:但是|但|然而|并且|且)")) {
            if (segment.strip().matches("^(?:若|如果|如有|当|引用|例如|假设).*")) continue;
            boolean conditional = false;
            for (String raw : segment.split("[，,；;。]")) {
                String clause = raw.strip();
                if (clause.matches("^(?:若|如果|如有|当|引用|例如|假设).*")) conditional = true;
                if (conditional) continue;
                if (clause.matches(".*(?:不阻塞|不应阻塞|不是阻塞|不构成阻塞|无需|无须|不必|并非必需|不得作为阻塞).*")) continue;
                // 明确其他缺项的主语不继承前句用途；只接受用途本身或省略主语的用途声明/影响谓词。
                boolean purpose = clause.matches("^(?:用途|目的裁剪|目的声明).*")
                    || clause.matches("^(?:必须(?:先)?声明|必需(?:条件|前置)|未声明|阻塞(?:\\s*C02|\\s*步骤)).*");
                if (purpose && clause.matches(".*(?:阻塞(?:\\s*C02)?|必须(?:先|补|声明)|必需(?:条件|前置)|才能继续).*")) return true;
            }
        }
        return false;
    }

    /** 先按 documentId 核对，同名标题不能互相借用身份。 */
    private boolean mislabelsKnownKnowledge(String body) {
        if (explicitIdentityContradiction(body)) {
            return true;
        }
        return metadataMislabel(body) || uniqueTitleMislabel(body);
    }

    /** 项目上下文是元数据，不因出现在审核材料层级表里就成为已审核文档。 */
    private static boolean metadataMislabel(String body) {
        for (String raw : body.split("[\\r\\n；;。]")) {
            String line = raw.replace("**", "").replace("`", "").trim();
            if (line.startsWith("|")) {
                String[] cells = line.split("\\|", -1);
                // 只核明确的“来源主体 → 类型”表行，不把整行任意后置字段借给主体。
                if (cells.length > 2 && metadataSubject(cells[1].trim())
                    && reviewedClassification(cells[2].trim())) return true;
            } else if ((line.startsWith("项目事实") || line.startsWith("项目上下文") || line.startsWith("项目元数据"))
                && !line.matches(".*(?:若|如果|如有|尚未取得|未取得).*")) {
                String relation = line.replaceFirst("^(项目事实|项目上下文|项目元数据)", "").trim();
                if (!relation.matches(".*(?:不是|不等于|不属于|不能作为|不代表|不应视为).*")) {
                    if (relation.matches(".{0,80}(?:属于|作为|等同于|视为|是)(?:项目已审核文档|已审核项目文档|已审核文档).*"))
                        return true;
                }
            }
        }
        return false;
    }

    private static boolean metadataSubject(String value) {
        return value.matches("^(?:项目事实|项目上下文|项目元数据)(?:[（(：:，,\\s].*|$)");
    }

    private static boolean reviewedClassification(String value) {
        return value.matches("^(?:项目已审核文档|已审核项目文档|已审核文档)(?:[（(\\s].*|$)");
    }

    /** 每条引用独立核对完整标识；白话资料编号与 documentId 使用同一身份。 */
    private boolean explicitIdentityContradiction(String body) {
        for (String part : body.split("[\\r\\n；;。]")) {
            Matcher fields = Pattern.compile(
                "(?<![\\p{L}\\p{N}_])(documentId|sourceType|reviewStatus)=([^\\s|｜，,；;。】（）()]+)"
                    + "|资料编号[：:][\\t ]*([^\\s|｜，,；;。】（）()]+)").matcher(part);
            Map<String, String> reference = new java.util.LinkedHashMap<>();
            int from = 0;
            while (fields.find()) {
                String key = fields.group(1) == null ? "documentId" : fields.group(1);
                String value = fields.group(1) == null ? fields.group(3) : fields.group(2);
                // 重复身份键开始下一引用；只把本条文本交给审核宣称判定。
                if (reference.containsKey(key)) {
                    int boundary = referenceBoundary(part, from, fields.start());
                    if (identityContradiction(reference, part.substring(from, boundary))) return true;
                    reference.clear();
                    from = boundary;
                }
                reference.put(key, value);
            }
            if (identityContradiction(reference, part.substring(from))) return true;
        }
        return false;
    }

    /** 下一编号前的归属谓词属于下一引用，不能留给上一正式文档。 */
    private static int referenceBoundary(String part, int from, int fieldStart) {
        int boundary = fieldStart;
        int comma = Math.max(part.lastIndexOf('，', fieldStart), part.lastIndexOf(',', fieldStart));
        if (comma >= from) boundary = comma + 1;
        Matcher preceding = Pattern.compile(
            "(?:作为|来自|属于|是)(?:项目已审核文档|已审核项目文档|已审核文档)的[\\t ]*$")
            .matcher(part.substring(from, fieldStart));
        if (preceding.find()) boundary = Math.min(boundary, from + preceding.start());
        return boundary;
    }

    /** 显式编号的审核归属必须有本次正式文档正证；未知编号不能借同名标题。 */
    private boolean identityContradiction(Map<String, String> reference, String part) {
        String documentId = reference.get("documentId");
        if (documentId == null) return false;
        if (part.stripLeading().matches("^(?:反例|错误示例|禁止示例)[：:].*")) return false;
        boolean reviewedClaim = affirmativeReviewedClaim(part);
        if (reviewedClaim && evidence.stream().noneMatch(item -> documentId.equals(item.documentId)
            && "PROJECT_DOCUMENT".equals(item.sourceType) && "REVIEWED".equals(item.reviewStatus))) return true;
        for (Evidence item : evidence) {
            if (!documentId.equals(item.documentId)) continue;
            String claimedType = reference.get("sourceType");
            String claimedReview = reference.get("reviewStatus");
            if (claimedType != null && !claimedType.equals(item.sourceType)) return true;
            if (claimedReview != null && !claimedReview.equals(item.reviewStatus)) return true;
            if ("KNOWLEDGE_FRAGMENT".equals(item.sourceType) && reviewedClaim) return true;
        }
        return false;
    }

    private static boolean affirmativeReviewedClaim(String part) {
        for (String cell : part.split("[|｜]")) {
            if (reviewedClassification(cell.replace("**", "").replace("`", "").trim())) return true;
        }
        String[] claims = {"属于已审核文档", "属于项目已审核文档", "作为项目已审核文档",
            "来自项目已审核文档", "来自已审核文档", "是项目已审核文档",
            "来自已审核项目文档", "属于已审核项目文档", "是已审核项目文档",
            "sourceType=PROJECT_DOCUMENT", "reviewStatus=REVIEWED"};
        for (String claim : claims) {
            int from = 0;
            while (from < part.length()) {
                int at = part.indexOf(claim, from);
                if (at < 0) break;
                String prefix = part.substring(0, at);
                prefix = prefix.substring(Math.max(prefix.lastIndexOf('，'), prefix.lastIndexOf(',')) + 1);
                if (!prefix.matches(".*(?:不|未|没|不能|不应|不得|禁止|不要|并非|不等于|未被认定为)(?:能|可|将|应|会)?$|.*(?:不是|不属于|不能作为|不代表)(?:项目)?$")
                    && !conditionalAfter(part, at, claim.length())
                    && !part.substring(at + claim.length()).matches("^(?:未取得|未命中).*")
                    && !prefix.matches(".*(?:若|如果|如有).*")) return true;
                from = at + claim.length();
            }
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
     * 保留百分比及明确金额单位，不允许同一裸数跨计量单位借证。
     * 不换算金额规模或币种，不推断实体、期间或指标关系。
     */
    private static Set<String> measurementCores(String text) {
        Set<String> cores = new LinkedHashSet<>();
        String values = HEADING_SECTION.matcher(text == null ? "" : text).replaceAll("$1");
        Matcher matcher = MEASUREMENT.matcher(values);
        while (matcher.find()) {
            String number = matcher.group(1);
            String unit = matcher.group(2);
            if (unit == null && !number.contains(".")) continue;
            if ("％".equals(unit)) unit = "%";
            cores.add(number + "|" + (unit == null ? "" : unit));
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
