package org.ruoyi.ipd.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI-P1-1：文档助手 promptType 枚举（卡片「三补」之模板化，本卡仅后端）。
 * <p>PM 在生成请求（{@code AiGenerateReq.promptType}）中可选指定文档类型，
 * 后端按类型套用 {@code service.ai.PromptTemplates} 内置 system prompt 模板；
 * 为空 = 老逻辑裸 prompt 直传（向后兼容，老前端零改动零感知）。
 * <p>值域与审计 aiRole 白名单（draft/precheck/summarize…）正交：aiRole 描述
 * AI 在行动链中的角色，本枚举描述输出文档体裁，不可混用。
 */
@Getter
@AllArgsConstructor
public enum PromptType {

    /** PRD 产品需求文档 */
    PRD("PRD"),

    /** MRD 市场需求文档 */
    MRD("MRD"),

    /** BRD 商业需求文档 */
    BRD("BRD"),

    /** 项目章程（Charter） */
    CHARTER("CHARTER"),

    /** 测试报告 */
    TEST_REPORT("TEST_REPORT"),

    /** 发布说明（Release Note） */
    RELEASE_NOTE("RELEASE_NOTE"),

    /** 评审纪要 / 评审意见稿 */
    REVIEW("REVIEW"),

    /** 复盘报告（AI-P3 场景包之复盘起草；US-L1-09：目标回顾/达成数据/教训/改进项） */
    RETROSPECTIVE("RETROSPECTIVE");

    /** 线传值（与枚举名一致；预留 code 字段对齐 NotificationChannelType 惯例） */
    private final String code;

    /**
     * 安全反查：未知/空 code 返回 null（裸 prompt 直传由调用方处理，
     * 与 NotificationChannelType.fromCode 的"默认值兜底"不同——prompt 无默认模板可言）。
     * 匹配大小写不敏感，容忍前端序列化差异。
     */
    public static PromptType fromCodeOrNull(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        for (PromptType t : values()) {
            if (t.code.equalsIgnoreCase(trimmed)) {
                return t;
            }
        }
        return null;
    }
}
