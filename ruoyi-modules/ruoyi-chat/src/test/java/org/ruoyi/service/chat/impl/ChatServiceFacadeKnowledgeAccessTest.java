package org.ruoyi.service.chat.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.trace.config.TraceProperties;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.mcp.service.core.AgentScopeMcpToolProviderService;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.ChatSessionOwnershipGuard;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.knowledge.retriever.MultiKnowledgeAugmentorFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * S1 接入点负例：ChatServiceFacade.collectKnowledgeIds 对非法 kid 抛业务异常，
 * 且不进入 augmentor 构建（B1 C2 收敛后经 MultiKnowledgeAugmentorFactory，故以工厂 never 验证）。
 * <p>
 * collectKnowledgeIds 为私有方法且 ChatServiceFacade 公开链路过重（SSE/WebSocket/模型工厂），
 * 与仓内既有做法一致采用反射直测私有方法；其余依赖全部 mock。纯 mock 用例，未覆盖 DDL 合法性。
 * <p>
 * Facade 曾有另一接入点 buildQueryVectorBo（主源码零调用的死方法，B0 在其上的 Gate
 * 仅防复活）——2026-09-28 审计四口收敛轮已删除该方法及其孤儿 import
 * （QueryVectorBo/KnowledgeInfoVo），Gate 在 Facade 的活收敛点自此仅剩 collectKnowledgeIds。
 */
@Tag("dev")
class ChatServiceFacadeKnowledgeAccessTest {

    @Test
    void illegalKnowledgeIdIsRejectedBeforeAugmentorBuild() throws Exception {
        MultiKnowledgeAugmentorFactory factory = mock(MultiKnowledgeAugmentorFactory.class);
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        doThrow(new ServiceException("无权访问该知识库 kid=99")).when(gate).checkRetrievalAccess(99L);

        ChatServiceFacade facade = new ChatServiceFacade(
            mock(IChatModelService.class),
            gate,
            factory,
            mock(SseEmitterManager.class),
            mock(IChatMessageService.class),
            mock(ChatSessionOwnershipGuard.class),
            mock(IAgentService.class),
            mock(AgentScopeMcpToolProviderService.class),
            mock(TraceRecordService.class),
            new TraceProperties());

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setKnowledgeId("99");

        Method collect = ChatServiceFacade.class.getDeclaredMethod("collectKnowledgeIds", ChatRequest.class, AgentVo.class);
        collect.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> collect.invoke(facade, chatRequest, null));
        Throwable cause = thrown.getCause();
        assertInstanceOf(ServiceException.class, cause, "拒绝语义应为业务异常，实际=" + cause);
        assertTrue(cause.getMessage().contains("kid=99"));

        // 未进入 augmentor 构建（B1 C2 收敛后 Facade 委托工厂，以工厂 never 验证）
        verify(factory, never()).augment(any(), any(), any());
    }

    @Test
    void illegalAgentBoundKnowledgeIdIsRejectedForEveryKid() throws Exception {
        KnowledgeAccessGate gate = mock(KnowledgeAccessGate.class);
        doThrow(new ServiceException("无权访问该知识库 kid=7")).when(gate).checkRetrievalAccess(7L);

        ChatServiceFacade facade = new ChatServiceFacade(
            mock(IChatModelService.class),
            gate,
            mock(MultiKnowledgeAugmentorFactory.class),
            mock(SseEmitterManager.class),
            mock(IChatMessageService.class),
            mock(ChatSessionOwnershipGuard.class),
            mock(IAgentService.class),
            mock(AgentScopeMcpToolProviderService.class),
            mock(TraceRecordService.class),
            new TraceProperties());

        AgentVo agentVo = new AgentVo();
        agentVo.setKnowledgeIds(List.of(6L, 7L));
        ChatRequest chatRequest = new ChatRequest();

        Method collect = ChatServiceFacade.class.getDeclaredMethod("collectKnowledgeIds", ChatRequest.class, AgentVo.class);
        collect.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> collect.invoke(facade, chatRequest, agentVo));
        assertInstanceOf(ServiceException.class, thrown.getCause());
        // 逐个校验语义：先过 6L 再撞 7L
        verify(gate).checkRetrievalAccess(6L);
        verify(gate).checkRetrievalAccess(7L);
    }
}
