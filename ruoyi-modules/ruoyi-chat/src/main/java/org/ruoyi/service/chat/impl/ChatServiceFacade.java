package org.ruoyi.service.chat.impl;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.bo.chat.ChatMessageBo;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.enums.RoleType;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.chat.service.chat.IChatService;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.ruoyi.common.sse.dto.SseEventDto;
import org.ruoyi.common.sse.utils.SseMessageUtils;
import org.ruoyi.common.trace.config.TraceProperties;
import org.ruoyi.common.trace.constant.TraceConstants;
import org.ruoyi.common.trace.core.DefaultTraceStreamSpan;
import org.ruoyi.common.trace.core.TraceContext;
import org.ruoyi.common.trace.core.TraceScope;
import org.ruoyi.common.trace.core.TraceStreamSpan;
import org.ruoyi.common.trace.domain.TraceNode;
import org.ruoyi.common.trace.domain.TraceRun;
import org.ruoyi.common.trace.service.TraceRecordService;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.mcp.service.core.AgentScopeMcpToolProviderService;
import org.ruoyi.common.chat.service.chat.ChatResponseHandler;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.message.TextBlock;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.ChatSessionOwnershipGuard;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.chat.kernel.AgentScopeChatKernel;
import org.ruoyi.chat.kernel.KernelChatSink;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.service.knowledge.retriever.MultiKnowledgeAugmentorFactory;
import org.ruoyi.argtrace.RagTraceNodeTypes;
import org.ruoyi.argtrace.RagTracePayloadBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天服务门面层
 * <p>
 * 作为统一入口，负责：
 * 1. 构建对话上下文
 * 2. 路由到对应的处理器
 *
 * @author ageerle@163.com
 * @date 2025/12/13
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceFacade implements IChatService {


    static final String SAFE_CHAT_ERROR_MESSAGE = "对话处理失败，请稍后重试";
    static final String KERNEL_PROJECT_SEGMENT = "chat";
    static final String KERNEL_MODEL_AGENT_SEGMENT = "chat-model";

    private final IChatModelService chatModelService;

    private final KnowledgeAccessGate knowledgeAccessGate;

    private final MultiKnowledgeAugmentorFactory multiKnowledgeAugmentorFactory;

    private final SseEmitterManager sseEmitterManager;

    private final IChatMessageService chatMessageService;

    private final ChatSessionOwnershipGuard chatSessionOwnershipGuard;

    private final IAgentService agentService;

    private final AgentScopeMcpToolProviderService agentScopeMcpToolProviderService;

    private final TraceRecordService traceRecordService;

    private final TraceProperties traceProperties;

    @Autowired
    private AgentScopeChatKernel agentScopeChatKernel;

    /**
     * 统一聊天入口 - SSE流式响应
     *
     * @param chatRequest 聊天请求
     * @return SseEmitter
     */
    public SseEmitter sseChat(ChatRequest chatRequest) {
        if (Boolean.TRUE.equals(chatRequest.getEnableWorkFlow())) {
            if (chatRequest.getAgentId() != null) {
                throw new IllegalArgumentException("对话模式参数冲突：工作流和智能体不能同时启用");
            }
            throw new IllegalArgumentException("旧工作流对话入口已退役，不能创建运行");
        }
        Long userId = LoginHelper.getUserId();
        String tokenValue = StpUtil.getTokenValue();
        chatSessionOwnershipGuard.requireOwned(userId, chatRequest.getSessionId());

        boolean agentMode = chatRequest.getAgentId() != null;

        // 智能体解析：传入 agentId 时按智能体绑定的模型覆盖 model 字段
        AgentVo agentVo = null;
        if (agentMode) {
            agentVo = agentService.queryById(chatRequest.getAgentId());
            if (agentVo == null) {
                throw new IllegalArgumentException("智能体不存在");
            }
            if (!"0".equals(agentVo.getStatus())) {
                throw new IllegalArgumentException("智能体已停用");
            }
            if (agentVo != null && agentVo.getModelId() != null) {
                ChatModelVo agentModel = chatModelService.queryById(agentVo.getModelId());
                if (agentModel == null) {
                    throw new IllegalArgumentException("智能体绑定的模型不存在");
                }
                chatRequest.setModel(agentModel.getModelName());
            }
        }

        if (StringUtils.isBlank(chatRequest.getModel())) {
            throw new IllegalArgumentException(
                agentVo == null ? "对话模式必须指定模型" : "智能体未绑定模型，且请求未提供回退模型"
            );
        }

        // 根据模型名称查询完整配置
        ChatModelVo chatModelVo = chatModelService.selectModelByName(chatRequest.getModel());
        if (chatModelVo == null) {
            throw new IllegalArgumentException("模型不存在");
        }

        if (agentScopeChatKernel == null) {
            throw new IllegalStateException("AgentScope 聊天内核已启用但未装配");
        }

        // 对话和智能体模式共用按会话隔离的 SSE。
        SseEmitter emitter = sseEmitterManager.connect(String.valueOf(chatRequest.getSessionId()));

        chatRequest.setEmitter(emitter);
        chatRequest.setUserId(userId);
        chatRequest.setTokenValue(tokenValue);
        chatRequest.setChatModelVo(chatModelVo);

        // 保存用户消息
        Long currentMessageId = saveRequiredUserMessage(chatRequest);

        TraceRunHandle traceRun = startRagTraceRun(chatRequest, userId);
        return handleKernelChat(chatRequest, agentVo, traceRun, null, currentMessageId);
    }

    /** 记录既有聊天链路追踪。 */
    private TraceRunHandle startRagTraceRun(ChatRequest chatRequest, Long userId) {
        if (!traceProperties.isEnabled()) {
            return null;
        }

        String traceId = UUID.randomUUID().toString().replace("-", "");
        long startMillis = System.currentTimeMillis();
        TraceRun run = new TraceRun();
        run.setTraceId(traceId);
        run.setTraceName(RagTraceNodeTypes.TRACE_NAME_RAG_CHAT);
        run.setBusinessType(RagTraceNodeTypes.BUSINESS_TYPE_RAG_CHAT);
        run.setBusinessId(chatRequest.getSessionId() == null ? null : chatRequest.getSessionId().toString());
        run.setUserId(userId);
        run.setTenantId(safeGetTenantId());
        run.setStatus(TraceConstants.STATUS_RUNNING);
        run.setStartTime(new Date(startMillis));
        run.setMetadata(RagTracePayloadBuilder.chatRequestSummary(chatRequest));

        try {
            traceRecordService.startRun(run);
        } catch (Exception e) {
            log.warn("trace_persistence operation=START_RUN status=FAILED traceId={} errorType={}",
                traceId, errorType(e));
        }
        return new TraceRunHandle(traceId, startMillis, run.getBusinessId(), run.getTenantId());
    }

    private TraceScope openTraceScope(TraceRunHandle traceRun, Long userId) {
        if (traceRun == null) {
            return null;
        }
        return TraceContext.begin(traceRun.traceId, RagTraceNodeTypes.BUSINESS_TYPE_RAG_CHAT,
            traceRun.businessId, userId, traceRun.tenantId);
    }

    private TraceStreamSpan startLlmCallSpan(TraceRunHandle traceRun, ChatRequest chatRequest,
                                             String methodName) {
        if (traceRun == null || StringUtils.isBlank(TraceContext.getTraceId())) {
            return null;
        }

        String nodeId = UUID.randomUUID().toString().replace("-", "");
        long startMillis = System.currentTimeMillis();
        TraceNode node = new TraceNode();
        node.setTraceId(traceRun.traceId);
        node.setNodeId(nodeId);
        node.setParentNodeId(TraceContext.currentNodeId());
        node.setDepth(TraceContext.depth());
        node.setNodeName("llm-call");
        node.setNodeType(RagTraceNodeTypes.NODE_LLM_CALL);
        node.setClassName(ChatServiceFacade.class.getName());
        node.setMethodName(methodName);
        node.setStatus(TraceConstants.STATUS_RUNNING);
        node.setStartTime(new Date(startMillis));
        node.setInputPayload(RagTracePayloadBuilder.streamInputSummary(chatRequest));

        try {
            traceRecordService.startNode(node);
            TraceContext.pushNode(nodeId);
            return new DefaultTraceStreamSpan(traceRecordService, traceProperties, traceRun.traceId, nodeId, startMillis);
        } catch (Exception e) {
            log.warn("trace_persistence operation=START_NODE status=FAILED traceId={} nodeId={} errorType={}",
                traceRun.traceId, nodeId, errorType(e));
            return null;
        }
    }

    private void finishTraceRun(TraceRunHandle traceRun, String status, Throwable error) {
        if (traceRun == null || !traceRun.finished.compareAndSet(false, true)) {
            return;
        }
        try {
            traceRecordService.finishRun(traceRun.traceId, status, traceErrorSummary(error),
                new Date(), System.currentTimeMillis() - traceRun.startMillis);
        } catch (Exception e) {
            log.warn("trace_persistence operation=FINISH_RUN status=FAILED traceId={} errorType={}",
                traceRun.traceId, errorType(e));
        }
    }

    private String safeGetTenantId() {
        try {
            return LoginHelper.getTenantId();
        } catch (Exception e) {
            log.warn("trace_context operation=RESOLVE_TENANT status=FAILED errorType={}", errorType(e));
            return null;
        }
    }

    private static final class TraceRunHandle {

        private final String traceId;
        private final long startMillis;
        private final String businessId;
        private final String tenantId;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private TraceRunHandle(String traceId, long startMillis, String businessId, String tenantId) {
            this.traceId = traceId;
            this.startMillis = startMillis;
            this.businessId = businessId;
            this.tenantId = tenantId;
        }
    }

    /**
     * 智能体对话下的输入增强：智能体绑定知识库时，对原始 content 做多知识库 RAG 增强。
     * 无知识库时原样返回 content。
     */
    private String augmentAgentInput(ChatRequest chatRequest, AgentVo agentVo) {
        String content = chatRequest.getContent();
        List<Long> knowledgeIds = collectKnowledgeIds(chatRequest, agentVo);
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return content;
        }
        return multiKnowledgeAugmentorFactory.augment(knowledgeIds, content, chatRequest.getSessionId());
    }

    /**
     * 支持外部 handler 的对话接口（跨模块调用）
     * 同时发送到 SSE 和外部 handler
     *
     * @param chatRequest     聊天请求
     * @param externalHandler 外部响应处理器（可为 null）
     */
    @Override
    public void chat(ChatRequest chatRequest, ChatResponseHandler externalHandler) {
        if (externalHandler == null) {
            sseChat(chatRequest);
            return;
        }
        Long userId = LoginHelper.getUserId();
        chatSessionOwnershipGuard.requireOwned(userId, chatRequest.getSessionId());
        ChatModelVo selected = chatModelService.selectModelByName(chatRequest.getModel());
        if (selected == null) { throw new IllegalArgumentException("模型不存在"); }
        chatRequest.setUserId(userId);
        chatRequest.setChatModelVo(selected);
        Long currentMessageId = saveRequiredUserMessage(chatRequest);
        handleKernelChat(chatRequest, null, startRagTraceRun(chatRequest, userId), externalHandler, currentMessageId);
    }

    /**
     * 实现接口默认方法 - 不带 handler 的调用
     */
    @Override
    public SseEmitter chat(ChatRequest chatRequest) {
        return sseChat(chatRequest);
    }


    /** 所有选定知识库逐个经过业务访问门后才检索。 */
    private List<Long> collectKnowledgeIds(ChatRequest chatRequest, AgentVo agentVo) {
        if (agentVo != null && agentVo.getKnowledgeIds() != null && !agentVo.getKnowledgeIds().isEmpty()) {
            agentVo.getKnowledgeIds().forEach(knowledgeAccessGate::checkRetrievalAccess);
            return agentVo.getKnowledgeIds();
        }
        if (StringUtils.isNotBlank(chatRequest.getKnowledgeId())) {
            try {
                Long kid = Long.valueOf(chatRequest.getKnowledgeId());
                knowledgeAccessGate.checkRetrievalAccess(kid);
                return List.of(kid);
            } catch (NumberFormatException ignored) {
            }
        }
        return List.of();
    }

    /**
     * 普通对话响应处理器：推送流式内容、保存助手消息并结束链路追踪。
     */
    private SseEmitter handleKernelChat(ChatRequest chatRequest, AgentVo agentVo, TraceRunHandle traceRun) {
        return handleKernelChat(chatRequest, agentVo, traceRun, null, null);
    }

    private SseEmitter handleKernelChat(ChatRequest chatRequest, AgentVo agentVo, TraceRunHandle traceRun,
                                        ChatResponseHandler externalHandler, Long currentMessageId) {
        String sessionId = String.valueOf(chatRequest.getSessionId());
        // 沿用旧链知识库选择与访问门；只有检索异常可回退，授权拒绝在内核前 fail-closed。
        String augmentedInput = augmentAgentInput(chatRequest, agentVo);
        StringBuilder messageBuffer = new StringBuilder();
        AtomicReference<io.agentscope.core.message.Msg> finalResult = new AtomicReference<>();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean disconnected = new AtomicBoolean(false);
        AtomicBoolean terminal = new AtomicBoolean(false);
        Object lifecycleLock = new Object();
        Runnable cancel = () -> {
            Disposable active;
            synchronized (lifecycleLock) {
                disconnected.set(true);
                terminal.set(true);
                active = subscription.get();
            }
            if (active != null) {
                active.dispose();
            }
        };
        if (externalHandler == null) {
            chatRequest.getEmitter().onCompletion(cancel);
            chatRequest.getEmitter().onTimeout(cancel);
            chatRequest.getEmitter().onError(ignored -> cancel.run());
        }
        KernelChatSink sink = new KernelChatSink() {

            @Override
            public void onContent(String delta) {
                synchronized (lifecycleLock) {
                    if (terminal.get()) {
                        return;
                    }
                    messageBuffer.append(delta);
                    if (externalHandler == null) { SseMessageUtils.sendContent(sessionId, delta); }
                    else { externalHandler.onPartialResponse(delta); }
                }
            }

            @Override
            public void onReasoning(String delta) {
                synchronized (lifecycleLock) {
                    if (terminal.get()) {
                        return;
                    }
                    if (externalHandler == null) { SseMessageUtils.sendReasoning(sessionId, delta); }
                    else { externalHandler.onPartialThinking(delta); }
                }
            }

            @Override
            public void onMcpTool(String toolName, String status, String result) {
                synchronized (lifecycleLock) {
                    if (terminal.get()) {
                        return;
                    }
                    if (externalHandler == null) {
                        SseMessageUtils.sendEvent(sessionId, SseEventDto.mcpTool(toolName, status, result));
                    }
                }
            }

            @Override
            public void onError(String code, String message) {
                synchronized (lifecycleLock) {
                    if (!terminal.compareAndSet(false, true)) {
                        return;
                    }
                    finishTraceRun(traceRun, TraceConstants.STATUS_ERROR, new IllegalStateException(code + ": " + message));
                    if (externalHandler == null) {
                        SseMessageUtils.sendError(sessionId, SAFE_CHAT_ERROR_MESSAGE);
                        SseMessageUtils.completeConnection(sessionId);
                    } else { externalHandler.onError(new IllegalStateException(code)); }
                    log.error("chat_stream operation=KERNEL_STREAM status=FAILED code={}", code);
                }
            }

            @Override
            public void onResult(io.agentscope.core.message.Msg result) {
                synchronized (lifecycleLock) {
                    if (!terminal.get()) { finalResult.set(result); }
                }
            }

            @Override
            public void onComplete() {
                synchronized (lifecycleLock) {
                    if (!terminal.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        String fullMessage = org.ruoyi.chat.kernel.KernelFinalResponse.text(finalResult.get(), messageBuffer.toString());
                        if (StringUtils.isNotBlank(fullMessage)) {
                            ChatMessageBo message = new ChatMessageBo();
                            message.setUserId(chatRequest.getUserId());
                            message.setSessionId(chatRequest.getSessionId());
                            message.setContent(fullMessage);
                            message.setRole(RoleType.ASSISTANT.getName());
                            message.setModelName(chatRequest.getModel());
                            if (!Boolean.TRUE.equals(chatMessageService.insertByBo(message))) {
                                throw new IllegalStateException("assistant message persistence failed");
                            }
                        } else {
                            log.warn("chat_stream status=EMPTY_RESPONSE");
                        }
                        finishTraceRun(traceRun, TraceConstants.STATUS_SUCCESS, null);
                        if (externalHandler == null) {
                            if (!java.util.Objects.equals(fullMessage, messageBuffer.toString())) {
                                SseMessageUtils.sendEvent(sessionId, org.ruoyi.common.sse.dto.SseEventDto.replacement(fullMessage));
                            }
                            SseMessageUtils.sendDone(sessionId);
                        }
                        else { externalHandler.onCompleteResponse(org.ruoyi.chat.kernel.KernelFinalResponse.response(
                            finalResult.get(), fullMessage)); }
                    } catch (Exception e) {
                        finishTraceRun(traceRun, TraceConstants.STATUS_ERROR, e);
                        if (externalHandler == null) { SseMessageUtils.sendError(sessionId, SAFE_CHAT_ERROR_MESSAGE); }
                        else { externalHandler.onError(e); }
                        log.error("chat_stream operation=KERNEL_COMPLETE status=FAILED errorType={}", errorType(e));
                    } finally {
                        if (externalHandler == null) { SseMessageUtils.completeConnection(sessionId); }
                    }
                }
            }
        };

        AgentScopeMcpToolProviderService.ToolSession tools;
        try {
            tools = agentScopeMcpToolProviderService.createSession(
                agentVo == null ? List.of() : agentVo.getMcpToolIds());
        } catch (RuntimeException failure) {
            sink.onError("TOOL_UNAVAILABLE", SAFE_CHAT_ERROR_MESSAGE);
            return chatRequest.getEmitter();
        }
        String nativePrompt;
        try {
            nativePrompt = org.ruoyi.agent.AgentChatPrompt.build(agentVo == null ? null : agentVo.getSystemPrompt(),
                agentVo == null ? List.of() : agentVo.getSkillNames(), tools.toolkit().getToolNames());
        } catch (RuntimeException failure) {
            tools.close();
            sink.onError("SKILL_UNAVAILABLE", SAFE_CHAT_ERROR_MESSAGE);
            return chatRequest.getEmitter();
        }
        Disposable active = agentScopeChatKernel.stream(
            KERNEL_PROJECT_SEGMENT,
            // 不得经 String.valueOf 把空身份变成 "null"；内核正式桥会 fail-closed 拒绝。
            chatRequest.getUserId() == null ? null : String.valueOf(chatRequest.getUserId()),
            chatRequest.getAgentId() == null ? KERNEL_MODEL_AGENT_SEGMENT : String.valueOf(chatRequest.getAgentId()),
            sessionId,
            augmentedInput,
            nativePrompt,
            KernelModelRequest.from(chatRequest.getChatModelVo(), chatRequest.getModel()),
            tools.toolkit(), tools,
            () -> initialHistory(chatRequest.getSessionId(), currentMessageId), sink
        );
        subscription.set(active);
        if (disconnected.get()) {
            active.dispose();
        }
        return chatRequest.getEmitter();
    }

    private Long saveRequiredUserMessage(ChatRequest request) {
        ChatMessageBo row = new ChatMessageBo();
        row.setUserId(request.getUserId()); row.setSessionId(request.getSessionId());
        row.setContent(request.getContent()); row.setRole(RoleType.USER.getName()); row.setModelName(request.getModel());
        if (!Boolean.TRUE.equals(chatMessageService.insertByBo(row)) || row.getId() == null) {
            throw new IllegalStateException("user message persistence failed");
        }
        return row.getId();
    }

    /** 仅缺失原生状态时读取；真实消息ID截断，避免导入本轮或尚未执行的后续用户行。 */
    private List<io.agentscope.core.message.Msg> initialHistory(Long sessionId, Long beforeMessageId) {
        if (beforeMessageId == null) { return List.of(); }
        List<io.agentscope.core.message.Msg> rows = chatMessageService.getMessagesBySessionId(sessionId);
        return rows == null ? List.of() : rows.stream().filter(row -> {
            Object id = row.getMetadata() == null ? null : row.getMetadata().get("chatMessageId");
            return id instanceof Number number && number.longValue() < beforeMessageId;
        }).toList();
    }

    /**
     * 创建组合响应处理器 - 同时发送到 SSE 和外部 handler
     *
     * @param sessionId       会话ID（SSE 按会话隔离推送）
     * @param externalHandler 外部响应处理器（可为 null）
     * @return 组合的流式响应处理器
     */
    protected ChatResponseHandler createCombinedHandler(String sessionId,
                                                                  ChatResponseHandler externalHandler) {
        return new ChatResponseHandler() {

            private final StringBuilder messageBuffer = new StringBuilder();

            @SneakyThrows
            @Override
            public void onPartialResponse(String partialResponse) {
                // 1. 追加到缓冲区
                messageBuffer.append(partialResponse);

                // 2. 发送内容事件到 SSE（前端可通过 SSE 监听）
                // 工作流调用时连接归工作流引擎所有, token 由引擎以 [NODE_CHUNK_] 事件推送, 不走聊天协议
                if (externalHandler == null) {
                    SseMessageUtils.sendContent(sessionId, partialResponse);
                }

                // 3. 转发给外部 handler（Workflow 等模块可处理）
                if (externalHandler != null) {
                    externalHandler.onPartialResponse(partialResponse);
                }
            }

            @Override
            public void onPartialThinking(String partialThinking) {
                // 发送推理内容到 SSE（前端通过 reasoning 事件监听）, 工作流调用时不发送
                if (externalHandler == null) {
                    SseMessageUtils.sendReasoning(sessionId, partialThinking);
                }

                // 转发给外部 handler
                if (externalHandler != null) {
                    externalHandler.onPartialThinking(partialThinking);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                try {
                    // 1&2. 发送完成事件并关闭 SSE 连接
                    // 工作流调用时流程可能还有后续节点, 连接关闭由工作流引擎统一负责, 此处不能关闭
                    if (externalHandler == null) {
                        SseMessageUtils.sendDone(sessionId);
                        SseMessageUtils.completeConnection(sessionId);
                    }

                    // 3. 转发给外部 handler
                    if (externalHandler != null) {
                        externalHandler.onCompleteResponse(completeResponse);
                    }
                } catch (Exception e) {
                    log.error("chat_stream operation=COMPLETE status=FAILED errorType={}", errorType(e));
                }
            }

            @Override
            public void onError(Throwable error) {
                // 发送错误事件（工作流调用时由工作流引擎统一上报）
                if (externalHandler == null) {
                    SseMessageUtils.sendError(sessionId, SAFE_CHAT_ERROR_MESSAGE);
                }
                log.error("chat_stream operation=COMBINED_STREAM status=FAILED errorType={}", errorType(error));

                // 转发给外部 handler
                if (externalHandler != null) {
                    // This is a trusted, in-process callback contract. Preserve the original
                    // throwable identity for workflow recovery; only durable/log/SSE boundaries
                    // redact untrusted exception messages.
                    externalHandler.onError(error);
                }
            }
        };
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getName();
    }

    static String traceErrorSummary(Throwable error) {
        return error == null ? null : "CHAT_OPERATION_FAILED errorType=" + errorType(error);
    }
}
