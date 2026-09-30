package org.ruoyi.ipd.agent.dto;

import java.util.List;

/**
 * POST /api/v1/projects/{projectId}/agent-runs 请求体（合同 #2）。
 *
 * <p>所有 ID 以字符串接收（雪花 ID 在 JS 中不丢精度）；服务端只接受 ID 与版本，
 * 不接受浏览器自报的 userId/角色/工具配置。身份一律来自会话。
 *
 * @param capabilityPackCode 能力包编码
 * @param capabilityPackVersion 能力包版本
 * @param modelConfigId ai_model_configs.id（字符串）
 * @param skillNames 选定 Skill（须 ⊆ 能力包 Skill）
 * @param toolIds 选定工具（须 ⊆ 能力包工具）
 * @param actionCode 动作编码（可空，须 ∈ 能力包 actionCodes）
 * @param message 用户输入
 * @param idempotencyKey 幂等键（同人同键重复提交返回原运行）
 * @param productLineId 可选产品线 ID（字符串）；传了则校验项目归属，不传保持原状
 */
public record AgentRunCreateReq(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                                List<String> skillNames, List<String> toolIds, String actionCode,
                                String message, String idempotencyKey, String productLineId) {

    /**
     * 兼容无产品线字段的既有调用（productLineId=null）。
     *
     * @param capabilityPackCode 能力包编码
     * @param capabilityPackVersion 能力包版本
     * @param modelConfigId 模型配置 ID
     * @param skillNames Skill 名
     * @param toolIds 工具 ID
     * @param actionCode 动作编码
     * @param message 用户输入
     * @param idempotencyKey 幂等键
     */
    public AgentRunCreateReq(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                             List<String> skillNames, List<String> toolIds, String actionCode,
                             String message, String idempotencyKey) {
        this(capabilityPackCode, capabilityPackVersion, modelConfigId, skillNames, toolIds, actionCode,
            message, idempotencyKey, null);
    }
}
