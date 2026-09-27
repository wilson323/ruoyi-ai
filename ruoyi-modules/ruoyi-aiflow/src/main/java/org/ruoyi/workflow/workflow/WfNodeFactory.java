package org.ruoyi.workflow.workflow;

import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.node.AbstractWfNode;
import org.ruoyi.workflow.workflow.node.EndNode;
import org.ruoyi.workflow.workflow.node.answer.LLMAnswerNode;
import org.ruoyi.workflow.workflow.node.httpRequest.HttpRequestNode;
import org.ruoyi.workflow.workflow.node.image.ImageNode;
import org.ruoyi.workflow.workflow.node.knowledgeRetrieval.KnowledgeRetrievalNode;
import org.ruoyi.workflow.workflow.node.mailSend.MailSendNode;
import org.ruoyi.workflow.workflow.node.start.StartNode;
import org.ruoyi.workflow.workflow.node.switcher.SwitcherNode;
import org.ruoyi.workflow.workflow.node.googleSearch.GoogleSearchNode;

import java.util.Arrays;

public class WfNodeFactory {
    public static AbstractWfNode create(WorkflowComponent wfComponent, WorkflowNode nodeDefinition,
                                        WfState wfState, WfNodeState nodeState) {
        WfComponentNameEnum component = WfComponentNameEnum.getByName(wfComponent.getName());
        if (component == null) {
            throw new IllegalArgumentException(
                "Unknown workflow component name: " + wfComponent.getName()
                    + ". Allowed: " + Arrays.toString(WfComponentNameEnum.values()));
        }
        AbstractWfNode wfNode = null;
        switch (component) {
            case START -> wfNode = new StartNode(wfComponent, nodeDefinition, wfState, nodeState);
            case LLM_ANSWER -> wfNode = new LLMAnswerNode(wfComponent, nodeDefinition, wfState, nodeState);
            case DALLE3, TONGYI_WANX -> wfNode = new ImageNode(wfComponent, nodeDefinition, wfState, nodeState);
            case KNOWLEDGE_RETRIEVER -> wfNode = new KnowledgeRetrievalNode(wfComponent, nodeDefinition, wfState, nodeState);
            case END -> wfNode = new EndNode(wfComponent, nodeDefinition, wfState, nodeState);
            case MAIL_SEND -> wfNode = new MailSendNode(wfComponent, nodeDefinition, wfState, nodeState);
            case HTTP_REQUEST -> wfNode = new HttpRequestNode(wfComponent, nodeDefinition, wfState, nodeState);
            case SWITCHER -> wfNode = new SwitcherNode(wfComponent, nodeDefinition, wfState, nodeState);
            case GOOGLE_SEARCH -> wfNode = new GoogleSearchNode(wfComponent, nodeDefinition, wfState, nodeState);
            case FAQ_EXTRACTOR ->
                throw new UnsupportedOperationException(
                    "FAQ_EXTRACTOR is registered in WfComponentNameEnum but no node class implemented yet. "
                        + "前端 FaqExtractor 节点需等 IPD 后续治理轮补实现类后再启用。");
            default ->
                throw new IllegalArgumentException(
                    "Unhandled workflow component: " + component.name());
        }
        return wfNode;
    }
}
