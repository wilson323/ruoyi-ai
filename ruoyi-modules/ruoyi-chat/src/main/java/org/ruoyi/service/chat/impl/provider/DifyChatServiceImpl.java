package org.ruoyi.service.chat.impl.provider;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.*;
import io.github.imfangs.dify.client.*;
import io.github.imfangs.dify.client.callback.ChatStreamCallback;
import io.github.imfangs.dify.client.event.*;
import io.github.imfangs.dify.client.enums.ResponseMode;
import io.github.imfangs.dify.client.model.DifyConfig;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.enums.ChatModeType;
import org.ruoyi.service.chat.AbstractChatService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;

/** Dify 官方应用协议薄适配为 AgentScope Model，不另建 agent 循环。 */
@Service
public class DifyChatServiceImpl implements AbstractChatService {
    @Override public String getProviderName() { return ChatModeType.DIFY.getCode(); }
    @Override public Model buildStreamingChatModel(ChatModelVo config, ChatRequest runtime) { return model(config, runtime); }
    @Override public Model buildChatModel(ChatModelVo config) { return model(config, null); }

    @jakarta.annotation.PostConstruct
    public void registerNativeAdapter() {
        ModelRegistry.registerFactory("dify:.*", (String modelId, ModelCreationContext context) -> {
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
                if (tools != null && !tools.isEmpty()) { return Flux.error(new IllegalArgumentException("Dify 应用不接受本地工具目录")); }
                return Flux.create(sink -> {
                    try {
                        var dify = DifyClientFactory.createChatClient(DifyConfig.builder().baseUrl(config.getApiHost())
                            .apiKey(config.resolveApiKeyForConfiguredEndpoint(getProviderName()))
                            .connectTimeout(5000).readTimeout(300000).writeTimeout(30000).build());
                        StringBuilder query = new StringBuilder();
                        if (messages != null) {
                            for (Msg message : messages) { query.append(message.getRole()).append(": ").append(message.getTextContent()).append('\n'); }
                        }
                        String user = runtime != null && runtime.getUserId() != null ? String.valueOf(runtime.getUserId())
                            : runtime != null && runtime.getSessionId() != null ? "session-" + runtime.getSessionId() : "ruoyi-ai";
                        var request = io.github.imfangs.dify.client.model.chat.ChatMessage.builder().query(query.toString())
                            .inputs(Map.of()).responseMode(ResponseMode.STREAMING).user(user).autoGenerateName(true).build();
                        dify.sendChatMessageStream(request, new ChatStreamCallback() {
                            private void next(String text, String id) {
                                if (text != null && !text.isEmpty() && !sink.isCancelled()) {
                                    sink.next(ChatResponse.builder().id(id).content(List.of(TextBlock.builder().text(text).build())).build());
                                }
                            }
                            @Override public void onMessage(MessageEvent event) { next(event.getAnswer(), event.getMessageId()); }
                            @Override public void onAgentMessage(AgentMessageEvent event) { next(event.getAnswer(), event.getMessageId()); }
                            @Override public void onMessageReplace(MessageReplaceEvent event) {
                                if (!sink.isCancelled()) { sink.next(ChatResponse.builder().id(event.getMessageId()).content(List.of())
                                    .metadata(Map.of("replacementText", event.getAnswer())).build()); }
                            }
                            @Override public void onMessageEnd(MessageEndEvent event) {
                                var metadata = event.getMetadata();
                                var usage = metadata == null ? null : metadata.getUsage();
                                sink.next(ChatResponse.builder().id(event.getMessageId()).content(List.of()).finishReason("stop")
                                    .usage(usage == null ? null : new ChatUsage(usage.getPromptTokens(), usage.getCompletionTokens(), 0)).build());
                                sink.complete();
                            }
                            @Override public void onError(ErrorEvent event) { sink.error(new IllegalStateException("Dify 应用调用失败：" + event.getCode())); }
                            @Override public void onException(Throwable error) { sink.error(error); }
                        });
                    } catch (Exception error) { sink.error(error); }
                });
            }
        });
    }
}
