package org.ruoyi.ipd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * R227-C1（AI-FUSION L2，2026-09-26）：域内 AI 建议请求体（方案 §5.1）。
 * <ul>
 *   <li>{@code scene} 必填：场景键，白名单见 {@code AiSuggestionService.SCENES}
 *       （workbench.next-step / workbench.risk-warning / project.summary.refresh /
 *       project.create.suggest / demand.create.from-requirement /
 *       gate.precheck-checklist / gate.conclusion-draft）；</li>
 *   <li>{@code projectId} 项目相关场景必填（越权按 copilot 同款 project_members 校验）；</li>
 *   <li>{@code entityId} gate 场景 = gateId（前端 gate-panel 定位粒度；服务内按 gateId
 *       拉全部评审行 + 要素结果；不开放自由 entityType 防 prompt 注入面扩大）；</li>
 *   <li>{@code userPrompt} 创建类场景（project.create / demand.create）必填——
 *       用户输入的原始素材（项目想法 / 需求原文），≤2000 字符；只进 prompt 不进审计原文（BR-AI-04）。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSuggestReq(@NotBlank @Size(max = 64) String scene,
                           Long projectId,
                           Long entityId,
                           @Size(max = 2000) String userPrompt) {
}
