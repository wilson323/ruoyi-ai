package org.ruoyi.domain.bo.shortdrama;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/**
 * Phase 1 剧本打磨结构化响应 —— 原生模型 JSON 结构化结果
 *
 * @author ageerle
 */
@Data
public class ShortDramaScriptResult {

    @JsonPropertyDescription("项目名称（有吸引力的短剧名）")
    private String projectName;

    @JsonPropertyDescription("一句话简介（20-50字）")
    private String description;

    @JsonPropertyDescription("剧本名称")
    private String scriptName;

    @JsonPropertyDescription("风格基调（如：都市甜宠/古装虐恋/悬疑惊悚/喜剧爽文）")
    private String tone;

    @JsonPropertyDescription("剧情大纲（400-800字，完整故事线）")
    private String outlineText;

    @JsonPropertyDescription("完整短剧文本（1000-3000字，标准剧本格式，含场景头、动作描述、对话）")
    private String scriptText;
}
