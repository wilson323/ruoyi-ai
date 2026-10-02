package org.ruoyi.workflow.workflow;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.ruoyi.workflow.util.UuidUtil;
import org.ruoyi.workflow.workflow.data.NodeIOData;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.DEFAULT_INPUT_PARAM_NAME;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.NODE_PROCESS_STATUS_READY;

/**
 * 工作流节点实例状态 | workflow node instance state
 */
@Setter
@Getter
@ToString(callSuper = true)
public class WfNodeState implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Map<String, Object> data;
    public Map<String, Object> data() { return data; }

    private String uuid = UuidUtil.createShort();
    private int processStatus = NODE_PROCESS_STATUS_READY;
    private String processStatusRemark = "";
    private List<NodeIOData> inputs = new ArrayList<>();
    private List<NodeIOData> outputs = new ArrayList<>();

    /**
     * Constructs an AgentState with the given initial data.
     *
     * @param initData the initial data for the agent state
     */
    @SuppressWarnings("unchecked")
    public WfNodeState(Map<String, Object> initData) {
        this.data = (Map<String,Object>) org.apache.commons.lang3.SerializationUtils.clone((java.io.Serializable) new java.util.LinkedHashMap<>(initData));
    }

    public WfNodeState() {
        this(Map.of());
    }

    public Optional<NodeIOData> getDefaultInput() {
        return inputs.stream().filter(item -> DEFAULT_INPUT_PARAM_NAME.equals(item.getName())).findFirst();
    }
}
