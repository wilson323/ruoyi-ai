package org.ruoyi.workflow.workflow;

import lombok.Getter;
import lombok.Setter;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.workflow.dto.workflow.WfRuntimeNodeDto;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.ruoyi.workflow.workflow.node.AbstractWfNode;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_READY;

/**
 * 工作流实例状态 | workflow instance state
 */
@Setter
@Getter
public class WfState {

    private String uuid;
    private User user;
    private String processingNodeUuid;
    private Long userId;
    private String tokenValue;
    private SseEmitter sseEmitter;
    private Long sessionId;

    //Source node uuid => target node uuid list
    private Map<String, List<String>> edges = new java.util.concurrent.ConcurrentHashMap<>();
    private Map<String, List<String>> conditionalEdges = new java.util.concurrent.ConcurrentHashMap<>();

    //Source node uuid => streaming chat generator
    private Map<String, WorkflowNodeStream> nodeToStreamingGenerator = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 已运行节点列表
     */
    private List<AbstractWfNode> completedNodes = new java.util.concurrent.CopyOnWriteArrayList<>();

    private List<WfRuntimeNodeDto> runtimeNodes = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 工作流接收到的输入（也是开始节点的输入参数）
     */
    private List<NodeIOData> input;

    /**
     * 工作流执行结束后的输出
     */
    private List<NodeIOData> output = new ArrayList<>();
    private Integer processStatus = WORKFLOW_PROCESS_STATUS_READY;

    public WfState(User user, List<NodeIOData> input, String uuid, Long userId, String tokenValue, SseEmitter sseEmitter, Long sessionId) {
        this.input = input;
        this.user = user;
        this.uuid = uuid;
        this.userId = userId;
        this.tokenValue = tokenValue;
        this.sseEmitter = sseEmitter;
        this.sessionId = sessionId;
    }

    /**
     * 获取最新的输出结果
     *
     * @return 参数列表
     */
    private final ThreadLocal<String> activeNode = new ThreadLocal<>();
    public void beginNode(String nodeUuid) { activeNode.set(nodeUuid); }
    public void endNode() { activeNode.remove(); }
    public List<NodeIOData> getLatestOutputs() {
        String current = activeNode.get();
        if (current != null) {
            Set<String> predecessors = new LinkedHashSet<>();
            edges.forEach((source, targets) -> { if (targets.contains(current)) predecessors.add(source); });
            List<NodeIOData> output = new ArrayList<>();
            completedNodes.stream().filter(n -> predecessors.contains(n.getNode().getUuid()))
                .forEach(n -> n.getState().getOutputs().forEach(value -> output.add(org.apache.commons.lang3.SerializationUtils.clone(value))));
            return output;
        }
        WfNodeState upstreamState = completedNodes.get(completedNodes.size() - 1).getState();
        return upstreamState.getOutputs();
    }

    public Optional<WfNodeState> getNodeStateByNodeUuid(String nodeUuid) {
        return this.completedNodes.stream().filter(item -> item.getNode().getUuid().equals(nodeUuid)).map(AbstractWfNode::getState).findFirst();
    }

    /**
     * 新增一条边
     * 并行执行分支的情况下会出现一个 source node 对应多个 target node
     *
     * @param sourceNodeUuid 开始节点
     * @param targetNodeUuid 目标节点
     */
    public void addEdge(String sourceNodeUuid, String targetNodeUuid) {
        List<String> targetNodeUuids = edges.computeIfAbsent(sourceNodeUuid, k -> new ArrayList<>());
        targetNodeUuids.add(targetNodeUuid);
    }

    /**
     * 新增一条边
     * 按条件执行的分支会出现一个 source node 对应多个 target node 的情况
     *
     * @param sourceNodeUuid 开始节点
     * @param targetNodeUuid 目标节点
     */
    public void addConditionalEdge(String sourceNodeUuid, String targetNodeUuid) {
        List<String> targetNodeUuids = conditionalEdges.computeIfAbsent(sourceNodeUuid, k -> new ArrayList<>());
        targetNodeUuids.add(targetNodeUuid);
    }

    public List<NodeIOData> getIOByNodeUuid(String nodeUuid) {
        List<NodeIOData> result = new ArrayList<>();
        Optional<AbstractWfNode> optional = completedNodes.stream().filter(node -> nodeUuid.equals(node.getNode().getUuid())).findFirst();
        if (optional.isEmpty()) {
            return result;
        }
        optional.get().getState().getInputs().forEach(value -> result.add(org.apache.commons.lang3.SerializationUtils.clone(value)));
        optional.get().getState().getOutputs().forEach(value -> result.add(org.apache.commons.lang3.SerializationUtils.clone(value)));
        return result;
    }

    /** 只保留已提交检查点的上游上下文；未提交尝试只留在数据库历史中。 */
    public void retainCheckpointCompleted(Set<String> completed) {
        Map<String, AbstractWfNode> latest = new LinkedHashMap<>();
        for (AbstractWfNode node : completedNodes) {
            String id = node.getNode().getUuid();
            if (completed.contains(id)) latest.put(id, node);
        }
        completedNodes.clear();
        completedNodes.addAll(latest.values());
        Set<String> attempts = new HashSet<>();
        latest.values().forEach(node -> attempts.add(node.getState().getUuid()));
        runtimeNodes.removeIf(node -> !attempts.contains(node.getUuid()));
        output = completedNodes.isEmpty() ? new ArrayList<>() : new ArrayList<>(completedNodes.get(completedNodes.size() - 1).getState().getOutputs());
    }

    public WfRuntimeNodeDto getRuntimeNodeByNodeUuid(String wfNodeUuid) {
        Optional<WfNodeState> state = getNodeStateByNodeUuid(wfNodeUuid);
        if (state.isEmpty()) return null;
        return runtimeNodes.stream()
                .filter(item -> Objects.equals(item.getUuid(), state.get().getUuid()))
                .findFirst().orElse(null);
    }
}
