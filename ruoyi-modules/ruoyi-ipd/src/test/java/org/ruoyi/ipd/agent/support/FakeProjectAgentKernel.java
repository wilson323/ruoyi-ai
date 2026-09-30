package org.ruoyi.ipd.agent.support;

import org.ruoyi.ipd.agent.kernel.ProjectAgentEventSink;
import org.ruoyi.ipd.agent.kernel.ProjectAgentKernel;
import org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试替身内核（非运行态证据）：记录每次执行的冻结输入与 sink，由测试手动驱动回调，
 * 可观测上游是否被 dispose。可配置为执行即抛异常。
 */
public final class FakeProjectAgentKernel implements ProjectAgentKernel {

    /** 一次执行的记录。 */
    public record Execution(ProjectAgentRunSpec spec, ProjectAgentEventSink sink, AtomicBoolean disposed) { }

    public final List<Execution> executions = new CopyOnWriteArrayList<>();
    public volatile RuntimeException failWith;

    /** {@inheritDoc} */
    @Override
    public Disposable execute(ProjectAgentRunSpec spec, ProjectAgentEventSink sink) {
        if (failWith != null) {
            throw failWith;
        }
        AtomicBoolean disposed = new AtomicBoolean();
        executions.add(new Execution(spec, sink, disposed));
        return new Disposable() {
            @Override
            public void dispose() {
                disposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };
    }

    /** @return 最近一次执行 */
    public Execution last() {
        return executions.get(executions.size() - 1);
    }
}
