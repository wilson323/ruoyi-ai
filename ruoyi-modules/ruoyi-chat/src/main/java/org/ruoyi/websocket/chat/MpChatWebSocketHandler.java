package org.ruoyi.websocket.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.query.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.bo.chat.ChatModelBo;
import org.ruoyi.common.chat.domain.bo.chat.ChatMessageBo;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.enums.RoleType;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.domain.vo.agent.AgentVo;
import org.ruoyi.factory.ChatServiceFactory;
import org.ruoyi.service.agent.IAgentService;
import org.ruoyi.service.chat.ChatSessionOwnershipGuard;
import org.ruoyi.service.chat.IChatMessageService;
import org.ruoyi.service.knowledge.KnowledgeAccessGate;
import org.ruoyi.chat.kernel.AgentScopeChatKernel;
import org.ruoyi.chat.kernel.KernelChatSink;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.service.knowledge.retriever.MultiKnowledgeAugmentorFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.Disposable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

/**
 * 小程序对话 WebSocket 处理器。
 * <p>
 * 收到前端 JSON 消息后：解析模型（智能体绑定 / 前端传入 / 默认兜底）→
 * 拼装 systemPrompt 与 RAG 增强后的 content → 调用 StreamingChatModel 流式生成 →
 * 将增量 token 通过当前 WS session 回推前端。
 * <p>
 * 输出协议（与前端 index.vue 现有接收逻辑兼容）：
 * <ul>
 *   <li>增量：<code>{"content":"token片段"}</code></li>
 *   <li>结束：<code>[DONE]</code></li>
 *   <li>错误：<code>{"data":"错误:xxx"}</code></li>
 * </ul>
 *
 * @author ruoyi team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MpChatWebSocketHandler extends AbstractWebSocketHandler {

    private final ChatServiceFactory chatServiceFactory;
    private final IChatModelService chatModelService;
    private final IAgentService agentService;
    private final KnowledgeAccessGate knowledgeAccessGate;
    private final MultiKnowledgeAugmentorFactory multiKnowledgeAugmentorFactory;
    private final IChatMessageService chatMessageService;
    private final ChatSessionOwnershipGuard chatSessionOwnershipGuard;
    private final ObjectMapper objectMapper;

    private final Map<WebSocketSession, Set<Runnable>> activeKernelStreams = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cancelKernelStreams(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        cancelKernelStreams(session);
    }

    private void cancelKernelStreams(WebSocketSession session) {
        Set<Runnable> streams = activeKernelStreams.remove(session);
        if (streams != null) {
            streams.forEach(Runnable::run);
        }
    }

    private void unregisterKernelStream(WebSocketSession session, Runnable cancel) {
        activeKernelStreams.computeIfPresent(session, (ignored, streams) -> {
            streams.remove(cancel);
            return streams.isEmpty() ? null : streams;
        });
    }

    /** W1 内核委托：chat 平台域无项目维度，projectId 折叠为常量段（镜像 ChatServiceFacade，ADR-0075 矩阵 #1 登记风险）。 */
    static final String KERNEL_PROJECT_SEGMENT = "chat";

    /** W1 内核委托：普通模型对话（无智能体）在四维键中的 agent 折叠段（镜像 ChatServiceFacade）。 */
    static final String KERNEL_MODEL_AGENT_SEGMENT = "chat-model";

    /** W1 内核委托：内核错误帧对外固定脱敏文案（KernelChatSink 契约：对外展示前须由适配器脱敏）。 */
    static final String SAFE_KERNEL_ERROR_MESSAGE = "对话处理失败，请稍后重试";

    /**
     * W1 内核委托（ADR-0075 回滚点）：bean 缺席时保留 StreamingChatModel 执行路径，
     * WS 消息保存与完成帧的可靠性修复同时适用于两条执行路径。
     * 开关 = {@code chat.kernel.agentscope.enabled}（matchIfMissing=false）。
     * <p>
     * 不得进 {@code @RequiredArgsConstructor} 构造面（Lombok 只收 final 字段）——
     * 采用可选注入字段，与 {@code ChatServiceFacade} 同口径双保险回滚。
     */
    @Autowired(required = false)
    private AgentScopeChatKernel agentScopeChatKernel;

    @Value("${chat.kernel.agentscope.enabled:false}")
    private boolean agentScopeEnabled;

    @Value("${chat.default-model:}")
    private String defaultModel;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            sendError(session, "错误:消息格式不正确");
            return;
        }
        String content = asString(payload.get("content"));
        String agentIdRaw = asString(payload.get("agentId"));
        String model = asString(payload.get("model"));
        String systemPrompt = asString(payload.get("systemPrompt"));
        String knowledgeId = asString(payload.get("knowledgeId"));
        String sessionIdRaw = asString(payload.get("sessionId"));

        if (StringUtils.isBlank(content)) {
            sendError(session, "错误:对话消息不能为空");
            return;
        }

        Long userId = (Long) session.getAttributes().get(MpChatHandshakeInterceptor.USER_ID_KEY);
        Long sessionId = parseLong(sessionIdRaw);

        try {
            chatSessionOwnershipGuard.requireOwned(userId, sessionId);
        } catch (IllegalArgumentException unavailable) {
            sendError(session, "错误:对话会话不可用");
            return;
        }

        try {
            // 1. 解析智能体（若传了 agentId），取其绑定模型与 systemPrompt、知识库
            AgentVo agentVo = null;
            if (StringUtils.isNotBlank(agentIdRaw)) {
                Long agentId = parseLong(agentIdRaw);
                if (agentId != null) {
                    agentVo = agentService.queryById(agentId);
                }
            }

            // 2. 解析模型：智能体绑定 > 前端传入 > 默认配置 > 表内首个 chat 模型。
            // 显式指定的模型失效时拒绝本轮，不能静默改用其他模型。
            ChatModelVo modelVo = null;
            if (agentVo != null && agentVo.getModelId() != null) {
                modelVo = chatModelService.queryById(agentVo.getModelId());
                if (modelVo == null) {
                    sendError(session, "错误:智能体绑定的对话模型不可用");
                    return;
                }
            }
            if (modelVo == null && StringUtils.isNotBlank(model)) {
                modelVo = chatModelService.selectModelByName(model);
                if (modelVo == null) {
                    sendError(session, "错误:指定的对话模型不可用");
                    return;
                }
            }
            if (modelVo == null) {
                modelVo = resolveDefaultModel();
            }
            if (modelVo == null) {
                sendError(session, "错误:未找到可用对话模型，请联系管理员配置");
                return;
            }

            // 3. 拼装最终输入：RAG 增强 + systemPrompt 前置
            String finalSystemPrompt = (agentVo != null && StringUtils.isNotBlank(agentVo.getSystemPrompt()))
                ? agentVo.getSystemPrompt() : systemPrompt;
            String augmentedContent = augmentWithKnowledge(content, agentVo, knowledgeId, userId);
            String finalContent = StringUtils.isNotBlank(finalSystemPrompt)
                ? finalSystemPrompt + "\n\n" + augmentedContent : augmentedContent;

            // 4. WS 完成语义要求本轮用户消息可确认落库，失败时不得启动模型。
            saveRequiredMessage(userId, sessionId, content, RoleType.USER.getName(), modelVo.getModelName());

            // 5. W1 内核委托（ADR-0075 回滚点）：开关开 → AgentScope 内核对话；
            // 开关关 / bean 缺席 → StreamingChatModel 流式路径。
            if (agentScopeEnabled) {
                if (agentScopeChatKernel == null) {
                    throw new IllegalStateException("AgentScope 聊天内核已启用但未装配");
                }
                log.info("mp-chat 处理内核对话,会话:{},agentId:{}", sessionId, agentIdRaw);
                handleKernelChat(session, augmentedContent, finalSystemPrompt, agentVo,
                    userId, sessionId, modelVo);
                return;
            }

            // 6. 构造流式模型并异步生成
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setContent(content);
            chatRequest.setModel(modelVo.getModelName());
            chatRequest.setKnowledgeId(knowledgeId);
            StreamingChatModel streamingModel = chatServiceFactory
                .getOriginalService(modelVo.getProviderCode())
                .buildStreamingChatModel(modelVo, chatRequest);

            final String modelName = modelVo.getModelName();
            CompletableFuture.runAsync(() -> {
                StringBuilder buffer = new StringBuilder();
                // 任一终态仅允许发送一次完成或错误帧。
                AtomicBoolean terminal = new AtomicBoolean(false);
                StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (terminal.get()) {
                            return;
                        }
                        buffer.append(partialResponse);
                        sendJson(session, Map.of("content", partialResponse));
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        if (!terminal.compareAndSet(false, true)) {
                            return;
                        }
                        try {
                            if (buffer.length() > 0) {
                                saveRequiredMessage(userId, sessionId, buffer.toString(),
                                    RoleType.ASSISTANT.getName(), modelName);
                            }
                            sendRaw(session, "[DONE]");
                        } catch (Exception e) {
                            log.error("mp-chat operation=ASSISTANT_MESSAGE_SAVE status=FAILED exceptionType={}",
                                e.getClass().getSimpleName());
                            sendError(session, "错误:" + SAFE_KERNEL_ERROR_MESSAGE);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (terminal.compareAndSet(false, true)) {
                            sendError(session, "错误:" + SAFE_KERNEL_ERROR_MESSAGE);
                        }
                        log.warn("mp-chat operation=LEGACY_STREAM status=FAILED exceptionType={}",
                            error.getClass().getSimpleName());
                    }
                };
                try {
                    streamingModel.chat(finalContent, handler);
                } catch (Exception e) {
                    log.error("mp-chat operation=LEGACY_STREAM_START status=FAILED exceptionType={}",
                        e.getClass().getSimpleName());
                    if (terminal.compareAndSet(false, true)) {
                        sendError(session, "错误:" + SAFE_KERNEL_ERROR_MESSAGE);
                    }
                }
            });
        } catch (Exception e) {
            log.error("mp-chat operation=WS_REQUEST status=FAILED exceptionType={}", e.getClass().getSimpleName());
            sendError(session, "错误:" + SAFE_KERNEL_ERROR_MESSAGE);
        }
    }

    private void handleKernelChat(WebSocketSession session, String augmentedContent, String systemPrompt,
                                  AgentVo agentVo, Long userId, Long sessionId, ChatModelVo modelVo) {
        StringBuilder buffer = new StringBuilder();
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicBoolean disconnected = new AtomicBoolean(false);
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
        Set<Runnable> streams = activeKernelStreams.computeIfAbsent(session, ignored -> ConcurrentHashMap.newKeySet());
        streams.add(cancel);
        KernelChatSink sink = new KernelChatSink() {

            @Override
            public void onContent(String delta) {
                synchronized (lifecycleLock) {
                    if (terminal.get() || disconnected.get()) {
                        return;
                    }
                    buffer.append(delta);
                    sendJson(session, Map.of("content", delta));
                }
            }

            @Override
            public void onReasoning(String delta) {
                // WS 契约无推理帧（增量只有 {"content"}），丢弃（KernelChatSink 契约允许）
            }

            @Override
            public void onMcpTool(String toolName, String status, String result) {
                // WS 契约无 mcp_tool 帧；W1 对话内核不挂工具，丢弃
            }

            @Override
            public void onError(String code, String message) {
                synchronized (lifecycleLock) {
                    unregisterKernelStream(session, cancel);
                    if (terminal.compareAndSet(false, true) && !disconnected.get()) {
                        sendError(session, "错误:" + SAFE_KERNEL_ERROR_MESSAGE);
                    }
                    log.error("mp-chat kernel operation=KERNEL_STREAM status=FAILED code={}", code);
                }
            }

            @Override
            public void onComplete() {
                synchronized (lifecycleLock) {
                    unregisterKernelStream(session, cancel);
                    if (!terminal.compareAndSet(false, true) || disconnected.get()) {
                        return;
                    }
                    try {
                        if (buffer.length() > 0) {
                            saveRequiredMessage(userId, sessionId, buffer.toString(),
                                RoleType.ASSISTANT.getName(), modelVo.getModelName());
                        }
                        sendRaw(session, "[DONE]");
                    } catch (Exception e) {
                        log.error("mp-chat operation=KERNEL_ASSISTANT_MESSAGE_SAVE status=FAILED exceptionType={}",
                            e.getClass().getSimpleName());
                        sendError(session, "错误:" + SAFE_KERNEL_ERROR_MESSAGE);
                    }
                }
            }
        };

        Disposable active = agentScopeChatKernel.stream(
            KERNEL_PROJECT_SEGMENT,
            userId == null ? null : String.valueOf(userId),
            agentVo == null || agentVo.getId() == null ? KERNEL_MODEL_AGENT_SEGMENT : String.valueOf(agentVo.getId()),
            sessionId == null ? null : String.valueOf(sessionId),
            augmentedContent,
            systemPrompt,
            KernelModelRequest.from(modelVo),
            sink
        );
        subscription.set(active);
        if (disconnected.get() || !session.isOpen()) {
            cancel.run();
            unregisterKernelStream(session, cancel);
        }
    }

    /** 与旧 saveChatMessage 的五个业务字段一致，但写入结果必须可观察。 */
    private void saveRequiredMessage(Long userId, Long sessionId, String content, String role, String modelName) {
        if (userId == null || sessionId == null) {
            throw new IllegalStateException("missing owned session");
        }
        ChatMessageBo message = new ChatMessageBo();
        message.setUserId(userId);
        message.setSessionId(sessionId);
        message.setContent(content);
        message.setRole(role);
        message.setModelName(modelName);
        if (!Boolean.TRUE.equals(chatMessageService.insertByBo(message))) {
            throw new IllegalStateException("chat message persistence failed");
        }
    }

    /**
     * 智能体绑定知识库 / 前端传入 knowledgeId 时，对 content 做向量检索增强。
     * 经 MultiKnowledgeAugmentorFactory 收敛组装（B1 C2：与 ChatServiceFacade 同一实现，
     * 并发检索 + kid|docId|fid 去重 + 20条/24000字符限界）。
     * <p>
     * userId 取自握手期 MpChatHandshakeInterceptor 验 token 后写入的 session attributes：
     * ws 消息线程无 Sa-Token ThreadLocal，须以显式身份过 Gate（双参重载），不得走单参会话变体。
     */
    private String augmentWithKnowledge(String content, AgentVo agentVo, String knowledgeId, Long userId) {
        List<Long> kids = new ArrayList<>();
        if (agentVo != null && agentVo.getKnowledgeIds() != null) {
            kids.addAll(agentVo.getKnowledgeIds());
        }
        if (StringUtils.isBlank(knowledgeId) && kids.isEmpty()) {
            return content;
        }
        if (StringUtils.isNotBlank(knowledgeId)) {
            try {
                kids.add(Long.valueOf(knowledgeId));
            } catch (NumberFormatException ignored) {
            }
        }
        if (kids.isEmpty()) {
            return content;
        }
        // S1：kids 进入检索上下文前逐个过检索访问门，不可见即抛业务异常
        // （放在回退 try 之前，异常向上传播由 handleTextMessage 统一向前端报错，而非静默回退原文）
        // B0 修复：ws 消息线程无 Sa-Token ThreadLocal，单参变体取会话恒 null 会被全拒，改传显式身份
        kids.forEach(kid -> knowledgeAccessGate.checkRetrievalAccess(kid, userId));
        try {
            RetrievalAugmentor augmentor = multiKnowledgeAugmentorFactory.buildMultiKnowledgeAugmentor(kids);
            if (augmentor == null) {
                return content;
            }
            UserMessage userMessage = UserMessage.userMessage(content);
            Metadata metadata = Metadata.from(userMessage, null, new ArrayList<>());
            AugmentationResult result = augmentor.augment(new AugmentationRequest(userMessage, metadata));
            ChatMessage augmented = result.chatMessage();
            return augmented instanceof UserMessage ? ((UserMessage) augmented).singleText() : content;
        } catch (Exception e) {
            log.warn("mp-chat RAG 增强失败，回退原文: {}", e.getMessage());
            return content;
        }
    }

    /**
     * 默认模型兜底：优先用 chat.default-model 配置，其次取表内首个 chat 类模型。
     */
    private ChatModelVo resolveDefaultModel() {
        if (StringUtils.isNotBlank(defaultModel)) {
            ChatModelVo vo = chatModelService.selectModelByName(defaultModel);
            if (vo != null) {
                return vo;
            }
        }
        try {
            List<ChatModelVo> list = chatModelService.queryList(new ChatModelBo());
            if (list != null) {
                for (ChatModelVo vo : list) {
                    if ("chat".equalsIgnoreCase(vo.getCategory()) && "Y".equalsIgnoreCase(vo.getModelShow())) {
                        return vo;
                    }
                }
                if (!list.isEmpty()) {
                    return list.get(0);
                }
            }
        } catch (Exception e) {
            log.warn("mp-chat 解析默认模型失败: {}", e.getMessage());
        }
        return null;
    }

    // ---------- WS 输出辅助 ----------

    private void sendJson(WebSocketSession session, Map<String, ?> data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.warn("mp-chat 发送 WS 消息失败: {}", e.getMessage());
        }
    }

    private void sendRaw(WebSocketSession session, String raw) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(raw));
        } catch (Exception e) {
            log.warn("mp-chat 发送 WS 消息失败: {}", e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String msg) {
        Map<String, Object> err = new HashMap<>();
        err.put("data", msg);
        sendJson(session, err);
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long parseLong(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
