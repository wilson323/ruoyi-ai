package org.ruoyi.workflow.workflow;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.chat.enums.ErrorEnum;
import org.ruoyi.common.core.exception.base.BaseException;
import org.ruoyi.workflow.base.NodeInputConfigTypeHandler;
import org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver;
import org.ruoyi.workflow.dto.workflow.WfRuntimeNodeDto;
import org.ruoyi.workflow.dto.workflow.WfRuntimeResp;
import org.ruoyi.workflow.entity.*;
import org.ruoyi.common.sse.core.SseEmitterHelper;
import org.ruoyi.workflow.service.WorkflowRuntimeNodeService;
import org.ruoyi.workflow.service.WorkflowRuntimeService;
import org.ruoyi.workflow.util.JsonUtil;
import org.ruoyi.workflow.util.WorkflowMessageUtil;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.ruoyi.workflow.workflow.def.WfNodeIO;
import org.ruoyi.workflow.workflow.def.WfNodeParamRef;
import org.ruoyi.workflow.workflow.node.AbstractWfNode;
import org.ruoyi.workflow.workflow.node.enmus.NodeMessageTemplateEnum;
import org.springframework.beans.BeanUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.function.Function;

import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.*;
import static org.ruoyi.common.chat.enums.ErrorEnum.*;

@Slf4j
public class WorkflowEngine {
    private final Workflow workflow;
    private final List<WorkflowComponent> components;
    private final List<WorkflowNode> wfNodes;
    private final List<WorkflowEdge> wfEdges;
    private final WorkflowRuntimeService workflowRuntimeService;
    private final WorkflowRuntimeNodeService workflowRuntimeNodeService;
    private final JdbcCheckpointSaver checkpointSaver;
    @Getter
    private WorkflowExecutionPlan app;
    @Setter
    private SseEmitter sseEmitter;
    private User user;
    @Getter
    private WfState wfState;
    private WfRuntimeResp wfRuntimeResp;

    public WorkflowEngine(
            Workflow workflow,
            List<WorkflowComponent> components,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> wfEdges,
            WorkflowRuntimeService workflowRuntimeService,
            WorkflowRuntimeNodeService workflowRuntimeNodeService,
            JdbcCheckpointSaver checkpointSaver) {
        this.workflow = workflow;
        this.components = components;
        this.wfNodes = nodes;
        this.wfEdges = wfEdges;
        this.workflowRuntimeService = workflowRuntimeService;
        this.workflowRuntimeNodeService = workflowRuntimeNodeService;
        this.checkpointSaver = checkpointSaver;
    }

    public void run(User user, List<ObjectNode> userInputs, SseEmitter sseEmitter, Long userId, String tokenValue, Long sessionId) {
        this.user = user;
        this.sseEmitter = sseEmitter;
        log.info("WorkflowEngine run,userId:{},workflowUuid:{},userInputs:{}", user.getId(), workflow.getUuid(), userInputs);
        if (!this.workflow.getIsEnable()) {
            WorkflowMessageUtil.sendErrorAndComplete(user.getId(), sseEmitter, ErrorEnum.A_WF_DISABLED.getInfo());
            throw new BaseException(ErrorEnum.A_WF_DISABLED.getInfo());
        }

        Long workflowId = this.workflow.getId();
        this.wfRuntimeResp = workflowRuntimeService.create(user, workflowId);
        if (sseEmitter != null) {
            WorkflowMessageUtil.startSse(user, sseEmitter, JsonUtil.toJson(wfRuntimeResp));
        }

        String runtimeUuid = this.wfRuntimeResp.getUuid();
        try (JdbcCheckpointSaver.RunLease lease = checkpointSaver.acquireRun(runtimeUuid)) {
            try {
                lease.requireHeld();
                Pair<WorkflowNode, Set<WorkflowNode>> startAndEnds = findStartAndEndNode();
                WorkflowNode startNode = startAndEnds.getLeft();
                List<NodeIOData> wfInputs = getAndCheckUserInput(userInputs, startNode);
                this.wfState = new WfState(user, wfInputs, runtimeUuid,userId, tokenValue, sseEmitter, sessionId);
                workflowRuntimeService.updateInput(this.wfRuntimeResp.getId(), wfState);


                WorkflowGraphBuilder graphBuilder = new WorkflowGraphBuilder(
                        components,
                        wfNodes,
                        wfEdges,
                        this::runNode,
                        this.wfState);
                app = graphBuilder.build(startNode);
                exe(runtimeUuid, false, lease);
            } catch (Exception e) {
                lease.requireHeld();
                errorWhenExe(e);
            }
        } catch (Exception e) {
            reportUnownedFailure(e);
        }
    }

