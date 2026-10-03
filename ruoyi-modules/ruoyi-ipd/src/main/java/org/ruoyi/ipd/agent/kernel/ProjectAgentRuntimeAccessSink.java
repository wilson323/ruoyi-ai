package org.ruoyi.ipd.agent.kernel;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 在原执行所有权边界重新读取业务访问权，不保存角色或权限快照。 */
final class ProjectAgentRuntimeAccessSink implements ProjectAgentEventSink {
    private final ProjectAgentEventSink delegate;
    private final ProjectAgentRunSpec spec;
    private final Consumer<ProjectAgentRunSpec> access;

    ProjectAgentRuntimeAccessSink(ProjectAgentEventSink delegate, ProjectAgentRunSpec spec,
                                 Consumer<ProjectAgentRunSpec> access) {
        this.delegate = Objects.requireNonNull(delegate);
        this.spec = Objects.requireNonNull(spec);
        this.access = Objects.requireNonNull(access);
    }

    /** 终态清理仅核原 run 所有权，撤销业务访问不能阻止服务器回收私有检查点。 */
    ProjectAgentEventSink checkpointOwnership() { return delegate; }

    @Override public void requireActiveOwnership() {
        delegate.requireActiveOwnership();
        access.accept(spec);
    }
    @Override public long executionEpoch() {
        requireActiveOwnership();
        return delegate.executionEpoch();
    }
    @Override public <T> T withActiveOwnership(Supplier<T> action) {
        return delegate.withActiveOwnership(() -> {
            access.accept(spec);
            return action.get();
        });
    }
    @Override public void registerTemporaryStateCleanup(Runnable cleanup) { delegate.registerTemporaryStateCleanup(cleanup); }
    @Override public void registerTerminalSuccessReceipt(Runnable receipt) {
        requireActiveOwnership();
        delegate.registerTerminalSuccessReceipt(receipt);
    }
    @Override public void recordChildCompletion(ProjectAgentChildLineageRegistry.ChildCompletion completion) {
        requireActiveOwnership();delegate.recordChildCompletion(completion);
    }
    @Override public java.util.List<ProjectAgentChildLineageRegistry.ChildCompletion> loadChildCompletions() {
        requireActiveOwnership();return delegate.loadChildCompletions();
    }
    @Override public void requireChildResumeConsumed(ProjectAgentChildLineageRegistry.ChildApproval approval) {
        requireActiveOwnership();
        delegate.requireChildResumeConsumed(approval);
    }
    @Override public void releaseTemporaryState() { delegate.releaseTemporaryState(); }
    @Override public void onAguiInterrupt(Map<String, io.agentscope.core.agui.event.AguiEvent.Interrupt> pending,
                                         long checkpointVersion) {
        requireActiveOwnership();
        delegate.onAguiInterrupt(pending, checkpointVersion);
    }
    @Override public void onChildInterrupt(java.util.List<ProjectAgentChildLineageRegistry.ChildApproval> pending,
                                          long checkpointVersion) {
        requireActiveOwnership();
        delegate.onChildInterrupt(pending, checkpointVersion);
    }
    @Override public boolean isPaused() { return delegate.isPaused(); }
    @Override public void onStep(String kind, Map<String, Object> detail) { delegate.onStep(kind, detail); }
    @Override public void onToolCall(String id, String name) { delegate.onToolCall(id, name); }
    @Override public void onToolResult(String id, String name, String state) { delegate.onToolResult(id, name, state); }
    @Override public void onSource(Map<String, Object> source) { delegate.onSource(source); }
    @Override public void onText(String delta) { delegate.onText(delta); }
    @Override public void onFinalText(String text) { delegate.onFinalText(text); }
    @Override public void onArtifact(String id, String title, String hash, int version) { delegate.onArtifact(id, title, hash, version); }
    @Override public void onError(String code) { delegate.onError(code); }
    @Override public void onComplete() { delegate.onComplete(); }
}
