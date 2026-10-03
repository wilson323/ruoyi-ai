package org.ruoyi.ipd.agent.kernel;

import org.ruoyi.ipd.service.AiModelUsageLedgerService;

import java.util.Map;
import java.util.Objects;

/**
 * 把项目智能体模型结束步骤上的真实 token 写入既有用量账本。
 * 没有 inputTokens 的步骤（模型开始、空 usage）不落账。
 */
public final class ProjectAgentUsageSink implements ProjectAgentEventSink {

    static final String SCENE = "project_agent";

    private final ProjectAgentEventSink delegate;
    private final AiModelUsageLedgerService ledger;
    private final Long modelConfigId;
    private final String actorId;
    private final String traceId;

    /**
     * @param delegate 原事件出口
     * @param ledger 既有用量账本
     * @param modelConfigId 本次运行选定的模型配置
     * @param actorId 运行人
     * @param traceId 运行 ID 字符串
     */
    public ProjectAgentUsageSink(ProjectAgentEventSink delegate, AiModelUsageLedgerService ledger,
                                 Long modelConfigId, String actorId, String traceId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.modelConfigId = Objects.requireNonNull(modelConfigId, "modelConfigId");
        this.actorId = actorId;
        this.traceId = traceId;
    }

    @Override
    public void requireActiveOwnership() { delegate.requireActiveOwnership(); }
    @Override public long executionEpoch() { return delegate.executionEpoch(); }
    @Override public void registerTerminalSuccessReceipt(Runnable receipt) { delegate.registerTerminalSuccessReceipt(receipt); }
    @Override public boolean isPaused() { return delegate.isPaused(); }
    @Override public void onAguiInterrupt(Map<String,io.agentscope.core.agui.event.AguiEvent.Interrupt> pending,long version) {
        delegate.onAguiInterrupt(pending,version);
    }
    @Override public void onChildInterrupt(java.util.List<ProjectAgentChildLineageRegistry.ChildApproval> children,long version) {
        delegate.onChildInterrupt(children,version);
    }
    @Override public void recordChildCompletion(ProjectAgentChildLineageRegistry.ChildCompletion completion) {
        requireActiveOwnership();delegate.recordChildCompletion(completion);
    }
    @Override public java.util.List<ProjectAgentChildLineageRegistry.ChildCompletion> loadChildCompletions() {
        requireActiveOwnership();return delegate.loadChildCompletions();
    }
    @Override public void requireChildResumeConsumed(ProjectAgentChildLineageRegistry.ChildApproval approval) {
        delegate.requireChildResumeConsumed(approval);
    }
    @Override
    public <T> T withActiveOwnership(java.util.function.Supplier<T> action) { return delegate.withActiveOwnership(action); }
    @Override
    public void registerTemporaryStateCleanup(Runnable cleanup) { delegate.registerTemporaryStateCleanup(cleanup); }
    @Override
    public void releaseTemporaryState() { delegate.releaseTemporaryState(); }

    @Override
    public void onStep(String kind, Map<String, Object> detail) {
        delegate.onStep(kind, detail);
        recordStepUsage(kind, detail);
    }

    /** 执行器在epoch保护事务内调用，仅落既有账本，不再次进入事件/终态路径。 */
    public void recordStepUsage(String kind, Map<String, Object> detail) {
        if (!"MODEL_CALL".equals(kind) || detail == null || !detail.containsKey("inputTokens")) {
            return;
        }
        int input = asInt(detail.get("inputTokens"));
        int output = asInt(detail.get("outputTokens"));
        String outcome = Objects.toString(detail.get("outcome"), "COMPLETE");
        String status = switch (outcome) {
            case "ERROR" -> "error";
            case "CANCELLED" -> "cancelled";
            default -> "ok";
        };
        ledger.recordUsage(modelConfigId, actorId, SCENE, input, output, 0L, status, traceId);
    }

    @Override
    public void onToolCall(String toolCallId, String toolName) {
        delegate.onToolCall(toolCallId, toolName);
    }

    @Override
    public void onToolResult(String toolCallId, String toolName, String state) {
        delegate.onToolResult(toolCallId, toolName, state);
    }

    @Override
    public void onSource(Map<String, Object> source) {
        delegate.onSource(source);
    }

    @Override
    public void onText(String delta) {
        delegate.onText(delta);
    }

    @Override public void onFinalText(String fullText) { delegate.onFinalText(fullText); }

    @Override
    public void onArtifact(String artifactId, String title, String contentHash, int version) {
        delegate.onArtifact(artifactId, title, contentHash, version);
    }

    @Override public void onArtifactPayload(Map<String, Object> payload) { delegate.onArtifactPayload(payload); }

    @Override
    public void onError(String errorCode) {
        delegate.onError(errorCode);
    }

    @Override
    public void onComplete() {
        delegate.onComplete();
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
