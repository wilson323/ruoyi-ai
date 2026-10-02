package org.ruoyi.chat.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.model.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/** 最终原生消息为正文/用量权威；供应商正文替换不变成第二条发送轨。 */
public final class KernelFinalResponse {
    private KernelFinalResponse() { }
    public static String text(Msg result, String accumulated) {
        if (result == null) { return accumulated; }
        Object replacement = result.getMetadata() == null ? null : result.getMetadata().get("replacementText");
        if (replacement instanceof String text) { return text; }
        String finalText = result.getTextContent();
        return finalText == null || finalText.isEmpty() ? accumulated : finalText;
    }
    public static ChatResponse response(Msg result, String accumulated) {
        if (result == null) {
            return ChatResponse.builder().content(List.of(TextBlock.builder().text(accumulated).build())).build();
        }
        Object replacement = result.getMetadata() == null ? null : result.getMetadata().get("replacementText");
        List<ContentBlock> content = result.getContent();
        if (replacement instanceof String text) {
            content = new ArrayList<>();
            if (result.getContent() != null) {
                for (ContentBlock block : result.getContent()) {
                    if (!(block instanceof TextBlock)) { content.add(block); }
                }
            }
            content.add(TextBlock.builder().text(text).build());
        }
        return ChatResponse.builder().id(result.getId()).content(content)
            .metadata(result.getMetadata()).usage(result.getChatUsage()).build();
    }
}
