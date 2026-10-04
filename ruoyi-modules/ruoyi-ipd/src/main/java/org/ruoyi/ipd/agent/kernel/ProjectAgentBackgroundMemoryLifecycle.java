package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.session.SessionTree;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import reactor.core.publisher.Flux;

/** One run's official background IO receipts; no memory store or business authority. */
public final class ProjectAgentBackgroundMemoryLifecycle implements MiddlewareBase {
    @FunctionalInterface interface Hydration { void accept(InputStream archive) throws Exception; }
    /**
     * 排空预算（秒）。旧值 10 秒与健康调用的实测耗时上限<b>完全重合</b>——按 modelCallId 精确配对
     * START/END 统计的 9 次成功后台调用：3s/3s/4s/5s/5s/5s/<b>9s</b>/<b>10s</b>/10s，即 2 次耗时
     * 已经等于或超过 10 秒预算，另有 3 次达到 9 秒。健康但偏慢的调用会被误判成「未排空」，
     * 产生一条与事实相反的 STREAM_ERROR。
     *
     * <p>提到 30 秒不是放宽校验：这道门禁守的是<b>归档完整性</b>（{@link #verifyArchive} 逐字节
     * 比对会话与上传物），不能因为「让运行过」就放松。给足余量是为了让门禁只在真的没排空时才响。
     *
     * <p>残留边界：{@code MemoryBackgroundTasks} 与 {@code SessionTree} 的 await 在 2.0.3 是
     * <b>进程级</b>静态计数器，没有 per-run 句柄（javap 实测），并发运行会互相拖累。
     * 每运行独立的等待/取消句柄在 2.0.3 无法实现，未在本条内解决。
     */
    static final int QUIESCE_BUDGET_SECONDS = 30;

    /**
     * 被委派出去、尚未跑完的子智能体调用在归档固化前必须先收口（秒）。
     *
     * <p>这不是「多等一会儿」的时间参数，而是<b>有明确终止条件</b>的等待：{@link #liveNestedSessions}
     * 里每一条都在某个子调用真正结束时才被移除，归档门禁等到集合为空（或超时）才继续。
     * 与 {@link #QUIESCE_BUDGET_SECONDS} 的区别在于：排空预算是<b>盲等</b>一个时长，
     * 这里的等待是<b>盯着真实未完成集合</b>，绝大多数情况下瞬间返回。
     *
     * <p>仍然有上界，因此<b>不是根治</b>：子智能体若跑超过本预算，运行仍会以
     * 「子调用未在归档前收口」失败——这是有意的，运行宁可响亮地失败，
     * 也不能产出一个缺子智能体会话记录的未核验归档。
     * 彻底方案（子运行独立落库 / 独立终态）属架构改动，已在 /tmp/fix-subagent-lifetime.md 登记为技术债。
     */
    static final int NESTED_CALL_BUDGET_SECONDS = 120;

    private final Map<String, Map<String, String>> uploads = new HashMap<>();
    private final Map<String, Map<String, Path>> sessions = new HashMap<>();
    /**
     * 已登记但尚未收口的被委派子调用。根沙箱在 {@link #prepareArchive} 等它清空，
     * 然后由 SDK 生成归档；不能等到 snapshot.persist 才等待已固化内容。
     *
     * <p>实证（runId 2106436968471633922 / 2106443323328794625）：子智能体跑了 65.3s / 47.7s，
     * 主运行在其结束前 1 秒就固化并核验了归档，{@code verifyArchive} 按设计拒绝
     * （子智能体会话既不在归档也不在主机暂存），整轮因此改判 FAILED / STREAM_ERROR。
     * 单纯调大 {@link #QUIESCE_BUDGET_SECONDS}（已实测 30→120）对本故障毫无作用。
     */
    private final java.util.Set<String> liveNestedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private record ArchiveCall(boolean root, io.agentscope.harness.agent.sandbox.SandboxAcquireResult acquired) { }
    private final java.util.concurrent.ConcurrentMap<String, ArchiveCall> archiveCalls = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong callIds = new java.util.concurrent.atomic.AtomicLong();
    private volatile io.agentscope.harness.agent.workspace.WorkspaceManager workspace;
    private Throwable failure;

    public void bindWorkspace(io.agentscope.harness.agent.workspace.WorkspaceManager manager) {
        workspace = java.util.Objects.requireNonNull(manager);
    }

