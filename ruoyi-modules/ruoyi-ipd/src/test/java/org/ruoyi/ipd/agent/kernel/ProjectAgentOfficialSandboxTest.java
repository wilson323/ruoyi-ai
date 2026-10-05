package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.IsolationScope;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentOfficialSandboxTest {
    @org.junit.jupiter.api.io.TempDir Path workspace;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void rootWaitsBeforeSdkTarGenerationRatherThanAfterBytesAreFrozen(boolean withSnapshot) throws Exception {
        var managed = ProjectAgentOfficialSandbox.managedFilesystem(workspace, "python:3.13-alpine",
            org.mockito.Mockito.mock(ProjectAgentEventSink.class));
        var sandbox = resumedSandbox(managed, "root-archive", withSnapshot);
        var rootContext = io.agentscope.core.agent.RuntimeContext.builder().sessionId("root-call").build();
        rootContext.put(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class,
            io.agentscope.harness.agent.sandbox.SandboxAcquireResult.userManaged(sandbox));
        var root = org.mockito.Mockito.mock(io.agentscope.core.agent.Agent.class);
        org.mockito.Mockito.when(root.getName()).thenReturn(org.ruoyi.ipd.agent.ProjectAgentConstants.AGENT_ID);
        managed.lifecycle().onAgent(root, rootContext, null,
            ignored -> reactor.core.publisher.Flux.<io.agentscope.core.event.AgentEvent>empty()).blockLast();
        reactor.core.publisher.Sinks.Many<io.agentscope.core.event.AgentEvent> end =
            reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        var child = org.mockito.Mockito.mock(io.agentscope.core.agent.Agent.class);
        org.mockito.Mockito.when(child.getName()).thenReturn("general-purpose-subagent");
        var childContext = io.agentscope.core.agent.RuntimeContext.builder(rootContext).sessionId("borrowed-child-call").build();
        assertSame(rootContext.get(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class),
            childContext.get(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class));
        var running = managed.lifecycle().onAgent(child, childContext, null,
            ignored -> end.asFlux()).subscribe();
        assertEquals(1, managed.lifecycle().liveNestedSessions().size());
        var finished = java.util.concurrent.CompletableFuture.runAsync(() -> end.tryEmitComplete(),
            java.util.concurrent.CompletableFuture.delayedExecutor(150, java.util.concurrent.TimeUnit.MILLISECONDS));
        try (var docker = fakeTar(() -> managed.lifecycle().liveNestedSessions().isEmpty()
                ? "child-session-complete" : "child-session-missing")) {
            byte[] bytes;
            try (var archive = sandbox.persistWorkspace()) { bytes = archive.readAllBytes(); }
            try (var archive = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                    new java.io.ByteArrayInputStream(bytes))) {
                assertNotNull(archive.getNextEntry());
                assertEquals("child-session-complete", new String(archive.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    "SDK must generate tar after the delegated call completes");
            }
            assertEquals(1, docker.constructed().size());
            finished.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { running.dispose(); }
    }

    @Test void childSnapshotDoesNotWaitForAnUnfinishedSibling() throws Exception {
        var managed = ProjectAgentOfficialSandbox.managedFilesystem(workspace, "python:3.13-alpine",
            org.mockito.Mockito.mock(ProjectAgentEventSink.class));
        var sandbox = resumedSandbox(managed, "child-archive", true);
        var sibling = org.mockito.Mockito.mock(io.agentscope.core.agent.Agent.class);
        org.mockito.Mockito.when(sibling.getName()).thenReturn("general-purpose-subagent");
        var childContext = io.agentscope.core.agent.RuntimeContext.builder().sessionId("child-call").build();
        childContext.put(io.agentscope.harness.agent.sandbox.SandboxAcquireResult.class,
            io.agentscope.harness.agent.sandbox.SandboxAcquireResult.userManaged(sandbox));
        managed.lifecycle().onAgent(sibling, childContext, null,
            ignored -> reactor.core.publisher.Flux.<io.agentscope.core.event.AgentEvent>empty()).blockLast();
        var running = managed.lifecycle().onAgent(sibling, io.agentscope.core.agent.RuntimeContext.empty(), null,
            ignored -> reactor.core.publisher.Flux.<io.agentscope.core.event.AgentEvent>never()).subscribe();
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                try (var docker = fakeTar("agents/general-purpose-subagent/sessions/child-call.log.jsonl",
                        () -> "child-independent")) { sandbox.stop(); }
            });
            assertEquals(1, managed.lifecycle().liveNestedSessions().size(), "sibling remains active");
            assertEquals(1, managed.verifyReleased().size(), "child snapshot must be durably verified");
        } finally { running.dispose(); }
    }

    private io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox resumedSandbox(
            ProjectAgentOfficialSandbox.ManagedFilesystem managed, String id, boolean withSnapshot) {
        var context = managed.spec().toSandboxContext(workspace);
        var state = new io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState();
        state.setContainerId("mock-container"); state.setSessionId(id); state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(context.getWorkspaceSpec());
        if (withSnapshot) state.setSnapshot(managed.snapshots().build(id));
        return (io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox)
            ((io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient) context.getClient()).resume(state);
    }

    private static org.mockito.MockedConstruction<ProcessBuilder> fakeTar(java.util.function.Supplier<String> content) {
        return fakeTar("reports/child-session.txt", content);
    }

    private static org.mockito.MockedConstruction<ProcessBuilder> fakeTar(String path, java.util.function.Supplier<String> content) {
        return org.mockito.Mockito.mockConstruction(ProcessBuilder.class, (builder, construction) -> {
            var process = org.mockito.Mockito.mock(Process.class);
            org.mockito.Mockito.when(builder.start()).thenAnswer(ignored -> {
                var bytes = new java.io.ByteArrayOutputStream();
                try (var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bytes)) {
                    byte[] value = content.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(path);
                    entry.setSize(value.length); tar.putArchiveEntry(entry); tar.write(value); tar.closeArchiveEntry();
                }
                org.mockito.Mockito.when(process.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(bytes.toByteArray()));
                return process;
            });
            org.mockito.Mockito.when(process.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
            org.mockito.Mockito.when(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
            org.mockito.Mockito.when(process.exitValue()).thenReturn(0);
        });
    }

    @Test void requiresConcreteProviderAndRunScopedSnapshotWithoutHostFallback() {
        assertThrows(IllegalArgumentException.class,
            () -> ProjectAgentOfficialSandbox.filesystem(Path.of("/tmp/test-run"), "", org.mockito.Mockito.mock(ProjectAgentEventSink.class)));
        var spec = ProjectAgentOfficialSandbox.filesystem(Path.of("/tmp/test-run"), "python:3.13-alpine", org.mockito.Mockito.mock(ProjectAgentEventSink.class));
        assertEquals(IsolationScope.SESSION, spec.getIsolationScope());
        // toSandboxContext only constructs SDK provider metadata, never starts a container.
        var context = spec.toSandboxContext(Path.of("/tmp/test-run"));
        assertNotNull(context);
    }

    @Test void ownershipMustBeValidatedBeforeOfficialProviderAcquiresAnyContainer() throws Exception {
        var sink = org.mockito.Mockito.mock(ProjectAgentEventSink.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("ownership lost")).when(sink).requireActiveOwnership();
        var spec = ProjectAgentOfficialSandbox.filesystem(Path.of("/tmp/test-run"), "python:3.13-alpine", sink);
        assertThrows(IllegalStateException.class, () -> spec.getExecutionGuard().tryEnter(null));
    }
}
