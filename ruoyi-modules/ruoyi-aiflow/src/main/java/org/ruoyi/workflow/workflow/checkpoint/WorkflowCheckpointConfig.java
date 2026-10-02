package org.ruoyi.workflow.workflow.checkpoint;

import lombok.Builder;
import lombok.Getter;

/** 检查点定位只使用现有 runtime UUID 和 checkpoint ID。 */
@Getter
@Builder
public final class WorkflowCheckpointConfig {
    private String threadId;
    private String checkPointId;
}