    private void exe(String runtimeUuid, boolean resume, JdbcCheckpointSaver.RunLease lease) throws Exception {
        String lastNode = app.executeUnderLease(checkpointSaver, runtimeUuid, resume, Map.of(), this::persistNodeOutput, lease);
        lease.requireHeld();
        wfState.setProcessStatus(WORKFLOW_PROCESS_STATUS_SUCCESS);
        WorkflowRuntime updatedRuntime = workflowRuntimeService.updateOutput(wfRuntimeResp.getId(), wfState);
        try {
            wfNodes.stream().filter(node -> lastNode.equals(node.getUuid())).findFirst().ifPresent(node -> {
                String template = WorkflowMessageUtil.getNodeMessageTemplate(NodeMessageTemplateEnum.END.getValue());
                if (sseEmitter != null) WorkflowMessageUtil.notifyAndStoreMessage(wfState, sseEmitter, node, template);
                else WorkflowMessageUtil.saveWorkflowMessage(wfState, template);
            });
            if (sseEmitter != null) WorkflowMessageUtil.sendComplete(user.getId(), sseEmitter, updatedRuntime.getOutput());
        } catch (RuntimeException notificationError) {
            log.warn("Workflow succeeded but completion notification failed,runtimeUuid:{}", runtimeUuid, notificationError);
        }
    }

    private void persistNodeOutput(String nodeUuid) {
        AbstractWfNode completed = wfState.getCompletedNodes().stream()
                .filter(node -> node.getNode().getUuid().equals(nodeUuid)).findFirst().orElseThrow();
        WfRuntimeNodeDto runtimeNode = wfState.getRuntimeNodeByNodeUuid(nodeUuid);
        if (runtimeNode == null) throw new IllegalStateException("工作流节点执行记录缺失");
        workflowRuntimeNodeService.updateOutput(runtimeNode.getId(), completed.getState());
        wfState.setOutput(completed.getState().getOutputs());
    }

    /** 未取得或已失去租约，只通知本次连接；禁止写原运行状态与持久消息。 */
    private void reportUnownedFailure(Exception e) {
        log.error("Workflow attempt cannot retain execution ownership", e);
        if (sseEmitter != null) {
            WorkflowMessageUtil.sendErrorAndComplete(user.getId(), sseEmitter,
                "本次运行无法继续，请确认原运行状态后重试");
        }
    }

    private void errorWhenExe(Exception e) {
        log.error("error", e);
        String errorMsg = e.getMessage();
        if (errorMsg != null && errorMsg.contains("parallel node doesn't support conditional branch")) {
            errorMsg = "并行节点中不能包含条件分支";
        }
        String failure = errorMsg != null ? errorMsg : e.getClass().getSimpleName();
        // 调用方已确认租约；业务终态不依赖配置、持久消息或连接通知。
        workflowRuntimeService.updateStatus(wfRuntimeResp.getId(), WORKFLOW_PROCESS_STATUS_FAIL, failure);
        String notification = failure;
        try {
            notification = WorkflowMessageUtil.getNodeMessageTemplate(NodeMessageTemplateEnum.EXCEPTION.getValue()) + failure;
        } catch (RuntimeException templateError) {
            log.warn("Workflow failure persisted but notification template unavailable", templateError);
        }
        try {
            WorkflowMessageUtil.saveWorkflowMessage(wfState, notification);
        } catch (RuntimeException messageError) {
            log.warn("Workflow failure persisted but failure message unavailable", messageError);
        }
        if (sseEmitter != null) {
            try {
                WorkflowMessageUtil.sendErrorAndComplete(user.getId(), sseEmitter, notification);
            } catch (RuntimeException notificationError) {
                log.warn("Workflow failure persisted but connection notification failed", notificationError);
            }
        }
    }

