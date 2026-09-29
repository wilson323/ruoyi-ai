package org.ruoyi.websocket.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.chat.kernel.AgentScopeChatKernel;
import org.ruoyi.chat.kernel.KernelChatSink;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.domain.bo.chat.ChatMessageBo;
import org.ruoyi.common.chat.enums.RoleType;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.factory.ChatServiceFactory;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.ChatSessionOwnershipGuard;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.knowledge.retriever.MultiKnowledgeAugmentorFactory;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.Disposable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1 WS 面内核委托（2026-09-28，ADR-0075 矩阵 #7「包装」）：帧契约镜像 + 身份透传。
 *
 * <p>被测语义（WS 失败语义修复后两条模型执行路径统一适用）：
 * 增量 {@code {"content":"..."}}、结束 {@code [DONE]}（doneSent 防重）、
 * 错误 {@code {"data":"错误:..."}}；流中途异常即使已有内容也不能伪报完成。
 *
 * <p>身份面（矩阵 #4）：userId 取 WS 握手 attributes（禁来自请求参数），缺失身份由归属门拒绝；
 * 无智能体时 agent 折叠段 {@code chat-model}（镜像 ChatServiceFacade 同名折叠常量）。
 *
 * <p>口径：内核以 mock 捕获 sink 与入参（委托映射单测，不引 AgentScope 引擎）；
 * 帧断言走真实 {@code ObjectMapper} 序列化路径，与前端接收字节面对齐。
 */
@Tag("dev")
@DisplayName("W1 WS 面内核委托：帧契约镜像 + 身份透传（ADR-0075 矩阵 #4/#7）")
class MpChatWebSocketHandlerKernelDelegateTest {

    private IChatModelService chatModelService;
    private IAgentService agentService;
    private IChatMessageService chatMessageService;
    private AgentScopeChatKernel kernel;
    private MpChatWebSocketHandler handler;
    private WebSocketSession wsSession;
    private Map<String, Object> attributes;
    private final List<String> frames = new ArrayList<>();

    /** kernel.stream 捕获：入参数组（projectId, userId, agentId, sessionId, userText, systemPrompt, model, sink）。 */
    private Object[] streamArgs;
    private KernelChatSink streamSink;
    private Disposable streamDisposable;

