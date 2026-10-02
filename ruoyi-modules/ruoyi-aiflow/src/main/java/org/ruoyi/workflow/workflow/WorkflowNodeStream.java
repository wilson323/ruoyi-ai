package org.ruoyi.workflow.workflow;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** AgentScope 回调到既有节点 SSE 的桥；不承担图调度或状态存储。 */
public final class WorkflowNodeStream {
    private record Signal(String chunk, Throwable error, boolean terminal) { }
    private final BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
    public void chunk(String text) { if (!ended.get()) signals.add(new Signal(text, null, false)); }
    public void complete() { complete(() -> {}); }
    public void complete(Runnable storeResponse) {
        if (!ended.compareAndSet(false, true)) return;
        try {
            storeResponse.run();
            signals.add(new Signal(null, null, true));
        } catch (Throwable error) {
            signals.add(new Signal(null, error, true));
            if (error instanceof Error fatal) throw fatal;
        }
    }
    public void fail(Throwable error) { if (ended.compareAndSet(false, true)) signals.add(new Signal(null, error, true)); }
    public void consume(Consumer<String> consumer) {
        try {
            while (true) {
                Signal next = signals.poll(300, TimeUnit.SECONDS);
                if (next == null) throw new IllegalStateException("工作流模型响应超时");
                if (next.error() != null) throw new IllegalStateException("工作流模型调用失败", next.error());
                if (next.terminal()) return;
                consumer.accept(next.chunk());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工作流模型响应被中断", error);
        }
    }
}
