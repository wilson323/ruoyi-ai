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
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.langchain4j.generators.StreamingChatGenerator;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.chat.enums.ErrorEnum;
import org.ruoyi.common.core.exception.base.BaseException;
import org.ruoyi.workflow.base.NodeInputConfigTypeHandler;
import org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver;
import org.ruoyi.workflow.dto.workflow.WfRuntimeNodeDto;
import org.ruoyi.workflow.dto.workflow.WfRuntimeResp;
import org.ruoyi.workflow.entity.*;
import org.ruoyi.workflow.helper.SSEEmitterHelper;
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

import static org.bsc.langgraph4j.StateGraph.END;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.*;
import static org.ruoyi.common.chat.enums.ErrorEnum.*;

@Slf4j
public class WorkflowEngine {
    private final Workflow workflow;
    private final List<WorkflowComponent> components;
    private final List<WorkflowNode> wfNodes;
    private final List<WorkflowEdge> wfEdges;
    private final SSEEmitterHelper sseEmitterHelper;
    private final WorkflowRuntimeService workflowRuntimeService;
    private final WorkflowRuntimeNodeService workflowRuntimeNodeService;
    private final JdbcCheckpointSaver checkpointSaver;
    @Getter
    private CompiledGraph<WfNodeState> app;
    @Setter
    private SseEmitter sseEmitter;
    private User user;
    @Getter
    private WfState wfState;
    private WfRuntimeResp wfRuntimeResp;

    public WorkflowEngine(
            Workflow workflow,
            SSEEmitterHelper sseEmitterHelper,
            List<WorkflowComponent> components,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> wfEdges,
            WorkflowRuntimeService workflowRuntimeService,
            WorkflowRuntimeNodeService workflowRuntimeNodeService,
            JdbcCheckpointSaver checkpointSaver) {
        this.workflow = workflow;
        this.sseEmitterHelper = sseEmitterHelper;
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
            sseEmitterHelper.sendErrorAndComplete(user.getId(), sseEmitter, ErrorEnum.A_WF_DISABLED.getInfo());
            throw new BaseException(ErrorEnum.A_WF_DISABLED.getInfo());
        }

        Long workflowId = this.workflow.getId();
        this.wfRuntimeResp = workflowRuntimeService.create(user, workflowId);
        this.sseEmitterHelper.startSse(user, sseEmitter, JsonUtil.toJson(wfRuntimeResp));