    private void requireCallSession(Agent agent, RuntimeContext context) {
        if (context == null) return;
        var acquired = context.get(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class);
        if (acquired == null) return; // Standalone/local middleware probes do not own a sandbox.
        var state = acquired.getSandbox().getState();
        String id = state.getSnapshot() == null ? state.getSessionId() : state.getSnapshot().getId();
        String name = agentName(agent);
        String session = context.getSessionId();
        if (name == null || name.isBlank() || session == null || session.isBlank()) {
            var error = new IllegalStateException("Official call session identity is missing");
            recordFailure(error); throw error;
        }
        boolean root = org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID.equals(name);
        if (id == null || id.isBlank()) {
            var error = new IllegalStateException("Official archive identity is missing");
            recordFailure(error); throw error;
        }
        var call = new ArchiveCall(root, acquired);
        ArchiveCall previous = archiveCalls.putIfAbsent(id, call);
        // SDK 2.0.3 shared subagents inherit the parent's exact acquisition through
        // RuntimeContext.builder(parent). They borrow its filesystem, not its lifecycle.
        if (previous != null && previous.root() != root && previous.acquired() != acquired) {
            var error = new IllegalStateException("Root and child archive identities overlap");
            recordFailure(error); throw error;
        }
        if (previous != null && root && !previous.root()) archiveCalls.replace(id, previous, call);
        Path source = workspace == null ? null : workspace.resolveSessionLogFile(context, name, session);
        // Official SessionTranscriptWriter supplies SessionTree a path WITHOUT the local namespace.
        // The local spool remains namespaced and must match the actual remote bytes.
        String relative = io.agentscope.harness.agent.workspace.WorkspaceConstants.AGENTS_DIR + "/" + name
            + "/" + io.agentscope.harness.agent.workspace.WorkspaceConstants.SESSIONS_DIR + "/" + session
            + io.agentscope.harness.agent.workspace.WorkspaceConstants.SESSION_LOG_EXT;
        synchronized (this) { sessions.computeIfAbsent(id, ignored -> new HashMap<>()).put(relative, source); }
    }

    /** 与 SDK 子智能体日志里的 agentId 一致：根为 ipd_project_agent，子为 general-purpose-subagent。 */
    private static String agentName(Agent agent) {
        if (agent instanceof io.agentscope.harness.agent.HarnessAgent harness
            && harness.getAgentId() != null && !harness.getAgentId().isBlank()) return harness.getAgentId();
        return agent == null ? null : agent.getName();
    }

    /**
     * 登记一次调用；返回的句柄在该调用真正结束时（完成、报错或取消）解除登记，且只解除一次。
     * 根调用不登记：归档固化时根本来就在栈上，把它算成「待收口」会让门禁永远等自己。
     */
    private NestedCall beginCall(Agent agent, RuntimeContext context) {
        String name = agentName(agent);
        if (name == null || org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID.equals(name)) return NestedCall.NONE;
        String session = context == null ? null : context.getSessionId();
        String key = session == null || session.isBlank()
            ? name + "/unknown" : name + "/" + session;
        // Session identity can repeat across concurrent calls; settlement belongs to one subscription.
        key += "#" + callIds.incrementAndGet();
        liveNestedSessions.add(key);
        return new NestedCall(key);
    }

    private static final class NestedCall {
        static final NestedCall NONE = new NestedCall(null);
        private final String key;
        private final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();
        private NestedCall(String key) { this.key = key; }
        void settle(java.util.Set<String> live) { if (key != null && settled.compareAndSet(false, true)) live.remove(key); }
    }

    /**
     * 等所有被委派的子调用收口后再放行归档固化。
     *
     * <p>等待期间不睡死循环以外的东西：绝大多数调用本就已经收口，第一次检查即返回。
     * 超时按「子调用未在归档前收口」失败，错误里带上仍未收口的会话，便于定位。
     */
    public void awaitNestedCallsSettled() {
        awaitNestedCallsSettled(NESTED_CALL_BUDGET_SECONDS);
    }

    /** Called before SDK tar generation; a child's archive must not wait for its siblings. */
    void prepareArchive(String archiveId) {
        ArchiveCall call = archiveId == null ? null : archiveCalls.get(archiveId);
        if (call == null) {
            var error = new IllegalStateException("Official archive call identity was not registered");
            recordFailure(error); throw error;
        }
        if (call.root()) {
            awaitNestedCallsSettled();
            drain();
        }
        requireHealthy();
    }

