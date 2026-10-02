package org.ruoyi.workflow.workflow.node.knowledgeRetrieval;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.util.SpringUtil;
import org.ruoyi.workflow.workflow.NodeProcessResult;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.WorkflowUtil;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.ruoyi.workflow.workflow.node.AbstractWfNode;

import java.util.ArrayList;
import java.util.List;

import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;

/**
 * 【节点】知识库检索节点
 * 从知识库中检索相关内容
 */
@Slf4j
public class KnowledgeRetrievalNode extends AbstractWfNode {

    public KnowledgeRetrievalNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
    }

    /**
     * 处理知识库检索
     * nodeConfig 格式：
     * {
     *   "knowledge_id": "kb_123",
     *   "top_k": 5,
     *   "similarity_threshold": 0.7,
     *   "retrieval_mode": "vector",
     *   "embedding_model": "text-embedding-3-small",
     *   "return_source": true,
     *   "prompt": "额外的查询改写提示词"
     * }
     *
     * @return 检索结果
     */
    @Override
    public NodeProcessResult onProcess() {
        KnowledgeRetrievalNodeConfig config = checkAndGetConfig(KnowledgeRetrievalNodeConfig.class);

        // 验证知识库ID
        if (StringUtils.isBlank(config.getKnowledgeId())) {
            log.error("Knowledge base ID is required but not provided");
            List<NodeIOData> outputs = new ArrayList<>();
            outputs.add(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", "错误：未配置知识库ID"));
            return NodeProcessResult.builder().content(outputs).build();
        }

        // 获取查询文本
        String queryText = getFirstInputText();
        if (StringUtils.isBlank(queryText)) {
            log.warn("Knowledge retrieval node has no input query, node: {}", state.getUuid());
            // 返回空结果
            List<NodeIOData> outputs = new ArrayList<>();
            outputs.add(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", ""));
            return NodeProcessResult.builder().content(outputs).build();
        }

        log.info("Knowledge retrieval node config: {}", config);
        log.info("Query text: {}", queryText);

        // 如果有自定义提示词，对查询进行改写
        String finalQuery = queryText;
        if (StringUtils.isNotBlank(config.getPrompt())) {
            finalQuery = rewriteQuery(config, queryText);
            log.info("Rewritten query: {}", finalQuery);
        }

        // 根据检索模式执行不同的检索策略
        String retrievalResult;
        String mode = config.getRetrievalMode() != null ? config.getRetrievalMode().toLowerCase() : "vector";

        // 图谱检索需要依赖 graph 模块，暂不支持；vector/hybrid 由统一检索服务处理
        if ("graph".equals(mode)) {
            log.warn("Graph retrieval mode is not supported");
            throw new UnsupportedOperationException("GraphRAG retrieval is not supported");
        }

        retrievalResult = retrieveFromVector(config, finalQuery);

        log.info("Retrieval result length: {}", retrievalResult.length());

        // 构建输出
        List<NodeIOData> outputs = new ArrayList<>();
        outputs.add(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", retrievalResult));

        // 如果需要返回原始查询
        outputs.add(NodeIOData.createByText("query", "", finalQuery));

        return NodeProcessResult.builder().content(outputs).build();
    }

    /**
     * 使用LLM改写查询
     */
    private String rewriteQuery(KnowledgeRetrievalNodeConfig config, String originalQuery) {
        try {
            // 构建改写提示词
            String prompt = WorkflowUtil.renderTemplate(config.getPrompt(), state.getInputs());
            prompt = prompt.replace("{query}", originalQuery);

            log.info("Query rewrite prompt: {}", prompt);

            // 调用LLM进行查询改写
            String rewrittenQuery = invokeLLMSync(config, prompt);

            if (StringUtils.isNotBlank(rewrittenQuery)) {
                log.info("Query rewritten from '{}' to '{}'", originalQuery, rewrittenQuery);
                return rewrittenQuery.trim();
            }

            // 如果改写失败，返回原查询
            return originalQuery;
        } catch (Exception e) {
            log.error("Failed to rewrite query, using original query", e);
            return originalQuery;
        }
    }

    /**
     * 同步调用LLM
     * 使用一个临时的流式处理器来收集完整响应
     */
    private String invokeLLMSync(KnowledgeRetrievalNodeConfig config, String prompt) {
        try {
            // 创建一个StringBuilder来收集LLM响应
            StringBuilder responseBuilder = new StringBuilder();
            Object lock = new Object();
            boolean[] completed = {false};

            // 创建临时节点状态用于LLM调用
            WfNodeState tempState = new WfNodeState();
            tempState.setUuid(state.getUuid() + "_rewrite");
            List<NodeIOData> tempInputs = new ArrayList<>();
            tempInputs.add(NodeIOData.createByText("input", "", prompt));
            tempState.setInputs(tempInputs);

            // 创建临时工作流节点定义
            WorkflowNode tempNode = new WorkflowNode();
            tempNode.setUuid(tempState.getUuid());
            tempNode.setInputConfig(node.getInputConfig());

            // 使用WorkflowUtil调用LLM（流式）
            WorkflowUtil workflowUtil = SpringUtil.getBean(WorkflowUtil.class);

            // 调用流式LLM
            String modelName = StringUtils.isNotBlank(config.getModelName()) ? config.getModelName() : "deepseek-chat";

            workflowUtil.streamingInvokeLLM(
                wfState,
                tempState,
                tempNode,
                modelName,
                prompt,
                ""
            );

            // 等待LLM响应完成（最多等待30秒）
            long startTime = System.currentTimeMillis();
            long timeout = 30000; // 30秒超时

            while (!completed[0] && (System.currentTimeMillis() - startTime) < timeout) {
                synchronized (lock) {
                    // 检查是否有输出
                    if (!tempState.getOutputs().isEmpty()) {
                        for (NodeIOData output : tempState.getOutputs()) {
                            if ("output".equals(output.getName())) {
                                String text = output.valueToString();
                                if (StringUtils.isNotBlank(text)) {
                                    responseBuilder.append(text);
                                    completed[0] = true;
                                    break;
                                }
                            }
                        }
                    }
                }

                if (!completed[0]) {
                    Thread.sleep(100); // 等待100ms后重试
                }
            }

            String result = responseBuilder.toString().trim();
            if (StringUtils.isBlank(result)) {
                log.warn("LLM sync call returned empty response");
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to invoke LLM synchronously", e);
            return "";
        }
    }

    /**
     * 从向量库检索（复用聊天模块的统一检索服务：向量 + 可选混合检索 + 可选重排）。
     * <p>
     * A 口收敛（B0 审计破坏面 A，§8.3 S1-③ 登记卡）：节点 config.knowledgeId 客户端可控
     * （自建工作流即可引用任意 kid，PrivilegeUtil 只校验工作流归属不校验节点引用的库），
     * 进检索上下文前必须过检索访问门。工作流节点在 @Async 线程执行、无 Sa-Token
     * ThreadLocal，按 ws 先例走双参显式身份——userId 由 WorkflowStarter.streaming 在
     * HTTP 起始线程经 WfState 透传（resume 路径同构透传）。
     * <p>
     * Gate 拒绝语义：抛 ServiceException 且置于下方 try 之外——被 catch(Exception) 吞成
     * 静默空结果即变相绕过 Gate（非静默忽略是端口契约），异常交由 WorkflowEngine 统一
     * 进入节点失败路径（重试/DEAD 留痕，见 AbstractWfNodeRetryDeadTest 语义）。
     */
    private String retrieveFromVector(KnowledgeRetrievalNodeConfig config, String query) {
        final Long knowledgeId;
        try {
            knowledgeId = Long.parseLong(config.getKnowledgeId());
        } catch (NumberFormatException e) {
            log.error("Invalid knowledge base ID format: {}", config.getKnowledgeId(), e);
            return "错误：知识库ID格式无效";
        }
        SpringUtil.getBean(org.ruoyi.service.knowledge.KnowledgeAccessGate.class)
            .checkRetrievalAccess(knowledgeId, wfState.getUserId());
        try {
            org.ruoyi.service.knowledge.IKnowledgeInfoService knowledgeInfoService =
                SpringUtil.getBean(org.ruoyi.service.knowledge.IKnowledgeInfoService.class);
            org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo kb = knowledgeInfoService.queryById(knowledgeId);
            if (kb == null) {
                log.error("Knowledge base not found: {}", knowledgeId);
                return "错误：知识库不存在, id=" + knowledgeId;
            }

            org.ruoyi.common.chat.service.chat.IChatModelService chatModelService =
                SpringUtil.getBean(org.ruoyi.common.chat.service.chat.IChatModelService.class);
            org.ruoyi.common.chat.domain.vo.chat.ChatModelVo embModel =
                chatModelService.selectModelByName(kb.getEmbeddingModel());
            if (embModel == null) {
                log.error("Embedding model not found: {}", kb.getEmbeddingModel());
                return "错误：知识库未配置有效的向量模型";
            }

            // 组装检索参数：节点配置优先，混合检索/重排继承知识库配置
            org.ruoyi.domain.bo.vector.QueryVectorBo bo = new org.ruoyi.domain.bo.vector.QueryVectorBo();
            bo.setQuery(query);
            bo.setKid(String.valueOf(knowledgeId));
            bo.setMaxResults(config.getTopK() != null ? config.getTopK() : kb.getRetrieveLimit());
            bo.setSimilarityThreshold(config.getSimilarityThreshold() != null
                ? config.getSimilarityThreshold() : kb.getSimilarityThreshold());
            bo.setEmbeddingModelName(kb.getEmbeddingModel());
            bo.setVectorModelName(kb.getVectorModel());
            bo.setBaseUrl(embModel.getApiHost());

            String mode = config.getRetrievalMode() != null ? config.getRetrievalMode().toLowerCase() : "vector";
            boolean enableHybrid = "hybrid".equals(mode)
                || (kb.getEnableHybrid() != null && kb.getEnableHybrid() == 1);
            bo.setEnableHybrid(enableHybrid);
            bo.setHybridAlpha(kb.getHybridAlpha());
            bo.setEnableRerank(kb.getEnableRerank() != null && kb.getEnableRerank() == 1);
            bo.setRerankModelName(kb.getRerankModel());
            bo.setRerankTopN(kb.getRerankTopN());
            bo.setRerankScoreThreshold(kb.getRerankScoreThreshold());

            org.ruoyi.service.retrieval.KnowledgeRetrievalService retrievalService =
                SpringUtil.getBean(org.ruoyi.service.retrieval.KnowledgeRetrievalService.class);
            // B2 检索接线：@Async 线程无 Sa-Token 上下文，显式传 WfState.userId（与上方 Gate
            // 双参同源）——否则检索入口会把本节点降为匿名档（anon 最严档，E anon 纪律）。
            java.util.List<org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo> results =
                retrievalService.retrieve(bo, wfState.getUserId());
            if (results == null || results.isEmpty()) {
                log.info("Knowledge retrieval returned no results, kid={}, query={}", knowledgeId, query);
                return "";
            }

            // 合并结果
            boolean returnSource = config.getReturnSource() == null || config.getReturnSource();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo vo = results.get(i);
                sb.append(i + 1).append(". ").append(vo.getContent());
                if (returnSource && StringUtils.isNotBlank(vo.getSourceName())) {
                    sb.append("（来源: ").append(vo.getSourceName());
                    if (vo.getScore() != null) {
                        sb.append(String.format(", 相关度: %.3f", vo.getScore()));
                    }
                    sb.append("）");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Failed to retrieve from vector store", e);
            return "";
        }
    }

}
