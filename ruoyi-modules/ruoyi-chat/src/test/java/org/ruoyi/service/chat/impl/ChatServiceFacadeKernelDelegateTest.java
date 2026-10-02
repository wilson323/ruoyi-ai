package org.ruoyi.service.chat.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.hutool.extra.spring.SpringUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.ruoyi.chat.kernel.AgentScopeChatKernel;
import org.ruoyi.chat.kernel.KernelChatSink;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.enums.RoleType;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.chat.service.workFlow.IWorkFlowStarterService;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.sse.dto.SseEventDto;
import org.ruoyi.common.sse.dto.SseMessageDto;
import org.ruoyi.common.sse.utils.SseMessageUtils;
import org.ruoyi.common.trace.config.TraceProperties;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.mcp.service.core.AgentScopeMcpToolProviderService;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.ChatSessionOwnershipGuard;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.knowledge.retriever.MultiKnowledgeAugmentorFactory;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import java.util.function.Consumer;

/**
 * W1 内核委托接线（ADR-0075 回滚点 + 矩阵 #7 帧出口）。
 *
 * <p>被测语义：
 * <ol>
 *   <li>{@code handleKernelChat} 帧映射严格镜像 {@code createModelChatResponseHandler}
 *       （content/reasoning 增量、mcp_tool 事件、done 前保存助手消息、error 走固定安全文案、
 *       completeConnection 恰一次收口）——前端零改动硬约束；</li>
 *   <li>委托参数四维收口：projectId 折叠常量段（chat 平台域无项目维度，登记差异）、
 *       无 agentId 时折叠 chat-model 段、AgentVo.systemPrompt 透传；</li>
 *   <li>错误帧不发 done、不保存助手消息、错误细节不得外泄（SseMessageUtils.sendError
 *       固定安全文案，忽略入参——镜像既有 onError 语义）。</li>
 * </ol>
 *
 * <p>口径：反射直测私有方法（ChatServiceFacadeKnowledgeAccessTest 仓内惯例）；
 * 帧出口走<b>真实</b> {@code SseMessageUtils} 编码路径（{@code SseMessageUtils} 静态初始化依赖
 * Spring 容器，经 mockStatic(hutool SpringUtil) 引导 MANAGER 为共享 mock，验证真实 SseMessageDto
 * 帧编码——比 mockStatic(SseMessageUtils) 更强）。12 参构造面由
 * ChatServiceFacadeKnowledgeAccessTest 哨兵锁死，本类顺带复证新字段不进构造器。
 */
@Tag("dev")
@DisplayName("W1 内核委托：帧出口镜像 + 四维收口（ADR-0075 矩阵 #7）")
class ChatServiceFacadeKernelDelegateTest {

    private static final Long USER_ID = 42L;
    private static final Long SESSION_ID = 9001L;
    private static final String SESSION_KEY = "9001";

    private static SseEmitterManager sseManager;
    private Disposable streamDisposable;

