package org.ruoyi.ipd.agent.service;

import java.util.Map;

/**
 * 落草稿前的完成检查。检索被调用却零命中、且正文未写明「未取得」，
 * 或正文宣称 Gate 已通过 / 建议签署时，运行不得标为成功。
 */
public final class ProjectAgentCompletionGate {

    /** ERROR 事件使用的业务码，文案见 {@link ProjectAgentErrorTexts}。 */
    public static final String REJECTED = "COMPLETION_REJECTED";

    static final String SEARCH_TOOL = "project_knowledge_search";

    private boolean searchInvoked;
    private boolean anyHit;

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
     * 记录一条 SOURCE。只认数值 hits；缺字段或非数值不记为命中。
     *
     * @param source SOURCE 载荷
     */
    public void noteSource(Map<String, Object> source) {
        if (source == null || !source.containsKey("hits")) {
            return;
        }
        Object raw = source.get("hits");
        if (raw instanceof Number number && number.intValue() > 0) {
            anyHit = true;
        }
    }

    /**
     * 判断正文能否作为成功产物留下。
     *
     * @param text 已刷出的全文
     * @return 拒绝码；允许落草稿时返回 null
     */
    public String reject(String text) {
        String body = text == null ? "" : text;
        if (claimsGate(body)) {
            return REJECTED;
        }
        if (searchInvoked && !anyHit && !body.contains("未取得")) {
            return REJECTED;
        }
        return null;
    }

    private static boolean claimsGate(String body) {
        return body.contains("评审通过")
            || body.contains("评审已通过")
            || body.contains("Gate已通过")
            || body.contains("Gate 已通过")
            || body.contains("建议Gate签署")
            || body.contains("建议 Gate 签署");
    }
}