    private Map<String, Object> runNode(WorkflowNode wfNode, WfNodeState nodeState) {
        Map<String, Object> resultMap = new HashMap<>();
        try {
            WorkflowComponent wfComponent = components.stream().filter(item -> item.getId().equals(wfNode.getWorkflowComponentId())).findFirst().orElseThrow();
            AbstractWfNode abstractWfNode = WfNodeFactory.create(wfComponent, wfNode, wfState, nodeState);
            //节点实例
            WfRuntimeNodeDto runtimeNodeDto = workflowRuntimeNodeService.createByState(user, wfNode.getId(), wfRuntimeResp.getId(), nodeState);
            wfState.getRuntimeNodes().add(runtimeNodeDto);

            sendPartialIfConnected("[NODE_RUN_" + wfNode.getUuid() + "]", JsonUtil.toJson(runtimeNodeDto));

            NodeProcessResult processResult = abstractWfNode.process((is) -> {
                workflowRuntimeNodeService.updateInput(runtimeNodeDto.getId(), nodeState);
                List<NodeIOData> nodeIODataList = nodeState.getInputs();
                for (NodeIOData input : nodeIODataList) {
                    String inputConfig = wfNode.getInputConfig();
                    WfNodeInputConfig nodeInputConfig = NodeInputConfigTypeHandler.fillNodeInputConfig(inputConfig);
                    List<WfNodeParamRef> refInputs = nodeInputConfig.getRefInputs();
                    if (CollUtil.isNotEmpty(refInputs) && "input".equals(input.getName())) {
                        continue;
                    }
                    sendPartialIfConnected("[NODE_INPUT_" + wfNode.getUuid() + "]", JsonUtil.toJson(input));
                }
            }, (is) -> {
                //并行节点内部的节点执行结束后，需要主动向客户端发送输出结果
                workflowRuntimeNodeService.updateOutput(runtimeNodeDto.getId(), nodeState);
                String nodeUuid = wfNode.getUuid();
                List<NodeIOData> nodeOutputs = nodeState.getOutputs();
                for (NodeIOData output : nodeOutputs) {
                    log.info("callback node:{},output:{}", nodeUuid, output.getContent());
                    sendPartialIfConnected("[NODE_OUTPUT_" + nodeUuid + "]", JsonUtil.toJson(output));
                }
            });
            if (StringUtils.isNotBlank(processResult.getNextNodeUuid())) {
                resultMap.put("next", processResult.getNextNodeUuid());
            }
        } catch (Exception e) {
            log.error("Node run error", e);
            throw new BaseException(ErrorEnum.B_WF_RUN_ERROR.getInfo());
        }
        resultMap.put("name", wfNode.getTitle());
        WorkflowNodeStream generator = wfState.getNodeToStreamingGenerator().remove(wfNode.getUuid());
        if (generator != null) {
            generator.consume(chunk -> sendPartialIfConnected("[NODE_CHUNK_" + wfNode.getUuid() + "]", chunk));
        }
        return resultMap;
    }

    /**
     * 校验用户输入并组装成工作流的输入
     *
     * @param userInputs 用户输入
     * @param startNode  开始节点定义
     * @return 正确的用户输入列表
     */
    private List<NodeIOData> getAndCheckUserInput(List<ObjectNode> userInputs, WorkflowNode startNode) {
        WfNodeInputConfig wfNodeInputConfig = NodeInputConfigTypeHandler.fillNodeInputConfig(startNode.getInputConfig());
        List<WfNodeIO> defList = wfNodeInputConfig.getUserInputs();
        defList = CollStreamUtil.toList(defList, Function.identity());
        List<NodeIOData> wfInputs = new ArrayList<>();
        for (WfNodeIO paramDefinition : defList) {
            String paramNameFromDef = paramDefinition.getName();
            boolean requiredParamMissing = paramDefinition.getRequired();
            for (ObjectNode userInput : userInputs) {
                NodeIOData nodeIOData = WfNodeIODataUtil.createNodeIOData(userInput);
                if (!paramNameFromDef.equalsIgnoreCase(nodeIOData.getName())) {
                    continue;
                }
                Integer dataType = nodeIOData.getContent().getType();
                if (null == dataType) {
                    throw new BaseException(A_WF_INPUT_INVALID.getInfo());
                }
                requiredParamMissing = false;
                boolean valid = paramDefinition.checkValue(nodeIOData);
                if (!valid) {
                    log.error("用户输入无效,workflowId:{}", startNode.getWorkflowId());
                    throw new BaseException(ErrorEnum.A_WF_INPUT_INVALID.getInfo());
                }
                wfInputs.add(nodeIOData);
            }
            if (requiredParamMissing) {
                log.error("在流程定义中必填的参数没有传进来,name:{}", paramNameFromDef);
                throw new BaseException(A_WF_INPUT_MISSING.getInfo());
            }
        }
        return wfInputs;
    }

