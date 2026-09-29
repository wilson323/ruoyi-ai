package org.ruoyi.ipd.vo;

import java.util.List;

/**
 * 动作技能视图：动作码 + 目录真名（ActionCatalog 派生）+ 归属小阶段 + 技能列表。
 * skillNames 为已解析列表（NULL/未定稿 → 空列表，不回 null）。
 */
public record ActionSkillView(
    String actionCode,
    String actionName,
    String subStageCode,
    List<String> skillNames,
    Integer sortOrder) {
}
