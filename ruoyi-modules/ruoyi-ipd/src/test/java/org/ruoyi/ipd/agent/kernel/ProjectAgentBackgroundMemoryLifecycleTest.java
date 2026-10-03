package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectAgentBackgroundMemoryLifecycleTest {
    @TempDir Path root;
    private static final String MEMORY = "memory/2026-10-03.md";

    @Test void requiredSessionRejectsEmptyArchiveEvenWhenNoUploadReachedHydrate() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        var context = callContext(); Agent agent = mock(Agent.class); when(agent.getName()).thenReturn("root");
        owner.onAgent(agent, context, null, input -> Flux.empty()).blockLast();
        assertThrows(IllegalStateException.class, () -> owner.verifyArchive("one", new ByteArrayInputStream(archive(Map.of()))));
    }

    @Test void requiredSessionMatchesActualLocalSpoolNotStaleResumeArchive() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        var manager = new io.agentscope.harness.agent.workspace.WorkspaceManager(root, null, null,
            context -> java.util.List.of(context.getSessionId()));
        owner.bindWorkspace(manager); var context = callContext();
        var file = manager.resolveSessionLogFile(context, "root", "session");
        java.nio.file.Files.createDirectories(file.getParent()); java.nio.file.Files.writeString(file, "current invocation bytes");
        String relative = "agents/root/sessions/session.log.jsonl";
        assertEquals("session/" + relative, manager.getWorkspace().relativize(file).toString().replace('\\', '/'));
        Agent agent = mock(Agent.class); when(agent.getName()).thenReturn("root");
        owner.onAgent(agent, context, null, input -> Flux.empty()).blockLast();
        owner.verifyArchive("one", new ByteArrayInputStream(archive(Map.of(relative, "current invocation bytes"))));
        assertThrows(IllegalStateException.class, () -> owner.verifyArchive("one", new ByteArrayInputStream(archive(Map.of(relative, "previous invocation bytes")))));
    }

    /**
     * 委派出去的子智能体是「被委派的调用」，必须登记到归档固化之前收口。
     * 实证 runId 2106436968471633922：子智能体仍在写时归档被固化并核验，整轮改判 FAILED。
     */
    @Test void delegatedChildCallIsRegisteredUntilItActuallyFinishes() {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        var started = new CountDownLatch(1);
        Agent child = mock(Agent.class); when(child.getName()).thenReturn("general-purpose-subagent");
        owner.onAgent(child, callContext(), null, input -> reactor.core.publisher.Flux.concat(
                reactor.core.publisher.Mono.fromRunnable(started::countDown),
                reactor.core.publisher.Mono.delay(Duration.ofMillis(300)).thenMany(Flux.empty())))
            .subscribe();
        assertTrue(await(started));
        assertEquals(1, owner.liveNestedSessions().size(), "子智能体还在跑就必须在未收口集合里");
        owner.awaitNestedCallsSettled(30);
        assertTrue(owner.liveNestedSessions().isEmpty(), "子智能体跑完后必须从未收口集合里摘掉");
    }

    /** 根运行自己就在归档固化的调用栈上，算成待收口会让门禁永远等自己。 */
    @Test void rootCallIsNeverCountedAsPendingNestedWork() {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        Agent root = mock(Agent.class); when(root.getName()).thenReturn(org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID);
        var context = callContext();
        var release = new CountDownLatch(1);
        owner.onAgent(root, context, null, input -> Flux.<AgentEvent>never()
                .doOnSubscribe(ignored -> release.countDown()))
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .subscribe();
        assertTrue(owner.liveNestedSessions().isEmpty(), "根调用不得登记为未收口子调用");
        release.countDown();
    }

    /** 没有在途子调用时等待必须立即返回，否则每个正常运行都会被白等一个轮询周期。 */
    @Test void awaitingWithNoDelegatedWorkReturnsImmediately() {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        long startedAt = System.nanoTime();
        owner.awaitNestedCallsSettled(30);
        assertTrue(System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(1));
    }

    /** 超时必须响亮失败并点名仍在跑的子调用，绝不能放行一个缺子会话记录的归档。 */
    @Test void unSettledChildFailsLoudlyAndNamesThePendingSession() {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        Agent child = mock(Agent.class); when(child.getName()).thenReturn("general-purpose-subagent");
        owner.onAgent(child, callContext(), null, input -> Flux.<AgentEvent>never()).subscribe();
        var failure = assertThrows(IllegalStateException.class, () -> owner.awaitNestedCallsSettled(1));
        assertTrue(failure.getMessage().contains("did not settle before sandbox release"), failure.getMessage());
        assertTrue(failure.getMessage().contains("general-purpose-subagent/session"), failure.getMessage());
        assertTrue(owner.failed(), "超时必须记成粘性失败，让运行响亮地失败而不是产出未核验归档");
    }

    private static boolean await(CountDownLatch latch) {
        try { return latch.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    private static RuntimeContext callContext() {
        var state = new io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState(); state.setSessionId("one");
        var sandbox = mock(io.agentscope.harness.agent.sandbox.Sandbox.class); when(sandbox.getState()).thenReturn(state);
        var context = RuntimeContext.builder().userId("same-owner").sessionId("session").build();
        context.put(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class,
            io.agentscope.harness.agent.sandbox.SandboxAcquireResult.userManaged(sandbox));
        return context;
    }

    @Test void uploadedBytesMustExistUnchangedInReleasedArchive() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        byte[] archive = archive(Map.of(MEMORY, "actual isolated preference"));
        owner.hydrate("run-session", new ByteArrayInputStream(archive), input -> input.transferTo(java.io.OutputStream.nullOutputStream()));
        owner.verifyArchive("run-session", new ByteArrayInputStream(archive));
        assertThrows(IllegalStateException.class, () -> owner.verifyArchive("run-session",
            new ByteArrayInputStream(archive(Map.of(MEMORY, "changed")))));
        assertTrue(owner.failed());
    }

    @Test void latestAcceptedWriteReplacesRestoredDigestAndNormalizesOfficialTarPaths() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        owner.hydrate("one", new ByteArrayInputStream(archive(Map.of("./" + MEMORY, "restored"))), input -> input.transferTo(java.io.OutputStream.nullOutputStream()));
        owner.hydrate("one", new ByteArrayInputStream(archive(Map.of("/workspace/" + MEMORY, "new actual write"))), input -> input.transferTo(java.io.OutputStream.nullOutputStream()));
        owner.verifyArchive("one", new ByteArrayInputStream(archive(Map.of("./" + MEMORY, "new actual write"))));
        assertFalse(owner.failed());
    }

    @Test void missingUploadCannotBeBorrowedFromAnotherSession() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        owner.hydrate("one", new ByteArrayInputStream(archive(Map.of(MEMORY, "one"))), input -> input.transferTo(java.io.OutputStream.nullOutputStream()));
        owner.hydrate("two", new ByteArrayInputStream(archive(Map.of(MEMORY, "two"))), input -> input.transferTo(java.io.OutputStream.nullOutputStream()));
        assertThrows(IllegalStateException.class, () -> owner.verifyArchive("one", new ByteArrayInputStream(archive(Map.of(MEMORY, "two")))));
    }

    @Test void hydrateFailureRemainsStickyEvenWhenSdkConsumerWouldSwallowIt() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        var ioFailure = new java.io.IOException("synthetic upload failure");
        assertSame(ioFailure, assertThrows(java.io.IOException.class, () -> owner.hydrate("one",
            new ByteArrayInputStream(archive(Map.of(MEMORY, "one"))), input -> { throw ioFailure; })));
        assertThrows(IllegalStateException.class, owner::requireHealthy);
        var snapshots = new ProjectAgentVerifiedSnapshotSpec(root, owner);
        snapshots.build("one");
        assertThrows(IllegalStateException.class, snapshots::verifyReleased);
    }

    @Test void rejectedCompleteCandidateCannotReplacePreviousValidArchive() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        byte[] old = archive(Map.of(MEMORY, "accepted old memory"));
        owner.hydrate("one", new ByteArrayInputStream(old), input -> input.transferTo(java.io.OutputStream.nullOutputStream()));
        var snapshots = new ProjectAgentVerifiedSnapshotSpec(root, owner);
        var snapshot = snapshots.build("one");
        snapshot.persist(new ByteArrayInputStream(old));
        byte[] rejected = archive(Map.of("reports/other.txt", "complete but missing memory"));
        assertThrows(IllegalStateException.class, () -> snapshot.persist(new ByteArrayInputStream(rejected)));
        try (var restored = snapshot.restore()) { assertArrayEquals(old, restored.readAllBytes()); }
        assertThrows(IllegalStateException.class, snapshots::verifyReleased);
    }

    @Test void snapshotRejectsValidTarWithMissingExpectedFileBeforeReceipt() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        owner.hydrate("one", new ByteArrayInputStream(archive(Map.of(MEMORY, "one"))), input -> input.transferTo(java.io.OutputStream.nullOutputStream()));
        var snapshots = new ProjectAgentVerifiedSnapshotSpec(root, owner);
        assertThrows(IllegalStateException.class, () -> snapshots.build("one").persist(new ByteArrayInputStream(archive(Map.of()))));
        assertThrows(IllegalStateException.class, snapshots::verifyReleased);
    }

    @Test void completeAndSuspendedEventsAreNotReleasedBeforePendingIo() throws Exception {
        for (String outcome : new String[]{"success", "pause"}) {
            var owner = new ProjectAgentBackgroundMemoryLifecycle();
            AgentEvent event = new io.agentscope.core.event.AgentResultEvent(io.agentscope.core.message.Msg.builder()
                .role(io.agentscope.core.message.MsgRole.ASSISTANT).textContent("fixture " + outcome)
                .generateReason("pause".equals(outcome) ? io.agentscope.core.message.GenerateReason.TOOL_SUSPENDED
                    : io.agentscope.core.message.GenerateReason.MODEL_STOP).build());
            var written = new AtomicBoolean();
            MemoryBackgroundTasks.begin();
            Thread task = new Thread(() -> { written.set(true); MemoryBackgroundTasks.end(); }, "fixture-io-" + outcome);
            task.start();
            var delivered = owner.onAgent(mock(Agent.class), RuntimeContext.empty(), null, input -> Flux.just(event))
                .collectList().block(Duration.ofSeconds(3));
            assertTrue(written.get()); assertEquals(java.util.List.of(event), delivered);
            task.join();
        }
    }

    @Test void originalFailureSurvivesAndIoFailureIsSuppressed() {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        owner.recordFailure(new java.io.IOException("synthetic IO failure"));
        var original = new IllegalArgumentException("synthetic primary failure");
        var actual = assertThrows(IllegalArgumentException.class, () -> owner.onAgent(mock(Agent.class),
            RuntimeContext.empty(), null, input -> Flux.error(original)).blockLast(Duration.ofSeconds(3)));
        assertSame(original, actual);
        assertTrue(java.util.Arrays.stream(actual.getSuppressed()).anyMatch(e -> e instanceof IllegalStateException));
    }

    @Test void cancellationDrainsBeforeResourceReleaseAndCannotClearStickyFailure() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        var released = new AtomicBoolean(); var wroteWhileOwned = new AtomicBoolean();
        var producer = new CountDownLatch(1);
        MemoryBackgroundTasks.begin();
        Thread task = new Thread(() -> {
            try { producer.await(); wroteWhileOwned.set(!released.get()); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { MemoryBackgroundTasks.end(); }
        }, "fixture-cancel-io"); task.start();
        var subscription = Flux.using(() -> "same-call", ignored -> owner.onAgent(mock(Agent.class),
            RuntimeContext.empty(), null, input -> Flux.<AgentEvent>never().doOnCancel(producer::countDown)), ignored -> released.set(true)).subscribe();
        subscription.dispose(); task.join();
        assertTrue(wroteWhileOwned.get()); assertTrue(released.get()); assertFalse(owner.failed(), "producer cancellation must unblock pending IO before drain");
        owner.recordFailure(new java.io.IOException("cancelled IO failure"));
        assertThrows(IllegalStateException.class, owner::requireHealthy);
    }

    @Test void timeoutFailsClosedAndCancellationRetainsReceiptFailure() {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        try (var background = mockStatic(MemoryBackgroundTasks.class)) {
            background.when(() -> MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS)).thenReturn(false);
            assertThrows(IllegalStateException.class, () -> owner.onAgent(mock(Agent.class), RuntimeContext.empty(),
                null, input -> Flux.empty()).blockLast(Duration.ofSeconds(3)));
            assertTrue(owner.failed());
            var subscription = owner.onAgent(mock(Agent.class), RuntimeContext.empty(), null, input -> Flux.never()).subscribe();
            subscription.dispose();
            assertThrows(IllegalStateException.class, owner::requireHealthy);
        }
    }

    @Test void actualOfficialFactoryRecordsUploadFailureWithoutCallingDocker() throws Exception {
        var managed = ProjectAgentOfficialSandbox.managedFilesystem(root, "python:3.13-alpine", mock(ProjectAgentEventSink.class));
        var context = managed.spec().toSandboxContext(root);
        var client = (io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient) context.getClient();
        var sandbox = client.create(context.getWorkspaceSpec(), context.getSnapshotSpec(),
            (io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions) context.getClientOptions());
        var expected = new java.io.IOException("synthetic archive read failure");
        assertSame(expected, assertThrows(java.io.IOException.class, () -> sandbox.hydrateWorkspace(new java.io.InputStream() {
            public int read() throws java.io.IOException { throw expected; }
        })));
        assertTrue(managed.lifecycle().failed());
        assertThrows(IllegalStateException.class, managed::verifyReleased);
    }

    @Test void memoryModelIsTransparentButActualErrorsPreventSuccessfulReceipt() {
        var owner = new ProjectAgentBackgroundMemoryLifecycle(); Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("same-metered-model");
        var error = new IllegalArgumentException("synthetic extraction failure");
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.error(error));
        var original = ProjectAgentNativeProfile.memory(); var observed = owner.memoryConfig(original, delegate);
        assertEquals(original.flushPrompt(), observed.flushPrompt()); assertEquals(original.consolidationPrompt(), observed.consolidationPrompt());
        assertEquals("same-metered-model", observed.model().getModelName());
        assertSame(error, assertThrows(IllegalArgumentException.class, () -> observed.model().stream(java.util.List.of(), java.util.List.of(),
            io.agentscope.core.model.GenerateOptions.builder().build()).blockLast()));
        verify(delegate, times(1)).stream(any(), any(), any());
        assertThrows(IllegalStateException.class, owner::requireHealthy);
    }

    @Test void upstreamTimeoutSurvivesReceiptCleanupFailure() {
        var timeout = new java.util.concurrent.TimeoutException("original deadline");
        var receiptFailure = new IllegalStateException("archive receipt failed");
        var signal = AgentScopeProjectAgentKernel.<String, String>usingPreservingFailure(() -> "resource",
            resource -> Flux.error(timeout), resource -> { throw receiptFailure; }).materialize().blockLast();
        assertSame(timeout, signal.getThrowable());
        assertArrayEquals(new Throwable[]{receiptFailure}, timeout.getSuppressed());
    }

    @Test void synchronousSourceFailureSurvivesReceiptCleanupFailure() {
        var original = new IllegalStateException("synchronous source failed",
            new java.util.concurrent.TimeoutException("original deadline"));
        var receiptFailure = new IllegalStateException("archive receipt failed");
        var signal = AgentScopeProjectAgentKernel.<String, String>usingPreservingFailure(() -> "resource",
            resource -> { throw original; }, resource -> { throw receiptFailure; }).materialize().blockLast();
        assertSame(original, signal.getThrowable());
        assertArrayEquals(new Throwable[]{receiptFailure}, original.getSuppressed());
    }

    @Test void normalCompletionCannotHideReceiptCleanupFailure() {
        var receiptFailure = new IllegalStateException("archive receipt failed");
        var signal = AgentScopeProjectAgentKernel.<String, String>usingPreservingFailure(() -> "resource",
            resource -> Flux.just("result"), resource -> { throw receiptFailure; }).materialize().blockLast();
        assertSame(receiptFailure, signal.getThrowable());
    }

    @Test void stagingCleanupFailureIsStickyAndSuppressedOnOriginalFailure() throws Exception {
        var owner = new ProjectAgentBackgroundMemoryLifecycle();
        var snapshots = new ProjectAgentVerifiedSnapshotSpec(root, owner);
        var original = new java.io.IOException("staging input failed");
        var failure = assertThrows(io.agentscope.harness.agent.sandbox.SandboxException.SnapshotException.class,
            () -> snapshots.build("cleanup").persist(new java.io.InputStream() {
                public int read() throws java.io.IOException {
                    try (var paths = java.nio.file.Files.list(root)) {
                        var candidate = paths.filter(path -> path.getFileName().toString().startsWith(".ipd-snapshot-candidate-")).findFirst().orElseThrow();
                        java.nio.file.Files.delete(candidate);
                        java.nio.file.Files.createDirectory(candidate);
                        java.nio.file.Files.writeString(candidate.resolve("occupied"), "cleanup obstruction");
                    }
                    throw original;
                }
            }));
        assertSame(original, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(java.nio.file.DirectoryNotEmptyException.class, failure.getSuppressed()[0]);
        assertTrue(owner.failed());
        assertThrows(IllegalStateException.class, snapshots::verifyReleased);
    }

    private static byte[] archive(Map<String, String> contents) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(bytes)) {
            for (var item : contents.entrySet()) {
                byte[] content = item.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(item.getKey()); entry.setSize(content.length);
                tar.putArchiveEntry(entry); tar.write(content); tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
}