    /**
     * 僵尸实例断点续跑（补遗 §5-5 D1）：给定 runtime 实例 uuid（= checkpoint thread_id），
     * 从该 thread 最新 checkpoint 的 nextNodeId 恢复执行；已完成节点按 t_workflow_runtime_node 留痕
     * 重建上游上下文（completedNodes），供续跑节点 initInput/getLatestOutputs 引用。
     * <p>
     * sseEmitter 可为 null（进程重启后无 SSE 连接）：跳过实时推送，结果照常落库。
     */
    public void resume(User user, WorkflowRuntime runtime, SseEmitter sseEmitter, Long userId, String tokenValue, Long sessionId) {
        this.user = user;
        this.sseEmitter = sseEmitter;
        String runtimeUuid = runtime.getUuid();
        this.wfRuntimeResp = new WfRuntimeResp();
        BeanUtils.copyProperties(runtime, this.wfRuntimeResp);
        log.info("WorkflowEngine resume,runtimeUuid:{},workflowId:{}", runtimeUuid, runtime.getWorkflowId());
        try (JdbcCheckpointSaver.RunLease lease = checkpointSaver.acquireRun(runtimeUuid)) {
            lease.requireHeld();
            WorkflowRuntime fresh = workflowRuntimeService.getByUuidForResume(runtimeUuid);
            if (fresh == null || !Objects.equals(fresh.getId(), runtime.getId())
                    || !Objects.equals(fresh.getWorkflowId(), workflow.getId())
                    || !Objects.equals(fresh.getStatus(), WORKFLOW_PROCESS_STATUS_DOING)
                        && !Objects.equals(fresh.getStatus(), WORKFLOW_PROCESS_STATUS_FAIL)) {
                reportUnownedFailure(new IllegalStateException("运行已结束或不可恢复"));
                return;
            }
            BeanUtils.copyProperties(fresh, this.wfRuntimeResp);
            try {
                lease.requireHeld();
                Pair<WorkflowNode, Set<WorkflowNode>> startAndEnds = findStartAndEndNode();
                WorkflowNode startNode = startAndEnds.getLeft();
                this.wfState = new WfState(user, rebuildNodeIOData(fresh.getInput()), runtimeUuid, userId, tokenValue, sseEmitter, sessionId);
                rebuildCompletedNodes(fresh.getId());
                workflowRuntimeService.updateStatus(fresh.getId(), WORKFLOW_PROCESS_STATUS_DOING, "");

                WorkflowGraphBuilder graphBuilder = new WorkflowGraphBuilder(
                        components,
                        wfNodes,
                        wfEdges,
                        this::runNode,
                        this.wfState);
                app = graphBuilder.build(startNode);
                exe(runtimeUuid, true, lease);
            } catch (Exception e) {
                lease.requireHeld();
                errorWhenExe(e);
            }
        } catch (Exception e) {
            reportUnownedFailure(e);
        }
    }

    /**
     * SSE 推送（sseEmitter 可为 null：断点续跑无 SSE 连接时跳过推送，不影响落库）
     */
    private void sendPartialIfConnected(String name, String content) {
        if (null != sseEmitter) {
            SseEmitterHelper.parseAndSendPartialMsg(sseEmitter, name, content);
        }
    }

    /**
     * 从落库 JSON（{参数名: content} 形态，见 WorkflowRuntimeService.updateInput/updateOutput）重建 NodeIOData 列表
     */
    private List<NodeIOData> rebuildNodeIOData(String ioJson) {
        List<NodeIOData> result = new ArrayList<>();
        if (StringUtils.isBlank(ioJson)) {
            return result;
        }
        JsonNode root = JsonUtil.toJsonNode(ioJson);
        if (!(root instanceof ObjectNode objectNode)) {
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            ObjectNode wrapper = JsonUtil.createObjectNode();
            wrapper.put("name", entry.getKey());
            wrapper.set("content", entry.getValue());
            NodeIOData nodeIOData = WfNodeIODataUtil.createNodeIOData(wrapper);
            if (null != nodeIOData) {
                result.add(nodeIOData);
            }
        }
        return result;
    }

