package org.ruoyi.websocket.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.domain.bo.chat.ChatModelBo;
import org.ruoyi.common.chat.domain.bo.chat.ChatMessageBo;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.enums.RoleType;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.domain.vo.agent.AgentVo;
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

/**
 * 小程序对话 WebSocket 处理器。
 * <p>
 * 收到前端 JSON 消息后：解析模型（智能体绑定 / 前端传入 / 默认兜底）→
 * 拼装 systemPrompt 与知识检索增强后的 content → AgentScope 原生运行 →
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

    private final org.ruoyi.mcp.service.core.AgentScopeMcpToolProviderService toolProvider;
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

    @Autowired
    private AgentScopeChatKernel agentScopeChatKernel;

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
                if (agentVo == null || !"0".equals(agentVo.getStatus())) {
                    throw new IllegalArgumentException("智能体不存在或已停用");
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
            // 4. WS 完成语义要求本轮用户消息可确认落库，失败时不得启动模型。
            Long currentMessageId = saveRequiredMessage(userId, sessionId, content, RoleType.USER.getName(), modelVo.getModelName());

            if (agentScopeChatKernel == null) {
                throw new IllegalStateException("AgentScope聊天内核未装配");
            }
            handleKernelChat(session, augmentedContent, finalSystemPrompt, agentVo,
                userId, sessionId, modelVo, currentMessageId);
        } catch (Exception e) {
            log.error("mp-chat operation=WS_REQUEST status=FAILED exceptionType={}", e.getClass().getSimpleName());
            sendError(session, "错误:" + SAFE_KERNEL_ERROR_MESSAGE);
        }
    }

    private void handleKernelChat(WebSocketSession session, String augmentedContent, String systemPrompt,
                                  AgentVo agentVo, Long userId, Long sessionId, ChatModelVo modelVo, Long currentMessageId) {
        StringBuilder buffer = new StringBuilder();
        AtomicReference<io.agentscope.core.message.Msg> finalResult = new AtomicReference<>();
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
                // 保持原 WS 帧契约，工具结果由原生内核继续处理。
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
            public void onResult(io.agentscope.core.message.Msg result) {
                synchronized (lifecycleLock) {
                    if (!terminal.get() && !disconnected.get()) { finalResult.set(result); }
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
                        String finalText = org.ruoyi.chat.kernel.KernelFinalResponse.text(finalResult.get(), buffer.toString());
                        if (finalText != null && !finalText.isEmpty()) {
                            saveRequiredMessage(userId, sessionId, finalText,
                                RoleType.ASSISTANT.getName(), modelVo.getModelName());
                        }
                        if (!java.util.Objects.equals(finalText, buffer.toString())) {
                            sendJson(session, Map.of("content", finalText, "replace", true));
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

        org.ruoyi.mcp.service.core.AgentScopeMcpToolProviderService.ToolSession tools;
        try {
            tools = toolProvider.createSession(agentVo == null ? List.of() : agentVo.getMcpToolIds());
        } catch (RuntimeException failure) {
            sink.onError("TOOL_UNAVAILABLE", SAFE_KERNEL_ERROR_MESSAGE);
            return;
        }
        String nativePrompt;
        try {
            nativePrompt = org.ruoyi.agent.AgentChatPrompt.build(systemPrompt,
                agentVo == null ? List.of() : agentVo.getSkillNames(), tools.toolkit().getToolNames());
        } catch (RuntimeException failure) {
            tools.close();
            sink.onError("SKILL_UNAVAILABLE", SAFE_KERNEL_ERROR_MESSAGE);
            return;
        }
        Disposable active = agentScopeChatKernel.stream(
            KERNEL_PROJECT_SEGMENT,
            userId == null ? null : String.valueOf(userId),
            agentVo == null || agentVo.getId() == null ? KERNEL_MODEL_AGENT_SEGMENT : String.valueOf(agentVo.getId()),
            sessionId == null ? null : String.valueOf(sessionId),
            augmentedContent,
            nativePrompt,
            KernelModelRequest.from(modelVo),
            tools.toolkit(), tools,
            () -> initialHistory(sessionId, currentMessageId), sink
        );
        subscription.set(active);
        if (disconnected.get() || !session.isOpen()) {
            cancel.run();
            unregisterKernelStream(session, cancel);
        }
    }

    /** 与旧 saveChatMessage 的五个业务字段一致，但写入结果必须可观察。 */
    /** 按真实持久化ID截断，只用于初始化尚无原生状态的会话。 */
    private List<io.agentscope.core.message.Msg> initialHistory(Long sessionId, Long beforeMessageId) {
        if (beforeMessageId == null) { return List.of(); }
        List<io.agentscope.core.message.Msg> rows = chatMessageService.getMessagesBySessionId(sessionId);
        return rows == null ? List.of() : rows.stream().filter(row -> {
            Object id = row.getMetadata() == null ? null : row.getMetadata().get("chatMessageId");
            return id instanceof Number number && number.longValue() < beforeMessageId;
        }).toList();
    }

    private Long saveRequiredMessage(Long userId, Long sessionId, String content, String role, String modelName) {
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
        return message.getId();
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
        return multiKnowledgeAugmentorFactory.augment(kids, content, null);
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
