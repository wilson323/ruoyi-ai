package org.ruoyi.service.chat.impl.provider.doubao;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class DoubaoNativeModelTest {
    @Test void sdkRetainsReplacementMetadataButDoesNotReplaceAccumulatedText() {
        var accumulator = new io.agentscope.core.agent.accumulator.ReasoningContext("dify");
        accumulator.processChunk(io.agentscope.core.model.ChatResponse.builder()
            .content(List.of(TextBlock.builder().text("before").build())).build());
        accumulator.processChunk(io.agentscope.core.model.ChatResponse.builder().content(List.of())
            .metadata(Map.of("replacementText", "after")).build());
        var message = accumulator.buildFinalMessage();
        assertEquals("before", message.getTextContent());
        assertEquals("after", message.getMetadata().get("replacementText"));
    }

    @Test void preservesEncryptedReasoningAndXhighAcrossToolRound() throws Exception {
        var json = new ObjectMapper();
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            body.set(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] response = ("data: {\"id\":\"response\",\"choices\":[{\"delta\":{\"reasoning_content\":\"reasoning\",\"encrypted_content\":\"encrypted\",\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]}}]}\n\n" + "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = DoubaoStreamingChatModel.builder().endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions")
                .apiKey("local-test").modelName("doubao-seed").reasoningEffort("high").thinkingEnabled(true).timeout(Duration.ofSeconds(2)).build();
            var user = Msg.builder().role(MsgRole.USER).content(List.of(ImageBlock.builder().source(new URLSource("https://example.test/image.png")).build()))
                .metadata(Map.of("imageDetail", "xhigh")).build();
            var prior = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(TextBlock.builder().text("prior").build()))
                .metadata(Map.of(DoubaoStreamingChatModel.ENCRYPTED_CONTENT_ATTRIBUTE, "original-encrypted")).build();
            var responses = model.stream(List.of(prior, user), List.of(), GenerateOptions.builder().build()).collectList().block();
            assertEquals("xhigh", body.get().path("messages").get(1).path("content").get(0).path("image_url").path("detail").asText());
            assertEquals("original-encrypted", body.get().path("messages").get(0).path("encrypted_content").asText());
            var last = responses.get(responses.size() - 1);
            assertEquals("encrypted", last.getMetadata().get(DoubaoStreamingChatModel.ENCRYPTED_CONTENT_ATTRIBUTE));
            var accumulator = new io.agentscope.core.agent.accumulator.ReasoningContext("doubao");
            responses.forEach(accumulator::processChunk);
            assertEquals("encrypted", accumulator.buildFinalMessage().getMetadata().get(DoubaoStreamingChatModel.ENCRYPTED_CONTENT_ATTRIBUTE));
            ToolUseBlock tool = (ToolUseBlock) last.getContent().get(0);
            assertEquals("search", tool.getName());
            assertEquals("encrypted", tool.getMetadata().get(DoubaoStreamingChatModel.ENCRYPTED_CONTENT_ATTRIBUTE));
        } finally { server.stop(0); }
    }
}
