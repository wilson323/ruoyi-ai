package org.ruoyi.workflow.workflow;

import org.apache.commons.lang3.StringUtils;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.entity.WorkflowEdge;
import java.util.*;

/** 业务图编译入口；只验证并保留既有节点、边与确定性路由。 */
public final class WorkflowGraphBuilder {
    private final List<WorkflowComponent> components;
    private final List<WorkflowNode> nodes;
    private final List<WorkflowEdge> edges;
    private final WorkflowNodeRunner runner;
    private final WfState state;
    public WorkflowGraphBuilder(List<WorkflowComponent> components, List<WorkflowNode> nodes,
            List<WorkflowEdge> edges, WorkflowNodeRunner runner, WfState state) {
        this.components = List.copyOf(components); this.nodes = List.copyOf(nodes); this.edges = List.copyOf(edges); this.runner = runner; this.state = state;
    }
    public WorkflowExecutionPlan build(WorkflowNode start) {
        Set<String> effects = new HashSet<>();
        for (WorkflowNode node : nodes) {
            components.stream().filter(c -> Objects.equals(c.getId(), node.getWorkflowComponentId()))
                .filter(c -> WfComponentNameEnum.getByName(c.getName()) != null
                    && WfComponentNameEnum.getByName(c.getName()).hasSideEffect())
                .findFirst().ifPresent(c -> effects.add(node.getUuid()));
        }
        WorkflowExecutionPlan plan = new WorkflowExecutionPlan(nodes, edges, start.getUuid(), runner, state, effects);
        if (state != null) {
            state.addEdge(WorkflowExecutionPlan.START, start.getUuid());
            edges.forEach(e -> state.addEdge(e.getSourceNodeUuid(), e.getTargetNodeUuid()));
        }
        return plan;
    }
    static String resolveNextRoute(Map<String, Object> stateData, String sourceNodeId) {
        Object next = stateData == null ? null : stateData.get("next");
        if (next == null || StringUtils.isBlank(next.toString())) {
            throw new IllegalStateException("条件路由失败：节点 [" + sourceNodeId + "] 未产出 next 路由键"
                    + "（分支未命中且默认分支目标为空，或节点失败输出未携带 next），state 键: "
                    + (stateData == null ? "[]" : stateData.keySet()));
        }
        return next.toString();
    }

}
