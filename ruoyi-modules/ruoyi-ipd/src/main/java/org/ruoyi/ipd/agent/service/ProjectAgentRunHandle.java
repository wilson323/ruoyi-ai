package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.kernel.ProjectAgentEventSink;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 单次运行的事件写入者（单写者 + 终态收口 + SUCCEEDED 时落 ARTIFACT 草稿）。
 *
 * <p>并发纪律：全部写入方法对本对象加锁；seq 在锁内分配，终态收口后 {@code closed=true}，
 * 此后任何迟到帧一律丢弃。终态以数据库 CAS 为准；CANCEL_REQUESTED 一律收口为 CANCELLED。
 */
public final class ProjectAgentRunHandle implements ProjectAgentEventSink {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgentRunHandle.class);

    static final long STATUS_PROBE_INTERVAL_MS = 2_000L;
    /** 有正文且产物插入失败时写入 FAILED 的错误码。 */
    static final String ARTIFACT_PERSIST = "ARTIFACT_PERSIST";
    private static final int FINISH_ATTEMPTS = 3;

    private final Long runId;
    private final String tenantId;
    private final Long personId;
    private final String actionCode;
    private final AgentRunStore store;
    private final ArtifactVersionStore artifactStore;
    private final ObjectMapper mapper;
    private final LongSupplier clock;
    private final Runnable onClosed;
    private Consumer<String> onSucceeded = text -> { };
    private final StringBuilder textBuffer = new StringBuilder();
    /** 最近一次文本落库的时钟；0 表示本段还没开始计时。 */
    private long lastTextFlushAt;
    private final StringBuilder fullText = new StringBuilder();
    private final ProjectAgentCompletionGate completion;
    /** Quality 域 V-2 产物校验器；生产默认实例，测试可注入替身。 */
    private ProjectAgentArtifactVerifier verifier = new ProjectAgentArtifactVerifier();
    private long seq;
    private boolean closed;
    private boolean paused;
    private java.util.function.BiConsumer<Map<String, io.agentscope.core.agui.event.AguiEvent.Interrupt>, Long> aguiInterruptHandler;
    private java.util.function.BiConsumer<java.util.List<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval>, Long> childInterruptHandler;
    private long lastStatusProbe;
    private Disposable subscription;
    private TransactionTemplate finishTransaction;
    private ProjectAgentRunOwnership.Lease ownership;
    private Integer epoch;
    private Runnable temporaryStateCleanup = () -> { };
    private Runnable terminalSuccessReceipt = () -> { };
    private Consumer<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval> childResumeGuard;

    /**
     * @param run 已进入 RUNNING 的运行
     * @param store 运行持久化
     * @param artifactStore 产物版本持久化（可空则跳过 ARTIFACT）
     * @param mapper JSON 映射器
     * @param clock 毫秒时钟
     * @param onClosed 收口回调
     */
    public ProjectAgentRunHandle(IpdAgentRun run, AgentRunStore store, ArtifactVersionStore artifactStore,
                                 ObjectMapper mapper, LongSupplier clock, Runnable onClosed) {
        this.runId = run.getId();
        this.tenantId = run.getTenantId();
        this.personId = run.getPersonId();
        this.actionCode = run.getActionCode();
        this.completion = new ProjectAgentCompletionGate(this.actionCode);
        this.store = store;
        this.artifactStore = artifactStore;
        this.mapper = mapper;
        this.clock = clock;
        this.onClosed = onClosed == null ? () -> { } : onClosed;
        this.seq = store.maxSeq(runId);
        this.lastStatusProbe = clock.getAsLong();
    }

    /** Quality 域 V-2 校验器注入；空引用回落默认实例。 */
    public void setVerifier(ProjectAgentArtifactVerifier verifier) {
        this.verifier = verifier == null ? new ProjectAgentArtifactVerifier() : verifier;
    }

    /** 生产执行器绑定同数据源事务；直接构造的内存测试保留兼容。 */
    public void setFinishTransaction(TransactionTemplate transaction) {
        if (transaction == null) {
            // 直接构造的内存执行器没有事务；不会签发已提交的生产成功回执。
            this.finishTransaction = null;
            return;
        }
        var isolated = new TransactionTemplate(java.util.Objects.requireNonNull(
            transaction.getTransactionManager(), "finish transaction manager"), transaction);
        // finish 的返回、提交回执和释放租约必须对应自己的真实提交，不能参与尚未提交的外层事务。
        isolated.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.finishTransaction = isolated;
    }

    public void setOwnership(ProjectAgentRunOwnership.Lease ownership, int epoch) {
        this.ownership = ownership;
        this.epoch = epoch;
    }

    /** 用量、需求等副作用也必须在原run行锁内验证epoch，不依赖本机closed状态。 */
    public synchronized void runOwned(Runnable action) {
        if (closed) throw new ProjectAgentRunOwnership.OwnershipLost();
        try {
            if (ownership == null) { action.run(); return; }
            if (finishTransaction == null) throw new IllegalStateException("owned run requires transaction");
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                assertOwnership();
                action.run();
            } else {
                finishTransaction.executeWithoutResult(status -> { assertOwnership(); action.run(); });
            }
        } catch (ProjectAgentRunOwnership.OwnershipLost lost) {
            closeLost();
            throw lost;
        }
    }

    private void assertOwnership() {
        if (ownership == null) return;
        try {
            if (!store.lockEpoch(runId, epoch) || !ownership.held()) throw new ProjectAgentRunOwnership.OwnershipLost();
        } catch (RuntimeException unavailable) { throw new ProjectAgentRunOwnership.OwnershipLost(); }
    }

    public synchronized void abandonOwnership() { closeLost(); }

    private void closeLost() {
        if (closed) return;
        closed = true;
        if (subscription != null) subscription.dispose();
        try { onClosed.run(); } catch (RuntimeException failure) {
            log.warn("project_agent operation=OWNERSHIP_RELEASE status=FAILED runId={} errorType={}", runId, failure.getClass().getName());
        }
    }

    /**
     * 运行成功收口后把已校验的交付正文交给回写。失败和取消不调用。
     *
     * @param consumer 成功回调，可空
     */
    public void whenSucceeded(Consumer<String> consumer) {
        this.onSucceeded = consumer == null ? text -> { } : consumer;
    }

    @Override
    public void requireActiveOwnership() { runOwned(() -> { }); }

    @Override public synchronized long executionEpoch() {
        return withActiveOwnership(() -> {
            if (ownership == null || epoch == null)
                throw new IllegalStateException("execution epoch is not bound");
            return epoch.longValue();
        });
    }

    /** 等待审批不占执行租约/额度；不变更数据库状态，不追加终态。 */
    public synchronized void pauseForApproval() { closeLost(); }

    @Override
    public synchronized <T> T withActiveOwnership(java.util.function.Supplier<T> action) {
        var value = new java.util.concurrent.atomic.AtomicReference<T>();
        runOwned(() -> value.set(action.get()));
        return value.get();
    }

    @Override
    public synchronized void registerTemporaryStateCleanup(Runnable cleanup) {
        if (closed) throw new ProjectAgentRunOwnership.OwnershipLost();
        temporaryStateCleanup = cleanup == null ? () -> { } : cleanup;
    }

    @Override public synchronized void registerTerminalSuccessReceipt(Runnable receipt) {
        if (closed) throw new ProjectAgentRunOwnership.OwnershipLost();
        terminalSuccessReceipt = java.util.Objects.requireNonNull(receipt);
    }

    @Override
    public synchronized void releaseTemporaryState() { if (!paused) runOwned(temporaryStateCleanup); }

    public synchronized void setAguiInterruptHandler(java.util.function.BiConsumer<
            Map<String, io.agentscope.core.agui.event.AguiEvent.Interrupt>, Long> handler) {
        aguiInterruptHandler = java.util.Objects.requireNonNull(handler);
    }

    @Override public synchronized boolean isPaused() { return paused; }

    @Override public synchronized void onAguiInterrupt(
            Map<String, io.agentscope.core.agui.event.AguiEvent.Interrupt> pending, long checkpointVersion) {
        if (closed) throw new ProjectAgentRunOwnership.OwnershipLost();
        if (aguiInterruptHandler == null) throw new IllegalStateException("AG-UI pause handler is not configured");
        // handler 必须提交原 run CAS 与事件事务后返回；提交失败不释放订阅。
        aguiInterruptHandler.accept(Map.copyOf(pending), checkpointVersion);
        paused = true;
        closeLost();
    }

    /** 子审批必须先由原运行服务提交持久化凭据与等待状态。 */
    public synchronized void setChildInterruptHandler(java.util.function.BiConsumer<
            java.util.List<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval>, Long> handler) {
        childInterruptHandler = java.util.Objects.requireNonNull(handler);
    }

    @Override public synchronized void onChildInterrupt(
            java.util.List<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval> approvals,
            long rootCheckpointVersion) {
        if (closed) throw new ProjectAgentRunOwnership.OwnershipLost();
        if (childInterruptHandler == null) throw new IllegalStateException("child pause handler is not configured");
        var frozen = java.util.List.copyOf(approvals);
        if (frozen.isEmpty()) throw new IllegalArgumentException("child approvals must not be empty");
        // 事务失败时不释放订阅；与根审批共用原运行资源收口。
        childInterruptHandler.accept(frozen, rootCheckpointVersion);
        paused = true;
        closeLost();
    }

    public synchronized void setChildResumeGuard(Consumer<
            org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval> guard) {
        childResumeGuard = java.util.Objects.requireNonNull(guard);
    }

    @Override public synchronized void requireChildResumeConsumed(
            org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildApproval approval) {
        if (childResumeGuard == null) throw new IllegalStateException("child consumed receipt guard is not configured");
        runOwned(() -> childResumeGuard.accept(java.util.Objects.requireNonNull(approval)));
    }

    private Consumer<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildCompletion> childCompletionWriter;
    private java.util.function.Supplier<java.util.List<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildCompletion>> childCompletionReader;
    public synchronized void setChildCompletionJournal(
            Consumer<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildCompletion> writer,
            java.util.function.Supplier<java.util.List<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildCompletion>> reader) {
        childCompletionWriter=java.util.Objects.requireNonNull(writer);childCompletionReader=java.util.Objects.requireNonNull(reader);
    }
    @Override public synchronized void recordChildCompletion(org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildCompletion completion) {
        if(childCompletionWriter==null) throw new IllegalStateException("child completion writer is not configured");
        runOwned(()->childCompletionWriter.accept(java.util.Objects.requireNonNull(completion)));
    }
    @Override public synchronized java.util.List<org.ruoyi.ipd.agent.kernel.ProjectAgentChildLineageRegistry.ChildCompletion> loadChildCompletions() {
        if(childCompletionReader==null) throw new IllegalStateException("child completion reader is not configured");
        return withActiveOwnership(()->java.util.List.copyOf(childCompletionReader.get()));
    }

    /** 已拼好的助手全文。 */
    public String assistantText() {
        return fullText.toString();
    }

    /**
     * 兼容无产物存储的调用（单测 / 旧装配）；不落 ARTIFACT。
     *
     * @param run 已进入 RUNNING 的运行
     * @param store 运行持久化
     * @param mapper JSON 映射器
     * @param clock 毫秒时钟
     * @param onClosed 收口回调
     */
    public ProjectAgentRunHandle(IpdAgentRun run, AgentRunStore store, ObjectMapper mapper,
                                 LongSupplier clock, Runnable onClosed) {
        this(run, store, null, mapper, clock, onClosed);
    }

    /** @return 运行 ID */
    public Long runId() {
        return runId;
    }

    /** @return 是否已收口 */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * 绑定上游订阅；已收口则立即取消上游。
     *
     * @param disposable 上游句柄
     */
    public synchronized void attach(Disposable disposable) {
        this.subscription = disposable;
        if (closed && disposable != null) {
            disposable.dispose();
        }
    }

    /**
     * 追加一个非终态事件（已收口则丢弃）。
     *
     * @param type 事件类型（非终态）
     * @param payload 载荷
     */
    public synchronized void append(AgentEventType type, Map<String, Object> payload) {
        if (closed || type.isTerminal()) {
            return;
        }
        if (probeCancelRequested()) {
            return;
        }
        if (type != AgentEventType.TEXT_DELTA) {
            flushText();
        }
        write(type, payload);
    }

    /** {@inheritDoc} */
    @Override
    public void onStep(String kind, Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        if (detail != null) {
            payload.putAll(detail);
        }
        append(AgentEventType.STEP, payload);
    }

    /** {@inheritDoc} */
    @Override
    public void onToolCall(String toolCallId, String toolName) {
        completion.noteTool(toolName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolCallId);
        payload.put("toolName", toolName);
        append(AgentEventType.TOOL_CALL, payload);
    }

    /** {@inheritDoc} */
    @Override
    public void onToolResult(String toolCallId, String toolName, String state) {
        completion.noteTool(toolName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolCallId);
        payload.put("toolName", toolName);
        payload.put("state", state);
        append(AgentEventType.TOOL_RESULT, payload);
    }

    /** {@inheritDoc} */
    @Override
    public void onSource(Map<String, Object> source) {
        completion.noteSource(source);
        Map<String, Object> visible = new LinkedHashMap<>(source == null ? Map.of() : source);
        Object citation = visible.remove("citationText");
        if (citation instanceof String text) {
            visible.put("citationChars", text.length());
            visible.put("citationSha256", ProjectAgentSkillCatalog.sha256Hex(text));
        }
        append(AgentEventType.SOURCE, visible);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void onText(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        if (ownership != null) {
            try { runOwned(() -> { }); } catch (ProjectAgentRunOwnership.OwnershipLost lost) { return; }
        }
        textBuffer.append(delta);
        fullText.append(delta);
        long now = clock.getAsLong();
        if (lastTextFlushAt == 0L) {
            lastTextFlushAt = now;
        }
        boolean charsFull = textBuffer.length() >= ProjectAgentConstants.TEXT_FLUSH_CHARS;
        boolean intervalDue = now - lastTextFlushAt >= ProjectAgentConstants.TEXT_FLUSH_INTERVAL_MS;
        if ((charsFull || intervalDue) && !probeCancelRequested()) {
            flushText();
        }
    }

    /** 原生SDK最终正文有修订时，沿同一TEXT_DELTA事件原子替换权威正文。 */
    @Override
    public synchronized void onFinalText(String text) {
        if (closed || text == null || text.equals(fullText.toString())) return;
        long previousSeq = seq;
        long previousFlushAt = lastTextFlushAt;
        String previousText = fullText.toString();
        String previousBuffer = textBuffer.toString();
        try {
            runOwned(() -> {
                writeOwned(AgentEventType.TEXT_DELTA, Map.of("text", text, "replace", true));
                fullText.setLength(0);
                fullText.append(text);
                textBuffer.setLength(0);
                lastTextFlushAt = clock.getAsLong();
            });
        } catch (RuntimeException failure) {
            seq = previousSeq;
            fullText.setLength(0);
            fullText.append(previousText);
            textBuffer.setLength(0);
            textBuffer.append(previousBuffer);
            lastTextFlushAt = previousFlushAt;
            if (!(failure instanceof ProjectAgentRunOwnership.OwnershipLost)) throw failure;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onArtifact(String artifactId, String title, String contentHash, int version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactId", artifactId);
        payload.put("title", title);
        payload.put("contentHash", contentHash);
        payload.put("version", version);
        putVersionId(payload, artifactId, null);
        append(AgentEventType.ARTIFACT, payload);
    }

    @Override
    public void onArtifactPayload(Map<String, Object> payload) {
        append(AgentEventType.ARTIFACT, Map.copyOf(payload));
    }

    /** {@inheritDoc} */
    @Override
    public void onError(String errorCode) {
        finish(AgentRunStatus.FAILED, errorCode == null ? "KERNEL_ERROR" : errorCode);
    }

    /** {@inheritDoc} */
    @Override
    public void onComplete() {
        finish(AgentRunStatus.SUCCEEDED, null);
    }

    /**
     * 本机取消：状态已由服务落为 CANCEL_REQUESTED 后调用，收口为 CANCELLED。
     *
     * @return true 本次写入了终态
     */
    public boolean cancel() {
        return finish(AgentRunStatus.CANCELLED, null);
    }

    /**
     * 终态收口：CAS 胜者写唯一终态事件。
     *
     * <p>生产路径在独立事务中提交剩余文本、必要草稿、终态 CAS 和事件；提交后才释放资源。
     * 插入返回失败或抛错则迁到 FAILED，不再只记警告。没有正文，或未装配产物存储时，成功收口不要求产物。
     *
     * @param target 期望终态
     * @param errorCode 失败码（FAILED 时）
     * @return true 本次调用写入了终态事件
     */
    public synchronized boolean finish(AgentRunStatus target, String errorCode) {
        if (closed) {
            return false;
        }
        AgentRunStatus requested = target;
        String requestedCode = errorCode;
        for (int attempt = 0; attempt < FINISH_ATTEMPTS; attempt++) {
            long previousSeq = seq;
            String pendingText = textBuffer.toString();
            long previousFlushAt = lastTextFlushAt;
            try {
                AgentRunStatus finalTarget = requested;
                String finalCode = requestedCode;
                FinishResult result = finishTransaction == null
                    ? finishOnce(finalTarget, finalCode)
                    : finishTransaction.execute(status -> finishOnce(finalTarget, finalCode));
                if (result == null) {
                    throw new IllegalStateException("finish transaction returned no result");
                }
                if (result.terminal()) {
                    if (result.won()) cleanupCommitted(result.status());
                    closeCommitted(result.status(), result.won());
                }
                return result.won();
            } catch (ProjectAgentRunOwnership.OwnershipLost lost) {
                closeLost();
                return false;
            } catch (RuntimeException failure) {
                // 数据库事务已回滚，内存游标和尚未提交的文本也回到同一个继续点。
                if (finishTransaction != null) {
                    seq = previousSeq;
                    textBuffer.setLength(0);
                    textBuffer.append(pendingText);
                    lastTextFlushAt = previousFlushAt;
                }
                if (!(failure instanceof FinishRace)) {
                    requested = AgentRunStatus.FAILED;
                    requestedCode = failure instanceof ArtifactPersistenceFailure ? ARTIFACT_PERSIST : "KERNEL_ERROR";
                    log.warn("project_agent operation=FINISH_TRANSACTION status=FAILED runId={} errorType={}",
                        runId, failure.getClass().getName());
                }
            }
        }
        // 持久层持续失败：保留句柄和额度，不能假装已提交终态或丢弃待写文本。
        if (subscription != null) {
            subscription.dispose();
        }
        log.error("project_agent operation=FINISH_TRANSACTION status=UNRESOLVED runId={}", runId);
        return false;
    }

    private FinishResult finishOnce(AgentRunStatus target, String errorCode) {
        assertOwnership();
        AgentRunStatus current = store.findRun(runId).map(r -> AgentRunStatus.valueOf(r.getStatus())).orElse(null);
        if (current == null) {
            throw new IllegalStateException("run disappeared during finish");
        }
        if (current.isTerminal()) {
            if (store.terminalSeq(runId).isEmpty()) {
                throw new IllegalStateException("terminal run has no terminal event");
            }
            return new FinishResult(false, true, current);
        }
        seq = Math.max(seq, store.maxSeq(runId));
        flushText();
        AgentRunStatus effective = current == AgentRunStatus.CANCEL_REQUESTED ? AgentRunStatus.CANCELLED : target;
        String code = effective == AgentRunStatus.FAILED ? errorCode : null;
        ProjectAgentCompletionGate.RejectionReason completionReason = null;
        if (effective == AgentRunStatus.SUCCEEDED) {
            completionReason = completion.rejectionReason(fullText.toString());
            if (completionReason != null) {
                effective = AgentRunStatus.FAILED;
                code = ProjectAgentCompletionGate.REJECTED;
            }
        }
        ArtifactDraft draft = null;
        if (effective == AgentRunStatus.SUCCEEDED && artifactRequired()) {
            try {
                draft = insertArtifactDraft();
                if (draft == null) {
                    throw new ArtifactPersistenceFailure();
                }
            } catch (RuntimeException failure) {
                throw new ArtifactPersistenceFailure(failure);
            }
        }
        ProjectAgentArtifactVerifier.Verdict verifyVerdict = null;
        if (draft != null) {
            // Quality 域 V-2：产物已落库后机器校验；BLOCK 缺口停 VERIFYING 不判 FAILED（设计 §4.2/§4.3）。
            verifyVerdict = verifier.evaluate(actionCode, deliverableBody());
            if (verifyVerdict.hasBlockingGaps()) {
                effective = AgentRunStatus.VERIFYING;
                code = null;
            }
        }
        if (!store.transition(runId, EnumSet.of(current), effective, code, new Date(clock.getAsLong()))) {
            throw new FinishRace();
        }
        if (draft != null) {
            writeArtifactEvent(draft);
        }
        if (effective == AgentRunStatus.VERIFYING) {
            writeVerifyGapsStep(verifyVerdict);
        } else {
            writeTerminal(effective, code, completionReason);
        }
        if (effective == AgentRunStatus.SUCCEEDED) {
            try { onSucceeded.accept(deliverableBody()); } catch (RuntimeException bindEx) {
                log.warn("project_agent operation=DEMAND_BIND status=FAILED runId={} errorType={}", runId, bindEx.getClass().getName());
            }
        }
        return new FinishResult(true, true, effective);
    }

    /** execute 返回意味着原终态事务已提交；外部状态清理不再置于可回滚的数据库事务内。 */
    private void cleanupCommitted(AgentRunStatus status) {
        try {
            if (status == AgentRunStatus.SUCCEEDED && finishTransaction != null) {
                if (ownership == null || !ownership.held())
                    throw new ProjectAgentRunOwnership.OwnershipLost();
                terminalSuccessReceipt.run();
            }
            temporaryStateCleanup.run();
        } catch (RuntimeException failure) {
            // 终态已经提交，不能重写成失败或重试副作用；清理可能部分完成，记录待对账错误。
            log.error("project_agent operation=COMMITTED_STATE_CLEANUP status=UNRESOLVED runId={} errorType={}",
                runId, failure.getClass().getName());
        }
    }

    /** 只在事务提交后清理执行资源；需求回写是既有可选后置，不影响运行终态。 */
    private void closeCommitted(AgentRunStatus status, boolean won) {
        closed = true;
        if (subscription != null) {
            subscription.dispose();
        }
        try {
            onClosed.run();
        } catch (RuntimeException failure) {
            log.warn("project_agent operation=CLOSE_CALLBACK status=FAILED runId={} errorType={}",
                runId, failure.getClass().getName());
        }
    }

    private record FinishResult(boolean won, boolean terminal, AgentRunStatus status) { }
    private static final class FinishRace extends RuntimeException { }
    private static final class ArtifactPersistenceFailure extends RuntimeException {
        ArtifactPersistenceFailure() { }
        ArtifactPersistenceFailure(Throwable cause) { super(cause); }
    }

    private String deliverableBody() {
        return ProjectAgentCompletionGate.deliverableBody(fullText.toString());
    }

    /** 有正文且已装配产物存储时，成功收口前必须先插入草稿。 */
    private boolean artifactRequired() {
        return artifactStore != null && !deliverableBody().isEmpty();
    }

    /**
     * 插入 DRAFT 产物行，不写事件。事件要等 SUCCEEDED 迁移赢了再写。
     *
     * @return 插入成功的草稿；唯一键冲突返回 null
     */
    private ArtifactDraft insertArtifactDraft() {
        String content = deliverableBody();
        String hash = ProjectAgentSkillCatalog.sha256Hex(content);
        String artifactId = UUID.randomUUID().toString().replace("-", "");
        String title = (actionCode == null || actionCode.isBlank() ? "项目智能体产物" : actionCode + " 产物");
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        Date now = new Date(clock.getAsLong());
        IpdAgentArtifactVersion version = IpdAgentArtifactVersion.builder()
            .tenantId(tenantId)
            .runId(runId)
            .artifactId(artifactId)
            .versionNo(1)
            .title(title)
            .content(content)
            .contentSha256(hash)
            .status(IpdAgentArtifactVersion.STATUS_DRAFT)
            .delFlag("0")
            .build();
        version.setCreateTime(now);
        version.setCreateBy(personId);
        version.setUpdateBy(personId);
        if (!artifactStore.insert(version)) {
            log.warn("project_agent operation=ARTIFACT_INSERT status=DUPLICATE runId={}", runId);
            return null;
        }
        return new ArtifactDraft(artifactId, title, hash, version.getId());
    }

    /** 直接 write，避免经 append 再探测取消导致 finish 重入。 */
    private void writeArtifactEvent(ArtifactDraft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactId", draft.artifactId());
        payload.put("title", draft.title());
        payload.put("contentHash", draft.hash());
        payload.put("version", 1);
        putVersionId(payload, draft.artifactId(), draft.versionRowId());
        write(AgentEventType.ARTIFACT, payload);
    }

    /** 已插入、尚未写 ARTIFACT 事件的草稿。 */
    private record ArtifactDraft(String artifactId, String title, String hash, Long versionRowId) {
    }

    /**
     * 点赞主键是版本行雪花 id。逻辑 artifactId 仍单独下发给定档。
     *
     * @param payload 事件载荷
     * @param artifactId 逻辑产物 ID
     * @param versionRowId 已知版本行 id；空则按逻辑 id 回查
     */
    private void putVersionId(Map<String, Object> payload, String artifactId, Long versionRowId) {
        Long rowId = versionRowId;
        if (rowId == null && artifactStore != null && artifactId != null) {
            rowId = artifactStore.findLatest(runId, artifactId).map(IpdAgentArtifactVersion::getId).orElse(null);
        }
        if (rowId != null) {
            payload.put("versionId", String.valueOf(rowId));
        }
    }

    private boolean probeCancelRequested() {
        long now = clock.getAsLong();
        if (now - lastStatusProbe < STATUS_PROBE_INTERVAL_MS) {
            return false;
        }
        lastStatusProbe = now;
        boolean requested = store.findRun(runId)
            .map(r -> AgentRunStatus.CANCEL_REQUESTED.name().equals(r.getStatus())).orElse(false);
        if (requested) {
            finish(AgentRunStatus.CANCELLED, null);
        }
        return requested;
    }

    private void flushText() {
        if (textBuffer.length() == 0) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", textBuffer.toString());
        write(AgentEventType.TEXT_DELTA, payload);
        textBuffer.setLength(0);
        lastTextFlushAt = clock.getAsLong();
    }

    /** 校验缺口挂 STEP（kind=VERIFY_GAPS 复用既有事件类型，不新增枚举；缺口可寻址）。 */
    private void writeVerifyGapsStep(ProjectAgentArtifactVerifier.Verdict verdict) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "VERIFY_GAPS");
        payload.put("verdict", ProjectAgentArtifactVerifier.Verdict.GAPS);
        payload.put("title", "产物校验");
        payload.put("detail", "产物已生成，机器校验发现缺口，等待补证据后复检。" + verdict.summary());
        List<Map<String, Object>> checks = new ArrayList<>();
        for (ProjectAgentArtifactVerifier.Gap gap : verdict.gaps()) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("id", gap.id());
            check.put("status", gap.status());
            check.put("severity", gap.severity().name());
            check.put("evidencePath", gap.evidencePath());
            check.put("gapSummary", gap.gapSummary());
            checks.add(check);
        }
        payload.put("checks", checks);
        write(AgentEventType.STEP, payload);
    }

    private void writeTerminal(AgentRunStatus status, String errorCode,
                               ProjectAgentCompletionGate.RejectionReason completionReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (status == AgentRunStatus.FAILED) {
            payload.put("errorCode", errorCode);
            payload.put("message", ProjectAgentErrorTexts.textOf(errorCode));
            if (completionReason != null) {
                payload.put("completionReason", completionReason.name());
            }
            write(AgentEventType.ERROR, payload);
        } else {
            payload.put("status", status.name());
            write(AgentEventType.RUN_FINISHED, payload);
        }
    }

    private void write(AgentEventType type, Map<String, Object> payload) {
        runOwned(() -> writeOwned(type, payload));
    }

    private void writeOwned(AgentEventType type, Map<String, Object> payload) {
        long next = seq + 1;
        IpdAgentRunEvent event = ProjectAgentRunEvents.of(runId, tenantId, personId, next, type,
            toJson(payload), new Date(clock.getAsLong()));
        if (!store.appendEvent(event)) {
            throw new IllegalStateException("run event insert rejected");
        }
        seq = next;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event serialization failed", e);
        }
    }
}