    /** 测试与诊断用：显式指定等待预算（秒）。生产走 {@link #NESTED_CALL_BUDGET_SECONDS}。 */
    void awaitNestedCallsSettled(long budgetSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(budgetSeconds);
        while (true) {
            var pending = java.util.Set.copyOf(liveNestedSessions);
            if (pending.isEmpty()) return;
            if (System.nanoTime() - deadline >= 0L) {
                var error = new IllegalStateException(
                    "Delegated subagent call did not settle before sandbox release: pendingSessions=" + pending);
                recordFailure(error); throw error;
            }
            try { Thread.sleep(50L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                var error = new IllegalStateException("Interrupted while awaiting delegated subagent calls", interrupted);
                recordFailure(error); throw error;
            }
        }
    }

    /** 当前仍未收口的被委派子调用；供测试与诊断读取。 */
    java.util.Set<String> liveNestedSessions() { return java.util.Set.copyOf(liveNestedSessions); }

    synchronized void recordFailure(Throwable error) {
        if (failure == null) failure = error;
        else if (failure != error && error.getCause() != failure) failure.addSuppressed(error);
    }
    public synchronized boolean failed() { return failure != null; }
    public synchronized void requireHealthy() {
        if (failure != null) throw new IllegalStateException("Official background IO receipt failed", failure);
    }

    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            requireCallSession(agent, context);
            var call = beginCall(agent, context);
            // Registered before official hooks: their dispatch completes before this outer terminal gate.
            return Flux.defer(() -> next.apply(input)).materialize().concatMap(signal -> {
                if (signal.isOnComplete() || signal.isOnError()) {
                    // 先解除登记再排空：根运行的终态门禁要把子调用一并等进来，子调用自己不能被自己等住。
                    call.settle(liveNestedSessions);
                    try { drain(); }
                    catch (RuntimeException ioFailure) {
                        if (signal.isOnError()) {
                            Throwable original = signal.getThrowable();
                            if (original != ioFailure) original.addSuppressed(ioFailure);
                            return reactor.core.publisher.Mono.just(signal);
                        }
                        return reactor.core.publisher.Mono.error(ioFailure);
                    }
                }
                return reactor.core.publisher.Mono.just(signal);
            }).<AgentEvent>dematerialize().transform(reactor.core.publisher.Operators.<AgentEvent, AgentEvent>lift((source, downstream) ->
                new reactor.core.CoreSubscriber<AgentEvent>() {
                    public reactor.util.context.Context currentContext() { return downstream.currentContext(); }
                    public void onSubscribe(org.reactivestreams.Subscription upstream) {
                        downstream.onSubscribe(new org.reactivestreams.Subscription() {
                            public void request(long count) { upstream.request(count); }
                            public void cancel() {
                                // Stop the producer first; drain still finishes inside the call before outer SDK release.
                                try { upstream.cancel(); } catch (RuntimeException cancelFailure) { recordFailure(cancelFailure); }
                                finally {
                                    call.settle(liveNestedSessions);
                                    try { drain(); } catch (RuntimeException ignored) { /* sticky receipt protects checkpoint */ }
                                }
                            }
                        });
                    }
                    public void onNext(AgentEvent event) { downstream.onNext(event); }
                    public void onError(Throwable error) { downstream.onError(error); }
                    public void onComplete() { downstream.onComplete(); }
                }));
        });
    }

    public void drain() {
        try {
            if (!SessionTree.awaitMirrorQuiescence(QUIESCE_BUDGET_SECONDS, TimeUnit.SECONDS)
                    || !MemoryBackgroundTasks.awaitQuiescence(QUIESCE_BUDGET_SECONDS, TimeUnit.SECONDS)
                    || !SessionTree.awaitMirrorQuiescence(QUIESCE_BUDGET_SECONDS, TimeUnit.SECONDS))
                throw new IllegalStateException("Official background IO did not quiesce before release");
            requireHealthy();
        } catch (RuntimeException error) { recordFailure(error); throw error; }
    }

    /** Explicit official memory model override, transparently reusing the already metered model. */
    public MemoryConfig memoryConfig(MemoryConfig config, Model model) {
        Model observer = new Model() {
            public String getModelName() { return model.getModelName(); }
            public int getContextWindowSize() { return model.getContextWindowSize(); }
            public boolean supportsNativeStructuredOutput() { return model.supportsNativeStructuredOutput(); }
            public boolean supportsNativeStructuredOutputWithTools() { return model.supportsNativeStructuredOutputWithTools(); }
            public Flux<io.agentscope.core.model.ChatResponse> stream(
                    java.util.List<io.agentscope.core.message.Msg> messages,
                    java.util.List<io.agentscope.core.model.ToolSchema> tools,
                    io.agentscope.core.model.GenerateOptions options) {
                return Flux.defer(() -> model.stream(messages, tools, options)).doOnError(ProjectAgentBackgroundMemoryLifecycle.this::recordFailure);
            }
        };
        return MemoryConfig.builder().model(observer).flushPrompt(config.flushPrompt())
            .consolidationPrompt(config.consolidationPrompt()).consolidationMaxTokens(config.consolidationMaxTokens())
            .consolidationMinGap(config.consolidationMinGap()).dailyFileRetentionDays(config.dailyFileRetentionDays())
            .sessionRetentionDays(config.sessionRetentionDays()).flushTrigger(config.flushTrigger()).build();
    }

    /** Observe actual official archive upload input and result, even if SDK later swallows its error. */
    void hydrate(String snapshotId, InputStream archive, Hydration operation) throws Exception {
        Path scratch = null;
        try {
            scratch = Files.createTempFile("ipd-official-hydrate-", ".tar");
            Files.copy(archive, scratch, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Map<String, String> expected;
            try (var input = Files.newInputStream(scratch)) { expected = digests(input); }
            try (var input = Files.newInputStream(scratch)) { operation.accept(input); }
            synchronized (this) { uploads.computeIfAbsent(snapshotId, ignored -> new HashMap<>()).putAll(expected); }
        } catch (Exception error) { recordFailure(error); throw error; }
        finally {
            if (scratch != null) try { Files.deleteIfExists(scratch); }
            catch (java.io.IOException cleanupFailure) { recordFailure(cleanupFailure); throw cleanupFailure; }
        }
    }

    void verifyArchive(String snapshotId, InputStream archive) throws Exception {
        requireHealthy();
        Map<String, String> expected;
        synchronized (this) { expected = Map.copyOf(uploads.getOrDefault(snapshotId, Map.of())); }
        Map<String, Path> required;
        synchronized (this) { required = new HashMap<>(sessions.getOrDefault(snapshotId, Map.of())); }
        if (expected.isEmpty() && required.isEmpty()) return;
        Map<String, String> actual = digests(archive);
        for (var session : required.entrySet()) {
            String digest = actual.get(session.getKey());
            if (digest == null || session.getValue() != null && (!Files.isRegularFile(session.getValue())
                    || Files.isSymbolicLink(session.getValue()) || !digest.equals(fileDigest(session.getValue())))) {
                var error = new IllegalStateException("Official call session missing or changed in released archive: required=" + session.getKey() + ", sourceExists=" + (session.getValue() != null && Files.isRegularFile(session.getValue())) + ", archivePresent=" + (digest != null));
                recordFailure(error); throw error;
            }
        }
        for (var entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                var error = new IllegalStateException("Official background upload absent or changed in released archive");
                recordFailure(error); throw error;
            }
        }
    }

    private static String fileDigest(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new java.security.DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, String> digests(InputStream input) throws Exception {
        var result = new HashMap<String, String>();
        try (var tar = new TarArchiveInputStream(input)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("./")) name = name.substring(2);
                if (name.startsWith("/workspace/")) name = name.substring("/workspace/".length());
                else if (name.startsWith("workspace/")) name = name.substring("workspace/".length());
                if (!entry.isFile() || !tracked(name)) continue;
                var digest = MessageDigest.getInstance("SHA-256"); int read;
                while ((read = tar.read(buffer)) != -1) digest.update(buffer, 0, read);
                result.put(name, HexFormat.of().formatHex(digest.digest()));
            }
        }
        return result;
    }
    private static boolean tracked(String path) {
        return path.matches("(?:.*/)?memory/[^/]+\\.md") || path.matches("(?:.*/)?MEMORY\\.md")
            || path.matches("(?:.*/)?agents/[^/]+/sessions/[^/]+(?:\\.jsonl|\\.json)");
    }
}
