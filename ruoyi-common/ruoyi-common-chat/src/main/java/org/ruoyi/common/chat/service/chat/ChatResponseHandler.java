package org.ruoyi.common.chat.service.chat;

import io.agentscope.core.model.ChatResponse;

/** 传输层回调合同；响应使用 AgentScope 原生类型，不再依赖另一套模型 SDK。 */
public interface ChatResponseHandler {
    default void onPartialResponse(String content) { }
    default void onPartialThinking(String reasoning) { }
    void onCompleteResponse(ChatResponse response);
    void onError(Throwable error);
}