    @BeforeAll
    static void bootSseUtils() {
        sseManager = mock(SseEmitterManager.class);
        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class)) {
            spring.when(() -> SpringUtil.getProperty("sse.enabled", Boolean.class, true))
                .thenReturn(Boolean.TRUE);
            spring.when(() -> SpringUtil.getBean(SseEmitterManager.class)).thenReturn(sseManager);
            Class.forName(SseMessageUtils.class.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void resetSseManager() {
        reset(sseManager);
    }

    private ChatServiceFacade newFacade(IChatMessageService chatMessageService) {
        return newFacade(chatMessageService, mock(KnowledgeAccessGate.class));
    }

    private ChatServiceFacade newFacade(IChatMessageService chatMessageService,
                                        KnowledgeAccessGate accessGate) {
        when(chatMessageService.insertByBo(any())).thenReturn(true);
        AgentScopeMcpToolProviderService provider = mock(AgentScopeMcpToolProviderService.class);
        AgentScopeMcpToolProviderService.ToolSession tools = mock(AgentScopeMcpToolProviderService.ToolSession.class);
        when(tools.toolkit()).thenReturn(new io.agentscope.core.tool.Toolkit());
        when(provider.createSession(org.mockito.ArgumentMatchers.nullable(List.class))).thenReturn(tools);
        return new ChatServiceFacade(
            mock(IChatModelService.class),
            accessGate,
            mock(MultiKnowledgeAugmentorFactory.class),
            mock(SseEmitterManager.class),
            chatMessageService,
            mock(ChatSessionOwnershipGuard.class),
            mock(IWorkFlowStarterService.class),
            mock(IAgentService.class),
            provider,
            mock(TraceRecordService.class),
            new TraceProperties());
    }

    private void injectKernel(ChatServiceFacade facade, AgentScopeChatKernel kernel) throws Exception {
        Field field = ChatServiceFacade.class.getDeclaredField("agentScopeChatKernel");
        field.setAccessible(true);
        field.set(facade, kernel);
    }

    private KernelChatSink invokeHandleKernelChat(ChatServiceFacade facade,
                                                  ChatRequest request,
                                                  AgentVo agentVo,
                                                  AgentScopeChatKernel kernel) throws Exception {
        Method method = null;
        for (Method candidate : ChatServiceFacade.class.getDeclaredMethods()) {
            if ("handleKernelChat".equals(candidate.getName()) && candidate.getParameterCount() == 3) {
                method = candidate;
                break;
            }
        }
        assertNotNull(method, "handleKernelChat(ChatRequest,AgentVo,TraceRunHandle) 应存在");
        method.setAccessible(true);
        KernelChatSink[] captured = new KernelChatSink[1];
        streamDisposable = mock(Disposable.class);
        doAnswer(invocation -> {
            captured[0] = invocation.getArgument(10);
            return streamDisposable;
        }).when(kernel).stream(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        method.invoke(facade, request, agentVo, null);
        return captured[0];
    }

    private ChatRequest newRequest(String content) {
        ChatRequest request = new ChatRequest();
        request.setUserId(USER_ID);
        request.setSessionId(SESSION_ID);
        request.setContent(content);
        request.setModel("MiniMax-M3");
        request.setEmitter(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());
        return request;
    }

    private List<SseMessageDto> capturedFrames() {
        ArgumentCaptor<SseMessageDto> captor = ArgumentCaptor.forClass(SseMessageDto.class);
        verify(sseManager, org.mockito.Mockito.atLeast(0)).publishMessage(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void nativeFinalReplacementIsWhatGetsPersisted() throws Exception {
        IChatMessageService messages = mock(IChatMessageService.class);
        ChatServiceFacade facade = newFacade(messages);
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);
        KernelChatSink sink = invokeHandleKernelChat(facade, newRequest("hello"), null, kernel);
        sink.onContent("old reply");
        sink.onResult(io.agentscope.core.message.Msg.builder()
            .role(io.agentscope.core.message.MsgRole.ASSISTANT).textContent("old reply")
            .metadata(java.util.Map.of("replacementText", "replacement reply")).build());
        sink.onComplete();
        verify(messages).insertByBo(argThat(row -> "replacement reply".equals(row.getContent())));
        var frames = capturedFrames();
        assertEquals(3, frames.size());
        assertEquals("content", frames.get(1).getEventDto().getEvent());
        assertEquals("replacement reply", frames.get(1).getEventDto().getContent());
        assertEquals(Boolean.TRUE, frames.get(1).getEventDto().getReplace());
        assertEquals("done", frames.get(2).getEventDto().getEvent());
    }

    @Test
    @DisplayName("委托帧映射镜像既有契约：content/reasoning/mcp_tool/done + 助手消息 + completeConnection 恰一次")
    void kernelDelegateMapsFramesToSseContract() throws Exception {
        IChatMessageService chatMessageService = mock(IChatMessageService.class);
        ChatServiceFacade facade = newFacade(chatMessageService);
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);

        ChatRequest request = newRequest("你好");
        KernelChatSink sink = invokeHandleKernelChat(facade, request, null, kernel);

        // 四维收口：projectId=chat 折叠段、无 agentId 折叠 chat-model、systemPrompt=null
        verify(kernel).stream(
            eq("chat"), eq(String.valueOf(USER_ID)), eq("chat-model"), eq(SESSION_KEY),
            eq("你好"), argThat(s -> s != null && s.contains("只有本次登记的工具")),
            argThat(m -> m != null && "MiniMax-M3".equals(m.modelName())), any(), any(), any(), any());

        sink.onContent("你好，");
        sink.onContent("世界");
        sink.onReasoning("思考中");
        sink.onMcpTool("tool-1", "allowed", "r");
        sink.onComplete();

        List<SseMessageDto> frames = capturedFrames();
        assertEquals(5, frames.size(), "帧序列应为 content×2 + reasoning + mcp_tool + done，实际=" + frames.size());
        SseEventDto c1 = frames.get(0).getEventDto();
        SseEventDto c2 = frames.get(1).getEventDto();
        SseEventDto r = frames.get(2).getEventDto();
        SseEventDto tool = frames.get(3).getEventDto();
        SseEventDto done = frames.get(4).getEventDto();
        frames.forEach(f -> assertEquals(SESSION_KEY, f.getSessionId(), "帧会话路由必须按 sessionId 隔离"));

        assertEquals("content", c1.getEvent());
        assertEquals("你好，", c1.getContent());
        assertEquals("content", c2.getEvent());
        assertEquals("世界", c2.getContent());
        assertEquals("reasoning", r.getEvent());
        assertEquals("思考中", r.getReasoningContent());
        assertEquals("mcp_tool", tool.getEvent());
        assertNotNull(tool.getContent());
        assertEquals(true, tool.getContent().contains("\"toolName\":\"tool-1\"")
            && tool.getContent().contains("\"status\":\"allowed\""), "mcp_tool 帧载荷应含工具名与三态映射");
        assertEquals("done", done.getEvent(), "done 帧必须在助手落库后发出");

        // done 前保存助手消息（缓冲全量）；completeConnection 恰一次（disconnect）
        verify(chatMessageService).insertByBo(argThat(message ->
            USER_ID.equals(message.getUserId()) && SESSION_ID.equals(message.getSessionId())
                && "你好，世界".equals(message.getContent())
                && RoleType.ASSISTANT.getName().equals(message.getRole())
                && "MiniMax-M3".equals(message.getModelName())));
        verify(sseManager, times(1)).disconnect(SESSION_KEY);
    }

    @Test
    @DisplayName("助手消息落库失败时只发错误，不发 done")
    void assistantSaveFailureDoesNotSendDone() throws Exception {
        IChatMessageService chatMessageService = mock(IChatMessageService.class);
        ChatServiceFacade facade = newFacade(chatMessageService);
        when(chatMessageService.insertByBo(any())).thenReturn(false);
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);
        KernelChatSink sink = invokeHandleKernelChat(facade, newRequest("必须落库"), null, kernel);

        sink.onContent("模型回答");
        sink.onComplete();

        List<SseMessageDto> frames = capturedFrames();
        assertEquals(2, frames.size());
        assertEquals("content", frames.get(0).getEventDto().getEvent());
        assertEquals("error", frames.get(1).getEventDto().getEvent());
        verify(sseManager, times(1)).disconnect(SESSION_KEY);
    }

    @Test
    @DisplayName("错误帧镜像既有安全语义：固定安全文案、无 done、无助手落库、错误细节不外泄")
    void kernelDelegateErrorSendsSafeErrorFrameOnce() throws Exception {
        IChatMessageService chatMessageService = mock(IChatMessageService.class);
        ChatServiceFacade facade = newFacade(chatMessageService);
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);

        ChatRequest request = newRequest("会失败的问题");
        KernelChatSink sink = invokeHandleKernelChat(facade, request, null, kernel);

        sink.onError("KERNEL_ERROR", "boom 细节不得外泄");

        List<SseMessageDto> frames = capturedFrames();
        assertEquals(1, frames.size(), "错误帧后不得再发任何帧（含 done）");
        SseEventDto error = frames.get(0).getEventDto();
        assertEquals("error", error.getEvent());
        assertNotNull(error.getError());
        assertEquals(false, error.getError().contains("boom"), "错误细节不得外泄（sendError 固定安全文案）");

        verify(sseManager, times(1)).disconnect(SESSION_KEY);
        verify(chatMessageService, never()).insertByBo(any());
    }

    @Test
    @DisplayName("智能体场景：AgentVo.systemPrompt 透传内核、agentId 进四维键")
    void agentVoSystemPromptIsForwarded() throws Exception {
        ChatServiceFacade facade = newFacade(mock(IChatMessageService.class));
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);

        ChatRequest request = newRequest("执行任务");
        request.setAgentId(7L);
        AgentVo agentVo = new AgentVo();
        agentVo.setSystemPrompt("你是研发助手");

        invokeHandleKernelChat(facade, request, agentVo, kernel);

        verify(kernel).stream(
            eq("chat"), eq(String.valueOf(USER_ID)), eq("7"), eq(SESSION_KEY),
            eq("执行任务"), argThat(s -> s.startsWith("你是研发助手")),
            argThat(m -> m != null && "MiniMax-M3".equals(m.modelName())), any(), any(), any(), any());
    }

    @Test
    @DisplayName("空 userId 原样传 null 供正式内核 fail-closed，禁 'null' 字面量段")
    void nullUserIdIsPassedAsNullForRejection() throws Exception {
        ChatServiceFacade facade = newFacade(mock(IChatMessageService.class));
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);

        ChatRequest request = newRequest("匿名提问");
        request.setUserId(null);

        invokeHandleKernelChat(facade, request, null, kernel);

        verify(kernel).stream(
            eq("chat"), argThat(s -> s == null), eq("chat-model"), eq(SESSION_KEY),
            eq("匿名提问"), argThat(s -> s != null && s.contains("只有本次登记的工具")),
            argThat(m -> m != null && "MiniMax-M3".equals(m.modelName())), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SSE 连接完成或超时会取消当前内核订阅")
    void disconnectedEmitterCancelsKernelStream() throws Exception {
        IChatMessageService chatMessageService = mock(IChatMessageService.class);
        ChatServiceFacade facade = newFacade(chatMessageService);
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);
        ChatRequest request = newRequest("长回复");
        RecordingEmitter emitter = new RecordingEmitter();
        request.setEmitter(emitter);
        KernelChatSink sink = invokeHandleKernelChat(facade, request, null, kernel);
        emitter.completeFromClient();
        verify(streamDisposable).dispose();
        sink.onContent("迟到的片段");
        sink.onComplete();
        sink.onError("KERNEL_STREAM_ERROR", "迟到的错误");
        assertEquals(0, capturedFrames().size(), "断连后的回调不能继续发布帧");
        verify(chatMessageService, never()).insertByBo(any());
        verify(sseManager, never()).disconnect(SESSION_KEY);
    }

    @Test
    @DisplayName("错误后迟到的 complete 不发 done 或保存助手消息")
    void lateCompleteAfterErrorDoesNotCommitMessage() throws Exception {
        IChatMessageService chatMessageService = mock(IChatMessageService.class);
        ChatServiceFacade facade = newFacade(chatMessageService);
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);
        KernelChatSink sink = invokeHandleKernelChat(facade, newRequest("失败回合"), null, kernel);

        sink.onContent("不完整答复");
        sink.onError("KERNEL_STREAM_ERROR", "失败");
        sink.onComplete();

        assertEquals(2, capturedFrames().size(), "只允许首个 content 和 error 帧");
        verify(chatMessageService, never()).insertByBo(any());
        verify(sseManager, times(1)).disconnect(SESSION_KEY);
    }

    @Test
    @DisplayName("请求知识库无权时授权门在内核调用前拒绝")
    void deniedKnowledgeNeverReachesKernel() throws Exception {
        KnowledgeAccessGate accessGate = mock(KnowledgeAccessGate.class);
        doThrow(new SecurityException("DENIED_KNOWLEDGE"))
            .when(accessGate).checkRetrievalAccess(7L);
        ChatServiceFacade facade = newFacade(mock(IChatMessageService.class), accessGate);
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);
        ChatRequest request = newRequest("私有知识问题");
        request.setKnowledgeId("7");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> invokeHandleKernelChat(facade, request, null, kernel));
        assertEquals(SecurityException.class, thrown.getCause().getClass());
        verifyNoInteractions(kernel);
    }

    @Test
    @DisplayName("W2 模型路由收口：ChatModelVo 映射 KernelModelRequest 进内核（矩阵 #9「包装」）")
    void modelVoIsRoutedIntoKernelModelRequest() throws Exception {
        ChatServiceFacade facade = newFacade(mock(IChatMessageService.class));
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);

        ChatRequest request = newRequest("换模型");
        ChatModelVo modelVo = new ChatModelVo();
        modelVo.setModelName("glm-4");
        modelVo.setProviderCode("zhipu");
        modelVo.setApiKey("sk-vo");
        modelVo.setApiHost("https://open.bigmodel.cn/api/paas/v4");
        request.setChatModelVo(modelVo);

        invokeHandleKernelChat(facade, request, null, kernel);

        ArgumentCaptor<KernelModelRequest> modelCaptor = ArgumentCaptor.forClass(KernelModelRequest.class);
        verify(kernel).stream(eq("chat"), eq(String.valueOf(USER_ID)), eq("chat-model"), eq(SESSION_KEY),
                eq("换模型"), argThat(s -> s != null && s.contains("只有本次登记的工具")), modelCaptor.capture(), any(), any(), any(), any());
        assertEquals("glm-4", modelCaptor.getValue().modelName(), "请求 model 字段路由进内核");
        assertEquals("zhipu", modelCaptor.getValue().providerCode(), "厂商码随模型配置透传");
        assertEquals("sk-vo", modelCaptor.getValue().apiKey(), "凭据走 ModelCreationContext 落位");
        assertEquals("https://open.bigmodel.cn/api/paas/v4", modelCaptor.getValue().apiHost());
    }

    private static final class RecordingEmitter extends SseEmitter {
        private Runnable completion;

        @Override public void onCompletion(Runnable callback) {
            completion = callback;
        }

        @Override public void onTimeout(Runnable callback) { }

        @Override public void onError(Consumer<Throwable> callback) { }

        private void completeFromClient() {
            completion.run();
        }
    }

    @Test
    @DisplayName("单一原生内核必须注入，构造器仅保留实际业务依赖")
    void nativeKernelInjectionIsRequiredAndLegacyDependencyRemoved() throws Exception {
        var constructors = ChatServiceFacade.class.getDeclaredConstructors();
        assertEquals(1, constructors.length, "Spring使用唯一业务依赖构造器");
        assertEquals(11, constructors[0].getParameterCount());
        assertTrue(java.util.Arrays.stream(constructors[0].getParameterTypes())
            .noneMatch(type -> "ChatServiceFactory".equals(type.getSimpleName())));
        Field field = ChatServiceFacade.class.getDeclaredField("agentScopeChatKernel");
        var injection = field.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class);
        assertNotNull(injection);
        assertTrue(injection.required(), "缺少原生内核时禁止装配旧执行回退");
        ChatServiceFacade facade = newFacade(mock(IChatMessageService.class));
        AgentScopeChatKernel kernel = mock(AgentScopeChatKernel.class);
        injectKernel(facade, kernel);
        field.setAccessible(true);
        assertEquals(kernel, field.get(facade));
    }
}
