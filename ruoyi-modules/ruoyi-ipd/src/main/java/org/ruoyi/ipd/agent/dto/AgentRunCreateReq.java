package org.ruoyi.ipd.agent.dto;

import java.util.List;
import io.agentscope.core.agui.model.RunAgentInput;

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
 * @param requirementId 可选需求单 ID（字符串）；分拣运行成功后据此回写产品线
 */
public record AgentRunCreateReq(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                                List<String> skillNames, List<String> toolIds, String actionCode,
                                String message, String idempotencyKey, String productLineId,
                                String requirementId, String previousRunId, String targetDocumentId, String baseVersionId,
                                RunAgentInput aguiInput) {

    /** 兼容原完整请求；AG-UI 输入沿原创建口显式提供。 */
    public AgentRunCreateReq(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                            List<String> skillNames, List<String> toolIds, String actionCode,
                            String message, String idempotencyKey, String productLineId, String requirementId,
                            String previousRunId, String targetDocumentId, String baseVersionId) {
        this(capabilityPackCode, capabilityPackVersion, modelConfigId, skillNames, toolIds, actionCode,
            message, idempotencyKey, productLineId, requirementId, previousRunId, targetDocumentId, baseVersionId, null);
    }

    /** 兼容既有创建请求；返工关联只由显式三元组提供。 */
    public AgentRunCreateReq(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                            List<String> skillNames, List<String> toolIds, String actionCode,
                            String message, String idempotencyKey, String productLineId, String requirementId) {
        this(capabilityPackCode, capabilityPackVersion, modelConfigId, skillNames, toolIds, actionCode,
            message, idempotencyKey, productLineId, requirementId, null, null, null);
    }

    /**
     * 兼容未带需求单的调用（requirementId=null）。
     */
    public AgentRunCreateReq(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                             List<String> skillNames, List<String> toolIds, String actionCode,
                             String message, String idempotencyKey, String productLineId) {
        this(capabilityPackCode, capabilityPackVersion, modelConfigId, skillNames, toolIds, actionCode,
            message, idempotencyKey, productLineId, null);
    }

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
