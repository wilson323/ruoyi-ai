package org.ruoyi.workflow.workflow.node.switcher;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.service.WorkflowNodeService;
import org.ruoyi.workflow.util.JsonUtil;
import org.ruoyi.workflow.workflow.NodeProcessResult;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.ruoyi.workflow.workflow.node.AbstractWfNode;
import org.ruoyi.workflow.workflow.node.enmus.NodeMessageTemplateEnum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 条件分支节点：按配置顺序评估分支（存量 conditions 组合翻译成 SpEL，或直接用可选 spel 表达式，
 * 统一在 SwitcherCaseEvaluator 沙箱中求值），命中则路由到对应分支，全部未命中走默认分支。
 *
 * <p>D2 修复：条件评估异常不再吞成 error 输出（此前被下游 WorkflowGraphBuilder 的
 * next 路由 NPE 覆盖），真实错误一律携带「哪个 switcher 节点、哪条 case、什么表达式/操作数」
 * 上下文抛出；确定性错误（配置/表达式类）经 {@link SwitcherEvaluationException.Fatal}
 * 标记为不可重试，瞬时性错误（取值时 DB/IO 故障）走 AbstractWfNode 有界重试。
 */
@Slf4j
public class SwitcherNode extends AbstractWfNode {

    public SwitcherNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
    }

    @Override
    public NodeProcessResult onProcess() {
        SwitcherNodeConfig config = loadConfig();
        List<NodeIOData> inputs = state.getInputs();
        int caseCount = config.getCases() != null ? config.getCases().size() : 0;
        log.info("条件分支节点处理中，分支数量: {}", caseCount);
        String nodeMessageTemplate = messageTemplate(NodeMessageTemplateEnum.SWITCH.getValue());

        if (config.getCases() != null) {
            for (int i = 0; i < config.getCases().size(); i++) {
                SwitcherCase switcherCase = config.getCases().get(i);
                if (!evaluateCase(switcherCase, i + 1)) {
                    continue;
                }
                String targetNodeUuid = switcherCase.getTargetNodeUuid();
                if (StringUtils.isBlank(targetNodeUuid)) {
                    log.warn("分支 {} 匹配但目标节点UUID为空，跳过到下一个分支", i + 1);
                    continue;
                }
                findNodeAndNotify(targetNodeUuid, nodeMessageTemplate);
                log.info("分支 {} 匹配，跳转到节点: {}", i + 1, targetNodeUuid);
                return NodeProcessResult.builder()
                        .content(buildOutputs(inputs, String.valueOf(i + 1), switcherCase.getUuid(), targetNodeUuid))
                        .nextNodeUuid(targetNodeUuid)
                        .build();
            }
        }

        log.info("没有分支匹配，使用默认分支: {}", config.getDefaultTargetNodeUuid());
        findNodeAndNotify(config.getDefaultTargetNodeUuid(), nodeMessageTemplate);
        if (StringUtils.isBlank(config.getDefaultTargetNodeUuid())) {
            log.warn("默认目标节点UUID为空，工作流可能在此停止");
        }
        String defaultTarget = config.getDefaultTargetNodeUuid() != null ? config.getDefaultTargetNodeUuid() : "";
        return NodeProcessResult.builder()
                .content(buildOutputs(inputs, "default", null, defaultTarget))
                .nextNodeUuid(config.getDefaultTargetNodeUuid())
                .build();
    }

    /**
     * 评估单条 case：spel 原始表达式优先，否则走存量 conditions 组合（翻译成 SpEL 求值）。
     * 评估异常统一带上下文抛出，不吞成 false / error 输出（D2）。
     */
    private boolean evaluateCase(SwitcherCase switcherCase, int caseIndex) {
        String caseContext = "条件分支节点 [uuid=" + node.getUuid() + ", title=" + node.getTitle()
                + "] case [序号=" + caseIndex + ", uuid=" + switcherCase.getUuid() + "]";
        try {
            if (StringUtils.isNotBlank(switcherCase.getSpel())) {
                return SwitcherCaseEvaluator.evaluateSpel(switcherCase.getSpel().trim(), caseContext, buildSpelVars());
            }
            return SwitcherCaseEvaluator.evaluateLegacyCase(switcherCase, caseContext,
                    condition -> resolveConditionValue(condition, caseContext));
        } catch (SwitcherEvaluationException e) {
            throw e;
        } catch (Exception e) {
            throw new SwitcherEvaluationException(caseContext + " 评估异常: " + e.getMessage(), e);
        }
    }

    /**
     * 条件取值（存量语义保留）：未解析到时按空串评估（缺失值兜底，旧流程零迁移）。
     */
    private String resolveConditionValue(SwitcherCase.Condition condition, String caseContext) {
        String actualValue = getValueFromInputs(condition.getNodeUuid(), condition.getNodeParamName(), state.getInputs());
        if (actualValue == null) {
            log.warn("{} 未找到节点: {}, 参数: {} 的值，按存量语义按空串评估 - 可用输入: {}",
                    caseContext, condition.getNodeUuid(), condition.getNodeParamName(),
                    state.getInputs().stream().map(NodeIOData::getName).toList());
        }
        return actualValue;
    }

    /** 原始 spel 表达式的变量表：各输入参数（名称 → 值），另以只读 Map #vars 暴露 */
    private Map<String, String> buildSpelVars() {
        Map<String, String> vars = new LinkedHashMap<>();
        for (NodeIOData input : state.getInputs()) {
            if (input.getName() != null) {
                vars.put(input.getName(), input.valueToString());
            }
        }
        return vars;
    }

    /**
     * 分支输出契约（WorkflowEngine 据 nextNodeUuid 写 "next" 路由键，下游依赖 matched_case/case_uuid/target_node）：
     * 非 input 参数透传 + 缺 output 时由 input 派生 + 分支匹配信息。
     */
    private List<NodeIOData> buildOutputs(List<NodeIOData> inputs, String matchedCase, String caseUuid, String targetNodeUuid) {
        List<NodeIOData> outputs = new ArrayList<>();
        inputs.stream()
                .filter(item -> !"input".equals(item.getName()))
                .forEach(outputs::add);
        boolean hasOutput = outputs.stream().anyMatch(item -> "output".equals(item.getName()));
        if (!hasOutput) {
            inputs.stream()
                    .filter(item -> "input".equals(item.getName()))
                    .findFirst()
                    .ifPresent(inputParam -> {
                        String title = inputParam.getContent() != null && inputParam.getContent().getTitle() != null
                                ? inputParam.getContent().getTitle() : "";
                        outputs.add(NodeIOData.createByText("output", title, inputParam.valueToString()));
                        log.debug("从输入创建输出参数供下游节点使用");
                    });
        }
        outputs.add(NodeIOData.createByText("matched_case", "switcher", matchedCase));
        if (caseUuid != null) {
            outputs.add(NodeIOData.createByText("case_uuid", "switcher", caseUuid));
        }
        outputs.add(NodeIOData.createByText("target_node", "switcher", targetNodeUuid));
        return outputs;
    }

    /**
     * 根据节点ID查询对应节点并广播提示消息。消息通知为尽力而为：失败只告警，不中断路由。
     */
    private void findNodeAndNotify(String targetNodeUuid, String nodeMessageTemplate) {
        if (StringUtils.isBlank(targetNodeUuid)) {
            return;
        }
        try {
            WorkflowNode workflowNode = lookupWorkflowNode(targetNodeUuid);
            if (null != workflowNode) {
                notifyAndStoreMessage(wfState, nodeMessageTemplate + workflowNode.getTitle());
            }
        } catch (Exception e) {
            log.warn("条件分支节点提示消息发送失败（不影响路由）: {}", targetNodeUuid, e);
        }
    }

    /**
     * 从输入数据中获取指定节点的参数值（存量语义保留）：
     * 当前输入按名匹配（同名取最后）→ 指定节点历史输出 → 节点 user_inputs 配置指向的 input → output 缺失时回退 input。
     */
    private String getValueFromInputs(String nodeUuid, String paramName, List<NodeIOData> inputs) {
        log.debug("从节点UUID '{}' 搜索参数 '{}'", nodeUuid, paramName);

        String result = null;
        for (NodeIOData input : inputs) {
            if (paramName.equals(input.getName())) {
                result = input.valueToString();
            }
        }
        if (result != null) {
            return result;
        }

        if (StringUtils.isNotBlank(nodeUuid)) {
            for (NodeIOData output : wfState.getIOByNodeUuid(nodeUuid)) {
                if (paramName.equals(output.getName())) {
                    result = output.valueToString();
                }
            }
            // 根据UUID查询对应节点是否存在Param(替换成Input)
            result = findParamValueInNode(nodeUuid, paramName, inputs, result);
            if (result != null) {
                return result;
            }
        }

        // 特殊处理：如果找的是 'output' 但没找到，尝试找 'input'
        if ("output".equals(paramName)) {
            String inputValue = getValueFromInputs(nodeUuid, "input", inputs);
            if (inputValue != null) {
                return inputValue;
            }
        }
        log.warn("在输入或节点 '{}' 的输出中未找到参数 '{}'", nodeUuid, paramName);
        return null;
    }

    /**
     * 根据节点UUID和参数名查找对应的输入值。
     * 意义：修复开始节点参数名错误的问题——节点 user_inputs 中声明的参数实际取节点的 input 值。
     */
    private String findParamValueInNode(String nodeUuid, String paramName, List<NodeIOData> inputs, String result) {
        WorkflowNode workflowNode = lookupWorkflowNode(nodeUuid);
        if (ObjectUtils.isEmpty(workflowNode)) {
            return result;
        }
        String inputConfig = workflowNode.getInputConfig();
        if (StringUtils.isBlank(inputConfig)) {
            return result;
        }
        try {
            JsonNode configJson = JsonUtil.toJsonNode(inputConfig);
            if (configJson == null) {
                return result;
            }
            JsonNode userInputs = configJson.get("user_inputs");
            if (userInputs == null || !userInputs.isArray()) {
                return result;
            }
            for (JsonNode inputNode : userInputs) {
                if (inputNode.has("name") && paramName.equals(inputNode.get("name").asText())) {
                    String value = getValueFromInputs(nodeUuid, "input", inputs);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            // 不抛出异常，返回默认结果，避免中断整个流程（存量语义保留）
            log.error("解析节点 '{}' 输入配置失败，参数名: {}, 配置内容: {}", nodeUuid, paramName, inputConfig, e);
        }
        return result;
    }

    // ==================== 测试/运行环境接缝 ====================

    /** 加载节点配置（基类 JSON 解析 + 校验），测试可覆写注入配置 */
    protected SwitcherNodeConfig loadConfig() {
        return checkAndGetConfig(SwitcherNodeConfig.class);
    }

    /** 按 UUID 查询工作流节点定义（DB），测试可覆写免除 DB 依赖 */
    protected WorkflowNode lookupWorkflowNode(String nodeUuid) {
        return workflowNodeService().lambdaQuery().eq(WorkflowNode::getUuid, nodeUuid).one();
    }

    private WorkflowNodeService workflowNodeService() {
        return SpringUtils.getBean(WorkflowNodeService.class);
    }

    /** 提示消息模板：sys_config 缺失/不可用时回退内置默认模板，不中断工作流 */
    private String messageTemplate(String configKey) {
        try {
            return getNodeMessageTemplate(configKey);
        } catch (Exception e) {
            log.warn("获取节点消息模板失败，回退内置默认模板: {}", configKey, e);
            return NodeMessageTemplateEnum.getDefaultTemplate(configKey);
        }
    }
}
