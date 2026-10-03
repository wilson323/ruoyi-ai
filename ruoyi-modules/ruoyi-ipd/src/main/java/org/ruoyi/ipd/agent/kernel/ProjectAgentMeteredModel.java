package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

/** Measures every official model consumer through the original run STEP/usage sink. */
public final class ProjectAgentMeteredModel implements Model {
    private final org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity identity;
    private final Model delegate;
    private final ProjectAgentEventSink sink;
    private final ProjectAgentEventSink accountingSink;

    public ProjectAgentMeteredModel(Model delegate, ProjectAgentEventSink sink) {
        this(delegate, sink, sink);
    }

    /** 新调用验证业务访问权，已消费的可信用量按原执行租约记账。 */
    public ProjectAgentMeteredModel(Model delegate, ProjectAgentEventSink sink,
            ProjectAgentEventSink accountingSink) {
        this(delegate, sink, accountingSink, null);
    }

    public ProjectAgentMeteredModel(Model delegate, ProjectAgentEventSink sink, ProjectAgentEventSink accountingSink,
            org.ruoyi.ipd.agent.model.ProjectAgentModelIdentity identity) {
        this.identity = identity;
        this.delegate = Objects.requireNonNull(delegate);
        this.sink = Objects.requireNonNull(sink);
        this.accountingSink = Objects.requireNonNull(accountingSink);
    }

    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    @Override public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }

    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
            GenerateOptions options) {
        return Flux.defer(() -> {
            Call call = new Call(UUID.randomUUID().toString());
            sink.requireActiveOwnership();
            sink.onModelCall(identity, Map.of("modelCallId", call.id, "phase", "START"));
            return Flux.defer(() -> delegate.stream(messages, tools, options))
                .doOnNext(call::observe)
                .doOnComplete(() -> call.finish("COMPLETE"))
                .doOnError(error -> call.finish("ERROR"))
                .doOnCancel(() -> call.finish("CANCELLED"));
        });
    }

    private final class Call {
        private final String id;
        private final Map<String, ChatUsage> snapshots = new LinkedHashMap<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private Call(String id) { this.id = id; }

        private synchronized void observe(ChatResponse response) {
            ChatUsage usage = response.getUsage();
            if (usage == null || usage.getInputTokens() < 0 || usage.getOutputTokens() < 0) return;
            // Official 2.0.3 OpenAI parser reports per-response cumulative usage, usually last chunk.
            // Replace snapshots, never add cumulative chunks. Distinct retry response IDs are distinct usage.
            snapshots.put(response.getId(), usage);
        }

        private synchronized void finish(String outcome) {
            if (!finished.compareAndSet(false, true)) return;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("modelCallId", id);
            detail.put("phase", "END");
            detail.put("outcome", outcome);
            detail.put("usageAvailable", !snapshots.isEmpty());
            if (!snapshots.isEmpty()) {
                long input = 0;
                long output = 0;
                for (ChatUsage usage : snapshots.values()) {
                    input = Math.addExact(input, usage.getInputTokens());
                    output = Math.addExact(output, usage.getOutputTokens());
                }
                detail.put("inputTokens", Math.toIntExact(input));
                detail.put("outputTokens", Math.toIntExact(output));
                detail.put("usageComplete", "COMPLETE".equals(outcome));
            }
            accountingSink.withActiveOwnership(() -> {
                accountingSink.onModelCall(identity, Map.copyOf(detail));
                return null;
            });
        }
    }
}
