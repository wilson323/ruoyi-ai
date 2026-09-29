package org.ruoyi.workflow.workflow.node.knowledgeRetrieval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.retrieval.KnowledgeRetrievalService;
import org.ruoyi.workflow.entity.WorkflowComponent;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.util.SpringUtil;
import org.ruoyi.workflow.workflow.NodeProcessResult;
import org.ruoyi.workflow.workflow.WfNodeState;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.data.NodeIOData;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A 口收敛调用验证（B0 审计破坏面 A，§8.3 S1-③ 登记卡）：
 * KnowledgeRetrievalNode.retrieveFromVector 在检索前把（kid, WfState.userId）双参送进
 * KnowledgeAccessGate——工作流节点在 @Async 线程执行无 Sa-Token ThreadLocal，
 * 身份由 WorkflowStarter.streaming 在 HTTP 起始线程经 WfState 透传（ws 握手先例同构）。
 * <p>
 * 关键语义：Gate 拒绝必须向上抛 ServiceException（不被 retrieveFromVector 的
 * catch(Exception) 吞成静默空结果——吞掉即变相绕过 Gate），由 WorkflowEngine 统一
 * 进入节点失败路径。判据本体在 ruoyi-chat 的 UserIdShareKnowledgeAccessGateTest。
 * <p>
 * mock 合法性：kb/embedding 模型/空检索结果均为真实链路可达组合
 * （现网库带 embeddingModel 配置、空结果为常规返回）；纯 mock 用例，未覆盖 DDL 合法性。
 */
@Tag("dev")
class KnowledgeRetrievalNodeAccessTest {

    private static final NodeIOData INPUT = NodeIOData.createByText("input", "用户输入", "hello");

    /** 组装节点：nodeConfig.knowledge_id=9，工作流发起者身份 = WfState.userId */
    private static KnowledgeRetrievalNode newNode(Long userId, String nodeConfig) {
        WorkflowNode def = new WorkflowNode();
        def.setUuid("node-kb");
        def.setTitle("知识检索");
        def.setInputConfig("{\"refInputs\":[],\"userInputs\":[]}");
        def.setNodeConfig(nodeConfig);
        WfState wfState = new WfState(new User(), new ArrayList<>(List.of(INPUT)),
            "rt-uuid", userId, "token", null, 1L);
        WfNodeState nodeState = new WfNodeState();
        nodeState.setUuid("node-kb");
        nodeState.setInputs(new ArrayList<>(List.of(INPUT)));
        return new KnowledgeRetrievalNode(new WorkflowComponent(), def, wfState, nodeState);
    }

    /** checkAndGetConfig 经 SpringUtil.getBean("beanValidator", LocalValidatorFactoryBean.class)
     * 取校验器；mockStatic 未 stub 时返回 null → NPE 被 catch → configValid=false，
     * 误抛「工作流节点配置异常」。所有 mockStatic 块必须先 stub 它。 */
    private static void stubValidator(MockedStatic<SpringUtil> spring) {
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator =
            mock(org.springframework.validation.beanvalidation.LocalValidatorFactoryBean.class);
        when(validator.validate(any())).thenReturn(java.util.Set.of());
        spring.when(() -> SpringUtil.getBean("beanValidator",
                org.springframework.validation.beanvalidation.LocalValidatorFactoryBean.class))
            .thenReturn(validator);
    }

    /** 放行链路的常规 stub：kb 存在 + 向量模型配置存在 + 空检索结果；
     *  返回 retrievalService mock 供「身份透传进检索装配点」断言复用。 */
    private static KnowledgeRetrievalService stubPassThroughBeans(MockedStatic<SpringUtil> spring,
                                                                  KnowledgeAccessGate gate,
                                                                  IKnowledgeInfoService infoService) {
        stubValidator(spring);
        spring.when(() -> SpringUtil.getBean(KnowledgeAccessGate.class)).thenReturn(gate);
        spring.when(() -> SpringUtil.getBean(IKnowledgeInfoService.class)).thenReturn(infoService);

        KnowledgeInfoVo kb = new KnowledgeInfoVo();
        kb.setId(9L);
        kb.setUserId(100L);
        kb.setEmbeddingModel("test-embed");
        kb.setVectorModel("milvus");
        kb.setRetrieveLimit(5);
        kb.setSimilarityThreshold(0.7);
        when(infoService.queryById(9L)).thenReturn(kb);

        IChatModelService chatModelService = mock(IChatModelService.class);
        spring.when(() -> SpringUtil.getBean(IChatModelService.class)).thenReturn(chatModelService);
        ChatModelVo chatModelVo = new ChatModelVo();
        chatModelVo.setApiHost("http://localhost");
        when(chatModelService.selectModelByName("test-embed")).thenReturn(chatModelVo);

        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        spring.when(() -> SpringUtil.getBean(KnowledgeRetrievalService.class)).thenReturn(retrievalService);
        // B2 双参：WfState.userId 显式身份随检索请求进装配链（unstubbed 会返回 null 走空结果分支）
        when(retrievalService.retrieve(any(), any())).thenReturn(List.of());
        return retrievalService;
    }

