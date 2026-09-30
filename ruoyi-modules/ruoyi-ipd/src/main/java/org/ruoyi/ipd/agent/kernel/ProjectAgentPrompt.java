package org.ruoyi.ipd.agent.kernel;

import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;

import java.util.List;

/**
 * 项目智能体系统提示词组装（纯函数，可单测）。
 *
 * <p>Skill 正文由服务端确定性注入（已过 sha256 校验），并以 STEP/SKILL_LOADED 事件留证；
 * 不依赖模型自行调用 load_skill，避免“是否真的加载了正文”无法回读。
 */
public final class ProjectAgentPrompt {

    private ProjectAgentPrompt() {
    }

    /**
     * 组装系统提示词。
     *
     * @param spec 运行输入
     * @return 系统提示词
     */
    public static String build(ProjectAgentRunSpec spec) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("你是 IPD 项目智能体（ipd_project_agent），只为当前一个项目工作，用简体中文回答。\n");
        sb.append("硬性约束：\n");
        sb.append("1. 事实只能来自用户本轮输入");
        if (spec.toolIds().contains(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)) {
            sb.append("和工具 ").append(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)
                .append(" 返回的本项目已审核资料");
        }
        sb.append("；没有来源的内容标注“未取得”，不得编造。\n");
        sb.append("2. 不能访问互联网，也不能引用其他项目的资料；不得声称做过未实际执行的检索。\n");
        sb.append("3. 不输出 Gate 评审通过或不通过的结论，评审结论由既有业务流程决定。\n");
        if (spec.actionCode() != null && !spec.actionCode().isBlank()) {
            sb.append("当前 IPD 动作：").append(spec.actionCode()).append("。\n");
        }
        List<String> skillBodies = spec.skills().stream().map(LoadedSkill::content).toList();
        sb.append(ProjectAgentIntent.prompt(
            ProjectAgentIntent.decide(spec.message(), spec.actionCode(), skillBodies)));
        for (LoadedSkill skill : spec.skills()) {
            sb.append("\n---\n## 执行说明（Skill: ").append(skill.name())
                .append('@').append(skill.version()).append("）\n")
                .append(skill.content()).append('\n');
        }
        return sb.toString();
    }
}
