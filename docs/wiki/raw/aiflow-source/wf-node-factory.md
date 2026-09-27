---
source: file:///Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WfNodeFactory.java
collected: 2026-09-27
published: 2026-09-27
topic: aiflow-source
note: 刷新自 R27 修复（P0-N1），case DALLE3/TONGYI_WANX 合并 + default 抛异常 + null 早抛
---

# WfNodeFactory.java

```java
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
                        + "前端 Dalle3/FaqExtractor 节点需等 IPD 后续治理轮补实现类后再启用。");
            default ->
                throw new IllegalArgumentException(
                    "Unhandled workflow component: " + component.name());
        }
        return wfNode;
    }
}
```

## 关联枚举

`WfComponentNameEnum` 11 项（YAML）：

| 枚举名 | 字符串值 | 节点类 | 工厂分支 |
|---|---|---|---|
| `START` | "Start" | StartNode | case START |
| `END` | "End" | EndNode | case END |
| `LLM_ANSWER` | "Answer" | LLMAnswerNode | case LLM_ANSWER |
| `DALLE3` | "Dalle3" | ImageNode | case DALLE3（与 TONGYI_WANX 共用） |
| `TONGYI_WANX` | "Tongyiwanx" | ImageNode | case TONGYI_WANX |
| `FAQ_EXTRACTOR` | "FaqExtractor" | 未实现 | case FAQ_EXTRACTOR → 抛 UnsupportedOperationException |
| `KNOWLEDGE_RETRIEVER` | "KnowledgeRetrieval" | KnowledgeRetrievalNode | case KNOWLEDGE_RETRIEVER |
| `SWITCHER` | "Switcher" | SwitcherNode | case SWITCHER |
| `GOOGLE_SEARCH` | "Google" | GoogleSearchNode | case GOOGLE_SEARCH |
| `MAIL_SEND` | "MailSend" | MailSendNode | case MAIL_SEND |
| `HTTP_REQUEST` | "HttpRequest" | HttpRequestNode | case HTTP_REQUEST |

## null 防御

工厂在 L21-26 校验 `getByName(name)` 返回 null（未注册名 / 拼错名）→ 立即抛 `IllegalArgumentException`，避免后续 switch NPE。
