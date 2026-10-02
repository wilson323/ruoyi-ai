package org.ruoyi.observability;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 原生 Model 流的可观测 middleware；不记录消息正文、工具参数或凭据。 */
@Slf4j
public class MyChatModelListener {
    private interface ObservedModel extends Model { }
    public Model wrap(Model delegate) {
        if (delegate instanceof ObservedModel) { return delegate; }
        return new ObservedModel() {
            @Override
            public String getModelName() { return delegate.getModelName(); }
            @Override
            public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
            @Override
            public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
            @Override
            public int getContextWindowSize() { return delegate.getContextWindowSize(); }
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    long started = System.nanoTime();
                    AtomicReference<ChatUsage> usage = new AtomicReference<>();
                    log.info("chat_model status=STARTED model={} messageCount={} toolCount={}", getModelName(),
                        messages == null ? 0 : messages.size(), tools == null ? 0 : tools.size());
                    return delegate.stream(messages, tools, options)
                        .doOnNext(response -> { if (response.getUsage() != null) { usage.set(response.getUsage()); } })
                        .doOnError(error -> log.error("chat_model status=FAILED model={} errorType={} elapsedMs={}",
                            getModelName(), error.getClass().getName(), (System.nanoTime() - started) / 1_000_000))
                        .doOnComplete(() -> log.info("chat_model status=COMPLETED model={} usage={} elapsedMs={}",
                            getModelName(), usage.get(), (System.nanoTime() - started) / 1_000_000));
                });
            }
        };
    }
}
