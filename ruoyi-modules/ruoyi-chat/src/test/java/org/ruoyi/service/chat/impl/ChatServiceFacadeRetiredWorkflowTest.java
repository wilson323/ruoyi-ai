package org.ruoyi.service.chat.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.trace.config.TraceProperties;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.mcp.service.core.AgentScopeMcpToolProviderService;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.ChatSessionOwnershipGuard;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.service.knowledge.retriever.MultiKnowledgeAugmentorFactory;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("dev")
class ChatServiceFacadeRetiredWorkflowTest {
    @Test
    void retiredWorkflowIsRejectedBeforeMessageOrSseAndCannotFallBackToAgent() {
        IChatModelService models = mock(IChatModelService.class);
        SseEmitterManager sse = mock(SseEmitterManager.class);
        IChatMessageService messages = mock(IChatMessageService.class);
        ChatSessionOwnershipGuard ownership = mock(ChatSessionOwnershipGuard.class);
        IAgentService agents = mock(IAgentService.class);
        AgentScopeMcpToolProviderService tools = mock(AgentScopeMcpToolProviderService.class);
        TraceRecordService traces = mock(TraceRecordService.class);
        ChatServiceFacade facade = new ChatServiceFacade(models, mock(KnowledgeAccessGate.class),
            mock(MultiKnowledgeAugmentorFactory.class), sse, messages, ownership, agents, tools,
            traces, new TraceProperties());

        for (Long agentId : new Long[]{null, 7L}) {
            ChatRequest request = new ChatRequest();
            request.setEnableWorkFlow(true);
            request.setAgentId(agentId);
            request.setSessionId(99L);
            request.setContent("不要保存这条退役入口请求");
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> facade.sseChat(request));
            assertTrue(rejected.getMessage().contains(agentId == null ? "已退役" : "参数冲突"));
        }
        verifyNoInteractions(models, sse, messages, ownership, agents, tools, traces);
    }
}
