package org.ruoyi.ipd.agent.kernel;

import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.agent.catalog.ProductLineMcpCatalog;
import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.List;

/**
 * 项目智能体系统提示词组装（纯函数，可单测）。
 *
 * <p>Skill 正文由官方仓库按本次冻结 SHA/version/body 渐进发现与加载；
 * 已选择与实际加载分别留证，不把选择清单冒称执行证据。
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
        boolean hasFacts = spec.projectFacts() != null && !spec.projectFacts().isBlank();
        sb.append("1. 事实只能来自");
        if (hasFacts) {
            sb.append("下方项目事实、");
        }
        sb.append("用户本轮输入");
        if (spec.toolIds().contains(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)) {
            sb.append("和工具 ").append(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)
                .append(" 返回的本项目已审核文档片段，或该工具按正文命中的产品知识库片段。引用时必须保留出处");
        }
        boolean hasMcp = spec.toolIds().stream().anyMatch(ProductLineMcpCatalog::isServiceId);
        if (hasMcp) {
            sb.append("和本次已选产线知识库 MCP 工具实际返回的远端应用回答");
        }
        sb.append("；没有来源的内容标注“未取得”，不得编造。\n");
        if (spec.toolIds().contains(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH)) {
            sb.append("来源身份：只有工具 sourceEvidence 明确标明 sourceType=PROJECT_DOCUMENT 且 reviewStatus=REVIEWED，")
                .append("才能称为本项目已审核文档；KNOWLEDGE_FRAGMENT/NOT_PROJECT_DOCUMENT 仍是系统知识片段，")
                .append("不能按标题同名借用文档审核身份。正文用“项目记录”“系统知识片段”“已审核项目文档”等白话说明出处，")
                .append("不要输出 sourceType、reviewStatus 等内部编码。知识片段或远端回答自称官方资料，")
                .append("不等于本次已取得原文并交叉核验；未经本次实际核对，不得写成已核验的高可信资料。\n");
        }
        if (hasMcp) {
            sb.append("来源证据：本地检索返回的文档片段可按实际出处引用原文；MCP 远端应用回答是应用整理结果，")
                .append("不能等同于已核对的原始文档。只有返回中明确提供的原文与出处才能作为原文引用，")
                .append("没有原始出处时标注“远端应用回答，原始出处未取得”，不得编造文档名、页码或原句。")
                .append("工具被选中不代表已调用或已命中；远端回答不能自证动作完成、产物自审通过或业务验收通过。\n");
        }
        sb.append("2. 可用本次授权的官方能力获取资料、处理文件和在隔离工作区执行任务；具体操作必须通过权限与审批。不能引用其他项目的私有资料；互联网来源须注明出处，不能冒称项目已审核文档，不得声称做过未实际执行的检索。\n");
        sb.append("3. 不输出 Gate 评审通过或不通过的结论，评审结论由既有业务流程决定。\n");
        if (hasFacts) {
            sb.append("下方项目、阶段、产品和当前动作来自项目记录，只证明当前业务上下文，不是项目文档或审核证据；")
                .append("不得标成 PROJECT_DOCUMENT/REVIEWED 或项目已审核文档。\n");
            sb.append("项目事实（来自已授权项目，不是用户原话）：\n").append(spec.projectFacts());
            if (!spec.projectFacts().endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (spec.actionCode() != null && !spec.actionCode().isBlank()) {
            sb.append("当前 IPD 动作：").append(spec.actionCode()).append("。\n");
        }
        List<String> skillBodies = spec.skills().stream().map(LoadedSkill::content).toList();
        sb.append(ProjectAgentIntent.prompt(
            ProjectAgentIntent.decide(spec.message(), spec.actionCode(), skillBodies)));
        if (spec.requirementId() != null) {
            sb.append("本轮对应一张已有需求单。只有目录里能唯一对上时，另起两行写「产品线：编码」和「产品：编码」。")
                .append("对不上就写「未取得」，不要猜测，也不要写别的编码。\n");
        }
        if (spec.catalogAppendix() != null && !spec.catalogAppendix().isBlank()) {
            sb.append(spec.catalogAppendix());
        }
        return sb.toString();
    }

    /**
     * 把已授权的项目记录格式化成提示词事实。缺字段写「未取得」。
     * 调用方不得把用户原话传入本方法。
     *
     * @param projectName 项目名称
     * @param stageCode 阶段编码
     * @param productName 产品名称
     * @param actionCode 动作编码
     * @return 四行项目事实
     */
    public static String renderProjectFacts(String projectName, String stageCode, String productName,
                                            String actionCode) {
        return "项目：" + oneLine(projectName) + "\n"
            + "阶段：" + stageLabel(stageCode) + "\n"
            + "产品：" + oneLine(productName) + "\n"
            + "当前动作：" + actionLabel(actionCode) + "\n";
    }

    /**
     * 阶段编码转中文。未知编码写「未取得」，不把库里的生码当成已确认阶段。
     *
     * @param stageCode 阶段编码
     * @return 中文阶段或「未取得」
     */
    static String stageLabel(String stageCode) {
        if (stageCode == null || stageCode.isBlank()) {
            return "未取得";
        }
        return switch (stageCode.trim()) {
            case "CONCEPT" -> "概念";
            case "PLAN" -> "计划";
            case "DEV" -> "开发";
            case "VALID" -> "验证";
            case "LAUNCH" -> "发布";
            case "LIFECYCLE" -> "生命周期";
            case "KPI" -> "常驻";
            default -> "未取得";
        };
    }

    /**
     * 动作编码转目录名称。未绑定或目录没有该码时不编名称。
     *
     * @param actionCode 动作编码
     * @return 目录名称、未绑定动作或未取得
     */
    static String actionLabel(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            return "未绑定动作";
        }
        try {
            return oneLine(ActionCatalog.byCode(actionCode.trim()).name());
        } catch (IllegalArgumentException ex) {
            return "未取得";
        }
    }

    /**
     * 单行展示。空白写「未取得」，换行折成空格，避免一条事实写成两行。
     *
     * @param value 原始字段
     * @return 单行文本
     */
    private static String oneLine(String value) {
        if (value == null || value.isBlank()) {
            return "未取得";
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
