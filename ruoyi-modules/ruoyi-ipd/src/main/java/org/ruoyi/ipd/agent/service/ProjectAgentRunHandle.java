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

import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    private final StringBuilder textBuffer = new StringBuilder();
    /** 最近一次文本落库的时钟；0 表示本段还没开始计时。 */
    private long lastTextFlushAt;
    private final StringBuilder fullText = new StringBuilder();
    private final ProjectAgentCompletionGate completion;
    private long seq;
    private boolean closed;
    private long lastStatusProbe;
    private Disposable subscription;

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
        append(AgentEventType.SOURCE, source == null ? Map.of() : source);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void onText(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
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
     * 终态收口：CAS 胜者写唯一终态事件；SUCCEEDED 且有正文时先落 ARTIFACT 草稿。
     *
     * @param target 期望终态
     * @param errorCode 失败码（FAILED 时）
     * @return true 本次调用写入了终态事件
     */
    public synchronized boolean finish(AgentRunStatus target, String errorCode) {
        if (closed) {
            return false;
        }
        flushText();
        boolean won = false;
        AgentRunStatus effectiveWon = null;
        boolean completionEvaluated = false;
        ProjectAgentCompletionGate.RejectionReason completionReason = null;
        for (int attempt = 0; attempt < FINISH_ATTEMPTS && !won; attempt++) {
            AgentRunStatus current = store.findRun(runId).map(r -> AgentRunStatus.valueOf(r.getStatus())).orElse(null);
            if (current == null || current.isTerminal()) {
                break;
            }
            AgentRunStatus effective = current == AgentRunStatus.CANCEL_REQUESTED ? AgentRunStatus.CANCELLED : target;
            String code = effective == AgentRunStatus.FAILED ? errorCode : null;
            if (effective == AgentRunStatus.SUCCEEDED) {
                if (!completionEvaluated) {
                    completionReason = completion.rejectionReason(fullText.toString());
                    completionEvaluated = true;
                }
                if (completionReason != null) {
                    effective = AgentRunStatus.FAILED;
                    code = ProjectAgentCompletionGate.REJECTED;
                }
            }
            won = store.transition(runId, EnumSet.of(current), effective, code, new Date(clock.getAsLong()));
            if (won) {
                effectiveWon = effective;
                if (effective == AgentRunStatus.SUCCEEDED) {
                    // 产物落库失败不得吞掉终态事件（否则 status=SUCCEEDED 但无 RUN_FINISHED/ARTIFACT）
                    try {
                        persistArtifactDraft();
                    } catch (RuntimeException artifactEx) {
                        log.warn("project_agent operation=ARTIFACT_PERSIST status=FAILED runId={} errorType={}",
                            runId, artifactEx.getClass().getName());
                    }
                }
                writeTerminal(effective, code, effective == AgentRunStatus.FAILED ? completionReason : null);
            }
        }
        closed = true;
        if (subscription != null) {
            subscription.dispose();
        }
        try {
            onClosed.run();
        } catch (RuntimeException e) {
            log.warn("project_agent operation=CLOSE_CALLBACK status=FAILED runId={} errorType={}",
                runId, e.getClass().getName());
        }
        return won && effectiveWon != null;
    }

    /** SUCCEEDED 时将助手全文落为 DRAFT 产物版本，并写 ARTIFACT 事件（同事件表）。 */
    private void persistArtifactDraft() {
        if (artifactStore == null || fullText.length() == 0) {
            return;
        }
        String content = fullText.toString();
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
            return;
        }
        // 直接 write，避免经 append 再探测取消导致 finish 重入。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifactId", artifactId);
        payload.put("title", title);
        payload.put("contentHash", hash);
        payload.put("version", 1);
        putVersionId(payload, artifactId, version.getId());
        write(AgentEventType.ARTIFACT, payload);
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
        textBuffer.setLength(0);
        lastTextFlushAt = clock.getAsLong();
        write(AgentEventType.TEXT_DELTA, payload);
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
        long next = ++seq;
        IpdAgentRunEvent event = ProjectAgentRunEvents.of(runId, tenantId, personId, next, type,
            toJson(payload), new Date(clock.getAsLong()));
        if (!store.appendEvent(event)) {
            log.warn("project_agent operation=APPEND status=DUPLICATE runId={} seq={}", runId, next);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
