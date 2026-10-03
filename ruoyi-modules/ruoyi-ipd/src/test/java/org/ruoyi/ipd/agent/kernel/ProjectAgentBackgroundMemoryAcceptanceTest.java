package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.session.SessionTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual SDK call and file readback: quiescence alone must never count as memory delivery. */
@Tag("dev")
class ProjectAgentBackgroundMemoryAcceptanceTest {
    @TempDir Path root;

    @Test void localSdkBackgroundFlushHasActualSessionAndMemoryBytes() throws Exception {
        verifyActualFiles(false, false);
    }

    @Test void officialSandboxBackgroundFlushHasActualSessionAndMemoryArchiveBytes() throws Exception {
        verifyActualFiles(true, false);
    }

    @Test void officialAroundCallCanDrainBeforeSandboxReleaseAndPreserveActualBytes() throws Exception {
        verifyActualFiles(true, true);
    }

    private void verifyActualFiles(boolean sandbox, boolean drainBeforeRelease) throws Exception {
        String run = "background-" + UUID.randomUUID();
        String agentName = "memory-contract-" + UUID.randomUUID();
        String conversationMarker = "SESSION_EVIDENCE_" + run;
        String memoryMarker = "MEMORY_EVIDENCE_" + run;
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        var releaseMemory = new CountDownLatch(1);
        var memoryRequested = new CountDownLatch(1);
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("deterministic-main-" + run);
        when(primary.stream(any(), any(), any())).thenReturn(reply(conversationMarker));
        Model memory = mock(Model.class);
        when(memory.getModelName()).thenReturn("deterministic-memory-" + run);
        // Bounded asynchronous extraction outlives primary reasoning; lifecycle must await actual IO.
        when(memory.stream(any(), any(), any())).thenAnswer(invocation -> Flux.defer(() -> {
            memoryRequested.countDown();
            return reply(memoryMarker).delaySubscription(Duration.ofMillis(300));
        }));
        var builder = HarnessAgent.builder().name(agentName).model(primary).workspace(workspace)
            .stateStore(new InMemoryAgentStateStore()).maxIters(2)
            .memory(MemoryConfig.builder().model(memory)
                .flushPrompt(ProjectAgentNativeProfile.memory().flushPrompt())
                .consolidationPrompt(ProjectAgentNativeProfile.memory().consolidationPrompt()).build());
        if (drainBeforeRelease) builder.middleware(new io.agentscope.core.middleware.MiddlewareBase() {
            @Override public Flux<io.agentscope.core.event.AgentEvent> onAgent(
                    io.agentscope.core.agent.Agent actualAgent, RuntimeContext callContext,
                    io.agentscope.core.middleware.AgentInput input,
                    java.util.function.Function<io.agentscope.core.middleware.AgentInput, Flux<io.agentscope.core.event.AgentEvent>> next) {
                return next.apply(input).doOnComplete(() -> {
                    // Test-only proof of the official extension ordering, not a production fix.
                    assertNotNull(callContext.get(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class),
                        "official sandbox must still be call-bound before drain: " + run);
                    releaseMemory.countDown();
                    assertTrue(SessionTree.awaitMirrorQuiescence(10, TimeUnit.SECONDS), "pre-release mirror drain: " + run);
                    assertTrue(MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS), "pre-release memory drain: " + run);
                });
            }
        });
        ProjectAgentOfficialSandbox.ManagedFilesystem managed = null;
        if (sandbox) {
            managed = ProjectAgentOfficialSandbox.managedFilesystem(workspace, "python:3.13-alpine", mock(ProjectAgentEventSink.class));
            builder.filesystem(managed.spec()).middleware(managed.lifecycle())
                .memory(managed.lifecycle().memoryConfig(ProjectAgentNativeProfile.memory(), memory));
        } else builder.filesystem(new LocalFilesystemSpec().project(workspace));
        try (var agent = builder.build()) {
            if (managed != null) managed.lifecycle().bindWorkspace(agent.getWorkspaceManager());
            var context = RuntimeContext.builder().userId("test-owner-" + run).sessionId(run).build();
            var events = agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER)
                .textContent(conversationMarker).build()), context).collectList().block(Duration.ofSeconds(40));
            assertNotNull(events, "actual SDK events: " + run);
            assertTrue(memoryRequested.await(5, TimeUnit.SECONDS), "actual memory model invoked: " + run);
            releaseMemory.countDown();
            assertTrue(SessionTree.awaitMirrorQuiescence(10, TimeUnit.SECONDS), "session mirror drained: " + run);
            assertTrue(MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS), "memory work drained: " + run);
            if (sandbox) {
                var receipts = managed.verifyReleased();
                assertFalse(receipts.isEmpty(), "actual snapshot receipts: " + run);
                String archive = archiveText(workspace.resolve(".sandbox-snapshots").resolve(receipts.get(0).snapshotId() + ".tar"));
                assertTrue(archive.contains(run + ".log.jsonl"), "actual session archive missing: " + run);
                assertTrue(archive.contains(conversationMarker), "session bytes missing: " + run);
                assertTrue(archive.contains("memory/" + LocalDate.now() + ".md"), "daily memory archive missing: " + run);
                assertTrue(archive.contains(memoryMarker), "actual memory bytes missing despite drained background task: " + run);
            } else {
                try (var files = Files.walk(workspace)) {
                    var memoryFiles = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals(LocalDate.now() + ".md")).toList();
                    assertFalse(memoryFiles.isEmpty(), "daily memory file missing: " + run);
                    assertTrue(memoryFiles.stream().anyMatch(p -> read(p).contains(memoryMarker)), "actual memory bytes: " + run);
                }
                try (var files = Files.walk(workspace)) {
                    var sessions = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals(run + ".log.jsonl")).toList();
                    assertFalse(sessions.isEmpty(), "session file missing: " + run);
                    assertTrue(sessions.stream().anyMatch(p -> read(p).contains(conversationMarker)), "actual session bytes: " + run);
                }
            }
        } finally { releaseMemory.countDown(); }
    }

    private static Flux<ChatResponse> reply(String text) {
        return Flux.just(ChatResponse.builder().finishReason("stop")
            .content(List.of(TextBlock.builder().text(text).build())).build());
    }
    private static String read(Path path) {
        try { return Files.readString(path); } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    // Raw tar bytes preserve filenames and UTF-8 markers; no extraction or executable archive contents.
    private static String archiveText(Path path) throws java.io.IOException {
        return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }
}