        String runtimeUuid = this.wfRuntimeResp.getUuid();
        try {
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
            StateGraph<WfNodeState> mainStateGraph = graphBuilder.build(startNode);

            CompileConfig compileConfig = CompileConfig.builder().checkpointSaver(checkpointSaver)
                    .build();
            app = mainStateGraph.compile(compileConfig);
            // checkpoint 落库按 thread_id 隔离，必须传 runtime 实例 uuid，否则不同实例 checkpoint 串到 $default 桶
            RunnableConfig invokeConfig = RunnableConfig.builder().threadId(runtimeUuid).build();
            exe(invokeConfig, GraphInput.args(Map.of()));
        } catch (Exception e) {
            errorWhenExe(e);
        }
    }

    private void exe(RunnableConfig invokeConfig, GraphInput graphInput) {
        //不使用langgraph4j state的update相关方法，无需传入input
        AsyncGenerator<NodeOutput<WfNodeState>> outputs = app.stream(graphInput, invokeConfig);
        streamingResult(wfState, outputs, sseEmitter);

        StateSnapshot<WfNodeState> stateSnapshot = app.getState(invokeConfig);
        wfState.setProcessStatus(WORKFLOW_PROCESS_STATUS_SUCCESS);
        WorkflowRuntime updatedRuntime = workflowRuntimeService.updateOutput(wfRuntimeResp.getId(), wfState);
        wfNodes.stream().filter(item -> stateSnapshot.node().equals(item.getUuid()))
            .findFirst().ifPresent(wfNode -> {
                String nodeMessageTemplate = WorkflowMessageUtil.getNodeMessageTemplate(NodeMessageTemplateEnum.END.getValue());
                if (null != sseEmitter) {
                    WorkflowMessageUtil.notifyAndStoreMessage(wfState, sseEmitter, wfNode, nodeMessageTemplate);
                } else {
                    WorkflowMessageUtil.saveWorkflowMessage(wfState, nodeMessageTemplate);
                }
        });
        if (null != sseEmitter) {
            sseEmitterHelper.sendComplete(user.getId(), sseEmitter, updatedRuntime.getOutput());
        }
    }

    private void errorWhenExe(Exception e) {
        log.error("error", e);
        String nodeMessageTemplate = WorkflowMessageUtil.getNodeMessageTemplate(NodeMessageTemplateEnum.EXCEPTION.getValue());
        String errorMsg = e.getMessage();
        if (errorMsg != null && errorMsg.contains("parallel node doesn't support conditional branch")) {
            errorMsg = "并行节点中不能包含条件分支";
        }
        errorMsg = nodeMessageTemplate + (errorMsg != null ? errorMsg : e.getClass().getSimpleName());
        // 保存会话信息且发送驱动消息事件
        WorkflowMessageUtil.saveWorkflowMessage(wfState, errorMsg);
        if (null != sseEmitter) {
            sseEmitterHelper.sendErrorAndComplete(user.getId(), sseEmitter, errorMsg);
        }
        workflowRuntimeService.updateStatus(wfRuntimeResp.getId(), WORKFLOW_PROCESS_STATUS_FAIL, errorMsg);
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
        //langgraph4j state中的data不做数据存储，只存储元数据
        StreamingChatGenerator<AgentState> generator = wfState.getNodeToStreamingGenerator().get(wfNode.getUuid());
        if (null != generator) {
            resultMap.put("_streaming_messages", generator);
            return resultMap;
        }
        return resultMap;
    }

    /**
     * 流式输出结果
     *
     * @param outputs    输出
     * @param sseEmitter sse emitter
     */
    private void streamingResult(WfState wfState, AsyncGenerator<NodeOutput<WfNodeState>> outputs, SseEmitter sseEmitter) {
        for (NodeOutput<WfNodeState> out : outputs) {
            if (out instanceof StreamingOutput<WfNodeState> streamingOutput) {
                String node = streamingOutput.node();
                String chunk = streamingOutput.chunk();
                log.info("node:{},chunk:{}", node, chunk);
                sendPartialIfConnected("[NODE_CHUNK_" + node + "]", chunk);
            } else {
                // __END__ 是 langgraph4j 的终止伪节点, 无对应业务节点状态, 跳过
                if (END.equals(out.node())) {
                    continue;
                }
                AbstractWfNode abstractWfNode = wfState.getCompletedNodes().stream()
                        .filter(item -> item.getNode().getUuid().endsWith(out.node())).findFirst().orElse(null);
                if (null != abstractWfNode) {
                    WfRuntimeNodeDto runtimeNodeDto = wfState.getRuntimeNodeByNodeUuid(out.node());
                    if (null != runtimeNodeDto) {
                        workflowRuntimeNodeService.updateOutput(runtimeNodeDto.getId(), abstractWfNode.getState());
                        wfState.setOutput(abstractWfNode.getState().getOutputs());
                    } else {
                        log.warn("Can not find runtime node, node uuid:{}", out.node());
                    }
                } else {
                    log.warn("Can not find node state,node uuid:{}", out.node());
                }
            }
        }
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
        try {
            Pair<WorkflowNode, Set<WorkflowNode>> startAndEnds = findStartAndEndNode();
            WorkflowNode startNode = startAndEnds.getLeft();
            this.wfState = new WfState(user, rebuildNodeIOData(runtime.getInput()), runtimeUuid, userId, tokenValue, sseEmitter, sessionId);
            rebuildCompletedNodes(runtime.getId());
            workflowRuntimeService.updateStatus(runtime.getId(), WORKFLOW_PROCESS_STATUS_DOING, "");

            WorkflowGraphBuilder graphBuilder = new WorkflowGraphBuilder(
                    components,
                    wfNodes,
                    wfEdges,
                    this::runNode,
                    this.wfState);
            StateGraph<WfNodeState> mainStateGraph = graphBuilder.build(startNode);

            CompileConfig compileConfig = CompileConfig.builder().checkpointSaver(checkpointSaver)
                    .build();
            app = mainStateGraph.compile(compileConfig);
            RunnableConfig invokeConfig = RunnableConfig.builder().threadId(runtimeUuid).build();
            // GraphInput.resume()：有 checkpoint 时从最新断点（nextNodeId）继续；
            // 注意 stream(Map) 是 GraphArgs 全新执行（从 START 重跑），不能用于续跑
            exe(invokeConfig, GraphInput.resume());
        } catch (Exception e) {
            errorWhenExe(e);
        }
    }

    /**
     * SSE 推送（sseEmitter 可为 null：断点续跑无 SSE 连接时跳过推送，不影响落库）
     */
    private void sendPartialIfConnected(String name, String content) {
        if (null != sseEmitter) {
            SSEEmitterHelper.parseAndSendPartialMsg(sseEmitter, name, content);
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
