package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.state.*;
import org.ruoyi.chat.kernel.KernelScopeKey;
import java.util.*;
import java.util.function.Supplier;

/** 原生SDK store的run生命周期边界：不保留终态内部推理，seal后禁止异步晚写。 */
final class ProjectAgentTemporaryStateStore implements AgentStateStore {
    private final AgentStateStore delegate;
    private final KernelScopeKey.Scope scope;
    private final ProjectAgentEventSink sink;
    private final ProjectAgentChildLineageRegistry lineage;
    /**
     * 裸 sessionId（本仓 ipd 链 = runId 字符串）。官方无参 {@code getAgentState()} 以
     * {@code (null, defaultSessionId)} 访问，AG-UI 运行时以 {@code (runId, runId)} 身份访问——
     * 两者都不是复合 scope 身份；裸 runId 雪花全局唯一，认它不降低隔离强度。
     */
    private final String rawSessionId;
    private final Object monitor = new Object();
    private boolean sealed;
    private final Set<String> ownedSessions = new LinkedHashSet<>();
    ProjectAgentTemporaryStateStore(AgentStateStore delegate, KernelScopeKey.Scope scope, ProjectAgentEventSink sink) {
        this(delegate, scope, sink, null, null);
    }
    ProjectAgentTemporaryStateStore(AgentStateStore delegate, KernelScopeKey.Scope scope,
            ProjectAgentEventSink sink, ProjectAgentChildLineageRegistry lineage) {
        this(delegate, scope, sink, lineage, null);
    }
    ProjectAgentTemporaryStateStore(AgentStateStore delegate, KernelScopeKey.Scope scope,
            ProjectAgentEventSink sink, ProjectAgentChildLineageRegistry lineage, String rawSessionId) {
        this.delegate = delegate; this.scope = scope; this.sink = sink; this.lineage = lineage;
        this.rawSessionId = rawSessionId;
    }
    private <T> T access(String user, String session, Supplier<T> action) {
        validateSlot(user, session);
        // 先锁handle/原run，再锁store；与终态cleanup同一锁顺序，避免晚写与清理互锁。
        return sink.withActiveOwnership(() -> {
            synchronized (monitor) {
                if (sealed) throw new IllegalStateException("temporary checkpoint already released");
                ownedSessions.add(storageSession(user, session));
                return action.get();
            }
        });
    }
    private boolean ownsSession(String session) {
        return Objects.equals(session, scope.sessionId())
            || Objects.equals(session, rawSessionId)
            || session != null && session.startsWith("sandbox/session/")
                && ownsSession(session.substring("sandbox/session/".length()))
            || lineage != null && lineage.ownsSession(scope.userId(), session);
    }
    private void validateSlot(String user, String session) {
        boolean agentSlot = Objects.equals(user, scope.userId()) && ownsSession(session);
        // 官方无参 getAgentState()（user=null）与 AG-UI 运行时身份（user=裸 runId）：session 拥有权
        // 已把住隔离（雪花 runId 全局唯一），user 三态放行只接纳本 run 自身的官方内部访问。
        boolean officialSlot = (user == null || Objects.equals(user, rawSessionId)) && ownsSession(session);
        if (!agentSlot && !officialSlot) throw new IllegalArgumentException("temporary checkpoint scope mismatch");
    }
    private String storageSession(String user, String session) {
        if (Objects.equals(user, scope.userId()) && Objects.equals(session, scope.sessionId())) return session;
        return scope.sessionId() + "/official/" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(session.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public boolean supportsVersioning() { return delegate.supportsVersioning(); }
    public void save(String u, String s, String key, State state) {
        access(u, s, () -> {
            if ("agent_state".equals(key) && delegate.supportsVersioning())
                throw new IllegalStateException("temporary checkpoint requires native CAS");
            delegate.save(scope.userId(), storageSession(u, s), key, state); return null;
        });
    }
    public void save(String u, String s, String key, List<? extends State> states) {
        access(u, s, () -> { delegate.save(scope.userId(), storageSession(u, s), key, states); return null; });
    }
    public long saveIfVersion(String u, String s, String key, State state, long version) {
        return access(u, s, () -> {
            if (delegate.supportsVersioning() && version == UNVERSIONED)
                throw new IllegalStateException("temporary checkpoint refuses unconditional overwrite");
            long saved = delegate.saveIfVersion(scope.userId(), storageSession(u, s), key, state, version);
            if (delegate.supportsVersioning() && saved == UNVERSIONED)
                throw new IllegalStateException("temporary checkpoint native CAS conflict");
            return saved;
        });
    }
    public <T extends State> VersionedState<T> getVersioned(String u, String s, String key, Class<T> type) {
        return access(u, s, () -> delegate.getVersioned(scope.userId(), storageSession(u, s), key, type));
    }
    public <T extends State> Optional<T> get(String u, String s, String key, Class<T> type) {
        return access(u, s, () -> delegate.get(scope.userId(), storageSession(u, s), key, type));
    }
    public <T extends State> List<T> getList(String u, String s, String key, Class<T> type) {
        return access(u, s, () -> delegate.getList(scope.userId(), storageSession(u, s), key, type));
    }
    public boolean exists(String u, String s) { return access(u, s, () -> delegate.exists(scope.userId(), storageSession(u, s))); }
    public void delete(String u, String s) { access(u, s, () -> { delegate.delete(scope.userId(), storageSession(u, s)); return null; }); }
    public Set<String> listSessionIds(String u) {
        return access(u, scope.sessionId(), () -> delegate.exists(scope.userId(), storageSession(u, scope.sessionId()))
            ? Set.of(scope.sessionId()) : Set.of());
    }
    /** 调用者必须持有该run的epoch事务；删除失败允许同一终态事务重试，sealed永不撤回。 */
    void sealAndDelete() {
        synchronized (monitor) {
            sealed = true;
            ownedSessions.add(scope.sessionId());
            for (String session : ownedSessions) delegate.delete(scope.userId(), session);
        }
    }
    /** 共享原生store/RedissonClient由配置管理，不随单run关闭。 */
    public void close() { }
}