    @Test
    void gateVerifiedWithWorkflowOwnerIdentityBeforeRetrieval() {
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        KnowledgeRetrievalNode node = newNode(100L, "{\"knowledge_id\":\"9\",\"top_k\":3}");

        KnowledgeRetrievalService retrievalService = null;
        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class)) {
            retrievalService = stubPassThroughBeans(spring, gate, infoService);
            assertDoesNotThrow(node::onProcess);
        }
        // 身份透传正确性：WfState.userId（而非 Sa-Token 会话）作为显式身份进 Gate
        verify(gate).checkRetrievalAccess(9L, 100L);
        // B2：同一身份继续透传到检索装配点（retrieve 双参），不因 @Async 线程降为匿名档
        verify(retrievalService).retrieve(any(), eq(100L));
        verify(infoService).queryById(9L);
    }

    @Test
    void gateDenialPropagatesInsteadOfBeingSwallowedAsEmptyResult() {
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        doThrow(new ServiceException("无权访问该知识库 kid=9"))
            .when(gate).checkRetrievalAccess(9L, 100L);
        KnowledgeRetrievalNode node = newNode(100L, "{\"knowledge_id\":\"9\",\"top_k\":3}");

        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class)) {
            stubValidator(spring);
            spring.when(() -> SpringUtil.getBean(KnowledgeAccessGate.class)).thenReturn(gate);
            // 拒绝必须发生在一切检索副作用之前，且不被 catch(Exception) 吞成 ""（非静默语义）
            ServiceException ex = assertThrows(ServiceException.class, node::onProcess);
            assertTrue(ex.getMessage().contains("kid=9"));
        }
        verify(infoService, never()).queryById(any());
    }

    @Test
    void nullWorkflowUserIsRoutedExplicitlyToGate() {
        // 身份透传完整性：WfState.userId 缺失（如断点续跑无会话）时原样传 null 给 Gate，
        // 由 Gate 判据层 fail-closed（explicitNullUserIdRejected 已覆盖），节点层不做静默改写
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        KnowledgeRetrievalNode node = newNode(null, "{\"knowledge_id\":\"9\",\"top_k\":3}");

        KnowledgeRetrievalService retrievalService = null;
        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class)) {
            retrievalService = stubPassThroughBeans(spring, gate, infoService);
            assertDoesNotThrow(node::onProcess);
        }
        verify(gate).checkRetrievalAccess(9L, null);
        // B2：null 身份原样进检索装配点（装配层按 anon 最严档处理，节点层不改写）
        verify(retrievalService).retrieve(any(), isNull());
    }

    @Test
    void nonNumericKidKeepsSoftErrorWithoutTouchingGate() {
        // 既有行为保持：非数字 kid 返回错误文本、不触 Gate（Gate 只对可解析 kid 生效，
        // 与 collectKnowledgeIds 回退分支的 NumberFormatException 处理同构）
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        KnowledgeRetrievalNode node = newNode(100L, "{\"knowledge_id\":\"abc\"}");

        NodeProcessResult result;
        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class)) {
            stubValidator(spring);
            spring.when(() -> SpringUtil.getBean(KnowledgeAccessGate.class)).thenReturn(gate);
            result = node.onProcess();
        }
        assertTrue(result.getContent().get(0).valueToString().contains("错误：知识库ID格式无效"));
        verify(gate, never()).checkRetrievalAccess(any(), any());
        verify(infoService, never()).queryById(any());
    }
}