    @BeforeEach
    void setUp() throws Exception {
        frames.clear();
        streamArgs = null;
        streamSink = null;

        chatModelService = mock(IChatModelService.class);
        agentService = mock(IAgentService.class);
        chatMessageService = mock(IChatMessageService.class);
        when(chatMessageService.insertByBo(any(ChatMessageBo.class))).thenReturn(true);
        kernel = mock(AgentScopeChatKernel.class);

        ChatModelVo modelVo = new ChatModelVo();
        modelVo.setModelName("m1");
        modelVo.setProviderCode("minimax");
        when(chatModelService.selectModelByName("m1")).thenReturn(modelVo);

        ChatSessionOwnershipGuard ownershipGuard = mock(ChatSessionOwnershipGuard.class);
        doAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            Long sessionId = invocation.getArgument(1);
            if (userId == null || sessionId == null) {
                throw new IllegalArgumentException("unauthenticated session");
            }
            return null;
        }).when(ownershipGuard).requireOwned(any(), any());

        handler = new MpChatWebSocketHandler(
            mock(ChatServiceFactory.class),
            chatModelService,
            agentService,
            mock(KnowledgeAccessGate.class),
            mock(MultiKnowledgeAugmentorFactory.class),
            chatMessageService,
            ownershipGuard,
            new ObjectMapper());

        Field field = MpChatWebSocketHandler.class.getDeclaredField("agentScopeChatKernel");
        field.setAccessible(true);
        field.set(handler, kernel);
        Field enabled = MpChatWebSocketHandler.class.getDeclaredField("agentScopeEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(handler, true);

        attributes = new HashMap<>();
        wsSession = mock(WebSocketSession.class);
        when(wsSession.getAttributes()).thenReturn(attributes);
        when(wsSession.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            frames.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(wsSession).sendMessage(any(TextMessage.class));

        streamDisposable = mock(Disposable.class);
        doAnswer(invocation -> {
            streamArgs = invocation.getArguments();
            streamSink = (KernelChatSink) invocation.getArgument(7);
            return streamDisposable;
        }).when(kernel).stream(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("智能体对话：content 增量 + [DONE] 帧序，reasoning/mcp_tool 无 WS 帧，用户和助手消息均落库")
    void kernelDelegateMapsFramesToWsContract() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);
        AgentVo agentVo = new AgentVo();
        agentVo.setId(7L);
        agentVo.setSystemPrompt("你是客服");
        when(agentService.queryById(7L)).thenReturn(agentVo);

        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"你好\",\"agentId\":\"7\",\"sessionId\":\"100\",\"model\":\"m1\"}"));

        streamSink.onContent("你");
        streamSink.onContent("好");
        streamSink.onReasoning("推理片段");
        streamSink.onMcpTool("tool-1", "allowed", "ok");
        streamSink.onComplete();
        assertEquals(0, activeStreamSessionCount(), "终态必须移除空订阅集合");

        assertEquals(List.of("{\"content\":\"你\"}", "{\"content\":\"好\"}", "[DONE]"), frames,
            "WS 帧必须严格为增量 JSON + [DONE]，reasoning/mcp_tool 无契约帧不得外泄");

        assertEquals("chat", streamArgs[0], "projectId 折叠段");
        assertEquals("42", streamArgs[1], "userId 取握手身份");
        assertEquals("7", streamArgs[2], "agentId 段取智能体 id");
        assertEquals("100", streamArgs[3], "sessionId 段");
        assertEquals("你好", streamArgs[4], "RAG 增强后内容透传（无知识库=原文）");
        assertEquals("你是客服", streamArgs[5], "agentVo.systemPrompt 透传内核 sysPrompt");
        KernelModelRequest modelRequest = (KernelModelRequest) streamArgs[6];
        assertEquals("m1", modelRequest.modelName(), "请求 model 字段路由进内核模型请求（W2 收口）");
        assertEquals("minimax", modelRequest.providerCode(), "厂商码随 ChatModelVo 透传");

        ArgumentCaptor<ChatMessageBo> saved = ArgumentCaptor.forClass(ChatMessageBo.class);
        verify(chatMessageService, org.mockito.Mockito.times(2)).insertByBo(saved.capture());
        assertEquals(List.of(RoleType.USER.getName(), RoleType.ASSISTANT.getName()),
            saved.getAllValues().stream().map(ChatMessageBo::getRole).toList());
        for (ChatMessageBo row : saved.getAllValues()) {
            assertEquals(42L, row.getUserId());
            assertEquals(100L, row.getSessionId());
            assertEquals("你好", row.getContent());
            assertEquals("m1", row.getModelName());
        }
    }

    @Test
    @DisplayName("普通模型对话：无智能体时 agent 折叠段 chat-model，systemPrompt 回退请求字段")
    void modelChatFoldsAgentSegmentAndForwardsRequestSystemPrompt() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);

        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"hi\",\"sessionId\":\"100\",\"model\":\"m1\",\"systemPrompt\":\"用一句话回答\"}"));

        assertEquals("chat-model", streamArgs[2], "无智能体折叠段（镜像 ChatServiceFacade）");
        assertEquals("用一句话回答", streamArgs[5], "请求级 systemPrompt 回退透传");
    }

    @Test
    @DisplayName("零输出即错误：对外固定脱敏错误帧恰一次，无 [DONE]，仅用户消息落库，内情不外泄")
    void kernelDelegateErrorSendsSafeErrorFrameOnce() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);

        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"你好\",\"sessionId\":\"100\",\"model\":\"m1\"}"));

        streamSink.onError("KERNEL_STREAM_ERROR", "boom");

        assertEquals(List.of("{\"data\":\"错误:对话处理失败，请稍后重试\"}"), frames,
            "错误帧必须为 {\"data\":\"错误:...\"} 恰一次且固定脱敏文案");
        assertFalse(frames.get(0).contains("boom"), "内核错误详情不得外泄前端");
        verify(chatMessageService, org.mockito.Mockito.times(1)).insertByBo(any(ChatMessageBo.class));
    }

    @Test
    @DisplayName("流中途异常且已有内容：仅发错误，不伪报完成")
    void midStreamErrorSendsErrorNotDone() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);

        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"你好\",\"sessionId\":\"100\",\"model\":\"m1\"}"));

        streamSink.onContent("部分");
        streamSink.onError("KERNEL_STREAM_ERROR", "boom");

        assertEquals(List.of("{\"content\":\"部分\"}",
                "{\"data\":\"错误:对话处理失败，请稍后重试\"}"), frames,
            "已有内容的中途异常必须给错误帧，不能伪报完成");
        verify(chatMessageService, org.mockito.Mockito.times(1)).insertByBo(any(ChatMessageBo.class));
    }

    @Test
    @DisplayName("内核完成时助手消息保存先于 [DONE]")
    void assistantSavePrecedesDone() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);
        when(chatMessageService.insertByBo(any(ChatMessageBo.class))).thenAnswer(invocation -> {
            ChatMessageBo row = invocation.getArgument(0);
            if (RoleType.ASSISTANT.getName().equals(row.getRole())) {
                assertFalse(frames.contains("[DONE]"), "保存前不能宣布完成");
            }
            return true;
        });

        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"你好\",\"sessionId\":\"100\",\"model\":\"m1\"}"));
        streamSink.onContent("回复");
        streamSink.onComplete();

        assertEquals("[DONE]", frames.get(frames.size() - 1));
    }

    @Test
    @DisplayName("助手消息保存返回失败时仅发错误，不发 [DONE]")
    void assistantSaveFailureCannotComplete() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);
        when(chatMessageService.insertByBo(any(ChatMessageBo.class))).thenAnswer(invocation -> {
            ChatMessageBo row = invocation.getArgument(0);
            return !RoleType.ASSISTANT.getName().equals(row.getRole());
        });

        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"你好\",\"sessionId\":\"100\",\"model\":\"m1\"}"));
        streamSink.onContent("回复");
        streamSink.onComplete();

        assertEquals(List.of("{\"content\":\"回复\"}",
                "{\"data\":\"错误:对话处理失败，请稍后重试\"}"), frames);
    }

    @Test
    @DisplayName("显式模型不存在时拒绝本轮，不回退默认模型")
    void unknownExplicitModelDoesNotFallback() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);
        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"你好\",\"sessionId\":\"100\",\"model\":\"missing-model\"}"));

        assertNull(streamArgs);
        verify(chatModelService, never()).queryList(any());
        verify(chatMessageService, never()).insertByBo(any(ChatMessageBo.class));
        assertEquals(List.of("{\"data\":\"错误:指定的对话模型不可用\"}"), frames);
    }

    @Test
    @DisplayName("用户消息保存失败时拒绝启动内核且无完成帧")
    void userSaveFailureCannotStartKernel() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);
        when(chatMessageService.insertByBo(any(ChatMessageBo.class))).thenReturn(false);

        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"你好\",\"sessionId\":\"100\",\"model\":\"m1\"}"));

        assertNull(streamArgs, "用户消息落库失败不得启动模型");
        assertEquals(List.of("{\"data\":\"错误:对话处理失败，请稍后重试\"}"), frames);
    }

    @Test
    @DisplayName("缺失身份：归属门拒绝，内核与消息存储零触达")
    void missingIdentityIsRejectedBeforeKernel() {
        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"hi\",\"model\":\"m1\"}"));

        assertNull(streamArgs, "未认证会话不得进入内核");
        verify(chatMessageService, never()).insertByBo(any(ChatMessageBo.class));
    }

    @Test
    @DisplayName("连接在流注册期间已关闭时取消并清空登记")
    void closedBeforeStreamRegistrationDoesNotLeak() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);
        when(wsSession.isOpen()).thenReturn(false);

        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"hi\",\"sessionId\":\"100\",\"model\":\"m1\"}"));

        verify(streamDisposable).dispose();
        assertEquals(0, activeStreamSessionCount());
    }

    @Test
    @DisplayName("WS 连接关闭会取消在途内核流")
    void closedConnectionCancelsKernelStream() {
        attributes.put(MpChatHandshakeInterceptor.USER_ID_KEY, 42L);
        handler.handleTextMessage(wsSession, new TextMessage(
            "{\"content\":\"hi\",\"sessionId\":\"100\",\"model\":\"m1\"}"));
        handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
        verify(streamDisposable).dispose();
        assertEquals(0, activeStreamSessionCount(), "关闭连接不得残留订阅登记");
    }

    @SuppressWarnings("unchecked")
    private int activeStreamSessionCount() {
        try {
            Field field = MpChatWebSocketHandler.class.getDeclaredField("activeKernelStreams");
            field.setAccessible(true);
            return ((Map<WebSocketSession, ?>) field.get(handler)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
