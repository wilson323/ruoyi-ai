package org.ruoyi.chat.kernel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.transcript.FilesystemTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Official transcript producer + store extensions. Install BOTH middleware and transcriptStore.
 * SDK writer merges ThinkingBlock with ordinary text and writes a local mirror before invoking
 * TranscriptStore, so store-only redaction cannot safely remove reasoning from every copy.
 * Known credential values are exact-redacted; this is not a detector for unknown free-text secrets.
 */
public final class ChatSafeTranscriptStore implements TranscriptStore, MiddlewareBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<String> knownSecrets;
    private volatile TranscriptStore delegate;

    public ChatSafeTranscriptStore(Collection<String> knownSecrets) {
        this.knownSecrets = knownSecrets == null ? List.of() : knownSecrets.stream()
            .filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct()
            .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    /** Bind the official provider after build; the same runtime namespace is retained. */
    public synchronized void bind(WorkspaceManager manager) {
        if (delegate != null) throw new IllegalStateException("Transcript provider already bound");
        // Transcript segments outlive short-lived Docker containers. Keep SDK's native
        // immutable filesystem store under the trusted workspace; TranscriptRef retains
        // tenant/agent/session routing. A wrapped ObjectStore cannot use SDK's concrete-type
        // sandbox pin optimization and would risk an async upload after container shutdown.
        delegate = new FilesystemTranscriptStore(Objects.requireNonNull(manager).getWorkspace()
            .resolve(".chat-transcripts"));
    }

    /** Explicit provider injection for deterministic store verification. */
    ChatSafeTranscriptStore(TranscriptStore delegate, Collection<String> knownSecrets) {
        this(knownSecrets);
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override public int order() { return Integer.MIN_VALUE; }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).concatWith(Mono.fromRunnable(() -> {
            AgentState state = RuntimeContext.resolveAgentState(context, agent);
            if (state == null) throw new IllegalStateException("Transcript call-scoped state is required");
            List<Msg> safe = state.getContext().stream().map(this::sanitizeMessage).toList();
            state.contextMutable().clear();
            state.contextMutable().addAll(safe);
            state.setSummary(redact(state.getSummary()));
        }).thenMany(Flux.empty()));
    }

    Msg sanitizeMessage(Msg message) {
        List<ContentBlock> safe = new ArrayList<>();
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ThinkingBlock) continue;
            if (block instanceof TextBlock text) {
                safe.add(TextBlock.builder().text(redact(text.getText())).build());
            } else if (block instanceof ToolUseBlock use) {
                // Tool identity is an audit record; arbitrary arguments are not transcript prose.
                safe.add(new ToolUseBlock(use.getId(), use.getName(), Map.of()));
            } else if (block instanceof ToolResultBlock result) {
                safe.add(new ToolResultBlock(result.getId(), result.getName(),
                    List.of(TextBlock.builder().text("Tool result recorded by the chat audit channel").build()),
                    Map.of(), result.getState()));
            }
            // Images/audio/document payloads have authoritative artifact storage; omit raw payloads.
        }
        return Msg.builder().id(message.getId()).name(message.getName()).role(message.getRole())
            .content(safe).timestamp(message.getTimestamp()).usage(message.getUsage()).build();
    }

    private String redact(String text) {
        if (text == null) return "";
        String safe = text;
        for (String secret : knownSecrets) safe = safe.replace(secret, "[REDACTED]");
        return safe;
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node.isTextual()) return TextNode.valueOf(redact(node.textValue()));
        if (node.isObject()) {
            ObjectNode safe = JSON.createObjectNode();
            node.fields().forEachRemaining(entry -> safe.set(entry.getKey(), sanitizeNode(entry.getValue())));
            return safe;
        }
        if (node.isArray()) {
            var safe = JSON.createArrayNode();
            node.forEach(value -> safe.add(sanitizeNode(value)));
            return safe;
        }
        return node;
    }

    @Override public String appendSegment(TranscriptRef ref, long start, long end, String writer, byte[] jsonl) {
        try {
            StringBuilder safe = new StringBuilder();
            for (String line : new String(jsonl, StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) safe.append(JSON.writeValueAsString(sanitizeNode(JSON.readTree(line)))).append('\n');
            }
            return provider().appendSegment(ref, start, end, writer, safe.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("Transcript segment could not be sanitized", failure);
        }
    }

    private TranscriptStore provider() { return Objects.requireNonNull(delegate, "Official transcript provider is not bound"); }
    @Override public List<SegmentInfo> listSegments(TranscriptRef ref) { return provider().listSegments(ref); }
    @Override public InputStream readSegment(String key) { return provider().readSegment(key); }
    @Override public void compact(TranscriptRef ref) { provider().compact(ref); }
    @Override public void delete(TranscriptRef ref) { provider().delete(ref); }
    @Override public TranscriptStore withRuntimeContext(RuntimeContext context) {
        return new ChatSafeTranscriptStore(provider().withRuntimeContext(context), knownSecrets);
    }
}
