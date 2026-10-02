package org.ruoyi.service.chat.impl.provider.doubao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** AgentScope 2.0.3 未覆盖 encrypted_content/xhigh 的必要 Doubao 协议适配。 */
public final class DoubaoStreamingChatModel implements Model {
    public static final String ENCRYPTED_CONTENT_ATTRIBUTE = "doubao_encrypted_content";
    public static final String ENCRYPTED_CONTENT_FIELD = "encrypted_content";
    private final String endpoint, apiKey, modelName, reasoningEffort;
    private final Duration timeout;
    private final boolean thinkingEnabled;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client;
    private DoubaoStreamingChatModel(Builder builder) {
        endpoint = Objects.requireNonNull(builder.endpoint);
        apiKey = Objects.requireNonNull(builder.apiKey);
        modelName = Objects.requireNonNull(builder.modelName);
        reasoningEffort = builder.reasoningEffort;
        thinkingEnabled = builder.thinkingEnabled;
        timeout = builder.timeout == null ? Duration.ofMinutes(10) : builder.timeout;
        client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }
    public static Builder builder() { return new Builder(); }
    @Override public String getModelName() { return modelName; }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.<ChatResponse>create(sink -> {
            AtomicReference<Stream<String>> body = new AtomicReference<>();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(serialize(messages, tools, options)))).build();
                var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
                sink.onDispose(() -> {
                    future.cancel(true);
                    Stream<String> stream = body.get();
                    if (stream != null) { stream.close(); }
                });
                future.whenComplete((response, error) -> {
                    if (error != null) { if (!sink.isCancelled()) { sink.error(error); } return; }
                    body.set(response.body());
                    try (Stream<String> lines = response.body()) {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            sink.error(new IllegalStateException("Doubao 返回 HTTP " + response.statusCode()));
                            return;
                        }
                        State state = new State(sink);
                        lines.forEach(state::accept);
                        state.finish();
                    } catch (Exception failure) { if (!sink.isCancelled()) { sink.error(failure); } }
                });
            } catch (Exception failure) { sink.error(failure); }
        }).timeout(timeout);
    }

    private ObjectNode serialize(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        ObjectNode request = json.createObjectNode().put("model", modelName).put("stream", true);
        request.putObject("stream_options").put("include_usage", true);
        request.putObject("thinking").put("type", thinkingEnabled ? "enabled" : "disabled");
        if (thinkingEnabled && reasoningEffort != null && !reasoningEffort.isBlank()) { request.put("reasoning_effort", reasoningEffort); }
        if (options != null && options.getMaxTokens() != null) { request.put("max_tokens", options.getMaxTokens()); }
        ArrayNode output = request.putArray("messages");
        for (Msg message : messages) {
            for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                String text = result.getOutput().stream().filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).collect(java.util.stream.Collectors.joining("\n"));
                output.addObject().put("role", "tool").put("tool_call_id", result.getId()).put("content", text);
            }
            if (message.hasContentBlocks(ToolResultBlock.class) && message.getContent().size() == message.getContentBlocks(ToolResultBlock.class).size()) { continue; }
            ObjectNode node = output.addObject().put("role", message.getRole().name().toLowerCase(Locale.ROOT));
            ArrayNode content = json.createArrayNode();
            int imageIndex = 0;
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock text) { content.addObject().put("type", "text").put("text", text.getText()); }
                else if (block instanceof ImageBlock image) {
                    String url;
                    if (image.getSource() instanceof URLSource source) { url = source.getUrl(); }
                    else if (image.getSource() instanceof Base64Source source) { url = "data:" + source.getMediaType() + ";base64," + source.getData(); }
                    else { throw new IllegalArgumentException("不支持的图片来源"); }
                    ObjectNode imageUrl = content.addObject().put("type", "image_url").putObject("image_url");
                    imageUrl.put("url", url);
                    Object detail = message.getMetadata() == null ? null : message.getMetadata().get("imageDetail");
                    if (message.getMetadata() != null && message.getMetadata().get("imageDetails") instanceof List<?> details && imageIndex < details.size()) { detail = details.get(imageIndex); }
                    imageUrl.put("detail", detail == null ? "auto" : detail.toString());
                    imageIndex++;
                }
            }
            node.set("content", content);
            String reasoning = message.getContentBlocks(ThinkingBlock.class).stream().map(ThinkingBlock::getThinking)
                .collect(java.util.stream.Collectors.joining());
            if (!reasoning.isBlank()) { node.put("reasoning_content", reasoning); }
            Object encrypted = message.getMetadata() == null ? null : message.getMetadata().get(ENCRYPTED_CONTENT_ATTRIBUTE);
            if (encrypted == null && message.getMetadata() != null) { encrypted = message.getMetadata().get(ENCRYPTED_CONTENT_FIELD); }
            if (encrypted == null) {
                for (ToolUseBlock tool : message.getContentBlocks(ToolUseBlock.class)) {
                    if (tool.getMetadata() != null && tool.getMetadata().containsKey(ENCRYPTED_CONTENT_ATTRIBUTE)) { encrypted = tool.getMetadata().get(ENCRYPTED_CONTENT_ATTRIBUTE); }
                }
            }
            if (encrypted != null) { node.put(ENCRYPTED_CONTENT_FIELD, encrypted.toString()); }
            if (message.hasContentBlocks(ToolUseBlock.class)) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolUseBlock tool : message.getContentBlocks(ToolUseBlock.class)) {
                    ObjectNode call = calls.addObject().put("id", tool.getId()).put("type", "function");
                    call.putObject("function").put("name", tool.getName()).put("arguments", json.valueToTree(tool.getInput()).toString());
                }
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolNodes = request.putArray("tools");
            for (ToolSchema tool : tools) {
                ObjectNode function = toolNodes.addObject().put("type", "function").putObject("function");
                function.put("name", tool.getName()).put("description", tool.getDescription());
                function.set("parameters", json.valueToTree(tool.getParameters()));
            }
        }
        return request;
    }

    private final class State {
        private final FluxSink<ChatResponse> sink;
        private String id, finishReason;
        private ChatUsage usage;
        private final StringBuilder encrypted = new StringBuilder();
        private final Map<Integer, Tool> tools = new LinkedHashMap<>();
        private boolean finished;
        State(FluxSink<ChatResponse> sink) { this.sink = sink; }
        void accept(String line) {
            if (sink.isCancelled() || finished || !line.startsWith("data:")) { return; }
            String data = line.substring(5).strip();
            if ("[DONE]".equals(data)) { finish(); return; }
            if (data.isBlank()) { return; }
            try {
                JsonNode root = json.readTree(data);
                if (root.has("error")) { sink.error(new IllegalStateException("Doubao 流返回错误")); finished = true; return; }
                if (root.has("id")) { id = root.get("id").asText(); }
                JsonNode u = root.get("usage");
                if (u != null && !u.isNull()) { usage = new ChatUsage(u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt(), 0); }
                for (JsonNode choice : root.path("choices")) {
                    if (choice.hasNonNull("finish_reason")) { finishReason = choice.get("finish_reason").asText(); }
                    JsonNode delta = choice.path("delta");
                    List<ContentBlock> content = new ArrayList<>();
                    if (delta.hasNonNull("content")) { content.add(TextBlock.builder().text(delta.get("content").asText()).build()); }
                    if (delta.hasNonNull("reasoning_content")) { content.add(ThinkingBlock.builder().thinking(delta.get("reasoning_content").asText()).build()); }
                    if (delta.hasNonNull(ENCRYPTED_CONTENT_FIELD)) { encrypted.append(delta.get(ENCRYPTED_CONTENT_FIELD).asText()); }
                    // 空内容帧仍表示 SSE 有进展，保留原有闲置超时而非整轮绝对截止。
                    sink.next(ChatResponse.builder().id(id).content(content).build());
                    for (JsonNode call : delta.path("tool_calls")) {
                        Tool tool = tools.computeIfAbsent(call.path("index").asInt(), key -> new Tool());
                        if (call.hasNonNull("id")) { tool.id.append(call.get("id").asText()); }
                        JsonNode function = call.path("function");
                        if (function.hasNonNull("name")) { tool.name.append(function.get("name").asText()); }
                        if (function.hasNonNull("arguments")) { tool.arguments.append(function.get("arguments").asText()); }
                    }
                }
            } catch (Exception error) { finished = true; sink.error(error); }
        }
        void finish() {
            if (finished || sink.isCancelled()) { return; }
            finished = true;
            try {
                Map<String, Object> metadata = encrypted.isEmpty() ? Map.of() : Map.of(ENCRYPTED_CONTENT_ATTRIBUTE, encrypted.toString());
                List<ContentBlock> content = new ArrayList<>();
                for (Tool tool : tools.values()) {
                    Map<String, Object> arguments = tool.arguments.isEmpty() ? Map.of()
                        : json.readValue(tool.arguments.toString(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    content.add(new ToolUseBlock(tool.id.toString(), tool.name.toString(), arguments, null, metadata));
                }
                sink.next(ChatResponse.builder().id(id).content(content).metadata(metadata).usage(usage).finishReason(finishReason).build());
                sink.complete();
            } catch (Exception error) { sink.error(error); }
        }
    }
    private static final class Tool { final StringBuilder id = new StringBuilder(), name = new StringBuilder(), arguments = new StringBuilder(); }
    public static final class Builder {
        private String endpoint, apiKey, modelName, reasoningEffort;
        private Duration timeout;
        private boolean thinkingEnabled;
        public Builder endpoint(String value) { endpoint = value; return this; }
        public Builder apiKey(String value) { apiKey = value; return this; }
        public Builder modelName(String value) { modelName = value; return this; }
        public Builder timeout(Duration value) { timeout = value; return this; }
        public Builder reasoningEffort(String value) { reasoningEffort = value; return this; }
        public Builder thinkingEnabled(boolean value) { thinkingEnabled = value; return this; }
        public DoubaoStreamingChatModel build() { return new DoubaoStreamingChatModel(this); }
    }
}
