package org.ruoyi.agent;

import java.util.List;
import java.util.Set;

/** 专业规约合并进同一次原生 Agent；能力仅来自显式选定工具。 */
public final class AgentChatPrompt {
    private AgentChatPrompt() { }
    public static String build(String base, List<String> skills, Set<String> toolNames) {
        StringBuilder prompt = new StringBuilder(base == null || base.isBlank()
            ? "用用户的语言回答。" : base);
        prompt.append("\n仅在用户请求绘制图表时遵循以下图表规约：\n").append(ChartGenerationAgent.SYSTEM_PROMPT);
        if (toolNames.contains("executeSql")) { prompt.append("\n需要查询数据库时遵循以下查询规约：\n").append(SqlAgent.SYSTEM_PROMPT); }
        prompt.append("\n只有本次登记的工具可执行操作；没有资料或调用失败时说明限制，不伪造检索、查询、图表和文件。\n");
        prompt.append(NativeChatSkills.selectedPrompt(skills));
        return prompt.toString();
    }
}
