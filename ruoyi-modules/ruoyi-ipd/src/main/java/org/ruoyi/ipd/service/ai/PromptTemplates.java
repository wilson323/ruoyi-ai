package org.ruoyi.ipd.service.ai;

import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.PromptType;

import java.util.EnumMap;
import java.util.Map;

/**
 * AI-P1-1：promptType → 内置 system prompt 模板映射（模板字符串常量，落 service 层）。
 * <p>渲染规则：模板中 {@code {sourceText}} 槽位填入 PM 提交的原始资料/生成指令
 * （RAG 拼装后的 effectivePrompt）。promptType 为空 → 原样返回（裸 prompt 直传，
 * 向后兼容老前端）；非法值 → PARAM_INVALID 拒绝（不静默降级，避免"以为套了模板
 * 实际没套"的假成功；与 AiGenerationService.validateActorAndReq 拒错风格一致）。
 */
public final class PromptTemplates {

    /** 原始资料槽位标记 */
    static final String SLOT = "{sourceText}";

    /** promptType → 模板（服务层常量映射；PM 原始资料统一置于模板尾部槽位，符合模型注意力惯例） */
    private static final Map<PromptType, String> TEMPLATES = new EnumMap<>(PromptType.class);

    static {
        TEMPLATES.put(PromptType.PRD,
            "你是一名资深产品经理，请将以下原始资料整理为规范的 PRD（产品需求文档），"
                + "包含：背景与目标、用户与场景、功能需求（含优先级）、非功能需求、验收标准、风险与依赖。"
                + "仅基于资料内容，不得虚构未提及的需求。\n\n【原始资料】\n" + SLOT);
        TEMPLATES.put(PromptType.MRD,
            "你是一名市场分析师，请将以下原始资料整理为规范的 MRD（市场需求文档），"
                + "包含：市场与目标用户、竞品分析、市场机会与定位、需求优先级、商业化路径。"
                + "仅基于资料内容，不得虚构未提及的数据。\n\n【原始资料】\n" + SLOT);
        TEMPLATES.put(PromptType.BRD,
            "你是一名商业分析师，请将以下原始资料整理为规范的 BRD（商业需求文档），"
                + "包含：业务目标与价值、现状痛点、方案概述、收益与成本估算、风险与假设。"
                + "仅基于资料内容，不得虚构未提及的数字。\n\n【原始资料】\n" + SLOT);
        TEMPLATES.put(PromptType.CHARTER,
            "你是项目发起人视角的文档助手，请将以下原始资料整理为项目章程（Charter），"
                + "包含：项目背景与立项依据、目标与成功标准、范围与主要里程碑、干系人与角色、高层级风险。"
                + "仅基于资料内容，不得虚构未提及的承诺。\n\n【原始资料】\n" + SLOT);
        TEMPLATES.put(PromptType.TEST_REPORT,
            "你是测试负责人视角的文档助手，请将以下原始资料整理为测试报告，"
                + "包含：测试范围与策略、环境与版本、用例/执行统计、缺陷分布与严重度、遗留风险、结论与建议。"
                + "仅基于资料内容，不得虚构未执行的测试。\n\n【原始资料】\n" + SLOT);
        TEMPLATES.put(PromptType.RELEASE_NOTE,
            "你是发布经理视角的文档助手，请将以下原始资料整理为发布说明（Release Note），"
                + "包含：版本概述、新增功能、优化项、缺陷修复、已知问题、升级注意事项。"
                + "面向最终用户措辞，仅基于资料内容，不得虚构未包含的变更。\n\n【原始资料】\n" + SLOT);
        TEMPLATES.put(PromptType.REVIEW,
            "你是评审会议秘书，请将以下原始资料整理为评审纪要/评审意见稿，"
                + "包含：评审对象与结论、参会角色与意见汇总、争议点与决议、行动项（负责人+期限）。"
                + "仅基于资料内容，不得虚构未发生的决议。\n\n【原始资料】\n" + SLOT);
    }

    private PromptTemplates() {
    }

    /**
     * 渲染入口：按 promptType code 套模板；null/空 → sourceText 原样返回（向后兼容）。
     *
     * @param promptTypeCode 前端可选传的文档类型（PRD/MRD/BRD/CHARTER/TEST_REPORT/RELEASE_NOTE/REVIEW，大小写不敏感）
     * @param sourceText     本次生成实际送入模型的资料（含 RAG 注入后的文本）
     * @return 拼装后的最终 prompt
     * @throws IpdBusinessException promptType 非空但非法 → PARAM_INVALID（拒绝，不降级）
     */
    public static String render(String promptTypeCode, String sourceText) {
        if (promptTypeCode == null || promptTypeCode.isBlank()) {
            return sourceText;
        }
        PromptType type = PromptType.fromCodeOrNull(promptTypeCode);
        if (type == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "promptType 非法: " + promptTypeCode);
        }
        return TEMPLATES.get(type).replace(SLOT, sourceText);
    }

    /** 取指定类型的原始模板（测试/管理端预览用；不含 sourceText 渲染）。 */
    public static String templateOf(PromptType type) {
        return TEMPLATES.get(type);
    }
}
