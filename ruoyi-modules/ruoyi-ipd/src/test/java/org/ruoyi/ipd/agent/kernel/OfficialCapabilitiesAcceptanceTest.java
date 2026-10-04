package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.tool.FilesystemTool;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Docker effects and deterministic permission isolation; no model/network mock acceptance. */
@Tag("dev")
class OfficialCapabilitiesAcceptanceTest {
    @TempDir Path root;
    private final String acceptanceRun = "acceptance-run-" + java.util.UUID.randomUUID();
    private HarnessAgent harness;
    private InMemoryAgentStateStore harnessStore;
    private final java.util.concurrent.atomic.AtomicReference<ToolUseBlock> nextCall = new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicInteger modelResponses = new AtomicInteger();
    private String actualContainerId;
    private final java.util.Set<String> actualContainerIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean realContainerWriteVerified;

    @Test
    void callScopedPermissionCannotBorrowAnotherSessionsAuthorization() {
        ToolBase delegate = mock(ToolBase.class);
        when(delegate.getName()).thenReturn("isolated_write");
        when(delegate.getDescription()).thenReturn("A protected write");
        when(delegate.getParameters()).thenReturn(Map.of("type", "object"));
        when(delegate.checkPermissions(any(), any())).thenReturn(Mono.just(PermissionDecision.passthrough("native")));
        when(delegate.callAsync(any())).thenReturn(Mono.just(ToolResultBlock.text("unexpected write")));
        Agent agent = mock(Agent.class);
        when(agent.getAgentState()).thenReturn(AgentState.builder().permissionContext(
            PermissionContextState.builder().mode(PermissionMode.BYPASS).build()).build());
        RuntimeContext context = RuntimeContext.builder().userId("person-a").sessionId("run-a").build();
        context.setAgentState(AgentState.builder().permissionContext(
            PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build()).build());
        var tool = new ProjectAgentOfficialToolGovernance.OwnedTool(delegate, mock(ProjectAgentEventSink.class));
        assertThrows(RuntimeException.class, () -> tool.callAsync(param("isolated_write", Map.of(), agent, context))
            .block(Duration.ofSeconds(3)));
        verify(delegate, never()).callAsync(any());
    }

