package org.ruoyi.ipd.vo;

import java.util.List;

/**
 * 小阶段引导视图（GET /api/v1/ipd/stage/sub-stages）。
 * id 为字符串（P0-4.1 大整数保真）；actions 已按 sort_order 升序并解析 skill_names。
 */
public record SubStageView(
    String id,
    String code,
    String name,
    String stageCode,
    Integer sortOrder,
    String isGate,
    String gateCode,
    String skillHint,
    String ownerRole,
    List<ActionSkillView> actions) {
}
