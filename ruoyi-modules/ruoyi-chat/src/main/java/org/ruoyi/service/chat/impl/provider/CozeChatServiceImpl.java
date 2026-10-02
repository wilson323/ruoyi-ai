package org.ruoyi.service.chat.impl.provider;

import com.coze.openapi.client.chat.CreateChatReq;
import com.coze.openapi.client.chat.model.ChatEventType;
import com.coze.openapi.client.connversations.message.model.Message;
import com.coze.openapi.service.auth.TokenAuth;
import com.coze.openapi.service.service.CozeAPI;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.*;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.enums.ChatModeType;
import org.ruoyi.service.chat.AbstractChatService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.ArrayList;

/** Coze 官方应用客户端适配原生 AgentScope Model。 */
@Service
public class CozeChatServiceImpl implements AbstractChatService {
    @Override public String getProviderName() { return ChatModeType.COZE.getCode(); }
    @Override public Model buildStreamingChatModel(ChatModelVo config, ChatRequest runtime) { return model(config, runtime); }
    @Override public Model buildChatModel(ChatModelVo config) { return model(config, null); }

    @jakarta.annotation.PostConstruct
    public void registerNativeAdapter() {
        ModelRegistry.registerFactory("coze:.*", (String modelId, ModelCreationContext context) -> {
            ChatModelVo config = new ChatModelVo();
            config.setProviderCode(getProviderName());
            config.setModelName(modelId.substring(modelId.indexOf(':') + 1));
            config.setApiHost(context.getBaseUrl());
            config.setApiKey(context.getApiKey());
            ChatRequest runtime = new ChatRequest();
            Object userId = context.option("userId");
            Object sessionId = context.option("sessionId");
            if (userId != null) { runtime.setUserId(Long.valueOf(userId.toString())); }
            if (sessionId != null) { runtime.setSessionId(Long.valueOf(sessionId.toString())); }
            return model(config, runtime);
        });
    }

    private Model model(ChatModelVo config, ChatRequest runtime) {
        return new org.ruoyi.observability.MyChatModelListener().wrap(new Model() {
            public String getModelName() { return config.getModelName(); }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (tools != null && !tools.isEmpty()) { return Flux.error(new IllegalArgumentException("Coze 应用不接受本地工具目录")); }
                return Flux.create(sink -> {
                    CozeAPI client = new CozeAPI.Builder().baseURL(config.getApiHost())
                        .auth(new TokenAuth(config.resolveApiKeyForConfiguredEndpoint(getProviderName())))
                        .connectTimeout(10000).readTimeout(300000).build();
                    sink.onDispose(client::shutdownExecutor);
                    try {
                        List<Message> inputs = new ArrayList<>();
                        if (messages != null) {
                            for (Msg message : messages) {
                                inputs.add(message.getRole() == MsgRole.ASSISTANT ? Message.buildAssistantAnswer(message.getTextContent())
                                    : Message.buildUserQuestionText(message.getRole() == MsgRole.SYSTEM
                                        ? "System:\n" + message.getTextContent() : message.getTextContent()));
                            }
                        }
                        String user = runtime != null && runtime.getUserId() != null ? String.valueOf(runtime.getUserId())
                            : runtime != null && runtime.getSessionId() != null ? "session-" + runtime.getSessionId() : "ruoyi-ai";
                        client.chat().stream(CreateChatReq.builder().botID(config.getModelName()).userID(user)
                            .messages(inputs).autoSaveHistory(false).build()).blockingForEach(event -> {
                                if (sink.isCancelled()) { return; }
                                if (ChatEventType.CONVERSATION_MESSAGE_DELTA.equals(event.getEvent()) && event.getMessage() != null) {
                                    String content = event.getMessage().getContent();
                                    if (content != null && !content.isEmpty()) { sink.next(ChatResponse.builder().id(event.getMessage().getId())
                                        .content(List.of(TextBlock.builder().text(content).build())).build()); }
                                }
                                if (ChatEventType.CONVERSATION_CHAT_COMPLETED.equals(event.getEvent())) {
                                    var chat = event.getChat();
                                    var usage = chat == null ? null : chat.getUsage();
                                    sink.next(ChatResponse.builder().id(chat == null ? null : chat.getID()).content(List.of()).finishReason("stop")
                                        .usage(usage == null ? null : new ChatUsage(usage.getInputTokens(), usage.getOutputTokens(), 0)).build());
                                    sink.complete();
                                }
                                if (ChatEventType.CONVERSATION_CHAT_FAILED.equals(event.getEvent()) || ChatEventType.ERROR.equals(event.getEvent())) {
                                    sink.error(new IllegalStateException("Coze 应用调用失败"));
                                }
                            });
                        if (!sink.isCancelled()) { sink.complete(); }
                    } catch (Exception error) { sink.error(error); }
                });
            }
        });
    }
}