    @Test
    void authorizedNativeWriteReadAndShellHaveRealContainerEffectsAndCleanup() throws Exception {
        // Missing daemon/image is a real failed prerequisite, not a skipped green test.
        assertEquals(0, docker("image", "inspect", "python:3.13-alpine", "--format", "{{.Id}}").exit());
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path hostCanary = root.resolve("host-only-canary.txt");
        Files.writeString(hostCanary, "HOST_ONLY_ACCEPTANCE_CANARY");
        // The actual Harness below owns acquisition, snapshot persistence and release.
        // A separately created caller-managed sandbox has no registered call lifecycle.
        try {
            RuntimeContext context = RuntimeContext.builder().userId("acceptance-person").sessionId(acceptanceRun).build();
            var permission = PermissionContextState.builder().mode(PermissionMode.DONT_ASK);
            for (String name : new String[] {"write_file", "read_file", "execute"}) {
                // ToolBase's official matcher uses null for a tool-name-level approval;
                // a literal "*" is deliberately NOT a wildcard and never grants permission.
                permission.addAllowRule(name, new PermissionRule(name, null, PermissionBehavior.ALLOW, "test-approved"));
            }
            AgentState state = AgentState.builder().permissionContext(permission.build()).build();
            context.setAgentState(state);
            SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new FilesystemTool(filesystem));
            toolkit.registerTool(new ShellExecuteTool(filesystem));
            var sink = mock(ProjectAgentEventSink.class);
            // The helper executes through actual Harness onActing and ToolExecutor.
            String write = call(toolkit, sink, workspace, "write_file", Map.of("path", "reports/native.txt", "content", "NATIVE_REAL_CONTENT"), context);
            assertTrue(write.contains("Written to"), write);
            String read = call(toolkit, sink, workspace, "read_file", Map.of("path", "reports/native.txt"), context);
            assertTrue(read.contains("NATIVE_REAL_CONTENT"), read);
            assertTrue(realContainerWriteVerified, "container effect must be checked before official release");
            // The original command contract allows finite file-based Python, not shell chains or inline source.
            // Create the probe through the actual official file tool so execution still proves durable container effects.
            String probe = "from pathlib import Path\nimport sys\n"
                + "print(Path('reports/native.txt').read_text())\nprint('REAL_SHELL_MARKER')\n"
                + "if len(sys.argv) > 1:\n    assert not Path(sys.argv[1]).exists(), 'host canary unexpectedly mounted'\n"
                + "    print('HOST_NOT_MOUNTED')\n";
            String probeWrite = call(toolkit, sink, workspace, "write_file",
                Map.of("path", "reports/native_probe.py", "content", probe), context);
            assertTrue(probeWrite.contains("Written to"), probeWrite);
            String shell = call(toolkit, sink, workspace, "execute", Map.of("command", "python3 reports/native_probe.py"), context);
            assertTrue(shell.contains("Exit code: 0"), shell);
            assertTrue(shell.contains("NATIVE_REAL_CONTENT"), shell);
            assertTrue(shell.contains("REAL_SHELL_MARKER"), shell);
            String hostRead = call(toolkit, sink, workspace, "read_file", Map.of("path", hostCanary.toString()), context);
            assertFalse(hostRead.contains("HOST_ONLY_ACCEPTANCE_CANARY"), hostRead);
            String quotedHostCanary = "'" + hostCanary.toString().replace("'", "'\\''") + "'";
            String hostShell = call(toolkit, sink, workspace, "execute",
                Map.of("command", "python3 reports/native_probe.py " + quotedHostCanary), context);
            assertTrue(hostShell.contains("Exit code: 0"), hostShell);
            assertTrue(hostShell.contains("HOST_NOT_MOUNTED"), hostShell);
            assertEquals("HOST_ONLY_ACCEPTANCE_CANARY", Files.readString(hostCanary));
            verify(sink, atLeast(3)).requireActiveOwnership();
        } finally {
            if (harness != null) harness.close();
            assertFalse(actualContainerIds.isEmpty(), "official Harness must have acquired a real owned container");
            for (String ownedContainer : actualContainerIds) {
                CommandResult inspection = docker("inspect", "--format", "{{.State.Running}}", ownedContainer);
                assertNotEquals(0, inspection.exit(),
                    "official release/close must remove every container acquired by this test");
                assertTrue(inspection.output().toLowerCase(java.util.Locale.ROOT).contains("no such object"),
                    "cleanup must prove removal; daemon or command failure is not removal: " + inspection.output());
            }
        }
    }

    private String call(Toolkit toolkit, ProjectAgentEventSink sink, Path workspace, String name,
            Map<String, Object> input, RuntimeContext context) throws Exception {
        ToolUseBlock use = ToolUseBlock.builder().id("acceptance-" + java.util.UUID.randomUUID()).name(name)
            .content(new ObjectMapper().writeValueAsString(input)).input(input).build();
        nextCall.set(use);
        modelResponses.set(0);
        if (harness == null) {
            var model = mock(Model.class);
            when(model.getModelName()).thenReturn("docker-official-executor-contract");
            when(model.stream(any(), any(), any())).thenAnswer(invocation -> Flux.just(ChatResponse.builder()
                .finishReason(modelResponses.get() == 0 ? "tool_calls" : "stop")
                .content(modelResponses.getAndIncrement() == 0 ? List.of(nextCall.get()) : List.of(TextBlock.builder().text("SESSION_EVIDENCE_" + acceptanceRun).build()))
                .build()));
            harnessStore = new InMemoryAgentStateStore();
            var managedFilesystem = ProjectAgentOfficialSandbox.managedFilesystem(workspace, "python:3.13-alpine", sink);
            harness = HarnessAgent.builder().name("docker-contract").model(model).toolkit(toolkit)
                .middleware(managedFilesystem.lifecycle())
                .memory(managedFilesystem.lifecycle().memoryConfig(ProjectAgentNativeProfile.memory(), model))
                .middleware(new ProjectAgentOfficialToolGovernance(sink))
                .middleware(new io.agentscope.core.middleware.MiddlewareBase() {
                    @Override public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onActing(
                            Agent agent, RuntimeContext runtime, io.agentscope.core.middleware.ActingInput acting,
                            java.util.function.Function<io.agentscope.core.middleware.ActingInput, reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>> next) {
                        var acquired = runtime.get(SandboxAcquireResult.class);
                        actualContainerId = ((DockerSandboxState) acquired.getSandbox().getState()).getContainerId();
                        assertNotNull(actualContainerId, "official onActing must observe its actual sandbox");
                        actualContainerIds.add(actualContainerId);
                        return next.apply(acting).doOnComplete(() -> {
                            if ("write_file".equals(nextCall.get().getName())) {
                                try {
                                    CommandResult effect = docker("exec", actualContainerId, "cat", "/workspace/reports/native.txt");
                                    assertEquals(0, effect.exit(), effect.output());
                                    assertTrue(effect.output().contains("NATIVE_REAL_CONTENT"), effect.output());
                                    realContainerWriteVerified = true;
                                } catch (Exception exception) { throw new IllegalStateException(exception); }
                            }
                        });
                    }
                })
                .permissionContext(context.getAgentState().getPermissionContext())
                .filesystem(managedFilesystem.spec()).workspace(workspace)
                .stateStore(harnessStore).maxIters(3).build();
            managedFilesystem.lifecycle().bindWorkspace(harness.getWorkspaceManager());
        }
        RuntimeContext caller = RuntimeContext.builder().userId(context.getUserId()).sessionId(context.getSessionId()).build();
        {
            harness.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("execute acceptance operation").build()), caller)
                .collectList().block(Duration.ofSeconds(40));
            assertTrue(io.agentscope.harness.agent.memory.session.SessionTree.awaitMirrorQuiescence(10, TimeUnit.SECONDS),
                "actual session mirror must drain for " + acceptanceRun);
            try (var archives = Files.list(workspace.resolve(".sandbox-snapshots"))) {
                assertTrue(archives.filter(path -> path.getFileName().toString().endsWith(".tar")).anyMatch(path -> {
                    try {
                        String bytes = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
                        return bytes.contains(acceptanceRun + ".log.jsonl") && bytes.contains("SESSION_EVIDENCE_" + acceptanceRun);
                    } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                }), "actual session archive marker must persist for " + acceptanceRun);
            }
            var results = harnessStore.get(caller.getUserId(), caller.getSessionId(), "agent_state", AgentState.class).orElseThrow().getContext().stream().flatMap(msg -> msg.getContent().stream())
                .filter(block -> block instanceof ToolResultBlock).map(block -> (ToolResultBlock) block)
                .filter(result -> use.getId().equals(result.getId())).toList();
            assertEquals(1, results.size(), "actual SDK ToolExecutor must return exactly one tool result");
            return results.get(0).getOutput().stream()
                .map(block -> block instanceof TextBlock text ? text.getText() : block.toString())
                .collect(Collectors.joining("\n"));
        }
    }

    private static ToolCallParam param(String name, Map<String, Object> input, Agent agent, RuntimeContext context) {
        return ToolCallParam.builder().toolUseBlock(new ToolUseBlock("acceptance-" + name, name, input))
            .input(input).agent(agent).runtimeContext(context).build();
    }

    private record CommandResult(int exit, String output) { }
    private static CommandResult docker(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("docker");
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker verification timed out");
        }
        return new CommandResult(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
