package org.ruoyi.ipd.vo;

import org.ruoyi.ipd.domain.AiAgentTask;

import java.util.Date;

/**
 * ai_agent_tasks 只读投影（R232 P2-04 任务卡数据通道；GET /api/v1/ai-agent-tasks）。
 *
 * <p>暴露面 = 状态呈现到 result_summary 粒度（P2-04 任务卡口径）：
 * 任务身份（id/projectId/actionCode/stageActionId/triggerType/execMode）+ 状态机
 * （status/attempt）+ 结果摘要（resultSummary/errorMsg）+ 草稿审核直达（aiDocId）。
 *
 * <p><b>红线</b>：prompt / fillPayload / inputDigest / dedupKey 一律不出此 VO
 * （审计规约 L0-5：只存 input_digest + result_summary，原文永不展示）。
 * 哨兵：AiAgentTaskQueryTest#viewNeverExposesPromptFields 断言组件面零泄漏。
 */
public record AiAgentTaskView(
    Long id,
    Long projectId,
    String actionCode,
    Long stageActionId,
    String triggerType,
    String execMode,
    String status,
    Integer attempt,
    String resultSummary,
    Long aiDocId,
    String errorMsg,
    Long triggeredBy,
    Date createTime,
    Date updateTime
) {
    public static AiAgentTaskView from(AiAgentTask task) {
        return new AiAgentTaskView(
            task.getId(),
            task.getProjectId(),
            task.getActionCode(),
            task.getStageActionId(),
            task.getTriggerType(),
            task.getExecMode(),
            task.getStatus(),
            task.getAttempt(),
            task.getResultSummary(),
            task.getAiDocId(),
            task.getErrorMsg(),
            task.getTriggeredBy(),
            task.getCreateTime(),
            task.getUpdateTime());
    }
}
