package org.ruoyi.workflow.workflow.checkpoint;

import lombok.Builder;
import lombok.Getter;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** 既有工作流检查点行的应用载荷，不依赖智能体或图框架状态。 */
@Getter
@Builder
public final class WorkflowCheckpointState {
    @Builder.Default private String id = UUID.randomUUID().toString();
    private String nodeId;
    private String nextNodeId;
    @Builder.Default private Map<String, Object> state = new LinkedHashMap<>();
}