    /**
     * 从 t_workflow_runtime_node 留痕重建已完成节点（completedNodes），
     * 恢复上游输出供续跑节点引用
     */
    private void rebuildCompletedNodes(Long runtimeId) {
        List<WorkflowRuntimeNode> runtimeNodes = workflowRuntimeNodeService.lambdaQuery()
                .eq(WorkflowRuntimeNode::getWorkflowRuntimeId, runtimeId)
                .eq(WorkflowRuntimeNode::getIsDeleted, false)
                .orderByAsc(WorkflowRuntimeNode::getId)
                .list();
        for (WorkflowRuntimeNode runtimeNode : runtimeNodes) {
            if (!Integer.valueOf(NODE_PROCESS_STATUS_SUCCESS).equals(runtimeNode.getStatus())) {
                continue;
            }
            WorkflowNode wfNode = wfNodes.stream()
                    .filter(item -> item.getId().equals(runtimeNode.getNodeId())).findFirst().orElse(null);
            if (null == wfNode) {
                log.warn("Can not find workflow node by runtime node,nodeId:{}", runtimeNode.getNodeId());
                continue;
            }
            WorkflowComponent wfComponent = components.stream()
                    .filter(item -> item.getId().equals(wfNode.getWorkflowComponentId())).findFirst().orElse(null);
            if (null == wfComponent) {
                log.warn("Can not find workflow component,componentId:{}", wfNode.getWorkflowComponentId());
                continue;
            }
            WfNodeState nodeState = new WfNodeState();
            nodeState.setUuid(runtimeNode.getUuid());
            nodeState.setProcessStatus(runtimeNode.getStatus());
            nodeState.getInputs().addAll(rebuildNodeIOData(runtimeNode.getInput()));
            nodeState.getOutputs().addAll(rebuildNodeIOData(runtimeNode.getOutput()));
            WfRuntimeNodeDto restoredAttempt = new WfRuntimeNodeDto();
            BeanUtils.copyProperties(runtimeNode, restoredAttempt);
            wfState.getRuntimeNodes().add(restoredAttempt);
            wfState.getCompletedNodes().add(WfNodeFactory.create(wfComponent, wfNode, wfState, nodeState));
            wfState.setOutput(nodeState.getOutputs());
        }
    }

    /**
     * 查找开始及结束节点 <br/>
     * 开始节点只能有一个，结束节点可能多个
     *
     * @return 开始节点及结束节点列表
     */
    public Pair<WorkflowNode, Set<WorkflowNode>> findStartAndEndNode() {
        WorkflowNode startNode = null;
        Set<WorkflowNode> endNodes = new HashSet<>();
        for (WorkflowNode node : wfNodes) {
            Optional<WorkflowComponent> wfComponent = components.stream().filter(item -> item.getId().equals(node.getWorkflowComponentId())).findFirst();
            if (wfComponent.isPresent() && WfComponentNameEnum.START.getName().equals(wfComponent.get().getName())) {
                if (null != startNode) {
                    throw new BaseException(ErrorEnum.A_WF_MULTIPLE_START_NODE.getInfo());
                }
                startNode = node;
            } else if (wfComponent.isPresent() && WfComponentNameEnum.END.getName().equals(wfComponent.get().getName())) {
                endNodes.add(node);
            }
        }
        if (null == startNode) {
            log.error("没有开始节点, workflowId:{}", wfNodes.get(0).getWorkflowId());
            throw new BaseException(ErrorEnum.A_WF_START_NODE_NOT_FOUND.getInfo());
        }
        //Find all end nodes
        wfNodes.forEach(item -> {
            String nodeUuid = item.getUuid();
            boolean source = false;
            boolean target = false;
            for (WorkflowEdge edgeDef : wfEdges) {
                if (edgeDef.getSourceNodeUuid().equals(nodeUuid)) {
                    source = true;
                } else if (edgeDef.getTargetNodeUuid().equals(nodeUuid)) {
                    target = true;
                }
            }
            if (!source && target) {
                endNodes.add(item);
            }
        });
        log.info("start node:{}", startNode);
        log.info("end nodes:{}", endNodes);
        if (endNodes.isEmpty()) {
            log.error("没有结束节点,workflowId:{}", startNode.getWorkflowId());
            throw new BaseException(A_WF_END_NODE_NOT_FOUND.getInfo());
        }
        return Pair.of(startNode, endNodes);
    }

}
